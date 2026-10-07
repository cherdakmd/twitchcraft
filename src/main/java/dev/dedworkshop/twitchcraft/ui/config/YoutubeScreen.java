package dev.dedworkshop.twitchcraft.ui.config;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.game.GameStats;
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

/**
 * YouTube Live: OAuth Desktop, статус live chat, зрители и квота Data API,
 * флаги событий, управление трансляцией и модерация чата.
 */
class YoutubeScreen extends BaseScreen {
	private static final String CLOUD_URL = "https://console.cloud.google.com/apis/credentials";
	private static final String QUOTA_URL = "https://console.cloud.google.com/apis/api/youtube.googleapis.com/quotas";
	private final ModConfig.Youtube draft = new ModConfig.Youtube();
	private String titleDraft = "";
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
		draft.pollMaxResults = current.pollMaxResults;
		draft.trackViewers = current.trackViewers;
		draft.viewersIntervalSeconds = current.viewersIntervalSeconds;
		draft.quotaBudget = current.quotaBudget;
		draft.quotaGuard = current.quotaGuard;
		draft.control = current.control;
		draft.showModeration = current.showModeration;
		draft.defaultTimeoutSeconds = current.defaultTimeoutSeconds;
		titleDraft = mod.youtube().broadcastTitle();
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

