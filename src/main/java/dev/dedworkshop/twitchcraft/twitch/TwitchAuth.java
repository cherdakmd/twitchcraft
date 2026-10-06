package dev.dedworkshop.twitchcraft.twitch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.dedworkshop.twitchcraft.config.TokenStore;
import dev.dedworkshop.twitchcraft.twitch.TwitchHttp.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Авторизация в Twitch через Device Code Flow.
 *
 * Как это работает:
 *  1. Мод просит у Twitch специальный код (requestDeviceCode).
 *  2. Игрок открывает https://www.twitch.tv/activate и вводит код.
 *  3. Мод каждые несколько секунд спрашивает Twitch, подтвердил ли игрок (poll).
 *  4. Как только подтвердил — Twitch выдаёт access_token + refresh_token.
 *
 * Этот способ не требует client secret и локального веб-сервера,
 * поэтому идеально подходит для игр и десктопных приложений.
 */
public final class TwitchAuth {
	private static final String OAUTH_URL = "https://id.twitch.tv/oauth2";

	/** Права, без которых мод не работает. */
	public static final List<String> REQUIRED_SCOPES = List.of(
			"channel:read:redemptions",   // награды за баллы канала
			"channel:read:subscriptions", // подписки
			"bits:read",                  // битсы
			"moderator:read:followers"    // фолловы
	);

	/** Дополнительные права: без них часть функций просто отключается. */
	public static final List<String> OPTIONAL_SCOPES = List.of(
			"user:read:chat",             // чат Twitch в игре и чат-команды
			"user:write:chat",            // ответы в чат Twitch
			"channel:manage:redemptions", // создание наград, возврат баллов
			"clips:edit",                 // клипы (1.7.0)
			"channel:manage:broadcast"    // метки стрима (1.7.0)
	);

	/** Все права, которые запрашиваются при входе. */
	public static final List<String> ALL_SCOPES;

	static {
		List<String> all = new ArrayList<>(REQUIRED_SCOPES);
		all.addAll(OPTIONAL_SCOPES);
		ALL_SCOPES = List.copyOf(all);
	}

	/** Описание функции для каждого дополнительного права (для подсказок игроку). */
	public static String describeScope(String scope) {
		return switch (scope) {
			case "user:read:chat" -> "чат Twitch и чат-команды";
			case "user:write:chat" -> "ответы в чат";
			case "channel:manage:redemptions" -> "создание наград и возврат баллов";
			case "clips:edit" -> "создание клипов";
			case "channel:manage:broadcast" -> "метки стрима";
			default -> scope;
		};
	}

	private TwitchAuth() {
	}

	public record DeviceCode(String deviceCode, String userCode, String verificationUri, int intervalSeconds, int expiresInSeconds) {
	}

	public record Validation(String clientId, String login, String userId, long expiresInSeconds, List<String> scopes) {
		public boolean hasRequiredScopes() {
			return scopes.containsAll(REQUIRED_SCOPES);
		}

		/** Какие дополнительные права отсутствуют. */
		public List<String> missingOptionalScopes() {
			List<String> missing = new ArrayList<>();
			for (String scope : OPTIONAL_SCOPES) {
				if (!scopes.contains(scope)) {
					missing.add(scope);
				}
			}
			return missing;
		}
	}

	public enum PollKind { SUCCESS, PENDING, SLOW_DOWN, DENIED, EXPIRED, ERROR }

	public record PollStatus(PollKind kind, String message) {
	}

	/** Шаг 1: запрашиваем код устройства. */
	public static DeviceCode requestDeviceCode(String clientId) throws IOException, InterruptedException {
		Response response = TwitchHttp.postForm(OAUTH_URL + "/device",
				Map.of("client_id", clientId, "scopes", String.join(" ", ALL_SCOPES)),
				Map.of());
		if (!response.ok()) {
			throw new IOException("Twitch ответил " + response.status() + ": " + response.errorMessage()
					+ " (проверь Client ID и что тип клиента приложения — Public)");
		}
		JsonObject json = response.json();
		return new DeviceCode(
				json.get("device_code").getAsString(),
				json.get("user_code").getAsString(),
				json.get("verification_uri").getAsString(),
				json.has("interval") ? json.get("interval").getAsInt() : 5,
				json.has("expires_in") ? json.get("expires_in").getAsInt() : 1800
		);
	}

