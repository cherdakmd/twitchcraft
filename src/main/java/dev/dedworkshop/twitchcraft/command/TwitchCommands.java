package dev.dedworkshop.twitchcraft.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.action.FundraiserTracker;
import dev.dedworkshop.twitchcraft.action.GoalTracker;
import dev.dedworkshop.twitchcraft.action.SessionStats;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import java.nio.file.Path;
import dev.dedworkshop.twitchcraft.util.SafeFiles;
import dev.dedworkshop.twitchcraft.config.DonationPresets;
import dev.dedworkshop.twitchcraft.config.TokenStore;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.ui.config.ConfigScreens;
import dev.dedworkshop.twitchcraft.twitch.EventSubClient;
import dev.dedworkshop.twitchcraft.twitch.RewardManager;
import dev.dedworkshop.twitchcraft.twitch.TwitchAuth;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import dev.dedworkshop.twitchcraft.util.Chat;
import dev.dedworkshop.twitchcraft.vk.VkLive;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * Клиентские команды мода. Все начинаются с /twitch.
 */
public final class TwitchCommands {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

	private TwitchCommands() {
	}

	private static final SuggestionProvider<FabricClientCommandSource> MODULE_IDS = (ctx, builder) -> {
		for (Module module : Module.values()) {
			if (module.id.toLowerCase().startsWith(builder.getRemainingLowerCase())) {
				builder.suggest(module.id);
			}
		}
		return builder.buildFuture();
	};

	private static final SuggestionProvider<FabricClientCommandSource> TRIGGER_SLOTS = (ctx, builder) -> {
		for (int i = 0; i < dev.dedworkshop.twitchcraft.api.AddonRegistry.MAX_CUSTOM_TRIGGERS; i++) {
			builder.suggest("v" + i);
		}
		return builder.buildFuture();
	};

	private static SuggestionProvider<FabricClientCommandSource> goalNames(TwitchCraftClient mod) {
		return (ctx, builder) -> {
			for (ModConfig.Goal goal : mod.config().goals) {
				if (goal != null && goal.name != null && !goal.name.isBlank()) {
					String name = goal.name.contains(" ") ? "\"" + goal.name + "\"" : goal.name;
					if (name.toLowerCase().startsWith(builder.getRemainingLowerCase())) {
						builder.suggest(name);
					}
				}
			}
			return builder.buildFuture();
		};
	}

	private static SuggestionProvider<FabricClientCommandSource> fundNames(TwitchCraftClient mod) {
		return (ctx, builder) -> {
			for (ModConfig.Fundraiser fund : mod.config().fundraisers) {
				if (fund != null && fund.name != null && !fund.name.isBlank()) {
					String name = fund.name.contains(" ") ? "\"" + fund.name + "\"" : fund.name;
					if (name.toLowerCase().startsWith(builder.getRemainingLowerCase())) {
						builder.suggest(name);
					}
				}
			}
			return builder.buildFuture();
		};
	}

