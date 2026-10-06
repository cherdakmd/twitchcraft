package dev.dedworkshop.twitchcraft.twitch;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.util.Chat;

import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Подключение к Twitch EventSub по WebSocket.
 *
 * Схема работы:
 *  1. Открываем wss://eventsub.wss.twitch.tv/ws
 *  2. Twitch присылает session_welcome с id сессии.
 *  3. В течение 10 секунд создаём подписки через Helix API (TwitchApi.createSubscription).
 *  4. Дальше получаем notification-сообщения и keepalive каждые N секунд.
 *  5. Если Twitch просит переподключиться (session_reconnect) — открываем новый сокет по
 *     указанному адресу, подписки переносятся автоматически.
 *  6. Если связь потеряна — переподключаемся сами с нарастающей задержкой.
 */
public class EventSubClient {
	private static final int KEEPALIVE_SECONDS = 30;
	/** Адрес можно переопределить свойством -Dtwitchcraft.eventsubUrl=... (нужно только для тестов). */
	private static final String DEFAULT_URL = System.getProperty("twitchcraft.eventsubUrl",
			"wss://eventsub.wss.twitch.tv/ws?keepalive_timeout_seconds=" + KEEPALIVE_SECONDS);
	/** Если дольше этого нет ни одного сообщения — считаем соединение мёртвым. */
	private static final long SILENCE_LIMIT_MS = (KEEPALIVE_SECONDS + 15) * 1000L;
	private static final long CONNECT_LIMIT_MS = 30_000L;

	private final TwitchCraftClient mod;

	private volatile Session current;   // активная сессия
	private volatile Session pending;   // сессия, которая сейчас подключается
	private volatile boolean wantConnected;
	private volatile boolean subscribed;
	private volatile boolean chatSubscribed;
	private volatile int subscriptionCount;
	private volatile long lastMessageAt;
	private int reconnectAttempts;
	/** Сколько раз за сессию соединение рвалось (для статуса и чтобы не спамить в чат). */
	private int reconnectCount;
	private volatile boolean chatHintShown;
	private ScheduledFuture<?> watchdog;

	/** Идентификаторы уже обработанных сообщений — Twitch может прислать одно и то же дважды. */
	private final Set<String> seenMessageIds = new LinkedHashSet<>();

	public EventSubClient(TwitchCraftClient mod) {
		this.mod = mod;
	}

	// ---------- Публичное API ----------

	public synchronized void connect() {
		wantConnected = true;
		reconnectAttempts = 0;
		if (current != null || pending != null) {
			return;
		}
		open(DEFAULT_URL, false);
		if (watchdog == null) {
			watchdog = mod.scheduler().scheduleAtFixedRate(this::checkHealth, 10, 10, TimeUnit.SECONDS);
		}
	}

	public synchronized void disconnect() {
		wantConnected = false;
		subscribed = false;
		chatSubscribed = false;
		subscriptionCount = 0;
		Session c = current;
		Session p = pending;
		current = null;
		pending = null;
		if (c != null) {
			c.close();
		}
		if (p != null) {
			p.close();
		}
		if (watchdog != null) {
			watchdog.cancel(false);
			watchdog = null;
		}
	}

	/** Пользователь хочет быть подключённым (даже если сейчас идёт переподключение). */
	public boolean isActive() {
		return wantConnected;
	}

	/** Соединение установлено и подписки созданы. */
	public boolean isSubscribed() {
		return wantConnected && current != null && subscribed;
	}

	public int subscriptionCount() {
		return subscriptionCount;
	}

	public String statusText() {
		if (!wantConnected) {
			return "§7отключено";
		}
		if (isSubscribed()) {
			return "§aподключено§r (подписок: " + subscriptionCount + (chatSubscribed ? ", чат: да" : ", чат: нет")
					+ (reconnectCount > 0 ? ", переподключений: " + reconnectCount : "") + ")";
		}
		return "§eподключение...";
	}

	// ---------- Внутренняя логика ----------

	private void open(String url, boolean reconnectSession) {
		Session session = new Session(reconnectSession);
		pending = session;
		TwitchCraftClient.LOGGER.info("EventSub: подключение к {}", url);
		TwitchHttp.CLIENT.newWebSocketBuilder()
				.connectTimeout(Duration.ofSeconds(15))
				.buildAsync(URI.create(url), session)
				.whenComplete((ws, error) -> {
					if (error != null) {
						TwitchCraftClient.LOGGER.warn("EventSub: не удалось подключиться: {}", error.toString());
						onConnectionLost(session);
					}
				});
	}

