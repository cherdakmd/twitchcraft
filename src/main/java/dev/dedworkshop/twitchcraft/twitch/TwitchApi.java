package dev.dedworkshop.twitchcraft.twitch;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.twitch.TwitchHttp.Response;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Запросы к Twitch Helix API (https://api.twitch.tv/helix).
 * Если токен протух (401), автоматически обновляет его и повторяет запрос.
 */
public class TwitchApi {
	/** Адрес можно переопределить свойством -Dtwitchcraft.helixUrl=... (нужно только для тестов). */
	private static final String HELIX = System.getProperty("twitchcraft.helixUrl", "https://api.twitch.tv/helix");

	private final TwitchCraftClient mod;

	public TwitchApi(TwitchCraftClient mod) {
		this.mod = mod;
	}

	public record User(String id, String login, String displayName) {
	}

	/** Кто владелец токена. Без параметров /users возвращает текущего пользователя. */
	public User fetchCurrentUser() throws IOException, InterruptedException {
		Response response = authorized(() -> TwitchHttp.get(HELIX + "/users", headers()));
		if (!response.ok()) {
			throw new IOException("Не удалось получить пользователя: " + response.status() + " " + response.errorMessage());
		}
		JsonArray data = response.json().getAsJsonArray("data");
		if (data == null || data.isEmpty()) {
			throw new IOException("Twitch вернул пустой список пользователей");
		}
		JsonObject user = data.get(0).getAsJsonObject();
		return new User(
				user.get("id").getAsString(),
				user.get("login").getAsString(),
				user.get("display_name").getAsString()
		);
	}

	/**
	 * Создаёт подписку EventSub для WebSocket-сессии.
	 *
	 * @return null при успехе, иначе текст ошибки.
	 */
	public String createSubscription(String sessionId, String type, String version, JsonObject condition) throws IOException, InterruptedException {
		JsonObject transport = new JsonObject();
		transport.addProperty("method", "websocket");
		transport.addProperty("session_id", sessionId);

		JsonObject body = new JsonObject();
		body.addProperty("type", type);
		body.addProperty("version", version);
		body.add("condition", condition);
		body.add("transport", transport);

		Response response = authorized(() -> TwitchHttp.postJson(HELIX + "/eventsub/subscriptions", body, headers()));
		if (response.status() == 202 || response.status() == 409) {
			return null; // 409 = такая подписка уже есть, это тоже успех
		}
		return response.status() + " " + response.errorMessage();
	}

	// ---------- Награды за баллы канала (scope channel:manage:redemptions) ----------

	/** Все награды канала. */
	public JsonArray listCustomRewards(String broadcasterId) throws IOException, InterruptedException {
		Response response = authorized(() -> TwitchHttp.get(HELIX + "/channel_points/custom_rewards?broadcaster_id=" + enc(broadcasterId), headers()));
		if (!response.ok()) {
			throw new IOException(response.status() + " " + response.errorMessage());
		}
		JsonArray data = response.json().getAsJsonArray("data");
		return data == null ? new JsonArray() : data;
	}

	/**
	 * Создаёт награду. Награды, созданные через API, может подтверждать/отменять только то же приложение.
	 *
	 * @return null при успехе, иначе текст ошибки.
	 */
	public String createCustomReward(String broadcasterId, JsonObject body) throws IOException, InterruptedException {
		Response response = authorized(() -> TwitchHttp.postJson(HELIX + "/channel_points/custom_rewards?broadcaster_id=" + enc(broadcasterId), body, headers()));
		if (response.ok()) {
			return null;
		}
		return response.status() + " " + response.errorMessage();
	}

	/**
	 * Меняет статус активации награды: FULFILLED (выполнено) или CANCELED (баллы возвращаются зрителю).
	 * Работает только для наград, созданных этим же приложением (Client ID).
	 *
	 * @return HTTP-статус ответа (200 — успех, 403 — награда создана не нами).
	 */
	public int updateRedemptionStatus(String broadcasterId, String rewardId, String redemptionId, String status) throws IOException, InterruptedException {
		JsonObject body = new JsonObject();
		body.addProperty("status", status);
		String url = HELIX + "/channel_points/custom_rewards/redemptions?broadcaster_id=" + enc(broadcasterId)
				+ "&reward_id=" + enc(rewardId) + "&id=" + enc(redemptionId);
		Response response = authorized(() -> TwitchHttp.patchJson(url, body, headers()));
		if (!response.ok() && response.status() != 403) {
			TwitchCraftClient.LOGGER.warn("Не удалось изменить статус награды: {} {}", response.status(), response.errorMessage());
		}
		return response.status();
	}

	// ---------- Чат (scope user:write:chat) ----------

	/**
	 * Отправляет сообщение в чат канала от имени владельца токена.
	 *
	 * @return null при успехе, иначе текст ошибки.
	 */
	public String sendChatMessage(String broadcasterId, String text) throws IOException, InterruptedException {
		JsonObject body = new JsonObject();
		body.addProperty("broadcaster_id", broadcasterId);
		body.addProperty("sender_id", broadcasterId);
		body.addProperty("message", text);
		Response response = authorized(() -> TwitchHttp.postJson(HELIX + "/chat/messages", body, headers()));
		if (!response.ok()) {
			return response.status() + " " + response.errorMessage();
		}
		JsonArray data = response.json().getAsJsonArray("data");
		if (data != null && !data.isEmpty()) {
			JsonObject result = data.get(0).getAsJsonObject();
			if (result.has("is_sent") && !result.get("is_sent").getAsBoolean()) {
				JsonObject drop = result.has("drop_reason") && result.get("drop_reason").isJsonObject()
						? result.getAsJsonObject("drop_reason") : null;
				return "сообщение отклонено" + (drop != null && drop.has("message") ? ": " + drop.get("message").getAsString() : "");
			}
		}
		return null;
	}

	// ---------- Стрим, клипы и метки (1.7.0) ----------

	/** Состояние стрима: live, время начала (epoch ms), зрители, заголовок, категория. */
	public record Stream(boolean live, long startedAt, int viewers, String title, String game) {
		public static final Stream OFFLINE = new Stream(false, 0, 0, "", "");
	}

	/** GET /streams?user_id= — пустой data, если стрим не идёт. Не требует дополнительных прав. */
	public Stream getStream(String userId) throws IOException, InterruptedException {
		Response response = authorized(() -> TwitchHttp.get(HELIX + "/streams?user_id=" + enc(userId), headers()));
		if (!response.ok()) {
			throw new IOException(response.status() + " " + response.errorMessage());
		}
		JsonArray data = response.json().getAsJsonArray("data");
		if (data == null || data.isEmpty()) {
			return Stream.OFFLINE;
		}
		JsonObject stream = data.get(0).getAsJsonObject();
		long startedAt = 0;
		try {
			String started = stream.has("started_at") ? stream.get("started_at").getAsString() : "";
			if (!started.isBlank()) {
				startedAt = java.time.Instant.parse(started).toEpochMilli();
			}
		} catch (RuntimeException ignored) {
			// формат времени неожиданный — оставим 0
		}
		return new Stream(true, startedAt,
				stream.has("viewer_count") ? stream.get("viewer_count").getAsInt() : 0,
				stream.has("title") ? stream.get("title").getAsString() : "",
				stream.has("game_name") ? stream.get("game_name").getAsString() : "");
	}

	/** Результат создания метки/клипа: ok + id (+ ссылка на редактирование) или текст ошибки. */
	public record Created(boolean ok, int status, String id, String editUrl, String error) {
	}

	/** POST /streams/markers (scope channel:manage:broadcast). 404 — стрим не идёт. Описание — до 140 символов. */
	public Created createStreamMarker(String userId, String description) throws IOException, InterruptedException {
		JsonObject body = new JsonObject();
		body.addProperty("user_id", userId);
		String text = description == null ? "" : description.trim();
		if (text.length() > 140) {
			text = text.substring(0, 140);
		}
		if (!text.isEmpty()) {
			body.addProperty("description", text);
		}
		Response response = authorized(() -> TwitchHttp.postJson(HELIX + "/streams/markers", body, headers()));
		if (!response.ok()) {
			return new Created(false, response.status(), "", "", response.status() + " " + response.errorMessage());
		}
		JsonArray data = response.json().getAsJsonArray("data");
		String id = data != null && !data.isEmpty() && data.get(0).getAsJsonObject().has("id")
				? data.get(0).getAsJsonObject().get("id").getAsString() : "";
		return new Created(true, response.status(), id, "", "");
	}

	/** POST /clips?broadcaster_id= (scope clips:edit). Twitch отвечает 202 и делает клип ~15 секунд; 404 — стрим не идёт. */
	public Created createClip(String broadcasterId) throws IOException, InterruptedException {
		Response response = authorized(() -> TwitchHttp.postJson(HELIX + "/clips?broadcaster_id=" + enc(broadcasterId) + "&has_delay=false",
				new JsonObject(), headers()));
		if (!response.ok()) {
			return new Created(false, response.status(), "", "", response.status() + " " + response.errorMessage());
		}
		JsonArray data = response.json().getAsJsonArray("data");
		if (data == null || data.isEmpty()) {
			return new Created(false, response.status(), "", "", "пустой ответ Twitch");
		}
		JsonObject clip = data.get(0).getAsJsonObject();
		return new Created(true, response.status(), clip.has("id") ? clip.get("id").getAsString() : "",
				clip.has("edit_url") ? clip.get("edit_url").getAsString() : "", "");
	}

	/** GET /clips?id= — ссылка на готовый клип или null, пока Twitch его ещё не обработал. */
	public String getClipUrl(String clipId) throws IOException, InterruptedException {
		Response response = authorized(() -> TwitchHttp.get(HELIX + "/clips?id=" + enc(clipId), headers()));
		if (!response.ok()) {
			throw new IOException(response.status() + " " + response.errorMessage());
		}
		JsonArray data = response.json().getAsJsonArray("data");
		if (data == null || data.isEmpty()) {
			return null;
		}
		JsonObject clip = data.get(0).getAsJsonObject();
		return clip.has("url") ? clip.get("url").getAsString() : "https://clips.twitch.tv/" + clipId;
	}

	// ---------- Внутреннее ----------

	private Map<String, String> headers() {
		return Map.of(
				"Authorization", "Bearer " + mod.tokens().accessToken,
				"Client-Id", mod.config().clientId
		);
	}

	private static String enc(String value) {
		return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
	}

	/** Выполняет запрос; при 401 обновляет токен и повторяет один раз. */
	private Response authorized(HttpCall call) throws IOException, InterruptedException {
		String usedToken = mod.tokens().accessToken;
		Response response = call.run();
		if (response.status() == 401) {
			TwitchCraftClient.LOGGER.info("Токен Twitch истёк — обновляю...");
			if (TwitchAuth.refreshIfStale(mod.config().clientId, mod.tokens(), usedToken)) {
				response = call.run();
			}
		}
		return response;
	}

	@FunctionalInterface
	private interface HttpCall {
		Response run() throws IOException, InterruptedException;
	}
}
