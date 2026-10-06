package dev.dedworkshop.twitchcraft.donations;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.twitch.TwitchHttp;

import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Минимальный клиент Centrifugo (JSON-протокол v2, который использует DonationAlerts):
 * connect → subscribe → публикации. Переподключением занимается владелец (через Handler.onClosed).
 *
 * Токены Centrifugo у DonationAlerts живут недолго (около 10 минут): сервер сообщает об этом полями
 * {@code expires}/{@code ttl} в ответах на connect и subscribe. Без продления он рвёт соединение
 * (а подписку снимает push-сообщением unsub) — поэтому клиент заранее просит владельца выдать
 * новые токены и отправляет команды refresh (10) и sub_refresh (11).
 */
public class CentrifugoClient implements WebSocket.Listener {
	private static final int METHOD_SUBSCRIBE = 1;
	private static final int METHOD_PING = 7;
	private static final int METHOD_REFRESH = 10;
	private static final int METHOD_SUB_REFRESH = 11;
	private static final int PUSH_PUBLICATION = 0;
	private static final int PUSH_UNSUB = 3;
	private static final int CONNECT_ID = 1;
	private static final long PING_SECONDS = 25;

	public interface Handler {
		/** Соединение установлено, сервер выдал id клиента (нужен для подписки на приватные каналы). */
		void onConnected(String clientId);

		void onSubscribed(String channel);

		void onPublication(String channel, JsonObject data);

		/** Соединение закрыто (кроме close() с нашей стороны). */
		void onClosed(String reason);

		/** Сервер ответил ошибкой на команду с данным id (1 — connect, остальные — subscribe/refresh). */
		void onCommandError(int id, int code, String message);

		/** Токен соединения скоро истечёт: нужно получить новый и вызвать {@link #refresh(String)}. */
		default void onRefreshNeeded() {
		}

		/** Токен подписки скоро истечёт: нужно получить новый и вызвать {@link #subRefresh(String, String)}. */
		default void onSubRefreshNeeded(String channel) {
		}

		/** Сервер снял подписку с канала (например, истёк токен подписки). */
		default void onUnsubscribed(String channel, boolean resubscribe) {
		}
	}

	private enum Kind { CONNECT, SUBSCRIBE, PING, REFRESH, SUB_REFRESH }

	private record Pending(Kind kind, String channel) {
	}

	private final String url;
	private final Handler handler;
	private final ScheduledExecutorService scheduler;
	private final AtomicInteger nextId = new AtomicInteger(2);
	private final StringBuilder buffer = new StringBuilder();
	private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
	private volatile WebSocket socket;
	private volatile boolean closedByUs;
	private volatile boolean connected;
	private volatile long lastMessageAt = System.currentTimeMillis();
	private ScheduledFuture<?> pingTask;
	private ScheduledFuture<?> refreshTask;
	private ScheduledFuture<?> subRefreshTask;
	private String connectToken = "";
	private volatile int refreshCount;
	private volatile int subRefreshCount;

	public CentrifugoClient(String url, Handler handler, ScheduledExecutorService scheduler) {
		this.url = url;
		this.handler = handler;
		this.scheduler = scheduler;
	}

	public boolean isConnected() {
		return connected && socket != null && !closedByUs;
	}

	public long lastMessageAt() {
		return lastMessageAt;
	}

	/** Сколько раз продлевали токен соединения (для статуса и тестов). */
	public int refreshCount() {
		return refreshCount;
	}

	/** Сколько раз продлевали токен подписки (для статуса и тестов). */
	public int subRefreshCount() {
		return subRefreshCount;
	}

	/** Открывает WebSocket и отправляет команду connect с токеном. */
	public void connect(String token) {
		this.connectToken = token == null ? "" : token;
		TwitchHttp.CLIENT.newWebSocketBuilder()
				.connectTimeout(Duration.ofSeconds(15))
				.buildAsync(URI.create(url), this)
				.whenComplete((ws, error) -> {
					if (error != null && !closedByUs) {
						TwitchCraftClient.LOGGER.warn("Centrifugo: не удалось подключиться к {}: {}", url, error.toString());
						handler.onClosed("ошибка подключения: " + rootMessage(error));
					}
				});
	}

