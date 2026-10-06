package dev.dedworkshop.twitchcraft.donations;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import dev.dedworkshop.twitchcraft.twitch.TwitchHttp;
import dev.dedworkshop.twitchcraft.util.Chat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * DonationAlerts: вход через OAuth (implicit, только Client ID), донаты в реальном времени через Centrifugo.
 *
 * Схема: GET /user/oauth → socket_connection_token и id → WebSocket connect → POST /centrifuge/subscribe
 * (канал $alerts:donation_ID) → subscribe → публикации с донатами.
 */
public class DonationAlertsClient implements CentrifugoClient.Handler {
	/** Адреса можно переопределить системными свойствами (нужно только для тестов). */
	static final String API = System.getProperty("twitchcraft.daApiUrl", "https://www.donationalerts.com/api/v1");
	static final String OAUTH = System.getProperty("twitchcraft.daOauthUrl", "https://www.donationalerts.com/oauth/authorize");
	static final String WS = System.getProperty("twitchcraft.daWsUrl", "wss://centrifugo.donationalerts.com/connection/websocket");
	/** oauth-donation-index нужен только для «догонки» донатов, пропущенных за время обрыва связи (без него — просто не догоняем). */
	public static final String SCOPES = "oauth-user-show oauth-donation-subscribe oauth-donation-index";
	public static final String CALLBACK_PATH = "/da";
	private static final long LOGIN_TIMEOUT_MINUTES = 10;
	private static final int SEEN_LIMIT = 500;

	private final TwitchCraftClient mod;
	private final Set<String> seen = new LinkedHashSet<>();
	private volatile CentrifugoClient client;
	private volatile boolean wantConnected;
	private volatile boolean subscribed;
	private volatile String statusDetail = "";
	private volatile boolean tokenRejected;
	private volatile int reconnectAttempts;
	private volatile long lastDonationAt;
	private volatile int donationsReceived;
	/** Наибольший id доната, полученного в этой игровой сессии (для догонки после обрыва; не сохраняется). */
	private volatile long lastDonationId;
	/** id клиента Centrifugo из ответа на connect — нужен для выдачи и продления токенов канала. */
	private volatile String centrifugoClientId = "";
	/** Сообщение «подключено» показываем один раз; тихие переподключения идут только в лог. */
	private volatile boolean announcedConnected;
	private volatile long disconnectedAt;
	private volatile boolean catchUpScopeMissing;
	private volatile LocalCallbackServer loginServer;
	/** Одноразовый OAuth state текущей попытки входа (защита от подмены токена). */
	private volatile String loginState;
	private ScheduledFuture<?> reconnectTask;
	private ScheduledFuture<?> loginTimeout;
	private ScheduledFuture<?> watchdog;

	public DonationAlertsClient(TwitchCraftClient mod) {
		this.mod = mod;
	}

	// ---------- Состояние ----------

	public boolean isConfigured() {
		return mod.donationStore().hasDonationAlerts();
	}

	public boolean isActive() {
		return wantConnected && subscribed;
	}

	public boolean isLoginInProgress() {
		LocalCallbackServer server = loginServer;
		return server != null && server.isRunning();
	}

	public int donationsReceived() {
		return donationsReceived;
	}

	public String statusText() {
		DonationStore store = mod.donationStore();
		if (!store.hasDonationAlerts()) {
			return isLoginInProgress() ? "§eжду вход в браузере" : "§7не подключён (нет входа)";
		}
		String who = store.daUserName == null || store.daUserName.isBlank() ? "" : " §7(" + store.daUserName + ")";
		if (tokenRejected) {
			return "§cтокен недействителен — войди заново" + who;
		}
		if (!wantConnected) {
			return "§7отключено" + who;
		}
		if (subscribed) {
			return "§aподключено" + who + (donationsReceived > 0 ? " §7донатов: " + donationsReceived : "");
		}
		return "§eподключение..." + (statusDetail.isBlank() ? "" : " §7(" + statusDetail + ")") + who;
	}

	// ---------- Вход ----------

