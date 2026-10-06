package dev.dedworkshop.twitchcraft.game;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.ElderGuardian;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.Locale;

/**
 * События самой игры → события мода (тип GAME): смерть, достижения, боссы, смена измерения.
 * <p>
 * Как это ловится без миксинов:
 * <ul>
 *   <li>смерть — каждый тик смотрим на здоровье игрока / экран смерти; текст причины берём из сообщения
 *       сервера (в одиночной игре — напрямую из CombatTracker через событие Fabric AFTER_DEATH, на сервере —
 *       из системного сообщения «death.*» в чате);</li>
 *   <li>достижения — системные сообщения «chat.type.advancement.task|goal|challenge» (объявляются при
 *       включённом правиле announceAdvancements; подходят и достижения модов/датапаков);</li>
 *   <li>боссы — событие Fabric AFTER_DEATH встроенного сервера для дракона, иссушителя, вардена и древнего стража;</li>
 *   <li>измерение — изменение {@code level.dimension()} между тиками.</li>
 * </ul>
 */
public class GameEvents {
	private static final String ADVANCEMENT_KEY_PREFIX = "chat.type.advancement.";
	/** Сколько тиков после смерти ждать текст причины (сообщение чата и событие сервера приходят вразнобой). */
	private static final int DEATH_MESSAGE_WAIT_TICKS = 6;
	private static final long DEATH_MESSAGE_WINDOW_MS = 3000;

	private final TwitchCraftClient mod;
	private final GameStats stats;

	private String playerName = "";
	private long joinedAt;
	private boolean wasDead;
	private boolean pendingDeath;
	private int deathWaitTicks;
	private ResourceKey<Level> lastDimension;
	/** Текст из сообщения чата «death.*» (самый точный: учитывает «…пока сражался с…»). */
	private volatile String lastDeathMessage = "";
	private volatile long lastDeathMessageAt;
	/** Запасной текст из DamageSource на встроенном сервере (если сообщения о смерти выключены правилом). */
	private volatile String fallbackDeathMessage = "";
	private volatile long fallbackDeathMessageAt;

	public GameEvents(TwitchCraftClient mod, GameStats stats) {
		this.mod = mod;
		this.stats = stats;
	}

	public GameStats stats() {
		return stats;
	}

