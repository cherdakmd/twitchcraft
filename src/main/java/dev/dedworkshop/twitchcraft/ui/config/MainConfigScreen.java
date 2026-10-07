package dev.dedworkshop.twitchcraft.ui.config;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.config.TokenStore;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.twitch.RewardManager;
import dev.dedworkshop.twitchcraft.twitch.TwitchAuth;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import dev.dedworkshop.twitchcraft.util.Chat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.net.URI;

/**
 * Главный экран настроек TwitchCraft (открывается из Mod Menu или командой /twitch config).
 * Работает и из главного меню: код авторизации и сообщения мода показываются прямо на экране.
 */
class MainConfigScreen extends BaseScreen {
	private String signature = "";
	private int ticks;

	MainConfigScreen(TwitchCraftClient mod, Screen parent) {
		super(mod, parent, "TwitchCraft — настройки");
	}

	@Override
	protected void buildContent(LinearLayout content) {
		signature = signature();
		content.addChild(new StringWidget(accountText(), font));
		content.addChild(new StringWidget(connectionText(), font));

		TwitchAuth.DeviceCode device = mod.pendingDeviceCode();
		if (device != null) {
			content.addChild(SpacerElement.height(2));
			content.addChild(new StringWidget(Component.literal("Код: ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(device.userCode()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
					.append(Component.literal("  → введи его на twitch.tv/activate").withStyle(ChatFormatting.GRAY)), font));
			LinearLayout codeRow = LinearLayout.horizontal().spacing(10);
			codeRow.addChild(Widgets.button("Открыть twitch.tv/activate", Widgets.HALF,
					() -> ConfirmLinkScreen.confirmLinkNow(this, URI.create(device.verificationUri())),
					"Откроется браузер; войди в Twitch и введи код"));
			codeRow.addChild(Widgets.button("Скопировать код", Widgets.HALF, () -> {
				minecraft.keyboardHandler.setClipboard(device.userCode());
				Chat.info("Код скопирован: " + device.userCode());
			}));
			content.addChild(codeRow);
		}

		String last = Chat.lastText();
		if (!last.isEmpty() && System.currentTimeMillis() - Chat.lastTime() < 120_000) {
			content.addChild(Widgets.clipped(font, Component.literal("» " + last).withStyle(ChatFormatting.YELLOW), Widgets.FULL));
		}
		content.addChild(SpacerElement.height(4));

		GridLayout grid = new GridLayout().columnSpacing(10).rowSpacing(4);
		GridLayout.RowHelper rows = grid.createRowHelper(2);

		rows.addChild(Widgets.button("Модули", Widgets.HALF, () -> open(new ModulesScreen(mod, this)),
				"Включить или выключить любую функцию мода"));
		rows.addChild(Widgets.button("Общие настройки", Widgets.HALF, () -> open(new SettingsScreen(mod, this)),
				"Client ID, очередь, чат, оверлей, цели"));

		rows.addChild(Widgets.button("События Twitch", Widgets.HALF, () -> open(new EventsScreen(mod, this)),
				"Фоллов, подписка, ресаб, подарки, рейд, битсы"));
		rows.addChild(Widgets.button("Награды за баллы", Widgets.HALF,
				() -> open(new ActionListScreen(mod, this, ActionKind.REWARD, "Награды за баллы канала", mod.config().rewards, null)),
				"Что происходит при активации награды"));

		rows.addChild(Widgets.button("Чат-команды", Widgets.HALF,
				() -> open(new ActionListScreen(mod, this, ActionKind.CHAT_COMMAND, "Чат-команды зрителей", mod.config().chatCommands, null)),
				"Команды вида !zombie из чата Twitch"));
		rows.addChild(Widgets.button("Цели", Widgets.HALF, () -> open(new GoalsScreen(mod, this)),
				"Каждые N фолловеров / сабов / битсов / рублей — награда"));

		rows.addChild(Widgets.button("Донаты", Widgets.HALF, () -> open(new DonationsScreen(mod, this)),
				"DonationAlerts и DonatePay: вход, эффекты по сумме доната"));
		rows.addChild(Widgets.button("Эффекты за донаты", Widgets.HALF,
				() -> open(new ActionListScreen(mod, this, ActionKind.TIER, "Эффекты за донаты (все сервисы)", mod.config().donationTiers, TwitchEvent.Type.DONATION)),
				"Что происходит при донате на 50, 100, 500... (общая таблица)"));

		rows.addChild(Widgets.button("Сборы средств (боссбар)", Widgets.HALF, () -> open(new FundraisersScreen(mod, this)),
				"Полоса сбора вверху экрана: «Сбор на микрофон: 3 500 / 10 000 ₽». Донаты заполняют её сами"));
		rows.addChild(Widgets.button("VK Video Live", Widgets.HALF, () -> open(new VkScreen(mod, this)),
				"Чат, команды, награды за баллы и фолловы с live.vkvideo.ru"));
		rows.addChild(Widgets.button("YouTube Live", Widgets.HALF, () -> open(new YoutubeScreen(mod, this)),
				"YouTube Data API v3: OAuth, live chat, команды, Super Chat и членства"));

		rows.addChild(Widgets.button("События игры → чат", Widgets.HALF, () -> open(new GameEventsScreen(mod, this)),
				"Смерти со счётчиком, достижения, боссы и смена измерения — сообщением в чаты Twitch, VK и YouTube"));
		rows.addChild(Widgets.button("Клипы и метки", Widgets.HALF, () -> open(new ClipsScreen(mod, this)),
				"Автоклип и метка стрима при смерти, донате, боссе; F10 — клип вручную"));

		rows.addChild(Widgets.button("Триггеры аддонов", Widgets.HALF, () -> open(new AddonTriggersScreen(mod, this)),
				"Кастомные триггеры аддонов v0…v3: посмотреть и привязать к ним действия из конфига"));
		rows.addChild(Widgets.button("Таймеры чата", Widgets.HALF, () -> open(new TimersScreen(mod, this)),
				"Напоминания в чат раз в N минут, когда чат живой: ценник, соцсети..."));

		content.addChild(grid);
		content.addChild(SpacerElement.height(6));
		content.addChild(Widgets.header(font, "Действия"));

		GridLayout actions = new GridLayout().columnSpacing(10).rowSpacing(4);
		GridLayout.RowHelper actionRows = actions.createRowHelper(2);

		actionRows.addChild(Widgets.button(loginLabel(), Widgets.HALF, this::loginOrLogout,
				"Авторизация через код на twitch.tv/activate"));
		actionRows.addChild(Widgets.button(connectLabel(), Widgets.HALF, this::connectOrDisconnect,
				"Подключение к событиям Twitch (EventSub)"));
		actionRows.addChild(Widgets.button("Перечитать конфиг", Widgets.HALF, () -> {
			mod.reloadConfig();
			refresh();
		}, "Загрузить twitchcraft.json заново (если правил файл вручную)"));
		actionRows.addChild(Widgets.button("Создать награды на Twitch", Widgets.HALF, this::syncRewards,
				"Создать на Twitch награды из списка «Награды за баллы» (/twitch rewards sync)"));
		content.addChild(actions);

		ModConfig config = mod.config();
		if (config.loadError != null) {
			content.addChild(SpacerElement.height(4));
			content.addChild(new StringWidget(Component.literal("Файл конфига не прочитан — работают настройки по умолчанию")
					.withStyle(ChatFormatting.RED), font));
		} else if (config.warnings != null && !config.warnings.isEmpty()) {
			content.addChild(SpacerElement.height(4));
			content.addChild(new StringWidget(Component.literal("Предупреждений в конфиге: " + config.warnings.size()
					+ " (подробности: /twitch reload)").withStyle(ChatFormatting.YELLOW), font));
		}
		content.addChild(SpacerElement.height(4));
		content.addChild(Widgets.gray(font, "Файл: config/twitchcraft.json · команды: /twitch"));
	}

	@Override
	protected void buildFooter(LinearLayout footer) {
		footer.addChild(Widgets.button("Готово", Widgets.HALF, this::onClose));
	}

	@Override
	public void tick() {
		super.tick();
		// Раз в полсекунды проверяем, не изменилось ли состояние (вход, подключение, сообщения) — и перестраиваем экран
		if (++ticks % 10 == 0 && !signature.equals(signature())) {
			refresh();
		}
	}

	private String signature() {
		TwitchAuth.DeviceCode device = mod.pendingDeviceCode();
		String last = System.currentTimeMillis() - Chat.lastTime() < 120_000 ? Chat.lastText() : "";
		return accountText().getString() + "|" + connectionText().getString() + "|" + loginLabel() + "|" + connectLabel()
				+ "|" + (device == null ? "" : device.userCode()) + "|" + last + "|" + mod.config().warnings.size();
	}

	private Component accountText() {
		TokenStore tokens = mod.tokens();
		ModConfig config = mod.config();
		if (config.clientId == null || config.clientId.isBlank()) {
			return Component.literal("Client ID не указан — открой «Общие настройки»").withStyle(ChatFormatting.RED);
		}
		if (mod.isLoginInProgress()) {
			return Component.literal(mod.pendingDeviceCode() == null ? "Авторизация: запрашиваю код у Twitch..." : "Авторизация: жду подтверждения на Twitch...")
					.withStyle(ChatFormatting.YELLOW);
		}
		if (!tokens.hasTokens()) {
			return Component.literal("Аккаунт: не авторизован — нажми «Войти в Twitch»").withStyle(ChatFormatting.RED);
		}
		return Component.literal("Аккаунт: ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(tokens.displayOrLogin()).withStyle(ChatFormatting.LIGHT_PURPLE));
	}

	private Component connectionText() {
		String status = mod.eventSub().statusText();
		int enabled = 0;
		for (Module module : Module.values()) {
			if (mod.isModuleEnabled(module)) {
				enabled++;
			}
		}
		String donations = mod.donations().overlayMark();
		String vk = mod.vk().overlayMark();
		String youtube = mod.youtube().overlayMark();
		return Component.literal("EventSub: ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(status))
				.append(Component.literal((donations.isEmpty() ? "" : "  ·  донаты: " + donations)
						+ (vk.isEmpty() ? "" : "  ·  " + vk)
						+ (youtube.isEmpty() ? "" : "  ·  " + youtube)
						+ "  ·  модулей включено: " + enabled + "/" + Module.values().length).withStyle(ChatFormatting.GRAY));
	}

	private String loginLabel() {
		if (mod.isLoginInProgress()) {
			return "Отменить вход";
		}
		return mod.tokens().hasTokens() ? "Выйти из Twitch" : "Войти в Twitch";
	}

	private String connectLabel() {
		return mod.eventSub().isActive() ? "Отключиться" : "Подключиться";
	}

	private void loginOrLogout() {
		if (mod.isLoginInProgress()) {
			mod.cancelLogin();
		} else if (mod.tokens().hasTokens()) {
			confirm("Выйти из Twitch?", "Токены будут удалены, соединение закрыто. Для повторного входа понадобится код.", mod::logout);
		} else {
			mod.login();
		}
	}

	private void connectOrDisconnect() {
		if (mod.eventSub().isActive()) {
			mod.disconnect();
		} else {
			mod.connect(true);
		}
	}

	private void syncRewards() {
		if (!mod.tokens().hasTokens()) {
			Chat.warn("Сначала войди в Twitch.");
			return;
		}
		if (!mod.tokens().hasScope(RewardManager.SCOPE)) {
			Chat.warn("Нет права " + RewardManager.SCOPE + ": выйди и войди в Twitch заново.");
			return;
		}
		confirm("Создать награды на Twitch?", "На канале появятся награды из списка «Награды за баллы», которых там ещё нет. Существующие не меняются.",
				() -> mod.rewards().sync());
	}
}
