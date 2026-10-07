package dev.dedworkshop.twitchcraft.ui.config;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.util.Chat;
import dev.dedworkshop.twitchcraft.youtube.YoutubeLive;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.net.URI;

/** YouTube OAuth, live chat status, and per-platform chat/event flags. */
class YoutubeScreen extends BaseScreen {
	private static final String CLOUD_URL = "https://console.cloud.google.com/apis/credentials";
	private final ModConfig.Youtube draft = new ModConfig.Youtube();
	private String signature = "";
	private int ticks;

	YoutubeScreen(TwitchCraftClient mod, Screen parent) {
		super(mod, parent, "YouTube Live");
		ModConfig.Youtube current = mod.config().youtube;
		draft.clientId = current.clientId;
		draft.callbackPort = current.callbackPort;
		draft.showChat = current.showChat;
		draft.chatPrefix = current.chatPrefix;
		draft.chatCommands = current.chatCommands;
		draft.paidMessages = current.paidMessages;
		draft.memberships = current.memberships;
		draft.replies = current.replies;
		draft.debugEvents = current.debugEvents;
	}

	@Override
	protected void buildContent(LinearLayout content) {
		YoutubeLive youtube = mod.youtube();
		signature = signature();
		String last = Chat.lastText();
		if (!last.isEmpty() && System.currentTimeMillis() - Chat.lastTime() < 120_000) {
			content.addChild(Widgets.clipped(font, Component.literal("» " + last).withStyle(ChatFormatting.YELLOW), Widgets.FULL));
			content.addChild(SpacerElement.height(2));
		}

		content.addChild(Widgets.header(font, "Подключение" + (mod.isModuleEnabled(Module.YOUTUBE_LIVE) ? "" : " (модуль выключен)")));
		content.addChild(Widgets.clipped(font, Component.literal("Статус: ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(youtube.statusText())), Widgets.FULL));
		content.addChild(Widgets.gray(font, "HTTP polling YouTube Data API v3: чат, чат-команды, Super Chat/стикеры и платные членства"));

		section(content, "Приложение Google OAuth");
		GridLayout app = Widgets.form();
		int row = 0;
		row(app, row++, "Desktop Client ID", Widgets.textField(font, Widgets.FIELD, draft.clientId,
				value -> draft.clientId = value.trim(), "Client ID приложения типа Desktop"));
		row(app, row++, "Порт loopback", Widgets.intField(font, Widgets.FIELD, draft.callbackPort, 1024, 65535,
				value -> draft.callbackPort = value));
		content.addChild(app);
		content.addChild(Widgets.gray(font, "В Google Cloud: включи YouTube Data API v3, настрой OAuth consent screen и создай OAuth Client ID типа Desktop app."));
		content.addChild(Widgets.gray(font, "Callback URI: " + redirectUri() + "  (Desktop-приложению не нужен заранее зарегистрированный порт)"));
		GridLayout appButtons = new GridLayout().columnSpacing(8).rowSpacing(4);
		GridLayout.RowHelper buttons = appButtons.createRowHelper(2);
		buttons.addChild(Widgets.button("Google Cloud Credentials", Widgets.HALF,
				() -> ConfirmLinkScreen.confirmLinkNow(this, URI.create(CLOUD_URL)), "Создать Client ID типа Desktop app"));
		buttons.addChild(Widgets.button("Скопировать callback", Widgets.HALF, () -> {
			minecraft.keyboardHandler.setClipboard(redirectUri());
			Chat.info("Скопировано: " + redirectUri());
		}));
		buttons.addChild(Widgets.button(loginLabel(), Widgets.HALF, this::loginOrLogout,
				"OAuth Authorization Code + PKCE в браузере; токены сохраняются отдельно"));
		buttons.addChild(Widgets.button(youtube.isActive() || youtube.isConnecting() ? "Отключить YouTube" : "Подключить YouTube",
				Widgets.HALF, () -> {
					boolean disconnect = youtube.isActive() || youtube.isConnecting();
					if (disconnect) youtube.disconnect();
					applyDraft();
					if (!disconnect) youtube.connect(true);
					refresh();
				}, "Найти активную трансляцию и начать чтение live chat"));
		content.addChild(appButtons);

		section(content, "События YouTube Live");
		GridLayout flags = Widgets.form();
		row = 0;
		row(flags, row++, "Обычный чат в игре", Widgets.toggle("Показывать", draft.showChat,
				value -> draft.showChat = value, Widgets.FIELD, "Сообщения YouTube показываются с собственным префиксом"));
		row(flags, row++, "Префикс чата", Widgets.textField(font, Widgets.FIELD, draft.chatPrefix,
				value -> draft.chatPrefix = value, "&c[YT]&r "));
		row(flags, row++, "Чат-команды", Widgets.toggle("Выполнять", draft.chatCommands,
				value -> draft.chatCommands = value, Widgets.FIELD, "Использует общий список команд, но отдельный переключатель YouTube"));
		row(flags, row++, "Платные сообщения", Widgets.toggle("Обрабатывать", draft.paidMessages,
				value -> draft.paidMessages = value, Widgets.FIELD, "Super Chat и Super Stickers идут как донаты через общие пороги"));
		row(flags, row++, "Участия канала", Widgets.toggle("Обрабатывать", draft.memberships,
				value -> draft.memberships = value, Widgets.FIELD, "Новые/продлённые платные участия и агрегированные подарки участий"));
		row(flags, row++, "Автоответы зрителям", Widgets.toggle("Отправлять", draft.replies,
				value -> draft.replies = value, Widgets.FIELD, "Автоответы зрителям; игровые сообщения, таймеры и ссылки на клипы имеют отдельную маршрутизацию"));
		row(flags, row++, "Диагностика API", Widgets.toggle("Подробно", draft.debugEvents,
				value -> draft.debugEvents = value, Widgets.FIELD, "Писать неизвестные типы сообщений и polling в logs/latest.log"));
		content.addChild(flags);
		content.addChild(SpacerElement.height(4));
		content.addChild(Widgets.gray(font, "OAuth access/refresh tokens хранятся отдельно в config/twitchcraft-youtube.json — не показывай и не отправляй этот файл."));
		content.addChild(Widgets.gray(font, "Первый ответ API намеренно пропускается: старые сообщения и команды не запускаются повторно."));
		content.addChild(SpacerElement.height(6));
	}

	@Override
	protected void buildFooter(LinearLayout footer) {
		footer.addChild(Widgets.button("Сохранить", Widgets.HALF, this::save));
		footer.addChild(Widgets.button("Отмена", Widgets.HALF, this::onClose));
	}

	@Override
	public void tick() {
		super.tick();
		if (++ticks % 10 == 0 && !signature.equals(signature())) refresh();
	}

	private String signature() {
		YoutubeLive youtube = mod.youtube();
		String last = System.currentTimeMillis() - Chat.lastTime() < 120_000 ? Chat.lastText() : "";
		return youtube.isConfigured() + "|" + youtube.isLoggedIn() + "|" + youtube.isActive() + "|"
				+ youtube.isConnecting() + "|" + youtube.isLoginInProgress() + "|" + youtube.statusText() + "|" + last;
	}

	private String loginLabel() {
		YoutubeLive youtube = mod.youtube();
		if (youtube.isLoginInProgress()) return "Отменить вход";
		return youtube.isLoggedIn() ? "Выйти из YouTube" : "Войти в YouTube Live";
	}

	private void loginOrLogout() {
		YoutubeLive youtube = mod.youtube();
		if (youtube.isLoginInProgress()) {
			youtube.cancelLogin();
			refresh();
			return;
		}
		if (youtube.isLoggedIn()) {
			confirm("Выйти из YouTube Live?", "Подключение закроется, OAuth-токены будут удалены. Client ID останется.", () -> {
				youtube.logout();
				refresh();
			});
			return;
		}
		applyDraft();
		String url = youtube.beginLogin();
		if (url != null) {
			ConfirmLinkScreen.confirmLinkNow(this, URI.create(url));
			refresh();
		}
	}

	private String redirectUri() {
		return "http://localhost:" + draft.callbackPort;
	}

	private void applyDraft() {
		ModConfig.Youtube target = mod.config().youtube;
		target.clientId = draft.clientId;
		target.callbackPort = draft.callbackPort;
		target.showChat = draft.showChat;
		target.chatPrefix = draft.chatPrefix;
		target.chatCommands = draft.chatCommands;
		target.paidMessages = draft.paidMessages;
		target.memberships = draft.memberships;
		target.replies = draft.replies;
		target.debugEvents = draft.debugEvents;
		mod.config().normalize();
		draft.callbackPort = target.callbackPort;
		mod.configEdited();
	}

	private void save() {
		applyDraft();
		Chat.success("Настройки YouTube Live сохранены.");
		onClose();
	}

	private void section(LinearLayout content, String title) {
		content.addChild(SpacerElement.height(4));
		content.addChild(Widgets.header(font, title));
	}

	private void row(GridLayout grid, int row, String label, LayoutElement field) {
		grid.addChild(Widgets.label(font, label), row, 0, settings -> settings.alignVerticallyMiddle().alignHorizontallyLeft());
		grid.addChild(field, row, 1);
	}
}
