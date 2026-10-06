package dev.dedworkshop.twitchcraft.config;

import dev.dedworkshop.twitchcraft.config.ModConfig.Action;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Готовый ценник донатов: 25 негативных (☠) и 25 позитивных (★) событий по сумме + «Спасибо» за мелочь.
 * Ключ таблицы — «от скольки»: срабатывает самый большой порог, не превышающий сумму доната.
 * Плохие и хорошие суммы чередуются: ☠ 30, ★ 40, ☠ 50, ★ 60 … Популярные круглые суммы
 * (100, 200, 300, 500, 1000, 2000, 5000) — хорошие, чтобы донат «без чтения ценника» помогал стримеру.
 *
 * Все команды проверены по именам предметов, мобов, эффектов, звуков, NBT-ключей и компонентов Minecraft 26.3.
 */
public final class DonationPresets {
	/** Префиксы в поле name — по ним мод различает плохие и хорошие события при показе ценника. */
	public static final String BAD = "☠ ";
	public static final String GOOD = "★ ";

	private static final String MSG = "&d{user} &7задонатил(а) &6{sum}&7 — ";
	private static final String SUB = "&f{user} · {sum}";

	/** Старый ценник (до 1.5.0): ключ → name. Если в конфиге ровно он — при обновлении заменяем на новый. */
	private static final Map<String, String> LEGACY = Map.of(
			"1", "Спасибо", "50", "Перекус", "100", "Салют", "150", "Бу!", "200", "Ночь ужасов",
			"300", "Зомби-волна", "500", "Супер-сила", "1000", "Сокровища", "2000", "В небо", "5000", "Легенда");

	private DonationPresets() {
	}

