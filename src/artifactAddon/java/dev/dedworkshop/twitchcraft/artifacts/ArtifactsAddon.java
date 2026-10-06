package dev.dedworkshop.twitchcraft.artifacts;

import dev.dedworkshop.twitchcraft.api.AddonAction;
import dev.dedworkshop.twitchcraft.api.AddonContext;
import dev.dedworkshop.twitchcraft.api.AddonElements;
import dev.dedworkshop.twitchcraft.api.AddonTrigger;
import dev.dedworkshop.twitchcraft.api.TwitchCraftAddon;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Аддон «Артефакты» — отдельный мод для TwitchCraft.
 *
 * Механики перенесены с сервера CHRDK REBORN (аддон vkchat_artifacts):
 *  - артефакты с баффами и проклятиями, 6 редкостей (мифический — без проклятия);
 *  - проклятие растёт, пока артефакт лежит в инвентаре: +1 % за 60 минут;
 *  - на 100 % артефакт рассыпается навсегда (предмет убирается из инвентаря);
 *  - не больше 5 артефактов на игрока; 35 именных артефактов из каталога.
 *
 * Отличие от сервера: выбивают артефакты зрители — за битсы, донаты, подписки,
 * подаренные подписки, рейды, награды за баллы канала и победы над боссами в игре.
 */
public final class ArtifactsAddon implements TwitchCraftAddon {
	public static final String ID = "artifacts";
	public static final String VERSION = "1.0.0";
	/** Как часто проверяем инвентарь (мс). */
	private static final long SCAN_INTERVAL_MS = 1000;
	/** Как часто «начисляем» проклятие (мс). */
	private static final long CURSE_STEP_MS = 5000;
	/** Как часто сохраняем состояние (мс). */
	private static final long SAVE_INTERVAL_MS = 60_000;
	/** Сколько ждать, прежде чем проверить, исчез ли предмет после команды удаления (мс). */
	private static final long DESTROY_VERIFY_MS = 1500;

	private static ArtifactsAddon instance;

	private AddonContext context;
	private ArtifactConfig config;
	private ArtifactStore store;
	private final ArtifactDrops drops = new ArtifactDrops();
	private final Random random = new Random();
	/** Боссы: расписание, навыки, победа (перенос BossManager серверного аддона). */
	private final BossManager bosses = new BossManager(this);

	/** Награды, привязанные к аддону по id (хук 3): их обрабатывает binding, а не общие правила. */
	private final Set<String> boundRewardIds = new LinkedHashSet<>();
	/** Зарегистрированы ли хуки (переменные/действия/триггеры регистрируются один раз). */
	private boolean hooksRegistered;
	private String lastArtifactName = "";

	private long lastScanAt;
	private long lastCurseAt;
	private long lastEffectsAt;
	private long lastSaveAt;

