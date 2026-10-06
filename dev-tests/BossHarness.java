package dev.dedworkshop.twitchcraft.artifacts;

import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Логические тесты боссов аддона «Артефакты» (без Minecraft и без сети):
 * каталог 14 боссов, 11 навыков (команды, координаты, селекторы), настройки и их нормализация.
 *
 * Запуск: см. run_tests.sh (нужны собранный проект и jar Minecraft).
 */
public class BossHarness {
	static int failures = 0;
	static int total = 0;

	static void check(String name, boolean cond) {
		total++;
		System.out.println((cond ? "  OK   " : "  FAIL ") + name);
		if (!cond) {
			failures++;
		}
	}

	static void section(String name) {
		System.out.println("== " + name + " ==");
	}

	static BossSkills.Context ctx() {
		return new BossSkills.Context("Кровавый Палач", "minecraft:zombie", 100, 64, 200, "Steve", 110, 64, 208,
				new Random(42));
	}

	static boolean anyContains(List<String> commands, String part) {
		return commands.stream().anyMatch(c -> c.contains(part));
	}

	public static void main(String[] args) {
		// ---------- Каталог ----------
		section("Каталог боссов");
		List<BossCatalog.Boss> bosses = BossCatalog.BOSSES;
		check("14 боссов, как в серверном конфиге", bosses.size() == 14);
		Set<String> ids = new HashSet<>();
		Set<String> names = new HashSet<>();
		for (BossCatalog.Boss boss : bosses) {
			ids.add(boss.id());
			names.add(boss.name());
		}
		check("идентификаторы уникальны", ids.size() == bosses.size());
		check("имена уникальны (по ним строятся селекторы)", names.size() == bosses.size());
		check("у всех есть тип вида minecraft:…", bosses.stream().allMatch(b -> b.type().startsWith("minecraft:")));
		check("здоровье разумное (>=100 и <=10000)", bosses.stream().allMatch(b -> b.health() >= 100 && b.health() <= 10000));
		check("урон разумный (1..100)", bosses.stream().allMatch(b -> b.damage() >= 1 && b.damage() <= 100));
		check("скорость положительная", bosses.stream().allMatch(b -> b.speed() > 0 && b.speed() <= 1));
		check("шанс артефакта 50 % (как на сервере)", bosses.stream().allMatch(b -> b.artifactChance() == 50));
		check("у каждого есть хотя бы один навык", bosses.stream().allMatch(b -> !b.skills().isEmpty()));
		check("все навыки известны", bosses.stream().flatMap(b -> b.skills().stream()).allMatch(BossCatalog::isSkill));
		check("особые боссы на месте", BossCatalog.byId("king_skeleton") != null && BossCatalog.byId("phantom_dragon") != null
				&& BossCatalog.byId("slime_king") != null);
		check("неизвестный id -> null", BossCatalog.byId("нет такого") == null && BossCatalog.byId(null) == null);
		check("имя босса по id", "Король Смерти".equals(BossCatalog.nameOf("king_skeleton")));
		check("нейтральный босс: Железный Титан мирный", !BossCatalog.byId("iron_titan").aggressive()
				&& BossCatalog.byId("blood_zombie").aggressive());

		BossCatalog.Boss skeleton = BossCatalog.byId("king_skeleton");
		check("селектор по имени и типу", skeleton.selector().equals("@e[type=minecraft:skeleton,name=\"Король Смерти\",limit=1]"));
		check("в именах нет кавычек (селектор не сломается)", bosses.stream().allMatch(b -> !b.name().contains("\"")));

		BossCatalog.Boss random = BossCatalog.random(new Random(1), List.of());
		check("случайный босс из каталога", random != null && BossCatalog.byId(random.id()) != null);
		for (int i = 0; i < 200; i++) {
			BossCatalog.Boss picked = BossCatalog.random(new Random(i), List.of("slime_king", "phantom_dragon"));
			if (picked == null || picked.id().equals("slime_king") || picked.id().equals("phantom_dragon")) {
				check("выключенные боссы не выпадают", false);
				break;
			}
		}
		check("выключенные боссы не выпадают (200 бросков)", true);
		check("если выключены все — null", BossCatalog.random(new Random(1),
				bosses.stream().map(BossCatalog.Boss::id).toList()) == null);

		// ---------- Навыки ----------
		section("Навыки (11 штук)");
		check("список навыков — 11", BossCatalog.SKILLS.size() == 11);
		check("описания есть у всех", BossCatalog.SKILLS.stream().allMatch(s -> s != null
				&& !BossSkills.description(s).isBlank() && !BossSkills.description(s).equals("неизвестный навык")));
		for (String skill : BossCatalog.SKILLS) {
			List<String> commands = BossSkills.commands(skill, ctx());
			check("навык " + skill + " даёт команды (" + commands.size() + ")", !commands.isEmpty());
		}
		check("неизвестный навык -> пусто", BossSkills.commands("НЕТ ТАКОГО", ctx()).isEmpty());
		check("null -> пусто", BossSkills.commands(null, ctx()).isEmpty() && BossSkills.commands("MINIONS", null).isEmpty());
		check("регистр не важен", !BossSkills.commands("minions", ctx()).isEmpty());

		List<String> minions = BossSkills.commands("MINIONS", ctx());
		check("MINIONS: три скелета", minions.size() == 3 && minions.stream().allMatch(c -> c.startsWith("summon minecraft:skeleton")));
		check("MINIONS: рядом с боссом (100/64/200)", minions.get(0).contains("102 64 200") && minions.get(1).contains("98 64 200"));

		List<String> lightning = BossSkills.commands("LIGHTNING", ctx());
		check("LIGHTNING: молния в игрока", lightning.size() == 1
				&& lightning.get(0).equals("summon minecraft:lightning_bolt 110 64 208"));

		check("PULL: телепорт к боссу", BossSkills.commands("PULL", ctx()).get(0).equals("tp @s 100 64 200"));
		check("EARTHQUAKE: левитация и мягкое падение", anyContains(BossSkills.commands("EARTHQUAKE", ctx()), "minecraft:levitation")
				&& anyContains(BossSkills.commands("EARTHQUAKE", ctx()), "minecraft:slow_falling"));
		check("POISON_CLOUD: отравление в радиусе", BossSkills.commands("POISON_CLOUD", ctx()).get(0)
				.contains("poison 5 1") && BossSkills.commands("POISON_CLOUD", ctx()).get(0).contains("distance=..5"));
		check("WEB_TRAP: паутина под ногами с keep", BossSkills.commands("WEB_TRAP", ctx()).get(0)
				.equals("setblock 110 64 208 minecraft:cobweb keep"));
		check("FREEZE: сильное замедление", BossSkills.commands("FREEZE", ctx()).get(0)
				.equals("effect give @s minecraft:slowness 5 4"));
		check("BLINDNESS: слепота", BossSkills.commands("BLINDNESS", ctx()).get(0)
				.equals("effect give @s minecraft:blindness 3 1"));

		List<String> wither = BossSkills.commands("WITHER_SKULL", ctx());
		check("WITHER_SKULL: голова иссушителя с движением", wither.size() == 1
				&& wither.get(0).startsWith("summon minecraft:wither_skull 100 65.5 200 {Motion:[")
				&& wither.get(0).endsWith("d]}"));
		java.util.regex.Matcher motion = java.util.regex.Pattern
				.compile("Motion:\\[(-?[0-9.]+)d,(-?[0-9.]+)d,(-?[0-9.]+)d]").matcher(wither.get(0));
		boolean motionOk = motion.find() && Double.parseDouble(motion.group(1)) > 0
				&& Double.parseDouble(motion.group(3)) > 0; // игрок правее (+10) и дальше (+8) босса
		check("WITHER_SKULL: вектор в сторону игрока (dx>0, dz>0)", motionOk);

		List<String> ring = BossSkills.commands("FIRE_RING", ctx());
		check("FIRE_RING: 12 блоков огня", ring.size() == 12 && ring.stream().allMatch(c -> c.endsWith("minecraft:fire keep")));
		check("FIRE_RING: радиус 3 вокруг босса", ring.get(0).equals("setblock 103 64 200 minecraft:fire keep")
				&& ring.get(3).equals("setblock 100 64 203 minecraft:fire keep"));

		List<String> teleport = BossSkills.commands("TELEPORT", ctx());
		boolean teleportNear = false;
		if (teleport.size() == 1) {
			// селектор содержит пробел внутри name="...", поэтому координаты берём с конца строки
			String[] parts = teleport.get(0).split(" ");
			if (parts.length >= 4 && parts[0].equals("tp") && parts[1].startsWith("@e[type=minecraft:zombie")) {
				double tx = Double.parseDouble(parts[parts.length - 3]);
				double ty = Double.parseDouble(parts[parts.length - 2]);
				double tz = Double.parseDouble(parts[parts.length - 1]);
				teleportNear = Math.abs(tx - 100) <= 5 && Math.abs(tz - 200) <= 5 && ty == 64;
			}
		}
		check("TELEPORT: босс смещается недалеко (±5 блоков)", teleportNear);

		boolean moved = false;
		for (int i = 0; i < 30 && !moved; i++) {
			String command = BossSkills.commands("TELEPORT", new BossSkills.Context("Палач", "minecraft:zombie", 0, 64, 0,
					"Steve", 1, 64, 1, new Random(i))).get(0);
			moved = !command.endsWith(" 0 64 0");
		}
		check("TELEPORT: смещение случайное (не всегда 0,0)", moved);

		check("координаты без лишних нулей", BossSkills.summon("minecraft:skeleton", 10.0, 64.0, -3.0)
				.equals("summon minecraft:skeleton 10 64 -3"));
		check("дробные координаты округляются", BossSkills.summon("minecraft:zombie", 10.25, 64.5, 0)
				.equals("summon minecraft:zombie 10.25 64.5 0"));

		// ---------- Настройки ----------
		section("Настройки боссов");
		ArtifactConfig config = ArtifactConfig.defaults();
		ArtifactConfig.Bosses settings = config.bosses;
		check("боссы включены по умолчанию", settings.enabled);
		check("интервал 6 часов (21600 с), как на сервере", settings.spawnIntervalSeconds == 21600);
		check("предупреждение за 15 минут (900 с)", settings.announceBeforeSeconds == 900);
		check("радиус 5000", settings.radius == 5000);
		check("шанс навыка 20 % и кулдаун 5 с (как на сервере)", settings.skillChancePercent == 20
				&& settings.skillCooldownSeconds == 5);
		check("шанс артефакта за победу 50 %", settings.artifactChancePercent == 50);
		check("вызов рядом с игроком по умолчанию", settings.spawnNearPlayer && settings.distanceFromPlayer == 40);
		check("атрибуты включены и с новыми id", settings.attributes.enabled
				&& settings.attributes.maxHealth.equals("minecraft:max_health")
				&& settings.attributes.attackDamage.equals("minecraft:attack_damage")
				&& settings.attributes.movementSpeed.equals("minecraft:movement_speed"));
		check("шаблон вызова с плейсхолдерами", settings.summonCommand.contains("{type}") && settings.summonCommand.contains("{name}")
				&& settings.summonCommand.contains("CustomName") && settings.summonCommand.contains("PersistenceRequired"));
		check("тексты не пустые", !settings.texts.announce.isBlank() && !settings.texts.spawn.isBlank()
				&& !settings.texts.defeat.isBlank() && !settings.texts.noKiller.isBlank());
		check("в текстах есть плейсхолдеры", settings.texts.announce.contains("{boss}") && settings.texts.announce.contains("{minutes}")
				&& settings.texts.spawn.contains("{health}") && settings.texts.defeat.contains("{killer}"));
		check("сломанные значения нормализуются", normalised(0, -5, 1, 1000, -1) != null);
		ArtifactConfig.Bosses fixed = normalised(0, -5, 1, 1000, -1);
		check("интервал не меньше минуты", fixed.spawnIntervalSeconds >= 60);
		check("предупреждение не больше интервала", fixed.announceBeforeSeconds <= fixed.spawnIntervalSeconds);
		check("радиус не меньше 16", fixed.radius >= 16);
		check("шанс навыка 0..100", fixed.skillChancePercent >= 0 && fixed.skillChancePercent <= 100);
		check("кулдаун навыка >= 1", fixed.skillCooldownSeconds >= 1);
		check("выключенные боссы — пустой список, не null", fixed.disabled != null);
		check("атрибуты не null", fixed.attributes != null && fixed.texts != null);
		check("пустой шаблон вызова заменяется на стандартный", !fixed.summonCommand.isBlank()
				&& fixed.summonCommand.contains("summon {type}"));

		// ---------- Мост BossManager → BossSkills ----------
		section("Связка с менеджером");
		List<String> fromManager = BossManager.skillCommands("FIRE_RING", "fire_blaze", 0, 64, 0, "Steve", 1, 64, 1,
				new Random(7));
		check("BossManager берёт тип из каталога", fromManager.size() == 12);
		List<String> pullFromCatalog = BossManager.skillCommands("PULL", "drowned_sniper", 5, 30, 5, "Steve", 9, 30, 9,
				new Random(7));
		check("BossManager подставляет координаты босса", pullFromCatalog.get(0).equals("tp @s 5 30 5"));
		check("неизвестный босс не ломает навык", !BossManager.skillCommands("LIGHTNING", "нет", 0, 0, 0, "Steve", 1, 2, 3,
				new Random(1)).isEmpty());

		// ---------- События для конвейера мода ----------
		section("События боссов (публикация в TwitchCraft)");
		BossManager manager = new BossManager(null);
		check("новый вид: босс появился", TwitchEvent.GAME_BOSS_SPAWN.equals("bossSpawn")
				&& TwitchEvent.GAME_BOSS_DEFEAT.equals("bossDefeat"));
		TwitchEvent spawnEvent = manager.hookEvent(TwitchEvent.GAME_BOSS_SPAWN, "Кровавый Палач", "Кровавый Палач",
				"X 120 Z -340", 3);
		check("появление: вид и игрок", spawnEvent.type() == TwitchEvent.Type.GAME
				&& TwitchEvent.GAME_BOSS_SPAWN.equals(spawnEvent.gameKind())
				&& !spawnEvent.user().isBlank());
		check("появление: имя босса и координаты", spawnEvent.message().equals("Кровавый Палач")
				&& spawnEvent.tier().equals("X 120 Z -340"));
		check("появление: текст для чата", spawnEvent.describe().contains("босс появился")
				&& spawnEvent.describe().contains("Кровавый Палач"));
		check("появление: счётчик вызовов", spawnEvent.amount() == 3);
		TwitchEvent defeatEvent = manager.hookEvent(TwitchEvent.GAME_BOSS_DEFEAT, "Steve", "Кровавый Палач", "Steve", 2);
		check("победа: кто победил и кого", defeatEvent.user().equals("Steve") && defeatEvent.message().equals("Кровавый Палач"));
		check("победа: текст и пометка синтетики", defeatEvent.describe().contains("босс аддона повержен")
				&& defeatEvent.tier().equals("Steve") && defeatEvent.synthetic());
		check("старая форма hookEvent продолжает работать", manager.hookEvent(TwitchEvent.GAME_BOSS, "Тест")
				.message().equals("Тест"));
		check("событие игры, а не площадки", spawnEvent.isGame() && !spawnEvent.isVk());
		check("плейсхолдеры события: {boss}, {killer}, {game_kind}", spawnEvent.message().equals("Кровавый Палач")
				&& defeatEvent.tier().equals("Steve") && !spawnEvent.gameKind().isBlank());

		// ---------- Расписание и «вечный» бой ----------
		section("Расписание: следующий вызов и лимит боя");
		long now = 1_000_000L;
		check("следующая попытка — через интервал", BossManager.nextAttemptAt(now, 3600) == now + 3_600_000L);
		check("интервал короче минуты не ускоряет боссов", BossManager.nextAttemptAt(now, 5) == now + 60_000L);
		check("бой в пределах лимита ещё идёт", !BossManager.expired(now, now + 60_000L, 900));
		check("время ожидания победы истекло", BossManager.expired(now, now + 900_000L, 900));
		check("побег до лимита не засчитывается как истёкший", !BossManager.expired(now, now + 899_999L, 900));
		check("лимит 0 — запись не снимать никогда", !BossManager.expired(now, now + 10_000_000L, 0));
		check("босс без отметки времени не «истекает»", !BossManager.expired(0, now, 900));

		ArtifactConfig fightConfig = ArtifactConfig.defaults();
		fightConfig.bosses.maxAliveSeconds = -5;
		fightConfig.normalize();
		check("отрицательный лимит боя -> 0", fightConfig.bosses.maxAliveSeconds == 0);
		fightConfig.bosses.maxAliveSeconds = 999_999_999L;
		fightConfig.normalize();
		check("гигантский лимит боя ограничен сутками", fightConfig.bosses.maxAliveSeconds == 24 * 3600L);

		ArtifactConfig messyConfig = ArtifactConfig.defaults();
		java.util.List<String> messy = new java.util.ArrayList<>();
		messy.add("slime_king");
		messy.add("   ");
		messy.add(null);
		messy.add("  phantom_dragon  ");
		messyConfig.bosses.disabled = messy;
		messyConfig.normalize();
		check("пустые id в disabled убираются, остальные нормализуются",
				messyConfig.bosses.disabled.size() == 2
						&& "slime_king".equals(messyConfig.bosses.disabled.get(0))
						&& "phantom_dragon".equals(messyConfig.bosses.disabled.get(1)));
		check("случайный босс не падает на disabled с null и пустой строкой",
				BossCatalog.random(new Random(3), java.util.Arrays.asList("slime_king", null, "")) != null);

		java.util.List<String> allOff = new java.util.ArrayList<>();
		for (BossCatalog.Boss boss : BossCatalog.BOSSES) {
			allOff.add(boss.id());
		}
		check("все боссы выключены — случайный равен null (расписание уходит в следующий интервал)",
				BossCatalog.random(new Random(1), allOff) == null);

		// ---------- Состояние ----------
		section("Состояние (ArtifactStore)");
		ArtifactStore store = new ArtifactStore();
		check("счётчики боссов с нуля", store.bossSpawned == 0 && store.bossDefeats == 0);
		check("вызванного босса нет", store.bossActiveId.isEmpty() && store.bossPlannedId.isEmpty());
		check("время следующего не задано", store.bossNextAt == 0);
		store.normalize();
		check("нормализация не ломает поля боссов", store.bossActiveId != null && store.bossPlannedId != null);

		System.out.println();
		if (failures == 0) {
			System.out.println("ALL " + total + " BOSS TESTS PASSED");
		} else {
			System.out.println("FAILED: " + failures + " из " + total);
			System.exit(1);
		}
	}

	/** Конфиг с заведомо плохими значениями — проверяем, что normalize() приводит их к границам. */
	static ArtifactConfig.Bosses normalised(long interval, long announce, int radius, int chance, int cooldown) {
		ArtifactConfig config = ArtifactConfig.defaults();
		config.bosses.spawnIntervalSeconds = interval;
		config.bosses.announceBeforeSeconds = announce;
		config.bosses.radius = radius;
		config.bosses.skillChancePercent = chance;
		config.bosses.skillCooldownSeconds = cooldown;
		config.bosses.artifactChancePercent = 500;
		config.bosses.summonCommand = " ";
		config.bosses.attributes = null;
		config.bosses.texts = null;
		config.bosses.disabled = null;
		config.normalize();
		return config.bosses;
	}
}