	public void subscribe(String channel, String token) {
		JsonObject params = new JsonObject();
		params.addProperty("channel", channel);
		if (token != null && !token.isBlank()) {
			params.addProperty("token", token);
		}
		command(METHOD_SUBSCRIBE, params, new Pending(Kind.SUBSCRIBE, channel));
	}

	/** Продлевает соединение новым connection-токеном (ответ на onRefreshNeeded). */
	public void refresh(String token) {
		JsonObject params = new JsonObject();
		params.addProperty("token", token == null ? "" : token);
		command(METHOD_REFRESH, params, new Pending(Kind.REFRESH, ""));
	}

	/** Продлевает подписку на приватный канал новым токеном (ответ на onSubRefreshNeeded). */
	public void subRefresh(String channel, String token) {
		JsonObject params = new JsonObject();
		params.addProperty("channel", channel);
		params.addProperty("token", token == null ? "" : token);
		command(METHOD_SUB_REFRESH, params, new Pending(Kind.SUB_REFRESH, channel));
	}

	private void command(int method, JsonObject params, Pending what) {
		int id = nextId.getAndIncrement();
		pending.put(id, what);
		JsonObject command = new JsonObject();
		command.add("params", params);
		command.addProperty("method", method);
		command.addProperty("id", id);
		send(command.toString());
	}

	public void close() {
		closedByUs = true;
		connected = false;
		stopPing();
		cancel(refreshTask);
		cancel(subRefreshTask);
		refreshTask = null;
		subRefreshTask = null;
		pending.clear();
		WebSocket ws = socket;
		if (ws != null) {
			try {
				ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
			} catch (Exception ignored) {
			}
			ws.abort();
		}
	}

	private void send(String text) {
		WebSocket ws = socket;
		if (ws == null || closedByUs) {
			return;
		}
		try {
			ws.sendText(text, true);
		} catch (Exception e) {
			TwitchCraftClient.LOGGER.warn("Centrifugo: ошибка отправки: {}", e.toString());
		}
	}

	private void startPing() {
		stopPing();
		if (scheduler == null || scheduler.isShutdown()) {
			return;
		}
		pingTask = scheduler.scheduleAtFixedRate(() -> {
			if (!isConnected()) {
				return;
			}
			command(METHOD_PING, new JsonObject(), new Pending(Kind.PING, ""));
		}, PING_SECONDS, PING_SECONDS, TimeUnit.SECONDS);
	}

	private void stopPing() {
		cancel(pingTask);
		pingTask = null;
	}

	private static void cancel(ScheduledFuture<?> task) {
		if (task != null) {
			task.cancel(false);
		}
	}

	/**
	 * Планирует продление по полям expires/ttl из ответа сервера: за 10 секунд до истечения
	 * (для коротких ttl — на половине срока), чтобы запрос нового токена через REST успел пройти.
	 */
	private ScheduledFuture<?> scheduleExpiry(ScheduledFuture<?> previous, JsonObject result, Runnable action) {
		cancel(previous);
		if (result == null || !result.has("expires") || !result.get("expires").getAsBoolean()) {
			return null;
		}
		long ttl = result.has("ttl") ? result.get("ttl").getAsLong() : 0;
		if (ttl <= 0 || scheduler == null || scheduler.isShutdown()) {
			return null;
		}
		long delay = ttl > 20 ? ttl - 10 : Math.max(1, ttl / 2);
		return scheduler.schedule(() -> {
			if (isConnected()) {
				try {
					action.run();
				} catch (Exception e) {
					TwitchCraftClient.LOGGER.warn("Centrifugo: ошибка продления токена: {}", e.toString());
				}
			}
		}, delay, TimeUnit.SECONDS);
	}

	// ---------- WebSocket.Listener ----------

	@Override
	public void onOpen(WebSocket webSocket) {
		socket = webSocket;
		lastMessageAt = System.currentTimeMillis();
		webSocket.request(1);
		JsonObject params = new JsonObject();
		params.addProperty("token", connectToken);
		JsonObject command = new JsonObject();
		command.add("params", params);
		command.addProperty("id", CONNECT_ID);
		pending.put(CONNECT_ID, new Pending(Kind.CONNECT, ""));
		send(command.toString()); // method 0 (connect) по умолчанию — так делает и официальный клиент
	}

