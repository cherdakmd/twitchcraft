package dev.dedworkshop.twitchcraft.vk;

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
 * Минимальный клиент Centrifugo для VK Video Live (сервер Centrifugo v4+, JSON-протокол версии 2):
 * команды вида {"id":1,"connect":{...}}, пуши {"push":{"channel":..,"pub":{"data":..}}}, пинг — пустой объект {}.
 *
 * Отличается от {@link dev.dedworkshop.twitchcraft.donations.CentrifugoClient} (DonationAlerts, старый протокол
 * с числовыми методами). Переподключением занимается владелец (через Handler.onClosed).
 */
public class VkPubSub implements WebSocket.Listener {
	private static final int CONNECT_ID = 1;

	public interface Handler {
		void onConnected(String clientId);

		void onSubscribed(String channel);

		/** Публикация в канале: data — содержимое pub.data (у VK это {"type":..,"data":{..}}). */
		void onPublication(String channel, JsonObject data);

		/** Ошибка команды: id 1 — connect, остальные — subscribe/refresh. */
		void onCommandError(int id, String channel, int code, String message);

		/** Соединение закрыто (кроме close() с нашей стороны). */
		void onClosed(String reason);

		/** Токен соединения скоро истечёт — нужно получить новый и вызвать {@link #refresh(String)}. */
		default void onRefreshNeeded() {
		}

		/** Сервер снял подписку с канала. */
		default void onUnsubscribed(String channel, int code, String reason) {
		}
	}

	private enum Kind { CONNECT, SUBSCRIBE, REFRESH, SUB_REFRESH, OTHER }

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
	private volatile int pingsAnswered;
	private String connectToken = "";
	private ScheduledFuture<?> refreshTask;