	private synchronized void onConnectionLost(Session session) {
		if (!wantConnected) {
			return;
		}
		if (session == pending) {
			pending = null;
			if (current == null) {
				scheduleReconnect();
			}
			return;
		}
		if (session != current) {
			return; // это старая сессия, которую мы уже заменили
		}
		current = null;
		subscribed = false;
		if (pending == null) {
			scheduleReconnect();
		}
	}

	private void scheduleReconnect() {
		long delaySeconds = Math.min(60, 1L << Math.min(reconnectAttempts, 6));
		reconnectAttempts++;
		reconnectCount++;
		TwitchCraftClient.LOGGER.warn("EventSub: связь потеряна, переподключение через {} с (попытка {})", delaySeconds, reconnectAttempts);
		// В чат пишем только первые попытки, дальше — каждую десятую, чтобы не заспамить стрим
		if (reconnectAttempts <= 3 || reconnectAttempts % 10 == 0) {
			Chat.warn("Связь с Twitch потеряна. Переподключение через " + delaySeconds + " с..."
					+ (reconnectAttempts > 3 ? " (попытка " + reconnectAttempts + ")" : ""));
		}
		mod.scheduler().schedule(() -> {
			synchronized (this) {
				if (wantConnected && current == null && pending == null) {
					open(DEFAULT_URL, false);
				}
			}
		}, delaySeconds, TimeUnit.SECONDS);
	}

	/** Каждые 10 секунд: проверяем, что сервер не замолчал и подключение не зависло. */
	private void checkHealth() {
		try {
			checkHealthUnsafe();
		} catch (Exception e) {
			// Исключение внутри задачи scheduleAtFixedRate молча останавливает её навсегда — не даём этому случиться
			TwitchCraftClient.LOGGER.warn("EventSub: ошибка сторожевого таймера: {}", e.toString());
		}
	}

	private void checkHealthUnsafe() {
		if (!wantConnected) {
			return;
		}
		long now = System.currentTimeMillis();
		Session c = current;
		if (c != null && now - lastMessageAt > SILENCE_LIMIT_MS) {
			TwitchCraftClient.LOGGER.warn("EventSub: нет keepalive от Twitch — переподключаюсь");
			c.close();
			synchronized (this) {
				if (current == c) {
					current = null;
					subscribed = false;
					if (pending == null) {
						scheduleReconnect();
					}
				}
			}
		}
		Session p = pending;
		if (p != null && now - p.openedAt > CONNECT_LIMIT_MS) {
			TwitchCraftClient.LOGGER.warn("EventSub: подключение зависло — пробую заново");
			p.close();
			onConnectionLost(p);
		}
	}

	private boolean markSeen(String messageId) {
		synchronized (seenMessageIds) {
			if (!seenMessageIds.add(messageId)) {
				return false;
			}
			if (seenMessageIds.size() > 500) {
				Iterator<String> it = seenMessageIds.iterator();
				it.next();
				it.remove();
			}
			return true;
		}
	}

	/** Описание одной подписки EventSub. */
	private record SubscriptionSpec(String type, String version, Module module, String... conditionKeys) {
		JsonObject condition(String broadcasterId) {
			JsonObject condition = new JsonObject();
			for (String key : conditionKeys) {
				condition.addProperty(key, broadcasterId);
			}
			return condition;
		}
	}

	public static final String CHAT_SCOPE = "user:read:chat";
	public static final String CHAT_TYPE = "channel.chat.message";

	private static final List<SubscriptionSpec> BASE_SUBSCRIPTIONS = List.of(
			new SubscriptionSpec("channel.channel_points_custom_reward_redemption.add", "1", Module.CHANNEL_POINTS, "broadcaster_user_id"),
			new SubscriptionSpec("channel.subscribe", "1", Module.SUBSCRIPTIONS, "broadcaster_user_id"),
			new SubscriptionSpec("channel.subscription.gift", "1", Module.SUBSCRIPTIONS, "broadcaster_user_id"),
			new SubscriptionSpec("channel.subscription.message", "1", Module.SUBSCRIPTIONS, "broadcaster_user_id"),
			new SubscriptionSpec("channel.cheer", "1", Module.BITS, "broadcaster_user_id"),
			new SubscriptionSpec("channel.follow", "2", Module.FOLLOWS, "broadcaster_user_id", "moderator_user_id"),
			new SubscriptionSpec("channel.raid", "1", Module.RAIDS, "to_broadcaster_user_id")
	);

	/** Список подписок для текущих настроек: только включённые модули; чат — если он нужен и есть право. */
	private List<SubscriptionSpec> subscriptions() {
		List<SubscriptionSpec> list = new ArrayList<>();
		for (SubscriptionSpec spec : BASE_SUBSCRIPTIONS) {
			if (mod.config().isEnabled(spec.module())) {
				list.add(spec);
			}
		}
		if (mod.config().needsChat() && mod.tokens().hasScope(CHAT_SCOPE)) {
			// user_id = broadcaster: читаем чат от имени самого стримера
			list.add(new SubscriptionSpec(CHAT_TYPE, "1", Module.TWITCH_CHAT, "broadcaster_user_id", "user_id"));
		}
		return list;
	}

