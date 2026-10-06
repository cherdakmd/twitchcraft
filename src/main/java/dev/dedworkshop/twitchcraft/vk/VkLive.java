package dev.dedworkshop.twitchcraft.vk;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.donations.LocalCallbackServer;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.twitch.ChatSender;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import dev.dedworkshop.twitchcraft.util.Chat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Интеграция с VK Video Live (live.vkvideo.ru): вход через OAuth, подписка на каналы WebSocket
 * (чат, баллы канала, журнал действий), отправка ответов в чат, подтверждение/отклонение запросов наград
 * и создание наград из конфига. События превращаются в {@link TwitchEvent} с платформой vk и идут
 * в общий обработчик — те же действия, кулдауны, цели и сборы, что и для Twitch.
 */
public class VkLive implements VkPubSub.Handler {
	public static final String CALLBACK_PATH = "/vk";
	private static final long LOGIN_TIMEOUT_MINUTES = 10;
	private static final int SEEN_LIMIT = 500;
	private static final long SEND_INTERVAL_MS = 1500;
	private static final long CATCH_UP_WINDOW_MS = 10 * 60_000;
	/** Каналы WebSocket, на которые подписываемся (имена полей web_socket_channels ответа /v1/channel). */
	private static final List<String> WANTED_CHANNELS = List.of("chat", "channel_points", "info", "private_channel_points", "private_info");

	private final TwitchCraftClient mod;
	private final VkApi api;
	private final Set<String> seenDemands = new LinkedHashSet<>();
	private final Set<String> approvedDemands = ConcurrentHashMap.newKeySet();
	private final Set<String> seenFollows = ConcurrentHashMap.newKeySet();
	private final Map<String, Integer> unknownTypes = new ConcurrentHashMap<>();
	private final Deque<String> recentlySent = new ArrayDeque<>();
	private final Object sendLock = new Object();
	private long nextSendAt;

	private volatile VkPubSub pubsub;
	private volatile boolean wantConnected;
	private volatile boolean manuallyDisconnected;
	private volatile boolean tokenRejected;
	private volatile String statusDetail = "";
	private volatile int reconnectAttempts;
	private volatile boolean announcedConnected;
	private volatile long disconnectedAt;
	private volatile int eventsReceived;
	private volatile int chatReceived;
	private volatile long sessionStartedAt;

	/** Канал, к которому подключены. */
	private volatile String slug = "";
	private volatile String channelId = "";
	private volatile String channelOwnerNick = "";
	private volatile String streamId = "";
	private volatile Boolean online;
	/** Полные имена каналов WebSocket: chat → "public-chat:123" и т.п. */
	private volatile Map<String, String> wsChannels = new LinkedHashMap<>();
	private volatile Map<String, String> subscriptionTokens = new LinkedHashMap<>();
	private final Set<String> subscribed = ConcurrentHashMap.newKeySet();
	private final Set<String> failedChannels = ConcurrentHashMap.newKeySet();
	private volatile boolean warnedDemandScope;
	private volatile boolean warnedChatScope;
	private volatile boolean warnedNoStream;

	private volatile LocalCallbackServer loginServer;
	private volatile String loginState;
	private ScheduledFuture<?> loginTimeout;
	private ScheduledFuture<?> reconnectTask;
	private ScheduledFuture<?> watchdog;

	public VkLive(TwitchCraftClient mod) {
		this.mod = mod;
		this.api = new VkApi(mod);
	}

	public VkApi api() {
		return api;
	}

	// ---------- Состояние ----------

	/** Указаны ID и секрет приложения. */
	public boolean isConfigured() {
		ModConfig.Vk settings = mod.config().vk;
		return settings != null && settings.clientId != null && !settings.clientId.isBlank() && mod.vkStore().hasSecret();
	}

	public boolean isLoggedIn() {
		return mod.vkStore().hasTokens();
	}

	public boolean isActive() {
		VkPubSub p = pubsub;
		return wantConnected && p != null && p.isConnected() && !subscribed.isEmpty();
	}

	public boolean isConnecting() {
		return wantConnected && !isActive();
	}

	public boolean isLoginInProgress() {
		LocalCallbackServer server = loginServer;
		return server != null && server.isRunning();
	}

	public String channelSlug() {
		return slug;
	}

	public String streamId() {
		return streamId;
	}

	public Boolean online() {
		return online;
	}

	public int eventsReceived() {
		return eventsReceived;
	}

	public int chatReceived() {
		return chatReceived;
	}

	public Set<String> subscribedChannels() {
		return Set.copyOf(subscribed);
	}

	public String statusText() {
		VkStore store = mod.vkStore();
		if (!isConfigured()) {
			return "§7не настроено §8(нужны ID и секрет приложения: /twitch vk)";
		}
		if (isLoginInProgress()) {
			return "§eожидаю вход в браузере...";
		}
		if (!store.hasTokens()) {
			return "§7вход не выполнен §8(/twitch vk login)";
		}
		String who = store.userNick.isBlank() ? "" : " §7как §d" + store.userNick + "§7";
		if (tokenRejected) {
			return "§cтокен отклонён — войди заново: /twitch vk login";
		}
		if (!wantConnected) {
			return "§7отключено" + who;
		}
		if (isActive()) {
			String marks = " §8[" + mark("chat", "чат") + " " + mark("channel_points", "награды") + " " + mark("private_info", "журнал") + "]";
			return "§aподключено§r §7к §f" + slug + who + marks
					+ (online == null ? "" : online ? " §aэфир" : " §8нет эфира")
					+ (eventsReceived > 0 ? " §7событий: " + eventsReceived : "");
		}
		return "§eподключение..." + (statusDetail.isBlank() ? "" : " §7(" + statusDetail + ")") + who;
	}

