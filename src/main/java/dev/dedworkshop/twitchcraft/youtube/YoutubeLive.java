package dev.dedworkshop.twitchcraft.youtube;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.donations.LocalCallbackServer;
import dev.dedworkshop.twitchcraft.game.GameStats;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import dev.dedworkshop.twitchcraft.util.Chat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * YouTube Live: OAuth-вход, поиск эфира, чтение live chat, очередь исходящих сообщений,
 * учёт квоты YouTube Data API и управление трансляцией (эфир/тест/завершить, заголовок, модерация).
 */
public class YoutubeLive {
	private static final long LOGIN_TIMEOUT_MINUTES = 10;
	/** Первая задержка повторного поиска эфира. */
	private static final long BROADCAST_DISCOVERY_MS = 30_000;
	/** Потолок нарастающей задержки поиска, пока эфира нет. */
	private static final long DISCOVERY_MAX_MS = 5 * 60_000;
	private static final long DEFAULT_POLL_MS = 5_000;
	/** Первая задержка после сбоя опроса; дальше растёт вдвое. */
	private static final long BACKOFF_MIN_MS = 2_000;
	/** Потолок нарастающей задержки опроса при повторяющихся сбоях. */
	private static final long BACKOFF_MAX_MS = 60_000;
	/** Как часто проверять квоту после её исчерпания (и не позже сброса Google). */
	private static final long QUOTA_RETRY_MS = 30 * 60_000;
	/** Профиль канала не запрашиваем чаще этого интервала: channels.list тоже стоит квоты. */
	private static final long CHANNEL_REFRESH_MS = 30 * 60_000;
	/** Как часто писать счётчик квоты в config/twitchcraft-youtube.json. */
	private static final long QUOTA_SAVE_INTERVAL_MS = 60_000;
	/** Сколько id сообщений помним: страница live chat может содержать до 2000 сообщений. */
	private static final int MAX_SEEN_MESSAGES = 5_000;
	/** Сколько зрителей храним для модерации (бан/удаление по нику). */
	private static final int MAX_PARTICIPANTS = 300;
	/** Сколько минут храним id бана, чтобы снять его командой. */
	private static final long BAN_MEMORY_MS = 6 * 60 * 60_000L;

	private static final long SEND_INTERVAL_MS = 2_000;
	private static final int MAX_SEND_QUEUE = 20;
	private static final int MAX_MESSAGE_LENGTH = 200;
	private static final int MAX_TITLE_LENGTH = 100;
	private static final int RECENTLY_SENT_LIMIT = 30;

