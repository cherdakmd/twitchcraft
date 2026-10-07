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

	public YoutubeApi(TwitchCraftClient mod) {
		this.mod = mod;
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
		return firstChannel(get("channels", channelQuery()));
	}

	/** Validate a freshly exchanged token before replacing the credentials of an active account. */
	public JsonObject myChannel(String accessToken) throws IOException, InterruptedException {
		Response response = send(url("channels", channelQuery()), accessToken, null, false);
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

	/** Active broadcasts for the authenticated channel; a running broadcast carries snippet.liveChatId. */
	public JsonArray activeBroadcasts() throws IOException, InterruptedException {
		JsonObject result = get("liveBroadcasts", Map.of(
				"part", "id,snippet,status",
				"broadcastStatus", "active",
				"maxResults", "50"
		));
		return array(result, "items");
	}

	public JsonObject listMessages(String liveChatId, String pageToken) throws IOException, InterruptedException {
		Map<String, String> query = new LinkedHashMap<>();
		query.put("part", "snippet,authorDetails");
		query.put("liveChatId", liveChatId);
		query.put("maxResults", "200");
		if (pageToken != null && !pageToken.isBlank()) {
			query.put("pageToken", pageToken);
		}
		return get("liveChat/messages", query);
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
		return post("liveChat/messages", Map.of("part", "snippet"), body);
	}

	public JsonObject get(String path, Map<String, String> query) throws IOException, InterruptedException {
		return request(path, query, null, false);
	}

	private JsonObject post(String path, Map<String, String> query, JsonObject body) throws IOException, InterruptedException {
		return request(path, query, body, true);
	}

	private JsonObject request(String path, Map<String, String> query, JsonObject body, boolean post) throws IOException, InterruptedException {
		ensureFreshToken();
		String observedToken = mod.youtubeStore().accessToken;
		String url = url(path, query);
		Response response = send(url, observedToken, body, post);
		if (response.status() == 401 && refreshAfter401(observedToken)) {
			response = send(url, mod.youtubeStore().accessToken, body, post);
		}
		if (!response.ok()) {
			throw apiError(response);
		}
		return response.json();
	}

	private Response send(String url, String accessToken, JsonObject body, boolean post) throws IOException, InterruptedException {
		Map<String, String> headers = Map.of(
				"Authorization", "Bearer " + accessToken,
				"Accept", "application/json"
		);
		return post
				? TwitchHttp.postJson(url, body, headers)
				: TwitchHttp.get(url, headers);
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

	private static JsonObject oauthError(Response response) {
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
		return new ApiException(response.status(), reason, message);
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

		public ApiException(int status, String reason, String message) {
			super(message == null || message.isBlank() ? "HTTP " + status : message);
			this.status = status;
			this.reason = reason == null ? "" : reason;
		}

		public int status() {
			return status;
		}

		public String reason() {
			return reason;
		}

		public boolean unauthorized() {
			return status == 401 || reason.equalsIgnoreCase("unauthorized") || reason.equalsIgnoreCase("invalid_grant");
		}

		public boolean chatEnded() {
			return reason.equalsIgnoreCase("liveChatEnded") || reason.equalsIgnoreCase("liveChatDisabled")
					|| reason.equalsIgnoreCase("liveChatNotFound") || status == 404;
		}

		public boolean rateLimited() {
			String normalized = reason.toLowerCase(java.util.Locale.ROOT);
			return status == 429 || normalized.contains("ratelimit") || normalized.contains("rateexceeded")
					|| normalized.contains("perminuteexceeded");
		}

		public boolean quotaExceeded() {
			return reason.equalsIgnoreCase("quotaExceeded");
		}
	}
}