	/** Полный ценник по умолчанию (51 запись), отсортированный по сумме. */
	public static Map<String, Action> defaults() {
		TreeMap<Integer, Action> t = new TreeMap<>();

		// ---------- Нейтральное: любая мелочь ----------
		t.put(1, action("Спасибо", MSG + "спасибо! {message}", "", "",
				"minecraft:entity.experience_orb.pickup",
				"particle minecraft:heart ~ ~2 ~ 1 1 1 0 30").with(a -> {
			a.toast = "&6Донат {sum}";
			a.toastText = "&f{user}";
		}));

		// ---------- ☠ Негативные (25) ----------
		t.put(30, bad("Тыква", "тыква на голову! {message}", "&6ТЫКВА!",
				"minecraft:block.pumpkin.carve",
				"execute unless items entity @s armor.head * run item replace entity @s armor.head with minecraft:carved_pumpkin",
				"effect give @s minecraft:nausea 15 0 true"));
		t.put(50, bad("Голод", "желудок сводит от голода! {message}", "&6ГОЛОД",
				"minecraft:entity.player.burp",
				"effect give @s minecraft:hunger 60 2 true"));
		t.put(70, bad("Слепота", "свет погас! {message}", "&8СЛЕПОТА",
				"minecraft:entity.ghast.scream",
				"effect give @s minecraft:blindness 15 0 true"));
		t.put(90, bad("Яд", "отравление! {message}", "&2ЯД",
				"minecraft:entity.puffer_fish.blow_up",
				"effect give @s minecraft:poison 20 1 true"));
		t.put(110, bad("Зомби-волна", "ЗОМБИ-ВОЛНА! {message}", "&cЗОМБИ-ВОЛНА",
				"minecraft:entity.zombie.ambient",
				"summon minecraft:zombie ~3 ~ ~",
				"summon minecraft:zombie ~-3 ~ ~",
				"summon minecraft:zombie ~ ~ ~3",
				"summon minecraft:zombie ~ ~ ~-3",
				"delay 20",
				"summon minecraft:zombie ~2 ~ ~2",
				"summon minecraft:zombie ~-2 ~ ~-2"));
		t.put(130, bad("Пауки", "пещерные пауки лезут отовсюду! {message}", "&2ПАУКИ",
				"minecraft:entity.spider.ambient",
				"summon minecraft:cave_spider ~3 ~ ~",
				"summon minecraft:cave_spider ~-3 ~ ~",
				"summon minecraft:cave_spider ~ ~ ~3",
				"summon minecraft:cave_spider ~ ~ ~-3",
				"summon minecraft:cave_spider ~2 ~ ~-2"));
		t.put(150, bad("Тьма", "кто-то дышит в темноте… {message}", "&8ТЬМА",
				"minecraft:entity.warden.heartbeat",
				"effect give @s minecraft:darkness 30 0 true",
				"effect give @s minecraft:slowness 30 0 true"));
		t.put(170, bad("Катапульта", "В НЕБО! {message}", "&bКАТАПУЛЬТА",
				"minecraft:entity.wind_charge.wind_burst",
				"effect give @s minecraft:slow_falling 40 0 true",
				"execute if block ~ ~40 ~ #minecraft:air if block ~ ~41 ~ #minecraft:air run tp @s ~ ~40 ~",
				"execute unless block ~ ~40 ~ #minecraft:air run effect give @s minecraft:levitation 4 3 true"));
		t.put(190, bad("Скелеты", "отряд скелетов-лучников! {message}", "&7СКЕЛЕТЫ",
				"minecraft:entity.skeleton.ambient",
				"summon minecraft:skeleton ~5 ~ ~",
				"summon minecraft:skeleton ~-5 ~ ~",
				"summon minecraft:skeleton ~ ~ ~5",
				"summon minecraft:skeleton ~ ~ ~-5"));
		t.put(220, bad("Ночь ужасов", "наступает ночь ужасов! {message}", "&5НОЧЬ УЖАСОВ",
				"minecraft:ambient.cave",
				"time set midnight",
				"weather thunder 6000"));
		t.put(250, bad("Молния", "гром среди ясного неба! {message}", "&eМОЛНИЯ",
				"minecraft:entity.lightning_bolt.thunder",
				"summon minecraft:lightning_bolt ~ ~ ~",
				"delay 10",
				"summon minecraft:lightning_bolt ~3 ~ ~3",
				"summon minecraft:lightning_bolt ~-3 ~ ~-3"));
		t.put(280, bad("Наковальня", "смотри наверх! {message}", "&7НАКОВАЛЬНЯ",
				"minecraft:block.anvil.land",
				"summon minecraft:falling_block ~ ~8 ~ {BlockState:{Name:\"minecraft:anvil\"},Time:1,HurtEntities:1b,FallHurtAmount:8f,FallHurtMax:20}"));
		t.put(320, bad("Крипер", "обернись! {message}", "&2КРИПЕР",
				"minecraft:entity.creeper.primed",
				"summon minecraft:creeper ^ ^ ^-2 {powered:1b}"));
		t.put(380, bad("Ведьмы", "шабаш ведьм! {message}", "&5ВЕДЬМЫ",
				"minecraft:entity.witch.celebrate",
				"summon minecraft:witch ~4 ~ ~",
				"summon minecraft:witch ~-4 ~ ~"));
		t.put(450, bad("Телепорт", "телепорт в никуда! {message}", "&dТЕЛЕПОРТ",
				"minecraft:entity.enderman.teleport",
				"spreadplayers ~ ~ 50 300 false @s",
				"effect give @s minecraft:nausea 10 0 true"));
		t.put(550, bad("Вексы", "рой вексов! {message}", "&fВЕКСЫ",
				"minecraft:entity.vex.charge",
				"summon minecraft:vex ~2 ~2 ~ {life_ticks:900}",
				"summon minecraft:vex ~-2 ~2 ~ {life_ticks:900}",
				"summon minecraft:vex ~ ~2 ~2 {life_ticks:900}",
				"summon minecraft:vex ~ ~2 ~-2 {life_ticks:900}"));
		t.put(650, bad("Фантомы", "фантомы пикируют! {message}", "&3ФАНТОМЫ",
				"minecraft:entity.phantom.ambient",
				"summon minecraft:phantom ~ ~12 ~",
				"summon minecraft:phantom ~6 ~12 ~6",
				"summon minecraft:phantom ~-6 ~12 ~-6",
				"summon minecraft:phantom ~6 ~14 ~-6"));
		t.put(750, bad("Разбойники", "патруль разбойников! {message}", "&8РАЗБОЙНИКИ",
				"minecraft:event.raid.horn",
				"summon minecraft:pillager ~5 ~ ~",
				"summon minecraft:pillager ~-5 ~ ~",
				"summon minecraft:pillager ~ ~ ~5",
				"summon minecraft:vindicator ~ ~ ~-5",
				"summon minecraft:vindicator ~4 ~ ~-4"));
		t.put(850, bad("Заклинатель", "заклинатель и его свита! {message}", "&5ЗАКЛИНАТЕЛЬ",
				"minecraft:entity.evoker.prepare_summon",
				"summon minecraft:evoker ~6 ~ ~",
				"summon minecraft:vindicator ~5 ~ ~2",
				"summon minecraft:vindicator ~5 ~ ~-2"));
		t.put(950, bad("Иссушение", "иссушение! {message}", "&8ИССУШЕНИЕ",
				"minecraft:entity.wither_skeleton.ambient",
				"effect give @s minecraft:wither 10 0 true",
				"summon minecraft:wither_skeleton ~4 ~ ~",
				"summon minecraft:wither_skeleton ~-4 ~ ~",
				"summon minecraft:wither_skeleton ~ ~ ~4"));
		t.put(1100, bad("Опустошитель", "ОПУСТОШИТЕЛЬ! {message}", "&4ОПУСТОШИТЕЛЬ",
				"minecraft:entity.ravager.roar",
				"summon minecraft:ravager ~6 ~ ~6"));
		t.put(1300, bad("Босс-зомби", "БОСС-ЗОМБИ вышел на охоту! {message}", "&4БОСС-ЗОМБИ",
				"minecraft:entity.ender_dragon.growl",
				"summon minecraft:zombie ~4 ~ ~4 {CustomName:\"Босс {user}\",CustomNameVisible:1b,Health:120f,PersistenceRequired:1b,"
						+ "attributes:[{id:\"minecraft:max_health\",base:120},{id:\"minecraft:scale\",base:1.8},{id:\"minecraft:attack_damage\",base:9},"
						+ "{id:\"minecraft:movement_speed\",base:0.3},{id:\"minecraft:knockback_resistance\",base:0.8}],"
						+ "equipment:{mainhand:{id:\"minecraft:iron_axe\",count:1},head:{id:\"minecraft:diamond_helmet\",count:1}}}").with(a -> a.pitch = 0.6f));
		t.put(1700, bad("Гасты", "гасты над головой! {message}", "&fГАСТЫ",
				"minecraft:entity.ghast.scream",
				"summon minecraft:ghast ~ ~15 ~8",
				"summon minecraft:ghast ~8 ~15 ~-8"));
		t.put(2500, bad("Хранитель", "ХРАНИТЕЛЬ ВЫШЕЛ ИЗ ГЛУБИН! {message}", "&1ХРАНИТЕЛЬ",
				"minecraft:entity.warden.emerge",
				"summon minecraft:warden ~6 ~ ~6"));
		t.put(4000, bad("Иссушитель", "ИССУШИТЕЛЬ! БЕГИ! {message}", "&0ИССУШИТЕЛЬ",
				"minecraft:entity.wither.spawn",
				"summon minecraft:wither ~ ~3 ~10"));

		// ---------- ★ Позитивные (25) ----------
		t.put(40, good("Перекус", "перекус за счёт зрителя! {message}", "&6ПЕРЕКУС",
				"minecraft:entity.player.burp",
				"give @s minecraft:cooked_beef 8",
				"give @s minecraft:golden_carrot 8"));
		t.put(60, good("Исцеление", "полное исцеление! {message}", "&dИСЦЕЛЕНИЕ",
				"minecraft:entity.zombie_villager.converted",
				"effect clear @s minecraft:poison",
				"effect clear @s minecraft:wither",
				"effect clear @s minecraft:hunger",
				"effect clear @s minecraft:blindness",
				"effect clear @s minecraft:darkness",
				"effect clear @s minecraft:nausea",
				"effect clear @s minecraft:slowness",
				"effect clear @s minecraft:mining_fatigue",
				"effect clear @s minecraft:weakness",
				"effect clear @s minecraft:levitation",
				"effect give @s minecraft:instant_health 1 1 true",
				"effect give @s minecraft:saturation 1 4 true",
				"effect give @s minecraft:regeneration 20 1 true"));
		t.put(80, good("Опыт", "+30 уровней опыта! {message}", "&a+30 УРОВНЕЙ",
				"minecraft:entity.player.levelup",
				"xp add @s 30 levels"));
		t.put(100, good("Салют", "салют в твою честь! {message}", "&bСАЛЮТ!",
				"minecraft:entity.firework_rocket.twinkle",
				"give @s minecraft:golden_apple 2",
				firework("~3 ~ ~3", "16711680,16776960"),
				"delay 10",
				firework("~-3 ~ ~3", "65535,16777215"),
				"delay 10",
				firework("~ ~ ~-3", "16711935,65280"),
				"particle minecraft:firework ~ ~2 ~ 2 2 2 0.2 300"));
		t.put(120, good("Ясный день", "ясный день! {message}", "&eЯСНЫЙ ДЕНЬ",
				"minecraft:block.note_block.pling",
				"time set day",
				"weather clear 12000",
				"effect give @s minecraft:regeneration 10 0 true"));
		t.put(140, good("Железо и золото", "железо и золото! {message}", "&fЖЕЛЕЗО И ЗОЛОТО",
				"minecraft:entity.item.pickup",
				"give @s minecraft:iron_ingot 32",
				"give @s minecraft:gold_ingot 16"));
		t.put(160, good("Щит", "щит на 5 минут! {message}", "&9ЩИТ",
				"minecraft:item.armor.equip_diamond",
				"effect give @s minecraft:resistance 120 2 true",
				"effect give @s minecraft:fire_resistance 300 0 true",
				"effect give @s minecraft:absorption 300 3 true"));
		t.put(180, good("Ускорение", "ускорение на 5 минут! {message}", "&bУСКОРЕНИЕ",
				"minecraft:entity.breeze.charge",
				"effect give @s minecraft:speed 300 1 true",
				"effect give @s minecraft:haste 300 1 true",
				"effect give @s minecraft:jump_boost 300 1 true"));
		t.put(200, good("Изумруды", "32 изумруда и почёт в деревне! {message}", "&aИЗУМРУДЫ",
				"minecraft:entity.villager.celebrate",
				"give @s minecraft:emerald 32",
				"effect give @s minecraft:hero_of_the_village 600 0 true"));
		t.put(240, good("Эндер-набор", "эндер-набор! {message}", "&5ЭНДЕР-НАБОР",
				"minecraft:entity.enderman.teleport",
				"give @s minecraft:ender_pearl 16",
				"give @s minecraft:ender_eye 4",
				"give @s minecraft:ender_chest 1"));
		t.put(260, good("Алмазы", "12 алмазов! {message}", "&bАЛМАЗЫ",
				"minecraft:block.amethyst_block.chime",
				"give @s minecraft:diamond 12"));
		t.put(300, good("Големы", "два железных телохранителя! {message}", "&fГОЛЕМЫ",
				"minecraft:entity.iron_golem.repair",
				"summon minecraft:iron_golem ~3 ~ ~ {PlayerCreated:1b}",
				"summon minecraft:iron_golem ~-3 ~ ~ {PlayerCreated:1b}"));
		t.put(350, good("Зелья", "набор зелий! {message}", "&dЗЕЛЬЯ",
				"minecraft:block.respawn_anchor.charge",
				"effect give @s minecraft:regeneration 120 1 true",
				"effect give @s minecraft:strength 180 1 true",
				"effect give @s minecraft:night_vision 600 0 true",
				"effect give @s minecraft:water_breathing 600 0 true"));
		t.put(400, good("Книги", "зачарованные книги! {message}", "&5КНИГИ",
				"minecraft:block.enchantment_table.use",
				book("mending", 1),
				book("unbreaking", 3),
				book("fortune", 3),
				book("protection", 4),
				book("sharpness", 5)));
		t.put(500, good("Алмазная броня", "комплект алмазной брони! {message}", "&bАЛМАЗНАЯ БРОНЯ",
				"minecraft:item.armor.equip_diamond",
				armor("diamond_helmet", "\"minecraft:protection\":3,\"minecraft:unbreaking\":2,\"minecraft:respiration\":3"),
				armor("diamond_chestplate", "\"minecraft:protection\":3,\"minecraft:unbreaking\":2"),
				armor("diamond_leggings", "\"minecraft:protection\":3,\"minecraft:unbreaking\":2"),
				armor("diamond_boots", "\"minecraft:protection\":3,\"minecraft:unbreaking\":2,\"minecraft:feather_falling\":4")));
		t.put(600, good("Супер-кирка", "СУПЕР-КИРКА! {message}", "&bСУПЕР-КИРКА",
				"minecraft:entity.player.levelup",
				"give @s minecraft:netherite_pickaxe[minecraft:enchantments={\"minecraft:efficiency\":5,\"minecraft:unbreaking\":3,\"minecraft:fortune\":3,\"minecraft:mending\":1},"
						+ "minecraft:custom_name={text:\"Кирка от {user}\",color:\"aqua\",italic:false}] 1"));
		t.put(700, good("Денежный дождь", "ДЕНЕЖНЫЙ ДОЖДЬ! {message}", "&6ДЕНЕЖНЫЙ ДОЖДЬ",
				"minecraft:entity.experience_orb.pickup",
				drop("diamond", 2, "~2 ~10 ~"), drop("emerald", 4, "~-2 ~10 ~1"), drop("gold_ingot", 6, "~ ~10 ~-2"),
				"delay 10",
				drop("diamond", 2, "~-1 ~10 ~2"), drop("emerald", 4, "~1 ~10 ~-1"), drop("gold_ingot", 6, "~2 ~10 ~2"),
				"delay 10",
				drop("diamond", 2, "~ ~10 ~"), drop("emerald", 4, "~-2 ~10 ~-2"), drop("experience_bottle", 8, "~1 ~10 ~1"),
				"particle minecraft:totem_of_undying ~ ~3 ~ 2 1 2 0.3 200"));
		t.put(800, good("Сундук сокровищ", "СУНДУК СОКРОВИЩ! {message}", "&6СУНДУК СОКРОВИЩ",
				"minecraft:block.chest.open",
				"give @s minecraft:diamond 16",
				"give @s minecraft:emerald 32",
				"give @s minecraft:gold_ingot 32",
				"give @s minecraft:netherite_scrap 4",
				"give @s minecraft:experience_bottle 16",
				"give @s minecraft:shulker_box 1"));
		t.put(900, good("Тотемы", "два тотема бессмертия! {message}", "&eТОТЕМЫ",
				"minecraft:item.totem.use",
				"give @s minecraft:totem_of_undying 2",
				"effect give @s minecraft:absorption 300 1 true"));
		t.put(1000, good("Меч легенды", "МЕЧ ЛЕГЕНДЫ! {message}", "&6МЕЧ ЛЕГЕНДЫ",
				"minecraft:item.armor.equip_netherite",
				"give @s minecraft:netherite_sword[minecraft:enchantments={\"minecraft:sharpness\":5,\"minecraft:looting\":3,\"minecraft:fire_aspect\":2,\"minecraft:unbreaking\":3,\"minecraft:mending\":1},"
						+ "minecraft:custom_name={text:\"Меч от {user}\",color:\"gold\",italic:false}] 1"));
		t.put(1200, good("Крылья", "КРЫЛЬЯ! {message}", "&fКРЫЛЬЯ",
				"minecraft:item.elytra.flying",
				"give @s minecraft:elytra[minecraft:enchantments={\"minecraft:unbreaking\":3,\"minecraft:mending\":1}] 1",
				"give @s minecraft:firework_rocket[minecraft:fireworks={flight_duration:3}] 64",
				"effect give @s minecraft:slow_falling 120 0 true"));
		t.put(1500, good("Незерит", "8 незеритовых слитков! {message}", "&8НЕЗЕРИТ",
				"minecraft:item.armor.equip_netherite",
				"give @s minecraft:netherite_ingot 8"));
		t.put(2000, good("Незеритовая броня", "ПОЛНЫЙ НЕЗЕРИТ! {message}", "&8НЕЗЕРИТОВАЯ БРОНЯ",
				"minecraft:item.armor.equip_netherite",
				armor("netherite_helmet", "\"minecraft:protection\":4,\"minecraft:unbreaking\":3,\"minecraft:mending\":1,\"minecraft:respiration\":3,\"minecraft:aqua_affinity\":1"),
				armor("netherite_chestplate", "\"minecraft:protection\":4,\"minecraft:unbreaking\":3,\"minecraft:mending\":1"),
				armor("netherite_leggings", "\"minecraft:protection\":4,\"minecraft:unbreaking\":3,\"minecraft:mending\":1"),
				armor("netherite_boots", "\"minecraft:protection\":4,\"minecraft:unbreaking\":3,\"minecraft:mending\":1,\"minecraft:feather_falling\":4,\"minecraft:depth_strider\":3")));
		t.put(3000, good("Божественность", "БОЖЕСТВЕННОСТЬ на 10 минут! {message}", "&eБОЖЕСТВЕННОСТЬ",
				"minecraft:block.beacon.activate",
				"effect give @s minecraft:health_boost 600 4 true",
				"effect give @s minecraft:absorption 600 4 true",
				"effect give @s minecraft:regeneration 600 1 true",
				"effect give @s minecraft:resistance 600 1 true",
				"effect give @s minecraft:strength 600 1 true",
				"effect give @s minecraft:fire_resistance 600 0 true",
				"effect give @s minecraft:speed 600 0 true",
				"effect give @s minecraft:haste 600 1 true",
				"effect give @s minecraft:hero_of_the_village 1200 0 true",
				"give @s minecraft:enchanted_golden_apple 4",
				"particle minecraft:end_rod ~ ~1 ~ 1 1 1 0.1 300"));
		t.put(5000, good("Легенда", "ЛЕГЕНДА СТРИМА! {message}", "&6ЛЕГЕНДА",
				"minecraft:ui.toast.challenge_complete",
				"give @s minecraft:diamond 64",
				"give @s minecraft:netherite_ingot 16",
				"give @s minecraft:nether_star 1",
				"give @s minecraft:totem_of_undying 2",
				"give @s minecraft:enchanted_golden_apple 8",
				"xp add @s 100 levels",
				"effect give @s minecraft:resistance 300 2 true",
				firework("~4 ~ ~4", "16766720,16777215"),
				"delay 10",
				firework("~-4 ~ ~4", "16766720,65535"),
				"delay 10",
				firework("~4 ~ ~-4", "16711935,16766720"),
				"delay 10",
				firework("~-4 ~ ~-4", "65280,16766720"),
				"particle minecraft:totem_of_undying ~ ~1 ~ 1 1 1 0.5 400").with(a -> {
			a.toast = "&6ЛЕГЕНДА СТРИМА";
			a.toastText = "&f{user} · {sum}";
			a.reply = "{user}, ты ЛЕГЕНДА стрима — спасибо за {sum}!";
		}));

		Map<String, Action> result = new LinkedHashMap<>();
		for (Map.Entry<Integer, Action> entry : t.entrySet()) {
			result.put(String.valueOf(entry.getKey()), entry.getValue());
		}
		return result;
	}