	private final TwitchCraftClient mod;
	private final YoutubeApi api;
	private final AtomicBoolean pollInFlight = new AtomicBoolean();
	private final Deque<Outgoing> sendQueue = new ArrayDeque<>();
	private final Deque<String> recentlySent = new ArrayDeque<>();
	private final Set<String> seenMessageIds = new LinkedHashSet<>();
	/** Зрители текущего чата: ключ — channelId, значение свежее при каждом обращении. */
	private final Map<String, Participant> participants = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Participant> eldest) {
			return size() > MAX_PARTICIPANTS;
		}
	};
	/** Бан, созданный модом: ключ — логин зрителя, значение — id бана (для разбана). */
	private final Map<String, Ban> bans = new LinkedHashMap<>();
	private final Object sendLock = new Object();
	private final Object participantLock = new Object();

	private volatile boolean wantConnected;
	private volatile boolean manuallyDisconnected;
	private volatile boolean loginReconnectRequested;
	private volatile boolean disconnectDuringLogin;
	private volatile long connectionGeneration;
	private volatile boolean tokenRejected;
	private volatile String statusDetail = "";
	private volatile String channelId = "";
	private volatile String channelTitle = "";
	private volatile String broadcastId = "";
	private volatile String broadcastTitle = "";
	private volatile String liveChatId = "";
	private volatile String nextPageToken = "";
	private volatile long pollingIntervalMillis = DEFAULT_POLL_MS;
	private volatile boolean initialHistorySkipped;
	private volatile int eventsReceived;
	private volatile int chatReceived;
	private volatile int moderationSeen;
	private volatile int unknownTypes;
	private volatile long lastPollAt;

	// ---------- Надёжность и квота ----------
	private volatile int consecutiveFailures;
	private volatile String lastError = "";
	private volatile long lastFailureAt;
	private volatile long discoveryDelayMillis = BROADCAST_DISCOVERY_MS;
	private volatile long channelFetchedAt;
	private volatile boolean quotaWarned;
	private volatile long quotaWaitSince;
	private volatile long lastQuotaSaveAt;

	// ---------- Данные эфира ----------
	private volatile int viewers = -1;
	private volatile long viewersUpdatedAt;
	private volatile long streamStartedAt;
	private volatile long nextViewersAt;

	private volatile LocalCallbackServer loginServer;
	private volatile String loginState;
	private volatile String codeVerifier;
	private volatile long loginAttempt;
	private ScheduledFuture<?> loginTimeout;
	private volatile ScheduledFuture<?> pollTask;
	private volatile ScheduledFuture<?> discoveryTask;
	private volatile boolean sendDraining;
	private volatile long nextSendAt;
	private volatile boolean warnedReadScope;
	private volatile boolean warnedWriteScope;

	public YoutubeLive(TwitchCraftClient mod) {
		this.mod = mod;
		this.api = new YoutubeApi(mod);
		restoreQuota();
	}

	public YoutubeApi api() {
		return api;
	}

	public YoutubeQuota quota() {
		return api.quota();
	}

	public boolean isConfigured() {
		return mod.config().youtube != null && mod.config().youtube.clientId != null
				&& !mod.config().youtube.clientId.isBlank();
	}

	public boolean isLoggedIn() {
		return mod.youtubeStore().hasTokens();
	}

	public boolean isActive() {
		return wantConnected && !liveChatId.isBlank();
	}

	public boolean isConnecting() {
		return wantConnected && !isActive();
	}

	public boolean isLoginInProgress() {
		LocalCallbackServer server = loginServer;
		return server != null && server.isRunning();
	}

	public String channelTitle() {
		return channelTitle;
	}

	public String channelId() {
		return channelId;
	}

	public String broadcastId() {
		return broadcastId;
	}

	public String broadcastTitle() {
		return broadcastTitle;
	}

	/** Ссылка на трансляцию (пустая, если эфир ещё не найден). */
	public String broadcastUrl() {
		String id = broadcastId;
		return id.isBlank() ? "" : "https://youtu.be/" + id;
	}

	public String liveChatId() {
		return liveChatId;
	}

	public int eventsReceived() {
		return eventsReceived;
	}

	public int chatReceived() {
		return chatReceived;
	}

	/** Сколько модерационных сообщений (бан, удаление, спам) увидел мод в этом чате. */
	public int moderationSeen() {
		return moderationSeen;
	}

	/** Сколько раз подряд опрос/поиск завершился ошибкой (для нарастающей задержки). */
	public int consecutiveFailures() {
		return consecutiveFailures;
	}

	/** Зрители эфира по данным YouTube; -1 — неизвестно (опрос выключен или ещё не выполнен). */
	public int viewers() {
		return viewers;
	}

	/** Когда в последний раз обновлялись данные эфира (мс, 0 — ещё не запрашивали). */
	public long viewersUpdatedAt() {
		return viewersUpdatedAt;
	}

	/** Фактическое начало эфира в миллисекундах (0 — неизвестно). */
	public long streamStartedAt() {
		return streamStartedAt;
	}

	/** Последняя ошибка YouTube API (для статуса и /twitch youtube info). */
	public String lastError() {
		return lastError;
	}

	public long pollingIntervalMillis() {
		return pollingIntervalMillis;
	}

	/** Сколько зрителей чата помнит мод для модерации по нику. */
	public int participantsCount() {
		synchronized (participantLock) {
			return participants.size();
		}
	}

	public String statusText() {
		YoutubeStore store = mod.youtubeStore();
		if (!isConfigured()) {
			return "§7не настроено §8(нужен Desktop OAuth Client ID: /twitch youtube)";
		}
		if (isLoginInProgress()) {
			return "§eожидаю вход в браузере...";
		}
		if (!store.hasTokens()) {
			return "§7вход не выполнен §8(/twitch youtube login)";
		}
		String who = !store.channelTitle.isBlank() ? " §7как §c" + store.channelTitle + "§7" : "";
		if (tokenRejected) {
			return "§cтокен YouTube отклонён — войди заново: /twitch youtube login";
		}
		if (!mod.isModuleEnabled(Module.YOUTUBE_LIVE)) {
			return "§7модуль выключен" + who;
		}
		if (!store.canReadChat()) {
			return "§cнет scope youtube.readonly / youtube.force-ssl — войди заново" + who;
		}
		if (!wantConnected) {
			return "§7отключено" + who;
		}
		if (isActive()) {
			return "§aподключено к live chat" + who
					+ (viewers >= 0 ? " §7зрителей: §f" + viewers : "")
					+ (chatReceived > 0 ? " §7сообщений: " + chatReceived : "")
					+ " §8(опрос " + Math.max(1, pollingIntervalMillis / 1000) + " с)";
		}
		return "§eпоиск активного эфира..." + (statusDetail.isBlank() ? "" : " §7(" + statusDetail + ")") + who;
	}

	/** Подробный статус для /twitch youtube info и экрана настроек. */
	public List<String> infoLines() {
		List<String> lines = new ArrayList<>();
		ModConfig.Youtube cfg = mod.config().youtube;
		YoutubeQuota quota = api.quota();
		quota.sync(cfg == null ? "" : cfg.clientId);
		lines.add("§7Статус: " + statusText());
		lines.add("§7Канал: " + (channelTitle.isBlank() ? "не определён" : "§f" + channelTitle)
				+ (channelId.isBlank() ? "" : " §8(" + channelId + ")"));
		lines.add("§7Эфир: " + (broadcastId.isBlank() ? "не найден" : "§f" + broadcastId)
				+ (broadcastTitle.isBlank() ? "" : " §7— " + broadcastTitle)
				+ (broadcastUrl().isBlank() ? "" : " §8" + broadcastUrl()));
		lines.add("§7Чат: " + (liveChatId.isBlank() ? "не подключён" : "§f" + liveChatId)
				+ " §7опрос " + Math.max(1, pollingIntervalMillis / 1000) + " с, сообщений " + chatReceived
				+ ", событий " + eventsReceived + ", модерации " + moderationSeen);
		if (viewers >= 0) {
			lines.add("§7Зрители: §f" + viewers + " §8(обновлено "
					+ GameStats.formatDuration(System.currentTimeMillis() - viewersUpdatedAt) + " назад)"
					+ (streamStartedAt > 0 ? "§7, эфир идёт " + GameStats.formatDuration(System.currentTimeMillis() - streamStartedAt) : ""));
		} else {
			lines.add("§7Зрители: §8не опрашиваются" + (cfg != null && !cfg.trackViewers ? " (youtube.trackViewers выключен)" : ""));
		}
		lines.add("§7Квота Data API: §f" + quota.describe(cfg == null ? 0 : cfg.quotaBudget)
				+ (cfg != null && !cfg.quotaGuard ? " §8(контроль выключен)" : ""));
		lines.add("§7Зрителей в памяти для модерации: §f" + participantsCount()
				+ "§7, активных банов мода: §f" + activeBanCount());
		if (consecutiveFailures > 0) {
			lines.add("§cСбои подряд: " + consecutiveFailures + " — следующая попытка через "
					+ GameStats.formatDuration(nextRetryDelay()) + (lastError.isBlank() ? "" : "; последняя ошибка: " + lastError));
		} else if (!lastError.isBlank()) {
			lines.add("§7Последняя ошибка: §8" + lastError);
		}
		return lines;
	}

	/**
	 * Плейсхолдеры YouTube для текстов мода: {youtube_viewers} {youtube_live_time} {youtube_broadcast_url}
	 * {youtube_title} {youtube_channel} {youtube_quota} {youtube_quota_left}.
	 */
	/**
	 * Немедленно обновить данные эфира (зрители, время начала, заголовок) — кнопка «Обновить данные».
	 * Запрос стоит 1 единицу квоты, поэтому при исчерпанном бюджете ничего не делает.
	 */
	public void refreshStreamData() {
		ModConfig.Youtube cfg = mod.config().youtube;
		if (!mod.isModuleEnabled(Module.YOUTUBE_LIVE) || !isLoggedIn()) {
			Chat.warn("YouTube: модуль youtubeLive выключен или нет входа — данные эфира недоступны.");
			return;
		}
		if (broadcastId.isBlank()) {
			Chat.info("YouTube: трансляция не найдена — ищу активный эфир.");
			long generation = connectionGeneration;
			mod.worker().execute(() -> discoverBroadcast(generation));
			return;
		}
		if (cfg == null || !cfg.trackViewers) {
			Chat.warn("YouTube: опрос зрителей выключен (youtube.trackViewers).");
			return;
		}
		if (cfg.quotaGuard && api.quota().exhausted(cfg.quotaBudget)) {
			Chat.warn("YouTube: дневная квота Data API исчерпана (" + api.quota().describe(cfg.quotaBudget) + ").");
			return;
		}
		nextViewersAt = 0;
		pollViewers(connectionGeneration);
		Chat.info("YouTube: обновляю данные эфира (videos.list, 1 единица квоты).");
	}

	public Map<String, String> placeholders() {
		ModConfig.Youtube cfg = mod.config().youtube;
		YoutubeQuota quota = api.quota();
		quota.sync(cfg == null ? "" : cfg.clientId);
		int budget = cfg == null ? 0 : cfg.quotaBudget;
		Map<String, String> vars = new LinkedHashMap<>();
		vars.put("youtube_viewers", viewers >= 0 ? String.valueOf(viewers) : "");
		vars.put("youtube_live_time", streamStartedAt > 0
				? GameStats.formatDuration(System.currentTimeMillis() - streamStartedAt) : "");
		vars.put("youtube_broadcast_url", broadcastUrl());
		vars.put("youtube_id", broadcastId);
		vars.put("youtube_title", broadcastTitle);
		vars.put("youtube_channel", channelTitle);
		vars.put("youtube_quota", String.valueOf(quota.used()));
		vars.put("youtube_quota_left", String.valueOf(quota.remaining(budget)));
		return vars;
	}

	public String overlayMark() {
		if (!isLoggedIn() || !mod.isModuleEnabled(Module.YOUTUBE_LIVE)) return "";
		return "YT" + (isActive() ? "●" : wantConnected ? "◌" : "○");
	}

	// ---------- OAuth Desktop + PKCE ----------

	/** Google Desktop apps use a root loopback URI; unlike web clients, the port is not registered in advance. */
	public String redirectUri() {
		return "http://localhost:" + mod.config().youtube.callbackPort;
	}

	public String loginUrl() {
		String clientId = mod.config().youtube.clientId;
		if (clientId == null || clientId.isBlank() || loginState == null || codeVerifier == null) return "";
		return YoutubeApi.authorizeUrl(clientId.trim(), redirectUri(), loginState, YoutubeApi.codeChallenge(codeVerifier));
	}

	/** Start a local loopback callback and return the Google consent URL. */
	public synchronized String beginLogin() {
		if (!isConfigured()) {
			Chat.error("Сначала укажи OAuth Client ID приложения типа Desktop в Google Cloud Console (настройки: /twitch youtube).");
			return null;
		}
		cancelLogin();
		long attempt = loginAttempt;
		loginReconnectRequested = true;
		disconnectDuringLogin = false;
		loginState = LocalCallbackServer.newState();
		String expectedState = loginState;
		codeVerifier = YoutubeApi.newCodeVerifier();
		String url = loginUrl();
		LocalCallbackServer server = new LocalCallbackServer(mod.config().youtube.callbackPort, "/", expectedState,
				"YouTube Live", "/twitch youtube login",
				params -> onLoginCallback(params, attempt, expectedState),
				params -> onLoginError(params, attempt, expectedState));
		try {
			server.start();
		} catch (Exception e) {
			server.close();
			loginState = null;
			codeVerifier = null;
			loginReconnectRequested = false;
			disconnectDuringLogin = false;
			Chat.error("Не удалось открыть loopback-порт " + mod.config().youtube.callbackPort + ": " + e.getMessage()
					+ ". Поменяй порт YouTube в настройках и попробуй снова.");
			return null;
		}
		loginServer = server;
		loginTimeout = mod.scheduler().schedule(() -> {
			synchronized (YoutubeLive.this) {
				if (loginAttempt == attempt && loginServer == server && server.isRunning()) {
					server.close();
					loginServer = null;
					loginState = null;
					codeVerifier = null;
					loginReconnectRequested = false;
					disconnectDuringLogin = false;
					Chat.warn("Вход в YouTube отменён: callback не пришёл за " + LOGIN_TIMEOUT_MINUTES + " минут. Попробуй /twitch youtube login ещё раз.");
				}
			}
		}, LOGIN_TIMEOUT_MINUTES, TimeUnit.MINUTES);
		return url;
	}

	public synchronized void cancelLogin() {
		loginAttempt++;
		LocalCallbackServer server = loginServer;
		loginServer = null;
		if (server != null) server.close();
		ScheduledFuture<?> timeout = loginTimeout;
		loginTimeout = null;
		if (timeout != null) timeout.cancel(false);
		loginState = null;
		codeVerifier = null;
		loginReconnectRequested = false;
		disconnectDuringLogin = false;
	}

	private synchronized void onLoginCallback(Map<String, String> params, long attempt, String expectedState) {
		if (attempt != loginAttempt || !expectedState.equals(loginState)) return;
		String returnedState = params.get("state");
		if (!expectedState.equals(returnedState)) {
			cancelLogin();
			Chat.error("YouTube OAuth: параметр state отсутствует или не совпал — код отклонён. Начни вход заново.");
			return;
		}
		String code = params.get("code");
		String verifier = codeVerifier;
		String redirect = redirectUri();
		finishCallback(attempt);
		if (code == null || code.isBlank() || verifier == null) {
			loginReconnectRequested = false;
			disconnectDuringLogin = false;
			Chat.error("YouTube не вернул код авторизации. Попробуй /twitch youtube login ещё раз.");
			return;
		}
		mod.worker().execute(() -> exchangeAndConnect(code, verifier, redirect, attempt));
	}

	private synchronized void onLoginError(Map<String, String> params, long attempt, String expectedState) {
		if (attempt != loginAttempt || !expectedState.equals(loginState)) return;
		if (!expectedState.equals(params.get("state"))) {
			TwitchCraftClient.LOGGER.warn("YouTube OAuth: отклонён callback с отсутствующим/неверным state");
			return;
		}
		String error = params.getOrDefault("error_description", params.getOrDefault("error", "доступ не выдан"));
		finishCallback(attempt);
		codeVerifier = null;
		loginReconnectRequested = false;
		disconnectDuringLogin = false;
		Chat.error("Вход в YouTube не выполнен: " + error);
	}

	private synchronized void finishCallback(long attempt) {
		if (attempt != loginAttempt) return;
		LocalCallbackServer server = loginServer;
		loginServer = null;
		if (server != null) server.close();
		ScheduledFuture<?> timeout = loginTimeout;
		loginTimeout = null;
		if (timeout != null) timeout.cancel(false);
		loginState = null;
		// verifier is copied before finishCallback and erased after token exchange completes
	}

	/** Accept a full localhost callback URL pasted from the browser (state is still required). */
	public synchronized void finishLoginWithUrl(String callbackUrl) {
		if (callbackUrl == null || callbackUrl.isBlank()) {
			Chat.warn("Вставь полный адрес localhost из браузера: /twitch youtube code <url>");
			return;
		}
		String url = callbackUrl.trim();
		long attempt = loginAttempt;
		String expectedState = loginState;
		try {
			java.net.URI uri = java.net.URI.create(url);
			String query = uri.getRawQuery();
			if (query == null) throw new IllegalArgumentException("в адресе нет OAuth-параметров");
			Map<String, String> params = new java.util.LinkedHashMap<>();
			for (String part : query.split("&")) {
				int equals = part.indexOf('=');
				if (equals > 0) params.put(URLDecoder.decode(part.substring(0, equals), StandardCharsets.UTF_8),
						URLDecoder.decode(part.substring(equals + 1), StandardCharsets.UTF_8));
			}
			if (attempt != loginAttempt || expectedState == null || !expectedState.equals(loginState)
					|| !expectedState.equals(params.get("state"))) {
				Chat.error("OAuth state не совпал. Вставь адрес из текущей попытки входа или начни вход заново.");
				return;
			}
			String code = params.get("code");
			if (code == null || code.isBlank()) {
				Chat.error("В callback URL нет code. Попробуй /twitch youtube login ещё раз.");
				return;
			}
			String verifier = codeVerifier;
			String redirect = redirectUri();
			finishCallback(attempt);
			mod.worker().execute(() -> exchangeAndConnect(code, verifier, redirect, attempt));
		} catch (Exception e) {
			Chat.error("Не удалось разобрать callback URL: " + e.getMessage());
		}
	}

	private void exchangeAndConnect(String code, String verifier, String redirect, long attempt) {
		try {
			if (attempt != loginAttempt) return;
			String clientId = mod.config().youtube.clientId.trim();
			JsonObject json = api.exchangeCode(clientId, code, verifier, redirect);
			if (attempt != loginAttempt) return;
			String access = YoutubeApi.str(json, "access_token");
			if (access.isBlank()) {
				throw new YoutubeApi.ApiException(401, "invalid_grant", "Google не вернул access_token");
			}
			long expiresIn = longValue(json, "expires_in", 3600);
			String scopes = YoutubeApi.str(json, "scope");
			if (scopes.isBlank()) scopes = YoutubeApi.SCOPES;

			// Validate the newly issued token without mutating the active account's stored credentials.
			JsonObject channel = api.myChannel(access);
			if (attempt != loginAttempt) return;
			JsonObject snippet = child(channel, "snippet");
			String foundChannelId = YoutubeApi.str(channel, "id");
			String foundChannelTitle = YoutubeApi.str(snippet, "title");
			boolean connectAfterLogin;
			YoutubeStore store = mod.youtubeStore();
			synchronized (this) {
				if (attempt != loginAttempt) return;
				connectAfterLogin = loginReconnectRequested && !disconnectDuringLogin;
				// Invalidate old-account polling before swapping tokens or channel identity.
				stopConnection("обновлён вход YouTube");
				if (!store.channelId.isBlank() && !store.channelId.equals(foundChannelId)) {
					synchronized (recentlySent) {
						recentlySent.clear();
					}
					clearParticipants();
				}
				store.replaceTokens(access, YoutubeApi.str(json, "refresh_token"), expiresIn, scopes);
				store.channelId = foundChannelId;
				store.channelTitle = foundChannelTitle;
				store.save();
				tokenRejected = false;
				channelId = foundChannelId;
				channelTitle = foundChannelTitle;
				channelFetchedAt = System.currentTimeMillis();
				loginReconnectRequested = false;
				disconnectDuringLogin = false;
				if (connectAfterLogin) manuallyDisconnected = false;
				if (connectAfterLogin && mod.isModuleEnabled(Module.YOUTUBE_LIVE)) connect(false);
			}
			Chat.success("YouTube: вход выполнен" + (foundChannelTitle.isBlank() ? "" : " как §c" + foundChannelTitle + "§r") + ".");
		} catch (Exception e) {
			synchronized (this) {
				if (attempt != loginAttempt) return;
				loginReconnectRequested = false;
				disconnectDuringLogin = false;
			}
			TwitchCraftClient.LOGGER.error("YouTube: ошибка завершения OAuth-входа", e);
			Chat.error("YouTube: ошибка входа: " + safeMessage(e));
		} finally {
			synchronized (this) {
				if (attempt == loginAttempt) codeVerifier = null;
			}
		}
	}

	public synchronized void logout() {
		cancelLogin();
		disconnect();
		YoutubeStore store = mod.youtubeStore();
		String token = store.hasRefreshToken() ? store.refreshToken : store.accessToken;
		store.clearTokens();
		tokenRejected = false;
		mod.worker().execute(() -> api.revoke(token));
		Chat.info("YouTube: токены удалены из config/twitchcraft-youtube.json.");
	}

	// ---------- Connection and polling ----------

	public synchronized void connect(boolean verbose) {
		if (!mod.isModuleEnabled(Module.YOUTUBE_LIVE)) {
			if (verbose) Chat.warn("Модуль «YouTube Live» выключен: /twitch module youtubeLive on");
			return;
		}
		YoutubeStore store = mod.youtubeStore();
		if (!store.hasTokens()) {
			if (verbose) Chat.warn("Сначала войди в YouTube: /twitch youtube login");
			return;
		}
		if (!store.canReadChat()) {
			if (verbose || !warnedReadScope) {
				warnedReadScope = true;
				Chat.warn("Токен YouTube не имеет права youtube.readonly или youtube.force-ssl. Выйди и войди снова: /twitch youtube logout → /twitch youtube login");
			}
			return;
		}
		manuallyDisconnected = false;
		quotaWarned = false;
		consecutiveFailures = 0;
		discoveryDelayMillis = BROADCAST_DISCOVERY_MS;
		if (loginReconnectRequested) disconnectDuringLogin = false;
		// A discovery request may already be running on the worker while neither scheduled task exists.
		// Treat a wanted connection as in progress so config refreshes / repeated button clicks stay idempotent.
		if (wantConnected) {
			// Ждали сброса квоты и порог только что увеличили (сохранение настроек, кнопка «Подключить») —
			// проверяем заново сразу, не дожидаясь отложенной попытки.
			if (quotaWaitSince > 0 && !quotaExhaustedNow()) {
				quotaWaitSince = 0;
				statusDetail = "ищу активную трансляцию";
				cancelScheduledTasks();
				long retryGeneration = connectionGeneration;
				mod.worker().execute(() -> discoverBroadcast(retryGeneration));
			}
			return;
		}
		wantConnected = true;
		tokenRejected = false;
		statusDetail = "ищу активную трансляцию";
		cancelScheduledTasks();
		long generation = connectionGeneration;
		mod.worker().execute(() -> discoverBroadcast(generation));
	}

	public synchronized void disconnect() {
		manuallyDisconnected = true;
		if (loginReconnectRequested) disconnectDuringLogin = true;
		stopConnection("отключено");
		persistQuota(true);
	}

	public void onModuleChanged(boolean enabled) {
		if (enabled) {
			syncWithConfig();
		} else {
			stopConnection("модуль выключен");
		}
	}

	/** Reconcile the live chat connection after config reload/save or initial startup. */
	public void syncWithConfig() {
		restoreQuota();
		if (mod.isModuleEnabled(Module.YOUTUBE_LIVE) && isLoggedIn() && !manuallyDisconnected) {
			connect(false);
		} else if (!mod.isModuleEnabled(Module.YOUTUBE_LIVE)) {
			stopConnection("модуль выключен");
		}
	}

	public synchronized void shutdown() {
		cancelLogin();
		stopConnection("завершение игры");
		persistQuota(true);
	}

	private synchronized void stopConnection(String reason) {
		connectionGeneration++;
		wantConnected = false;
		statusDetail = reason == null ? "" : reason;
		liveChatId = "";
		broadcastId = "";
		broadcastTitle = "";
		nextPageToken = "";
		initialHistorySkipped = false;
		viewers = -1;
		viewersUpdatedAt = 0;
		streamStartedAt = 0;
		nextViewersAt = 0;
		consecutiveFailures = 0;
		discoveryDelayMillis = BROADCAST_DISCOVERY_MS;
		cancelScheduledTasks();
		pollInFlight.set(false);
		synchronized (sendLock) {
			sendQueue.clear();
			sendDraining = false;
		}
	}

	private void cancelScheduledTasks() {
		ScheduledFuture<?> poll = pollTask;
		pollTask = null;
		if (poll != null) poll.cancel(false);
		ScheduledFuture<?> discovery = discoveryTask;
		discoveryTask = null;
		if (discovery != null) discovery.cancel(false);
	}

	private void discoverBroadcast(long generation) {
		if (!wantConnected || generation != connectionGeneration) return;
		if (quotaBlocked()) return;
		try {
			YoutubeStore store = mod.youtubeStore();
			if (!store.hasTokens()) {
				statusDetail = "нет OAuth-токена";
				return;
			}
			// channels.list стоит квоты, поэтому профиль канала обновляем не чаще раза в полчаса.
			long now = System.currentTimeMillis();
			boolean channelKnown = !store.channelId.isBlank() && !store.channelTitle.isBlank();
			if (!channelKnown || now - channelFetchedAt > CHANNEL_REFRESH_MS) {
				JsonObject channel = api.myChannel();
				if (!wantConnected || generation != connectionGeneration) return;
				JsonObject channelSnippet = child(channel, "snippet");
				String foundChannelId = YoutubeApi.str(channel, "id");
				String foundTitle = YoutubeApi.str(channelSnippet, "title");
				if (!foundChannelId.isBlank()) {
					channelId = foundChannelId;
					store.channelId = foundChannelId;
				}
				if (!foundTitle.isBlank()) {
					channelTitle = foundTitle;
					store.channelTitle = foundTitle;
				}
				channelFetchedAt = System.currentTimeMillis();
				store.save();
			} else if (channelId.isBlank()) {
				channelId = store.channelId;
				channelTitle = store.channelTitle;
			}

			JsonArray broadcasts = api.activeBroadcasts();
			if (!wantConnected || generation != connectionGeneration) return;
			String foundBroadcastId = "";
			String foundChatId = "";
			for (var element : broadcasts) {
				if (!element.isJsonObject()) continue;
				JsonObject candidate = element.getAsJsonObject();
				JsonObject snippet = child(candidate, "snippet");
				String chatId = YoutubeApi.str(snippet, "liveChatId");
				if (!chatId.isBlank()) {
					foundBroadcastId = YoutubeApi.str(candidate, "id");
					foundChatId = chatId;
					break;
				}
			}
			if (foundChatId.isBlank()) {
				liveChatId = "";
				broadcastId = "";
				broadcastTitle = "";
				statusDetail = broadcasts.isEmpty() ? "нет активной трансляции" : "у трансляции нет live chat";
				// Пока эфира нет, запрашиваем всё реже: каждый поиск стоит квоты проекта Google.
				long delay = discoveryDelayMillis;
				discoveryDelayMillis = Math.min(DISCOVERY_MAX_MS, Math.max(BROADCAST_DISCOVERY_MS, delay * 2));
				succeeded();
				scheduleDiscovery(delay);
				return;
			}
			if (!foundChatId.equals(liveChatId)) {
				liveChatId = foundChatId;
				broadcastId = foundBroadcastId;
				nextPageToken = "";
				initialHistorySkipped = false;
				pollingIntervalMillis = DEFAULT_POLL_MS;
				viewers = -1;
				viewersUpdatedAt = 0;
				streamStartedAt = 0;
				nextViewersAt = 0;
				clearParticipants();
				synchronized (seenMessageIds) {
					seenMessageIds.clear();
				}
			}
			discoveryDelayMillis = BROADCAST_DISCOVERY_MS;
			succeeded();
			statusDetail = "подключено к трансляции";
			schedulePoll(0);
		} catch (YoutubeApi.ApiException e) {
			if (generation == connectionGeneration) handleApiFailure("поиск трансляции", e);
		} catch (Exception e) {
			if (generation != connectionGeneration) return;
			failed(e);
			TwitchCraftClient.LOGGER.warn("YouTube: не удалось найти активную трансляцию: {}", e.toString());
			statusDetail = safeMessage(e);
			scheduleDiscovery(Math.max(discoveryDelayMillis, backoffMillis(BROADCAST_DISCOVERY_MS)));
		}
	}

	private void scheduleDiscovery(long delayMillis) {
		if (!wantConnected) return;
		long generation = connectionGeneration;
		ScheduledFuture<?> old = discoveryTask;
		if (old != null) old.cancel(false);
		discoveryTask = mod.scheduler().schedule(() -> {
			if (generation != connectionGeneration) return;
			discoveryTask = null;
			if (wantConnected) mod.worker().execute(() -> discoverBroadcast(generation));
		}, Math.max(1000, delayMillis), TimeUnit.MILLISECONDS);
	}

	private void schedulePoll(long delayMillis) {
		if (!wantConnected || liveChatId.isBlank()) return;
		long generation = connectionGeneration;
		ScheduledFuture<?> old = pollTask;
		if (old != null) old.cancel(false);
		pollTask = mod.scheduler().schedule(() -> {
			if (generation != connectionGeneration) return;
			pollTask = null;
			if (!wantConnected || liveChatId.isBlank() || !pollInFlight.compareAndSet(false, true)) return;
			try {
				mod.worker().execute(() -> pollOnce(generation));
			} catch (Exception e) {
				if (generation == connectionGeneration) pollInFlight.set(false);
			}
		}, Math.max(0, delayMillis), TimeUnit.MILLISECONDS);
	}

	private void pollOnce(long generation) {
		try {
			if (!wantConnected || generation != connectionGeneration || liveChatId.isBlank()) return;
			if (quotaBlocked()) return;
			String currentChatId = liveChatId;
			String page = initialHistorySkipped ? nextPageToken : "";
			JsonObject result = api.listMessages(currentChatId, page, pollMaxResults());
			if (!wantConnected || generation != connectionGeneration || !currentChatId.equals(liveChatId)) return;
			lastPollAt = System.currentTimeMillis();
			long interval = longValue(result, "pollingIntervalMillis", DEFAULT_POLL_MS);
			pollingIntervalMillis = Math.max(1000, interval);
			String next = YoutubeApi.str(result, "nextPageToken");
			// Эфир закончился: в ответе появляется offlineAt — переходим к поиску следующей трансляции.
			if (!YoutubeApi.str(result, "offlineAt").isBlank()) {
				TwitchCraftClient.LOGGER.info("YouTube: трансляция завершилась (offlineAt) — ищу следующий эфир");
				resetChatState("трансляция завершилась");
				scheduleDiscovery(BROADCAST_DISCOVERY_MS);
				return;
			}
			if (!initialHistorySkipped) {
				// The first response deliberately contains recent history. Keep its continuation token but
				// do not replay old chat messages or commands on startup / stream discovery.
				for (var element : array(result, "items")) {
					if (!element.isJsonObject()) continue;
					JsonObject item = element.getAsJsonObject();
					rememberMessageId(YoutubeApi.str(item, "id"));
					rememberParticipant(item);
				}
				initialHistorySkipped = !next.isBlank();
				nextPageToken = next;
				if (initialHistorySkipped) {
					TwitchCraftClient.LOGGER.info("YouTube: начальная история live chat пропущена; начинаю с новых сообщений (опрос {} мс)", pollingIntervalMillis);
				} else {
					TwitchCraftClient.LOGGER.debug("YouTube: жду pageToken для безопасного пропуска начальной истории");
				}
			} else {
				JsonArray items = array(result, "items");
				for (var element : items) {
					if (!element.isJsonObject()) continue;
					JsonObject item = element.getAsJsonObject();
					if (!rememberMessageId(YoutubeApi.str(item, "id"))) continue;
					rememberParticipant(item);
					YoutubeEventMapper.Moderation moderation = YoutubeEventMapper.moderation(item);
					if (moderation != null) {
						showModeration(moderation);
						continue;
					}
					TwitchEvent event = YoutubeEventMapper.fromMessage(item);
					if (event == null) {
						String type = YoutubeEventMapper.messageType(item);
						if (mod.config().youtube.debugEvents) {
							TwitchCraftClient.LOGGER.info("YouTube live chat: пропущено {} событие type={}",
									YoutubeEventMapper.isServiceType(type) ? "служебное" : "неподдерживаемое", type);
						} else if (!YoutubeEventMapper.isServiceType(type)) {
							unknownTypes++;
							if (unknownTypes <= 3) TwitchCraftClient.LOGGER.debug("YouTube live chat: неизвестный/служебный type={}", type);
						}
						continue;
					}
					if (event.type() == TwitchEvent.Type.CHAT) {
						chatReceived++;
						if (event.userId().equals(channelId) || wasSentByUs(event.message())) continue;
					}
					if (event.type() == TwitchEvent.Type.DONATION && !mod.config().youtube.paidMessages) continue;
					if ((event.type() == TwitchEvent.Type.SUBSCRIBE || event.type() == TwitchEvent.Type.RESUB
							|| event.type() == TwitchEvent.Type.GIFT_SUB) && !mod.config().youtube.memberships) continue;
					eventsReceived++;
					mod.onTwitchEvent(event);
				}
				nextPageToken = next;
			}
			succeeded();
			persistQuota(false);
			pollViewers(generation);
			if (mod.config().youtube.debugEvents) {
				TwitchCraftClient.LOGGER.debug("YouTube live chat poll: {} items, next token {}, interval {} ms, квота ≈{}",
						array(result, "items").size(), nextPageToken.isBlank() ? "<empty>" : "<set>", pollingIntervalMillis,
						api.quota().used());
			}
			if (wantConnected && currentChatId.equals(liveChatId)) schedulePoll(pollingIntervalMillis);
		} catch (YoutubeApi.ApiException e) {
			if (generation == connectionGeneration) handlePollFailure(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			if (generation == connectionGeneration) {
				failed(e);
				TwitchCraftClient.LOGGER.warn("YouTube: ошибка опроса live chat: {}", e.toString());
				statusDetail = "ошибка опроса: " + safeMessage(e);
				if (wantConnected) schedulePoll(backoffMillis(pollingIntervalMillis));
			}
		} finally {
			if (generation == connectionGeneration) pollInFlight.set(false);
		}
	}

	private int pollMaxResults() {
		ModConfig.Youtube cfg = mod.config().youtube;
		return cfg == null ? 2000 : cfg.pollMaxResults;
	}

	/**
	 * Нарастающая задержка после сбоев: 2 с → 4 с → 8 с … до {@value #BACKOFF_MAX_MS} мс,
	 * но не меньше штатного интервала опроса/поиска.
	 */
	private long backoffMillis(long base) {
		long delay = BACKOFF_MIN_MS;
		for (int i = 1; i < consecutiveFailures && delay < BACKOFF_MAX_MS; i++) {
			delay *= 2;
		}
		if (consecutiveFailures <= 0) delay = 0;
		return Math.min(BACKOFF_MAX_MS, Math.max(base, delay));
	}

	/** Задержка следующей попытки — для статуса. */
	private long nextRetryDelay() {
		return backoffMillis(Math.max(pollingIntervalMillis, 1000));
	}

	private void succeeded() {
		consecutiveFailures = 0;
		lastFailureAt = 0;
		quotaWaitSince = 0;
	}

	private void failed(Throwable error) {
		consecutiveFailures++;
		lastFailureAt = System.currentTimeMillis();
		lastError = safeMessage(error);
	}

	private void resetChatState(String reason) {
		liveChatId = "";
		broadcastId = "";
		broadcastTitle = "";
		nextPageToken = "";
		initialHistorySkipped = false;
		viewers = -1;
		viewersUpdatedAt = 0;
		streamStartedAt = 0;
		nextViewersAt = 0;
		statusDetail = reason == null ? "" : reason;
	}

	private boolean rememberMessageId(String id) {
		if (id == null || id.isBlank()) return true;
		synchronized (seenMessageIds) {
			if (!seenMessageIds.add(id)) return false;
			while (seenMessageIds.size() > MAX_SEEN_MESSAGES) {
				seenMessageIds.remove(seenMessageIds.iterator().next());
			}
			return true;
		}
	}

	private void handlePollFailure(YoutubeApi.ApiException error) {
		failed(error);
		persistQuota(false);
		if (error.chatEnded()) {
			TwitchCraftClient.LOGGER.info("YouTube: live chat завершён; ищу следующую трансляцию");
			resetChatState(error.chatDisabled() ? "чат трансляции выключен" : "трансляция завершилась");
			scheduleDiscovery(BROADCAST_DISCOVERY_MS);
			return;
		}
		if (error.unauthorized()) {
			tokenRejected = true;
			statusDetail = "требуется повторный вход";
			TwitchCraftClient.LOGGER.warn("YouTube: OAuth-токен отклонён: {}", error.getMessage());
			Chat.warn("YouTube: токен недействителен. Войди заново: /twitch youtube login");
			stopConnection("требуется повторный вход");
			return;
		}
		if (error.quotaExceeded()) {
			warnQuotaExhausted(error);
			scheduleDiscovery(quotaRetryDelay());
			return;
		}
		if (error.rateLimited()) {
			long retryAfter = error.retryAfterMillis();
			long delay = retryAfter > 0 ? retryAfter : Math.max(10_000, pollingIntervalMillis);
			statusDetail = "YouTube ограничил частоту запросов" + (retryAfter > 0 ? " (жду " + delay / 1000 + " с)" : "");
			TwitchCraftClient.LOGGER.warn("YouTube rate limit: {} — повтор через {} мс", error.getMessage(), delay);
			schedulePoll(delay);
			return;
		}
		if (error.reason().equalsIgnoreCase("pageTokenInvalid")) {
			TwitchCraftClient.LOGGER.warn("YouTube: pageToken больше не действителен; повторно пропускаю текущую историю чата");
			initialHistorySkipped = false;
			nextPageToken = "";
			succeeded();
			schedulePoll(Math.max(pollingIntervalMillis, DEFAULT_POLL_MS));
			return;
		}
		statusDetail = safeMessage(error);
		TwitchCraftClient.LOGGER.warn("YouTube live chat API: HTTP {} {}: {}", error.status(), error.reason(), error.getMessage());
		schedulePoll(backoffMillis(pollingIntervalMillis));
	}

	private void handleApiFailure(String operation, YoutubeApi.ApiException error) {
		failed(error);
		persistQuota(false);
		if (error.unauthorized()) {
			tokenRejected = true;
			statusDetail = "требуется повторный вход";
			Chat.warn("YouTube: OAuth недействителен или не хватает прав. Войди заново: /twitch youtube login");
			stopConnection("требуется повторный вход");
		} else if (error.quotaExceeded()) {
			warnQuotaExhausted(error);
			scheduleDiscovery(quotaRetryDelay());
		} else {
			statusDetail = safeMessage(error);
			TwitchCraftClient.LOGGER.warn("YouTube {}: HTTP {} {}: {}", operation, error.status(), error.reason(), error.getMessage());
			scheduleDiscovery(error.rateLimited()
					? Math.max(error.retryAfterMillis(), 10_000)
					: backoffMillis(discoveryDelayMillis));
		}
	}

	// ---------- Квота YouTube Data API ----------

	/**
	 * Дневной бюджет исчерпан: не жжём запросы (каждый стоит квоты), ждём сброса Google.
	 *
	 * @return true, если опрос/поиск нужно отложить
	 */
	private boolean quotaBlocked() {
		if (!quotaExhaustedNow()) return false;
		warnQuotaExhausted(null);
		scheduleDiscovery(quotaRetryDelay());
		return true;
	}

	/** Исчерпан ли бюджет квоты прямо сейчас (с перекатом суток и сменой проекта Google Cloud). */
	private boolean quotaExhaustedNow() {
		ModConfig.Youtube cfg = mod.config().youtube;
		if (cfg == null || !cfg.quotaGuard) return false;
		YoutubeQuota quota = api.quota();
		quota.sync(cfg.clientId);
		return quota.exhausted(cfg.quotaBudget);
	}

	private void warnQuotaExhausted(YoutubeApi.ApiException error) {
		ModConfig.Youtube cfg = mod.config().youtube;
		int budget = cfg == null ? 0 : cfg.quotaBudget;
		statusDetail = "квота YouTube API исчерпана";
		quotaWaitSince = System.currentTimeMillis();
		if (error != null) {
			TwitchCraftClient.LOGGER.error("YouTube Data API quotaExceeded: {}", error.getMessage());
		}
		if (!quotaWarned) {
			quotaWarned = true;
			Chat.warn("YouTube: дневная квота Data API исчерпана (" + api.quota().describe(budget) + ")."
					+ " Чат возобновится после сброса; порог настраивается в /twitch config → YouTube Live.");
		}
		persistQuota(true);
	}

	/** Сколько ждать до проверки квоты: не дольше сброса Google и не чаще раза в 5 минут. */
	private long quotaRetryDelay() {
		return Math.max(5 * 60_000, Math.min(QUOTA_RETRY_MS, YoutubeQuota.millisUntilReset() + 60_000));
	}

	/** Восстановить счётчик квоты из секретного файла (тот же день и тот же Client ID). */
	private void restoreQuota() {
		try {
			YoutubeStore store = mod.youtubeStore();
			ModConfig.Youtube cfg = mod.config() == null ? null : mod.config().youtube;
			String clientId = cfg == null ? "" : cfg.clientId;
			api.quota().restore(clientId, store.quotaDay, store.quotaUnits);
			api.quota().sync(clientId);
		} catch (Exception e) {
			TwitchCraftClient.LOGGER.debug("YouTube: не удалось восстановить счётчик квоты: {}", e.toString());
		}
	}

	/** Записать счётчик квоты в файл (не чаще раза в минуту, чтобы не дёргать диск каждый опрос). */
	private void persistQuota(boolean force) {
		try {
			long now = System.currentTimeMillis();
			if (!force && now - lastQuotaSaveAt < QUOTA_SAVE_INTERVAL_MS) return;
			lastQuotaSaveAt = now;
			YoutubeQuota quota = api.quota();
			mod.youtubeStore().saveQuota(quota.clientId(), YoutubeQuota.dayKey(), quota.used());
		} catch (Exception e) {
			TwitchCraftClient.LOGGER.debug("YouTube: не удалось сохранить счётчик квоты: {}", e.toString());
		}
	}

	// ---------- Зрители и данные эфира ----------

	/** Раз в youtube.viewersIntervalSeconds спрашиваем liveStreamingDetails: зрители и время начала. */
	private void pollViewers(long generation) {
		ModConfig.Youtube cfg = mod.config().youtube;
		if (cfg == null || !cfg.trackViewers) {
			viewers = -1;
			return;
		}
		String videoId = broadcastId;
		if (videoId.isBlank()) return;
		long now = System.currentTimeMillis();
		if (now < nextViewersAt) return;
		int interval = Math.max(15, cfg.viewersIntervalSeconds);
		nextViewersAt = now + interval * 1000L;
		if (api.quota().exhausted(cfg.quotaBudget) && cfg.quotaGuard) return;
		mod.worker().execute(() -> {
			try {
				if (generation != connectionGeneration || !videoId.equals(broadcastId)) return;
				JsonObject video = api.videoDetails(videoId);
				if (generation != connectionGeneration || !videoId.equals(broadcastId)) return;
				JsonObject details = child(video, "liveStreamingDetails");
				JsonObject snippet = child(video, "snippet");
				if (details.has("concurrentViewers")) {
					viewers = (int) Math.min(Integer.MAX_VALUE, Math.max(0, longValue(details, "concurrentViewers", 0)));
					viewersUpdatedAt = System.currentTimeMillis();
				}
				long started = parseIso8601(YoutubeApi.str(details, "actualStartTime"));
				if (started > 0) streamStartedAt = started;
				String title = YoutubeApi.str(snippet, "title");
				if (!title.isBlank()) broadcastTitle = title;
				if (cfg.debugEvents) {
					TwitchCraftClient.LOGGER.debug("YouTube: зрителей {}, эфир с {}", viewers,
							streamStartedAt > 0 ? GameStats.formatDuration(System.currentTimeMillis() - streamStartedAt) : "?");
				}
			} catch (YoutubeApi.ApiException e) {
				if (generation == connectionGeneration && !e.quotaExceeded()) {
					TwitchCraftClient.LOGGER.debug("YouTube: не удалось получить данные эфира: {}", e.getMessage());
				}
			} catch (Exception e) {
				TwitchCraftClient.LOGGER.debug("YouTube: ошибка опроса зрителей: {}", e.toString());
			} finally {
				persistQuota(false);
			}
		});
	}

	// ---------- Модерация: участники чата ----------

	private void rememberParticipant(JsonObject item) {
		JsonObject author = object(item, "authorDetails");
		if (author == null) return;
		String id = YoutubeApi.str(author, "channelId");
		String name = YoutubeApi.str(author, "displayName");
		if (id.isBlank() && name.isBlank()) return;
		JsonObject snippet = object(item, "snippet");
		String messageId = YoutubeApi.str(item, "id");
		long now = System.currentTimeMillis();
		synchronized (participantLock) {
			Participant known = id.isBlank() ? null : participants.get(id);
			String key = id.isBlank() ? "name:" + name.toLowerCase(Locale.ROOT) : id;
			Participant participant = known != null ? known : participants.get(key);
			if (participant == null) {
				participant = new Participant(key);
				participants.put(key, participant);
			}
			if (!id.isBlank()) participant.channelId = id;
			if (!name.isBlank()) participant.displayName = name;
			participant.moderator = bool(author, "isChatModerator") || bool(author, "isChatOwner");
			participant.owner = bool(author, "isChatOwner");
			if (!messageId.isBlank() && snippet != null) {
				participant.lastMessageId = messageId;
				participant.lastMessageAt = now;
			}
			participant.lastSeenAt = now;
		}
	}

	private void clearParticipants() {
		synchronized (participantLock) {
			participants.clear();
			bans.clear();
		}
	}

	private int activeBanCount() {
		long cutoff = System.currentTimeMillis() - BAN_MEMORY_MS;
		synchronized (participantLock) {
			bans.values().removeIf(ban -> ban.at < cutoff);
			return bans.size();
		}
	}

	/**
	 * Найти зрителя по нику, channelId или началу ника.
	 *
	 * @return найденный участник или null; при неоднозначности — исключение с вариантами
	 */
	private Participant findParticipant(String query) {
		if (query == null || query.isBlank()) return null;
		String wanted = query.trim();
		String lower = wanted.toLowerCase(Locale.ROOT);
		synchronized (participantLock) {
			Participant byId = participants.get(wanted);
			if (byId != null) return byId;
			Participant exact = null;
			List<Participant> partial = new ArrayList<>();
			for (Participant participant : participants.values()) {
				String name = participant.displayName == null ? "" : participant.displayName;
				if (name.equalsIgnoreCase(wanted) || lower.equals(name.toLowerCase(Locale.ROOT))) {
					exact = participant;
					break;
				}
				if (participant.channelId != null && participant.channelId.equalsIgnoreCase(wanted)) {
					exact = participant;
					break;
				}
				if (!name.isBlank() && name.toLowerCase(Locale.ROOT).contains(lower)) {
					partial.add(participant);
				}
			}
			if (exact != null) return exact;
			if (partial.size() == 1) return partial.get(0);
			if (partial.size() > 1) {
				StringBuilder names = new StringBuilder();
				for (int i = 0; i < Math.min(5, partial.size()); i++) {
					if (i > 0) names.append(", ");
					names.append(partial.get(i).displayName);
				}
				throw new IllegalArgumentException("подходит нескольким зрителям: " + names
						+ (partial.size() > 5 ? " и ещё " + (partial.size() - 5) : ""));
			}
			return null;
		}
	}

	private void showModeration(YoutubeEventMapper.Moderation moderation) {
		moderationSeen++;
		if (mod.config().youtube.debugEvents) {
			TwitchCraftClient.LOGGER.info("YouTube live chat модерация: {} {}{}", moderation.kind(), moderation.user(),
					moderation.seconds() > 0 ? " (" + moderation.seconds() + " с)" : "");
		}
		if (!mod.config().youtube.showModeration) return;
		String who = moderation.user().isBlank() ? "зритель" : moderation.user();
		String text = switch (moderation.kind()) {
			case "ban" -> "⚠ " + who + (moderation.seconds() > 0
					? " получил тайм-аут " + GameStats.formatDuration(moderation.seconds() * 1000L)
					: " заблокирован в чате навсегда");
			case "spam" -> "⚠ сообщение " + who + " помечено как спам";
			case "delete" -> "⚠ сообщение " + who + " удалено модератором";
			default -> "⚠ модерация: " + moderation.kind() + " (" + who + ")";
		};
		Chat.info("§7[YT]§r " + text);
	}

	// ---------- Управление трансляцией ----------

	private boolean controlAllowed(boolean verbose) {
		ModConfig.Youtube cfg = mod.config().youtube;
		if (!mod.isModuleEnabled(Module.YOUTUBE_LIVE)) {
			if (verbose) Chat.warn("Модуль «YouTube Live» выключен: /twitch module youtubeLive on");
			return false;
		}
		if (cfg == null || !cfg.control) {
			if (verbose) Chat.warn("Управление эфиром YouTube выключено: /twitch config → YouTube Live → «Управление эфиром».");
			return false;
		}
		if (!mod.youtubeStore().hasTokens()) {
			if (verbose) Chat.warn("Сначала войди в YouTube: /twitch youtube login");
			return false;
		}
		if (!mod.youtubeStore().canWriteChat()) {
			if (verbose) Chat.warn("Нужен scope youtube.force-ssl: /twitch youtube logout → /twitch youtube login");
			return false;
		}
		return true;
	}

	/**
	 * Перевести трансляцию в другое состояние.
	 *
	 * @param status live / testing / complete
	 */
	public void transition(String status, boolean verbose) {
		if (!controlAllowed(verbose)) return;
		String wanted = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
		String target = switch (wanted) {
			case "live", "go", "start", "эфир", "старт" -> "live";
			case "testing", "test", "тест" -> "testing";
			case "complete", "stop", "end", "стоп", "завершить" -> "complete";
			default -> "";
		};
		if (target.isBlank()) {
			Chat.error("Состояние эфира: live, testing или complete (например, /twitch youtube go live).");
			return;
		}
		mod.worker().execute(() -> {
			try {
				String id = broadcastForControl();
				if (id.isBlank()) {
					Chat.warn("YouTube: трансляция не найдена. Создай эфир в YouTube Studio и повтори команду.");
					return;
				}
				api.transition(id, target);
				String title = switch (target) {
					case "live" -> "эфир начат";
					case "testing" -> "тестовая трансляция";
					default -> "трансляция завершена";
				};
				Chat.success("YouTube: " + title + ".");
				TwitchCraftClient.LOGGER.info("YouTube: liveBroadcasts.transition → {}", target);
				if ("complete".equals(target)) {
					resetChatState("трансляция завершена командой");
					scheduleDiscovery(BROADCAST_DISCOVERY_MS);
				} else {
					// После перехода в live чат появляется не мгновенно — ищем его через несколько секунд.
					discoveryDelayMillis = BROADCAST_DISCOVERY_MS;
					scheduleDiscovery(5_000);
				}
				persistQuota(true);
			} catch (YoutubeApi.ApiException e) {
				persistQuota(true);
				if (e.redundant()) {
					Chat.info("YouTube: трансляция уже в состоянии «" + target + "».");
					return;
				}
				lastError = safeMessage(e);
				TwitchCraftClient.LOGGER.warn("YouTube transition {}: HTTP {} {}: {}", target, e.status(), e.reason(), e.getMessage());
				Chat.error("YouTube: не удалось перевести эфир в «" + target + "»: " + safeMessage(e));
			} catch (Exception e) {
				lastError = safeMessage(e);
				Chat.error("YouTube: ошибка управления эфиром: " + safeMessage(e));
			}
		});
	}

	/** Сменить заголовок трансляции (liveBroadcasts.update отправляет ресурс целиком). */
	public void setTitle(String title) {
		if (!controlAllowed(true)) return;
		String cleaned = cleanTitle(title);
		if (cleaned.isBlank()) {
			Chat.error("Пустой заголовок: /twitch youtube title <текст>");
			return;
		}
		mod.worker().execute(() -> {
			try {
				String id = broadcastForControl();
				if (id.isBlank()) {
					Chat.warn("YouTube: трансляция не найдена — заголовок не изменён.");
					return;
				}
				api.updateBroadcastTitle(id, cleaned);
				broadcastTitle = cleaned;
				Chat.success("YouTube: заголовок трансляции обновлён: " + cleaned);
				persistQuota(true);
			} catch (YoutubeApi.ApiException e) {
				persistQuota(true);
				lastError = safeMessage(e);
				TwitchCraftClient.LOGGER.warn("YouTube liveBroadcasts.update: HTTP {} {}: {}", e.status(), e.reason(), e.getMessage());
				Chat.error("YouTube: не удалось сменить заголовок: " + safeMessage(e));
			} catch (Exception e) {
				lastError = safeMessage(e);
				Chat.error("YouTube: ошибка смены заголовка: " + safeMessage(e));
			}
		});
	}

	/**
	 * Тайм-аут или бан зрителя.
	 *
	 * @param who     ник, channelId или часть ника (из числа тех, кого мод видел в этом чате)
	 * @param seconds длительность; 0 или меньше — постоянный бан
	 */
	public void ban(String who, int seconds) {
		if (!controlAllowed(true)) return;
		String chatId = liveChatId;
		if (chatId.isBlank()) {
			Chat.warn("YouTube: чат не подключён — сначала /twitch youtube connect.");
			return;
		}
		Participant target;
		try {
			target = findParticipant(who);
		} catch (IllegalArgumentException e) {
			Chat.warn("YouTube: " + e.getMessage());
			return;
		}
		if (target == null || target.channelId == null || target.channelId.isBlank()) {
			Chat.warn("YouTube: зритель «" + who + "» не найден в текущем чате (мод помнит "
					+ participantsCount() + " участников этой трансляции).");
			return;
		}
		if (target.owner || target.channelId.equals(channelId)) {
			Chat.warn("YouTube: это владелец канала — забанить нельзя.");
			return;
		}
		String bannedId = target.channelId;
		String name = target.displayName;
		int duration = seconds;
		mod.worker().execute(() -> {
			try {
				JsonObject result = api.banUser(chatId, bannedId, duration);
				String banId = YoutubeApi.str(result, "id");
				if (!banId.isBlank()) {
					synchronized (participantLock) {
						bans.put(banKey(name, bannedId), new Ban(banId, System.currentTimeMillis()));
					}
				}
				Chat.success("YouTube: " + name + (duration > 0
						? " — тайм-аут " + GameStats.formatDuration(duration * 1000L)
						+ " (разбан: /twitch youtube unban " + name + ")"
						: " — заблокирован в чате навсегда"));
				persistQuota(true);
			} catch (YoutubeApi.ApiException e) {
				persistQuota(true);
				lastError = safeMessage(e);
				TwitchCraftClient.LOGGER.warn("YouTube liveChatBans.insert: HTTP {} {}: {}", e.status(), e.reason(), e.getMessage());
				Chat.error("YouTube: не удалось забанить " + name + ": " + safeMessage(e));
			} catch (Exception e) {
				lastError = safeMessage(e);
				Chat.error("YouTube: ошибка бана: " + safeMessage(e));
			}
		});
	}

	/**
	 * Снять бан, созданный модом. Google не отдаёт список банов, поэтому разбан возможен
	 * только для тех, кого забанил сам мод в этом сеансе (или по явному id бана).
	 */
	public void unban(String who) {
		if (!controlAllowed(true)) return;
		String query = who == null ? "" : who.trim();
		if (query.isBlank()) {
			Chat.error("Укажи зрителя или id бана: /twitch youtube unban <ник|id>");
			return;
		}
		String banId = "";
		String name = query;
		synchronized (participantLock) {
			activeBanCount();
			for (java.util.Iterator<Map.Entry<String, Ban>> iterator = bans.entrySet().iterator(); iterator.hasNext(); ) {
				Map.Entry<String, Ban> entry = iterator.next();
				if (entry.getKey().equalsIgnoreCase(query) || entry.getValue().id().equalsIgnoreCase(query)) {
					banId = entry.getValue().id();
					iterator.remove();
					break;
				}
			}
		}
		if (banId.isBlank()) {
			Participant target = null;
			try {
				target = findParticipant(query);
			} catch (IllegalArgumentException e) {
				Chat.warn("YouTube: " + e.getMessage());
				return;
			}
			if (target != null) {
				name = target.displayName;
				synchronized (participantLock) {
					Ban ban = bans.remove(banKey(name, target.channelId));
					if (ban != null) banId = ban.id();
				}
			}
		}
		if (banId.isBlank()) {
			// Возможно, это сам id бана (Google выдаёт его в ответе liveChatBans.insert).
			banId = query;
		}
		String finalBanId = banId;
		String finalName = name;
		mod.worker().execute(() -> {
			try {
				api.unban(finalBanId);
				Chat.success("YouTube: бан снят" + (finalName.isBlank() ? "" : " (" + finalName + ")") + ".");
				persistQuota(true);
			} catch (YoutubeApi.ApiException e) {
				persistQuota(true);
				lastError = safeMessage(e);
				TwitchCraftClient.LOGGER.warn("YouTube liveChatBans.delete: HTTP {} {}: {}", e.status(), e.reason(), e.getMessage());
				Chat.error("YouTube: не удалось снять бан: " + safeMessage(e));
			} catch (Exception e) {
				lastError = safeMessage(e);
				Chat.error("YouTube: ошибка разбана: " + safeMessage(e));
			}
		});
	}

	/**
	 * Удалить сообщение: по нику зрителя (его последнее сообщение в этом чате) или по id сообщения.
	 */
	public void deleteMessage(String who) {
		if (!controlAllowed(true)) return;
		String query = who == null ? "" : who.trim();
		if (query.isBlank()) {
			Chat.error("Укажи зрителя или id сообщения: /twitch youtube delete <ник|id>");
			return;
		}
		String messageId = "";
		String name = query;
		try {
			Participant target = findParticipant(query);
			if (target != null && target.lastMessageId != null && !target.lastMessageId.isBlank()) {
				messageId = target.lastMessageId;
				name = target.displayName;
			}
		} catch (IllegalArgumentException e) {
			Chat.warn("YouTube: " + e.getMessage());
			return;
		}
		if (messageId.isBlank()) {
			messageId = query; // считаем, что передали id сообщения
		}
		String finalMessageId = messageId;
		String finalName = name;
		mod.worker().execute(() -> {
			try {
				api.deleteMessage(finalMessageId);
				Chat.success("YouTube: сообщение удалено" + (finalName.isBlank() ? "" : " (" + finalName + ")") + ".");
				persistQuota(true);
			} catch (YoutubeApi.ApiException e) {
				persistQuota(true);
				lastError = safeMessage(e);
				TwitchCraftClient.LOGGER.warn("YouTube liveChatMessages.delete: HTTP {} {}: {}", e.status(), e.reason(), e.getMessage());
				Chat.error("YouTube: не удалось удалить сообщение: " + safeMessage(e));
			} catch (Exception e) {
				lastError = safeMessage(e);
				Chat.error("YouTube: ошибка удаления сообщения: " + safeMessage(e));
			}
		});
	}

	/**
	 * Трансляция для управления: сначала активная (её мод уже нашёл), иначе ближайшая запланированная.
	 * Поиск стоит 1 единицу квоты, поэтому результат запоминаем.
	 */
	private String broadcastForControl() throws java.io.IOException, InterruptedException {
		String current = broadcastId;
		if (!current.isBlank()) return current;
		JsonArray upcoming = api.broadcasts("upcoming");
		String best = "";
		long bestStart = Long.MAX_VALUE;
		for (var element : upcoming) {
			if (!element.isJsonObject()) continue;
			JsonObject candidate = element.getAsJsonObject();
			String id = YoutubeApi.str(candidate, "id");
			if (id.isBlank()) continue;
			JsonObject snippet = child(candidate, "snippet");
			long scheduled = parseIso8601(YoutubeApi.str(snippet, "scheduledStartTime"));
			if (scheduled > 0 && scheduled < bestStart) {
				bestStart = scheduled;
				best = id;
			} else if (best.isBlank()) {
				best = id;
			}
		}
		if (!best.isBlank()) {
			broadcastId = best;
		}
		return best;
	}

	private static String banKey(String name, String bannedId) {
		return (name == null ? "" : name.toLowerCase(Locale.ROOT)) + "|" + (bannedId == null ? "" : bannedId);
	}

	private static String cleanTitle(String title) {
		if (title == null) return "";
		String cleaned = title.replaceAll("§[0-9a-fk-orA-FK-OR]", "")
				.replace("§", "")
				.replaceAll("\\p{Cntrl}", " ")
				.replaceAll("\\s+", " ")
				.trim();
		if (cleaned.length() > MAX_TITLE_LENGTH) cleaned = cleaned.substring(0, MAX_TITLE_LENGTH).trim();
		return cleaned;
	}

	// ---------- Outgoing chat ----------

	/** Send a bot message (game announcements, timers, clip links, or user command). */
	public void send(String text, boolean verbose) {
		String message = cleanMessage(text);
		if (message.isBlank()) return;
		if (!canSend()) {
			if (verbose) Chat.warn("YouTube-чат недоступен: проверь вход, эфир, модуль и право youtube.force-ssl.");
			else warnWriteUnavailable();
			return;
		}
		synchronized (sendLock) {
			if (sendQueue.size() >= MAX_SEND_QUEUE) {
				TwitchCraftClient.LOGGER.warn("Очередь сообщений YouTube переполнена, сообщение отброшено");
				if (verbose) Chat.warn("Очередь сообщений YouTube переполнена.");
				return;
			}
			sendQueue.addLast(new Outgoing(message, verbose, 0));
			if (sendDraining) return;
			sendDraining = true;
		}
		scheduleSend(0);
	}

	/** Reply to a viewer only if the YouTube replies setting is enabled. */
	public void reply(String text) {
		if (!mod.config().youtube.replies) return;
		send(text, false);
	}

	private boolean canSend() {
		return mod.isModuleEnabled(Module.YOUTUBE_LIVE) && isActive() && mod.youtubeStore().canWriteChat();
	}

	private void warnWriteUnavailable() {
		if (!mod.youtubeStore().canWriteChat() && !warnedWriteScope) {
			warnedWriteScope = true;
			Chat.warn("YouTube: для сообщений нужен scope youtube.force-ssl. Войди заново: /twitch youtube logout → /twitch youtube login");
		}
	}

	private void scheduleSend(long delayMillis) {
		try {
			mod.scheduler().schedule(() -> {
				Outgoing next;
				String chatId;
				synchronized (sendLock) {
					if (!sendDraining) return;
					if (sendQueue.isEmpty()) {
						sendDraining = false;
						return;
					}
					if (!canSend()) {
						sendQueue.clear();
						sendDraining = false;
						return;
					}
					long wait = nextSendAt - System.currentTimeMillis();
					if (wait > 0) {
						mod.scheduler().schedule(() -> scheduleSend(0), wait, TimeUnit.MILLISECONDS);
						return;
					}
					next = sendQueue.pollFirst();
					chatId = liveChatId;
				}
				try {
					mod.worker().execute(() -> sendOne(next, chatId));
				} catch (Exception e) {
					finishSend(next, false);
				}
			}, Math.max(0, delayMillis), TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			synchronized (sendLock) {
				sendDraining = false;
			}
		}
	}

	private void sendOne(Outgoing outgoing, String chatId) {
		boolean success = false;
		try {
			if (chatId == null || chatId.isBlank() || !chatId.equals(liveChatId)) {
				throw new YoutubeApi.ApiException(404, "liveChatNotFound", "активный YouTube live chat изменился");
			}
			api.insertMessage(chatId, outgoing.text());
			success = true;
			nextSendAt = System.currentTimeMillis() + SEND_INTERVAL_MS;
			rememberSent(outgoing.text());
		} catch (YoutubeApi.ApiException e) {
			TwitchCraftClient.LOGGER.warn("Не удалось отправить сообщение в YouTube Live: {}", e.getMessage());
			if (e.rateLimited() && outgoing.attempts() < 3 && canSend()) {
				Outgoing retry = new Outgoing(outgoing.text(), outgoing.verbose(), outgoing.attempts() + 1);
				synchronized (sendLock) {
					sendQueue.addFirst(retry);
				}
				nextSendAt = System.currentTimeMillis() + Math.max(10_000, e.retryAfterMillis());
			} else if (outgoing.verbose()) {
				Chat.warn("Не удалось отправить в YouTube: " + safeMessage(e));
			}
		} catch (Exception e) {
			TwitchCraftClient.LOGGER.warn("Ошибка отправки сообщения в YouTube: {}", e.toString());
			if (outgoing.verbose()) Chat.warn("Ошибка отправки в YouTube: " + safeMessage(e));
		} finally {
			persistQuota(false);
			finishSend(outgoing, success);
		}
	}

	private void finishSend(Outgoing sent, boolean success) {
		long delay;
		synchronized (sendLock) {
			if (!sendDraining) return;
			if (sendQueue.isEmpty()) {
				sendDraining = false;
				return;
			}
			delay = Math.max(0, nextSendAt - System.currentTimeMillis());
		}
		scheduleSend(delay);
	}

	private static String cleanMessage(String text) {
		if (text == null) return "";
		String cleaned = text.replaceAll("§[0-9a-fk-orA-FK-OR]", "")
				.replaceAll("&([0-9a-fk-orA-FK-OR])", "")
				.replace("§", "")
				.replaceAll("\\p{Cntrl}", " ").trim();
		if (cleaned.length() > MAX_MESSAGE_LENGTH) cleaned = cleaned.substring(0, MAX_MESSAGE_LENGTH).trim();
		if (cleaned.startsWith("/") || cleaned.startsWith(".")) cleaned = " " + cleaned;
		return cleaned;
	}

	private void rememberSent(String text) {
		synchronized (recentlySent) {
			recentlySent.addLast(text.trim());
			while (recentlySent.size() > RECENTLY_SENT_LIMIT) recentlySent.pollFirst();
		}
	}

	public boolean wasSentByUs(String text) {
		if (text == null) return false;
		synchronized (recentlySent) {
			return recentlySent.contains(text.trim());
		}
	}

	/** Зритель чата, которого мод видел в этой трансляции (нужен для модерации по нику). */
	private static final class Participant {
		private final String key;
		private String channelId = "";
		private String displayName = "";
		private String lastMessageId = "";
		private boolean moderator;
		private boolean owner;
		private long lastSeenAt;
		private long lastMessageAt;

		private Participant(String key) {
			this.key = key;
		}
	}

	private record Ban(String id, long at) {
	}

	private record Outgoing(String text, boolean verbose, int attempts) {
	}

	private static JsonObject child(JsonObject object, String key) {
		return object != null && object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : new JsonObject();
	}

	private static JsonObject object(JsonObject object, String key) {
		return object != null && object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : null;
	}

	private static JsonArray array(JsonObject object, String key) {
		return object != null && object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : new JsonArray();
	}

	private static boolean bool(JsonObject object, String key) {
		try {
			return object != null && object.has(key) && !object.get(key).isJsonNull() && object.get(key).getAsBoolean();
		} catch (Exception ignored) {
			return false;
		}
	}

	private static long longValue(JsonObject object, String key, long fallback) {
		try {
			return object != null && object.has(key) ? object.get(key).getAsLong() : fallback;
		} catch (Exception e) {
			return fallback;
		}
	}

	/** «2026-10-07T18:00:00Z» → миллисекунды (0 — не разобрано). */
	static long parseIso8601(String value) {
		if (value == null || value.isBlank()) return 0;
		try {
			return java.time.Instant.parse(value.trim()).toEpochMilli();
		} catch (Exception ignored) {
			return 0;
		}
	}

	private static String safeMessage(Throwable e) {
		String message = e.getMessage();
		return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
	}
}