	/** Типы подписок, которые будут созданы при подключении (для экрана настроек и /twitch modules). */
	public List<String> plannedSubscriptionTypes() {
		List<String> types = new ArrayList<>();
		for (SubscriptionSpec spec : subscriptions()) {
			types.add(spec.type());
		}
		return types;
	}

	/** Подписки, созданные в текущей сессии. */
	private volatile List<String> activeSubscriptionTypes = List.of();

	/** Нужно ли переподключиться: настройки изменили набор подписок по сравнению с текущей сессией. */
	public boolean needsResubscribe() {
		return isSubscribed() && !plannedSubscriptionTypes().equals(activeSubscriptionTypes);
	}

	/** Подписка на чат активна в текущей сессии. */
	public boolean hasChat() {
		return chatSubscribed;
	}

	/** Создаёт все подписки для новой сессии. Вызывается в отдельном потоке (у Twitch лимит 10 секунд). */
	private void subscribeAll(String sessionId) {
		String broadcasterId = mod.tokens().userId;
		List<SubscriptionSpec> specs = subscriptions();
		List<String> planned = new ArrayList<>();
		for (SubscriptionSpec spec : specs) {
			planned.add(spec.type());
		}
		activeSubscriptionTypes = planned;
		int ok = 0;
		boolean chat = false;
		List<String> failed = new ArrayList<>();
		for (SubscriptionSpec spec : specs) {
			String error = createWithRetry(sessionId, spec, broadcasterId);
			if (error == null) {
				ok++;
				if (spec.type().equals(CHAT_TYPE)) {
					chat = true;
				}
			} else {
				failed.add(spec.type() + " → " + error);
			}
		}

		subscriptionCount = ok;
		chatSubscribed = chat;
		subscribed = ok > 0;
		for (String f : failed) {
			TwitchCraftClient.LOGGER.warn("EventSub: подписка не создана: {}", f);
		}

		if (ok == 0) {
			if (specs.isEmpty()) {
				Chat.warn("Все модули событий выключены — подписываться не на что. Включи модули: /twitch modules");
			} else {
				Chat.error("Не удалось создать ни одной подписки EventSub. Проверь права: /twitch logout → /twitch login");
			}
			disconnect();
			return;
		}
		boolean restored;
		synchronized (this) {
			restored = reconnectAttempts > 0 || reconnectCount > 0;
			reconnectAttempts = 0;
		}
		if (restored) {
			Chat.success("Соединение с Twitch восстановлено (подписок: " + ok + "/" + specs.size() + ").");
		} else {
			Chat.success("Подключено к Twitch как §d" + mod.tokens().displayOrLogin()
					+ "§a — подписок: " + ok + "/" + specs.size() + (chat ? " §7(+чат)" : ""));
		}
		if (!failed.isEmpty()) {
			Chat.warn("Часть подписок не создана (подробности в логе). Возможно, канал не аффилиат/партнёр.");
		}
		if (mod.config().needsChat() && !mod.tokens().hasScope(CHAT_SCOPE) && !chatHintShown) {
			chatHintShown = true;
			Chat.warn("Чат Twitch не подключён: нет права " + CHAT_SCOPE + ". Выполни §e/twitch logout§e → §e/twitch login§e.");
		}
	}

	/** Создаёт подписку; при сетевой ошибке пробует ещё дважды. Возвращает null при успехе. */
	private String createWithRetry(String sessionId, SubscriptionSpec spec, String broadcasterId) {
		String lastError = "";
		for (int attempt = 1; attempt <= 3; attempt++) {
			try {
				return mod.api().createSubscription(sessionId, spec.type(), spec.version(), spec.condition(broadcasterId));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return "прервано";
			} catch (Exception e) {
				lastError = e.getMessage();
				TwitchCraftClient.LOGGER.warn("EventSub: сетевая ошибка при подписке {} (попытка {}): {}", spec.type(), attempt, lastError);
				try {
					Thread.sleep(500L * attempt);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					return "прервано";
				}
			}
		}
		return lastError;
	}