		section(content, "Эфир");
		content.addChild(Widgets.clipped(font, viewersLine(youtube), Widgets.FULL));
		content.addChild(Widgets.clipped(font, broadcastLine(youtube), Widgets.FULL));
		content.addChild(Widgets.clipped(font, Component.literal("§7Квота Data API: §f"
				+ youtube.quota().describe(draft.quotaBudget)).withStyle(ChatFormatting.GRAY), Widgets.FULL));
		GridLayout broadcast = Widgets.form();
		row(broadcast, 0, "Заголовок эфира", Widgets.textField(font, Widgets.FIELD, titleDraft,
				value -> titleDraft = value, youtube.broadcastTitle().isBlank() ? "Название трансляции" : youtube.broadcastTitle()));
		content.addChild(broadcast);
		GridLayout liveButtons = new GridLayout().columnSpacing(8).rowSpacing(4);
		GridLayout.RowHelper live = liveButtons.createRowHelper(2);
		live.addChild(Widgets.button("Сменить заголовок", Widgets.HALF, () -> {
			applyDraft();
			youtube.setTitle(titleDraft);
			refresh();
		}, "liveBroadcasts.update: ресурс отправляется целиком, недостающие обязательные поля заполняются значениями YouTube"));
		live.addChild(Widgets.button("Квоты Google Cloud", Widgets.HALF,
				() -> ConfirmLinkScreen.confirmLinkNow(this, URI.create(QUOTA_URL)), "Фактический расход и лимиты проекта"));
		live.addChild(Widgets.button("В эфир (live)", Widgets.HALF, () -> transition(youtube, "live"),
				"liveBroadcasts.transition → live: трансляция становится видна зрителям"));
		live.addChild(Widgets.button("Завершить эфир", Widgets.HALF, () -> transition(youtube, "complete"),
				"liveBroadcasts.transition → complete: YouTube останавливает трансляцию"));
		live.addChild(Widgets.button("Тест (testing)", Widgets.HALF, () -> transition(youtube, "testing"),
				"liveBroadcasts.transition → testing: приватная проверка, нужен включённый monitor stream"));
			live.addChild(Widgets.button("Обновить данные", Widgets.HALF, () -> {
				applyDraft();
				youtube.refreshStreamData();
				refresh();
			}, "Пересчитать зрителей, время и заголовок эфира (videos.list — 1 единица квоты)"));
		content.addChild(liveButtons);

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
				value -> draft.paidMessages = value, Widgets.FIELD, "Super Chat, Super Stickers и Fan Funding идут как донаты через общие пороги"));
		row(flags, row++, "Участия канала", Widgets.toggle("Обрабатывать", draft.memberships,
				value -> draft.memberships = value, Widgets.FIELD, "Новые/продлённые платные участия и агрегированные подарки участий"));
		row(flags, row++, "Автоответы зрителям", Widgets.toggle("Отправлять", draft.replies,
				value -> draft.replies = value, Widgets.FIELD, "Автоответы зрителям; игровые сообщения, таймеры и ссылки на клипы имеют отдельную маршрутизацию"));
		row(flags, row++, "Модерация в чате игры", Widgets.toggle("Показывать", draft.showModeration,
				value -> draft.showModeration = value, Widgets.FIELD, "Баны, тайм-ауты, удаления сообщений и пометки спама от модераторов YouTube"));
		row(flags, row++, "Диагностика API", Widgets.toggle("Подробно", draft.debugEvents,
				value -> draft.debugEvents = value, Widgets.FIELD, "Писать неизвестные типы сообщений, polling и квоту в logs/latest.log"));
		content.addChild(flags);

		section(content, "Опрос чата и квота");
		GridLayout quota = Widgets.form();
		row = 0;
		row(quota, row++, "Сообщений за запрос", Widgets.intField(font, Widgets.FIELD, draft.pollMaxResults, 200, 2000,
				value -> draft.pollMaxResults = value));
		row(quota, row++, "Дневной бюджет квоты", Widgets.intField(font, Widgets.FIELD, draft.quotaBudget, 0, 100_000,
				value -> draft.quotaBudget = value));
		row(quota, row++, "Следить за бюджетом", Widgets.toggle("Останавливать", draft.quotaGuard,
				value -> draft.quotaGuard = value, Widgets.FIELD, "Не жечь запросы, когда бюджет исчерпан: чат возобновится после сброса квоты Google"));
		row(quota, row++, "Зрители эфира", Widgets.toggle("Опрашивать", draft.trackViewers,
				value -> draft.trackViewers = value, Widgets.FIELD, "videos.list: {youtube_viewers}, {youtube_live_time}, {youtube_title} (1 единица квоты)"));
		row(quota, row++, "Интервал, секунд", Widgets.intField(font, Widgets.FIELD, draft.viewersIntervalSeconds, 15, 3600,
				value -> draft.viewersIntervalSeconds = value));
		content.addChild(quota);
		content.addChild(Widgets.gray(font, "Один опрос чата стоит ≈5 единиц квоты, запрос зрителей/эфира — ≈1, запись (сообщение, бан, смена заголовка) — ≈50."));
		content.addChild(Widgets.gray(font, "При сбое опроса задержка растёт: 2, 4, 8… до 60 с; поиск эфира без трансляции — 30 с … 5 мин."));

		section(content, "Управление эфиром и модерация");
		GridLayout control = Widgets.form();
		row = 0;
		row(control, row++, "Управление из игры", Widgets.toggle("Разрешить", draft.control,
				value -> draft.control = value, Widgets.FIELD, "go live/testing/complete, смена заголовка, бан, разбан и удаление сообщений"));
		row(control, row++, "Тайм-аут по умолчанию", Widgets.intField(font, Widgets.FIELD, draft.defaultTimeoutSeconds, 0, 604_800,
				value -> draft.defaultTimeoutSeconds = value));
		content.addChild(control);
		content.addChild(Widgets.gray(font, "Команды: /twitch youtube go live|testing|complete, title <текст>, ban <ник> [сек], unban <ник>, delete <ник>."));
		content.addChild(Widgets.gray(font, "Ник берётся из числа зрителей, которых мод видел в этом чате (" + youtube.participantsCount() + " в памяти); 0 секунд — постоянный бан."));
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
				+ youtube.isConnecting() + "|" + youtube.isLoginInProgress() + "|" + youtube.statusText() + "|"
				+ youtube.viewers() + "|" + youtube.quota().used() + "|" + youtube.participantsCount() + "|"
				+ youtube.broadcastTitle() + "|" + youtube.lastError() + "|" + last;
	}

	private Component viewersLine(YoutubeLive youtube) {
		if (youtube.viewers() < 0) {
			return Component.literal("§7Зрители: §8не опрашиваются").withStyle(ChatFormatting.GRAY);
		}
		String duration = youtube.streamStartedAt() > 0
				? GameStats.formatDuration(System.currentTimeMillis() - youtube.streamStartedAt()) : "";
		return Component.literal("§7Зрители: §f" + youtube.viewers()
				+ (duration.isEmpty() ? "" : "§7, эфир идёт " + duration)
				+ " §8(обновлено " + GameStats.formatDuration(System.currentTimeMillis() - youtube.viewersUpdatedAt()) + " назад)");
	}

	private Component broadcastLine(YoutubeLive youtube) {
		if (youtube.broadcastId().isEmpty()) {
			return Component.literal("§7Трансляция: §8не найдена").withStyle(ChatFormatting.GRAY);
		}
		return Component.literal("§7Трансляция: §f" + (youtube.broadcastTitle().isBlank() ? youtube.broadcastId() : youtube.broadcastTitle())
				+ " §8" + youtube.broadcastUrl());
	}

	private String loginLabel() {
		YoutubeLive youtube = mod.youtube();
		if (youtube.isLoginInProgress()) return "Отменить вход";
		return youtube.isLoggedIn() ? "Выйти из YouTube" : "Войти в YouTube Live";
	}

	private void transition(YoutubeLive youtube, String status) {
		applyDraft();
		youtube.transition(status, true);
		refresh();
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
		target.pollMaxResults = draft.pollMaxResults;
		target.trackViewers = draft.trackViewers;
		target.viewersIntervalSeconds = draft.viewersIntervalSeconds;
		target.quotaBudget = draft.quotaBudget;
		target.quotaGuard = draft.quotaGuard;
		target.control = draft.control;
		target.showModeration = draft.showModeration;
		target.defaultTimeoutSeconds = draft.defaultTimeoutSeconds;
		mod.config().normalize();
		draft.callbackPort = target.callbackPort;
		draft.pollMaxResults = target.pollMaxResults;
		draft.viewersIntervalSeconds = target.viewersIntervalSeconds;
		draft.quotaBudget = target.quotaBudget;
		draft.defaultTimeoutSeconds = target.defaultTimeoutSeconds;
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
