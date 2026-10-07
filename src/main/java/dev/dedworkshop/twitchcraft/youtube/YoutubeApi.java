package dev.dedworkshop.twitchcraft.youtube;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.twitch.TwitchHttp;
import dev.dedworkshop.twitchcraft.twitch.TwitchHttp.Response;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** YouTube Data API v3 and Google OAuth 2.0 helpers (no extra runtime libraries). */
public class YoutubeApi {
	public static final String SCOPE_READ = "https://www.googleapis.com/auth/youtube.readonly";
	public static final String SCOPE_WRITE = "https://www.googleapis.com/auth/youtube.force-ssl";
	public static final String SCOPE_MANAGE = "https://www.googleapis.com/auth/youtube";
	public static final String SCOPES = SCOPE_READ + " " + SCOPE_WRITE;

	static final String API = trimSlash(System.getProperty("twitchcraft.youtubeApiUrl", "https://www.googleapis.com/youtube/v3"));
	static final String AUTHORIZE = System.getProperty("twitchcraft.youtubeAuthUrl", "https://accounts.google.com/o/oauth2/v2/auth");
	static final String TOKEN = System.getProperty("twitchcraft.youtubeTokenUrl", "https://oauth2.googleapis.com/token");
	static final String REVOKE = System.getProperty("twitchcraft.youtubeRevokeUrl", "https://oauth2.googleapis.com/revoke");

	private final TwitchCraftClient mod;
	private final Object refreshLock = new Object();
	/** Оценка дневного расхода квоты YouTube Data API (Google фактический расход не возвращает). */
	private final YoutubeQuota quota = new YoutubeQuota();

	public YoutubeApi(TwitchCraftClient mod) {
		this.mod = mod;
	}

	public YoutubeQuota quota() {
		return quota;
	}

