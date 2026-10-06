package dev.dedworkshop.twitchcraft.vk;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.twitch.TwitchHttp;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API VK Video Live для разработчиков (DevAPI) и его OAuth 2.0 (Authorization Code Flow).
 *
 * Документация: https://dev.live.vkvideo.ru/docs. Адреса можно переопределить системными свойствами
 * (нужно только для тестов против фейкового сервера).
 */
public class VkApi {
	static final String API = System.getProperty("twitchcraft.vkApiUrl", "https://apidev.live.vkvideo.ru/v1");
	static final String AUTHORIZE = System.getProperty("twitchcraft.vkAuthUrl", "https://auth.live.vkvideo.ru/app/oauth2/authorize");
	static final String TOKEN = System.getProperty("twitchcraft.vkTokenUrl", "https://api.live.vkvideo.ru/oauth/server/token");
	static final String REVOKE = System.getProperty("twitchcraft.vkRevokeUrl", "https://api.live.vkvideo.ru/oauth/server/revoke");
	static final String WS = System.getProperty("twitchcraft.vkWsUrl", "wss://pubsub-dev.live.vkvideo.ru/connection/websocket?format=json&cf_protocol_version=v2");

	/** Права, которые просим при входе: чат, баллы канала (список наград, создание, запросы наград). */
	public static final String SCOPES = "chat:message:send,channel:points,channel:points:rewards,channel:points:rewards:demands";
	public static final String SCOPE_CHAT = "chat:message:send";
	public static final String SCOPE_DEMANDS = "channel:points:rewards:demands";
	public static final String SCOPE_REWARDS = "channel:points:rewards";

	/** Ошибка API: HTTP-статус и код ошибки из JSON ({@code unauthorized}, {@code forbidden}, {@code message_too_long}...). */
	public static class ApiException extends IOException {
		public final int status;
		public final String code;

		public ApiException(int status, String code, String description) {
			super("HTTP " + status + (code.isEmpty() ? "" : " " + code) + (description.isEmpty() ? "" : ": " + description));
			this.status = status;
			this.code = code;
		}

		public boolean unauthorized() {
			return status == 401 || code.equals("unauthorized");
		}

		public boolean forbidden() {
			return status == 403 || code.equals("forbidden");
		}
	}

	private final TwitchCraftClient mod;
	private final Object refreshLock = new Object();

	public VkApi(TwitchCraftClient mod) {
		this.mod = mod;
	}

	// ---------- OAuth ----------

	public static String authorizeUrl(String clientId, String redirectUri, String state) {
		return AUTHORIZE + "?client_id=" + encode(clientId)
				+ "&redirect_uri=" + encode(redirectUri)
				+ "&response_type=code"
				+ "&scope=" + encode(SCOPES)
				+ (state == null || state.isEmpty() ? "" : "&state=" + encode(state));
	}

	private Map<String, String> basicAuth() {
		String clientId = mod.config().vk.clientId == null ? "" : mod.config().vk.clientId.trim();
		String secret = mod.vkStore().clientSecret == null ? "" : mod.vkStore().clientSecret.trim();
		String basic = Base64.getEncoder().encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
		return Map.of("Authorization", "Basic " + basic, "Accept", "application/json");
	}

	/** Обмен кода авторизации на токены. */
	public JsonObject exchangeCode(String code, String redirectUri) throws IOException, InterruptedException {
		Map<String, String> form = new LinkedHashMap<>();
		form.put("grant_type", "authorization_code");
		form.put("code", code);
		form.put("redirect_uri", redirectUri);
		TwitchHttp.Response response = TwitchHttp.postForm(TOKEN, form, basicAuth());
		if (!response.ok()) {
			throw error(response);
		}
		return response.json();
	}

	/** Продление токена по refresh-токену. */
	public JsonObject refresh(String refreshToken, String redirectUri) throws IOException, InterruptedException {
		Map<String, String> form = new LinkedHashMap<>();
		form.put("grant_type", "refresh_token");
		form.put("refresh_token", refreshToken);
		form.put("redirect_uri", redirectUri);
		TwitchHttp.Response response = TwitchHttp.postForm(TOKEN, form, basicAuth());
		if (!response.ok()) {
			throw error(response);
		}
		return response.json();
	}

	/** Отзыв токена (при выходе); ошибки не критичны. */
	public void revoke(String token) {
		if (token == null || token.isBlank()) {
			return;
		}
		try {
			Map<String, String> form = new LinkedHashMap<>();
			form.put("token", token);
			form.put("token_type_hint", "access_token");
			TwitchHttp.postForm(REVOKE, form, basicAuth());
		} catch (Exception e) {
			TwitchCraftClient.LOGGER.debug("VK: отзыв токена не удался: {}", e.toString());
		}
	}

