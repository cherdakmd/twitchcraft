package dev.dedworkshop.twitchcraft.artifacts;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.util.SafeFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Настройки аддона «Артефакты» — файл {@code config/twitchcraft-artifacts.json}.
 *
 * Значения по умолчанию повторяют серверный аддон CHRDK REBORN:
 * 5 артефактов на игрока, проклятие +1 % за 60 минут, разрушение на 100 %,
 * мифический артефакт без проклятия.
 */
public final class ArtifactConfig {
	public static final String FILE_NAME = "twitchcraft-artifacts.json";
	public static final int CURRENT_VERSION = 1;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	public int configVersion = CURRENT_VERSION;

	/** Выключенный аддон не выдаёт артефакты, но состояние и команды остаются. */
	public boolean enabled = true;
	/** Реагировать на тестовые события (/twitch test …, /twitch game test boss). */
	public boolean allowSynthetic = true;

	/** Сколько артефактов может лежать в инвентаре одновременно (как max-artifacts на сервере). */
	public int maxArtifacts = 5;

	/** Мировые боссы: расписание, навыки, награда за победу (раздел «Боссы» в MANUAL). */
	public Bosses bosses = new Bosses();

	/** Шанс, что артефакт окажется именным (Перо Феникса, Клинок Бездны…), проценты. */
	public double namedArtifactChance = 20.0;

	/** Шансы редкостей, проценты: common, uncommon, rare, epic, legendary, mythic. */
	public double[] rarityChances = ArtifactRarity.defaultChances();

	/** Применять баффы и проклятия как эффекты Minecraft (эффекты выдаются командой /effect). */
	public boolean applyEffects = true;
	/** Как часто обновлять эффекты, секунды (длительность выдаётся с запасом). */
	public int effectRefreshSeconds = 15;

	/** Писать в предмет ещё и компонент custom_data (для серверных инструментов; по умолчанию выключено). */
	public boolean writeCustomData = false;

	public Announce announce = new Announce();
	public Curse curse = new Curse();
	public Drops drops = new Drops();
	public Texts texts = new Texts();

	/** Куда писать объявления. */
	public static final class Announce {
		public boolean drops = true;
		public boolean mythic = true;
		public boolean broken = true;
		public boolean toTwitch = true;
		public boolean toVk = true;
	}

	/** Рост проклятия. */
	public static final class Curse {
		public boolean enabled = true;
		/** +1 % за каждые N минут «ношения» (артефакт в инвентаре). */
		public int intervalMinutes = 60;
		/** На каком проценте артефакт разрушается. */
		public double breakAt = 100;
		/** Хрупкие артефакты (проклятие FRAGILE) рассыпаются через N часов. */
		public int fragileHours = 24;
	}

	/** За что зрители получают артефакты. */
	public static final class Drops {
		public boolean bits = true;
		public int bitsPerArtifact = 100;
		public boolean donations = true;
		public int donationPerArtifact = 100;
		public boolean subs = true;
		public int subsPerArtifact = 1;
		public boolean giftSubs = true;
		public int giftSubsPerArtifact = 5;
		public boolean raids = true;
		public int raidViewersPerArtifact = 10;
		/** Награды за баллы канала: если название награды содержит одно из этих слов — артефакт. */
		public boolean rewards = true;
		public List<String> rewardNames = new ArrayList<>(List.of("артефакт", "artifact"));
		/**
		 * Награды, привязанные к аддону <b>по id</b> (хук 3 API): id награды → название для списка команд.
		 * Такие награды аддон получает от мода вместе с вводом зрителя, даже если стример переименовал награду.
		 * Пример: {@code "rewardIds": {"f0a1b2c3-…": "Артефакт"}}.
		 */
		public Map<String, String> rewardIds = new LinkedHashMap<>();
		/** Победа над боссом в игре: шанс в процентах. */
		public boolean bossKills = true;
		public double bossKillChance = 50;
	}

	/** Тексты сообщений. Плейсхолдеры: {user}, {name}, {rarity}, {buff}, {curse}, {percent}, {max}, {id}. */
	public static final class Texts {
		public String drop = "§d✦ Артефакт! {user} выбивает «{name}» §7({rarity}, {buff})";
		public String mythic = "§d§l✨ МИФИЧЕСКИЙ ДРОП! §f{user} выбил «{name}» §7— без проклятия!";
		public String broken = "§c☠ Артефакт «{name}» поглотило проклятие ({percent}) — он рассыпался в пыль!";
		public String full = "§eАртефактов уже {max} — «{name}» ускользнул. Используй или разрушь один из них.";
		public String inactive = "§cНе удалось убрать «{name}» из инвентаря — выброси его руками, он больше не действует.";
		public String bossAnnounce = "§5§lМИРОВОЕ СОБЫТИЕ!§r §fДревнее зло пробуждается: §d{boss}§f появится через §a{minutes} мин§f. "
				+ "Координаты: X: §c{x}§f Z: §c{z}";
		public String bossSpawn = "§d§l⚡ БОСС ПОЯВИЛСЯ!§r §f{boss}§r ждёт сражения (X {x} Z {z}, {health} HP). Сражение началось!";
		public String bossDefeat = "§a☠ {boss}§r повержен!§f Победитель: §f{killer}";
		/** Если убийцу определить не удалось (например, босс сгорел или упал). */
		public String bossNoKiller = "неизвестный герой";
	}