	/** Ссылка для входа (OAuth implicit). Пустая строка, если Client ID пустой или не состоит из цифр. */
	public String loginUrl() {
		String clientId = mod.config().donations.donationAlertsClientId;
		if (!isNumericClientId(clientId)) {
			return "";
		}
		String query = "client_id=" + encode(clientId.trim())
				+ "&redirect_uri=" + encode(redirectUri())
				+ "&response_type=token&scope=" + encode(SCOPES)
				+ (loginState == null ? "" : "&state=" + encode(loginState));
		// Keep the separator explicit: a malformed authorize URL can be reported by DonationAlerts as invalid_client.
		int queryStart = OAUTH.indexOf('?');
		String separator = queryStart < 0 ? "?" : (OAUTH.endsWith("?") || OAUTH.endsWith("&") ? "" : "&");
		return OAUTH + separator + query;
	}

	private static boolean isNumericClientId(String clientId) {
		if (clientId == null || clientId.isBlank()) {
			return false;
		}
		String value = clientId.trim();
		for (int i = 0; i < value.length(); i++) {
			if (value.charAt(i) < '0' || value.charAt(i) > '9') {
				return false;
			}
		}
		return true;
	}

	public String redirectUri() {
		return "http://localhost:" + mod.config().donations.callbackPort + CALLBACK_PATH;
	}

	/**
	 * Запускает вход: поднимает локальный сервер для приёма токена и возвращает ссылку, которую нужно открыть.
	 *
	 * @return ссылка или null, если вход начать нельзя (ошибка уже показана в чате)
	 */
	public synchronized String beginLogin() {
		cancelLogin();
		String clientId = mod.config().donations.donationAlertsClientId;
		if (clientId == null || clientId.isBlank()) {
			Chat.error("Не указан Client ID приложения DonationAlerts. Создай приложение на donationalerts.com/application/clients "
					+ "(Redirect URI: " + redirectUri() + ") и введи: /twitch donations da client <ID>");
			return null;
		}
		if (!isNumericClientId(clientId)) {
			Chat.error("Client ID DonationAlerts должен содержать только цифры. Вставь именно ID приложения, не Client Secret; "
					+ "Redirect URI: " + redirectUri());
			return null;
		}
		loginState = LocalCallbackServer.newState();
		String url = loginUrl();
		if (url.isEmpty()) {
			loginState = null;
			Chat.error("Не удалось построить ссылку OAuth DonationAlerts. Проверь Client ID и настройки приложения.");
			return null;
		}
		String expectedState = loginState;
		LocalCallbackServer server = new LocalCallbackServer(mod.config().donations.callbackPort, CALLBACK_PATH, expectedState,
				params -> onLoginToken(params, expectedState), params -> onLoginError(params, expectedState));
		try {
			server.start();
		} catch (Exception e) {
			loginState = null;
			Chat.error("Не удалось открыть локальный порт " + mod.config().donations.callbackPort + " для входа: " + e.getMessage()
					+ ". Поменяй порт в настройках донатов (callbackPort) и Redirect URI приложения.");
			return null;
		}
		loginServer = server;
		loginTimeout = mod.scheduler().schedule(() -> expireLogin(server, expectedState), LOGIN_TIMEOUT_MINUTES, TimeUnit.MINUTES);
		return url;
	}

	public synchronized void cancelLogin() {
		LocalCallbackServer server = loginServer;
		loginServer = null;
		loginState = null;
		if (server != null) {
			server.close();
		}
		if (loginTimeout != null) {
			loginTimeout.cancel(false);
			loginTimeout = null;
		}
	}