	private String mark(String key, String title) {
		String full = wsChannels.get(key);
		if (full == null) {
			return "§8" + title + " —";
		}
		return (subscribed.contains(full) ? "§a" : failedChannels.contains(full) ? "§c" : "§7") + title + (subscribed.contains(full) ? " ●" : " ○");
	}

	public String overlayMark() {
		if (!isLoggedIn() || !mod.isModuleEnabled(Module.VK_VIDEO_LIVE)) {
			return "";
		}
		return "VK" + (isActive() ? "●" : wantConnected ? "◌" : "○");
	}

	// ---------- Вход ----------

	public String redirectUri() {
		return "http://localhost:" + mod.config().vk.callbackPort + CALLBACK_PATH;
	}

	public String loginUrl() {
		String clientId = mod.config().vk.clientId;
		if (clientId == null || clientId.isBlank()) {
			return "";
		}
		return VkApi.authorizeUrl(clientId.trim(), redirectUri(), loginState);
	}

	/** Сохраняет ID и секрет приложения. */
	public void setApp(String clientId, String secret) {
		mod.config().vk.clientId = clientId == null ? "" : clientId.trim();
		mod.config().save();
		VkStore store = mod.vkStore();
		store.clientSecret = secret == null ? "" : secret.trim();
		store.save();
		tokenRejected = false;
	}

	/**
	 * Начинает вход: поднимает локальный сервер для приёма кода и возвращает ссылку, которую нужно открыть.
	 *
	 * @return ссылка или null, если вход начать нельзя (ошибка уже показана)
	 */
	public String beginLogin() {
		if (!isConfigured()) {
			Chat.error("Сначала укажи приложение VK Video Live: создай его на dev.live.vkvideo.ru/apps (Redirect URI: " + redirectUri()
					+ ") и введи §e/twitch vk app <ID> <секрет>");
			return null;
		}
		cancelLogin();
		loginState = LocalCallbackServer.newState();
		String url = loginUrl();
		LocalCallbackServer server = new LocalCallbackServer(mod.config().vk.callbackPort, CALLBACK_PATH, loginState,
				"VK Video Live", "/twitch vk login", this::onLoginCallback);
		try {
			server.start();
		} catch (Exception e) {
			Chat.error("Не удалось открыть локальный порт " + mod.config().vk.callbackPort + " для входа: " + e.getMessage()
					+ ". Поменяй порт в настройках VK (callbackPort) и Redirect URI приложения.");
			return null;
		}
		loginServer = server;
		loginTimeout = mod.scheduler().schedule(() -> {
			if (loginServer == server && server.isRunning()) {
				server.close();
				Chat.warn("Вход в VK Video Live отменён: за " + LOGIN_TIMEOUT_MINUTES + " минут код не получен. "
						+ "Если браузер не смог открыть localhost — скопируй code из адресной строки и введи /twitch vk code <code>");
			}
		}, LOGIN_TIMEOUT_MINUTES, TimeUnit.MINUTES);
		return url;
	}

	public void cancelLogin() {
		LocalCallbackServer server = loginServer;
		loginServer = null;
		if (server != null) {
			server.close();
		}
		if (loginTimeout != null) {
			loginTimeout.cancel(false);
			loginTimeout = null;
		}
	}

	private void onLoginCallback(Map<String, String> params) {
		// Освобождаем порт до возврата из обработчика: иначе повторный вход сразу после ошибки
		// (или ручной ввод кода) может получить «Address already in use».
		LocalCallbackServer server = loginServer;
		loginServer = null;
		ScheduledFuture<?> timeout = loginTimeout;
		loginTimeout = null;
		if (timeout != null) {
			timeout.cancel(false);
		}
		if (server != null) {
			server.close();
		}
		finishLogin(params.get("code"));
	}

	/**
	 * Завершает вход кодом, который пользователь вставил вручную (сам код или вся ссылка localhost:…/vk?code=…).
	 */
	public void finishLoginWithCode(String codeOrUrl) {
		if (codeOrUrl == null || codeOrUrl.isBlank()) {
			Chat.warn("Укажи код: /twitch vk code <code>");
			return;
		}
		String code = codeOrUrl.trim();
		int at = code.indexOf("code=");
		if (at >= 0) {
			code = code.substring(at + 5);
			int amp = code.indexOf('&');
			if (amp >= 0) {
				code = code.substring(0, amp);
			}
		}
		cancelLogin();
		finishLogin(code);
	}