	/**
	 * Проверяет срок access-токена и продлевает его заранее. При неудаче продления бросает ApiException(401),
	 * чтобы владелец попросил пользователя войти заново.
	 */
	public void ensureFreshToken(String redirectUri) throws IOException, InterruptedException {
		VkStore store = mod.vkStore();
		if (!store.hasTokens() || !store.expiresSoon() || store.refreshToken.isBlank()) {
			return;
		}
		synchronized (refreshLock) {
			if (store.expiresSoon()) { // пока ждали замок, токен мог продлить другой поток
				doRefresh(redirectUri);
			}
		}
	}

	/**
	 * Продление токена после 401. {@code failedToken} — access-токен, который сервер отклонил: если к моменту
	 * входа в замок токен уже другой (его продлил параллельный запрос), повторное продление не нужно —
	 * иначе второй запрос ушёл бы с уже использованным refresh-токеном и провалился.
	 */
	private void refreshAfter401(String failedToken, String redirectUri) throws IOException, InterruptedException {
		synchronized (refreshLock) {
			String current = mod.vkStore().accessToken;
			if (failedToken != null && !failedToken.isEmpty() && !failedToken.equals(current)) {
				return;
			}
			doRefresh(redirectUri);
		}
	}

	private void doRefresh(String redirectUri) throws IOException, InterruptedException {
		synchronized (refreshLock) {
			VkStore store = mod.vkStore();
			if (store.refreshToken == null || store.refreshToken.isBlank()) {
				throw new ApiException(401, "unauthorized", "нет refresh-токена");
			}
			JsonObject json = refresh(store.refreshToken, redirectUri);
			String access = str(json, "access_token");
			if (access.isEmpty()) {
				throw new ApiException(401, "unauthorized", "сервер не вернул access_token");
			}
			long expiresIn = json.has("expires_in") && json.get("expires_in").isJsonPrimitive() ? json.get("expires_in").getAsLong() : 0;
			store.setTokens(access, str(json, "refresh_token"), expiresIn, str(json, "scope"));
			TwitchCraftClient.LOGGER.info("VK: токен продлён (действует ещё {} мин)", expiresIn / 60);
		}
	}

	// ---------- Запросы с токеном ----------

	private Map<String, String> bearer() {
		return Map.of("Authorization", "Bearer " + mod.vkStore().accessToken, "Accept", "application/json");
	}

	private static String url(String path, Map<String, String> query) {
		StringBuilder sb = new StringBuilder(API).append(path);
		if (query != null && !query.isEmpty()) {
			sb.append('?');
			boolean first = true;
			for (Map.Entry<String, String> e : query.entrySet()) {
				if (!first) {
					sb.append('&');
				}
				first = false;
				sb.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
			}
		}
		return sb.toString();
	}

	/** GET с автоматическим продлением токена при 401 (один раз). */
	public JsonObject get(String path, Map<String, String> query, String redirectUri) throws IOException, InterruptedException {
		String token = mod.vkStore().accessToken;
		TwitchHttp.Response response = TwitchHttp.get(url(path, query), bearer());
		if (response.status() == 401 && tryRefresh(token, redirectUri)) {
			response = TwitchHttp.get(url(path, query), bearer());
		}
		if (!response.ok()) {
			throw error(response);
		}
		return response.json();
	}

	/** POST JSON с автоматическим продлением токена при 401 (один раз). */
	public JsonObject post(String path, Map<String, String> query, JsonElement body, String redirectUri) throws IOException, InterruptedException {
		JsonElement payload = body == null ? new JsonObject() : body;
		String token = mod.vkStore().accessToken;
		TwitchHttp.Response response = TwitchHttp.postJson(url(path, query), payload, bearer());
		if (response.status() == 401 && tryRefresh(token, redirectUri)) {
			response = TwitchHttp.postJson(url(path, query), payload, bearer());
		}
		if (!response.ok()) {
			throw error(response);
		}
		return response.json();
	}

	private boolean tryRefresh(String failedToken, String redirectUri) {
		try {
			refreshAfter401(failedToken, redirectUri);
			return true;
		} catch (Exception e) {
			TwitchCraftClient.LOGGER.warn("VK: не удалось продлить токен: {}", e.getMessage());
			return false;
		}
	}

	private static ApiException error(TwitchHttp.Response response) {
		JsonObject json = response.json();
		return new ApiException(response.status(), str(json, "error"), str(json, "error_description"));
	}

	// ---------- Методы API ----------

	/** Профиль владельца токена: data.user{id,nick}, data.channel{url}. */
	public JsonObject currentUser(String redirectUri) throws IOException, InterruptedException {
		return data(get("/current_user", Map.of(), redirectUri));
	}

	/** Канал по имени: data.channel{id,nick,url,web_socket_channels{...}}, data.owner, data.stream. */
	public JsonObject channel(String slug, String redirectUri) throws IOException, InterruptedException {
		return data(get("/channel", Map.of("channel_url", slug), redirectUri));
	}

	/** Токен подключения к WebSocket (Centrifugo). */
	public String websocketToken(String redirectUri) throws IOException, InterruptedException {
		return str(data(get("/websocket/token", Map.of(), redirectUri)), "token");
	}