	public static String newCodeVerifier() {
		byte[] bytes = new byte[32]; // 43 Base64URL characters, within RFC 7636's 43–128 range
		new SecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	public static String codeChallenge(String verifier) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		} catch (Exception e) {
			throw new IllegalStateException("SHA-256 недоступен", e);
		}
	}

	/** OAuth Authorization Code + PKCE link for a desktop (loopback) OAuth client. */
	public static String authorizeUrl(String clientId, String redirectUri, String state, String codeChallenge) {
		Map<String, String> query = new LinkedHashMap<>();
		query.put("client_id", clientId);
		query.put("redirect_uri", redirectUri);
		query.put("response_type", "code");
		query.put("scope", SCOPES);
		query.put("state", state);
		query.put("code_challenge", codeChallenge);
		query.put("code_challenge_method", "S256");
		query.put("access_type", "offline");
		query.put("prompt", "consent");
		StringBuilder url = new StringBuilder(AUTHORIZE).append('?');
		appendQuery(url, query);
		return url.toString();
	}

	public JsonObject exchangeCode(String clientId, String code, String verifier, String redirectUri) throws IOException, InterruptedException {
		Map<String, String> form = new LinkedHashMap<>();
		form.put("client_id", clientId);
		form.put("code", code);
		form.put("code_verifier", verifier);
		form.put("redirect_uri", redirectUri);
		form.put("grant_type", "authorization_code");
		Response response = TwitchHttp.postForm(TOKEN, form, Map.of());
		if (!response.ok()) {
			throw oauthError(response);
		}
		return response.json();
	}

	private JsonObject refresh(String clientId, String refreshToken) throws IOException, InterruptedException {
		Map<String, String> form = new LinkedHashMap<>();
		form.put("client_id", clientId);
		form.put("refresh_token", refreshToken);
		form.put("grant_type", "refresh_token");
		Response response = TwitchHttp.postForm(TOKEN, form, Map.of());
		if (!response.ok()) {
			ApiException error = oauthError(response);
			throw new ApiException(401, error.reason(), "Google не смог продлить OAuth-токен: " + error.getMessage());
		}
		return response.json();
	}

	/** Best-effort revocation. The local token is removed even if Google cannot be reached. */
	public void revoke(String token) {
		if (token == null || token.isBlank()) {
			return;
		}
		try {
			TwitchHttp.postForm(REVOKE, Map.of("token", token), Map.of());
		} catch (Exception e) {
			TwitchCraftClient.LOGGER.debug("YouTube: отзыв OAuth-токена не удался: {}", e.toString());
		}
	}

	public JsonObject myChannel() throws IOException, InterruptedException {
		return firstChannel(get("channels", channelQuery(), YoutubeQuota.COST_CHANNEL_LIST));
	}

	/** Проверка свежевыданного токена до замены сохранённых учётных данных активного аккаунта. */
	public JsonObject myChannel(String accessToken) throws IOException, InterruptedException {
		charge(YoutubeQuota.COST_CHANNEL_LIST);
		Response response = send("GET", url("channels", channelQuery()), accessToken, null);
		if (!response.ok()) throw apiError(response);
		return firstChannel(response.json());
	}

	private static Map<String, String> channelQuery() {
		return Map.of("part", "id,snippet", "mine", "true", "maxResults", "1");
	}

	private static JsonObject firstChannel(JsonObject result) throws ApiException {
		JsonArray items = array(result, "items");
		if (items.isEmpty() || !items.get(0).isJsonObject()) {
			throw new ApiException(404, "channelNotFound", "Google-аккаунт не связан с каналом YouTube");
		}
		return items.get(0).getAsJsonObject();
	}

	/** Активные трансляции канала: у идущего эфира в snippet.liveChatId есть чат. */
	public JsonArray activeBroadcasts() throws IOException, InterruptedException {
		return broadcasts("active");
	}

	/**
	 * Трансляции канала с нужным статусом.
	 *
	 * @param broadcastStatus active / upcoming / all (параметр liveBroadcasts.list)
	 */
	public JsonArray broadcasts(String broadcastStatus) throws IOException, InterruptedException {
		Map<String, String> query = new LinkedHashMap<>();
		query.put("part", "id,snippet,status");
		if (broadcastStatus != null && !broadcastStatus.isBlank()) {
			query.put("broadcastStatus", broadcastStatus);
		}
		query.put("maxResults", "50");
		JsonObject result = get("liveBroadcasts", query, YoutubeQuota.COST_BROADCAST_LIST);
		return array(result, "items");
	}

	/**
	 * Трансляция целиком (id, snippet, status, contentDetails): нужна для
	 * {@link #updateBroadcastTitle}, потому что liveBroadcasts.update принимает только полный ресурс.
	 */
	public JsonObject broadcast(String broadcastId) throws IOException, InterruptedException {
		JsonObject result = get("liveBroadcasts", Map.of(
				"part", "id,snippet,status,contentDetails",
				"id", broadcastId
		), YoutubeQuota.COST_BROADCAST_LIST);
		JsonArray items = array(result, "items");
		if (items.isEmpty() || !items.get(0).isJsonObject()) {
			throw new ApiException(404, "liveBroadcastNotFound", "Трансляция YouTube не найдена");
		}
		return items.get(0).getAsJsonObject();
	}

	/** Видео эфира: snippet (заголовок) и liveStreamingDetails (зрители, время начала, activeLiveChatId). */
	public JsonObject videoDetails(String videoId) throws IOException, InterruptedException {
		JsonObject result = get("videos", Map.of(
				"part", "snippet,liveStreamingDetails",
				"id", videoId
		), YoutubeQuota.COST_VIDEO_LIST);
		JsonArray items = array(result, "items");
		return items.isEmpty() || !items.get(0).isJsonObject() ? new JsonObject() : items.get(0).getAsJsonObject();
	}

	/**
	 * Страница сообщений live chat.
	 *
	 * @param maxResults 200…2000: больше сообщений за один запрос — меньше единиц квоты на оживлённый чат
	 */
	public JsonObject listMessages(String liveChatId, String pageToken, int maxResults) throws IOException, InterruptedException {
		Map<String, String> query = new LinkedHashMap<>();
		query.put("part", "snippet,authorDetails");
		query.put("liveChatId", liveChatId);
		query.put("maxResults", String.valueOf(Math.max(200, Math.min(2000, maxResults))));
		if (pageToken != null && !pageToken.isBlank()) {
			query.put("pageToken", pageToken);
		}
		return get("liveChat/messages", query, YoutubeQuota.COST_CHAT_LIST);
	}

	public JsonObject insertMessage(String liveChatId, String text) throws IOException, InterruptedException {
		JsonObject snippet = new JsonObject();
		snippet.addProperty("liveChatId", liveChatId);
		snippet.addProperty("type", "textMessageEvent");
		JsonObject details = new JsonObject();
		details.addProperty("messageText", text);
		snippet.add("textMessageDetails", details);
		JsonObject body = new JsonObject();
		body.add("snippet", snippet);
		return post("liveChat/messages", Map.of("part", "snippet"), body, YoutubeQuota.COST_CHAT_INSERT);
	}

	/** Удалить сообщение из live chat (модерация). */
	public JsonObject deleteMessage(String messageId) throws IOException, InterruptedException {
		return delete("liveChat/messages", Map.of("id", messageId), YoutubeQuota.COST_CHAT_DELETE);
	}

	/**
	 * Бан или тайм-аут зрителя в live chat.
	 *
	 * @param seconds длительность тайм-аута; 0 или меньше — постоянный бан
	 */
	public JsonObject banUser(String liveChatId, String bannedChannelId, int seconds) throws IOException, InterruptedException {
		JsonObject snippet = new JsonObject();
		snippet.addProperty("liveChatId", liveChatId);
		snippet.addProperty("type", seconds > 0 ? "temporary" : "permanent");
		if (seconds > 0) {
			snippet.addProperty("banDurationSeconds", seconds);
		}
		JsonObject user = new JsonObject();
		user.addProperty("channelId", bannedChannelId);
		snippet.add("bannedUserDetails", user);
		JsonObject body = new JsonObject();
		body.add("snippet", snippet);
		return post("liveChat/bans", Map.of("part", "snippet"), body, YoutubeQuota.COST_BAN_INSERT);
	}

	/** Снять бан по id, который вернул {@link #banUser}. */
	public JsonObject unban(String banId) throws IOException, InterruptedException {
		return delete("liveChat/bans", Map.of("id", banId), YoutubeQuota.COST_BAN_DELETE);
	}

	/**
	 * Перевод трансляции в состояние {@code testing}, {@code live} или {@code complete}.
	 * Тело запроса не передаётся: состояние задаётся параметром broadcastStatus.
	 */
	public JsonObject transition(String broadcastId, String broadcastStatus) throws IOException, InterruptedException {
		return post("liveBroadcasts/transition", Map.of(
				"id", broadcastId,
				"broadcastStatus", broadcastStatus,
				"part", "snippet,status"
		), null, YoutubeQuota.COST_BROADCAST_TRANSITION);
	}

	/**
	 * Заголовок трансляции. liveBroadcasts.update принимает ресурс целиком, поэтому сначала читаем
	 * трансляцию, меняем snippet.title и отправляем её обратно; обязательные поля, которых не
	 * оказалось в ответе, заполняются значениями YouTube по умолчанию.
	 */
	public JsonObject updateBroadcastTitle(String broadcastId, String title) throws IOException, InterruptedException {
		JsonObject broadcast = broadcast(broadcastId);
		JsonObject snippet = object(broadcast, "snippet");
		if (snippet == null) {
			snippet = new JsonObject();
			broadcast.add("snippet", snippet);
		}
		snippet.addProperty("title", title);
		JsonObject status = object(broadcast, "status");
		if (status == null) {
			status = new JsonObject();
			broadcast.add("status", status);
		}
		if (str(status, "privacyStatus").isBlank()) {
			status.addProperty("privacyStatus", "public");
		}
		JsonObject details = object(broadcast, "contentDetails");
		if (details == null) {
			details = new JsonObject();
			broadcast.add("contentDetails", details);
		}
		JsonObject monitor = object(details, "monitorStream");
		if (monitor == null) {
			monitor = new JsonObject();
			details.add("monitorStream", monitor);
		}
		defaultBool(monitor, "enableMonitorStream", false);
		defaultBool(details, "isPrivateBroadcast", false);
		defaultBool(details, "recordFromStart", true);
		defaultBool(details, "makeForKids", false);
		defaultBool(details, "enableAutoStart", false);
		defaultBool(details, "enableAutoStop", false);
		defaultBool(details, "enableDvr", true);
		defaultBool(details, "enableEmbed", true);
		return put("liveBroadcasts", Map.of("part", "snippet,status,contentDetails"), broadcast,
				YoutubeQuota.COST_BROADCAST_UPDATE);
	}

	private static void defaultBool(JsonObject target, String key, boolean fallback) {
		if (!target.has(key) || target.get(key).isJsonNull()) {
			target.addProperty(key, fallback);
		}
	}

	// ---------- Транспорт и учёт квоты ----------

	private JsonObject get(String path, Map<String, String> query, int cost) throws IOException, InterruptedException {
		return request("GET", path, query, null, cost);
	}

	private JsonObject post(String path, Map<String, String> query, JsonObject body, int cost) throws IOException, InterruptedException {
		return request("POST", path, query, body, cost);
	}

	private JsonObject put(String path, Map<String, String> query, JsonObject body, int cost) throws IOException, InterruptedException {
		return request("PUT", path, query, body, cost);
	}

	private JsonObject delete(String path, Map<String, String> query, int cost) throws IOException, InterruptedException {
		return request("DELETE", path, query, null, cost);
	}

	private JsonObject request(String method, String path, Map<String, String> query, JsonObject body, int cost)
			throws IOException, InterruptedException {
		ensureFreshToken();
		String observedToken = mod.youtubeStore().accessToken;
		String url = url(path, query);
		charge(cost);
		Response response = send(method, url, observedToken, body);
		if (response.status() == 401 && refreshAfter401(observedToken)) {
			charge(cost); // повтор после продления токена Google тарифицирует как отдельный запрос
			response = send(method, url, mod.youtubeStore().accessToken, body);
		}
		if (!response.ok()) {
			throw apiError(response);
		}
		return response.json();
	}

	/** Начислить расход квоты; счётчик привязан к OAuth Client ID текущего проекта Google. */
	private void charge(int cost) {
		quota.sync(mod.config() == null || mod.config().youtube == null ? "" : mod.config().youtube.clientId);
		quota.charge(cost);
	}

	private Response send(String method, String url, String accessToken, JsonObject body) throws IOException, InterruptedException {
		Map<String, String> headers = Map.of(
				"Authorization", "Bearer " + accessToken,
				"Accept", "application/json"
		);
		return switch (method) {
			case "POST" -> body == null ? TwitchHttp.postNoBody(url, headers) : TwitchHttp.postJson(url, body, headers);
			case "PUT" -> TwitchHttp.putJson(url, body == null ? new JsonObject() : body, headers);
			case "DELETE" -> TwitchHttp.delete(url, headers);
			default -> TwitchHttp.get(url, headers);
		};
	}

	private void ensureFreshToken() throws IOException, InterruptedException {
		YoutubeStore store = mod.youtubeStore();
		if (!store.hasTokens()) {
			throw new ApiException(401, "unauthorized", "Сначала войди в YouTube: /twitch youtube login");
		}
		if (!store.expiresSoon()) {
			return;
		}
		synchronized (refreshLock) {
			if (store.expiresSoon()) {
				doRefresh(store);
			}
		}
	}

	private boolean refreshAfter401(String failedToken) throws IOException, InterruptedException {
		synchronized (refreshLock) {
			YoutubeStore store = mod.youtubeStore();
			if (failedToken != null && !failedToken.equals(store.accessToken)) {
				return true; // другой запрос уже продлил токен
			}
			doRefresh(store);
			return true;
		}
	}

	private void doRefresh(YoutubeStore store) throws IOException, InterruptedException {
		if (!store.hasRefreshToken()) {
			throw new ApiException(401, "invalid_grant", "нет refresh-токена — войди заново: /twitch youtube login");
		}
		String clientId = mod.config().youtube.clientId;
		if (clientId == null || clientId.isBlank()) {
			throw new ApiException(401, "invalid_client", "не указан OAuth Client ID YouTube");
		}
		JsonObject json = refresh(clientId.trim(), store.refreshToken);
		String access = str(json, "access_token");
		if (access.isBlank()) {
			throw new ApiException(401, "invalid_grant", "Google не вернул access_token — войди заново");
		}
		long expiresIn = longValue(json, "expires_in", 3600);
		String scope = str(json, "scope");
		store.setTokens(access, str(json, "refresh_token"), expiresIn, scope);
		TwitchCraftClient.LOGGER.debug("YouTube: OAuth-токен продлён (ещё примерно {} мин)", expiresIn / 60);
	}

	private static ApiException oauthError(Response response) {
		JsonObject root = response.json();
		String reason = str(root, "error");
		JsonElement error = root.get("error");
		if (error != null && error.isJsonObject()) {
			JsonObject detail = error.getAsJsonObject();
			reason = str(detail, "status");
			if (reason.isBlank()) reason = str(detail, "error");
			String message = str(detail, "error_description");
			if (message.isBlank()) message = str(detail, "message");
			if (message.isBlank()) message = response.body();
			return new ApiException(response.status(), reason, message);
		}
		String message = str(root, "error_description");
		if (message.isBlank()) message = response.body();
		return new ApiException(response.status(), reason, message);
	}

	private static ApiException apiError(Response response) {
		JsonObject root = response.json();
		JsonObject error = object(root, "error");
		String reason = "";
		String message = "";
		if (error != null) {
			message = str(error, "message");
			JsonArray errors = array(error, "errors");
			if (!errors.isEmpty() && errors.get(0).isJsonObject()) {
				reason = str(errors.get(0).getAsJsonObject(), "reason");
			}
			if (reason.isBlank()) reason = str(error, "status");
		}
		if (message.isBlank()) message = response.errorMessage();
		if (message.isBlank()) message = "HTTP " + response.status();
		return new ApiException(response.status(), reason, message, response.retryAfterMillis());
	}

	private static String url(String path, Map<String, String> query) {
		StringBuilder url = new StringBuilder(API).append('/').append(path.startsWith("/") ? path.substring(1) : path);
		if (query != null && !query.isEmpty()) {
			url.append('?');
			appendQuery(url, query);
		}
		return url.toString();
	}

	private static void appendQuery(StringBuilder target, Map<String, String> query) {
		boolean first = true;
		for (Map.Entry<String, String> entry : query.entrySet()) {
			if (!first) target.append('&');
			first = false;
			target.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
		}
	}

	private static String encode(String value) {
		return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
	}

	private static String trimSlash(String value) {
		String result = value == null ? "" : value.trim();
		while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
		return result;
	}

	private static JsonArray array(JsonObject object, String name) {
		return object != null && object.has(name) && object.get(name).isJsonArray() ? object.getAsJsonArray(name) : new JsonArray();
	}

	private static JsonObject object(JsonObject object, String name) {
		return object != null && object.has(name) && object.get(name).isJsonObject() ? object.getAsJsonObject(name) : null;
	}

	static String str(JsonObject object, String key) {
		if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
		try {
			return object.get(key).getAsString().trim();
		} catch (Exception ignored) {
			return "";
		}
	}

	private static long longValue(JsonObject object, String key, long fallback) {
		if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
		try {
			return object.get(key).getAsLong();
		} catch (Exception ignored) {
			return fallback;
		}
	}

	public static final class ApiException extends IOException {
		private final int status;
		private final String reason;
		private final long retryAfterMillis;

		public ApiException(int status, String reason, String message) {
			this(status, reason, message, 0);
		}

		public ApiException(int status, String reason, String message, long retryAfterMillis) {
			super(message == null || message.isBlank() ? "HTTP " + status : message);
			this.status = status;
			this.reason = reason == null ? "" : reason;
			this.retryAfterMillis = Math.max(0, retryAfterMillis);
		}

		public int status() {
			return status;
		}

		public String reason() {
			return reason;
		}

		/** Заголовок Retry-After от Google в миллисекундах (0 — Google не сказал, сколько ждать). */
		public long retryAfterMillis() {
			return retryAfterMillis;
		}

		public boolean unauthorized() {
			return status == 401 || reason.equalsIgnoreCase("unauthorized") || reason.equalsIgnoreCase("invalid_grant");
		}

		public boolean chatEnded() {
			return reason.equalsIgnoreCase("liveChatEnded") || reason.equalsIgnoreCase("liveChatDisabled")
					|| reason.equalsIgnoreCase("liveChatNotFound") || status == 404;
		}

		/** Чат выключен владельцем канала (не «закончился» — эфир может продолжаться без чата). */
		public boolean chatDisabled() {
			return reason.equalsIgnoreCase("liveChatDisabled");
		}

		public boolean rateLimited() {
			String normalized = reason.toLowerCase(java.util.Locale.ROOT);
			return status == 429 || normalized.contains("ratelimit") || normalized.contains("rateexceeded")
					|| normalized.contains("perminuteexceeded") || normalized.contains("userrequestsexceed");
		}

		public boolean quotaExceeded() {
			return reason.equalsIgnoreCase("quotaExceeded") || reason.equalsIgnoreCase("dailyLimitExceeded");
		}

		/** Не хватает прав OAuth или операция запрещена для этого канала/трансляции. */
		public boolean permissionDenied() {
			return status == 403 && (reason.equalsIgnoreCase("forbidden") || reason.toLowerCase(java.util.Locale.ROOT).contains("permission")
					|| reason.toLowerCase(java.util.Locale.ROOT).contains("notallowed"));
		}

		/** Трансляция уже в запрошенном состоянии (или переходит в него). */
		public boolean redundant() {
			return reason.equalsIgnoreCase("redundantTransition");
		}

		/** Ошибка сервера Google (5xx) — стоит повторить с нарастающей задержкой. */
		public boolean serverError() {
			return status >= 500;
		}
	}
}