	private void finishLogin(String code) {
		if (code == null || code.isBlank()) {
			Chat.error("VK Video Live не вернул код авторизации. Попробуй ещё раз: /twitch vk login");
			return;
		}
		String redirect = redirectUri();
		mod.worker().execute(() -> {
			try {
				JsonObject json = api.exchangeCode(code, redirect);
				String access = VkApi.str(json, "access_token");
				if (access.isEmpty()) {
					Chat.error("VK Video Live не выдал токен: " + json);
					return;
				}
				long expiresIn = json.has("expires_in") && json.get("expires_in").isJsonPrimitive() ? json.get("expires_in").getAsLong() : 0;
				VkStore store = mod.vkStore();
				store.setTokens(access, VkApi.str(json, "refresh_token"), expiresIn, VkApi.str(json, "scope"));
				tokenRejected = false;
				JsonObject user = api.currentUser(redirect);
				rememberProfile(user);
				if (store.scopes.isBlank()) {
					store.scopes = VkApi.SCOPES; // сервер не сообщил права — считаем, что выданы запрошенные
					store.save();
				}
				Chat.success("VK Video Live: вход выполнен" + (store.userNick.isBlank() ? "" : " как §d" + store.userNick + "§r")
						+ (store.ownChannelUrl.isBlank() ? "" : " §7(канал " + store.ownChannelUrl + ")") + ". Подключаюсь...");
				manuallyDisconnected = false;
				connect(true);
			} catch (Exception e) {
				TwitchCraftClient.LOGGER.error("VK: ошибка завершения входа", e);
				Chat.error("VK Video Live: ошибка входа: " + e.getMessage()
						+ (e.getMessage() != null && e.getMessage().contains("401") ? " — проверь ID и секрет приложения (/twitch vk app)" : ""));
			}
		});
	}

	private void rememberProfile(JsonObject data) {
		VkStore store = mod.vkStore();
		JsonObject user = VkEvents.firstObj(data, "user");
		if (user != null) {
			store.userId = VkEvents.firstStr(user, "id");
			store.userNick = VkEvents.firstStr(user, "nick", "displayName", "name");
		}
		JsonObject channel = VkEvents.firstObj(data, "channel");
		if (channel != null) {
			store.ownChannelUrl = ModConfig.Vk.slugOf(VkEvents.firstStr(channel, "url"));
		}
		store.save();
		ModConfig.Vk settings = mod.config().vk;
		if ((settings.channelUrl == null || settings.channelUrl.isBlank()) && !store.ownChannelUrl.isBlank()) {
			settings.channelUrl = store.ownChannelUrl;
			mod.config().save();
		}
	}

	public void logout() {
		cancelLogin();
		disconnect();
		VkStore store = mod.vkStore();
		String token = store.accessToken;
		store.clearTokens();
		tokenRejected = false;
		mod.worker().execute(() -> api.revoke(token));
		Chat.info("VK Video Live: выход выполнен, токены удалены (секрет приложения сохранён).");
	}

	// ---------- Подключение ----------

	public synchronized void connect(boolean verbose) {
		if (!mod.config().isEnabled(Module.VK_VIDEO_LIVE)) {
			if (verbose) {
				Chat.warn("Модуль «VK Video Live» выключен: /twitch module vkVideoLive on");
			}
			return;
		}
		if (!mod.vkStore().hasTokens()) {
			if (verbose) {
				Chat.warn("VK Video Live: сначала войди — /twitch vk login");
			}
			return;
		}
		wantConnected = true;
		manuallyDisconnected = false;
		tokenRejected = false;
		closePubSub();
		subscribed.clear();
		failedChannels.clear();
		announcedConnected = false;
		disconnectedAt = 0;
		reconnectAttempts = 0;
		statusDetail = "запрашиваю канал";
		startWatchdog();
		mod.worker().execute(() -> openSession(verbose));
	}

	public synchronized void disconnect() {
		wantConnected = false;
		manuallyDisconnected = true;
		cancelReconnect();
		stopWatchdog();
		closePubSub();
		subscribed.clear();
	}

	/** Автоподключение при входе в мир (если есть токены, модуль включён и игрок сам не отключался). */
	public void autoConnect() {
		if (!mod.config().isEnabled(Module.VK_VIDEO_LIVE) || !mod.vkStore().hasTokens() || manuallyDisconnected || wantConnected) {
			return;
		}
		connect(false);
	}

	public void onModuleChanged(boolean enabled) {
		if (enabled) {
			manuallyDisconnected = false;
			if (mod.vkStore().hasTokens()) {
				connect(true);
			}
		} else {
			wantConnected = false;
			cancelReconnect();
			stopWatchdog();
			closePubSub();
			subscribed.clear();
		}
	}

	/** Конфиг сохранён из настроек: если сменился канал — переподключаемся. */
	public void syncWithConfig() {
		if (!wantConnected) {
			return;
		}
		String wanted = targetSlug();
		if (!wanted.isEmpty() && !wanted.equals(slug)) {
			Chat.info("§7VK: канал изменён на " + wanted + " — переподключаюсь...");
			connect(false);
		}
	}

	/** Канал, к которому нужно подключаться: из конфига, иначе — свой канал владельца токена. */
	public String targetSlug() {
		String configured = mod.config().vk == null ? "" : mod.config().vk.slug();
		return configured.isEmpty() ? mod.vkStore().ownChannelUrl : configured;
	}

	public void shutdown() {
		wantConnected = false;
		cancelLogin();
		cancelReconnect();
		stopWatchdog();
		closePubSub();
	}

