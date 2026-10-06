import dev.dedworkshop.twitchcraft.artifacts.Artifact;
import dev.dedworkshop.twitchcraft.artifacts.ArtifactConfig;
import dev.dedworkshop.twitchcraft.artifacts.ArtifactCurse;
import dev.dedworkshop.twitchcraft.artifacts.ArtifactCatalog;
import dev.dedworkshop.twitchcraft.artifacts.ArtifactDrops;
import dev.dedworkshop.twitchcraft.artifacts.ArtifactEffects;
import dev.dedworkshop.twitchcraft.artifacts.ArtifactFactory;
import dev.dedworkshop.twitchcraft.artifacts.ArtifactRarity;
import dev.dedworkshop.twitchcraft.artifacts.ArtifactStore;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Логические тесты аддона «Артефакты» — без запуска Minecraft.
 * Проверяют перенос механик серверного аддона CHRDK REBORN:
 * редкости, проклятия и их рост, разрушение на 100 %, лимит артефактов,
 * правила выдачи за события Twitch и сборку команд Minecraft.
 */
public class ArtifactsHarness {
	static int failures = 0;
	static int total = 0;

	static void check(String name, boolean condition) {
		total++;
		System.out.println((condition ? "  OK   " : "  FAIL ") + name);
		if (!condition) {
			failures++;
		}
	}

	static ArtifactConfig config() {
		ArtifactConfig config = ArtifactConfig.defaults();
		config.normalize();
		return config;
	}