	private void onLoginToken(Map<String, String> params, String expectedState) {
		if (!finishLoginAttempt(expectedState)) {
			TwitchCraftClient.LOGGER.info("DonationAlerts: проигнорирован токен из отменённой или устаревшей попытки входа");
			return;
		}
		String token = params.get("access_token");
		mod.worker().execute(() -> {
			try {
				JsonObject user = fetchUser(token);
				if (user == null) {
					Chat.error("DonationAlerts выдал токен, но профиль получить не удалось. Попробуй войти ещё раз.");
					return;
				}
				DonationStore store = mod.donationStore();
				store.daAccessToken = token;
				store.daUserId = user.has("id") ? user.get("id").getAsString() : "";
				store.daUserName = DonationParser.str(user, "name");
				store.save();
				tokenRejected = false;
				Chat.success("DonationAlerts: вход выполнен как §d" + store.daUserName + "§r. Подключаюсь...");
				connect(true);
			} catch (Exception e) {
				TwitchCraftClient.LOGGER.error("DonationAlerts: ошибка завершения входа", e);
				Chat.error("DonationAlerts: ошибка входа: " + e.getMessage());
			}
		});
	}

	private void onLoginError(Map<String, String> params, String expectedState) {
		if (!finishLoginAttempt(expectedState)) {
			return;
		}
		String error = params.getOrDefault("error", "oauth_error");
		if ("invalid_client".equals(error)) {
			Chat.error("DonationAlerts отклонил приложение (invalid_client). Проверь числовой Client ID (не Client Secret), "
					+ "что приложение не удалено, и точный Redirect URI: " + redirectUri());
			return;
		}
		String description = params.getOrDefault("error_description", error).replace('\r', ' ').replace('\n', ' ').trim();
		if (description.length() > 180) {
			description = description.substring(0, 180) + "…";
		}
		Chat.error("DonationAlerts: вход отклонён (" + error + "): " + description + ". Попробуй /twitch donations da login.");
	}

	private synchronized boolean finishLoginAttempt(String expectedState) {
		if (expectedState == null || !expectedState.equals(loginState)) {
			return false;
		}
		// Порт освобождаем сразу: LocalCallbackServer закрывает сокет сам, но уже после того,
		// как браузер получил ответ. Если пользователь (или тест) начнёт вход повторно в этот момент,
		// новый сервер не сможет занять порт и вход упадёт с «Address already in use».
		LocalCallbackServer server = loginServer;
		loginServer = null;
		loginState = null;
		if (loginTimeout != null) {
			loginTimeout.cancel(false);
			loginTimeout = null;
		}
		if (server != null) {
			server.close();
		}
		return true;
	}

	private synchronized void expireLogin(LocalCallbackServer server, String expectedState) {
		if (loginServer != server || !expectedState.equals(loginState) || !server.isRunning()) {
			return;
		}
		server.close();
		loginServer = null;
		loginState = null;
		loginTimeout = null;
		Chat.warn("Вход в DonationAlerts отменён: за " + LOGIN_TIMEOUT_MINUTES + " минут токен не получен.");
	}

	public void logout() {
		cancelLogin();
		disconnect();
		mod.donationStore().clearDonationAlerts();
		tokenRejected = false;
		Chat.info("DonationAlerts: выход выполнен, токен удалён.");
	}

	// ---------- Подключение ----------

	public synchronized void connect(boolean verbose) {
		DonationStore store = mod.donationStore();
		if (!store.hasDonationAlerts()) {
			if (verbose) {
				Chat.warn("DonationAlerts: сначала войди — /twitch donations da login");
			}
			return;
		}
		wantConnected = true;
		tokenRejected = false;
		closeClient();
		subscribed = false;
		announcedConnected = false;
		disconnectedAt = 0;
		statusDetail = "запрашиваю профиль";
		startWatchdog();
		mod.worker().execute(() -> openSession(verbose));
	}

	public synchronized void disconnect() {
		wantConnected = false;
		subscribed = false;
		cancelReconnect();
		stopWatchdog();
		closeClient();
	}