	private void openSession(boolean verbose) {
		if (!wantConnected) {
			return;
		}
		String redirect = redirectUri();
		try {
			api.ensureFreshToken(redirect);
			VkStore store = mod.vkStore();
			if (store.userId.isBlank() || store.ownChannelUrl.isBlank()) {
				rememberProfile(api.currentUser(redirect));
			}
			String target = targetSlug();
			if (target.isEmpty()) {
				statusDetail = "не указан канал";
				wantConnected = false;
				Chat.error("VK Video Live: не удалось определить канал. Укажи его: /twitch vk channel <ссылка или имя>");
				return;
			}
			JsonObject data = api.channel(target, redirect);
			JsonObject channel = VkEvents.firstObj(data, "channel");
			if (channel == null) {
				throw new IllegalStateException("ответ /channel без поля channel: " + data);
			}
			slug = target;
			channelId = VkEvents.firstStr(channel, "id");
			channelOwnerNick = VkEvents.firstStr(channel, "nick");
			JsonObject owner = VkEvents.firstObj(data, "owner");
			if (owner != null && channelOwnerNick.isEmpty()) {
				channelOwnerNick = VkEvents.firstStr(owner, "nick");
			}
			JsonObject stream = VkEvents.firstObj(data, "stream");
			if (stream != null) {
				String id = VkEvents.firstStr(stream, "id");
				if (!id.isEmpty()) {
					streamId = id;
				}
				String status = VkEvents.firstStr(stream, "status").toLowerCase(Locale.ROOT);
				if (!status.isEmpty()) {
					online = status.contains("online") || status.contains("live") || status.contains("started");
				}
			}
			Map<String, String> channels = new LinkedHashMap<>();
			JsonObject ws = VkEvents.firstObj(channel, "web_socket_channels", "webSocketChannels");
			if (ws != null) {
				for (String key : WANTED_CHANNELS) {
					String full = VkEvents.firstStr(ws, key);
					if (!full.isEmpty()) {
						channels.put(key, full);
					}
				}
			}
			if (channels.isEmpty()) {
				throw new IllegalStateException("у канала нет web_socket_channels (ответ: " + channel + ")");
			}
			wsChannels = channels;
			if (!store.ownChannelUrl.isBlank() && !store.ownChannelUrl.equalsIgnoreCase(slug)) {
				TwitchCraftClient.LOGGER.warn("VK: подключаюсь к чужому каналу {} (свой — {}): награды и ответы в чат могут быть недоступны", slug, store.ownChannelUrl);
			}

			statusDetail = "получаю токены";
			String socketToken = api.websocketToken(redirect);
			if (socketToken.isEmpty()) {
				throw new IllegalStateException("пустой токен WebSocket");
			}
			Map<String, String> tokens = new LinkedHashMap<>();
			try {
				tokens = api.subscriptionTokens(new ArrayList<>(channels.values()), redirect);
			} catch (Exception e) {
				TwitchCraftClient.LOGGER.warn("VK: токены подписки не выданы ({}) — приватные каналы (журнал, награды) могут быть недоступны", e.getMessage());
			}
			subscriptionTokens = tokens;
			if (!wantConnected) {
				return;
			}
			statusDetail = "открываю WebSocket";
			sessionStartedAt = System.currentTimeMillis();
			VkPubSub p = new VkPubSub(VkApi.WS, this, mod.scheduler());
			pubsub = p;
			p.connect(socketToken);
			if (verbose) {
				Chat.info("VK Video Live: подключаюсь к каналу " + slug + "...");
			}
		} catch (VkApi.ApiException e) {
			if (e.unauthorized()) {
				tokenRejected = true;
				wantConnected = false;
				statusDetail = "токен отклонён";
				Chat.error("VK Video Live: токен недействителен (" + e.getMessage() + "). Войди заново: /twitch vk login");
				return;
			}
			TwitchCraftClient.LOGGER.warn("VK: ошибка подключения: {}", e.getMessage());
			statusDetail = e.getMessage();
			if (e.status == 404 || e.code.contains("not_found")) {
				wantConnected = false;
				Chat.error("VK Video Live: канал «" + targetSlug() + "» не найден (" + e.getMessage() + "). Проверь: /twitch vk channel <ссылка>");
				return;
			}
			scheduleReconnect("ошибка API: " + e.getMessage());
		} catch (Exception e) {
			TwitchCraftClient.LOGGER.warn("VK: ошибка подключения", e);
			statusDetail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			scheduleReconnect("ошибка: " + statusDetail);
		}
	}

	private void closePubSub() {
		VkPubSub p = pubsub;
		pubsub = null;
		if (p != null) {
			p.close();
		}
	}

	private synchronized void scheduleReconnect(String why) {
		if (!wantConnected || tokenRejected) {
			return;
		}
		cancelReconnect();
		int attempt = ++reconnectAttempts;
		long delay = Math.min(60, (long) Math.pow(2, Math.min(attempt, 6)));
		statusDetail = "переподключение через " + delay + " с — " + why;
		TwitchCraftClient.LOGGER.info("VK: {} — повтор через {} с", why, delay);
		reconnectTask = mod.scheduler().schedule(() -> {
			if (wantConnected) {
				closePubSub();
				subscribed.clear();
				mod.worker().execute(() -> openSession(false));
			}
		}, delay, TimeUnit.SECONDS);
	}