	/**
	 * Настройки боссов — перенос {@code bosses} из серверного {@code config.yml}
	 * (интервал 6 часов, предупреждение за 15 минут, радиус 5000, шанс артефакта 50 %).
	 * Шаблоны команд вынесены в конфиг: если версия игры поменяет синтаксис {@code summon}
	 * или названия атрибутов, правь конфиг, а не код.
	 */
	public static final class Bosses {
		public static final String DEFAULT_SUMMON =
				"summon {type} {x} {y} {z} {CustomName:'{\"text\":\"{name}\"}',CustomNameVisible:1b,PersistenceRequired:1b}";

		public boolean enabled = true;
		/** Как часто вызывать босса (секунды; 21600 = 6 часов, как на сервере). */
		public long spawnIntervalSeconds = 21600;
		/** За сколько секунд предупреждать в чатах (900 = 15 минут). */
		public long announceBeforeSeconds = 900;
		/** Радиус случайной точки в мире (если спавн не рядом с игроком). */
		public int radius = 5000;
		/** Вызывать босса рядом с игроком (иначе — в случайной точке радиуса). */
		public boolean spawnNearPlayer = true;
		/** На каком расстоянии от игрока появляется босс. */
		public double distanceFromPlayer = 40;
		/** Шанс навыка при проверке, проценты (на сервере 20). */
		public int skillChancePercent = 20;
		/** Пауза между навыками, секунды (на сервере 5). */
		public int skillCooldownSeconds = 5;
		/** С какого расстояния босс «замечает» игрока и применяет навыки, блоки. */
		public int skillRange = 24;
		/** Показывать полосу босса (ванильный /bossbar). */
		public boolean bossBar = true;
		/** Шанс артефакта за победу над боссом, проценты. */
		public int artifactChancePercent = 50;
		/** Идентификаторы боссов, которых не нужно вызывать (например, ["slime_king"]). */
		public List<String> disabled = new ArrayList<>();
		/** Шаблон вызова босса: {type} {name} {x} {y} {z} {health} {damage} {speed}. */
		public String summonCommand = DEFAULT_SUMMON;
		public Attributes attributes = new Attributes();
		public Texts texts = new Texts();

		/** Настройки атрибутов босса: команда {@code /attribute … base set N}. */
		public static final class Attributes {
			public boolean enabled = true;
			public String maxHealth = "minecraft:max_health";
			public String attackDamage = "minecraft:attack_damage";
			public String movementSpeed = "minecraft:movement_speed";
		}

		/** Тексты боссов: {boss} {x} {z} {minutes} {health} {killer}. */
		public static final class Texts {
			public String announce = "§5§lМИРОВОЕ СОБЫТИЕ!§r §fДревнее зло пробуждается: §d{boss}§f появится через §a{minutes} мин§f. "
					+ "Координаты: X: §c{x}§f Z: §c{z}";
			public String spawn = "§d§l⚡ БОСС ПОЯВИЛСЯ!§r §f{boss}§r ждёт сражения (X {x} Z {z}, {health} HP).";
			public String defeat = "§a☠ {boss}§r повержен! §fПобедитель: §f{killer}";
			public String noKiller = "неизвестный герой";
		}
	}

	/** Значения по умолчанию (создать файл). */
	public static ArtifactConfig defaults() {
		return new ArtifactConfig();
	}

	public static Path path(Path configDir) {
		return configDir.resolve(FILE_NAME);
	}

	/** Загрузка: нет файла — создать с настройками по умолчанию. */
	public static ArtifactConfig load(Path configDir) {
		Path path = path(configDir);
		if (!Files.exists(path)) {
			ArtifactConfig config = defaults();
			config.save(configDir);
			return config;
		}
		try {
			String json = Files.readString(path);
			ArtifactConfig config = GSON.fromJson(json, ArtifactConfig.class);
			if (config == null) {
				config = defaults();
			}
			int oldVersion = config.configVersion;
			config.normalize();
			if (oldVersion < CURRENT_VERSION) {
				// Старый файл сохраняем рядом и перезаписываем с новыми полями.
				SafeFiles.backupCopy(path, ".bak-v" + oldVersion);
				config.save(configDir);
			}
			return config;
		} catch (Exception e) {
			TwitchCraftClient.LOGGER.error("Аддон «Артефакты»: не удалось прочитать {} — взял настройки по умолчанию", path, e);
			SafeFiles.backupBroken(path);
			return defaults();
		}
	}

