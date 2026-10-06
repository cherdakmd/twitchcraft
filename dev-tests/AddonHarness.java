package dev.dedworkshop.twitchcraft.api;

import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Логические тесты хуков API аддонов (без Minecraft и без сети).
 *
 * Хуки: 1) переменные, 2) действия «триггер + элементы», 3) привязка награды по id,
 * 4) кастомные триггеры v0…v3. Проверяется регистрация, отбор по триггеру, изоляция
 * ошибок аддона и лимиты (4 слота, занятые имена).
 *
 * Живёт в пакете {@code dev.dedworkshop.twitchcraft.api}, чтобы вызывать тот же путь
 * регистрации, которым пользуются аддоны (AddonContext — тонкая обёртка над реестром).
 *
 * Запуск: см. run_tests.sh (нужны собранный проект и jar Minecraft).
 */
public class AddonHarness {
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

	static TwitchEvent cheer(int bits) {
		return TwitchEvent.test(TwitchEvent.Type.CHEER, "Bits", bits, "", "", "");
	}

	static TwitchEvent donate(int amount) {
		return TwitchEvent.donation(TwitchEvent.SOURCE_DONATION_ALERTS, "Donor", amount, "RUB", "привет", "d-1", true);
	}

	static TwitchEvent raid(int viewers) {
		return TwitchEvent.test(TwitchEvent.Type.RAID, "Raider", viewers, "", "", "");
	}

	static TwitchEvent reward(String rewardId, String title, String input) {
		return new TwitchEvent(TwitchEvent.Type.REWARD, "Viewer", "viewer", "1", 500, input, title, "",
				rewardId, "redemption-1", Set.of(), "", "", false);
	}

	static TwitchEvent command(String name, String args) {
		return TwitchEvent.test(TwitchEvent.Type.CHAT_COMMAND, "Chatter", 0, args, "", "").asCommand(name, args);
	}

	static TwitchEvent boss(String kind) {
		return TwitchEvent.game(kind, "Player", "Wither", "", 1, true);
	}

	static TwitchEvent follow() {
		return TwitchEvent.test(TwitchEvent.Type.FOLLOW, "Follower", 0, "", "", "");
	}