	// ---------- Классификация и ценник ----------

	public static boolean isBad(Action action) {
		return action != null && action.name != null && action.name.startsWith(BAD.trim());
	}

	public static boolean isGood(Action action) {
		return action != null && action.name != null && action.name.startsWith(GOOD.trim());
	}

	/** Название без префикса ☠ / ★ (для ценника). */
	public static String plainName(Action action) {
		if (action == null || action.name == null) {
			return "";
		}
		String name = action.name.trim();
		if (name.startsWith(BAD.trim()) || name.startsWith(GOOD.trim())) {
			name = name.substring(1).trim();
		}
		return name;
	}

	// ---------- Награды за баллы: случайное событие из ценника ----------

	/** Названия наград по умолчанию (создаются на Twitch / VK командами sync). */
	public static final String REWARD_BAD = "Пакость";
	public static final String REWARD_GOOD = "Подарок";
	/** Стоимость наград по умолчанию в баллах канала. */
	public static final int REWARD_COST = 250;

	/**
	 * Две награды за баллы канала: «Пакость» — случайное ☠ событие, «Подарок» — случайное ★ событие
	 * (равновероятно из всех записей ценника соответствующего знака).
	 */
	public static Map<String, Action> poolRewards() {
		Map<String, Action> rewards = new LinkedHashMap<>();
		rewards.put(REWARD_BAD, new Action(
				"&c☠ &d{user} &7активировал(а) «{reward}» — выпало: &c{picked}&7! {picked_text}",
				"", "&f{user} · {picked}"
		).with(a -> {
			a.pool = "bad";
			a.cost = REWARD_COST;
			a.prompt = "Случайная пакость стримеру — одна из 25 (список: !плохое)";
			a.color = "#8B0000";
			a.reply = "{user}, выпало: ☠ {picked}!";
		}));
		rewards.put(REWARD_GOOD, new Action(
				"&a★ &d{user} &7активировал(а) «{reward}» — выпало: &a{picked}&7! {picked_text}",
				"", "&f{user} · {picked}"
		).with(a -> {
			a.pool = "good";
			a.cost = REWARD_COST;
			a.prompt = "Случайный подарок стримеру — один из 25 (список: !хорошее)";
			a.color = "#1E8449";
			a.reply = "{user}, выпало: ★ {picked}!";
		}));
		return rewards;
	}