	private void openSession(boolean verbose) {
		if (!wantConnected) {
			return;
		}
		DonationStore store = mod.donationStore();
		try {
			JsonObject user = fetchUser(store.daAccessToken);
			if (user == null) {
				return; // статус уже выставлен
			}
			String socketToken = DonationParser.str(user, "socket_connection_token");
			String id = user.has("id") ? user.get("id").getAsString() : store.daUserId;
			if (!id.isBlank() && !id.equals(store.daUserId)) {
				store.daUserId = id;
				store.daUserName = DonationParser.str(user, "name");
				store.save();
			}
			if (socketToken.isEmpty()) {
				statusDetail = "нет socket_connection_token";
				scheduleReconnect("профиль без токена сокета");
				return;
			}
			statusDetail = "открываю WebSocket";
			CentrifugoClient c = new CentrifugoClient(WS, this, mod.scheduler());
			client = c;
			c.connect(socketToken);
			if (verbose) {
				Chat.info("DonationAlerts: подключаюсь...");
			}
		} catch (Exception e) {
			TwitchCraftClient.LOGGER.warn("DonationAlerts: ошибка подключения", e);
			statusDetail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			scheduleReconnect("ошибка: " + statusDetail);
		}
	}

	/** Профиль пользователя; при 401 помечает токен недействительным и возвращает null. */
	private JsonObject fetchUser(String token) throws Exception {
		TwitchHttp.Response response = TwitchHttp.get(API + "/user/oauth", Map.of("Authorization", "Bearer " + token, "Accept", "application/json"));
		if (response.status() == 401 || response.status() == 403) {
			tokenRejected = true;
			wantConnected = false;
			statusDetail = "токен отклонён";
			Chat.error("DonationAlerts: токен недействителен (HTTP " + response.status() + "). Войди заново: /twitch donations da login");
			return null;
		}
		if (!response.ok()) {
			throw new IllegalStateException("HTTP " + response.status() + ": " + response.errorMessage());
		}
		JsonObject json = response.json();
		return json.has("data") && json.get("data").isJsonObject() ? json.getAsJsonObject("data") : null;
	}