	public static void main(String[] args) {
		// ---------- Хук 1: переменные ----------
		section("Хук 1: переменные аддона");
		check("переменная зарегистрирована", AddonRegistry.registerVariable("addon-a", "boss_kills",
				event -> event != null && event.type() == TwitchEvent.Type.GAME ? "7" : "3"));
		check("значение считается на событие", "7".equals(AddonRegistry.variables(boss(TwitchEvent.GAME_BOSS)).get("boss_kills")));
		check("значение без события (HUD)", "3".equals(AddonRegistry.globalVariables().get("boss_kills")));
		check("плохое имя отклонено", !AddonRegistry.registerVariable("addon-a", "Плохое имя", event -> "x"));
		check("имя с большой буквы отклонено", !AddonRegistry.registerVariable("addon-a", "BossKills", event -> "x"));
		check("чужое имя не перезаписывается", !AddonRegistry.registerVariable("addon-b", "boss_kills", event -> "x"));
		check("своё имя можно обновить", AddonRegistry.registerVariable("addon-a", "boss_kills", event -> "9"));
		check("обновление подхватилось", "9".equals(AddonRegistry.globalVariables().get("boss_kills")));
		check("имя попало в список", AddonRegistry.variableNames().contains("boss_kills"));

		AddonRegistry.registerVariable("addon-a", "broken_value", event -> {
			throw new IllegalStateException("аддон сломался");
		});
		AddonRegistry.registerVariable("addon-a", "good_value", event -> "ок");
		Map<String, String> values = AddonRegistry.variables(cheer(10));
		check("падающая переменная пропущена", !values.containsKey("broken_value"));
		check("остальные переменные живы", "ок".equals(values.get("good_value")));

		// Значение переменной попадает в команды Minecraft, которые мод выполняет с правами
		// оператора, — значит, чистится так же, как тексты зрителей.
		AddonRegistry.registerVariable("addon-a", "dangerous", event -> "строка\nс \"кавычкой\" и \u00a7dкодом");
		check("значение переменной очищается (без переносов, кавычек и \u00a7)",
				"строка с 'кавычкой' и dкодом".equals(AddonRegistry.variables(cheer(1)).get("dangerous")));
		AddonRegistry.registerVariable("addon-a", "long_value", event -> "х".repeat(500));
		check("длинное значение обрезается до 200 символов",
				AddonRegistry.variables(cheer(1)).get("long_value").length() == 200);

		// applyVariables: аддон занимает только свободные имена — системные плейсхолдеры не перехватывает
		AddonRegistry.registerVariable("addon-a", "user", event -> "подмена");
		AddonRegistry.registerVariable("addon-a", "own_var", event -> "своё");
		Map<String, String> vars = new LinkedHashMap<>();
		vars.put("user", "Steve");
		vars.put("amount", "100");
		AddonRegistry.applyVariables(vars, cheer(100));
		check("системный плейсхолдер {user} не перехвачен", "Steve".equals(vars.get("user")));
		check("прочие системные переменные целы", "100".equals(vars.get("amount")));
		check("свободное имя занято переменной аддона", "своё".equals(vars.get("own_var")));
		boolean nullsSafe = true;
		try {
			AddonRegistry.applyVariables(null, cheer(1));
			AddonRegistry.applyVariables(vars, null);
		} catch (Exception e) {
			nullsSafe = false;
		}
		check("applyVariables не падает на null-карту и null-событие", nullsSafe);

		// ---------- Хук 2: действия ----------
		section("Хук 2: действия (триггер + элементы)");
		AddonElements cheerElements = AddonElements.builder()
				.message("§d{user}§r задонатил {amount} битсов!")
				.sound("minecraft:entity.player.levelup", 1.0f, 1.2f)
				.command("say {user} — {amount}")
				.build();
		check("действие зарегистрировано", AddonRegistry.registerAction("addon-a",
				AddonAction.of("cheer_reward", "Награда за битсы", AddonTrigger.cheer(100)).elements(cheerElements).build()));
		check("триггер битсов не срабатывает ниже порога", AddonRegistry.elementsFor(cheer(99)).isEmpty());
		check("триггер битсов срабатывает на пороге", AddonRegistry.elementsFor(cheer(100)).contains(cheerElements));
		check("действие срабатывает и выше порога", AddonRegistry.elementsFor(cheer(500)).size() == 1);
		check("чужое событие не подходит", AddonRegistry.elementsFor(follow()).isEmpty());
		check("дубликат id чужим аддоном отклонён", !AddonRegistry.registerAction("addon-b",
				AddonAction.of("cheer_reward", "Подмена", AddonTrigger.cheer(1)).build()));
		check("свой id можно обновить", AddonRegistry.registerAction("addon-a",
				AddonAction.of("cheer_reward", "Награда за битсы (v2)", AddonTrigger.cheer(150)).elements(cheerElements).build()));
		check("обновлённый триггер: 100 битсов уже мало", AddonRegistry.elementsFor(cheer(100)).isEmpty());
		check("обновлённый триггер: 150 битсов хватает", AddonRegistry.elementsFor(cheer(150)).size() == 1);
		check("владелец действия виден", "addon-a".equals(AddonRegistry.actionAddon("cheer_reward")));

		// Элементы превращаются в действие мода — значит, мод сам выполнит текст, звук и команды
		AddonElements full = AddonElements.builder()
				.message("привет {user}")
				.title("Заголовок", "Подзаголовок")
				.actionbar("над хотбаром")
				.toast("Тост", "текст")
				.sound("minecraft:ui.button.click", 0.5f, 0.8f)
				.commands("say раз", "say два")
				.randomOne()
				.chance(50, "не повезло")
				.repeat("{amount}", 100, 3, 4)
				.build();
		var configAction = full.toConfigAction();
		check("элементы: сообщение", "привет {user}".equals(configAction.message));
		check("элементы: заголовок и подзаголовок", "Заголовок".equals(configAction.title) && "Подзаголовок".equals(configAction.subtitle));
		check("элементы: actionbar и тост", "над хотбаром".equals(configAction.actionbar) && "Тост".equals(configAction.toast));
		check("элементы: звук с громкостью и высотой", "minecraft:ui.button.click".equals(configAction.sound)
				&& configAction.volume == 0.5f && configAction.pitch == 0.8f);
		check("элементы: команды", configAction.commands.equals(List.of("say раз", "say два")));
		check("элементы: случайная одна команда", configAction.randomOne);
		check("элементы: шанс и сообщение при неудаче", configAction.chance == 50 && "не повезло".equals(configAction.failMessage));
		check("элементы: повтор", "{amount}".equals(configAction.repeat) && configAction.repeatPer == 100
				&& configAction.maxRepeat == 3 && configAction.repeatDelay == 4);
		check("пустые элементы распознаются", AddonElements.none().isEmpty() && !full.isEmpty());
		check("chance клампится", AddonElements.builder().chance(500, "").toConfigAction().chance == 100);

		// ---------- Хук 3: награда по id ----------
		section("Хук 3: привязка награды по id");
		AtomicReference<String> gotUser = new AtomicReference<>();
		AtomicReference<String> gotInput = new AtomicReference<>();
		AtomicReference<String> gotTitle = new AtomicReference<>();
		check("награда привязана", AddonRegistry.bindReward("addon-a", "reward-id-1", "Артефакт", (event, input) -> {
			gotUser.set(event.user());
			gotInput.set(input);
			gotTitle.set(event.reward());
		}));
		var binding = AddonRegistry.reward("reward-id-1");
		check("привязка находится по id", binding != null && "addon-a".equals(binding.addonId()));
		check("название сохранено", binding != null && "Артефакт".equals(binding.rewardTitle()));
		check("чужой id не привязан", AddonRegistry.reward("reward-id-2") == null);
		check("пустой id не ищется", AddonRegistry.reward("") == null && AddonRegistry.reward(null) == null);
		if (binding != null) {
			binding.handler().onRedeem(reward("reward-id-1", "Артефакт", "хочу мифический"), "хочу мифический");
		}
		check("обработчик получил зрителя", "Viewer".equals(gotUser.get()));
		check("обработчик получил ввод зрителя", "хочу мифический".equals(gotInput.get()));
		check("обработчик получил название награды", "Артефакт".equals(gotTitle.get()));
		check("повторная привязка своим аддоном — обновление", AddonRegistry.bindReward("addon-a", "reward-id-1", "Артефакт 2", (event, input) -> { }));
		check("чужой аддон награду не отберёт", !AddonRegistry.bindReward("addon-b", "reward-id-1", "Чужое", (event, input) -> { }));
		check("обновлённое название видно", "Артефакт 2".equals(AddonRegistry.reward("reward-id-1").rewardTitle()));
		check("без id привязки нет", !AddonRegistry.bindReward("addon-a", "  ", "Пусто", (event, input) -> { }));
		check("привязка попала в список", AddonRegistry.rewards().size() == 1);

		// ---------- Хук 4: кастомные триггеры ----------
		section("Хук 4: кастомные триггеры v0…v3");
		AddonElements manual = AddonElements.builder().message("проверка v0").build();
		AddonElements raids = AddonElements.builder().message("рейд {amount}").build();
		AddonElements commands = AddonElements.builder().message("команда чата").build();
		AddonElements donations = AddonElements.builder().message("донат {amount}").build();
		check("слот v0 зарегистрирован", AddonRegistry.registerCustomTrigger("addon-a",
				new AddonCustomTrigger(0, "Проверка", "вручную", AddonTrigger.custom("manual", Map.of("slot", "v0"), event -> false), List.of(manual))));
		check("слот v1 зарегистрирован", AddonRegistry.registerCustomTrigger("addon-a",
				new AddonCustomTrigger(1, "Рейд", "от 25", AddonTrigger.raid(25), List.of(raids))));
		check("слот v2 зарегистрирован", AddonRegistry.registerCustomTrigger("addon-a",
				new AddonCustomTrigger(2, "Команда", "!артефакты", AddonTrigger.chatCommand("артефакты", "artifacts"), List.of(commands))));
		check("слот v3 зарегистрирован", AddonRegistry.registerCustomTrigger("addon-a",
				new AddonCustomTrigger(3, "Донат", "от 100", AddonTrigger.donation(100), List.of(donations))));
		check("пятый слот (индекс 4) отклонён", !AddonRegistry.registerCustomTrigger("addon-a",
				new AddonCustomTrigger(4, "Лишний", "", AddonTrigger.follow(), List.of(manual))));
		check("отрицательный слот отклонён", !AddonRegistry.registerCustomTrigger("addon-a",
				new AddonCustomTrigger(-1, "Минус", "", AddonTrigger.follow(), List.of(manual))));
		check("чужой аддон слот не займёт", !AddonRegistry.registerCustomTrigger("addon-b",
				new AddonCustomTrigger(0, "Подмена", "", AddonTrigger.follow(), List.of(manual))));
		check("занято 4 слота", AddonRegistry.customTriggers().size() == 4);
		check("слот v0 не срабатывает сам", AddonRegistry.elementsFor(follow()).isEmpty());
		check("слот v1 срабатывает на рейд 25", AddonRegistry.elementsFor(raid(25)).contains(raids));
		check("слот v1 молчит на рейд 24", AddonRegistry.elementsFor(raid(24)).isEmpty());
		check("слот v2 срабатывает на команду", AddonRegistry.elementsFor(command("артефакты", "")).contains(commands));
		check("слот v2 срабатывает на синоним", AddonRegistry.elementsFor(command("artifacts", "")).contains(commands));
		check("слот v2 молчит на другую команду", AddonRegistry.elementsFor(command("смерти", "")).isEmpty());
		check("слот v3 срабатывает на донат 100", AddonRegistry.elementsFor(donate(100)).contains(donations));
		check("слот v3 молчит на донат 99", AddonRegistry.elementsFor(donate(99)).isEmpty());
		check("имена и описания слотов на месте", AddonRegistry.customTrigger(1).name().equals("Рейд")
				&& AddonRegistry.customTrigger(1).slot().equals("v1"));
		check("владелец слота виден", "addon-a".equals(AddonRegistry.customTriggerAddon(1))
				&& AddonRegistry.customTriggerAddon(3).equals("addon-a"));
		check("пустой слот возвращает null", AddonRegistry.customTriggerAddon(4) == null);

		// Кастомный триггер с падающим условием не должен ломать остальные
		AddonRegistry.registerCustomTrigger("addon-a", new AddonCustomTrigger(3, "Сломанный", "",
				AddonTrigger.custom("broken", Map.of(), event -> {
					throw new IllegalArgumentException("условие упало");
				}), List.of(manual)));
		check("падающее условие не мешает другим слотам", AddonRegistry.elementsFor(raid(30)).contains(raids));

		// ---------- Триггеры по видам событий ----------
		section("Триггеры: подписки, награды, игра");
		AddonElements followElements = AddonElements.builder().message("фолловер!").build();
		AddonElements subElements = AddonElements.builder().message("подписка!").build();
		AddonElements giftElements = AddonElements.builder().message("подарок!").build();
		AddonElements idElements = AddonElements.builder().message("награда по id!").build();
		AddonElements titleElements = AddonElements.builder().message("награда по названию!").build();
		AddonElements bossElements = AddonElements.builder().message("босс!").build();
		AddonRegistry.registerAction("addon-a",
				AddonAction.of("follow_hi", "Привет фолловеру", AddonTrigger.follow()).elements(followElements).build());
		AddonRegistry.registerAction("addon-a",
				AddonAction.of("sub_hi", "Подписка", AddonTrigger.subscribe()).elements(subElements).build());
		AddonRegistry.registerAction("addon-a",
				AddonAction.of("gift_hi", "Подарки от 5", AddonTrigger.giftSub(5)).elements(giftElements).build());
		AddonRegistry.registerAction("addon-a",
				AddonAction.of("id_reward", "Награда по id", AddonTrigger.redemption("reward-id-1")).elements(idElements).build());
		AddonRegistry.registerAction("addon-a",
				AddonAction.of("title_reward", "Награда по названию", AddonTrigger.redemptionTitle("Артефакт"))
						.elements(titleElements).build());
		AddonRegistry.registerAction("addon-a",
				AddonAction.of("boss_hi", "Босс", AddonTrigger.game(TwitchEvent.GAME_BOSS)).elements(bossElements).build());

		check("follow срабатывает", AddonRegistry.elementsFor(follow()).contains(followElements));
		check("подписка ловится триггером subscribe",
				AddonRegistry.elementsFor(TwitchEvent.test(TwitchEvent.Type.SUBSCRIBE, "Sub", 1, "", "", "")).contains(subElements));
		check("подарочная подписка тоже считается подпиской",
				AddonRegistry.elementsFor(TwitchEvent.test(TwitchEvent.Type.GIFT_SUB, "Gifter", 1, "", "", "")).contains(subElements));
		check("триггер подарков срабатывает от 5",
				AddonRegistry.elementsFor(TwitchEvent.test(TwitchEvent.Type.GIFT_SUB, "Gifter", 5, "", "", "")).contains(giftElements));
		check("триггер подарков молчит на 4",
				!AddonRegistry.elementsFor(TwitchEvent.test(TwitchEvent.Type.GIFT_SUB, "Gifter", 4, "", "", "")).contains(giftElements));
		check("награда по id срабатывает", AddonRegistry.elementsFor(reward("reward-id-1", "Как угодно", "")).contains(idElements));
		check("награда по id молчит на чужой id", !AddonRegistry.elementsFor(reward("другой-id", "Артефакт", "")).contains(idElements));
		check("награда по названию срабатывает", AddonRegistry.elementsFor(reward("любой-id", "Артефакт", "")).contains(titleElements));
		check("награда по названию молчит на другое", !AddonRegistry.elementsFor(reward("любой-id", "Другое", "")).contains(titleElements));
		check("параметры триггера redemption", "reward-id-1".equals(AddonTrigger.redemption("reward-id-1").params().get("id")));
		check("триггер игры ловит босса", AddonRegistry.elementsFor(boss(TwitchEvent.GAME_BOSS)).contains(bossElements));
		check("триггер игры не ловит смерть", !AddonRegistry.elementsFor(boss(TwitchEvent.GAME_DEATH)).contains(bossElements));
		check("every() ловит всё", AddonTrigger.every().matches(follow()) && AddonTrigger.every().matches(donate(1)));
		check("сводка показывает счётчики", AddonRegistry.summary().contains("переменных:") && AddonRegistry.summary().contains("/4"));

		// ---------- Порядок и изоляция ----------
		section("Порядок действий и изоляция");
		AddonRegistry.clear();
		check("clear() очистил реестр", AddonRegistry.actions().isEmpty() && AddonRegistry.rewards().isEmpty()
				&& AddonRegistry.customTriggers().isEmpty() && AddonRegistry.variableNames().isEmpty());
		AddonElements first = AddonElements.builder().message("первое").build();
		AddonElements second = AddonElements.builder().message("второе").build();
		AddonRegistry.registerAction("addon-a",
				AddonAction.of("first", "Первое", AddonTrigger.cheer(1)).elements(first).build());
		AddonRegistry.registerAction("addon-b",
				AddonAction.of("second", "Второе", AddonTrigger.every()).elements(second).build());
		check("действия разных аддонов срабатывают вместе", AddonRegistry.elementsFor(cheer(5)).size() == 2);
		check("элементы идут в порядке регистрации", AddonRegistry.elementsFor(cheer(5)).get(0) == first);
		check("падающий предикат действия не мешает другим", AddonRegistry.registerAction("addon-c",
				AddonAction.of("broken", "Сломанное", AddonTrigger.custom("broken", Map.of(), event -> {
					throw new IllegalStateException("предикат упал");
				})).elements(second).build()) && AddonRegistry.elementsFor(cheer(5)).size() == 2);

		System.out.println();
		if (failures == 0) {
			System.out.println("ALL " + total + " ADDON API TESTS PASSED");
		} else {
			System.out.println("FAILED: " + failures + " из " + total);
			System.exit(1);
		}
	}
}