	/**
	 * Записи ценника, из которых выбирает награда с данным pool: "bad" — ☠, "good" — ★, "any" — и те и другие.
	 * Выключенные и пустые записи пропускаются.
	 */
	public static List<Action> poolCandidates(Map<String, Action> tiers, String pool) {
		List<Action> result = new ArrayList<>();
		if (tiers == null || pool == null) {
			return result;
		}
		String which = pool.trim().toLowerCase(Locale.ROOT);
		for (Action action : sorted(tiers).values()) {
			if (action == null || !action.enabled || action.isEmpty()) {
				continue;
			}
			boolean bad = isBad(action);
			boolean good = isGood(action);
			boolean ok = switch (which) {
				case "bad" -> bad;
				case "good" -> good;
				case "any" -> bad || good;
				default -> false;
			};
			if (ok) {
				result.add(action);
			}
		}
		return result;
	}

	/** Случайная запись ценника для награды с pool (равновероятно); null, если подходящих записей нет. */
	public static Action pick(Map<String, Action> tiers, String pool, java.util.random.RandomGenerator random) {
		List<Action> candidates = poolCandidates(tiers, pool);
		if (candidates.isEmpty()) {
			return null;
		}
		return candidates.get(random.nextInt(candidates.size()));
	}

	/**
	 * Описание события без «{user} задонатил(а) {sum} — » и без {message}: «тыква на голову!».
	 * Подставляется в награды как {picked_text}.
	 */
	public static String flavor(Action action) {
		if (action == null || action.message == null) {
			return "";
		}
		String text = action.message;
		int dash = text.indexOf(" — ");
		if (dash >= 0 && text.substring(0, dash).contains("{sum}")) {
			text = text.substring(dash + 3);
		}
		text = text.replace("{message}", "").replace("{sum}", "").trim();
		return text;
	}