	private void cancelReconnect() {
		if (reconnectTask != null) {
			reconnectTask.cancel(false);
			reconnectTask = null;
		}
	}

	private void startWatchdog() {
		stopWatchdog();
		watchdog = mod.scheduler().scheduleAtFixedRate(() -> {
			VkPubSub p = pubsub;
			if (wantConnected && p != null && p.isConnected() && System.currentTimeMillis() - p.lastMessageAt() > 120_000) {
				TwitchCraftClient.LOGGER.warn("VK: сервер молчит больше 2 минут — переподключаюсь");
				subscribed.clear();
				scheduleReconnect("нет ответа от сервера");
			}
		}, 60, 30, TimeUnit.SECONDS);
	}

	private void stopWatchdog() {
		if (watchdog != null) {
			watchdog.cancel(false);
			watchdog = null;
		}
	}

	private void noteDisconnected() {
		if (disconnectedAt == 0) {
			disconnectedAt = System.currentTimeMillis();
		}
	}

	// ---------- VkPubSub.Handler ----------

	@Override
	public void onConnected(String clientId) {
		statusDetail = "подписываюсь";
		VkPubSub p = pubsub;
		if (p == null) {
			return;
		}
		for (Map.Entry<String, String> entry : wsChannels.entrySet()) {
			String full = entry.getValue();
			p.subscribe(full, subscriptionTokens.get(full));
		}
	}

	@Override
	public void onSubscribed(String channel) {
		subscribed.add(channel);
		failedChannels.remove(channel);
		String chat = wsChannels.get("chat");
		if (chat == null || chat.equals(channel)) {
			reconnectAttempts = 0;
			statusDetail = "";
			long gapMs = disconnectedAt == 0 ? 0 : System.currentTimeMillis() - disconnectedAt;
			disconnectedAt = 0;
			if (!announcedConnected) {
				announcedConnected = true;
				Chat.success("VK Video Live: §aподключено§r к каналу §f" + slug + "§r — чат, награды и фолловы идут в игру.");
			} else if (gapMs > 5000) {
				Chat.info("VK Video Live: соединение восстановлено (перерыв " + gapMs / 1000 + " с).");
			}
			// после каждого (пере)подключения забираем запросы наград, которые могли прийти, пока сокета не было
			mod.worker().execute(this::catchUpDemands);
		}
	}

	@Override
	public void onPublication(String channel, JsonObject data) {
		eventsReceived++;
		if (mod.config().vk.debugEvents) {
			TwitchCraftClient.LOGGER.info("VK событие [{}]: {}", channel, truncate(data.toString(), 1500));
		}
		VkEvents.Parsed parsed;
		try {
			parsed = VkEvents.parse(data);
		} catch (Exception e) {
			TwitchCraftClient.LOGGER.warn("VK: не разобрано событие {}: {}", truncate(data.toString(), 500), e.toString());
			return;
		}
		if (parsed.streamId() != null && !parsed.streamId().isEmpty()) {
			streamId = parsed.streamId();
		}
		if (parsed.online() != null) {
			online = parsed.online();
			if (!parsed.online()) {
				streamId = "";
			}
		}
		TwitchEvent event = parsed.event();
		if (event == null) {
			if (!parsed.type().isEmpty() && !isServiceType(parsed.type())) {
				int n = unknownTypes.merge(parsed.type(), 1, Integer::sum);
				if (n <= 3) {
					TwitchCraftClient.LOGGER.info("VK: событие без обработчика «{}» (канал {}): {}", parsed.type(), channel, truncate(data.toString(), 400));
				}
			}
			return;
		}
		ModConfig.Vk settings = mod.config().vk;
		switch (event.type()) {
			case CHAT -> {
				chatReceived++;
				mod.onTwitchEvent(event);
			}
			case REWARD -> {
				if (!settings.rewards) {
					return;
				}
				String status = parsed.demandStatus();
				if (status.contains("reject") || status.contains("cancel") || status.contains("declin")) {
					return;
				}
				if (!markDemandSeen(event.redemptionId(), event)) {
					return; // дубль: тот же запрос пришёл по второму каналу (channel_points и private_info)
				}
				if (status.contains("approv") || status.contains("accept") || status.contains("fulfil")) {
					approvedDemands.add(event.redemptionId()); // уже подтверждён на стороне VK (авто-подтверждение) — accept не нужен
				}
				mod.onTwitchEvent(event);
			}
			case FOLLOW -> {
				if (!settings.follows) {
					return;
				}
				String key = event.userId().isEmpty() ? event.userLogin() : event.userId();
				if (!key.isEmpty() && !seenFollows.add(key)) {
					return;
				}
				mod.onTwitchEvent(event);
			}
			default -> mod.onTwitchEvent(event);
		}
	}

	private static boolean isServiceType(String type) {
		return type.startsWith("stream_") || type.contains("viewers") || type.contains("like") || type.contains("counter")
				|| type.contains("deleted") || type.contains("clear") || type.contains("pin") || type.contains("settings")
				|| type.contains("typing") || type.contains("online_status");
	}