	/** Расшифровка кодов закрытия Twitch EventSub. */
	public static String describeClose(int code) {
		return switch (code) {
			case 1000 -> "нормальное закрытие";
			case 1006 -> "соединение оборвалось";
			case 4000 -> "внутренняя ошибка сервера Twitch";
			case 4001 -> "клиент отправил сообщение (нельзя)";
			case 4002 -> "клиент не ответил на ping";
			case 4003 -> "подписки не созданы за 10 секунд";
			case 4004 -> "не переподключились вовремя после session_reconnect";
			case 4005 -> "сетевой таймаут";
			case 4006 -> "сетевая ошибка";
			case 4007 -> "неверный reconnect URL";
			default -> "код " + code;
		};
	}

	// ---------- Одна WebSocket-сессия ----------

	private class Session implements WebSocket.Listener {
		private final boolean reconnectSession;
		private final long openedAt = System.currentTimeMillis();
		private final StringBuilder buffer = new StringBuilder();
		private volatile WebSocket socket;
		private volatile boolean closedByUs;

		Session(boolean reconnectSession) {
			this.reconnectSession = reconnectSession;
		}

		void close() {
			closedByUs = true;
			WebSocket ws = socket;
			if (ws != null) {
				try {
					ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
				} catch (Exception ignored) {
				}
				ws.abort();
			}
		}

		@Override
		public void onOpen(WebSocket webSocket) {
			this.socket = webSocket;
			lastMessageAt = System.currentTimeMillis();
			webSocket.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			buffer.append(data);
			if (last) {
				String text = buffer.toString();
				buffer.setLength(0);
				try {
					handle(JsonParser.parseString(text).getAsJsonObject());
				} catch (Exception e) {
					TwitchCraftClient.LOGGER.error("EventSub: ошибка обработки сообщения: {}", text, e);
				}
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			TwitchCraftClient.LOGGER.info("EventSub: соединение закрыто ({} {}) — {}", statusCode, reason, describeClose(statusCode));
			if (!closedByUs) {
				onConnectionLost(this);
			}
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			TwitchCraftClient.LOGGER.warn("EventSub: ошибка соединения: {}", error.toString());
			if (!closedByUs) {
				onConnectionLost(this);
			}
		}

		private void handle(JsonObject message) {
			lastMessageAt = System.currentTimeMillis();
			JsonObject metadata = message.getAsJsonObject("metadata");
			JsonObject payload = message.getAsJsonObject("payload");
			String type = metadata.get("message_type").getAsString();

			switch (type) {
				case "session_welcome" -> {
					String sessionId = payload.getAsJsonObject("session").get("id").getAsString();
					TwitchCraftClient.LOGGER.info("EventSub: сессия {} открыта", sessionId);
					synchronized (EventSubClient.this) {
						Session old = current;
						current = this;
						if (pending == this) {
							pending = null;
						}
						if (reconnectSession) {
							// Подписки переехали на новую сессию — старую можно закрыть.
							if (old != null && old != this) {
								old.close();
							}
							// Если Twitch успел закрыть старый сокет раньше, onConnectionLost сбросил флаг — восстанавливаем
							if (subscriptionCount > 0) {
								subscribed = true;
							}
							reconnectAttempts = 0;
						} else {
							// Отдельный поток: у Twitch лимит 10 секунд на подписки, а общий worker может быть занят
							Thread thread = new Thread(() -> subscribeAll(sessionId), "TwitchCraft-Subscribe");
							thread.setDaemon(true);
							thread.start();
						}
					}
				}
				case "session_keepalive" -> {
					// Просто сигнал «я жив». lastMessageAt уже обновлён.
				}
				case "notification" -> {
					String messageId = metadata.get("message_id").getAsString();
					if (!markSeen(messageId)) {
						return;
					}
					String subscriptionType = metadata.get("subscription_type").getAsString();
					JsonObject event = payload.getAsJsonObject("event");
					TwitchEvent twitchEvent = TwitchEvent.fromEventSub(subscriptionType, event);
					if (twitchEvent != null) {
						mod.onTwitchEvent(twitchEvent);
					}
				}
				case "session_reconnect" -> {
					String url = payload.getAsJsonObject("session").get("reconnect_url").getAsString();
					TwitchCraftClient.LOGGER.info("EventSub: Twitch просит переподключиться");
					synchronized (EventSubClient.this) {
						if (wantConnected && pending == null) {
							open(url, true);
						}
					}
				}
				case "revocation" -> {
					JsonObject subscription = payload.getAsJsonObject("subscription");
					String subType = subscription.get("type").getAsString();
					String status = subscription.get("status").getAsString();
					Chat.warn("Twitch отозвал подписку " + subType + " (" + status + "). Попробуй /twitch logout → /twitch login");
					if (CHAT_TYPE.equals(subType)) {
						chatSubscribed = false;
					}
				}
				default -> TwitchCraftClient.LOGGER.debug("EventSub: неизвестный тип сообщения {}", type);
			}
		}
	}
}
