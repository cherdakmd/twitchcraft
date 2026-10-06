package dev.dedworkshop.twitchcraft.artifacts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Каталог артефактов: баффы, проклятия, именные артефакты и материалы.
 *
 * Содержимое перенесено из серверного аддона CHRDK REBORN (VKChat Artifacts):
 * 53 баффа, 13 проклятий, 35 именных артефактов и три «особых» мифических
 * (Перо Феникса, Корона Бездны, Сердце Дракона).
 *
 * Материалы подобраны из «вечных» предметов ванильного Minecraft, чтобы команда
 * {@code /give} работала на любой версии; описания и названия оставлены как на сервере.
 */
public final class ArtifactCatalog {
	/** Именной артефакт: название, материал, бафф, уровень, редкость. */
	public record Template(String name, String material, String buff, int level, ArtifactRarity rarity) {
	}

	/** Особый мифический артефакт: выпадает только среди мифических. */
	public record Special(String id, String name, String material, String buff, double rollLimit) {
	}

	private static final List<ArtifactBuff> BUFFS = new ArrayList<>();
	private static final Map<String, ArtifactBuff> BUFFS_BY_ID = new LinkedHashMap<>();
	private static final List<ArtifactCurse> CURSES = new ArrayList<>();
	private static final Map<String, ArtifactCurse> CURSES_BY_ID = new LinkedHashMap<>();
	private static final List<Template> TEMPLATES = new ArrayList<>();
	private static final List<Special> SPECIALS = new ArrayList<>();
	private static final List<String> MATERIALS = new ArrayList<>();

	private ArtifactCatalog() {
	}

	/** Материалы для «обычных» (сгенерированных) артефактов. */
	public static List<String> materials() {
		return List.copyOf(MATERIALS);
	}

	public static List<ArtifactBuff> buffs() {
		return List.copyOf(BUFFS);
	}

	public static List<ArtifactCurse> curses() {
		return List.copyOf(CURSES);
	}

	public static List<Template> templates() {
		return List.copyOf(TEMPLATES);
	}

	public static List<Special> specials() {
		return List.copyOf(SPECIALS);
	}

	/** Бафф по идентификатору; для неизвестных — заглушка «уникальный эффект». */
	public static ArtifactBuff buff(String id) {
		if (id == null) {
			return unknownBuff("?");
		}
		ArtifactBuff buff = BUFFS_BY_ID.get(id.trim().toUpperCase(Locale.ROOT));
		return buff == null ? unknownBuff(id.trim()) : buff;
	}

	/** Проклятие по идентификатору; {@code null} — если проклятия нет. */
	public static ArtifactCurse curse(String id) {
		if (id == null || id.isBlank() || ArtifactCurse.NONE.equalsIgnoreCase(id.trim())) {
			return null;
		}
		ArtifactCurse curse = CURSES_BY_ID.get(id.trim().toUpperCase(Locale.ROOT));
		return curse == null ? new ArtifactCurse(id.trim().toUpperCase(Locale.ROOT), "Неизвестное проклятие", "") : curse;
	}

	public static boolean hasBuff(String id) {
		return id != null && BUFFS_BY_ID.containsKey(id.trim().toUpperCase(Locale.ROOT));
	}

	public static boolean hasCurse(String id) {
		return id != null && CURSES_BY_ID.containsKey(id.trim().toUpperCase(Locale.ROOT));
	}

	/** Случайный бафф (для «обычных» артефактов). */
	public static ArtifactBuff randomBuff(Random random) {
		return BUFFS.get(random.nextInt(BUFFS.size()));
	}

	/** Случайное проклятие. */
	public static ArtifactCurse randomCurse(Random random) {
		return CURSES.get(random.nextInt(CURSES.size()));
	}

	/** Именные артефакты заданной редкости. */
	public static List<Template> templates(ArtifactRarity rarity) {
		List<Template> result = new ArrayList<>();
		for (Template template : TEMPLATES) {
			if (template.rarity() == rarity) {
				result.add(template);
			}
		}
		return result;
	}