	private boolean markDemandSeen(String demandId, TwitchEvent event) {
		if (demandId == null || demandId.isEmpty()) {
			return true; // без id не можем отличить дубли — обрабатываем
		}
		synchronized (seenDemands) {
			if (!seenDemands.add(demandId)) {
				return false;
			}
			while (seenDemands.size() > SEEN_LIMIT) {
				String oldest = seenDemands.iterator().next();
				seenDemands.remove(oldest);
				approvedDemands.remove(oldest);
			}
		}
		return true;
	}

	@Override
	public void onCommandError(int id, String channel, int code, String message) {
		if (id == 1) {
			subscribed.clear();
			noteDisconnected();
			scheduleReconnect("сокет отклонил токен (" + code + " " + message + ")");
			return;
		}
		if (channel != null && !channel.isEmpty()) {
			failedChannels.add(channel);
			String key = keyOf(channel);
			TwitchCraftClient.LOGGER.warn("VK: подписка на канал {} ({}) отклонена: {} {}", key, channel, code, message);
			if (key.equals("chat")) {
				noteDisconnected();
				scheduleReconnect("чат недоступен (" + code + " " + message + ")");
			} else if (key.startsWith("private") && (code == 103 || code == 102)) {
				TwitchCraftClient.LOGGER.info("VK: канал {} приватный — нужен токен подписки; без него журнал/награды придут только по публичным каналам", key);
			}
			return;
		}
		TwitchCraftClient.LOGGER.warn("VK: Centrifugo ответил ошибкой на команду {}: {} {}", id, code, message);
	}

	private String keyOf(String fullChannel) {
		for (Map.Entry<String, String> entry : wsChannels.entrySet()) {
			if (entry.getValue().equals(fullChannel)) {
				return entry.getKey();
			}
		}
		return fullChannel;
	}

	@Override
	public void onClosed(String reason) {
		subscribed.clear();
		noteDisconnected();
		scheduleReconnect(reason);
	}

	@Override
	public void onRefreshNeeded() {
		mod.worker().execute(() -> {
			try {
				String token = api.websocketToken(redirectUri());
				VkPubSub p = pubsub;
				if (p != null && !token.isEmpty()) {
					p.refresh(token);
				}
			} catch (Exception e) {
				TwitchCraftClient.LOGGER.warn("VK: не удалось продлить токен WebSocket: {}", e.getMessage());
			}
		});
	}

	@Override
	public void onUnsubscribed(String channel, int code, String reason) {
		subscribed.remove(channel);
		TwitchCraftClient.LOGGER.info("VK: сервер снял подписку с {} ({} {}) — переподписываюсь", keyOf(channel), code, reason);
		String key = keyOf(channel);
		if (key.equals("chat")) {
			noteDisconnected();
			scheduleReconnect("подписка на чат снята");
			return;
		}
		mod.worker().execute(() -> {
			try {
				Map<String, String> tokens = api.subscriptionTokens(List.of(channel), redirectUri());
				subscriptionTokens.putAll(tokens);
			} catch (Exception e) {
				TwitchCraftClient.LOGGER.debug("VK: токен подписки для {} не получен: {}", channel, e.toString());
			}
			VkPubSub p = pubsub;
			if (p != null && p.isConnected()) {
				p.subscribe(channel, subscriptionTokens.get(channel));
			}
		});
	}

	// ---------- Догонка запросов наград ----------

	/** После подключения забираем свежие (до 10 минут) необработанные запросы наград — на случай, если мод был выключен. */
	private void catchUpDemands() {
		ModConfig.Vk settings = mod.config().vk;
		if (!settings.rewards || !mod.vkStore().hasScope(VkApi.SCOPE_DEMANDS)) {
			return;
		}
		String redirect = redirectUri();
		try {
			Map<String, String> titles = new HashMap<>();
			Map<String, Integer> prices = new HashMap<>();
			try {
				for (JsonElement element : api.rewards(slug, redirect)) {
					if (element.isJsonObject()) {
						JsonObject reward = element.getAsJsonObject();
						String id = VkEvents.firstStr(reward, "id");
						titles.put(id, VkEvents.firstStr(reward, "name", "title"));
						prices.put(id, VkEvents.integer(reward, "price", "cost"));
					}
				}
			} catch (Exception e) {
				TwitchCraftClient.LOGGER.debug("VK: список наград не получен: {}", e.toString());
			}
			JsonArray demands = api.demands(slug, 50, 0, redirect);
			long since = System.currentTimeMillis() - CATCH_UP_WINDOW_MS;
			List<TwitchEvent> pending = new ArrayList<>();
			for (JsonElement element : demands) {
				if (!element.isJsonObject()) {
					continue;
				}
				JsonObject demand = element.getAsJsonObject();
				String status = VkEvents.firstStr(demand, "status").toLowerCase(Locale.ROOT);
				if (!status.isEmpty() && !status.contains("pending") && !status.contains("new") && !status.contains("wait")) {
					continue;
				}
				long createdAt = VkEvents.integer(demand, "created_at", "createdAt");
				if (createdAt > 0 && createdAt < 100_000_000_000L) {
					createdAt *= 1000; // unix-секунды → мс
				}
				if (createdAt > 0 && createdAt < since) {
					continue;
				}
				JsonObject reward = VkEvents.firstObj(demand, "reward");
				String rewardId = reward == null ? "" : VkEvents.firstStr(reward, "id");
				VkEvents.Parsed parsed = VkEvents.restDemand(demand, titles.getOrDefault(rewardId, ""), prices.getOrDefault(rewardId, 0));
				if (parsed.event() == null || parsed.event().reward().isEmpty()) {
					continue;
				}
				if (markDemandSeen(parsed.event().redemptionId(), parsed.event())) {
					pending.add(parsed.event());
				}
			}
			if (!pending.isEmpty()) {
				TwitchCraftClient.LOGGER.info("VK: догоняю {} запрос(ов) наград, пропущенных за время отключения", pending.size());
				for (TwitchEvent event : pending) {
					mod.onTwitchEvent(event);
				}
			}
		} catch (VkApi.ApiException e) {
			if (e.forbidden()) {
				warnDemandScope();
			} else {
				TwitchCraftClient.LOGGER.debug("VK: догонка запросов наград не удалась: {}", e.getMessage());
			}
		} catch (Exception e) {
			TwitchCraftClient.LOGGER.debug("VK: догонка запросов наград не удалась: {}", e.toString());
		}
	}