	/**
	 * Копия записи ценника для запуска от награды: заголовок, звук, тост и команды остаются,
	 * а сообщение/подзаголовок/ответ убираются — их даёт само действие награды (там нет «задонатил(а)»).
	 */
	public static Action asPoolPick(Action picked) {
		Action copy = picked.copy();
		copy.message = "";
		copy.subtitle = "";
		copy.actionbar = "";
		copy.reply = "";
		copy.chance = 100;
		copy.cooldown = 0;
		copy.userCooldown = 0;
		return copy;
	}

	/** Таблица порогов, отсортированная по сумме (ключи, которые не числа, пропускаются). */
	public static TreeMap<Integer, Action> sorted(Map<String, Action> tiers) {
		TreeMap<Integer, Action> sorted = new TreeMap<>();
		if (tiers == null) {
			return sorted;
		}
		for (Map.Entry<String, Action> entry : tiers.entrySet()) {
			if (entry.getValue() == null || entry.getKey() == null) {
				continue;
			}
			try {
				sorted.put(Integer.parseInt(entry.getKey().trim()), entry.getValue());
			} catch (NumberFormatException ignored) {
				// не число — не порог
			}
		}
		return sorted;
	}

	/**
	 * Ценник одной строкой: «30 Тыква · 50 Голод · …».
	 *
	 * @param which 'b' — только плохие, 'g' — только хорошие, иначе все (с префиксами)
	 */
	public static String priceLine(Map<String, Action> tiers, char which) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<Integer, Action> entry : sorted(tiers).entrySet()) {
			Action action = entry.getValue();
			if (!action.enabled) {
				continue;
			}
			if (which == 'b' && !isBad(action) || which == 'g' && !isGood(action)) {
				continue;
			}
			String name = which == 'b' || which == 'g' ? plainName(action) : (action.name == null ? "" : action.name.trim());
			if (name.isEmpty()) {
				continue;
			}
			if (sb.length() > 0) {
				sb.append(" · ");
			}
			sb.append(entry.getKey()).append(' ').append(name);
		}
		return sb.toString();
	}

	/** Плейсхолдеры {donation_prices_bad} {donation_prices_good} {donation_prices} для ответов в чат. */
	public static Map<String, String> placeholders(Map<String, Action> tiers) {
		Map<String, String> vars = new LinkedHashMap<>();
		vars.put("donation_prices_bad", priceLine(tiers, 'b'));
		vars.put("donation_prices_good", priceLine(tiers, 'g'));
		vars.put("donation_prices", priceLine(tiers, 'a'));
		return vars;
	}

	/**
	 * Строки для чата игры: «☠ 30 ₽ — Тыква» … (отключённые помечены).
	 *
	 * @param which 'b' — плохие, 'g' — хорошие, 'o' — прочие (без префикса), иначе все
	 */
	public static List<String> priceLines(Map<String, Action> tiers, String symbol, char which) {
		List<String> lines = new ArrayList<>();
		for (Map.Entry<Integer, Action> entry : sorted(tiers).entrySet()) {
			Action action = entry.getValue();
			boolean bad = isBad(action);
			boolean good = isGood(action);
			if (which == 'b' && !bad || which == 'g' && !good || which == 'o' && (bad || good)) {
				continue;
			}
			String color = isBad(action) ? "§c" : isGood(action) ? "§a" : "§7";
			String mark = isBad(action) ? "☠" : isGood(action) ? "★" : "•";
			String name = plainName(action);
			lines.add(color + mark + " §f" + entry.getKey() + " " + symbol + " §7— " + color + (name.isEmpty() ? "(без названия)" : name)
					+ (action.enabled ? "" : " §8(выкл)"));
		}
		return lines;
	}

	/** Таблица совпадает со старым ценником по умолчанию (10 записей до 1.5.0) — пользователь её не трогал. */
	public static boolean isLegacyDefault(Map<String, Action> tiers) {
		if (tiers == null || tiers.size() != LEGACY.size()) {
			return false;
		}
		for (Map.Entry<String, String> legacy : LEGACY.entrySet()) {
			Action action = tiers.get(legacy.getKey());
			if (action == null || action.name == null || !action.name.trim().equalsIgnoreCase(legacy.getValue())) {
				return false;
			}
		}
		return true;
	}

	// ---------- Конструкторы записей ----------

	private static Action bad(String name, String text, String title, String sound, String... commands) {
		return action(BAD + name, MSG + text, title, SUB, sound, commands);
	}

	private static Action good(String name, String text, String title, String sound, String... commands) {
		return action(GOOD + name, MSG + text, title, SUB, sound, commands);
	}

	private static Action action(String name, String message, String title, String subtitle, String sound, String... commands) {
		Action action = new Action(message, title, subtitle, commands);
		action.name = name;
		action.sound = sound == null ? "" : sound;
		return action;
	}

	/** Ракета фейерверка с настоящим взрывом (большой шар, два цвета, след). */
	private static String firework(String pos, String colors) {
		return "summon minecraft:firework_rocket " + pos + " {LifeTime:25,FireworksItem:{id:\"minecraft:firework_rocket\",count:1,"
				+ "components:{\"minecraft:fireworks\":{flight_duration:1,explosions:[{shape:\"large_ball\",colors:[I;" + colors + "],has_trail:true}]}}}}";
	}

	private static String book(String enchantment, int level) {
		return "give @s minecraft:enchanted_book[minecraft:stored_enchantments={\"minecraft:" + enchantment + "\":" + level + "}] 1";
	}

	private static String armor(String item, String enchantments) {
		return "give @s minecraft:" + item + "[minecraft:enchantments={" + enchantments + "}] 1";
	}

	private static String drop(String item, int count, String pos) {
		return "summon minecraft:item " + pos + " {Item:{id:\"minecraft:" + item + "\",count:" + count + "}}";
	}

	/** Для тестов и диагностики: корневые команды, которые использует ценник. */
	public static List<String> roots(Map<String, Action> tiers) {
		List<String> roots = new ArrayList<>();
		for (Action action : tiers.values()) {
			for (String command : action.commands) {
				String root = command.trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
				if (!roots.contains(root)) {
					roots.add(root);
				}
			}
		}
		return roots;
	}
}