	/** Регистрирует слушатели Fabric. Вызывается один раз при старте мода. */
	public void register() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay) {
				return;
			}
			try {
				onGameMessage(message);
			} catch (Exception e) {
				TwitchCraftClient.LOGGER.warn("События игры: не удалось разобрать сообщение {}", message.getString(), e);
			}
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			try {
				onServerDeath(entity, source);
			} catch (Exception e) {
				TwitchCraftClient.LOGGER.warn("События игры: ошибка обработки смерти {}", entity.getType(), e);
			}
		});
	}

	public void onJoinWorld() {
		joinedAt = System.currentTimeMillis();
		wasDead = false;
		pendingDeath = false;
		lastDimension = null;
	}

	private boolean detectionEnabled() {
		return mod.isModuleEnabled(Module.GAME_EVENTS) || mod.isModuleEnabled(Module.CLIPS);
	}

	private boolean announceEnabled() {
		return mod.isModuleEnabled(Module.GAME_EVENTS);
	}

	private ModConfig.GameEventsSettings settings() {
		ModConfig.GameEventsSettings s = mod.config().gameEventsSettings;
		return s == null ? new ModConfig.GameEventsSettings() : s;
	}

	/** Каждый тик клиента. */
	public void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			joinedAt = 0;
			wasDead = false;
			pendingDeath = false;
			lastDimension = null;
			return;
		}
		long now = System.currentTimeMillis();
		if (joinedAt == 0) {
			joinedAt = now;
		}
		playerName = mc.player.getName().getString();
		boolean quiet = now - joinedAt < settings().quietSecondsAfterJoin * 1000L;

		// Смена измерения
		ResourceKey<Level> dimension = mc.level.dimension();
		if (lastDimension == null) {
			lastDimension = dimension;
		} else if (!dimension.equals(lastDimension)) {
			lastDimension = dimension;
			if (!quiet && announceEnabled()) {
				fireDimension(dimension);
			}
		}

		// Смерть: переход «жив → мёртв». Если вошли в мир уже мёртвыми (вышли на экране смерти) — не считаем.
		boolean dead = mc.player.isDeadOrDying() || mc.gui.screen() instanceof DeathScreen;
		if (dead && !wasDead) {
			wasDead = true;
			if (!quiet && detectionEnabled()) {
				pendingDeath = true;
				deathWaitTicks = DEATH_MESSAGE_WAIT_TICKS;
			}
		} else if (!dead) {
			wasDead = false;
		}
		if (pendingDeath && --deathWaitTicks <= 0) {
			pendingDeath = false;
			fireDeath(recentDeathMessage(now));
		}
	}

	/** Причина смерти: сообщение чата, иначе текст сервера, иначе пусто. */
	private String recentDeathMessage(long now) {
		if (now - lastDeathMessageAt < DEATH_MESSAGE_WINDOW_MS && !lastDeathMessage.isBlank()) {
			return lastDeathMessage;
		}
		if (now - fallbackDeathMessageAt < DEATH_MESSAGE_WINDOW_MS && !fallbackDeathMessage.isBlank()) {
			return fallbackDeathMessage;
		}
		return "";
	}

	// ---------- Источники текста ----------

	/** Системное сообщение от сервера: причина смерти или достижение. */
	void onGameMessage(Component message) {
		if (!(message.getContents() instanceof TranslatableContents contents)) {
			return;
		}
		String key = contents.getKey();
		Object[] args = contents.getArgs();
		if (key.startsWith("death.")) {
			// args[0] — кто погиб (может быть с префиксом команды); сообщения о гибели питомцев сюда не попадают
			if (args.length > 0 && !playerName.isEmpty() && isSameName(argText(args[0]), playerName)) {
				lastDeathMessage = message.getString();
				lastDeathMessageAt = System.currentTimeMillis();
			}
			return;
		}
		if (key.startsWith(ADVANCEMENT_KEY_PREFIX) && args.length >= 2) {
			String frame = key.substring(ADVANCEMENT_KEY_PREFIX.length()).toLowerCase(Locale.ROOT);
			String who = argText(args[0]);
			if (!who.equals(playerName) && !settings().otherPlayers) {
				return;
			}
			Component advancement = args[1] instanceof Component c ? c : Component.literal(String.valueOf(args[1]));
			Advancement parsed = parseAdvancement(advancement);
			fireAdvancement(frame, who, parsed.title(), parsed.description());
		}
	}

	/** Смерть любого живого существа на встроенном сервере (поток сервера!). */
	void onServerDeath(LivingEntity entity, DamageSource source) {
		if (entity.level().isClientSide()) {
			return;
		}
		if (entity instanceof ServerPlayer player) {
			// К этому моменту CombatTracker уже сброшен (recheckStatus в ServerPlayer.die), поэтому берём текст из DamageSource
			String name = player.getName().getString();
			Component message = source == null ? null : source.getLocalizedDeathMessage(player);
			String text = message == null ? "" : message.getString();
			Minecraft.getInstance().execute(() -> {
				if (name.equals(playerName)) {
					fallbackDeathMessage = text;
					fallbackDeathMessageAt = System.currentTimeMillis();
				}
			});
			return;
		}
		if (!isBoss(entity)) {
			return;
		}
		Component nameComponent = entity.hasCustomName() && entity.getCustomName() != null ? entity.getCustomName() : entity.getType().getDescription();
		String boss = nameComponent.getString();
		Entity killer = source == null ? null : source.getEntity();
		String killerName = killer == null ? "" : killer.getName().getString();
		boolean byPlayer = killer instanceof Player;
		Minecraft.getInstance().execute(() -> fireBoss(boss, killerName, byPlayer));
	}

	static boolean isBoss(LivingEntity entity) {
		return entity instanceof EnderDragon || entity instanceof WitherBoss || entity instanceof Warden || entity instanceof ElderGuardian;
	}

	// ---------- Генерация событий ----------

	private void fireDeath(String cause) {
		int number = stats.recordDeath(cause);
		if (mod.clips() != null) {
			mod.clips().onDeath(cause.isEmpty() ? "Смерть №" + number : cause);
		}
		if (announceEnabled()) {
			mod.onTwitchEvent(TwitchEvent.game(TwitchEvent.GAME_DEATH, playerName, cause, "", number, false));
		}
	}

	private void fireAdvancement(String frame, String who, String title, String description) {
		if (!announceEnabled()) {
			return;
		}
		int count = stats.recordAdvancement();
		String kind = switch (frame) {
			case "goal" -> TwitchEvent.GAME_ADVANCEMENT_GOAL;
			case "challenge" -> TwitchEvent.GAME_ADVANCEMENT_CHALLENGE;
			default -> TwitchEvent.GAME_ADVANCEMENT;
		};
		mod.onTwitchEvent(TwitchEvent.game(kind, who, title, description, count, false));
	}

	private void fireBoss(String boss, String killer, boolean byPlayer) {
		if (!detectionEnabled()) {
			return;
		}
		int count = stats.recordBoss();
		if (mod.clips() != null) {
			mod.clips().onBoss(boss);
		}
		if (announceEnabled()) {
			String who = byPlayer && !killer.isEmpty() ? killer : playerName;
			mod.onTwitchEvent(TwitchEvent.game(TwitchEvent.GAME_BOSS, who, boss, byPlayer ? "" : killer, count, false));
		}
	}

	private void fireDimension(ResourceKey<Level> dimension) {
		stats.recordDimensionChange();
		mod.onTwitchEvent(TwitchEvent.game(TwitchEvent.GAME_DIMENSION, playerName, dimensionName(dimension),
				dimension.identifier().toString(), stats.dimensionChanges, false));
	}

	/** Тестовое событие для /twitch game test и кнопок в настройках (счётчики не трогает). */
	public TwitchEvent testEvent(String kind) {
		String player = playerName.isEmpty() ? "Игрок" : playerName;
		return switch (kind == null ? "" : kind) {
			case TwitchEvent.GAME_ADVANCEMENT -> TwitchEvent.game(kind, player, "Каменный век", "Добудьте свою первую кирку", stats.advancements + 1, true);
			case TwitchEvent.GAME_ADVANCEMENT_GOAL -> TwitchEvent.game(kind, player, "Освободить Край", "Удачи!", stats.advancements + 1, true);
			case TwitchEvent.GAME_ADVANCEMENT_CHALLENGE -> TwitchEvent.game(kind, player, "Как мы сюда попали?", "Получите все эффекты одновременно", stats.advancements + 1, true);
			case TwitchEvent.GAME_BOSS -> TwitchEvent.game(kind, player, "Иссушитель", "", stats.bosses + 1, true);
			// События аддонов (боссы аддона «Артефакты») — публикуются через AddonContext.publish
			case TwitchEvent.GAME_BOSS_SPAWN -> TwitchEvent.game(kind, "Аддон «Артефакты»", "Кровавый Палач",
					"X 120 Z -340", 1, true);
			case TwitchEvent.GAME_BOSS_DEFEAT -> TwitchEvent.game(kind, player, "Кровавый Палач", player, 1, true);
			case TwitchEvent.GAME_DIMENSION -> TwitchEvent.game(kind, player, "Нижний мир", "minecraft:the_nether", stats.dimensionChanges + 1, true);
			default -> TwitchEvent.game(TwitchEvent.GAME_DEATH, player, player + " пытался(ась) поплавать в лаве", "", stats.deaths + 1, true);
		};
	}

	// ---------- Разбор ----------

	/** Название и описание достижения из компонента «[Название]» с подсказкой «Название\nОписание». */
	public record Advancement(String title, String description) {
	}

	public static Advancement parseAdvancement(Component component) {
		String title = stripBrackets(component.getString());
		String hover = findHoverText(component);
		String description = "";
		if (hover != null) {
			String text = hover.strip();
			int newline = text.indexOf('\n');
			if (newline >= 0) {
				String first = text.substring(0, newline).strip();
				String rest = text.substring(newline + 1).strip();
				description = first.equals(title) || title.isEmpty() ? rest : text.replace('\n', ' ').strip();
			} else if (!text.equals(title)) {
				description = text;
			}
		}
		return new Advancement(title, description.replace('\n', ' '));
	}

	private static String findHoverText(Component component) {
		if (component.getStyle() != null && component.getStyle().getHoverEvent() instanceof HoverEvent.ShowText show) {
			return show.value().getString();
		}
		for (Component sibling : component.getSiblings()) {
			String found = findHoverText(sibling);
			if (found != null) {
				return found;
			}
		}
		if (component.getContents() instanceof TranslatableContents contents) {
			for (Object arg : contents.getArgs()) {
				if (arg instanceof Component c) {
					String found = findHoverText(c);
					if (found != null) {
						return found;
					}
				}
			}
		}
		return null;
	}

	public static String stripBrackets(String text) {
		String t = text == null ? "" : text.strip();
		if (t.length() >= 2 && t.charAt(0) == '[' && t.charAt(t.length() - 1) == ']') {
			t = t.substring(1, t.length() - 1).strip();
		}
		return t;
	}

	/** Имя из сообщения совпадает с именем игрока (допускаем префикс/суффикс команды). */
	public static boolean isSameName(String fromMessage, String player) {
		String text = fromMessage == null ? "" : fromMessage.strip();
		return text.equals(player) || text.endsWith(" " + player) || text.startsWith(player + " ") || text.contains(" " + player + " ");
	}

	private static String argText(Object arg) {
		return arg instanceof Component c ? c.getString() : String.valueOf(arg);
	}

	/** Русское название измерения; для модовых — имя из id («aether» → «Aether»). */
	public static String dimensionName(ResourceKey<Level> dimension) {
		// Сравниваем по id, а не через Level.OVERWORLD и т.п., чтобы не трогать класс Level (тесты без игры)
		String id = dimension.identifier().toString();
		return switch (id) {
			case "minecraft:overworld" -> "Верхний мир";
			case "minecraft:the_nether" -> "Нижний мир";
			case "minecraft:the_end" -> "Край";
			default -> {
				String path = dimension.identifier().getPath().replace('_', ' ').replace('/', ' ').strip();
				yield path.isEmpty() ? id : Character.toUpperCase(path.charAt(0)) + path.substring(1);
			}
		};
	}
}