	// ---------- Запросы наград: подтверждение и отклонение ----------

	public void acceptDemand(TwitchEvent event) {
		resolveDemand(event, true, "");
	}

	public void rejectDemand(TwitchEvent event, String reason) {
		resolveDemand(event, false, reason);
	}

	private void resolveDemand(TwitchEvent event, boolean accept, String reason) {
		if (event == null || event.synthetic() || event.type() != TwitchEvent.Type.REWARD || !event.isVk()) {
			return;
		}
		if (!mod.config().vk.manageDemands || event.redemptionId().isBlank()) {
			return;
		}
		if (approvedDemands.contains(event.redemptionId())) {
			return; // VK уже подтвердил сам (авто-подтверждение награды) — менять статус нельзя
		}
		long id;
		try {
			id = Long.parseLong(event.redemptionId());
		} catch (NumberFormatException e) {
			return;
		}
		String channel = slug;
		mod.worker().execute(() -> {
			try {
				api.resolveDemands(channel, List.of(id), accept, redirectUri());
				if (!accept) {
					TwitchCraftClient.LOGGER.info("VK: запрос «{}» от {} отклонён, баллы вернутся: {}", event.reward(), event.user(), reason);
				}
			} catch (VkApi.ApiException e) {
				if (e.forbidden()) {
					warnDemandScope();
				} else {
					TwitchCraftClient.LOGGER.warn("VK: не удалось {} запрос награды {}: {}", accept ? "подтвердить" : "отклонить", id, e.getMessage());
				}
			} catch (Exception e) {
				TwitchCraftClient.LOGGER.warn("VK: ошибка изменения статуса запроса награды: {}", e.toString());
			}
		});
	}

	private void warnDemandScope() {
		if (!warnedDemandScope) {
			warnedDemandScope = true;
			Chat.warn("VK Video Live: нет права " + VkApi.SCOPE_DEMANDS + " — подтверждение/возврат запросов наград недоступны. "
					+ "Перелогинься: /twitch vk logout → /twitch vk login");
		}
	}

	// ---------- Отправка в чат VK ----------

	/** Автоответ зрителю — только если включены модуль «Ответы в чат» и ответы в VK. */
	public void reply(String text) {
		if (!mod.config().isEnabled(Module.CHAT_REPLIES) || !mod.config().vk.replies) {
			return;
		}
		send(text, false);
	}

	/**
	 * Отправить сообщение в чат канала VK (очередь с паузой 1,5 с между сообщениями).
	 *
	 * @param verbose показывать ошибки игроку (для команды /twitch vk say)
	 */
	public void send(String text, boolean verbose) {
		String message = ChatSender.clean(text);
		if (message.isEmpty()) {
			return;
		}
		if (!mod.vkStore().hasTokens() || slug.isEmpty()) {
			if (verbose) {
				Chat.error("VK Video Live не подключён: /twitch vk connect");
			}
			return;
		}
		if (!mod.vkStore().hasScope(VkApi.SCOPE_CHAT)) {
			if (verbose) {
				Chat.error("Нет права " + VkApi.SCOPE_CHAT + ". Перелогинься: /twitch vk logout → /twitch vk login");
			} else if (!warnedChatScope) {
				warnedChatScope = true;
				Chat.warn("Ответы в чат VK отключены: нет права " + VkApi.SCOPE_CHAT + " (перелогинься: /twitch vk logout → /twitch vk login).");
			}
			return;
		}
		long delay;
		synchronized (sendLock) {
			long now = System.currentTimeMillis();
			long at = Math.max(now, nextSendAt);
			nextSendAt = at + SEND_INTERVAL_MS;
			delay = at - now;
		}
		Runnable task = () -> mod.worker().execute(() -> doSend(message, verbose, true));
		if (delay <= 0) {
			task.run();
		} else {
			mod.scheduler().schedule(task, delay, TimeUnit.MILLISECONDS);
		}
	}