	public void save(Path configDir) {
		try {
			SafeFiles.writeAtomic(path(configDir), GSON.toJson(this));
		} catch (IOException e) {
			TwitchCraftClient.LOGGER.error("Аддон «Артефакты»: не удалось сохранить настройки", e);
		}
	}

	/** Приведение значений в разумные рамки (правится вручную в файле). */
	public void normalize() {
		configVersion = CURRENT_VERSION;
		maxArtifacts = clamp(maxArtifacts, 1, 64);
		namedArtifactChance = clamp(namedArtifactChance, 0, 100);
		if (rarityChances == null || rarityChances.length != ArtifactRarity.values().length) {
			rarityChances = ArtifactRarity.defaultChances();
		} else {
			for (int i = 0; i < rarityChances.length; i++) {
				if (!Double.isFinite(rarityChances[i]) || rarityChances[i] < 0) {
					rarityChances[i] = ArtifactRarity.values()[i].defaultChance;
				}
			}
		}
		if (announce == null) announce = new Announce();
		if (curse == null) curse = new Curse();
		curse.intervalMinutes = clamp(curse.intervalMinutes, 1, 24 * 60);
		curse.breakAt = clamp(curse.breakAt, 10, 1000);
		curse.fragileHours = clamp(curse.fragileHours, 1, 24 * 30);
		if (drops == null) drops = new Drops();
		drops.bitsPerArtifact = clamp(drops.bitsPerArtifact, 1, 1_000_000);
		drops.donationPerArtifact = clamp(drops.donationPerArtifact, 1, 1_000_000);
		drops.subsPerArtifact = clamp(drops.subsPerArtifact, 1, 1000);
		drops.giftSubsPerArtifact = clamp(drops.giftSubsPerArtifact, 1, 1000);
		drops.raidViewersPerArtifact = clamp(drops.raidViewersPerArtifact, 1, 100_000);
		drops.bossKillChance = clamp(drops.bossKillChance, 0, 100);
		if (drops.rewardNames == null) {
			drops.rewardNames = new ArrayList<>();
		}
		if (drops.rewardIds == null) {
			drops.rewardIds = new LinkedHashMap<>();
		}
		effectRefreshSeconds = clamp(effectRefreshSeconds, 5, 600);
		if (texts == null) texts = new Texts();
		if (bosses == null) bosses = new Bosses();
		bosses.spawnIntervalSeconds = (long) clamp(bosses.spawnIntervalSeconds, 60, 24 * 3600L);
		bosses.announceBeforeSeconds = (long) clamp(bosses.announceBeforeSeconds, 0, bosses.spawnIntervalSeconds);
		bosses.radius = clamp(bosses.radius, 16, 3_000_000);
		bosses.distanceFromPlayer = clamp(bosses.distanceFromPlayer, 8, 256);
		bosses.skillChancePercent = clamp(bosses.skillChancePercent, 0, 100);
		bosses.skillCooldownSeconds = clamp(bosses.skillCooldownSeconds, 1, 600);
		bosses.skillRange = clamp(bosses.skillRange, 4, 256);
		bosses.artifactChancePercent = clamp(bosses.artifactChancePercent, 0, 100);
		if (bosses.disabled == null) bosses.disabled = new ArrayList<>();
		if (bosses.attributes == null) bosses.attributes = new Attributes();
		if (bosses.texts == null) bosses.texts = new Texts();
		if (bosses.summonCommand == null || bosses.summonCommand.isBlank()) {
			bosses.summonCommand = Bosses.DEFAULT_SUMMON;
		}
	}

	/** Шансы для {@link ArtifactRarity#roll(java.util.Random, double[])} в порядке значений перечисления. */
	public double[] rarityChances() {
		return rarityChances;
	}

	/** Краткая сводка для чата и логов. */
	public Map<String, String> summary() {
		Map<String, String> map = new LinkedHashMap<>();
		map.put("enabled", String.valueOf(enabled));
		map.put("maxArtifacts", String.valueOf(maxArtifacts));
		map.put("intervalMinutes", String.valueOf(curse.intervalMinutes));
		map.put("breakAt", String.valueOf(curse.breakAt));
		map.put("bitsPerArtifact", String.valueOf(drops.bitsPerArtifact));
		map.put("donationPerArtifact", String.valueOf(drops.donationPerArtifact));
		return map;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static double clamp(double value, double min, double max) {
		if (!Double.isFinite(value)) {
			return min;
		}
		return Math.max(min, Math.min(max, value));
	}
}