	public static void register(TwitchCraftClient mod) {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				literal("twitch")
						.executes(ctx -> help(ctx.getSource()))

						// Экран настроек (тот же, что в Mod Menu)
						.then(literal("config").executes(ctx -> run(() -> ConfigScreens.openDeferred(mod))))

						// Модули
						.then(literal("modules").executes(ctx -> modules(mod)))
						.then(literal("module")
								.then(argument("id", StringArgumentType.word()).suggests(MODULE_IDS)
										.executes(ctx -> module(mod, StringArgumentType.getString(ctx, "id"), null))
										.then(literal("on").executes(ctx -> module(mod, StringArgumentType.getString(ctx, "id"), true)))
										.then(literal("off").executes(ctx -> module(mod, StringArgumentType.getString(ctx, "id"), false)))
										.then(literal("toggle").executes(ctx -> module(mod, StringArgumentType.getString(ctx, "id"), null)))))
						.then(literal("addons")
								.executes(ctx -> addons(""))
								.then(literal("actions").executes(ctx -> addons("actions")))
								.then(literal("rewards").executes(ctx -> addons("rewards")))
								.then(literal("triggers").executes(ctx -> addons("triggers")))
								.then(literal("fire")
										.then(argument("slot", StringArgumentType.word()).suggests(TRIGGER_SLOTS)
												.executes(ctx -> addonsFire(mod, StringArgumentType.getString(ctx, "slot"))))))

						// Цели
						.then(literal("goals")
								.executes(ctx -> goals(mod))
								.then(literal("reset")
										.executes(ctx -> goalsReset(mod, null))
										.then(argument("name", StringArgumentType.string()).suggests(goalNames(mod))
												.executes(ctx -> goalsReset(mod, StringArgumentType.getString(ctx, "name")))))
								.then(literal("add")
										.then(argument("name", StringArgumentType.string()).suggests(goalNames(mod))
												.then(argument("amount", IntegerArgumentType.integer(-100000, 100000))
														.executes(ctx -> goalsAdd(mod, StringArgumentType.getString(ctx, "name"),
																IntegerArgumentType.getInteger(ctx, "amount")))))))

						// Сборы средств (боссбар)
						.then(literal("fund")
								.executes(ctx -> funds(mod))
								.then(literal("create")
										.then(argument("name", StringArgumentType.string())
												.then(argument("target", IntegerArgumentType.integer(1, 1000000000))
														.executes(ctx -> fundCreate(mod, StringArgumentType.getString(ctx, "name"),
																IntegerArgumentType.getInteger(ctx, "target"), null))
														.then(argument("title", StringArgumentType.greedyString())
																.executes(ctx -> fundCreate(mod, StringArgumentType.getString(ctx, "name"),
																		IntegerArgumentType.getInteger(ctx, "target"), StringArgumentType.getString(ctx, "title")))))))
								.then(literal("remove")
										.then(argument("name", StringArgumentType.string()).suggests(fundNames(mod))
												.executes(ctx -> fundRemove(mod, StringArgumentType.getString(ctx, "name")))))
								.then(literal("show")
										.then(argument("name", StringArgumentType.string()).suggests(fundNames(mod))
												.executes(ctx -> fundVisible(mod, StringArgumentType.getString(ctx, "name"), true))))
								.then(literal("hide")
										.then(argument("name", StringArgumentType.string()).suggests(fundNames(mod))
												.executes(ctx -> fundVisible(mod, StringArgumentType.getString(ctx, "name"), false))))
								.then(literal("add")
										.then(argument("name", StringArgumentType.string()).suggests(fundNames(mod))
												.then(argument("amount", DoubleArgumentType.doubleArg(-10000000, 10000000))
														.executes(ctx -> fundAdd(mod, StringArgumentType.getString(ctx, "name"),
																DoubleArgumentType.getDouble(ctx, "amount"), ""))
														.then(argument("from", StringArgumentType.greedyString())
																.executes(ctx -> fundAdd(mod, StringArgumentType.getString(ctx, "name"),
																		DoubleArgumentType.getDouble(ctx, "amount"), StringArgumentType.getString(ctx, "from")))))))
								.then(literal("set")
										.then(argument("name", StringArgumentType.string()).suggests(fundNames(mod))
												.then(argument("amount", DoubleArgumentType.doubleArg(0, 10000000000.0))
														.executes(ctx -> fundSet(mod, StringArgumentType.getString(ctx, "name"),
																DoubleArgumentType.getDouble(ctx, "amount"))))))
								.then(literal("target")
										.then(argument("name", StringArgumentType.string()).suggests(fundNames(mod))
												.then(argument("target", IntegerArgumentType.integer(1, 1000000000))
														.executes(ctx -> fundTarget(mod, StringArgumentType.getString(ctx, "name"),
																IntegerArgumentType.getInteger(ctx, "target"))))))
								.then(literal("title")
										.then(argument("name", StringArgumentType.string()).suggests(fundNames(mod))
												.then(argument("title", StringArgumentType.greedyString())
														.executes(ctx -> fundTitle(mod, StringArgumentType.getString(ctx, "name"),
																StringArgumentType.getString(ctx, "title"))))))
								.then(literal("reset")
										.executes(ctx -> fundReset(mod, null))
										.then(argument("name", StringArgumentType.string()).suggests(fundNames(mod))
												.executes(ctx -> fundReset(mod, StringArgumentType.getString(ctx, "name"))))))

						.then(literal("donations")
								.executes(ctx -> donations(mod))
								.then(literal("prices").executes(ctx -> printDonationPrices(mod)))
								.then(literal("preset")
										.executes(ctx -> donationPreset(mod, false))
										.then(literal("confirm").executes(ctx -> donationPreset(mod, true))))
								.then(literal("connect").executes(ctx -> run(() -> mod.donations().connectAll())))
								.then(literal("disconnect").executes(ctx -> run(() -> mod.donations().disconnectAll())))
								.then(literal("da")
										.then(literal("client")
												.then(argument("clientId", StringArgumentType.word())
														.executes(ctx -> daClient(mod, StringArgumentType.getString(ctx, "clientId")))))
										.then(literal("login").executes(ctx -> daLogin(mod)))
										.then(literal("cancel").executes(ctx -> run(() -> mod.donations().donationAlerts().cancelLogin())))
										.then(literal("logout").executes(ctx -> run(() -> mod.donations().donationAlerts().logout())))
										.then(literal("connect").executes(ctx -> run(() -> mod.donations().donationAlerts().connect(true))))
										.then(literal("disconnect").executes(ctx -> run(() -> mod.donations().donationAlerts().disconnect()))))
								.then(literal("dp")
										.then(literal("key")
												.then(argument("key", StringArgumentType.word())
														.executes(ctx -> run(() -> mod.donations().donatePay().setKey(StringArgumentType.getString(ctx, "key"))))))
										.then(literal("logout").executes(ctx -> run(() -> mod.donations().donatePay().logout())))
										.then(literal("connect").executes(ctx -> run(() -> mod.donations().donatePay().connect(true))))
										.then(literal("disconnect").executes(ctx -> run(() -> mod.donations().donatePay().disconnect())))))

						.then(literal("vk")
								.executes(ctx -> vk(mod))
								.then(literal("channel")
										.then(argument("url", StringArgumentType.greedyString())
												.executes(ctx -> vkChannel(mod, StringArgumentType.getString(ctx, "url")))))
								.then(literal("app")
										.then(argument("clientId", StringArgumentType.word())
												.then(argument("secret", StringArgumentType.word())
														.executes(ctx -> vkApp(mod, StringArgumentType.getString(ctx, "clientId"), StringArgumentType.getString(ctx, "secret"))))))
								.then(literal("login").executes(ctx -> vkLogin(mod)))
								.then(literal("code")
										.then(argument("code", StringArgumentType.greedyString())
												.executes(ctx -> run(() -> mod.vk().finishLoginWithCode(StringArgumentType.getString(ctx, "code"))))))
								.then(literal("cancel").executes(ctx -> run(() -> mod.vk().cancelLogin())))
								.then(literal("logout").executes(ctx -> run(() -> mod.vk().logout())))
								.then(literal("connect").executes(ctx -> run(() -> mod.vk().connect(true))))
								.then(literal("disconnect").executes(ctx -> {
									mod.vk().disconnect();
									Chat.info("VK Video Live: отключено (автоподключение выключено до /twitch vk connect).");
									return 1;
								}))
								.then(literal("say")
										.then(argument("text", StringArgumentType.greedyString())
												.executes(ctx -> run(() -> mod.vk().send(StringArgumentType.getString(ctx, "text"), true)))))
								.then(literal("rewards")
										.then(literal("sync").executes(ctx -> run(() -> mod.vk().syncRewards()))))
								.then(literal("debug")
										.then(literal("on").executes(ctx -> vkDebug(mod, true)))
										.then(literal("off").executes(ctx -> vkDebug(mod, false))))
								.then(literal("test")
										.then(literal("chat")
												.then(argument("text", StringArgumentType.greedyString())
														.executes(ctx -> test(mod, VkLive.testEvent(TwitchEvent.Type.CHAT, "VkViewer", 0,
																StringArgumentType.getString(ctx, "text"), "")))))
										.then(literal("follow").executes(ctx -> test(mod, VkLive.testEvent(TwitchEvent.Type.FOLLOW, "VkViewer", 0, "", ""))))
										.then(literal("reward")
												.then(argument("title", StringArgumentType.greedyString())
														.executes(ctx -> test(mod, VkLive.testEvent(TwitchEvent.Type.REWARD, "VkViewer", 250,
																"Привет из VK", StringArgumentType.getString(ctx, "title"))))))))

						.then(literal("setup")
								.then(argument("clientId", StringArgumentType.word())
										.executes(ctx -> {
											mod.setClientId(StringArgumentType.getString(ctx, "clientId"));
											return 1;
										})))

						// 1.7.0: клипы и метки, события игры, таймеры чата
						.then(literal("clip")
								.executes(ctx -> clip(mod, ""))
								.then(argument("why", StringArgumentType.greedyString())
										.executes(ctx -> clip(mod, StringArgumentType.getString(ctx, "why")))))
						.then(literal("marker")
								.executes(ctx -> marker(mod, ""))
								.then(argument("text", StringArgumentType.greedyString())
										.executes(ctx -> marker(mod, StringArgumentType.getString(ctx, "text")))))
						.then(literal("game")
								.executes(ctx -> game(mod))
								.then(literal("reset")
										.executes(ctx -> gameReset(mod, false))
										.then(literal("all").executes(ctx -> gameReset(mod, true))))
								.then(literal("test")
										.then(argument("kind", StringArgumentType.word())
												.suggests(GAME_KINDS)
												.executes(ctx -> test(mod, mod.game().testEvent(gameKind(StringArgumentType.getString(ctx, "kind"))))))))
						.then(literal("timers")
								.executes(ctx -> timers(mod))
								.then(literal("post")
										.then(argument("name", StringArgumentType.greedyString())
												.suggests(TIMER_NAMES)
												.executes(ctx -> timerPost(mod, StringArgumentType.getString(ctx, "name")))))
								.then(literal("on")
										.then(argument("name", StringArgumentType.greedyString())
												.suggests(TIMER_NAMES)
												.executes(ctx -> timerEnable(mod, StringArgumentType.getString(ctx, "name"), true))))
								.then(literal("off")
										.then(argument("name", StringArgumentType.greedyString())
												.suggests(TIMER_NAMES)
												.executes(ctx -> timerEnable(mod, StringArgumentType.getString(ctx, "name"), false)))))

						.then(literal("login").executes(ctx -> run(mod::login)))
						.then(literal("logout").executes(ctx -> run(mod::logout)))
						.then(literal("connect").executes(ctx -> run(() -> mod.connect(true))))
						.then(literal("disconnect").executes(ctx -> run(mod::disconnect)))
						.then(literal("status").executes(ctx -> status(mod)))
						.then(literal("reload").executes(ctx -> run(mod::reloadConfig)))

						.then(literal("rewards")
								.executes(ctx -> rewards(mod))
								.then(literal("sync").executes(ctx -> run(() -> mod.rewards().sync()))))

						// Пауза / очередь / повтор
						.then(literal("pause").executes(ctx -> run(() -> mod.setPaused(true))))
						.then(literal("resume").executes(ctx -> run(() -> mod.setPaused(false))))
						.then(literal("queue")
								.executes(ctx -> queue(mod))
								.then(literal("clear").executes(ctx -> {
									int n = mod.events().clearQueue();
									Chat.success("Очередь очищена: " + n);
									return 1;
								})))
						.then(literal("replay").executes(ctx -> run(mod::replayLast)))

						// Статистика и история
						.then(literal("stats")
								.executes(ctx -> stats(mod))
								.then(literal("reset").executes(ctx -> {
									mod.events().stats().reset();
									Chat.success("Статистика сессии сброшена.");
									return 1;
								})))
						.then(literal("history")
								.executes(ctx -> history(mod, 10))
								.then(argument("count", IntegerArgumentType.integer(1, 100))
										.executes(ctx -> history(mod, IntegerArgumentType.getInteger(ctx, "count")))))

						// Оверлей и чат
						.then(literal("overlay").executes(ctx -> run(mod::toggleOverlay)))
						.then(literal("chat")
								.then(literal("on").executes(ctx -> chatToggle(mod, true)))
								.then(literal("off").executes(ctx -> chatToggle(mod, false))))
						.then(literal("say")
								.then(argument("text", StringArgumentType.greedyString())
										.executes(ctx -> {
											mod.chatSender().send(StringArgumentType.getString(ctx, "text"), true);
											return 1;
										})))

						// Тестовые события — проверяй действия без настоящих зрителей
						.then(literal("test")
								.then(literal("follow").executes(ctx -> test(mod,
										TwitchEvent.test(TwitchEvent.Type.FOLLOW, "TestViewer", 0, "", "", ""))))
								.then(literal("sub").executes(ctx -> test(mod,
										TwitchEvent.test(TwitchEvent.Type.SUBSCRIBE, "TestViewer", 1, "", "", "1"))))
								.then(literal("resub")
										.executes(ctx -> test(mod,
												TwitchEvent.test(TwitchEvent.Type.RESUB, "TestViewer", 6, "Классный стрим!", "", "1")))
										.then(argument("months", IntegerArgumentType.integer(1))
												.executes(ctx -> test(mod, TwitchEvent.test(TwitchEvent.Type.RESUB, "TestViewer",
														IntegerArgumentType.getInteger(ctx, "months"), "Классный стрим!", "", "1")))))
								.then(literal("gift")
										.executes(ctx -> test(mod,
												TwitchEvent.test(TwitchEvent.Type.GIFT_SUB, "TestViewer", 5, "", "", "1")))
										.then(argument("count", IntegerArgumentType.integer(1))
												.executes(ctx -> test(mod, TwitchEvent.test(TwitchEvent.Type.GIFT_SUB, "TestViewer",
														IntegerArgumentType.getInteger(ctx, "count"), "", "", "1")))))
								.then(literal("cheer")
										.executes(ctx -> test(mod,
												TwitchEvent.test(TwitchEvent.Type.CHEER, "TestViewer", 100, "Cheer100 Держи!", "", "")))
										.then(argument("bits", IntegerArgumentType.integer(1))
												.executes(ctx -> test(mod, TwitchEvent.test(TwitchEvent.Type.CHEER, "TestViewer",
														IntegerArgumentType.getInteger(ctx, "bits"), "Cheer! Держи!", "", "")))))
								.then(literal("raid")
										.executes(ctx -> test(mod,
												TwitchEvent.test(TwitchEvent.Type.RAID, "TestStreamer", 42, "", "", "")))
										.then(argument("viewers", IntegerArgumentType.integer(1))
												.executes(ctx -> test(mod, TwitchEvent.test(TwitchEvent.Type.RAID, "TestStreamer",
														IntegerArgumentType.getInteger(ctx, "viewers"), "", "", "")))))
								.then(literal("reward")
										.then(argument("title", StringArgumentType.greedyString())
												.executes(ctx -> test(mod, TwitchEvent.test(TwitchEvent.Type.REWARD, "TestViewer", 500,
														"Привет из теста", StringArgumentType.getString(ctx, "title"), "")))))
								.then(literal("chat")
										.then(argument("text", StringArgumentType.greedyString())
												.executes(ctx -> test(mod, new TwitchEvent(TwitchEvent.Type.CHAT, "TestViewer", "testviewer", "0",
														0, StringArgumentType.getString(ctx, "text"), "", "", "", "",
														Set.of("broadcaster"), "#9146FF", "", true)))))
								.then(literal("goal")
										.then(argument("name", StringArgumentType.greedyString()).suggests(goalNames(mod))
												.executes(ctx -> testGoal(mod, StringArgumentType.getString(ctx, "name")))))
								.then(literal("fund")
										.then(argument("name", StringArgumentType.greedyString()).suggests(fundNames(mod))
												.executes(ctx -> testFund(mod, StringArgumentType.getString(ctx, "name")))))
								.then(literal("donation")
										.executes(ctx -> run(() -> mod.donations().test("test", 100, "Тестовый донат!")))
										.then(argument("amount", IntegerArgumentType.integer(0))
												.executes(ctx -> run(() -> mod.donations().test("test", IntegerArgumentType.getInteger(ctx, "amount"), "Тестовый донат!")))
												.then(argument("message", StringArgumentType.greedyString())
														.executes(ctx -> run(() -> mod.donations().test("test", IntegerArgumentType.getInteger(ctx, "amount"),
																StringArgumentType.getString(ctx, "message"))))))))
		));
	}

	private static int testGoal(TwitchCraftClient mod, String name) {
		String clean = name.replace("\"", "").trim();
		ModConfig.Goal goal = mod.config().findGoal(clean);
		if (goal == null) {
			Chat.error("Цель «" + clean + "» не найдена. Список: /twitch goals");
			return 0;
		}
		GoalTracker.Progress progress = mod.goalTracker().peek(goal.name);
		return test(mod, TwitchEvent.goal(goal.name, Math.max(1, goal.target), progress.completed + 1, "TestViewer", "testviewer", true));
	}

	private static int testFund(TwitchCraftClient mod, String name) {
		String clean = name.replace("\"", "").trim();
		ModConfig.Fundraiser fund = mod.config().findFundraiser(clean);
		if (fund == null) {
			Chat.error("Сбор «" + clean + "» не найден. Список: /twitch fund");
			return 0;
		}
		FundraiserTracker.Progress progress = mod.fundraisers().peek(fund.name);
		return test(mod, TwitchEvent.fund(fund.name, Math.max(1, fund.target), progress.completed + 1,
				mod.config().donations.currency, "TestViewer", "testviewer", true));
	}

	// ---------- Сборы средств ----------

	private static ModConfig.Fundraiser findFund(TwitchCraftClient mod, String name) {
		ModConfig.Fundraiser fund = mod.config().findFundraiser(name == null ? "" : name.replace("\"", "").trim());
		if (fund == null) {
			Chat.error("Сбор «" + name + "» не найден. Список: /twitch fund, создать: /twitch fund create <имя> <цель>");
		}
		return fund;
	}

	private static int funds(TwitchCraftClient mod) {
		if (!mod.isModuleEnabled(Module.FUNDRAISERS)) {
			Chat.warn("Модуль «Сборы средств» выключен — полоса не показывается и вклады не считаются. Включить: §e/twitch module fundraisers on");
		}
		List<ModConfig.Fundraiser> all = mod.config().fundraisers;
		if (all.isEmpty()) {
			Chat.info("§7Сборов нет. Создать: §e/twitch fund create <имя> <цель> [заголовок]§7, например §e/twitch fund create pc 50000 Сбор на новый ПК");
			return 1;
		}
		Chat.info("§5§lСборы средств §7(клик — протестировать эффект закрытия)");
		for (ModConfig.Fundraiser fund : all) {
			if (fund == null) {
				continue;
			}
			FundraiserTracker.Line line = mod.fundraisers().line(fund);
			FundraiserTracker.Progress p = line.progress();
			String state = (line.done() ? "§a✔ " : "§a") + FundraiserTracker.formatAmount(line.current()) + "§7/§f"
					+ FundraiserTracker.formatAmount(line.target()) + " " + line.symbol() + " §7(" + line.percent() + "%)";
			String flags = (fund.enabled ? "" : " §8(выкл)") + (fund.visible ? "" : " §8(скрыт)") + (p.completed > 0 ? " §7· закрыт ×" + p.completed : "");
			Chat.send(Component.literal("  §6" + fund.name + " §7— " + state + flags)
					.withStyle(style -> style
							.withClickEvent(new ClickEvent.SuggestCommand("/twitch test fund " + fund.name))
							.withHoverEvent(new HoverEvent.ShowText(Component.literal(
									FundraiserTracker.stripColors(fund.displayTitle()) + "\n§7вкладов: " + p.contributions
											+ ", последний: " + (p.lastUser == null || p.lastUser.isBlank() ? "—" : p.lastUser)
											+ "\n§7/twitch fund add \"" + fund.name + "\" <сумма> [от кого] — ручной вклад")))));
		}
		Chat.info("§7Команды: §efund add <имя> <сумма>§7, §efund set§7, §efund target§7, §efund title§7, §efund show|hide§7, §efund reset§7, §efund remove§7, §efund create");
		return 1;
	}

	private static int fundCreate(TwitchCraftClient mod, String name, int target, String title) {
		String clean = name.replace("\"", "").trim();
		if (clean.isEmpty()) {
			Chat.error("Укажи имя сбора.");
			return 0;
		}
		if (mod.config().findFundraiser(clean) != null) {
			Chat.error("Сбор «" + clean + "» уже есть. Изменить цель: /twitch fund target \"" + clean + "\" <сумма>");
			return 0;
		}
		ModConfig.Fundraiser template = mod.config().fundraisers.isEmpty() ? null : mod.config().fundraisers.get(0);
		ModConfig.Fundraiser fund = new ModConfig.Fundraiser(clean, title == null ? "" : title.trim(), target,
				template == null || template.action == null ? defaultFundAction() : template.action.copy());
		if (template != null) {
			fund.color = template.color;
			fund.style = template.style;
			fund.format = template.format;
		}
		ModConfig.normalizeFundraiser(fund);
		mod.config().fundraisers.add(fund);
		mod.fundraisers().reset(fund.name);
		mod.configEdited();
		Chat.success("Сбор «" + fund.name + "» создан: цель " + FundraiserTracker.formatAmount(target) + " "
				+ TwitchEvent.currencySymbol(mod.config().donations.currency) + ". Полоса уже на экране; эффект закрытия и цвет — в /twitch config → Сборы.");
		return 1;
	}

	private static ModConfig.Action defaultFundAction() {
		ModConfig defaults = ModConfig.createDefault();
		return defaults.fundraisers.isEmpty() ? new ModConfig.Action() : defaults.fundraisers.get(0).action;
	}

	private static int fundRemove(TwitchCraftClient mod, String name) {
		ModConfig.Fundraiser fund = findFund(mod, name);
		if (fund == null) {
			return 0;
		}
		mod.config().fundraisers.remove(fund);
		mod.fundraisers().reset(fund.name);
		mod.configEdited();
		Chat.success("Сбор «" + fund.name + "» удалён.");
		return 1;
	}

	private static int fundVisible(TwitchCraftClient mod, String name, boolean visible) {
		ModConfig.Fundraiser fund = findFund(mod, name);
		if (fund == null) {
			return 0;
		}
		fund.visible = visible;
		if (visible && !fund.enabled) {
			fund.enabled = true;
		}
		mod.configEdited();
		Chat.success("Сбор «" + fund.name + "»: полоса " + (visible ? "показана" : "скрыта (вклады продолжают считаться)"));
		return 1;
	}

	private static int fundAdd(TwitchCraftClient mod, String name, double amount, String from) {
		ModConfig.Fundraiser fund = findFund(mod, name);
		if (fund == null) {
			return 0;
		}
		List<TwitchEvent> closed = mod.fundraisers().add(fund, amount, from);
		FundraiserTracker.Line line = mod.fundraisers().line(fund);
		Chat.success("Сбор «" + fund.name + "»: " + (amount >= 0 ? "+" : "") + FundraiserTracker.formatAmount(amount)
				+ (from == null || from.isBlank() ? "" : " от " + from) + " → " + line.plainText());
		for (TwitchEvent event : closed) {
			mod.onTwitchEvent(event);
		}
		return 1;
	}

	private static int fundSet(TwitchCraftClient mod, String name, double amount) {
		ModConfig.Fundraiser fund = findFund(mod, name);
		if (fund == null) {
			return 0;
		}
		mod.fundraisers().set(fund, amount);
		Chat.success("Сбор «" + fund.name + "»: " + mod.fundraisers().line(fund).plainText());
		return 1;
	}

	private static int fundTarget(TwitchCraftClient mod, String name, int target) {
		ModConfig.Fundraiser fund = findFund(mod, name);
		if (fund == null) {
			return 0;
		}
		fund.target = target;
		mod.fundraisers().set(fund, mod.fundraisers().peek(fund.name).current); // пересчитать флаг закрытия
		mod.configEdited();
		Chat.success("Сбор «" + fund.name + "»: " + mod.fundraisers().line(fund).plainText());
		return 1;
	}

	private static int fundTitle(TwitchCraftClient mod, String name, String title) {
		ModConfig.Fundraiser fund = findFund(mod, name);
		if (fund == null) {
			return 0;
		}
		fund.title = title == null ? "" : title.trim();
		mod.configEdited();
		Chat.success("Сбор «" + fund.name + "»: заголовок — " + fund.displayTitle());
		return 1;
	}

	private static int fundReset(TwitchCraftClient mod, String name) {
		if (name == null) {
			mod.fundraisers().reset(null);
			Chat.success("Прогресс всех сборов сброшен.");
			return 1;
		}
		ModConfig.Fundraiser fund = findFund(mod, name);
		if (fund == null) {
			return 0;
		}
		mod.fundraisers().reset(fund.name);
		Chat.success("Прогресс сбора «" + fund.name + "» сброшен: 0/" + FundraiserTracker.formatAmount(fund.target));
		return 1;
	}

	// ---------- Модули ----------

	/** Список подключённых аддонов (отдельные моды вроде «Артефактов»). */
	private static int addons(String section) {
		java.util.List<String> ids = dev.dedworkshop.twitchcraft.api.AddonManager.loadedIds();
		if (ids.isEmpty()) {
			Chat.info("Аддоны не подключены. Аддон — отдельный мод-файл, который ставится рядом с TwitchCraft "
					+ "(например §eartifact-addon§7 — артефакты с проклятиями).");
			return 1;
		}
		switch (section) {
			case "actions" -> {
				var actions = dev.dedworkshop.twitchcraft.api.AddonRegistry.actions();
				if (actions.isEmpty()) {
					Chat.info("Аддоны не зарегистрировали действий.");
					return 1;
				}
				Chat.info("§5§lДействия аддонов §7(" + actions.size() + ")");
				for (var action : actions) {
					Chat.info("  §a● §f" + action.id() + "§7 — " + action.title() + " §8[" + action.trigger() + "]");
				}
				return 1;
			}
			case "rewards" -> {
				var rewards = dev.dedworkshop.twitchcraft.api.AddonRegistry.rewards();
				if (rewards.isEmpty()) {
					Chat.info("Ни одна награда за баллы канала не привязана к аддонам. Привязка идёт по id награды.");
					return 1;
				}
				Chat.info("§5§lНаграды, привязанные к аддонам §7(" + rewards.size() + ")");
				for (var binding : rewards) {
					Chat.info("  §a● §f" + (binding.rewardTitle().isBlank() ? "(без названия)" : binding.rewardTitle())
							+ "§7 — id §8" + binding.rewardId() + "§7, аддон §f" + binding.addonId());
				}
				return 1;
			}
			case "triggers" -> {
				var triggers = dev.dedworkshop.twitchcraft.api.AddonRegistry.customTriggers();
				if (triggers.isEmpty()) {
					Chat.info("Кастомные триггеры не зарегистрированы (у аддона их может быть до "
							+ dev.dedworkshop.twitchcraft.api.AddonRegistry.MAX_CUSTOM_TRIGGERS + ").");
					return 1;
				}
				Chat.info("§5§lКастомные триггеры аддонов §7(" + triggers.size() + ")");
				for (var trigger : triggers) {
					Chat.info("  §a● §f" + trigger.slot() + "§7 — " + trigger.name() + " §8[" + trigger.trigger() + "]§7, "
							+ "действий: " + trigger.actions().size() + (trigger.description().isBlank() ? "" : ", " + trigger.description()));
				}
				Chat.info("§7Запустить вручную: §f/twitch addons fire v0");
				return 1;
			}
			default -> {
				Chat.info("§5§lАддоны TwitchCraft §7(" + ids.size() + ") — " + dev.dedworkshop.twitchcraft.api.AddonRegistry.summary());
				for (String id : ids) {
					Chat.info("  §a● §f" + id);
				}
				Chat.info("§7Подробности: §f/twitch addons actions§7, §frewards§7, §ftriggers§7, запуск — §ffire v0…v3");
				return 1;
			}
		}
	}

	/** Ручной запуск кастомного триггера аддона (слот v0…v3) — проверить механику без зрителей. */
	private static int addonsFire(TwitchCraftClient mod, String slot) {
		int index;
		try {
			index = Integer.parseInt(slot.trim().toLowerCase(java.util.Locale.ROOT).replace("v", ""));
		} catch (NumberFormatException e) {
			Chat.error("Слот кастомного триггера — v0…v" + (dev.dedworkshop.twitchcraft.api.AddonRegistry.MAX_CUSTOM_TRIGGERS - 1)
					+ ", например §f/twitch addons fire v0");
			return 0;
		}
		var trigger = dev.dedworkshop.twitchcraft.api.AddonRegistry.customTrigger(index);
		if (trigger == null) {
			Chat.error("Слот v" + index + " пуст: этот кастомный триггер никто не зарегистрировал.");
			return 0;
		}
		String player = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getName().getString() : "Игрок";
		TwitchEvent event = TwitchEvent.test(TwitchEvent.Type.CHAT_COMMAND, player, 0, "", trigger.name(), "");
		if (mod.events() == null) {
			Chat.error("Обработчик событий ещё не запущен — попробуй в мире.");
			return 0;
		}
		int fired = 0;
		java.util.Map<String, String> vars = new java.util.LinkedHashMap<>(mod.globalPlaceholders());
		vars.putAll(dev.dedworkshop.twitchcraft.api.AddonRegistry.variables(event));
		for (var elements : trigger.actions()) {
			if (elements == null || elements.isEmpty()) {
				continue;
			}
			mod.events().runner().run(event, elements.toConfigAction(), vars, () -> { });
			fired++;
		}
		Chat.success("Кастомный триггер v" + index + " «" + trigger.name() + "» запущен вручную (действий: " + fired + ").");
		return 1;
	}

	private static int modules(TwitchCraftClient mod) {
		Chat.info("§5§lМодули §7(клик — переключить; также /twitch config)");
		Module.Kind kind = null;
		for (Module module : Module.values()) {
			if (module.kind != kind) {
				kind = module.kind;
				Chat.info("§8— " + kind.title);
			}
			boolean on = mod.isModuleEnabled(module);
			String scopeNote = "";
			if (module.scope != null && mod.tokens().hasTokens() && module.missingScope(mod.tokens()) != null) {
				scopeNote = " §c(нет права " + module.missingScope(mod.tokens()) + ")";
			}
			String line = "  " + (on ? "§a● " : "§7○ ") + "§f" + module.title + " §8[" + module.id + "]" + scopeNote;
			String command = "/twitch module " + module.id + (on ? " off" : " on");
			Chat.send(Component.literal(line).withStyle(style -> style
					.withClickEvent(new ClickEvent.SuggestCommand(command))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal(module.description + "\n§7" + command)))));
		}
		return 1;
	}

	private static int module(TwitchCraftClient mod, String id, Boolean enabled) {
		Module module = Module.byId(id);
		if (module == null) {
			Chat.error("Неизвестный модуль «" + id + "». Список: /twitch modules");
			return 0;
		}
		boolean target = enabled != null ? enabled : !mod.isModuleEnabled(module);
		if (!mod.setModuleEnabled(module, target)) {
			Chat.info("Модуль «" + module.title + "» уже " + (target ? "включён" : "выключен"));
			return 1;
		}
		if (target && module.scope != null && mod.tokens().hasTokens() && module.missingScope(mod.tokens()) != null) {
			Chat.warn("Для этого модуля нужно право " + module.missingScope(mod.tokens()) + ". Выполни §e/twitch logout§e → §e/twitch login§e.");
		}
		return 1;
	}

	// ---------- Цели ----------

	private static int goals(TwitchCraftClient mod) {
		if (!mod.isModuleEnabled(Module.GOALS)) {
			Chat.warn("Модуль «Цели» выключен. Включить: §e/twitch module goals on");
		}
		List<GoalTracker.Line> lines = mod.goalTracker().lines();
		if (lines.isEmpty()) {
			Chat.info("§7Целей нет. Добавь их в /twitch config → Цели или в twitchcraft.json (раздел goals).");
			return 1;
		}
		Chat.info("§5§lЦели §7(клик — протестировать награду)");
		for (GoalTracker.Line line : lines) {
			ModConfig.Goal goal = line.goal();
			GoalTracker.Progress p = line.progress();
			String state = p.done && !goal.repeat ? "§a✔ выполнена" : "§a" + p.count + "§7/§f" + line.target();
			String extra = (p.completed > 0 ? " §7· достигнута ×" + p.completed : "") + (goal.repeat ? "" : " §8(один раз)");
			Chat.send(Component.literal("  §6" + goal.name + " §7(" + goal.goalType().title + "): " + state + extra)
					.withStyle(style -> style
							.withClickEvent(new ClickEvent.SuggestCommand("/twitch test goal " + goal.name))
							.withHoverEvent(new HoverEvent.ShowText(Component.literal("Последний вклад: " + (p.lastUser == null || p.lastUser.isBlank() ? "—" : p.lastUser)
									+ "\n§7/twitch goals add \"" + goal.name + "\" <число> — поправить прогресс")))));
		}
		Chat.info("§7Сбросить прогресс: §e/twitch goals reset [название]");
		return 1;
	}

	private static int goalsReset(TwitchCraftClient mod, String name) {
		if (name == null) {
			mod.goalTracker().reset(null);
			Chat.success("Прогресс всех целей сброшен.");
			return 1;
		}
		ModConfig.Goal goal = mod.config().findGoal(name);
		if (goal == null) {
			Chat.error("Цель «" + name + "» не найдена. Список: /twitch goals");
			return 0;
		}
		mod.goalTracker().reset(goal.name);
		Chat.success("Прогресс цели «" + goal.name + "» сброшен.");
		return 1;
	}

	private static int goalsAdd(TwitchCraftClient mod, String name, int amount) {
		ModConfig.Goal goal = mod.config().findGoal(name);
		if (goal == null) {
			Chat.error("Цель «" + name + "» не найдена. Список: /twitch goals");
			return 0;
		}
		List<TwitchEvent> reached = mod.goalTracker().add(goal, amount);
		GoalTracker.Progress p = mod.goalTracker().peek(goal.name);
		Chat.success("Цель «" + goal.name + "»: " + p.count + "/" + Math.max(1, goal.target));
		for (TwitchEvent event : reached) {
			mod.onTwitchEvent(event);
		}
		return 1;
	}

	private static int run(Runnable action) {
		action.run();
		return 1;
	}

	private static int donations(TwitchCraftClient mod) {
		ModConfig.Donations settings = mod.config().donations;
		Chat.info("§6§l=== Донаты ===§r §7валюта: §f" + settings.currency + "§7, эффектов по сумме: §f" + mod.config().donationTiers.size()
				+ "§7, минимальная сумма: §f" + settings.minAmount);
		for (String line : mod.donations().statusLines()) {
			Chat.info(line);
		}
		Chat.info("§7DonationAlerts: 1) создай приложение на сайте:");
		Chat.send(Component.literal("§7   ").append(link("donationalerts.com/application/clients", "https://www.donationalerts.com/application/clients"))
				.append(Component.literal("§7 — Redirect URI: §f" + mod.donations().donationAlerts().redirectUri())));
		Chat.info("§7   2) §e/twitch donations da client <ID>§7  3) §e/twitch donations da login§7 и разреши доступ в браузере");
		Chat.info("§7DonatePay: ключ в кабинете donatepay.ru → Настройки → API, затем §e/twitch donations dp key <ключ>");
		Chat.info("§7Проверка: §e/twitch test donation 500 Привет§7; эффекты — в §e/twitch config§7 → «Донаты»");
		return 1;
	}

	/** Ценник донатов в чат игры: ☠ плохие и ★ хорошие события по сумме. */
	public static int printDonationPrices(TwitchCraftClient mod) {
		ModConfig config = mod.config();
		String symbol = TwitchEvent.currencySymbol(config.donations.currency);
		List<String> bad = DonationPresets.priceLines(config.donationTiers, symbol, 'b');
		List<String> good = DonationPresets.priceLines(config.donationTiers, symbol, 'g');
		List<String> other = DonationPresets.priceLines(config.donationTiers, symbol, 'o');
		Chat.info("§6§l=== Ценник донатов ===§r §7(общая таблица, " + config.donationTiers.size() + " записей; срабатывает самый большой порог ≤ суммы)");
		if (!bad.isEmpty()) {
			Chat.info("§c☠ Плохие (" + bad.size() + "):");
			bad.forEach(Chat::info);
		}
		if (!good.isEmpty()) {
			Chat.info("§a★ Хорошие (" + good.size() + "):");
			good.forEach(Chat::info);
		}
		if (!other.isEmpty()) {
			Chat.info("§7• Прочие (" + other.size() + "):");
			other.forEach(Chat::info);
		}
		if (!config.donationAlertsTiers.isEmpty() || !config.donatePayTiers.isEmpty()) {
			Chat.info("§7У сервиса со своей таблицей (DonationAlerts: " + config.donationAlertsTiers.size() + ", DonatePay: "
					+ config.donatePayTiers.size() + ") действует она, а не общая.");
		}
		Chat.info("§7Зрителям: §e!ценник§7, §e!плохое§7, §e!хорошее§7. Правка: §e/twitch config§7 → Донаты → Общие эффекты. "
				+ "Вернуть ценник по умолчанию: §e/twitch donations preset");
		return 1;
	}

	/** Заменить общую таблицу донатов готовым ценником (25 ☠ + 25 ★ + «Спасибо»). */
	private static int donationPreset(TwitchCraftClient mod, boolean confirmed) {
		ModConfig config = mod.config();
		if (!confirmed) {
			Chat.warn("Это заменит общую таблицу эффектов за донаты (" + config.donationTiers.size() + " записей) готовым ценником: "
					+ "25 плохих, 25 хороших и «Спасибо» за мелочь. Текущая таблица будет сохранена в копии конфига.");
			Chat.send(Component.literal("§7Подтвердить (клик): ").append(Component.literal("§e/twitch donations preset confirm").withStyle(style -> style
					.withClickEvent(new ClickEvent.SuggestCommand("/twitch donations preset confirm"))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("Вставить команду в чат"))))));
			return 1;
		}
		Path backup = SafeFiles.backupCopy(ModConfig.path(), ".bak-" + java.time.LocalDate.now());
		config.donationTiers = DonationPresets.defaults();
		mod.configEdited();
		Chat.success("Ценник донатов загружен: " + config.donationTiers.size() + " записей"
				+ (backup == null ? "." : " (копия старого конфига: " + backup.getFileName() + ")."));
		Chat.info("§7Посмотреть: §e/twitch donations prices§7; зрителям — §e!ценник");
		return 1;
	}

	private static int daClient(TwitchCraftClient mod, String clientId) {
		mod.config().donations.donationAlertsClientId = clientId.trim();
		mod.config().save();
		Chat.success("Client ID DonationAlerts сохранён. Теперь введи §e/twitch donations da login");
		return 1;
	}

	private static int daLogin(TwitchCraftClient mod) {
		String url = mod.donations().donationAlerts().beginLogin();
		if (url == null) {
			return 0;
		}
		Chat.send(Component.literal("§7Открой ссылку и разреши доступ: ").append(link("войти в DonationAlerts", url)));
		Chat.info("§7После подтверждения в браузере вход завершится сам (жду до 10 минут; отмена: /twitch donations da cancel).");
		return 1;
	}

	// ---------- VK Video Live ----------

	private static int vk(TwitchCraftClient mod) {
		VkLive vk = mod.vk();
		ModConfig.Vk settings = mod.config().vk;
		Chat.info("§6§l=== VK Video Live ===§r" + (mod.isModuleEnabled(Module.VK_VIDEO_LIVE) ? "" : " §8[модуль выключен: /twitch module vkVideoLive on]"));
		Chat.info("§7Статус: " + vk.statusText());
		Chat.info("§7Канал: " + (settings.slug().isEmpty() ? (mod.vkStore().ownChannelUrl.isBlank() ? "§eне указан" : "§f" + mod.vkStore().ownChannelUrl + " §7(свой)")
				: "§f" + settings.slug()) + "§7, приложение: " + (vk.isConfigured() ? "§aуказано" : "§cне указано"));
		Chat.info("§7Флаги: чат " + onOff(settings.showChat) + "§7, команды " + onOff(settings.chatCommands) + "§7, награды " + onOff(settings.rewards)
				+ "§7, фолловы " + onOff(settings.follows) + "§7, ответы " + onOff(settings.replies) + "§7, подтверждение наград " + onOff(settings.manageDemands));
		Chat.info("§7Настройка: 1) создай приложение (Redirect URI: §f" + vk.redirectUri() + "§7):");
		Chat.send(Component.literal("§7   ").append(link("dev.live.vkvideo.ru/apps", "https://dev.live.vkvideo.ru/apps")));
		Chat.info("§7   2) §e/twitch vk app <ID> <секрет>§7  3) §e/twitch vk login§7 и разреши доступ в браузере");
		Chat.info("§7   4) §e/twitch vk rewards sync§7 — создать награды «Пакость»/«Подарок» на VK. Чужой канал: §e/twitch vk channel <ссылка>");
		Chat.info("§7Проверка: §e/twitch vk test chat !ценник§7, §e/twitch vk test reward Пакость§7; настройки — §e/twitch config§7 → «VK Video Live»");
		return 1;
	}

	private static String onOff(boolean value) {
		return value ? "§aвкл" : "§cвыкл";
	}

	private static int vkChannel(TwitchCraftClient mod, String url) {
		String slug = ModConfig.Vk.slugOf(url);
		if (slug.isEmpty()) {
			Chat.error("Не понял канал. Пример: /twitch vk channel https://live.vkvideo.ru/dedworkshop");
			return 0;
		}
		mod.config().vk.channelUrl = slug;
		mod.config().save();
		Chat.success("Канал VK Video Live: §f" + slug + (mod.vk().isActive() || mod.vk().isConnecting() ? " §7— переподключаюсь..." : ""));
		mod.vk().syncWithConfig();
		return 1;
	}

	private static int vkApp(TwitchCraftClient mod, String clientId, String secret) {
		mod.vk().setApp(clientId, secret);
		Chat.success("Приложение VK Video Live сохранено (секрет — в config/twitchcraft-vk.json). Теперь введи §e/twitch vk login");
		return 1;
	}

	private static int vkLogin(TwitchCraftClient mod) {
		String url = mod.vk().beginLogin();
		if (url == null) {
			return 0;
		}
		Chat.send(Component.literal("§7Открой ссылку и разреши доступ: ").append(link("войти в VK Video Live", url)));
		Chat.info("§7После подтверждения вход завершится сам (жду до 10 минут; отмена: /twitch vk cancel). "
				+ "Если страница localhost не открылась — скопируй code из адресной строки: /twitch vk code <code>");
		return 1;
	}

	private static int vkDebug(TwitchCraftClient mod, boolean on) {
		mod.config().vk.debugEvents = on;
		mod.config().save();
		Chat.info("VK: подробный лог событий " + (on ? "§aвключён§r §7(все события WebSocket пишутся в logs/latest.log)" : "§7выключен"));
		return 1;
	}

	private static int test(TwitchCraftClient mod, TwitchEvent event) {
		Chat.info("§7[тест] Имитирую событие " + event.type());
		mod.onTwitchEvent(event);
		return 1;
	}

	private static int help(FabricClientCommandSource source) {
		source.sendFeedback(Component.literal("§5§l=== TwitchCraft ===§r"));
		source.sendFeedback(Component.literal("§7Первая настройка:"));
		source.sendFeedback(Component.literal("§7 1. Создай приложение на ")
				.append(link("dev.twitch.tv/console/apps", "https://dev.twitch.tv/console/apps"))
				.append(Component.literal("§7 (тип клиента: §fPublic§7, Redirect URL: §fhttp://localhost§7)")));
		source.sendFeedback(Component.literal("§7 2. Скопируй Client ID и введи: §e/twitch setup <clientId>"));
		source.sendFeedback(Component.literal("§7 3. Введи §e/twitch login§7 и подтверди код на twitch.tv/activate"));
		source.sendFeedback(Component.literal(""));
		source.sendFeedback(Component.literal("§e/twitch config§7 — экран настроек (модули, награды, события, цели)"));
		source.sendFeedback(Component.literal("§e/twitch modules§7 — список модулей, §e/twitch module <id> on|off§7 — включить/выключить"));
		source.sendFeedback(Component.literal("§e/twitch addons§7 — подключённые аддоны (отдельные мод-файлы, например «Артефакты»); "
				+ "§f/twitch addons actions|rewards|triggers§7 — что они добавили, §f/twitch addons fire v0§7 — проверить кастомный триггер"));
		source.sendFeedback(Component.literal("§e/twitch goals§7 — прогресс целей, §e/twitch goals reset§7 — сбросить"));
		source.sendFeedback(Component.literal("§e/twitch fund§7 — сборы средств (боссбар): §efund create <имя> <цель>§7, §efund add <имя> <сумма>§7, §efund reset"));
		source.sendFeedback(Component.literal("§e/twitch status§7 — состояние подключения и прав"));
		source.sendFeedback(Component.literal("§e/twitch connect§7 / §e disconnect§7 — подключиться / отключиться"));
		source.sendFeedback(Component.literal("§e/twitch reload§7 — перечитать конфиг twitchcraft.json"));
		source.sendFeedback(Component.literal("§e/twitch rewards§7 — список наград, §e/twitch rewards sync§7 — создать их на Twitch"));
		source.sendFeedback(Component.literal("§e/twitch pause§7 / §eresume§7 (F8) — пауза событий, §e/twitch queue§7 — очередь"));
		source.sendFeedback(Component.literal("§e/twitch replay§7 (F9) — повторить последнее событие"));
		source.sendFeedback(Component.literal("§e/twitch stats§7, §e/twitch history [n]§7 — статистика и история сессии"));
		source.sendFeedback(Component.literal("§e/twitch overlay§7 (F7) — оверлей, §e/twitch chat on|off§7 — чат Twitch в игре"));
		source.sendFeedback(Component.literal("§e/twitch say <текст>§7 — написать в чат Twitch"));
		source.sendFeedback(Component.literal("§e/twitch donations§7 — донаты (DonationAlerts / DonatePay): статус и настройка"));
		source.sendFeedback(Component.literal("§e/twitch vk§7 — VK Video Live: статус, app/login/channel/connect/say/rewards sync/test"));
		source.sendFeedback(Component.literal("§e/twitch clip [повод]§7 (F10) / §e/twitch marker [текст]§7 — клип / метка стрима прямо сейчас"));
		source.sendFeedback(Component.literal("§e/twitch game§7 — события игры → чат: счётчики, §egame test death|advancement|boss|dimension§7, §egame reset"));
		source.sendFeedback(Component.literal("§e/twitch timers§7 — таймеры чата, §etimers post|on|off <имя>"));
		source.sendFeedback(Component.literal("§e/twitch test <follow|sub|resub|gift|cheer|raid|reward|chat|goal|donation ...>§7 — проверить действия"));
		return 1;
	}

	private static Component link(String text, String url) {
		return Component.literal(text).withStyle(style -> style
				.withColor(ChatFormatting.AQUA)
				.withUnderlined(true)
				.withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Открыть в браузере"))));
	}

	private static int status(TwitchCraftClient mod) {
		ModConfig config = mod.config();
		TokenStore tokens = mod.tokens();
		Minecraft mc = Minecraft.getInstance();

		boolean hasClientId = config.clientId != null && !config.clientId.isBlank();
		Chat.info("§7Client ID: " + (hasClientId ? "§aуказан" : "§cне указан §7(/twitch setup <clientId>)"));
		if (tokens.hasTokens()) {
			long minutesLeft = Math.max(0, tokens.secondsLeft() / 60);
			Chat.info("§7Аккаунт: §d" + tokens.displayOrLogin() + " §7(токен действует ещё ~" + minutesLeft + " мин, обновляется автоматически)");
			StringBuilder extra = new StringBuilder();
			for (String scope : TwitchAuth.OPTIONAL_SCOPES) {
				extra.append(tokens.hasScope(scope) ? "§a✔ " : "§c✘ ").append("§7").append(TwitchAuth.describeScope(scope)).append("  ");
			}
			Chat.info("§7Доп. права: " + extra);
		} else {
			Chat.info("§7Аккаунт: §cне авторизован §7(/twitch login)");
		}
		Chat.info("§7EventSub: " + mod.eventSub().statusText());
		if (mod.donations().anyConfigured()) {
			for (String line : mod.donations().statusLines()) {
				Chat.info(line);
			}
		}
		if (mod.vk().isConfigured() || mod.vk().isLoggedIn()) {
			Chat.info("§7VK Video Live" + (mod.isModuleEnabled(Module.VK_VIDEO_LIVE) ? "" : " §8[модуль выкл]§7") + ": " + mod.vk().statusText());
		}
		if (mod.isLoginInProgress()) {
			Chat.info("§7Авторизация: §eожидание подтверждения кода");
		}

		String mode;
		if (mc.player == null) {
			mode = "§cне в мире";
		} else if (mc.getSingleplayerServer() != null) {
			mode = "§aодиночная игра §7(команды выполняются с правами оператора)";
		} else {
			mode = "§eсервер §7(команды отправляются от игрока — нужен OP)";
		}
		Chat.info("§7Режим: " + mode);
		Chat.info("§7События: " + (mod.events().isPaused() ? "§eпауза" : "§aобрабатываются")
				+ " §7| в очереди: §f" + mod.events().queueSize()
				+ " §7| выполняется команд: §f" + mod.events().runner().runningCount()
				+ " §7| оверлей: " + (mod.isOverlayVisible() ? "§aвкл" : "§7выкл"));
		if (mod.tokens().hasTokens()) {
			Chat.info("§7Стрим: " + mod.streamStatus().describe() + " §7| клипы: " + (mod.clips().canClip() ? "§aправо есть" : "§cнет права")
					+ "§7, метки: " + (mod.clips().canMark() ? "§aправо есть" : "§cнет права")
					+ (mod.clips().canClip() && mod.clips().canMark() ? "" : " §8(/twitch logout → /twitch login)")
					+ " §7| смертей за стрим: §f" + mod.gameStats().deaths);
		}
		if (config.loadError != null) {
			Chat.error("Конфиг не прочитан (" + config.loadError + ") — работают настройки по умолчанию.");
		} else if (!config.warnings.isEmpty()) {
			Chat.warn("В конфиге " + config.warnings.size() + " предупреждений — смотри /twitch reload");
		}
		return 1;
	}

	private static int rewards(TwitchCraftClient mod) {
		ModConfig config = mod.config();
		if (config.rewards == null || config.rewards.isEmpty()) {
			Chat.warn("Награды не настроены. Отредактируй config/twitchcraft.json и введи /twitch reload");
			return 1;
		}
		Chat.info("§7Настроенные награды (название должно совпадать с наградой на Twitch):");
		for (var entry : config.rewards.entrySet()) {
			ModConfig.Action action = entry.getValue();
			int commands = action == null || action.commands == null ? 0 : action.commands.size();
			String name = entry.getKey().equals("*") ? "§8* (любая другая)" : "§d" + entry.getKey();
			String extra = "";
			if (action != null) {
				if (action.cost > 0) extra += " §7· " + action.cost + " баллов";
				if (action.cooldown > 0) extra += " §7· кд " + action.cooldown + " с";
				if (action.chance < 100) extra += " §7· шанс " + action.chance + "%";
				if (!action.enabled) extra += " §c· выключено";
			}
			Chat.send(Component.literal("  " + name + " §7— команд: " + commands + extra)
					.withStyle(style -> style
							.withClickEvent(new ClickEvent.SuggestCommand("/twitch test reward " + entry.getKey()))
							.withHoverEvent(new HoverEvent.ShowText(Component.literal("Нажми, чтобы протестировать")))));
		}
		if (mod.tokens().hasScope(RewardManager.SCOPE)) {
			Chat.info("§7Создать недостающие награды на Twitch: §e/twitch rewards sync");
		}
		return 1;
	}

	private static int queue(TwitchCraftClient mod) {
		int size = mod.events().queueSize();
		Chat.info("§7В очереди: §f" + size + (mod.events().isPaused() ? " §e(пауза — /twitch resume или F8)" : ""));
		if (size > 0) {
			Chat.info("§7Очистить с возвратом баллов: §e/twitch queue clear");
		}
		return 1;
	}

	// ---------- 1.7.0: клипы, события игры, таймеры ----------

	private static final SuggestionProvider<FabricClientCommandSource> GAME_KINDS = (ctx, builder) -> {
		for (String kind : List.of("death", "advancement", "goal", "challenge", "boss", "dimension")) {
			builder.suggest(kind);
		}
		return builder.buildFuture();
	};

	private static final SuggestionProvider<FabricClientCommandSource> TIMER_NAMES = (ctx, builder) -> {
		TwitchCraftClient mod = TwitchCraftClient.get();
		if (mod != null && mod.config().timers != null) {
			for (ModConfig.ChatTimer timer : mod.config().timers) {
				if (timer != null && timer.name != null && !timer.name.isBlank()) {
					builder.suggest(timer.name);
				}
			}
		}
		return builder.buildFuture();
	};

	private static String gameKind(String word) {
		return switch (word == null ? "" : word.toLowerCase(java.util.Locale.ROOT)) {
			case "advancement", "task", "достижение" -> TwitchEvent.GAME_ADVANCEMENT;
			case "goal", "цель" -> TwitchEvent.GAME_ADVANCEMENT_GOAL;
			case "challenge", "испытание" -> TwitchEvent.GAME_ADVANCEMENT_CHALLENGE;
			case "boss", "босс" -> TwitchEvent.GAME_BOSS;
			case "dimension", "измерение", "nether", "end" -> TwitchEvent.GAME_DIMENSION;
			default -> TwitchEvent.GAME_DEATH;
		};
	}

	private static int clip(TwitchCraftClient mod, String why) {
		String reason = why == null || why.isBlank() ? "вручную" : why.trim();
		if (!mod.clips().clip(reason, true)) {
			return 0;
		}
		Chat.info("§7Запрос на клип отправлен (" + reason + ").");
		if (mod.clips().canMark()) {
			mod.clips().marker("Клип: " + reason, false);
		}
		return 1;
	}

	private static int marker(TwitchCraftClient mod, String text) {
		String description = text == null || text.isBlank() ? "Метка из игры" : text.trim();
		return mod.clips().marker(description, true) ? 1 : 0;
	}

	private static int game(TwitchCraftClient mod) {
		dev.dedworkshop.twitchcraft.game.GameStats stats = mod.gameStats();
		ModConfig.GameEventsSettings settings = mod.config().gameEventsSettings;
		Chat.info("§2§l=== События игры → чат ===§r" + (mod.isModuleEnabled(Module.GAME_EVENTS) ? "" : " §8[модуль выключен: /twitch module gameEvents on]"));
		Chat.info("§7Куда: Twitch " + onOff(settings.toTwitch) + "§7, VK " + onOff(settings.toVk)
				+ "§7; клипы и метки: " + (mod.isModuleEnabled(Module.CLIPS) ? "§aвкл" : "§cвыкл")
				+ "§7 (клип " + (mod.clips().canClip() ? "§aправо есть" : "§cнет права clips:edit") + "§7, метка "
				+ (mod.clips().canMark() ? "§aправо есть" : "§cнет права channel:manage:broadcast") + "§7)");
		Chat.info("§7Стрим: " + mod.streamStatus().describe());
		Chat.info("§7За сеанс: смертей §f" + stats.deaths + "§7, достижений §f" + stats.advancements + "§7, боссов §f" + stats.bosses
				+ "§7, переходов §f" + stats.dimensionChanges + "§7; в игре §f" + dev.dedworkshop.twitchcraft.game.GameStats.formatDuration(System.currentTimeMillis() - stats.startedAt));
		Chat.info("§7За всё время: смертей §f" + stats.deathsTotal() + "§7, достижений §f" + stats.advancementsTotal() + "§7, боссов §f" + stats.bossesTotal()
				+ "§7, сеансов §f" + stats.sessions() + (stats.lastDeath.isBlank() ? "" : "§7; последняя смерть: §f" + stats.lastDeath));
		Chat.info("§7Клипов за сеанс: §f" + mod.clips().clipsCreated() + "§7, меток: §f" + mod.clips().markersCreated()
				+ (mod.clips().lastClipUrl().isBlank() ? "" : "§7, последний клип: §f" + mod.clips().lastClipUrl()));
		int configured = 0;
		for (String key : ModConfig.GAME_EVENT_KEYS) {
			ModConfig.Action action = mod.config().gameEvents.get(key);
			if (action != null && action.enabled && !action.isEmpty()) {
				configured++;
			}
		}
		Chat.info("§7Настроено действий: §f" + configured + "/" + ModConfig.GAME_EVENT_KEYS.size()
				+ "§7 — §e/twitch config§7 → «События игры → чат». Проверка: §e/twitch game test death§7, §ereset§7 — обнулить счётчики сеанса");
		return 1;
	}

	private static int gameReset(TwitchCraftClient mod, boolean all) {
		mod.gameStats().resetSession();
		if (all) {
			mod.gameStats().resetTotals();
		}
		Chat.success(all ? "Счётчики событий игры обнулены (и за всё время тоже)." : "Счётчики событий игры за сеанс обнулены.");
		return 1;
	}

	private static int timers(TwitchCraftClient mod) {
		List<ModConfig.ChatTimer> timers = mod.config().timers;
		Chat.info("§6§l=== Таймеры чата ===§r" + (mod.isModuleEnabled(Module.CHAT_TIMERS) ? "" : " §8[модуль выключен: /twitch module chatTimers on]"));
		if (timers == null || timers.isEmpty()) {
			Chat.info("§7Таймеров нет. Добавить: §e/twitch config§7 → «Таймеры чата».");
			return 1;
		}
		for (ModConfig.ChatTimer timer : timers) {
			long left = mod.timers().secondsLeft(timer);
			String when = !timer.enabled ? "§8выключен" : left < 0 ? "§7ждёт" : left == 0 ? "§eждёт живого чата" : "§7через " + (left / 60) + " мин";
			Chat.info((timer.enabled ? "§a● " : "§8○ ") + "§f" + timer.name + " §7— каждые " + timer.intervalMinutes + " мин"
					+ (timer.minChatMessages > 0 ? ", если чат живой (" + timer.minChatMessages + "+)" : "") + ", " + when
					+ " §8[" + (timer.twitch ? "Twitch" : "") + (timer.twitch && timer.vk ? "+" : "") + (timer.vk ? "VK" : "") + "]"
					+ (mod.timers().posts(timer) > 0 ? " §8×" + mod.timers().posts(timer) : ""));
		}
		Chat.info("§7Команды: §etimers post <имя>§7 — написать сейчас, §etimers on|off <имя>§7 — включить/выключить");
		return 1;
	}

	private static int timerPost(TwitchCraftClient mod, String name) {
		ModConfig.ChatTimer timer = mod.config().findTimer(name);
		if (timer == null) {
			Chat.error("Таймер «" + name + "» не найден. Список: /twitch timers");
			return 0;
		}
		if (!mod.timers().post(timer, true)) {
			Chat.error("У таймера «" + name + "» пустой текст.");
			return 0;
		}
		Chat.success("Таймер «" + timer.name + "» отправлен в чат.");
		return 1;
	}

	private static int timerEnable(TwitchCraftClient mod, String name, boolean enabled) {
		ModConfig.ChatTimer timer = mod.config().findTimer(name);
		if (timer == null) {
			Chat.error("Таймер «" + name + "» не найден. Список: /twitch timers");
			return 0;
		}
		timer.enabled = enabled;
		mod.configEdited();
		Chat.success("Таймер «" + timer.name + "» " + (enabled ? "включён" : "выключен") + ".");
		return 1;
	}

	private static int stats(TwitchCraftClient mod) {
		Chat.info("§5§lСтатистика сессии");
		for (String line : mod.events().stats().report()) {
			Chat.info(line);
		}
		if (mod.gameStats() != null) {
			Chat.info("§7Игра: смертей §f" + mod.gameStats().deaths + "§7 (всего " + mod.gameStats().deathsTotal() + "), достижений §f"
					+ mod.gameStats().advancements + "§7, боссов §f" + mod.gameStats().bosses + "§7 — подробнее: /twitch game");
		}
		Chat.info("§7Журнал: §f" + mod.events().log().path().getFileName() + " §7(папка logs)");
		return 1;
	}

	private static int history(TwitchCraftClient mod, int count) {
		var entries = mod.events().stats().recent(count);
		if (entries.isEmpty()) {
			Chat.info("§7История пуста — событий ещё не было.");
			return 1;
		}
		Chat.info("§7Последние события (новые сверху):");
		for (SessionStats.Entry entry : entries) {
			String time = TIME.format(Instant.ofEpochMilli(entry.time()).atZone(ZoneId.systemDefault()));
			String status = entry.status();
			String statusColor = status.startsWith("выполнено") ? "§a" : status.startsWith("пропущено") || status.startsWith("кулдаун") ? "§c" : "§7";
			Chat.info("§8" + time + " §f" + entry.event().shortText() + " " + statusColor + status
					+ (entry.event().synthetic() ? " §8(тест)" : ""));
		}
		return 1;
	}

	private static int chatToggle(TwitchCraftClient mod, boolean on) {
		if (on && !mod.tokens().hasScope(EventSubClient.CHAT_SCOPE)) {
			Chat.warn("Для чата нужно право user:read:chat. Выполни §e/twitch logout§e, затем §e/twitch login§e.");
		}
		if (!mod.setModuleEnabled(Module.TWITCH_CHAT, on)) {
			Chat.info("Чат Twitch в игре уже " + (on ? "включён" : "выключен"));
		}
		return 1;
	}
}