	/** Токены подписки на приватные каналы WebSocket: имя канала → токен (каналы без токена — публичные). */
	public Map<String, String> subscriptionTokens(List<String> channels, String redirectUri) throws IOException, InterruptedException {
		Map<String, String> result = new LinkedHashMap<>();
		if (channels.isEmpty()) {
			return result;
		}
		JsonObject data = data(get("/websocket/subscription_token", Map.of("channels", String.join(",", channels)), redirectUri));
		JsonElement list = data.get("channel_tokens");
		if (list != null && list.isJsonArray()) {
			for (JsonElement element : list.getAsJsonArray()) {
				if (element.isJsonObject()) {
					JsonObject item = element.getAsJsonObject();
					String channel = str(item, "channel");
					String token = str(item, "token");
					if (!channel.isEmpty() && !token.isEmpty()) {
						result.put(channel, token);
					}
				}
			}
		}
		return result;
	}

	/** Отправка сообщения в чат канала (право chat:message:send). */
	public void sendMessage(String slug, String streamId, String text, String redirectUri) throws IOException, InterruptedException {
		JsonObject part = new JsonObject();
		JsonObject textPart = new JsonObject();
		textPart.addProperty("content", text);
		part.add("text", textPart);
		JsonArray parts = new JsonArray();
		parts.add(part);
		JsonObject body = new JsonObject();
		body.add("parts", parts);
		Map<String, String> query = new LinkedHashMap<>();
		query.put("channel_url", slug);
		if (streamId != null && !streamId.isBlank()) {
			query.put("stream_id", streamId);
		}
		post("/chat/message/send", query, body, redirectUri);
	}

	/** Подтвердить (accept) или отклонить (reject) запросы наград за баллы. */
	public void resolveDemands(String slug, List<Long> ids, boolean accept, String redirectUri) throws IOException, InterruptedException {
		JsonArray demands = new JsonArray();
		for (Long id : ids) {
			JsonObject item = new JsonObject();
			item.addProperty("id", id);
			demands.add(item);
		}
		JsonObject body = new JsonObject();
		body.add("demands", demands);
		post(accept ? "/channel_point/reward/demand/accept" : "/channel_point/reward/demand/reject",
				Map.of("channel_url", slug), body, redirectUri);
	}

	/** Награды канала глазами владельца (право channel:points:rewards). */
	public JsonArray rewardsManageInfo(String slug, String redirectUri) throws IOException, InterruptedException {
		JsonObject data = data(get("/channel_point/rewards/manage_info", Map.of("channel_url", slug), redirectUri));
		JsonElement rewards = data.get("rewards");
		return rewards != null && rewards.isJsonArray() ? rewards.getAsJsonArray() : new JsonArray();
	}

	/** Награды канала (публичный список, право channel:points). */
	public JsonArray rewards(String slug, String redirectUri) throws IOException, InterruptedException {
		JsonObject data = data(get("/channel_point/rewards", Map.of("channel_url", slug), redirectUri));
		JsonElement rewards = data.get("rewards");
		return rewards != null && rewards.isJsonArray() ? rewards.getAsJsonArray() : new JsonArray();
	}

	/** Создать награду; возвращает id или пустую строку. */
	public String createReward(String slug, JsonObject reward, String redirectUri) throws IOException, InterruptedException {
		JsonObject body = new JsonObject();
		body.add("reward", reward);
		JsonObject data = data(post("/channel_point/reward/create", Map.of("channel_url", slug), body, redirectUri));
		JsonElement created = data.get("reward");
		return created != null && created.isJsonObject() ? str(created.getAsJsonObject(), "id") : "";
	}

	/** Список запросов наград (право channel:points:rewards:demands). */
	public JsonArray demands(String slug, int limit, int offset, String redirectUri) throws IOException, InterruptedException {
		Map<String, String> query = new LinkedHashMap<>();
		query.put("channel_url", slug);
		query.put("limit", String.valueOf(limit));
		query.put("offset", String.valueOf(offset));
		JsonObject data = data(get("/channel_point/reward/demands", query, redirectUri));
		JsonElement demands = data.get("demands");
		return demands != null && demands.isJsonArray() ? demands.getAsJsonArray() : new JsonArray();
	}

	// ---------- Утилиты ----------

	/** Поле data ответа (или сам объект, если data нет). */
	public static JsonObject data(JsonObject json) {
		if (json != null && json.has("data") && json.get("data").isJsonObject()) {
			return json.getAsJsonObject("data");
		}
		return json == null ? new JsonObject() : json;
	}

	public static String str(JsonObject obj, String key) {
		if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull()) {
			return "";
		}
		JsonElement element = obj.get(key);
		if (element.isJsonPrimitive()) {
			return element.getAsString();
		}
		return element.toString();
	}

	private static String encode(String value) {
		return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
	}
}