	private void doSend(String message, boolean verbose, boolean retry) {
		remember(message);
		try {
			api.sendMessage(slug, streamId, message, redirectUri());
		} catch (VkApi.ApiException e) {
			if (retry && (e.code.contains("too_fast") || e.status == 429)) {
				mod.scheduler().schedule(() -> mod.worker().execute(() -> doSend(message, verbose, false)), 3, TimeUnit.SECONDS);
				return;
			}
			TwitchCraftClient.LOGGER.warn("VK: не удалось отправить сообщение в чат: {}", e.getMessage());
			if (verbose) {
				Chat.error("Не удалось отправить в чат VK: " + e.getMessage());
			} else if (streamId.isEmpty() && !warnedNoStream) {
				warnedNoStream = true;
				Chat.warn("VK: сообщение в чат не отправлено (" + e.getMessage() + "). Возможно, нужен идущий эфир.");
			}
		} catch (Exception e) {
			TwitchCraftClient.LOGGER.warn("VK: ошибка отправки в чат: {}", e.toString());
			if (verbose) {
				Chat.error("Ошибка отправки в чат VK: " + e.getMessage());
			}
		}
	}

	private void remember(String text) {
		synchronized (recentlySent) {
			recentlySent.addLast(text.trim());
			while (recentlySent.size() > 30) {
				recentlySent.pollFirst();
			}
		}
	}

	/** Это сообщение недавно отправили мы сами? (чтобы наш ответ не сработал как команда) */
	public boolean wasSentByUs(String text) {
		if (text == null) {
			return false;
		}
		synchronized (recentlySent) {
			return recentlySent.contains(text.trim());
		}
	}

	// ---------- Создание наград на VK ----------

	/** Создаёт на VK Video Live награды из конфига (rewards), которых там ещё нет. */
	public void syncRewards() {
		if (!mod.vkStore().hasTokens() || slug.isEmpty()) {
			Chat.error("VK Video Live не подключён: /twitch vk login, затем /twitch vk connect");
			return;
		}
		if (!mod.vkStore().hasScope(VkApi.SCOPE_REWARDS)) {
			Chat.error("Нет права " + VkApi.SCOPE_REWARDS + ". Перелогинься: /twitch vk logout → /twitch vk login");
			return;
		}
		Chat.info("Сверяю награды с VK Video Live...");
		String channel = slug;
		String redirect = redirectUri();
		mod.worker().execute(() -> {
			try {
				Set<String> existing = ConcurrentHashMap.newKeySet();
				for (JsonElement element : api.rewardsManageInfo(channel, redirect)) {
					if (element.isJsonObject()) {
						existing.add(VkEvents.firstStr(element.getAsJsonObject(), "name", "title").trim().toLowerCase(Locale.ROOT));
					}
				}
				List<String> created = new ArrayList<>();
				List<String> skipped = new ArrayList<>();
				List<String> failed = new ArrayList<>();
				Map<String, ModConfig.Action> rewards = mod.config().rewards;
				if (rewards != null) {
					for (Map.Entry<String, ModConfig.Action> entry : rewards.entrySet()) {
						String title = entry.getKey().trim();
						ModConfig.Action action = entry.getValue();
						if (title.equals("*") || title.isEmpty() || action == null) {
							continue;
						}
						if (existing.contains(title.toLowerCase(Locale.ROOT))) {
							skipped.add(title);
							continue;
						}
						try {
							String id = api.createReward(channel, buildReward(title, action), redirect);
							created.add(title + (id.isEmpty() ? "" : ""));
						} catch (Exception e) {
							failed.add(title + " (" + e.getMessage() + ")");
						}
					}
				}
				Chat.success("Награды VK: создано " + created.size() + ", уже было " + skipped.size() + ", ошибок " + failed.size());
				if (!created.isEmpty()) {
					Chat.info("§7Созданы: §d" + String.join("§7, §d", created));
				}
				for (String f : failed) {
					Chat.warn("Не создана: " + f);
				}
				if (!failed.isEmpty()) {
					Chat.warn("Частые причины: баллы канала не включены на VK, лимит наград, повтор названия.");
				}
			} catch (Exception e) {
				TwitchCraftClient.LOGGER.error("VK: ошибка синхронизации наград", e);
				Chat.error("Ошибка синхронизации наград VK: " + e.getMessage());
			}
		});
	}

	private JsonObject buildReward(String title, ModConfig.Action action) {
		JsonObject reward = new JsonObject();
		reward.addProperty("name", title.length() > 50 ? title.substring(0, 50) : title);
		reward.addProperty("price", Math.max(1, action.cost > 0 ? action.cost : mod.config().rewardsSettings.defaultCost));
		if (action.prompt != null && !action.prompt.isBlank()) {
			reward.addProperty("description", action.prompt.length() > 200 ? action.prompt.substring(0, 200) : action.prompt);
		}
		reward.addProperty("is_message_required", action.input);
		if (action.cooldown >= 10) {
			reward.addProperty("repair_timeout", Math.min(604_800, action.cooldown));
		}
		return reward;
	}

	// ---------- Тесты ----------

	/** Тестовое событие «как будто с VK» (/twitch vk test ...): без обращений к API и без кулдаунов. */
	public static TwitchEvent testEvent(TwitchEvent.Type type, String user, int amount, String message, String reward) {
		return TwitchEvent.test(type, user, amount, message, reward, "1").withPlatform(TwitchEvent.PLATFORM_VK);
	}

	private static String truncate(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max) + "…";
	}
}