	private void closeClient() {
		CentrifugoClient c = client;
		client = null;
		if (c != null) {
			c.close();
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
		TwitchCraftClient.LOGGER.info("DonationAlerts: {} — повтор через {} с", why, delay);
		reconnectTask = mod.scheduler().schedule(() -> {
			if (wantConnected) {
				closeClient();
				subscribed = false;
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
			CentrifugoClient c = client;
			if (wantConnected && subscribed && c != null && System.currentTimeMillis() - c.lastMessageAt() > 120_000) {
				TwitchCraftClient.LOGGER.warn("DonationAlerts: сервер молчит больше 2 минут — переподключаюсь");
				subscribed = false;
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

	// ---------- CentrifugoClient.Handler ----------

	@Override
	public void onConnected(String clientId) {
		centrifugoClientId = clientId == null ? "" : clientId;
		statusDetail = "подписываюсь на канал";
		mod.worker().execute(this::subscribeChannel);
	}

	private String channelName() {
		return "$alerts:donation_" + mod.donationStore().daUserId;
	}

	/** Берёт токен канала через REST и отправляет subscribe (первая подписка и переподписка после unsub). */
	private void subscribeChannel() {
		if (!wantConnected) {
			return;
		}
		String channel = channelName();
		try {
			String token = requestChannelToken(channel);
			if (token == null) {
				return; // токен DonationAlerts отклонён — статус уже выставлен
			}
			CentrifugoClient c = client;
			if (c != null) {
				c.subscribe(channel, token);
			}
		} catch (Exception e) {
			TwitchCraftClient.LOGGER.warn("DonationAlerts: ошибка подписки на канал", e);
			scheduleReconnect("подписка: " + e.getMessage());
		}
	}

	/**
	 * POST /centrifuge/subscribe — подписывает наш client id на приватный канал и возвращает токен канала.
	 *
	 * @return токен или null, если DonationAlerts отклонил наш access-токен (401)
	 */
	private String requestChannelToken(String channel) throws Exception {
		DonationStore store = mod.donationStore();
		JsonObject body = new JsonObject();
		JsonArray channels = new JsonArray();
		channels.add(channel);
		body.add("channels", channels);
		body.addProperty("client", centrifugoClientId);
		TwitchHttp.Response response = TwitchHttp.postJson(API + "/centrifuge/subscribe", body,
				Map.of("Authorization", "Bearer " + store.daAccessToken, "Accept", "application/json"));
		if (response.status() == 401) {
			tokenRejected = true;
			wantConnected = false;
			closeClient();
			Chat.error("DonationAlerts: токен отклонён при подписке. Войди заново: /twitch donations da login");
			return null;
		}
		if (!response.ok()) {
			throw new IllegalStateException("HTTP " + response.status() + ": " + response.errorMessage());
		}
		String token = "";
		JsonObject json = response.json();
		if (json.has("channels") && json.get("channels").isJsonArray()) {
			for (var element : json.getAsJsonArray("channels")) {
				JsonObject entry = element.getAsJsonObject();
				if (channel.equals(DonationParser.str(entry, "channel"))) {
					token = DonationParser.str(entry, "token");
				}
			}
		}
		if (token.isEmpty()) {
			throw new IllegalStateException("сервер не выдал токен канала");
		}
		return token;
	}

	@Override
	public void onRefreshNeeded() {
		mod.worker().execute(() -> {
			CentrifugoClient c = client;
			if (c == null || !c.isConnected() || !wantConnected) {
				return;
			}
			try {
				JsonObject user = fetchUser(mod.donationStore().daAccessToken);
				if (user == null) {
					closeClient(); // access-токен отклонён — статус выставлен в fetchUser
					return;
				}
				String token = DonationParser.str(user, "socket_connection_token");
				if (token.isEmpty()) {
					throw new IllegalStateException("профиль без socket_connection_token");
				}
				c.refresh(token);
				TwitchCraftClient.LOGGER.info("DonationAlerts: токен соединения продлён");
			} catch (Exception e) {
				// Не страшно: сервер закроет соединение по истечении токена, и мы переподключимся штатно
				TwitchCraftClient.LOGGER.warn("DonationAlerts: не удалось продлить токен соединения: {}", e.toString());
			}
		});
	}

	@Override
	public void onSubRefreshNeeded(String channel) {
		mod.worker().execute(() -> {
			CentrifugoClient c = client;
			if (c == null || !c.isConnected() || !wantConnected) {
				return;
			}
			try {
				String token = requestChannelToken(channel);
				if (token != null) {
					c.subRefresh(channel, token);
					TwitchCraftClient.LOGGER.info("DonationAlerts: подписка на канал продлена");
				}
			} catch (Exception e) {
				TwitchCraftClient.LOGGER.warn("DonationAlerts: не удалось продлить подписку: {}", e.toString());
			}
		});
	}

	@Override
	public void onUnsubscribed(String channel, boolean resubscribe) {
		subscribed = false;
		if (!wantConnected) {
			return;
		}
		noteDisconnected();
		statusDetail = "сервер снял подписку — переподписываюсь";
		TwitchCraftClient.LOGGER.info("DonationAlerts: сервер снял подписку с канала {} — переподписываюсь", channel);
		mod.worker().execute(this::subscribeChannel);
	}

	@Override
	public void onSubscribed(String channel) {
		subscribed = true;
		reconnectAttempts = 0;
		statusDetail = "";
		long gapMs = disconnectedAt > 0 ? System.currentTimeMillis() - disconnectedAt : 0;
		boolean needCatchUp = disconnectedAt > 0 && lastDonationId > 0;
		disconnectedAt = 0;
		if (!announcedConnected) {
			announcedConnected = true;
			Chat.success("DonationAlerts: §aподключено§r — донаты будут приходить в игру.");
		} else if (gapMs > 60_000) {
			Chat.info("DonationAlerts: соединение восстановлено (перерыв " + gapMs / 1000 + " с).");
		} else {
			TwitchCraftClient.LOGGER.info("DonationAlerts: переподключено за {} мс", gapMs);
		}
		if (needCatchUp) {
			mod.worker().execute(this::catchUp);
		}
	}

	@Override
	public void onPublication(String channel, JsonObject data) {
		try {
			TwitchEvent event = DonationParser.donationAlerts(data, mod.config().donations.currency);
			if (event != null) {
				deliver(event, false);
			}
		} catch (Exception e) {
			TwitchCraftClient.LOGGER.error("DonationAlerts: ошибка обработки доната {}", data, e);
		}
	}

	/** Общий путь доната (из сокета или из догонки): дедуп по id, счётчики, порог minAmount, событие в игру. */
	private void deliver(TwitchEvent event, boolean catchUp) {
		String id = event.rewardId();
		synchronized (seen) {
			if (!id.isEmpty()) {
				if (seen.contains(id)) {
					return;
				}
				seen.add(id);
				if (seen.size() > SEEN_LIMIT) {
					seen.remove(seen.iterator().next());
				}
			}
		}
		long numericId = parseId(id);
		if (numericId > lastDonationId) {
			lastDonationId = numericId;
		}
		donationsReceived++;
		lastDonationAt = System.currentTimeMillis();
		if (event.amount() < mod.config().donations.minAmount) {
			TwitchCraftClient.LOGGER.info("DonationAlerts: донат {} меньше minAmount — пропущен", event.shortText());
			return;
		}
		if (catchUp) {
			TwitchCraftClient.LOGGER.info("DonationAlerts: донат {} пришёл за время обрыва связи — выполняю", event.shortText());
		}
		mod.onTwitchEvent(event);
	}

	/**
	 * После переподключения проверяем через REST (GET /alerts/donations), не пришло ли что-то, пока сокет был мёртв.
	 * Берём только донаты новее последнего полученного в этой сессии, чтобы не проигрывать старую историю.
	 */
	private void catchUp() {
		if (catchUpScopeMissing || !wantConnected) {
			return;
		}
		long since = lastDonationId;
		try {
			TwitchHttp.Response response = TwitchHttp.get(API + "/alerts/donations",
					Map.of("Authorization", "Bearer " + mod.donationStore().daAccessToken, "Accept", "application/json"));
			if (response.status() == 401 || response.status() == 403) {
				catchUpScopeMissing = true;
				TwitchCraftClient.LOGGER.info("DonationAlerts: у токена нет права oauth-donation-index — донаты за время обрыва связи не догоняются. "
						+ "Чтобы включить: /twitch donations da logout, затем da login");
				return;
			}
			if (!response.ok()) {
				TwitchCraftClient.LOGGER.warn("DonationAlerts: не удалось проверить пропущенные донаты: HTTP {}", response.status());
				return;
			}
			JsonArray data = response.json().getAsJsonArray("data");
			if (data == null) {
				return;
			}
			List<TwitchEvent> missed = new ArrayList<>();
			for (var element : data) {
				if (!element.isJsonObject()) {
					continue;
				}
				TwitchEvent event = DonationParser.donationAlerts(element.getAsJsonObject(), mod.config().donations.currency);
				if (event == null) {
					continue;
				}
				if (parseId(event.rewardId()) > since && !isSeen(event.rewardId())) {
					missed.add(event);
				}
			}
			missed.sort(Comparator.comparingLong(e -> parseId(e.rewardId())));
			for (TwitchEvent event : missed) {
				deliver(event, true);
			}
		} catch (Exception e) {
			TwitchCraftClient.LOGGER.warn("DonationAlerts: не удалось проверить пропущенные донаты: {}", e.toString());
		}
	}

	private boolean isSeen(String id) {
		synchronized (seen) {
			return seen.contains(id);
		}
	}

	private static long parseId(String id) {
		try {
			return Long.parseLong(id.trim());
		} catch (Exception e) {
			return 0;
		}
	}

	private void noteDisconnected() {
		if (disconnectedAt == 0) {
			disconnectedAt = System.currentTimeMillis();
		}
	}

	@Override
	public void onClosed(String reason) {
		subscribed = false;
		noteDisconnected();
		scheduleReconnect(reason);
	}

	@Override
	public void onCommandError(int id, int code, String message) {
		TwitchCraftClient.LOGGER.warn("DonationAlerts: Centrifugo ответил ошибкой на команду {}: {} {}", id, code, message);
		subscribed = false;
		noteDisconnected();
		if (id == 1) {
			// токен сокета не принят: запросим новый через профиль (а если и профиль не отдаётся — токен отклонён)
			scheduleReconnect("сокет отклонил токен (" + code + ")");
		} else {
			scheduleReconnect("ошибка подписки (" + code + " " + message + ")");
		}
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