	public static void main(String[] args) throws Exception {
		Random random = new Random(20261006L);

		// ---------------- Редкости ----------------
		System.out.println("== Редкости ==");
		check("шесть редкостей, мифический — самый редкий по умолчанию",
				ArtifactRarity.values().length == 6 && ArtifactRarity.MYTHIC.defaultChance < ArtifactRarity.LEGENDARY.defaultChance);
		check("byId терпим к регистру и мусору",
				ArtifactRarity.byId("MYTHIC") == ArtifactRarity.MYTHIC && ArtifactRarity.byId("rare") == ArtifactRarity.RARE
						&& ArtifactRarity.byId(null) == ArtifactRarity.COMMON && ArtifactRarity.byId("чепуха") == ArtifactRarity.COMMON);
		check("мифический — единственный «без проклятия»", ArtifactRarity.MYTHIC.isMythic() && !ArtifactRarity.LEGENDARY.isMythic());

		Map<ArtifactRarity, Integer> rolls = new HashMap<>();
		int count = 200_000;
		for (int i = 0; i < count; i++) {
			ArtifactRarity rarity = ArtifactRarity.roll(random, ArtifactRarity.defaultChances());
			rolls.merge(rarity, 1, Integer::sum);
		}
		boolean allSeen = rolls.size() == ArtifactRarity.values().length;
		double mythicShare = rolls.getOrDefault(ArtifactRarity.MYTHIC, 0) * 100.0 / count;
		double commonShare = rolls.getOrDefault(ArtifactRarity.COMMON, 0) * 100.0 / count;
		check("за 200 000 бросков выпали все редкости", allSeen);
		check("доли близки к настройкам (обычный ~40 %, мифический ~0,1 %)",
				Math.abs(commonShare - 40) < 3 && Math.abs(mythicShare - 0.1) < 0.1);
		check("мифических заметно меньше легендарных", rolls.getOrDefault(ArtifactRarity.MYTHIC, 0) * 10 < rolls.getOrDefault(ArtifactRarity.LEGENDARY, 0));
		double[] noMythic = ArtifactRarity.defaultChances();
		noMythic[ArtifactRarity.MYTHIC.ordinal()] = 0;
		boolean mythicFound = false;
		for (int i = 0; i < 50_000 && !mythicFound; i++) {
			mythicFound = ArtifactRarity.roll(random, noMythic) == ArtifactRarity.MYTHIC;
		}
		check("нулевой шанс мифического — он не выпадает", !mythicFound);
		double[] empty = new double[6];
		check("без шансов возвращается обычный", ArtifactRarity.roll(random, empty) == ArtifactRarity.COMMON);

		// ---------------- Каталог ----------------
		System.out.println("== Каталог (перенос с сервера) ==");
		check("56 баффов: 53 обычных и 3 мифических", ArtifactCatalog.buffs().size() == 56);
		check("13 проклятий", ArtifactCatalog.curses().size() == 13);
		check("35 именных артефактов", ArtifactCatalog.templates().size() == 35);
		check("три особых мифических (Перо Феникса, Корона Бездны, Сердце Дракона)",
				ArtifactCatalog.specials().size() == 3 && ArtifactCatalog.templateByName("✨ Перо Феникса") != null);
		boolean templatesOk = true;
		for (ArtifactCatalog.Template template : ArtifactCatalog.templates()) {
			templatesOk &= template.name() != null && !template.name().isBlank();
			templatesOk &= template.material().startsWith("minecraft:");
			templatesOk &= ArtifactCatalog.hasBuff(template.buff());
			templatesOk &= template.level() >= 1 && template.level() <= 5;
			templatesOk &= template.rarity() != null;
		}
		check("у каждого именного артефакта есть материал, бафф, уровень и редкость", templatesOk);
		check("редкости всех шести видов представлены в каталоге",
				ArtifactCatalog.templates(ArtifactRarity.MYTHIC).size() == 3
						&& ArtifactCatalog.templates(ArtifactRarity.LEGENDARY).size() == 4
						&& ArtifactCatalog.templates(ArtifactRarity.EPIC).size() == 6
						&& ArtifactCatalog.templates(ArtifactRarity.RARE).size() == 9
						&& ArtifactCatalog.templates(ArtifactRarity.COMMON).size() == 13);
		boolean buffsOk = true;
		int withEffect = 0;
		for (var buff : ArtifactCatalog.buffs()) {
			buffsOk &= buff.id() != null && !buff.id().isBlank() && buff.description() != null && !buff.description().isBlank();
			if (buff.hasEffect()) {
				withEffect++;
				buffsOk &= buff.effect().startsWith("minecraft:");
			}
		}
		check("у всех баффов есть id и описание", buffsOk);
		check("часть баффов выдаётся настоящими эффектами Minecraft (" + withEffect + " из 56)", withEffect >= 20);
		check("проклятия: NONE нет в списке, но распознаётся как «без проклятия»",
				ArtifactCatalog.curse(ArtifactCurse.NONE) == null && ArtifactCatalog.curse("SLOWNESS") != null
						&& ArtifactCatalog.curse("slowness").hasEffect());
		check("неизвестное проклятие не роняет аддон", ArtifactCatalog.curse("ЧУЖОЕ") != null && !ArtifactCatalog.curse("ЧУЖОЕ").hasEffect());
		check("неизвестный бафф описывается заглушкой", ArtifactCatalog.buff("NO_SUCH_BUFF").description().contains("NO_SUCH_BUFF"));
		check("материалы — ванильные предметы", !ArtifactCatalog.materials().isEmpty()
				&& ArtifactCatalog.materials().stream().allMatch(material -> material.startsWith("minecraft:")));

		// ---------------- Создание артефактов ----------------
		System.out.println("== Создание артефактов ==");
		ArtifactConfig cfg = config();
		Artifact mythic = null;
		boolean genericOk = true;
		boolean curseRulesOk = true;
		boolean markerOk = true;
		int mythicCount = 0;
		for (int i = 0; i < 20000; i++) {
			Artifact artifact = ArtifactFactory.create(random, cfg, "bits", "Зритель" + i);
			genericOk &= artifact.id != null && artifact.id.length() == 4;
			genericOk &= artifact.name != null && !artifact.name.isBlank();
			genericOk &= artifact.material.startsWith("minecraft:");
			genericOk &= ArtifactCatalog.hasBuff(artifact.buff);
			genericOk &= artifact.buffLevel >= 1 && artifact.buffLevel <= 5;
			genericOk &= artifact.cursePercent == 0 && artifact.wornMillis == 0;
			if (artifact.mythic) {
				mythicCount++;
				mythic = artifact;
				curseRulesOk &= ArtifactCurse.NONE.equals(artifact.curse) && !artifact.hasCurse();
				curseRulesOk &= artifact.buffLevel == 5;
			} else {
				curseRulesOk &= ArtifactCatalog.hasCurse(artifact.curse) && artifact.hasCurse();
			}
			markerOk &= artifact.id.equals(ArtifactFactory.extractId(artifact.coloredName()));
		}
		check("20 000 артефактов: id, название, материал, бафф, уровень — в порядке", genericOk);
		check("мифические без проклятия и 5-го уровня, остальные с проклятием", curseRulesOk && mythicCount > 0);
		check("метка ⟦tc:id⟧ читается из названия предмета", markerOk);
		check("пример мифического артефакта: 5-й уровень, без проклятия",
				mythic != null && mythic.buffLevel == 5 && !mythic.hasCurse());
		check("чужая вещь не считается артефактом",
				!ArtifactFactory.isArtifactName("Алмаз") && !ArtifactFactory.isArtifactName(null)
						&& !ArtifactFactory.isArtifactName("⟦tc:⟧"));

		Artifact forced = ArtifactFactory.create(random, cfg, ArtifactRarity.MYTHIC, "manual", "Тест");
		check("ручная выдача мифического даёт мифический",
				forced.rarity() == ArtifactRarity.MYTHIC && forced.mythic && ArtifactCurse.NONE.equals(forced.curse));
		boolean namedFound = false;
		for (int i = 0; i < 200 && !namedFound; i++) {
			namedFound = ArtifactCatalog.templateByName(ArtifactFactory.create(random, cfg, "bits", "named").name) != null;
		}
		check("именные артефакты встречаются (шанс 20 %)", namedFound);

		// ---------------- Команды Minecraft ----------------
		System.out.println("== Команды Minecraft ==");
		Artifact sample = new Artifact();
		sample.id = "a1b2";
		sample.name = "Тестовый Артефакт";
		sample.material = "minecraft:nether_star";
		sample.rarity = ArtifactRarity.EPIC.id;
		sample.buff = "SPEED";
		sample.buffLevel = 3;
		sample.curse = "SLOWNESS";
		sample.cursePercent = 42;
		sample.foundBy = "Зритель";
		String give = ArtifactFactory.giveCommand(sample, cfg);
		check("give: материал, метка и лор предмета",
				give.startsWith("give @s minecraft:nether_star[") && give.contains("minecraft:custom_name=\"")
						&& give.contains("⟦tc:a1b2⟧") && give.contains("minecraft:lore=[") && give.endsWith("] 1"));
		check("give: в лоре есть бафф, проклятие и процент",
				give.contains("Скорость передвижения") && give.contains("Замедление") && give.contains("42 %"));
		check("give: парные кавычки сбалансированы", give.chars().filter(ch -> ch == '"').count() % 2 == 0);
		ArtifactConfig withData = config();
		withData.writeCustomData = true;
		check("give: custom_data только по настройке",
				!give.contains("custom_data") && ArtifactFactory.giveCommand(sample, withData).contains("minecraft:custom_data={twitchcraft_artifact:{"));
		List<String> destroy = ArtifactFactory.destroyCommands(sample, 5);
		check("уничтожение: сначала точный слот, потом /clear",
				destroy.size() == 2 && destroy.get(0).equals("item replace entity @s hotbar.5 with air")
						&& destroy.get(1).equals("clear @s minecraft:nether_star 1"));
			check("слоты 9…35 — инвентарь, 36…40 — броня и вторая рука",
				ArtifactFactory.slotName(20).equals("inventory.11") && ArtifactFactory.slotName(36).equals("armor.feet")
						&& ArtifactFactory.slotName(39).equals("armor.head") && ArtifactFactory.slotName(40).equals("weapon.offhand")
						&& ArtifactFactory.slotName(999) == null && ArtifactFactory.slotName(-1) == null);

		// ---------------- Эффекты ----------------
		System.out.println("== Эффекты баффов и проклятий ==");
		check("уровень баффа превращается в усилитель 0…4",
				ArtifactEffects.buffAmplifier(1) == 0 && ArtifactEffects.buffAmplifier(3) == 2 && ArtifactEffects.buffAmplifier(5) == 4
						&& ArtifactEffects.buffAmplifier(99) == 4);
		check("проклятие растёт по уровням: 0 % — не действует, 40 % — II уровень, 70 % и выше — III (максимум)",
				ArtifactEffects.curseAmplifier(0) == -1 && ArtifactEffects.curseAmplifier(40) == 1
						&& ArtifactEffects.curseAmplifier(70) == 2 && ArtifactEffects.curseAmplifier(100) == 2);
		Artifact speed = new Artifact();
		speed.id = "0001";
		speed.buff = "SPEED";
		speed.buffLevel = 3;
		speed.curse = "SLOWNESS";
		speed.cursePercent = 70;
		speed.present = true;
		List<String> commands = ArtifactEffects.commands(List.of(speed), cfg, 30);
		check("эффекты выдаются одной командой на эффект с сильнейшим уровнем",
				commands.size() == 2 && commands.contains("effect give @s minecraft:speed 30 2 true")
						&& commands.contains("effect give @s minecraft:slowness 30 2 true"));
		Artifact noEffect = new Artifact();
		noEffect.id = "0002";
		noEffect.buff = "VAMPIRISM";
		noEffect.curse = ArtifactCurse.NONE;
		noEffect.mythic = true;
		noEffect.present = true;
		check("бафф без ванильного эффекта не выдаёт команд", ArtifactEffects.commands(List.of(noEffect), cfg, 30).isEmpty());
		Artifact absent = new Artifact();
		absent.id = "0003";
		absent.buff = "SPEED";
		absent.buffLevel = 1;
		absent.curse = ArtifactCurse.NONE;
		absent.present = false;
		check("артефакт не в инвентаре не действует", ArtifactEffects.commands(List.of(absent), cfg, 30).isEmpty());
		check("описание артефакта содержит бафф и его проклятие",
				ArtifactEffects.describe(speed).contains("Скорость") && ArtifactEffects.describe(speed).contains("Замедление"));

		// ---------------- Правила выдачи ----------------
		System.out.println("== Правила выдачи за события ==");
		ArtifactConfig drops = config();
		drops.drops.bitsPerArtifact = 100;
		drops.drops.donationPerArtifact = 100;
		drops.drops.subsPerArtifact = 1;
		drops.drops.giftSubsPerArtifact = 5;
		drops.drops.raidViewersPerArtifact = 10;
		ArtifactDrops rules = new ArtifactDrops();
		check("битсы копятся: 60 битсов — ещё нет, +50 — уже один",
				rules.countFor(TwitchEvent.simple(TwitchEvent.Type.CHEER, "A", "a", 60, "", "", "1"), drops, random) == 0
						&& rules.countFor(TwitchEvent.simple(TwitchEvent.Type.CHEER, "A", "a", 50, "", "", "1"), drops, random) == 1
						&& rules.bitsRemainder() == 10);
		check("битсы: 250 сразу — два артефакта и остаток 50",
				rules.countFor(TwitchEvent.simple(TwitchEvent.Type.CHEER, "A", "a", 240, "", "", "1"), drops, random) == 2
						&& rules.bitsRemainder() == 50);
		check("донаты: 250 ₽ шагом 100 — два артефакта",
				rules.countFor(TwitchEvent.simple(TwitchEvent.Type.DONATION, "B", "b", 250, "", "DonationAlerts", ""), drops, random) == 2
						&& rules.donationRemainder() == 50);
		check("подписки: ресаб на 2 месяца — два артефакта",
				rules.countFor(TwitchEvent.simple(TwitchEvent.Type.RESUB, "C", "c", 2, "", "", "2"), drops, random) == 2);
		check("подаренные подписки: 7 подарков шагом 5 — один артефакт и остаток 2",
				rules.countFor(TwitchEvent.simple(TwitchEvent.Type.GIFT_SUB, "D", "d", 7, "", "", "1"), drops, random) == 1
						&& rules.giftSubsRemainder() == 2);
		check("рейд: 25 зрителей шагом 10 — два артефакта",
				rules.countFor(TwitchEvent.simple(TwitchEvent.Type.RAID, "E", "e", 25, "", "", ""), drops, random) == 2);
		check("награда за баллы канала «Купить артефакт» — артефакт",
				rules.countFor(TwitchEvent.simple(TwitchEvent.Type.REWARD, "F", "f", 250, "", "Купить артефакт", ""), drops, random) == 1);
		check("другие награды артефакт не дают",
				rules.countFor(TwitchEvent.simple(TwitchEvent.Type.REWARD, "F", "f", 250, "", "Сменить погоду", ""), drops, random) == 0);
		check("совпадение названия награды — без учёта регистра",
				ArtifactDrops.matchesReward("ARTIFACT DROP", List.of("артефакт", "artifact"))
						&& !ArtifactDrops.matchesReward(null, List.of("artifact")));
		ArtifactConfig bossAlways = config();
		bossAlways.drops.bossKillChance = 100;
		ArtifactConfig bossNever = config();
		bossNever.drops.bossKillChance = 0;
		check("босс: шанс 100 % — артефакт, шанс 0 % — нет",
				new ArtifactDrops().countFor(TwitchEvent.game(TwitchEvent.GAME_BOSS, "Игрок", "Wither", "", 1, false), bossAlways, random) == 1
						&& new ArtifactDrops().countFor(TwitchEvent.game(TwitchEvent.GAME_BOSS, "Игрок", "Wither", "", 1, false), bossNever, random) == 0);
		check("смерть в игре артефактов не даёт",
				new ArtifactDrops().countFor(TwitchEvent.game(TwitchEvent.GAME_DEATH, "Игрок", "упал", "", 1, false), bossAlways, random) == 0);
		ArtifactConfig allOff = config();
		allOff.drops.bits = false;
		allOff.drops.donations = false;
		allOff.drops.subs = false;
		allOff.drops.giftSubs = false;
		allOff.drops.raids = false;
		allOff.drops.rewards = false;
		allOff.drops.bossKills = false;
		ArtifactDrops off = new ArtifactDrops();
		check("выключенные источники ничего не выдают",
				off.countFor(TwitchEvent.simple(TwitchEvent.Type.CHEER, "A", "a", 9999, "", "", ""), allOff, random) == 0
						&& off.countFor(TwitchEvent.simple(TwitchEvent.Type.DONATION, "A", "a", 9999, "", "", ""), allOff, random) == 0
						&& off.countFor(TwitchEvent.game(TwitchEvent.GAME_BOSS, "P", "b", "", 1, false), allOff, random) == 0);
		check("за одно событие — не больше " + ArtifactDrops.MAX_PER_EVENT,
				new ArtifactDrops().countFor(TwitchEvent.simple(TwitchEvent.Type.SUBSCRIBE, "A", "a", 50, "", "", "1"), drops, random) == ArtifactDrops.MAX_PER_EVENT);
		check("источник события подписывается по-русски",
				ArtifactDrops.sourceOf(TwitchEvent.simple(TwitchEvent.Type.CHEER, "A", "a", 1, "", "", "")).equals("bits")
						&& ArtifactDrops.sourceOf(TwitchEvent.game(TwitchEvent.GAME_BOSS, "P", "b", "", 1, false)).equals("boss"));

		// ---------------- Проклятие и разрушение ----------------
		System.out.println("== Проклятие: рост и разрушение ==");
		Path dir = Files.createTempDirectory("twitchcraft-artifacts-test");
		ArtifactConfig storeConfig = config();
		ArtifactStore store = ArtifactStore.load(dir.resolve(ArtifactStore.FILE_NAME));
		Artifact cursed = new Artifact();
		cursed.id = "1111";
		cursed.name = "Проклятая вещь";
		cursed.material = "minecraft:diamond";
		cursed.rarity = ArtifactRarity.RARE.id;
		cursed.buff = "SPEED";
		cursed.buffLevel = 2;
		cursed.curse = "SLOWNESS";
		cursed.present = true;
		store.add(cursed, random);
		check("артефакт добавлен, статистика выбитых и редкостей обновилась",
				store.activeCount() == 1 && store.generated == 1 && store.byRarity.get("rare") == 1);
		long hour = 60 * 60 * 1000L;
		check("через час ношения — 1 % проклятия",
				store.advanceCurse(hour, storeConfig).isEmpty() && Math.abs(cursed.cursePercent - 1) < 0.001);
		check("проклятие растёт плавно: за 90 минут — 1,5 %",
				store.advanceCurse(hour / 2, storeConfig).isEmpty() && Math.abs(cursed.cursePercent - 1.5) < 0.001);
		store.advanceCurse(hour * 99, storeConfig);
		check("к 100 % артефакт рассыпается", cursed.destroyed && store.destroyed == 1 && store.activeCount() == 0
				&& Math.abs(cursed.cursePercent - storeConfig.curse.breakAt) < 0.001);
		List<Artifact> secondBroken = store.advanceCurse(hour, storeConfig);
		check("разрушенный артефакт больше не «стареет»", secondBroken.isEmpty() && store.destroyed == 1);

		Artifact mythicStored = new Artifact();
		mythicStored.id = "2222";
		mythicStored.name = "Мифическая реликвия";
		mythicStored.rarity = ArtifactRarity.MYTHIC.id;
		mythicStored.mythic = true;
		mythicStored.curse = ArtifactCurse.NONE;
		mythicStored.present = true;
		store.add(mythicStored, random);
		store.advanceCurse(hour * 500, storeConfig);
		check("мифический артефакт не стареет и не разрушается",
				!mythicStored.destroyed && mythicStored.cursePercent == 0 && !mythicStored.hasCurse());

		Artifact dropped = new Artifact();
		dropped.id = "3333";
		dropped.rarity = ArtifactRarity.COMMON.id;
		dropped.curse = "HUNGER";
		dropped.present = false;
		store.add(dropped, random);
		store.advanceCurse(hour * 10, storeConfig);
		check("артефакт не в инвентаре не набирает проклятие", dropped.cursePercent == 0 && !dropped.destroyed);

		dropped.present = true;
		ArtifactConfig curseOff = config();
		curseOff.curse.enabled = false;
		store.advanceCurse(hour * 10, curseOff);
		check("выключенный рост проклятия ничего не делает", dropped.cursePercent == 0);
		dropped.curse = "FRAGILE";
		dropped.destroyed = false;
		store.advanceCurse(hour * 24, storeConfig);
		check("хрупкий артефакт (FRAGILE) рассыпается через сутки ношения", dropped.destroyed);

		// ---------------- «Выгоревшие» артефакты ----------------
		System.out.println("== «Выгоревший» артефакт: запись освобождает лимит ==");
		ArtifactStore burntStore = ArtifactStore.load(dir.resolve("burnt.json"));
		Artifact burnt = new Artifact();
		burnt.id = "b0rn";
		burnt.name = "Неубираемый";
		burnt.rarity = ArtifactRarity.COMMON.id;
		burnt.buff = "SPEED";
		burnt.curse = "GREED";
		burnt.present = true;
		burnt.inactive = true; // предмет не удалось убрать командами — артефакт «выгорел»
		burntStore.add(burnt, random);
		check("выгоревший артефакт занимает лимит, пока предмет в инвентаре",
				burntStore.activeCount() == 1 && burntStore.forgetBurntWithoutItem() == 0
						&& burntStore.activeCount() == 1);
		burnt.present = false; // игрок выбросил предмет руками
		check("запись выгоревшего артефакта без предмета убирается",
				burntStore.forgetBurntWithoutItem() == 1 && burntStore.activeCount() == 0
						&& burntStore.byId("b0rn") == null);
		check("статистика выбитого сохраняется, даже когда запись убрана",
				burntStore.generated == 1 && burntStore.byRarity.get("common") == 1);
		Artifact broken = new Artifact();
		broken.id = "c0rr";
		broken.rarity = ArtifactRarity.COMMON.id;
		broken.curse = "GREED";
		broken.destroyed = true; // рассыпался от проклятия — запись остаётся для истории
		broken.present = false;
		burntStore.add(broken, random);
		check("разрушенный проклятием артефакт не удаляется из записей",
				burntStore.forgetBurntWithoutItem() == 0 && burntStore.byId("c0rr") != null);
		Artifact held = new Artifact();
		held.id = "h3ld";
		held.rarity = ArtifactRarity.RARE.id;
		held.curse = "GREED";
		held.present = false;
		burntStore.add(held, random);
		check("обычный артефакт без предмета остаётся (мог лежать в сундуке)",
				burntStore.forgetBurntWithoutItem() == 0 && burntStore.byId("h3ld") != null);

		// ---------------- Лимит и сохранение ----------------
		System.out.println("== Лимит, статистика, сохранение ==");
		ArtifactConfig limitConfig = config();
		limitConfig.maxArtifacts = 2;
		ArtifactStore limitStore = ArtifactStore.load(dir.resolve("limit.json"));
		for (int i = 0; i < 5; i++) {
			Artifact artifact = new Artifact();
			artifact.id = String.format("%04d", i);
			artifact.rarity = ArtifactRarity.COMMON.id;
			artifact.buff = "SPEED";
			artifact.curse = "GREED";
			limitStore.add(artifact, random);
		}
		check("лимит артефактов: 5 при настройке 2 — выдача запрещена, при 6 — разрешена",
				limitStore.activeCount() == 5 && !limitStore.canAdd(limitConfig.maxArtifacts) && limitStore.canAdd(6));
		limitStore.byViewer.merge("Зритель", 3, Integer::sum);
		limitStore.byViewer.merge("Другой", 1, Integer::sum);
		check("топ зрителей сортируется по количеству",
				limitStore.topViewers(1).get(0).getKey().equals("Зритель") && limitStore.topViewers(5).size() == 2);

		ArtifactStore roundTrip = ArtifactStore.load(dir.resolve("roundtrip.json"));
		Artifact saved = new Artifact();
		saved.id = "abcd";
		saved.name = "Сохранённый";
		saved.material = "minecraft:emerald";
		saved.rarity = ArtifactRarity.LEGENDARY.id;
		saved.buff = "RESISTANCE";
		saved.buffLevel = 4;
		saved.curse = "WEAKNESS";
		saved.cursePercent = 37;
		saved.foundBy = "Зритель";
		roundTrip.add(saved, random);
		roundTrip.save();
		ArtifactStore loaded = ArtifactStore.load(dir.resolve("roundtrip.json"));
		Artifact restored = loaded.byId("abcd");
		check("состояние переживает перезапуск (id, проклятие, статистика)",
				restored != null && restored.name.equals("Сохранённый") && Math.abs(restored.cursePercent - 37) < 0.001
						&& restored.buffLevel == 4 && restored.foundBy.equals("Зритель")
						&& loaded.byRarity.get("legendary") == 1 && loaded.generated == 1);
		Artifact duplicate = new Artifact();
		duplicate.id = "abcd";
		duplicate.rarity = ArtifactRarity.COMMON.id;
		duplicate.buff = "SPEED";
		duplicate.curse = "GREED";
		loaded.add(duplicate, random);
		check("одинаковые id разводятся автоматически", !duplicate.id.equals("abcd") && loaded.byId(duplicate.id) == duplicate);

		// ---------------- Настройки ----------------
		System.out.println("== Настройки аддона ==");
		Path configDir = Files.createTempDirectory("twitchcraft-artifacts-config");
		ArtifactConfig defaults = ArtifactConfig.load(configDir);
		check("файл настроек создаётся при первом запуске",
				Files.exists(ArtifactConfig.path(configDir)) && defaults.maxArtifacts == 5
						&& defaults.curse.intervalMinutes == 60 && Math.abs(defaults.curse.breakAt - 100) < 0.001);
		defaults.maxArtifacts = 999;
		defaults.curse.intervalMinutes = 0;
		defaults.curse.breakAt = 5;
		defaults.drops.bitsPerArtifact = 0;
		defaults.namedArtifactChance = 500;
		defaults.rarityChances = new double[]{1, 2};
		defaults.normalize();
		check("normalize приводит значения в рамки",
				defaults.maxArtifacts == 64 && defaults.curse.intervalMinutes == 1 && defaults.curse.breakAt == 10
						&& defaults.drops.bitsPerArtifact == 1 && defaults.namedArtifactChance == 100
						&& defaults.rarityChances.length == 6);
		defaults.save(configDir);
		ArtifactConfig reloaded = ArtifactConfig.load(configDir);
		check("настройки читаются обратно (лимит 64, шаг битсов 1)",
				reloaded.maxArtifacts == 64 && reloaded.drops.bitsPerArtifact == 1 && reloaded.enabled);
		Files.writeString(ArtifactConfig.path(configDir), "{ это не json ");
		ArtifactConfig broken = ArtifactConfig.load(configDir);
		check("повреждённый файл настроек не роняет аддон (берутся значения по умолчанию)",
				broken.maxArtifacts == 5 && broken.curse.intervalMinutes == 60);

		// ---------------- Тексты ----------------
		Map<String, String> values = new HashMap<>();
		values.put("name", "Клинок Бездны");
		values.put("user", "Зритель");
		check("плейсхолдеры подставляются",
				ArtifactFactory.text("{user} выбил {name}!", values).equals("Зритель выбил Клинок Бездны!"));
		values.put("name", "{user}");
		check("подстановка в один проход (значение не раскрывается повторно)",
				ArtifactFactory.text("{name}", values).equals("{user}"));
		check("неизвестный плейсхолдер остаётся как есть", ArtifactFactory.text("{нет}", values).equals("{нет}"));

		Map<String, String> placeholders = ArtifactFactory.placeholders(sample, 5);
		check("плейсхолдеры артефакта: имя, редкость, процент, лимит",
				placeholders.get("name").equals("Тестовый Артефакт") && placeholders.get("rarity").equals("Эпический")
						&& placeholders.get("percent").equals("42 %") && placeholders.get("max").equals("5"));

		System.out.println(failures == 0 ? "\nALL " + total + " ARTIFACT TESTS PASSED" : "\nFAILURES: " + failures + " of " + total);
		System.exit(failures == 0 ? 0 : 1);
	}
}