	public VkPubSub(String url, Handler handler, ScheduledExecutorService scheduler) {
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

	/** Сколько пингов сервера мы отбили (для статуса и тестов). */
	public int pingsAnswered() {
		return pingsAnswered;
	}

	public void connect(String token) {
		this.connectToken = token == null ? "" : token;
		TwitchHttp.CLIENT.newWebSocketBuilder()
				.connectTimeout(Duration.ofSeconds(15))
				.buildAsync(URI.create(url), this)
				.whenComplete((ws, error) -> {
					if (error != null && !closedByUs) {
						TwitchCraftClient.LOGGER.warn("VK pubsub: не удалось подключиться к {}: {}", url, error.toString());
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
		command("subscribe", params, new Pending(Kind.SUBSCRIBE, channel));
	}

	public void refresh(String token) {
		JsonObject params = new JsonObject();
		params.addProperty("token", token == null ? "" : token);
		command("refresh", params, new Pending(Kind.REFRESH, ""));
	}

	public void subRefresh(String channel, String token) {
		JsonObject params = new JsonObject();
		params.addProperty("channel", channel);
		params.addProperty("token", token == null ? "" : token);
		command("sub_refresh", params, new Pending(Kind.SUB_REFRESH, channel));
	}

	private void command(String method, JsonObject params, Pending what) {
		int id = nextId.getAndIncrement();
		pending.put(id, what);
		JsonObject command = new JsonObject();
		command.addProperty("id", id);
		command.add(method, params);
		send(command.toString());
	}

	public void close() {
		closedByUs = true;
		connected = false;
		cancel(refreshTask);
		refreshTask = null;
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
			TwitchCraftClient.LOGGER.warn("VK pubsub: ошибка отправки: {}", e.toString());
		}
	}

	private static void cancel(ScheduledFuture<?> task) {
		if (task != null) {
			task.cancel(false);
		}
	}

	/** Планирует продление токена соединения по полям expires/ttl ответа на connect/refresh. */
	private void scheduleRefresh(JsonObject result) {
		cancel(refreshTask);
		refreshTask = null;
		if (result == null || !result.has("expires") || !result.get("expires").getAsBoolean()) {
			return;
		}
		long ttl = result.has("ttl") ? result.get("ttl").getAsLong() : 0;
		if (ttl <= 0 || scheduler == null || scheduler.isShutdown()) {
			return;
		}
		long delay = ttl > 20 ? ttl - 10 : Math.max(1, ttl / 2);
		refreshTask = scheduler.schedule(() -> {
			if (isConnected()) {
				try {
					handler.onRefreshNeeded();
				} catch (Exception e) {
					TwitchCraftClient.LOGGER.warn("VK pubsub: ошибка продления токена: {}", e.toString());
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
		params.addProperty("name", "twitchcraft");
		JsonObject command = new JsonObject();
		command.addProperty("id", CONNECT_ID);
		command.add("connect", params);
		pending.put(CONNECT_ID, new Pending(Kind.CONNECT, ""));
		send(command.toString());
	}

	@Override
	public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
		buffer.append(data);
		if (last) {
			String text = buffer.toString();
			buffer.setLength(0);
			lastMessageAt = System.currentTimeMillis();
			// несколько сообщений в одном кадре разделяются переводом строки
			for (String line : text.split("\n")) {
				String trimmed = line.trim();
				if (trimmed.isEmpty()) {
					continue;
				}
				if (trimmed.equals("{}")) {
					pingsAnswered++;
					send("{}"); // пинг сервера: отвечаем пустым объектом, иначе он закроет соединение
					continue;
				}
				try {
					JsonElement element = JsonParser.parseString(trimmed);
					if (element.isJsonObject()) {
						handle(element.getAsJsonObject());
					}
				} catch (Exception e) {
					TwitchCraftClient.LOGGER.warn("VK pubsub: не разобрано сообщение {}: {}", trimmed, e.toString());
				}
			}
		}
		webSocket.request(1);
		return null;
	}

	@Override
	public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
		connected = false;
		cancel(refreshTask);
		if (!closedByUs) {
			handler.onClosed("закрыто сервером (" + statusCode + (reason == null || reason.isBlank() ? "" : " " + reason) + ")");
		}
		return null;
	}

	@Override
	public void onError(WebSocket webSocket, Throwable error) {
		connected = false;
		cancel(refreshTask);
		if (!closedByUs) {
			handler.onClosed("ошибка соединения: " + rootMessage(error));
		}
	}

	private void handle(JsonObject message) {
		if (message.has("id")) {
			int id = message.get("id").getAsInt();
			Pending what = pending.remove(id);
			String channel = what == null ? "" : what.channel();
			if (message.has("error") && message.get("error").isJsonObject()) {
				JsonObject error = message.getAsJsonObject("error");
				int code = error.has("code") ? error.get("code").getAsInt() : 0;
				String text = error.has("message") ? error.get("message").getAsString() : "";
				handler.onCommandError(id, channel, code, text);
				return;
			}
			Kind kind = what == null ? (id == CONNECT_ID ? Kind.CONNECT : Kind.OTHER) : what.kind();
			switch (kind) {
				case CONNECT -> {
					JsonObject result = object(message, "connect");
					connected = true;
					scheduleRefresh(result);
					handler.onConnected(VkApi.str(result, "client"));
				}
				case SUBSCRIBE -> handler.onSubscribed(channel);
				case REFRESH -> scheduleRefresh(object(message, "refresh"));
				default -> {
				}
			}
			return;
		}
		JsonObject push = object(message, "push");
		if (push == null) {
			return;
		}
		String channel = VkApi.str(push, "channel");
		JsonObject pub = object(push, "pub");
		if (pub != null) {
			JsonObject data = object(pub, "data");
			if (data != null) {
				handler.onPublication(channel, data);
			}
			return;
		}
		JsonObject unsubscribe = object(push, "unsubscribe");
		if (unsubscribe != null) {
			int code = unsubscribe.has("code") ? unsubscribe.get("code").getAsInt() : 0;
			handler.onUnsubscribed(channel, code, VkApi.str(unsubscribe, "reason"));
			return;
		}
		JsonObject disconnect = object(push, "disconnect");
		if (disconnect != null) {
			connected = false;
			cancel(refreshTask);
			if (!closedByUs) {
				handler.onClosed("сервер попросил отключиться (" + VkApi.str(disconnect, "code") + " " + VkApi.str(disconnect, "reason") + ")");
			}
		}
		// join/leave/message/subscribe — не нужны
	}

	private static JsonObject object(JsonObject parent, String key) {
		if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) {
			return null;
		}
		return parent.getAsJsonObject(key);
	}

	private static String rootMessage(Throwable error) {
		Throwable t = error;
		while (t.getCause() != null && t.getCause() != t) {
			t = t.getCause();
		}
		return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
	}
}
