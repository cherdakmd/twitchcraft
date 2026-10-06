package dev.dedworkshop.twitchcraft.artifacts;

import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Боссы: расписание, объявления, вызов в мир, навыки и награда за победу.
 *
 * Перенос {@code BossManager} серверного аддона CHRDK REBORN (14 боссов, 11 навыков).
 * Отличия клиентской стороны:
 * <ul>
 *   <li>босс вызывается командами Minecraft (в одиночной игре — с правами оператора),
 *       поэтому шаблоны вызова и применения атрибутов лежат в конфиге: если версия игры
 *       поменяет синтаксис, правь конфиг, а не код;</li>
 *   <li>навык — это список команд ({@link BossSkills}), а не код Bukkit;</li>
 *   <li>навыки срабатывают, пока босс жив и игрок рядом (шанс 20 % раз в 5 секунд — как на сервере),
 *       а не «на удар»: у клиента нет события урона по чужой сущности;</li>
 *   <li>победа определяется по смерти сущности с нашим именем — мод слушает
 *       {@code ServerLivingEntityEvents.AFTER_DEATH} (в одиночной игре события встроенного сервера).</li>
 * </ul>
 *
 * Все команды выполняются через {@link dev.dedworkshop.twitchcraft.api.AddonContext#runCommand(String)},
 * то есть учитывают {@code blockedCommands} мода.
 */
public final class BossManager {

	/** Полоса босса в интерфейсе (id ванильной полосы). */
	private static final String BOSS_BAR_ID = "twitchcraft:boss";

	private final ArtifactsAddon addon;
	private final Random random = new Random();

	private long lastCheckAt;
	private long lastSkillAt;
	private boolean announced;

	public BossManager(ArtifactsAddon addon) {
		this.addon = addon;
	}

	// ---------- Регистрация событий ----------

	/** Слушает смерть сущностей встроенного сервера (победа над боссом). */
	public void register() {
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			try {
				onDeath(entity, source);
			} catch (Throwable t) {
				log().error("Боссы: ошибка обработки смерти сущности", t);
			}
		});
	}

	private void onDeath(LivingEntity entity, DamageSource source) {
		if (entity == null || !isActiveBoss(entity)) {
			return;
		}
		BossCatalog.Boss boss = activeBoss();
		String killer = "";
		if (source != null && source.getEntity() != null) {
			killer = source.getEntity().getName().getString();
		}
		String bossName = boss == null ? String.valueOf(entity.getName().getString()) : boss.title();
		ArtifactStore store = store();
		store.bossActiveId = "";
		store.bossActiveName = "";
		store.bossDefeats++;
		store.save();
		removeBossBar();

		ArtifactConfig config = addon.config();
		ArtifactConfig.Bosses.Texts texts = config == null || config.bosses == null
				? new ArtifactConfig.Bosses.Texts() : config.bosses.texts;
		String text = ArtifactFactory.text(texts.defeat, Map.of(
				"boss", bossName,
				"killer", killer.isBlank() ? texts.noKiller : killer,
				"player", killer.isBlank() ? "" : killer));
		announce(text);
		publish(hookEvent(TwitchEvent.GAME_BOSS_DEFEAT, killer.isBlank() ? bossName : killer, bossName,
				killer.isBlank() ? "" : killer, (int) Math.min(Integer.MAX_VALUE, store.bossDefeats)));

		// Артефакт за победу — с шансом из настроек (в серверном аддоне 50 %)
		int chance = config == null || config.bosses == null ? 50 : config.bosses.artifactChancePercent;
		if (boss != null && chance > 0 && random.nextInt(100) < chance) {
			addon.drop("boss", killer.isBlank() ? "" : killer);
		}
	}

	private boolean isActiveBoss(LivingEntity entity) {
		ArtifactStore store = store();
		if (store == null || store.bossActiveId == null || store.bossActiveId.isBlank()) {
			return false;
		}
		BossCatalog.Boss boss = BossCatalog.byId(store.bossActiveId);
		if (boss == null) {
			return false;
		}
		String typeId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
		if (!typeId.equalsIgnoreCase(boss.type())) {
			return false;
		}
		return entity.hasCustomName() && entity.getCustomName() != null
				&& boss.name().equalsIgnoreCase(entity.getCustomName().getString());
	}

	// ---------- Тик ----------

	/** Вызывается раз в секунду из тика аддона. */
	public void tick(Minecraft client) {
		ArtifactConfig config = addon.config();
		if (config == null || config.bosses == null) {
			return;
		}
		ArtifactConfig.Bosses settings = config.bosses;
		ArtifactStore store = store();
		if (store == null || !settings.enabled) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastCheckAt < 1000) {
			return;
		}
		lastCheckAt = now;

		// Есть ли уже вызванный босс: если сущность исчезла (мир выгрузился, босса убили без события) — снимаем
		if (!store.bossActiveId.isBlank()) {
			tickActiveBoss(client, settings, now);
			return;
		}
		// Расписание
		long intervalMs = Math.max(60, settings.spawnIntervalSeconds) * 1000L;
		if (store.bossNextAt <= 0) {
			store.bossNextAt = now + intervalMs;
			store.save();
		}
		long announceBeforeMs = Math.max(0, settings.announceBeforeSeconds) * 1000L;
		if (!announced && now >= store.bossNextAt - announceBeforeMs) {
			announced = true;
			BossCatalog.Boss planned = BossCatalog.random(random, settings.disabled);
			store.bossPlannedId = planned == null ? "" : planned.id();
			store.bossX = plannedX(client, settings);
			store.bossZ = plannedZ(client, settings);
			store.save();
			if (planned != null) {
				long minutes = Math.max(1, (store.bossNextAt - now) / 60_000L);
				String text = ArtifactFactory.text(settings.texts.announce, Map.of(
						"boss", planned.title(),
						"x", String.valueOf((long) store.bossX),
						"z", String.valueOf((long) store.bossZ),
						"minutes", String.valueOf(minutes)));
				announce(text);
				log().info("Боссы: через {} мин появится «{}» (X {} Z {})", minutes, planned.name(),
						(long) store.bossX, (long) store.bossZ);
			}
		}
		if (now >= store.bossNextAt) {
			BossCatalog.Boss boss = BossCatalog.byId(store.bossPlannedId);
			if (boss == null) {
				boss = BossCatalog.random(random, settings.disabled);
			}
			spawn(client, boss, settings, store.bossX, store.bossZ);
		}
	}

	/** Босс уже вызван: полоса, навыки, ожидание победы. */
	private void tickActiveBoss(Minecraft client, ArtifactConfig.Bosses settings, long now) {
		BossCatalog.Boss boss = activeBoss();
		ArtifactStore store = store();
		if (boss == null) {
			store.bossActiveId = "";
			store.save();
			return;
		}
		LocalPlayer player = client == null ? null : client.player;
		if (player == null) {
			return; // игрок не в мире: ждём, пока вернётся
		}
		if (player.distanceToSqr(store.bossX, store.bossY, store.bossZ) > Math.pow(Math.max(4, settings.skillRange), 2)) {
			return; // далеко — босс ждёт
		}
		long cooldownMs = Math.max(1, settings.skillCooldownSeconds) * 1000L;
		if (now - lastSkillAt < cooldownMs) {
			return;
		}
		lastSkillAt = now;
		if (random.nextInt(100) >= Math.max(0, Math.min(100, settings.skillChancePercent))
				|| boss.skills().isEmpty()) {
			return;
		}
		String skill = boss.skills().get(random.nextInt(boss.skills().size()));
		runSkill(boss, skill, player);
	}

	/** Выполнить навык и сказать о нём в чат (как серверный аддон писал игроку). */
	private void runSkill(BossCatalog.Boss boss, String skill, LocalPlayer player) {
		BossSkills.Context context = new BossSkills.Context(boss.name(), boss.type(),
				store().bossX, store().bossY, store().bossZ, player.getName().getString(),
				player.getX(), player.getY(), player.getZ(), random);
		List<String> commands = BossSkills.commands(skill, context);
		if (commands.isEmpty()) {
			return;
		}
		ArtifactChat.info("§4" + boss.name() + "§r: §f" + BossSkills.description(skill));
		for (String command : commands) {
			addon.context().runCommand(command);
		}
		// Телепорт сдвигает босса — запомним новое место, чтобы следующие навыки били оттуда
		if ("TELEPORT".equalsIgnoreCase(skill) && commands.size() == 1) {
			String[] parts = commands.get(0).split(" ");
			if (parts.length >= 5) {
				try {
					store().bossX = Double.parseDouble(parts[2]);
					store().bossZ = Double.parseDouble(parts[4]);
					store().save();
				} catch (NumberFormatException ignored) {
					// команду мог заменить шаблон из конфига — просто не двигаем запись
				}
			}
		}
	}

	// ---------- Вызов и остановка ----------

	/** Показать босса сейчас (команда {@code /artifact boss now}) или по расписанию. */
	public boolean spawnNow(Minecraft client, boolean silent) {
		ArtifactConfig config = addon.config();
		ArtifactStore store = store();
		if (config == null || config.bosses == null || store == null) {
			return false;
		}
		if (!store.bossActiveId.isBlank()) {
			return false; // один босс за раз — как на сервере
		}
		BossCatalog.Boss boss = BossCatalog.random(random, config.bosses.disabled);
		if (boss == null) {
			return false;
		}
		double x = plannedX(client, config.bosses);
		double z = plannedZ(client, config.bosses);
		spawn(client, boss, config.bosses, x, z);
		if (silent) {
			ArtifactChat.info("§7Босс «" + boss.name() + "» вызван вручную: X " + (long) x + " Z " + (long) z);
		}
		return true;
	}

	/** Вызвать конкретного босса по id (для проверки). */
	public boolean spawn(String bossId, Minecraft client) {
		ArtifactConfig config = addon.config();
		ArtifactStore store = store();
		if (config == null || config.bosses == null || store == null || !store.bossActiveId.isBlank()) {
			return false;
		}
		BossCatalog.Boss boss = BossCatalog.byId(bossId);
		if (boss == null) {
			return false;
		}
		spawn(client, boss, config.bosses, plannedX(client, config.bosses), plannedZ(client, config.bosses));
		return true;
	}

	private void spawn(Minecraft client, BossCatalog.Boss boss, ArtifactConfig.Bosses settings) {
		spawn(client, boss, settings, plannedX(client, settings), plannedZ(client, settings));
	}

	private void spawn(Minecraft client, BossCatalog.Boss boss, ArtifactConfig.Bosses settings, double x, double z) {
		if (boss == null) {
			return;
		}
		double y = surfaceY(client, x, z);
		ArtifactStore store = store();
		store.bossActiveId = boss.id();
		store.bossActiveName = boss.name();
		store.bossX = x;
		store.bossY = y;
		store.bossZ = z;
		store.bossSpawnAt = System.currentTimeMillis();
		store.bossSpawned++;
		store.bossPlannedId = "";
		store.bossNextAt = System.currentTimeMillis() + Math.max(60, settings.spawnIntervalSeconds) * 1000L;
		store.save();
		announced = false;
		lastSkillAt = System.currentTimeMillis();

		List<String> commands = new ArrayList<>();
		commands.add(render(settings.summonCommand, boss, x, y, z, settings, 0));
		if (settings.attributes.enabled) {
			commands.add(attribute(settings.attributes.maxHealth, boss, x, y, z, settings, boss.health()));
			commands.add(attribute(settings.attributes.attackDamage, boss, x, y, z, settings, boss.damage()));
			commands.add(attribute(settings.attributes.movementSpeed, boss, x, y, z, settings, boss.speed()));
		}
		if (settings.bossBar) {
			commands.add("bossbar add " + BOSS_BAR_ID + " {\"text\":\"" + boss.name() + "\"}");
			commands.add("bossbar set " + BOSS_BAR_ID + " color red");
			commands.add("bossbar set " + BOSS_BAR_ID + " max 1");
			commands.add("bossbar set " + BOSS_BAR_ID + " value 1");
			commands.add("bossbar set " + BOSS_BAR_ID + " players @a");
		}
		for (String command : commands) {
			addon.context().runCommand(command);
		}
		String text = ArtifactFactory.text(settings.texts.spawn, Map.of(
				"boss", boss.title(),
				"x", String.valueOf((long) x),
				"z", String.valueOf((long) z),
				"health", String.valueOf((long) boss.health()),
				"skills", String.valueOf(boss.skills().size())));
		announce(text);
		// Событие в конвейер мода: действия из конфига и кастомные триггеры аддонов
		publish(hookEvent(TwitchEvent.GAME_BOSS_SPAWN, boss.name(), boss.title(),
				"X " + (long) x + " Z " + (long) z, (int) Math.min(Integer.MAX_VALUE, store.bossSpawned)));
		log().info("Боссы: «{}» появился (X {} Y {} Z {}), навыков {}", boss.name(), (long) x, (long) y, (long) z,
				boss.skills().size());
	}

	/** Убрать босса и полосу (команда {@code /artifact boss stop}). */
	public boolean stop(boolean silent) {
		ArtifactStore store = store();
		if (store == null || store.bossActiveId.isBlank()) {
			return false;
		}
		String name = store.bossActiveName;
		BossCatalog.Boss boss = BossCatalog.byId(store.bossActiveId);
		if (boss != null) {
			addon.context().runCommand("kill " + boss.selector());
		}
		store.bossActiveId = "";
		store.bossActiveName = "";
		store.save();
		removeBossBar();
		if (!silent) {
			ArtifactChat.warn("Босс" + (name == null || name.isBlank() ? "" : " «" + name + "»") + " убран, следующий — по расписанию.");
		}
		return true;
	}

	private void removeBossBar() {
		addon.context().runCommand("bossbar remove " + BOSS_BAR_ID);
	}

	// ---------- Состояние для команд и переменных ----------

	public BossCatalog.Boss activeBoss() {
		ArtifactStore store = store();
		return store == null ? null : BossCatalog.byId(store.bossActiveId);
	}

	public boolean alive() {
		BossCatalog.Boss boss = activeBoss();
		return boss != null;
	}

	/** Когда появится следующий босс, словами. */
	public String nextText() {
		ArtifactStore store = store();
		if (store == null || store.bossNextAt <= 0) {
			return "не запланирован";
		}
		long left = store.bossNextAt - System.currentTimeMillis();
		if (left <= 0) {
			return "вот-вот";
		}
		long minutes = left / 60_000L;
		if (minutes < 60) {
			return "через " + minutes + " мин";
		}
		return "через " + (minutes / 60) + " ч " + (minutes % 60) + " мин";
	}

	/** Строки для {@code /artifact boss}. */
	public List<String> statusLines() {
		ArtifactConfig config = addon.config();
		ArtifactStore store = store();
		List<String> lines = new ArrayList<>();
		if (config == null || config.bosses == null || store == null) {
			lines.add("§cБоссы недоступны: настройки не загружены.");
			return lines;
		}
		ArtifactConfig.Bosses settings = config.bosses;
		lines.add("§5§lБоссы §7(" + (settings.enabled ? "включены" : "§cвыключены§7") + ")");
		if (alive()) {
			BossCatalog.Boss boss = activeBoss();
			lines.add("  §a● §f" + boss.title() + " §7— X " + (long) store.bossX + " Z " + (long) store.bossZ
					+ ", навыки: " + (boss.skills().isEmpty() ? "нет" : String.join(", ", boss.skills())));
		} else {
			lines.add("  §7Сейчас босса нет. Следующий: §f" + nextText()
					+ "§7, место: X " + (long) store.bossX + " Z " + (long) store.bossZ);
		}
		lines.add("  §7Всего боссов: §f" + store.bossSpawned + "§7, побед: §f" + store.bossDefeats
				+ "§7, шанс артефакта за победу: §f" + settings.artifactChancePercent + "%");
		lines.add("  §7Проверка: §f/artifact boss now§7, список: §f/artifact boss list§7, убрать: §f/artifact boss stop");
		return lines;
	}

	/** Строки со списком боссов и их навыками. */
	public List<String> listLines() {
		List<String> lines = new ArrayList<>();
		lines.add("§5§lБоссы §7(" + BossCatalog.BOSSES.size() + ")");
		for (BossCatalog.Boss boss : BossCatalog.BOSSES) {
			List<String> skillNames = new ArrayList<>();
			for (String skill : boss.skills()) {
				skillNames.add(skill + " (" + BossSkills.description(skill) + ")");
			}
			lines.add("  §a● §f" + boss.id() + "§7 — " + boss.title() + "§7: " + String.format(Locale.ROOT, "%.0f HP", boss.health())
					+ ", урон " + (long) boss.damage() + ", " + (boss.aggressive() ? "агрессивный" : "мирный")
					+ (skillNames.isEmpty() ? "" : ", навыки: " + String.join("; ", skillNames)));
		}
		return lines;
	}

	// ---------- Внутреннее ----------

	private String attribute(String attributeId, BossCatalog.Boss boss, double x, double y, double z,
			ArtifactConfig.Bosses settings, double value) {
		return "attribute " + boss.selector() + " " + attributeId + " base set " + trim(value);
	}

	private String render(String template, BossCatalog.Boss boss, double x, double y, double z,
			ArtifactConfig.Bosses settings, double value) {
		String text = template == null || template.isBlank()
				? "summon {type} {x} {y} {z} {CustomName:'{\"text\":\"{name}\"}',CustomNameVisible:1b,PersistenceRequired:1b}"
				: template;
		return ArtifactFactory.text(text, Map.of(
				"id", boss.id(),
				"name", boss.name(),
				"type", boss.type(),
				"x", trim(x),
				"y", trim(y),
				"z", trim(z),
				"health", trim(boss.health()),
				"damage", trim(boss.damage()),
				"speed", trim(boss.speed()),
				"value", trim(value)));
	}

	/** X для спавна: рядом с игроком (настраиваемое расстояние) или в случайной точке мира. */
	private double plannedX(Minecraft client, ArtifactConfig.Bosses settings) {
		LocalPlayer player = client == null ? null : client.player;
		if (settings.spawnNearPlayer && player != null) {
			return player.getX() + settings.distanceFromPlayer;
		}
		int radius = Math.max(16, settings.radius);
		return random.nextInt(radius * 2) - radius;
	}

	private double plannedZ(Minecraft client, ArtifactConfig.Bosses settings) {
		LocalPlayer player = client == null ? null : client.player;
		if (settings.spawnNearPlayer && player != null) {
			return player.getZ();
		}
		int radius = Math.max(16, settings.radius);
		return random.nextInt(radius * 2) - radius;
	}

	/** Высота поверхности в точке ({@code y} из карты высот клиента) — команда вызывается на уровне земли. */
	private double surfaceY(Minecraft client, double x, double z) {
		LocalPlayer player = client == null ? null : client.player;
		if (client == null || client.level == null) {
			return player == null ? 64 : player.getY();
		}
		net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos((int) x, 0, (int) z);
		try {
			net.minecraft.core.BlockPos surface = client.level.getHeightmapPos(
					net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, pos);
			return surface.getY();
		} catch (Throwable t) {
			return player == null ? 64 : player.getY();
		}
	}

	private static String trim(double value) {
		if (value == Math.rint(value) && Math.abs(value) < 1e15) {
			return String.valueOf((long) value);
		}
		return String.valueOf(Math.round(value * 1000.0) / 1000.0);
	}

	private void announce(String text) {
		ArtifactChat.info(text);
		addon.context().announce(text, true, true);
	}

	/** Проверка «босс повержен» доступна и без запущенной игры (тесты навыков). */
	public static List<String> skillCommands(String skill, String bossId, double bossX, double bossY, double bossZ,
			String player, double playerX, double playerY, double playerZ, Random random) {
		BossCatalog.Boss boss = BossCatalog.byId(bossId);
		String name = boss == null ? bossId : boss.name();
		String type = boss == null ? "minecraft:zombie" : boss.type();
		return BossSkills.commands(skill, new BossSkills.Context(name, type, bossX, bossY, bossZ,
				player, playerX, playerY, playerZ, random));
	}

	/** Событие для хуков аддона: «босс появился» / «босс повержен» (тестовое, чтобы не звать API Twitch). */
	public TwitchEvent hookEvent(String kind, String bossName) {
		return hookEvent(kind, bossName, bossName, "", 1);
	}

	/** Событие для конвейера мода: игрок — {@code who}, текст — {@code text}, дополнение — {@code extra}. */
	public TwitchEvent hookEvent(String kind, String who, String text, String extra, int amount) {
		return TwitchEvent.game(kind, who, text, extra, amount, true);
	}

	/** Отправить событие в TwitchCraft (действия из конфига, хуки аддонов, журнал). */
	private void publish(TwitchEvent event) {
		try {
			addon.context().publish(event);
		} catch (Exception e) {
			log().warn("Боссы: не удалось опубликовать событие {}", event == null ? "?" : event.gameKind(), e);
		}
	}

	private ArtifactStore store() {
		return addon.store();
	}

	private org.slf4j.Logger log() {
		return addon.context().logger();
	}
}
