package dev.dedworkshop.twitchcraft.youtube;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.donations.LocalCallbackServer;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import dev.dedworkshop.twitchcraft.util.Chat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** YouTube Live chat polling, OAuth login, event dispatch, and outgoing chat queue. */
public class YoutubeLive {
	private static final long LOGIN_TIMEOUT_MINUTES = 10;
	private static final long BROADCAST_DISCOVERY_MS = 30_000;
	private static final long DEFAULT_POLL_MS = 5_000;
	private static final long SEND_INTERVAL_MS = 2_000;
	private static final int MAX_SEND_QUEUE = 20;
	private static final int MAX_MESSAGE_LENGTH = 200;
	private static final int RECENTLY_SENT_LIMIT = 30;

	private final TwitchCraftClient mod;
	private final YoutubeApi api;
	private final AtomicBoolean pollInFlight = new AtomicBoolean();
	private final Deque<Outgoing> sendQueue = new ArrayDeque<>();
	private final Deque<String> recentlySent = new ArrayDeque<>();
	private final Set<String> seenMessageIds = new LinkedHashSet<>();
	private final Object sendLock = new Object();

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
	private volatile String liveChatId = "";
	private volatile String nextPageToken = "";
	private volatile long pollingIntervalMillis = DEFAULT_POLL_MS;
	private volatile boolean initialHistorySkipped;
	private volatile int eventsReceived;
	private volatile int chatReceived;
	private volatile int unknownTypes;
	private volatile long lastPollAt;

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
	}

	public YoutubeApi api() {
		return api;
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

	public String liveChatId() {
		return liveChatId;
	}

	public int eventsReceived() {
		return eventsReceived;
	}

	public int chatReceived() {
		return chatReceived;
	}

	public long pollingIntervalMillis() {
		return pollingIntervalMillis;
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
					+ (chatReceived > 0 ? " §7сообщений: " + chatReceived : "")
					+ " §8(опрос " + Math.max(1, pollingIntervalMillis / 1000) + " с)";
		}
		return "§eпоиск активного эфира..." + (statusDetail.isBlank() ? "" : " §7(" + statusDetail + ")") + who;
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
				}
				store.replaceTokens(access, YoutubeApi.str(json, "refresh_token"), expiresIn, scopes);
				store.channelId = foundChannelId;
				store.channelTitle = foundChannelTitle;
				store.save();
				tokenRejected = false;
				channelId = foundChannelId;
				channelTitle = foundChannelTitle;
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
		if (loginReconnectRequested) disconnectDuringLogin = false;
		// A discovery request may already be running on the worker while neither scheduled task exists.
		// Treat a wanted connection as in progress so config refreshes / repeated button clicks stay idempotent.
		if (wantConnected) return;
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
		if (mod.isModuleEnabled(Module.YOUTUBE_LIVE) && isLoggedIn() && !manuallyDisconnected) {
			connect(false);
		} else if (!mod.isModuleEnabled(Module.YOUTUBE_LIVE)) {
			stopConnection("модуль выключен");
		}
	}

	public synchronized void shutdown() {
		cancelLogin();
		stopConnection("завершение игры");
	}

	private synchronized void stopConnection(String reason) {
		connectionGeneration++;
		wantConnected = false;
		statusDetail = reason == null ? "" : reason;
		liveChatId = "";
		broadcastId = "";
		nextPageToken = "";
		initialHistorySkipped = false;
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
		try {
			YoutubeStore store = mod.youtubeStore();
			if (!store.hasTokens()) {
				statusDetail = "нет OAuth-токена";
				return;
			}
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
			store.save();

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
				statusDetail = broadcasts.isEmpty() ? "нет активной трансляции" : "у трансляции нет live chat";
				scheduleDiscovery(BROADCAST_DISCOVERY_MS);
				return;
			}
			if (!foundChatId.equals(liveChatId)) {
				liveChatId = foundChatId;
				broadcastId = foundBroadcastId;
				nextPageToken = "";
				initialHistorySkipped = false;
				pollingIntervalMillis = DEFAULT_POLL_MS;
				synchronized (seenMessageIds) {
					seenMessageIds.clear();
				}
			}
			statusDetail = "подключено к трансляции";
			schedulePoll(0);
		} catch (YoutubeApi.ApiException e) {
			if (generation == connectionGeneration) handleApiFailure("поиск трансляции", e);
		} catch (Exception e) {
			if (generation != connectionGeneration) return;
			TwitchCraftClient.LOGGER.warn("YouTube: не удалось найти активную трансляцию: {}", e.toString());
			statusDetail = safeMessage(e);
			scheduleDiscovery(BROADCAST_DISCOVERY_MS);
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
			String currentChatId = liveChatId;
			String page = initialHistorySkipped ? nextPageToken : "";
			JsonObject result = api.listMessages(currentChatId, page);
			if (!wantConnected || generation != connectionGeneration || !currentChatId.equals(liveChatId)) return;
			lastPollAt = System.currentTimeMillis();
			long interval = longValue(result, "pollingIntervalMillis", DEFAULT_POLL_MS);
			pollingIntervalMillis = Math.max(1000, interval);
			String next = YoutubeApi.str(result, "nextPageToken");
			if (!initialHistorySkipped) {
				// The first response deliberately contains recent history. Keep its continuation token but
				// do not replay old chat messages or commands on startup / stream discovery.
				for (var element : array(result, "items")) {
					if (element.isJsonObject()) rememberMessageId(YoutubeApi.str(element.getAsJsonObject(), "id"));
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
					TwitchEvent event = YoutubeEventMapper.fromMessage(item);
					if (event == null) {
						String type = YoutubeEventMapper.messageType(item);
						if (mod.config().youtube.debugEvents) {
							TwitchCraftClient.LOGGER.info("YouTube live chat: пропущено неподдерживаемое событие type={}", type);
						} else {
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
			if (mod.config().youtube.debugEvents) {
				TwitchCraftClient.LOGGER.debug("YouTube live chat poll: {} items, next token {}, interval {} ms",
						array(result, "items").size(), nextPageToken.isBlank() ? "<empty>" : "<set>", pollingIntervalMillis);
			}
			if (wantConnected && currentChatId.equals(liveChatId)) schedulePoll(pollingIntervalMillis);
		} catch (YoutubeApi.ApiException e) {
			if (generation == connectionGeneration) handlePollFailure(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			if (generation == connectionGeneration) {
				TwitchCraftClient.LOGGER.warn("YouTube: ошибка опроса live chat: {}", e.toString());
				statusDetail = "ошибка опроса: " + safeMessage(e);
				if (wantConnected) schedulePoll(Math.max(pollingIntervalMillis, DEFAULT_POLL_MS));
			}
		} finally {
			if (generation == connectionGeneration) pollInFlight.set(false);
		}
	}

	private boolean rememberMessageId(String id) {
		if (id == null || id.isBlank()) return true;
		synchronized (seenMessageIds) {
			if (!seenMessageIds.add(id)) return false;
			while (seenMessageIds.size() > 1000) {
				seenMessageIds.remove(seenMessageIds.iterator().next());
			}
			return true;
		}
	}

	private void handlePollFailure(YoutubeApi.ApiException error) {
		if (error.chatEnded()) {
			TwitchCraftClient.LOGGER.info("YouTube: live chat завершён; ищу следующую трансляцию");
			liveChatId = "";
			broadcastId = "";
			nextPageToken = "";
			initialHistorySkipped = false;
			statusDetail = "трансляция завершилась";
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
			statusDetail = "квота YouTube API исчерпана";
			TwitchCraftClient.LOGGER.error("YouTube Data API quotaExceeded: {}", error.getMessage());
			schedulePoll(Math.max(60_000, pollingIntervalMillis));
			return;
		}
		if (error.rateLimited()) {
			statusDetail = "YouTube ограничил частоту запросов";
			TwitchCraftClient.LOGGER.warn("YouTube rate limit: {}", error.getMessage());
			schedulePoll(Math.max(10_000, pollingIntervalMillis));
			return;
		}
		if (error.reason().equalsIgnoreCase("pageTokenInvalid")) {
			TwitchCraftClient.LOGGER.warn("YouTube: pageToken больше не действителен; повторно пропускаю текущую историю чата");
			initialHistorySkipped = false;
			nextPageToken = "";
			schedulePoll(Math.max(pollingIntervalMillis, DEFAULT_POLL_MS));
			return;
		}
		statusDetail = safeMessage(error);
		TwitchCraftClient.LOGGER.warn("YouTube live chat API: HTTP {} {}: {}", error.status(), error.reason(), error.getMessage());
		schedulePoll(Math.max(pollingIntervalMillis, DEFAULT_POLL_MS));
	}

	private void handleApiFailure(String operation, YoutubeApi.ApiException error) {
		if (error.unauthorized()) {
			tokenRejected = true;
			statusDetail = "требуется повторный вход";
			Chat.warn("YouTube: OAuth недействителен или не хватает прав. Войди заново: /twitch youtube login");
			stopConnection("требуется повторный вход");
		} else if (error.quotaExceeded()) {
			statusDetail = "квота YouTube API исчерпана";
			TwitchCraftClient.LOGGER.error("YouTube {}: квота API исчерпана: {}", operation, error.getMessage());
			scheduleDiscovery(60_000);
		} else {
			statusDetail = safeMessage(error);
			TwitchCraftClient.LOGGER.warn("YouTube {}: HTTP {} {}: {}", operation, error.status(), error.reason(), error.getMessage());
			scheduleDiscovery(error.rateLimited() ? 10_000 : BROADCAST_DISCOVERY_MS);
		}
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
				nextSendAt = System.currentTimeMillis() + 10_000;
			} else if (outgoing.verbose()) {
				Chat.warn("Не удалось отправить в YouTube: " + safeMessage(e));
			}
		} catch (Exception e) {
			TwitchCraftClient.LOGGER.warn("Ошибка отправки сообщения в YouTube: {}", e.toString());
			if (outgoing.verbose()) Chat.warn("Ошибка отправки в YouTube: " + safeMessage(e));
		} finally {
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

	private record Outgoing(String text, boolean verbose, int attempts) {
	}

	private static JsonObject child(JsonObject object, String key) {
		return object != null && object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : new JsonObject();
	}

	private static JsonArray array(JsonObject object, String key) {
		return object != null && object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : new JsonArray();
	}

	private static long longValue(JsonObject object, String key, long fallback) {
		try {
			return object != null && object.has(key) ? object.get(key).getAsLong() : fallback;
		} catch (Exception e) {
			return fallback;
		}
	}

	private static String safeMessage(Throwable e) {
		String message = e.getMessage();
		return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
	}
}
