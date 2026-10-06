package dev.dedworkshop.twitchcraft.artifacts;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Каталог мировых боссов — перенос {@code bosses.list} серверного аддона CHRDK REBORN
 * (14 боссов, у каждого свой набор навыков).
 *
 * <p>Клиентская сторона отличается тем, что боссы не «живут на сервере»: аддон выбирает босса,
 * объявляет его зрителям, а затем командами Minecraft вызывает его в мир, применяет навыки
 * ({@link BossSkills}) и следит за судьбой по имени сущности. Поэтому имя босса — обычный
 * текст (без цветовых кодов): по нему строится селектор {@code @e[name="…"]}.</p>
 */
public final class BossCatalog {

	/**
	 * Босс.
	 *
	 * @param id             идентификатор (латиницей, как в серверном конфиге)
	 * @param name           имя для селектора ({@code @e[name="Король Смерти"]})
	 * @param title          имя для объявлений (с цветами {@code §})
	 * @param type           тип сущности Minecraft ({@code minecraft:zombie})
	 * @param health         здоровье
	 * @param damage         урон
	 * @param speed          скорость (базовое значение атрибута)
	 * @param aggressive     агрессивный (иначе мирный, пока его не ударят)
	 * @param skills         навыки ({@link BossSkills})
	 * @param artifactChance шанс артефакта за победу, проценты
	 */
	public record Boss(String id, String name, String title, String type, double health, double damage, double speed,
			boolean aggressive, List<String> skills, double artifactChance) {

		public Boss {
			skills = skills == null ? List.of() : List.copyOf(skills);
		}

		/** Селектор этой сущности (по имени, одна ближайшая). */
		public String selector() {
			return "@e[type=" + type + ",name=\"" + name + "\",limit=1]";
		}
	}

	/** Тип-константы навыков (как в серверном конфиге). */
	public static final List<String> SKILLS = List.of(
			"MINIONS", "LIGHTNING", "PULL", "EARTHQUAKE", "POISON_CLOUD", "WEB_TRAP",
			"TELEPORT", "BLINDNESS", "WITHER_SKULL", "FREEZE", "FIRE_RING");

	/** 14 боссов серверного аддона (здоровье/урон/скорость — как в {@code config.yml}). */
	public static final List<Boss> BOSSES = List.of(
			boss("king_skeleton", "Король Смерти", "§4§l☠ КОРОЛЬ СМЕРТИ ☠", "minecraft:skeleton",
					1024, 15, 0.3, true, List.of("MINIONS", "LIGHTNING")),
			boss("blood_zombie", "Кровавый Палач", "§4§l КРОВАВЫЙ ПАЛАЧ", "minecraft:zombie",
					4000, 20, 0.25, true, List.of("PULL", "EARTHQUAKE")),
			boss("spider_queen", "Королева Тьмы", "§5§l КОРОЛЕВА ТЬМЫ", "minecraft:spider",
					2500, 12, 0.4, true, List.of("POISON_CLOUD", "WEB_TRAP")),
			boss("iron_titan", "Железный Титан", "§7§l⚙ ЖЕЛЕЗНЫЙ ТИТАН ⚙", "minecraft:iron_golem",
					6000, 30, 0.15, false, List.of("EARTHQUAKE")),
			boss("mad_villager", "Безумный Торговец", "§6§l БЕЗУМНЫЙ ТОРГОВЕЦ", "minecraft:villager",
					1500, 5, 0.4, false, List.of("TELEPORT", "BLINDNESS")),
			boss("wither_mutant", "Мутант Иссушитель", "§8§l МУТАНТ ИССУШИТЕЛЬ", "minecraft:wither_skeleton",
					3500, 18, 0.3, true, List.of("WITHER_SKULL")),
			boss("frost_stray", "Ледяной Бродяга", "§b§l❄ ЛЕДЯНОЙ БРОДЯГА ❄", "minecraft:stray",
					2800, 14, 0.28, true, List.of("FREEZE")),
			boss("fire_blaze", "Повелитель Пламени", "§6§l ПОВЕЛИТЕЛЬ ПЛАМЕНИ", "minecraft:blaze",
					2000, 16, 0.2, true, List.of("FIRE_RING")),
			boss("shadow_enderman", "Теневой Странник", "§5§l ТЕНЕВОЙ СТРАННИК", "minecraft:enderman",
					3200, 22, 0.35, true, List.of("BLINDNESS", "TELEPORT")),
			boss("slime_king", "Король Слизней", "§a§l КОРОЛЬ СЛИЗНЕЙ", "minecraft:slime",
					3500, 15, 0.3, true, List.of("MINIONS")),
			boss("swamp_witch", "Болотная Ведьма", "§2§l БОЛОТНАЯ ВЕДЬМА", "minecraft:witch",
					2200, 10, 0.25, true, List.of("POISON_CLOUD", "BLINDNESS")),
			boss("piglin_warlord", "Военачальник Пиглинов", "§6§l⚔ ВОЕНАЧАЛЬНИК ПИГЛИНОВ ⚔", "minecraft:piglin_brute",
					4500, 25, 0.35, true, List.of("EARTHQUAKE")),
			boss("drowned_sniper", "Глубинный Снайпер", "§b§l ГЛУБИННЫЙ СНАЙПЕР", "minecraft:drowned",
					2800, 18, 0.2, true, List.of("PULL")),
			boss("phantom_dragon", "Фантомный Ужас", "§5§l ФАНТОМНЫЙ УЖАС", "minecraft:phantom",
					1024, 20, 0.5, true, List.of("TELEPORT", "LIGHTNING")));

	private BossCatalog() {
	}

	private static Boss boss(String id, String name, String title, String type, double health, double damage,
			double speed, boolean aggressive, List<String> skills) {
		return new Boss(id, name, title, type, health, damage, speed, aggressive, skills, 50.0);
	}

	/** Босс по идентификатору ({@code null}, если такого нет). */
	public static Boss byId(String id) {
		if (id == null) {
			return null;
		}
		for (Boss boss : BOSSES) {
			if (boss.id().equalsIgnoreCase(id.trim())) {
				return boss;
			}
		}
		return null;
	}

	/** Случайный включённый босс ({@code disabled} — идентификаторы, которые не должны появляться). */
	public static Boss random(Random random, List<String> disabled) {
		List<Boss> pool = new java.util.ArrayList<>();
		for (Boss boss : BOSSES) {
			if (disabled == null || disabled.stream().noneMatch(id -> boss.id().equalsIgnoreCase(id.trim()))) {
				pool.add(boss);
			}
		}
		if (pool.isEmpty()) {
			return null;
		}
		return pool.get(random.nextInt(pool.size()));
	}

	/** Человеческое имя босса по идентификатору. */
	public static String nameOf(String id) {
		Boss boss = byId(id);
		return boss == null ? (id == null ? "" : id) : boss.name();
	}

	/** Есть ли такой навык (без учёта регистра). */
	public static boolean isSkill(String skill) {
		if (skill == null) {
			return false;
		}
		String upper = skill.trim().toUpperCase(Locale.ROOT);
		return SKILLS.contains(upper);
	}
}