	public static ArtifactsAddon get() {
		return instance;
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String title() {
		return "Артефакты";
	}

	@Override
	public String version() {
		return VERSION;
	}

	@Override
	public void onReady(AddonContext context) {
		instance = this;
		this.context = context;
		Path dir = context.configDir();
		this.config = ArtifactConfig.load(dir);
		this.store = ArtifactStore.load(dir.resolve(ArtifactStore.FILE_NAME));
		ArtifactCommands.register(this);
		bosses.register();
		restoreBossState();
		registerHooks(context);
		bindRewards(context);
		context.logger().info("Аддон «Артефакты» {}: загружено (артефактов {}, выбито {}, разрушено {}, лимит {}, проклятие +1 % за {} мин)",
				VERSION, store.activeCount(), store.generated, store.destroyed, config.maxArtifacts, config.curse.intervalMinutes);
	}

	// ---------- Доступ для команд ----------

	public AddonContext context() {
		return context;
	}

	public ArtifactConfig config() {
		return config;
	}

	public ArtifactStore store() {
		return store;
	}

	public Random random() {
		return random;
	}

	/** Боссы: расписание, навыки, победа (команды {@code /artifact boss}). */
	public BossManager bosses() {
		return bosses;
	}

	/** Перечитать свой файл настроек. */
	public void reload() {
		this.config = ArtifactConfig.load(context.configDir());
		store.save();
		bindRewards(context); // награды могли поменяться — привязку по id обновляем
	}

	// ---------- События ----------

	@Override
	public void onEvent(TwitchEvent event) {
		if (event == null || config == null || !config.enabled) {
			return;
		}
		if (event.type() == TwitchEvent.Type.CHAT || event.type() == TwitchEvent.Type.CHAT_COMMAND) {
			return; // чат не выдаёт артефакты и не спамит объявлениями
		}
		if (event.synthetic() && !config.allowSynthetic) {
			return;
		}
		if (event.type() == TwitchEvent.Type.REWARD && boundRewardIds.contains(event.rewardId())) {
			return; // эту награду мод уже отдал аддону по id — не выдаём второй артефакт
		}
		int count;
		try {
			count = drops.countFor(event, config, random);
		} catch (Exception e) {
			context.logger().error("Аддон «Артефакты»: ошибка правил выдачи для события {}", event.type(), e);
			return;
		}
		for (int i = 0; i < count; i++) {
			drop(ArtifactDrops.sourceOf(event), event.user());
		}
	}

	/** Выдать артефакт зрителю (или вручную без зрителя). */
	public Artifact drop(String source, String viewer) {
		if (config == null) {
			return null;
		}
		return addAndGive(ArtifactFactory.create(random, config, source, viewer));
	}

	/** Выдать артефакт заданной редкости (команда {@code /artifact give <редкость>}). */
	public Artifact giveRarity(ArtifactRarity rarity, String viewer) {
		if (config == null) {
			return null;
		}
		return addAndGive(ArtifactFactory.create(random, config, rarity, "manual", viewer));
	}

	private Artifact addAndGive(Artifact artifact) {
		if (artifact != null) {
			lastArtifactName = artifact.name == null ? "" : artifact.name;
		}
		if (!store.canAdd(config.maxArtifacts)) {
			ArtifactChat.warn(ArtifactFactory.text(config.texts.full, ArtifactFactory.placeholders(artifact, config.maxArtifacts)));
			return null;
		}
		store.add(artifact, random);
		if (!context.runCommand(ArtifactFactory.giveCommand(artifact, config))) {
			ArtifactChat.warn("Игрок не в мире — предмет не выдан. Артефакт сохранён: §e/artifact held");
		}
		announce(artifact);
		return artifact;
	}

	private void announce(Artifact artifact) {
		Map<String, String> values = ArtifactFactory.placeholders(artifact, config.maxArtifacts);
		boolean mythic = artifact.mythic;
		String text = ArtifactFactory.text(mythic ? config.texts.mythic : config.texts.drop, values);
		boolean allowed = mythic ? config.announce.mythic : config.announce.drops;
		if (allowed) {
			context.announce(text, config.announce.toTwitch, config.announce.toVk);
		}
		ArtifactChat.success(text);
	}

	// ---------- Тик ----------

	@Override
	public void onTick(Minecraft client) {
		if (context == null || config == null) {
			return;
		}
		long now = System.currentTimeMillis();
		try {
			LocalPlayer player = client == null ? null : client.player;
			if (now - lastScanAt >= SCAN_INTERVAL_MS) {
				lastScanAt = now;
				scan(player);
				verifyDestroy(now);
			}
			if (now - lastCurseAt >= CURSE_STEP_MS) {
				long delta = lastCurseAt == 0 ? 0 : now - lastCurseAt;
				lastCurseAt = now;
				if (delta > 0) {
					handleBroken(store.advanceCurse(delta, config));
				}
			}
			if (config.applyEffects && player != null
					&& now - lastEffectsAt >= Math.max(5, config.effectRefreshSeconds) * 1000L) {
				lastEffectsAt = now;
				applyEffects();
			}
			bosses.tick(client);
			if (now - lastSaveAt >= SAVE_INTERVAL_MS) {
				lastSaveAt = now;
				store.save();
			}
		} catch (Throwable t) {
			// Ошибка аддона не должна ломать игру: пишем в лог и продолжаем
			context.logger().error("Аддон «Артефакты»: ошибка в тике", t);
		}
	}

	@Override
	public void onConfigChanged() {
		if (context == null) {
			return;
		}
		try {
			reload();
			context.logger().info("Аддон «Артефакты»: настройки перечитаны");
		} catch (Exception e) {
			context.logger().error("Аддон «Артефакты»: не удалось перечитать настройки", e);
		}
	}

	@Override
	public void onShutdown() {
		if (store != null) {
			store.save();
		}
	}

	/**
	 * После перезапуска игры вызванного босса в мире уже нет: снимаем запись, чтобы
	 * не ждать победы над тем, кого нет, и не считать его вызов состоявшимся.
	 */
	private void restoreBossState() {
		if (store.bossActiveId != null && !store.bossActiveId.isBlank()) {
			context.logger().info("Аддон «Артефакты»: босс «{}» остался с прошлого запуска — начинаю расписание заново",
					store.bossActiveName);
			store.bossActiveId = "";
			store.bossActiveName = "";
		}
		if (store.bossNextAt <= 0) {
			long interval = config.bosses == null ? 21600 : config.bosses.spawnIntervalSeconds;
			store.bossNextAt = System.currentTimeMillis() + Math.max(60, interval) * 1000L;
		}
		store.save();
	}

	// ---------- Хуки API аддонов ----------

	/**
	 * Хуки 1, 2 и 4: переменные аддона, действие для чат-команды и кастомные триггеры {@code v0…v3}.
	 * Регистрируются один раз (повторная регистрация тех же имён отклоняется модом).
	 */
	private void registerHooks(AddonContext context) {
		if (hooksRegistered) {
			return;
		}
		hooksRegistered = true;

		// Хук 1: свои переменные — работают в любых текстах мода: {artifact_count}, {artifact_last}…
		context.registerVariable("artifact_count", event -> String.valueOf(store.activeCount()));
		context.registerVariable("artifact_total", event -> String.valueOf(store.generated));
		context.registerVariable("artifact_destroyed", event -> String.valueOf(store.destroyed));
		context.registerVariable("artifact_last", event -> lastArtifactName);
		context.registerVariable("artifact_limit", event -> String.valueOf(config.maxArtifacts));
		// Боссы: видно в чат-командах, таймерах и оверлее
		context.registerVariable("boss_name", event -> {
			BossCatalog.Boss boss = bosses.activeBoss();
			return boss == null ? "" : boss.name();
		});
		context.registerVariable("boss_alive", event -> bosses.alive() ? "да" : "нет");
		context.registerVariable("boss_next", event -> bosses.nextText());
		context.registerVariable("boss_defeats", event -> String.valueOf(store.bossDefeats));
		context.registerVariable("boss_spawned", event -> String.valueOf(store.bossSpawned));

		// Хук 2: действие исполняет мод — справка по команде чата
		context.registerAction(AddonAction.of("artifacts_help", "Артефакты: справка", AddonTrigger.chatCommand("артефакты"))
				.elements(AddonElements.builder()
						.message("§dАртефакты§r: в запасе §f{artifact_count}§r шт., всего выбито §f{artifact_total}§r, "
								+ "разрушено §f{artifact_destroyed}§r. Выпадают за битсы, донаты, подписки, рейды, "
								+ "награды за баллы канала и победы над боссами.")
						.build())
				.build());

		// Хук 4: кастомные триггеры v0…v3 (у аддона их может быть до четырёх)
		context.registerCustomTrigger(0, "Проверка артефактов", "Запуск вручную: /twitch addons fire v0",
				AddonTrigger.custom("manual", Map.of("slot", "v0"), event -> false),
				AddonElements.builder()
						.message("§dПроверка аддона «Артефакты»§r: в запасе §f{artifact_count}§r, выбито §f{artifact_total}§r, "
								+ "разрушено §f{artifact_destroyed}§r, последний — §f{artifact_last}§r.")
						.build());
		context.registerCustomTrigger(2, "Босс появился", "Мировое событие: босс вышел в мир",
				AddonTrigger.game(TwitchEvent.GAME_BOSS),
				AddonElements.builder()
						.message("§d§l⚡ Мировое событие!§r §fБосс §d{boss_name}§f вышел на охоту — координаты в чате!")
						.sound("minecraft:entity.ender_dragon.growl", 1.0f, 1.0f)
						.build());
		context.registerCustomTrigger(3, "Босс повержен", "Зрителям — итог сражения и статистика",
				AddonTrigger.game(TwitchEvent.GAME_BOSS),
				AddonElements.builder()
						.message("§a☠ Сражение окончено!§f Побед над боссами: §f{boss_defeats}§f, "
								+ "следующий босс §f{boss_next}§f. Артефактов выбито: §f{artifact_total}§f.")
						.build());
		context.registerCustomTrigger(1, "Рейд: приветствие", "Рейд от 25 зрителей",
				AddonTrigger.raid(25),
				AddonElements.builder()
						.message("§6{user}§r привёл рейд из §f{amount}§r зрителей! Артефактов в запасе: §f{artifact_count}§r.")
						.sound("minecraft:ui.toast.challenge_complete", 1.0f, 1.0f)
						.build());

		context.registerAction(AddonAction.of("bosses_help", "Боссы: справка", AddonTrigger.chatCommand("босс", "боссы"))
				.elements(AddonElements.builder()
						.message("§dБоссы§r: сейчас §f{boss_alive}§r" + " — §f{boss_name}§r. Следующий: §f{boss_next}§r. "
								+ "Побед: §f{boss_defeats}§r, вызовов: §f{boss_spawned}§r.")
						.build())
				.build());

		context.logger().info("Аддон «Артефакты»: зарегистрировано переменных 10, действий 2, кастомных триггеров 4 из 4");
	}

	/**
	 * Хук 3: привязка наград по id. Ввод зрителя (то, что он написал в поле награды) приходит
	 * в обработчик отдельным аргументом и пишется в чат стримеру.
	 */
	private void bindRewards(AddonContext context) {
		if (context == null || config == null || config.drops == null || config.drops.rewardIds == null) {
			return;
		}
		boundRewardIds.clear();
		for (Map.Entry<String, String> entry : config.drops.rewardIds.entrySet()) {
			String id = entry.getKey() == null ? "" : entry.getKey().trim();
			if (id.isEmpty()) {
				continue;
			}
			String title = entry.getValue() == null ? "" : entry.getValue();
			boolean bound = context.bindReward(id, title, this::onBoundReward);
			if (bound) {
				boundRewardIds.add(id);
				context.logger().info("Аддон «Артефакты»: награда «{}» ({}) привязана по id", title.isBlank() ? id : title, id);
			}
		}
	}

	/** Активирована награда, привязанная к аддону по id: выдаём артефакт и показываем ввод зрителя. */
	private void onBoundReward(TwitchEvent event, String input) {
		String note = input == null ? "" : input.trim();
		if (!note.isEmpty()) {
			ArtifactChat.info("§d" + event.user() + "§r в награде «" + event.reward() + "» написал(а): §f" + note);
		}
		drop("reward", event.user());
	}

	// ---------- Внутреннее ----------

	/** Отметить, какие артефакты сейчас в инвентаре (и в каких слотах). */
	private void scan(LocalPlayer player) {
		if (player == null) {
			for (Artifact artifact : store.active()) {
				artifact.present = false;
				artifact.slot = -1;
			}
			return;
		}
		Map<String, Integer> found = ArtifactInventory.scan(player);
		for (Artifact artifact : store.active()) {
			Integer slot = found.get(artifact.id);
			artifact.slot = slot == null ? -1 : slot;
			artifact.present = slot != null;
		}
	}

	/** Артефакты, дошедшие до предела проклятия: объявить и начать разрушение. */
	private void handleBroken(List<Artifact> broken) {
		for (Artifact artifact : broken) {
			String text = ArtifactFactory.text(config.texts.broken, ArtifactFactory.placeholders(artifact, config.maxArtifacts));
			if (config.announce.broken) {
				context.announce(text, config.announce.toTwitch, config.announce.toVk);
			}
			ArtifactChat.warn(text);
			startDestroy(artifact);
		}
	}

	/** Начать удаление предмета из инвентаря (несколько попыток разными командами). */
	public void startDestroy(Artifact artifact) {
		artifact.destroyAttempt = 1;
		runDestroyCommand(artifact);
	}

	private void runDestroyCommand(Artifact artifact) {
		List<String> commands = ArtifactFactory.destroyCommands(artifact, artifact.slot);
		int index = Math.min(Math.max(1, artifact.destroyAttempt), commands.size()) - 1;
		if (index >= 0 && index < commands.size()) {
			artifact.destroyTriedAt = System.currentTimeMillis();
			context.runCommand(commands.get(index));
		}
	}

	/** Проверить, исчез ли предмет; если нет — попробовать следующий способ. */
	private void verifyDestroy(long now) {
		for (Artifact artifact : new ArrayList<>(store.artifacts)) {
			if (artifact.destroyAttempt <= 0 || artifact.inactive) {
				continue;
			}
			if (!artifact.present) {
				artifact.destroyAttempt = 0; // предмет убран — готово
				continue;
			}
			if (now - artifact.destroyTriedAt < DESTROY_VERIFY_MS) {
				continue;
			}
			int total = ArtifactFactory.destroyCommands(artifact, artifact.slot).size();
			if (artifact.destroyAttempt >= total) {
				// Ни один способ не сработал (чужой сервер без прав OP): артефакт «выгорает»
				artifact.inactive = true;
				artifact.destroyAttempt = 0;
				ArtifactChat.error(ArtifactFactory.text(config.texts.inactive, ArtifactFactory.placeholders(artifact, config.maxArtifacts)));
				continue;
			}
			artifact.destroyAttempt++;
			runDestroyCommand(artifact);
		}
	}

	/** Выдать эффекты баффов и проклятий всем действующим артефактам. */
	private void applyEffects() {
		List<Artifact> present = new ArrayList<>();
		for (Artifact artifact : store.active()) {
			if (artifact.present) {
				present.add(artifact);
			}
		}
		if (present.isEmpty()) {
			return;
		}
		int seconds = Math.max(10, config.effectRefreshSeconds * 2);
		for (String command : ArtifactEffects.commands(present, config, seconds)) {
			context.runCommand(command);
		}
	}

	/** Установить процент проклятия (команда {@code /artifact curse}). */
	public void setCurse(Artifact artifact, int percent) {
		double value = Math.max(0, Math.min(config.curse.breakAt, percent));
		long intervalMs = Math.max(1, config.curse.intervalMinutes) * 60_000L;
		artifact.wornMillis = (long) (value * intervalMs);
		artifact.cursePercent = value;
		if (value >= config.curse.breakAt) {
			breakArtifact(artifact);
		}
	}

	/** Разрушить артефакт сейчас: объявить и убрать предмет из инвентаря. */
	public void breakArtifact(Artifact artifact) {
		if (artifact == null) {
			return;
		}
		boolean wasActive = artifact.active();
		store.destroy(artifact);
		startDestroy(artifact);
		if (wasActive) {
			String text = ArtifactFactory.text(config.texts.broken, ArtifactFactory.placeholders(artifact, config.maxArtifacts));
			if (config.announce.broken) {
				context.announce(text, config.announce.toTwitch, config.announce.toVk);
			}
			ArtifactChat.warn(text);
		}
	}

	/** Строки статуса для {@code /artifact}. */
	public List<String> statusLines() {
		List<String> lines = new ArrayList<>();
		lines.add("§5§lАртефакты §7(аддон v" + VERSION + (config.enabled ? "§a включён§7)" : "§c выключен§7)"));
		lines.add("§7В инвентаре: §f" + store.activeCount() + "§7/§f" + config.maxArtifacts
				+ "§7, выбито за всё время: §f" + store.generated + "§7, разрушено проклятием: §f" + store.destroyed);
		lines.add("§7Проклятие: §f+1 % за " + config.curse.intervalMinutes + " мин§7, рассыпается на §f"
				+ String.format(Locale.ROOT, "%.0f %%", config.curse.breakAt)
				+ "§7. Мифические артефакты — без проклятий.");
		lines.add("§7Выдают: §fбитсы §7(за " + config.drops.bitsPerArtifact + "), §fдонат §7(за "
				+ config.drops.donationPerArtifact + "), §fподписка §7(за " + config.drops.subsPerArtifact
				+ "), §fподарки §7(за " + config.drops.giftSubsPerArtifact + "), §fрейд §7(за "
				+ config.drops.raidViewersPerArtifact + " зрителей), §fнаграда §7(«"
				+ String.join("», «", config.drops.rewardNames) + "»), §fбоссы §7("
				+ String.format(Locale.ROOT, "%.0f %%", config.drops.bossKillChance) + ")");
		lines.add("§7Файл: §fconfig/" + ArtifactConfig.FILE_NAME + "§7, состояние: §fconfig/" + ArtifactStore.FILE_NAME);
		lines.add("§8/artifact give [редкость] [ник] · held · list · stats · curse <id> <процент> · break <id> · reload");
		return lines;
	}

	/** Строки «что в инвентаре» для {@code /artifact held}. */
	public List<String> heldLines() {
		List<String> lines = new ArrayList<>();
		List<Artifact> active = store.active();
		if (active.isEmpty()) {
			lines.add("§7Артефактов нет. Проверь выдачу: §e/artifact give common");
			return lines;
		}
		for (Artifact artifact : active) {
			String state = artifact.present ? (artifact.inactive ? "§8выгорел" : "§aв инвентаре") : "§8не найден";
			lines.add("§8[" + artifact.id + "] " + artifact.rarity().color + artifact.name
					+ " §7— " + state + "§7, проклятие §f" + artifact.cursePercentText()
					+ (artifact.foundBy.isEmpty() ? "" : "§7, выбил §f" + artifact.foundBy));
			lines.add("    " + ArtifactEffects.describe(artifact));
		}
		return lines;
	}

	/** Каталог артефактов для {@code /artifact list [редкость]}. */
	public List<String> listLines(ArtifactRarity rarity) {
		List<String> lines = new ArrayList<>();
		for (ArtifactCatalog.Template template : ArtifactCatalog.templates()) {
			if (rarity != null && template.rarity() != rarity) {
				continue;
			}
			lines.add(template.rarity().color + template.name() + " §8— " + ArtifactCatalog.buff(template.buff()).description()
					+ " §8(ур. " + template.level() + ", " + template.rarity().title + ")");
		}
		if (lines.isEmpty()) {
			lines.add("§7Ничего не найдено.");
		}
		return lines;
	}

	/** Статистика для {@code /artifact stats}. */
	public List<String> statsLines() {
		List<String> lines = new ArrayList<>();
		lines.add("§5§lАртефакты: статистика");
		lines.add("§7Выбито: §f" + store.generated + "§7, разрушено: §f" + store.destroyed
				+ "§7, действующих: §f" + store.activeCount());
		StringBuilder rarities = new StringBuilder();
		for (ArtifactRarity rarity : ArtifactRarity.values()) {
			int count = store.byRarity.getOrDefault(rarity.id, 0);
			if (count > 0) {
				rarities.append(rarities.length() > 0 ? "§7, " : "").append(rarity.color).append(rarity.title).append(" §f").append(count);
			}
		}
		lines.add("§7По редкостям: " + (rarities.length() == 0 ? "§8пока пусто" : rarities));
		StringBuilder sources = new StringBuilder();
		for (Map.Entry<String, Integer> entry : store.bySource.entrySet()) {
			sources.append(sources.length() > 0 ? "§7, " : "").append(sourceTitle(entry.getKey())).append(" §f").append(entry.getValue());
		}
		lines.add("§7Источники: " + (sources.length() == 0 ? "§8пока пусто" : sources));
		List<Map.Entry<String, Integer>> top = store.topViewers(5);
		if (top.isEmpty()) {
			lines.add("§7Зрители: §8пока никого");
		} else {
			StringBuilder viewers = new StringBuilder();
			for (Map.Entry<String, Integer> entry : top) {
				viewers.append(viewers.length() > 0 ? "§7, " : "").append("§f").append(entry.getKey()).append(" §8(").append(entry.getValue()).append(")");
			}
			lines.add("§7Топ зрителей: " + viewers);
		}
		return lines;
	}

	private static String sourceTitle(String source) {
		return switch (source == null ? "" : source) {
			case "bits" -> "битсы";
			case "donation" -> "донаты";
			case "sub" -> "подписки";
			case "gift" -> "подарки";
			case "raid" -> "рейды";
			case "reward" -> "награды";
			case "boss" -> "боссы";
			default -> "вручную";
		};
	}
}