	/** Шаг 3: один запрос «подтвердил ли пользователь?». При успехе токены сохраняются в store. */
	public static PollStatus poll(String clientId, String deviceCode, TokenStore store) throws IOException, InterruptedException {
		Response response = TwitchHttp.postForm(OAUTH_URL + "/token", Map.of(
				"client_id", clientId,
				"scopes", String.join(" ", ALL_SCOPES),
				"device_code", deviceCode,
				"grant_type", "urn:ietf:params:oauth:grant-type:device_code"
		), Map.of());

		if (response.ok()) {
			applyTokens(store, response.json());
			return new PollStatus(PollKind.SUCCESS, "");
		}

		String message = response.errorMessage().toLowerCase();
		if (message.contains("authorization_pending")) {
			return new PollStatus(PollKind.PENDING, message);
		}
		if (message.contains("slow_down")) {
			return new PollStatus(PollKind.SLOW_DOWN, message);
		}
		if (message.contains("expired")) {
			return new PollStatus(PollKind.EXPIRED, message);
		}
		if (message.contains("denied")) {
			return new PollStatus(PollKind.DENIED, message);
		}
		return new PollStatus(PollKind.ERROR, response.status() + ": " + response.errorMessage());
	}

	/**
	 * Проверка токена. Twitch требует делать это как минимум раз в час.
	 *
	 * @return данные токена или null, если токен недействителен (401).
	 */
	public static Validation validate(String accessToken) throws IOException, InterruptedException {
		Response response = TwitchHttp.get(OAUTH_URL + "/validate", Map.of("Authorization", "OAuth " + accessToken));
		if (response.status() == 401) {
			return null;
		}
		if (!response.ok()) {
			throw new IOException("Ошибка проверки токена: " + response.status() + " " + response.errorMessage());
		}
		JsonObject json = response.json();
		List<String> scopes = new ArrayList<>();
		if (json.has("scopes") && json.get("scopes").isJsonArray()) {
			JsonArray array = json.getAsJsonArray("scopes");
			for (JsonElement element : array) {
				scopes.add(element.getAsString());
			}
		}
		return new Validation(
				stringOrEmpty(json, "client_id"),
				stringOrEmpty(json, "login"),
				stringOrEmpty(json, "user_id"),
				json.has("expires_in") ? json.get("expires_in").getAsLong() : 0,
				scopes
		);
	}

	/**
	 * Обновление access-токена по refresh-токену.
	 * Для публичных клиентов client secret не нужен.
	 *
	 * @return true, если токен обновлён.
	 */
	/**
	 * Обновляет токен, но только если его ещё никто не обновил после того, как вызывающий его прочитал.
	 * Refresh-токен Twitch одноразовый: два параллельных обновления (проверка по таймеру + подключение +
	 * ответ 401 из API) «сжигали» бы его и приходилось бы логиниться заново.
	 *
	 * @param observedAccessToken access-токен, с которым вызывающий получил 401 (или проверил его)
	 */
	public static synchronized boolean refreshIfStale(String clientId, TokenStore store, String observedAccessToken)
			throws IOException, InterruptedException {
		if (observedAccessToken != null && !observedAccessToken.equals(store.accessToken)) {
			return true; // токен уже обновил кто-то другой — просто повторяем запрос с новым
		}
		return refresh(clientId, store);
	}

	public static synchronized boolean refresh(String clientId, TokenStore store) throws IOException, InterruptedException {
		if (store.refreshToken == null || store.refreshToken.isBlank()) {
			return false;
		}
		Response response = TwitchHttp.postForm(OAUTH_URL + "/token", Map.of(
				"client_id", clientId,
				"grant_type", "refresh_token",
				"refresh_token", store.refreshToken
		), Map.of());
		if (!response.ok()) {
			return false;
		}
		applyTokens(store, response.json());
		return true;
	}

	private static void applyTokens(TokenStore store, JsonObject json) {
		store.accessToken = json.get("access_token").getAsString();
		if (json.has("refresh_token") && !json.get("refresh_token").isJsonNull()) {
			store.refreshToken = json.get("refresh_token").getAsString();
		}
		long expiresIn = json.has("expires_in") ? json.get("expires_in").getAsLong() : 3600;
		store.expiresAt = System.currentTimeMillis() + expiresIn * 1000L;
		// Twitch возвращает scope массивом строк (или строкой через пробел)
		if (json.has("scope") && json.get("scope").isJsonArray()) {
			List<String> scopes = new ArrayList<>();
			for (JsonElement element : json.getAsJsonArray("scope")) {
				scopes.add(element.getAsString());
			}
			store.scopes = scopes;
		} else if (json.has("scope") && json.get("scope").isJsonPrimitive()) {
			store.scopes = new ArrayList<>(List.of(json.get("scope").getAsString().split("\\s+")));
		}
		store.save();
	}

	private static String stringOrEmpty(JsonObject json, String key) {
		return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
	}
}