	@Override
	public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
		buffer.append(data);
		if (last) {
			String text = buffer.toString();
			buffer.setLength(0);
			lastMessageAt = System.currentTimeMillis();
			// Centrifugo может прислать несколько JSON-объектов, разделённых переводом строки
			for (String line : text.split("\n")) {
				String trimmed = line.trim();
				if (trimmed.isEmpty()) {
					continue;
				}
				if (trimmed.equals("{}")) {
					send("{}"); // ping протокола v3+: отвечаем пустым объектом
					continue;
				}
				try {
					JsonElement element = JsonParser.parseString(trimmed);
					if (element.isJsonObject()) {
						handle(element.getAsJsonObject());
					}
				} catch (Exception e) {
					TwitchCraftClient.LOGGER.warn("Centrifugo: не разобрано сообщение {}: {}", trimmed, e.toString());
				}
			}
		}
		webSocket.request(1);
		return null;
	}

	@Override
	public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
		connected = false;
		stopPing();
		cancel(refreshTask);
		cancel(subRefreshTask);
		if (!closedByUs) {
			handler.onClosed("закрыто сервером (" + statusCode + (reason == null || reason.isBlank() ? "" : " " + reason) + ")");
		}
		return null;
	}

	@Override
	public void onError(WebSocket webSocket, Throwable error) {
		connected = false;
		stopPing();
		cancel(refreshTask);
		cancel(subRefreshTask);
		if (!closedByUs) {
			handler.onClosed("ошибка соединения: " + rootMessage(error));
		}
	}

	private void handle(JsonObject message) {
		if (message.has("id")) {
			int id = message.get("id").getAsInt();
			Pending what = pending.remove(id);
			if (message.has("error") && message.get("error").isJsonObject()) {
				JsonObject error = message.getAsJsonObject("error");
				int code = error.has("code") ? error.get("code").getAsInt() : 0;
				String text = error.has("message") ? error.get("message").getAsString() : "";
				handler.onCommandError(id, code, text);
				return;
			}
			JsonObject result = message.has("result") && message.get("result").isJsonObject() ? message.getAsJsonObject("result") : new JsonObject();
			Kind kind = what == null ? (id == CONNECT_ID ? Kind.CONNECT : Kind.PING) : what.kind();
			switch (kind) {
				case CONNECT -> {
					connected = true;
					startPing();
					refreshTask = scheduleExpiry(refreshTask, result, handler::onRefreshNeeded);
					String client = result.has("client") ? result.get("client").getAsString() : "";
					handler.onConnected(client);
				}
				case SUBSCRIBE -> {
					String channel = what.channel();
					subRefreshTask = scheduleExpiry(subRefreshTask, result, () -> handler.onSubRefreshNeeded(channel));
					handler.onSubscribed(channel);
				}
				case REFRESH -> {
					refreshCount++;
					refreshTask = scheduleExpiry(refreshTask, result, handler::onRefreshNeeded);
				}
				case SUB_REFRESH -> {
					subRefreshCount++;
					String channel = what.channel();
					subRefreshTask = scheduleExpiry(subRefreshTask, result, () -> handler.onSubRefreshNeeded(channel));
				}
				default -> {
				}
			}
			return;
		}
		if (!message.has("result") || !message.get("result").isJsonObject()) {
			return;
		}
		JsonObject result = message.getAsJsonObject("result");
		int type = result.has("type") ? result.get("type").getAsInt() : PUSH_PUBLICATION;
		String channel = result.has("channel") ? result.get("channel").getAsString() : "";
		if (type == PUSH_PUBLICATION) {
			JsonObject publication = DonationParser.centrifugoPublication(message);
			if (publication != null) {
				handler.onPublication(channel, publication);
			}
		} else if (type == PUSH_UNSUB) {
			cancel(subRefreshTask);
			boolean resubscribe = result.has("data") && result.get("data").isJsonObject()
					&& result.getAsJsonObject("data").has("resubscribe")
					&& result.getAsJsonObject("data").get("resubscribe").getAsBoolean();
			handler.onUnsubscribed(channel, resubscribe);
		}
		// type 1/2 (join/leave), 4 (message), 5 (sub) — нам не нужны
	}

	private static String rootMessage(Throwable error) {
		Throwable t = error;
		while (t.getCause() != null && t.getCause() != t) {
			t = t.getCause();
		}
		return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
	}
}