	/** Случайный именной артефакт заданной редкости (или null, если таких нет). */
	public static Template randomTemplate(Random random, ArtifactRarity rarity) {
		List<Template> list = templates(rarity);
		return list.isEmpty() ? null : list.get(random.nextInt(list.size()));
	}

	public static Template templateByName(String name) {
		if (name == null) {
			return null;
		}
		for (Template template : TEMPLATES) {
			if (template.name().equalsIgnoreCase(name.trim())) {
				return template;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------
	//  Содержимое каталога
	// ------------------------------------------------------------------

	private static void buff(String id, String description, String effect) {
		ArtifactBuff buff = new ArtifactBuff(id, description, effect == null ? "" : effect);
		BUFFS.add(buff);
		BUFFS_BY_ID.put(id, buff);
	}

	private static void curse(String id, String description, String effect) {
		ArtifactCurse entry = new ArtifactCurse(id, description, effect == null ? "" : effect);
		CURSES.add(entry);
		CURSES_BY_ID.put(id, entry);
	}

	private static void template(String name, String material, String buff, int level, ArtifactRarity rarity) {
		TEMPLATES.add(new Template(name, material, buff, level, rarity));
	}

	private static ArtifactBuff unknownBuff(String id) {
		return new ArtifactBuff(id, "Уникальный эффект " + id, "");
	}

	static {
		// ---------- Баффы (перенос списка BUFFS серверного аддона) ----------
		buff("HEALTH", "Максимальное здоровье +1 HP за уровень", "minecraft:health_boost");
		buff("DAMAGE", "Урон в ближнем бою +1", "minecraft:strength");
		buff("SPEED", "Скорость передвижения +5 % за уровень", "minecraft:speed");
		buff("REGENERATION", "Регенерация уровня артефакта", "minecraft:regeneration");
		buff("VAMPIRISM", "Вампиризм: крадёт здоровье при ударе", "");
		buff("THORNS", "Отражение урона атакующему", "");
		buff("FIRE_RESISTANCE", "Полный иммунитет к огню и лаве", "minecraft:fire_resistance");
		buff("LEVITATION", "Иммунитет к урону от падения (медленное падение)", "minecraft:slow_falling");
		buff("CRITICAL", "Шанс критического удара +4 % за уровень", "");
		buff("ABSORPTION", "Абсорбция: временные сердца", "minecraft:absorption");
		buff("NIGHT_VISION", "Ночное зрение — видно в темноте", "minecraft:night_vision");
		buff("HASTE", "Спешка: быстрее копаешь", "minecraft:haste");
		buff("WATER_BREATHING", "Дыхание под водой", "minecraft:water_breathing");
		buff("JUMP_BOOST", "Прыгучесть +1 за уровень", "minecraft:jump_boost");
		buff("LUCK", "Удача: лучше лут", "minecraft:luck");
		buff("WITHER_TOUCH", "Касание Иссушителя: эффект визера на врага", "");
		buff("POISON_STRIKE", "Ядовитый удар: отравляет врага", "");
		buff("FREEZE_AURA", "Ледяная аура: замедляет врагов рядом", "");
		buff("LIGHTNING_STRIKE", "Удар молнии с шансом при атаке", "");
		buff("GHOST_WALK", "Призрачный шаг: невидимость при здоровье < 40 %", "");
		buff("TRUE_STRIKE", "Истинный удар: игнорирует часть брони", "");
		buff("STEEL_SKIN", "Стальная кожа: дополнительная броня", "minecraft:resistance");
		buff("AQUATIC_SPEED", "Скорость под водой", "minecraft:dolphins_grace");
		buff("FIRE_WALKER", "Хождение по лаве (огнестойкость)", "minecraft:fire_resistance");
		buff("XP_BOOST", "Бонус опыта +8 % за уровень", "");
		buff("DOUBLE_JUMP", "Двойной прыжок", "minecraft:jump_boost");
		buff("DODGE_CHANCE", "Уклонение +3 % за уровень", "");
		buff("KNOCKBACK_RESIST", "Сопротивление отбрасыванию", "");
		buff("MAX_HEALTH_BOOST", "Колоссальное здоровье +5 HP за уровень", "minecraft:health_boost");
		buff("HERO_OF_VILLAGE", "Герой Деревни: дешевле торговля с жителями", "minecraft:hero_of_the_village");
		buff("STRENGTH_BOOST", "Сила: больше урона", "minecraft:strength");
		buff("RESISTANCE", "Сопротивление: меньше урона", "minecraft:resistance");
		buff("SATURATION", "Вечная сытость — не голодаешь", "minecraft:saturation");
		buff("LUCK_OF_THE_SEA", "Морская удача: шанс сокровищ", "minecraft:luck_of_the_sea");
		buff("SOUL_DRAIN", "Вытягивание души: лечит при убийстве", "");
		buff("FROST_BITE", "Морозный укус: замедление и урон", "");
		buff("MANA_SHIELD", "Мана-щит: поглощает часть урона", "minecraft:absorption");
		buff("TELEKINESIS", "Телекинез: предметы сами летят в инвентарь", "");
		buff("ENDER_SHIFT", "Эндер-сдвиг: телепорт при приседании", "");
		buff("BERSERKER", "Ярость: больше урона при низком здоровье", "");
		buff("ARCANE_BURST", "Магический взрыв вокруг при убийстве", "");
		buff("SHADOW_STEP", "Теневой шаг: ускорение после уклонения", "minecraft:speed");
		buff("LIFESTEAL_AURA", "Аура вампиризма: двойной вампиризм при HP < 30 %", "");
		buff("IRON_WILL", "Железная воля: не отбрасывает при низком здоровье", "");
		buff("TRAP_SENSE", "Чувство ловушки: видишь скрытые опасности", "");
		buff("TREASURE_HUNTER", "Охотник за сокровищами +5 % за уровень", "minecraft:luck");
		buff("FLAME_TONGUE", "Пылающий язык: поджигает врагов", "");
		buff("WIND_WALKER", "Шагающий по ветру: скорость и прыжок", "minecraft:speed");
		buff("ECHO_STRIKE", "Удар-эхо: шанс двойной атаки", "");
		buff("SOUL_SHIELD", "Щит души: абсорбция при низком здоровье", "minecraft:absorption");
		buff("FIRE_RESISTANCE_AURA", "Аура огнестойкости", "minecraft:fire_resistance");
		buff("XP_MAGNET", "Магнит опыта +25 % за уровень", "");
		buff("LOOT_FIND", "Поиск добычи +15 % за уровень", "");
		// Особые (мифические) баффы
		buff("REVIVAL", "Возрождение: 50 % здоровья при смерти (раз в 10 минут)", "");
		buff("ABYSSAL_POWER", "Сила Бездны: +5 урона и невидимость при HP < 30 %", "");
		buff("DRAGON_BLOOD", "Кровь Дракона: +5 HP, регенерация II, +30 % урона огнём", "minecraft:regeneration");

		// ---------- Проклятия (13, как на сервере) ----------
		curse("SLOWNESS", "Замедление", "minecraft:slowness");
		curse("WEAKNESS", "Слабость: меньше урона", "minecraft:weakness");
		curse("HUNGER", "Голод", "minecraft:hunger");
		curse("FRAGILE", "Хрупкость: артефакт рассыплется через 24 часа", "");
		curse("BLINDNESS", "Слепота", "minecraft:blindness");
		curse("VULNERABILITY", "Уязвимость: больше получаемого урона", "");
		curse("DECAY", "Распад: иссушение", "minecraft:wither");
		curse("SILENCE", "Тишина: не слышно шагов и мобов", "minecraft:darkness");
		curse("BLOODLETTING", "Кровотечение: теряешь здоровье со временем", "");
		curse("ANCHOR", "Якорь: тяжесть, трудно оторваться от земли", "minecraft:slowness");
		curse("NIGHTMARE", "Кошмар: мобы замечают тебя издалека", "");
		curse("GREED", "Жадность: часть добычи исчезает", "");
		curse("CHAOS", "Хаос: случайные эффекты", "");

		// ---------- Именные артефакты (35, как на сервере) ----------
		template("✨ Перо Феникса", "minecraft:totem_of_undying", "REVIVAL", 5, ArtifactRarity.MYTHIC);
		template("👑 Корона Бездны", "minecraft:nether_star", "ABYSSAL_POWER", 5, ArtifactRarity.MYTHIC);
		template("❤️ Сердце Дракона", "minecraft:dragon_breath", "DRAGON_BLOOD", 5, ArtifactRarity.MYTHIC);
		template("💎 Осколок Вечности", "minecraft:diamond", "MAX_HEALTH_BOOST", 4, ArtifactRarity.LEGENDARY);
		template("🔥 Пылающий Клинок", "minecraft:blaze_powder", "FLAME_TONGUE", 4, ArtifactRarity.LEGENDARY);
		template("❄️ Ледяное Сердце", "minecraft:ghast_tear", "FROST_BITE", 4, ArtifactRarity.LEGENDARY);
		template("⚡ Молния в Бутылке", "minecraft:end_crystal", "LIGHTNING_STRIKE", 4, ArtifactRarity.LEGENDARY);
		template("🌙 Лунный Камень", "minecraft:conduit", "NIGHT_VISION", 3, ArtifactRarity.EPIC);
		template("🌊 Приливный Талисман", "minecraft:heart_of_the_sea", "WATER_BREATHING", 3, ArtifactRarity.EPIC);
		template("🍀 Клевер Удачи", "minecraft:rabbit_foot", "LUCK", 3, ArtifactRarity.EPIC);
		template("🛡️ Щит Предков", "minecraft:nautilus_shell", "STEEL_SKIN", 3, ArtifactRarity.EPIC);
		template("👁️ Глаз Провидца", "minecraft:ender_eye", "TRAP_SENSE", 3, ArtifactRarity.EPIC);
		template("🌿 Травяной Эликсир", "minecraft:chorus_fruit", "REGENERATION", 3, ArtifactRarity.EPIC);
		template("💀 Череп Силы", "minecraft:wither_skeleton_skull", "WITHER_TOUCH", 2, ArtifactRarity.RARE);
		template("🕸️ Нить Паутины", "minecraft:cobweb", "SHADOW_STEP", 2, ArtifactRarity.RARE);
		template("🧲 Магнит Опыта", "minecraft:magma_cream", "XP_MAGNET", 2, ArtifactRarity.RARE);
		template("🔮 Хрустальный Шар", "minecraft:glass", "ARCANE_BURST", 2, ArtifactRarity.RARE);
		template("🧤 Рукавицы Гиганта", "minecraft:iron_ingot", "KNOCKBACK_RESIST", 2, ArtifactRarity.RARE);
		template("🥾 Сапоги Скорохода", "minecraft:leather_boots", "SPEED", 2, ArtifactRarity.RARE);
		template("📿 Ожерелье Вампира", "minecraft:prismarine_shard", "VAMPIRISM", 2, ArtifactRarity.RARE);
		template("🎯 Прицел Снайпера", "minecraft:arrow", "CRITICAL", 2, ArtifactRarity.RARE);
		template("🪶 Перо Птицы", "minecraft:feather", "DOUBLE_JUMP", 2, ArtifactRarity.RARE);
		template("🧪 Зелье Силы", "minecraft:glass_bottle", "STRENGTH_BOOST", 1, ArtifactRarity.COMMON);
		template("🛡️ Малый Щит", "minecraft:shield", "RESISTANCE", 1, ArtifactRarity.COMMON);
		template("🥾 Ботинки Бегуна", "minecraft:chainmail_boots", "SPEED", 1, ArtifactRarity.COMMON);
		template("🧤 Перчатки Кузнеца", "minecraft:iron_nugget", "DAMAGE", 1, ArtifactRarity.COMMON);
		template("📿 Амулет Здоровья", "minecraft:golden_apple", "HEALTH", 1, ArtifactRarity.COMMON);
		template("🔮 Малый Кристалл", "minecraft:amethyst_shard", "ABSORPTION", 1, ArtifactRarity.COMMON);
		template("🎯 Меткая Рука", "minecraft:bow", "CRITICAL", 1, ArtifactRarity.COMMON);
		template("🍀 Крошечный Клевер", "minecraft:wheat_seeds", "LUCK", 1, ArtifactRarity.COMMON);
		template("🔥 Огненный Камень", "minecraft:flint_and_steel", "FIRE_RESISTANCE", 1, ArtifactRarity.COMMON);
		template("💧 Капля Жизни", "minecraft:honey_bottle", "REGENERATION", 1, ArtifactRarity.COMMON);
		template("🌿 Травяной Мешочек", "minecraft:wheat", "SATURATION", 1, ArtifactRarity.COMMON);
		template("🧲 Малый Магнит", "minecraft:compass", "TREASURE_HUNTER", 1, ArtifactRarity.COMMON);
		template("🌙 Ночной Камень", "minecraft:ink_sac", "NIGHT_VISION", 1, ArtifactRarity.COMMON);

		// ---------- Особые мифические (шанс указан внутри мифического дропа, проценты) ----------
		SPECIALS.add(new Special("DRAGON_HEART", "✨ Сердце Дракона", "minecraft:dragon_breath", "DRAGON_BLOOD", 0.15));
		SPECIALS.add(new Special("PHOENIX_FEATHER", "✨ Перо Феникса", "minecraft:totem_of_undying", "REVIVAL", 0.40));
		SPECIALS.add(new Special("ABYSSAL_CROWN", "✨ Корона Бездны", "minecraft:nether_star", "ABYSSAL_POWER", 1.15));

		MATERIALS.add("minecraft:diamond");
		MATERIALS.add("minecraft:emerald");
		MATERIALS.add("minecraft:nether_star");
		MATERIALS.add("minecraft:totem_of_undying");
		MATERIALS.add("minecraft:heart_of_the_sea");
		MATERIALS.add("minecraft:dragon_breath");
		MATERIALS.add("minecraft:ghast_tear");
		MATERIALS.add("minecraft:blaze_powder");
		MATERIALS.add("minecraft:rabbit_foot");
		MATERIALS.add("minecraft:magma_cream");
		MATERIALS.add("minecraft:end_crystal");
		MATERIALS.add("minecraft:conduit");
		MATERIALS.add("minecraft:end_rod");
		MATERIALS.add("minecraft:chorus_fruit");
		MATERIALS.add("minecraft:amethyst_shard");
		MATERIALS.add("minecraft:echo_shard");
		MATERIALS.add("minecraft:nautilus_shell");
		MATERIALS.add("minecraft:prismarine_shard");
		MATERIALS.add("minecraft:golden_apple");
		MATERIALS.add("minecraft:iron_ingot");
		MATERIALS.add("minecraft:feather");
		MATERIALS.add("minecraft:arrow");
		MATERIALS.add("minecraft:shield");
		MATERIALS.add("minecraft:chainmail_boots");
		MATERIALS.add("minecraft:leather_boots");
		MATERIALS.add("minecraft:wheat");
		MATERIALS.add("minecraft:flint_and_steel");
		MATERIALS.add("minecraft:ink_sac");
		MATERIALS.add("minecraft:wither_skeleton_skull");
		MATERIALS.add("minecraft:cobweb");
		MATERIALS.add("minecraft:glass");
		MATERIALS.add("minecraft:iron_nugget");
		MATERIALS.add("minecraft:bow");
		MATERIALS.add("minecraft:wheat_seeds");
		MATERIALS.add("minecraft:honey_bottle");
		MATERIALS.add("minecraft:compass");
		MATERIALS.add("minecraft:ender_eye");
	}
}
