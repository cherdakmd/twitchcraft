package dev.dedworkshop.twitchcraft.twitch;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.util.Chat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

/**
 * Отправка сообщений в чат Twitch от имени стримера (нужно право user:write:chat).
 *
 * Сообщения идут через очередь с паузой между отправками, чтобы не упереться
 * в лимит Twitch (20 сообщений за 30 секунд) и не блокировать игру.
 */
public class ChatSender {
	public static final String SCOPE = "user:write:chat";
	private static final long MIN_INTERVAL_MS = 1500;
	private static final int MAX_QUEUE = 20;
	private static final int MAX_LENGTH = 500;

	private final TwitchCraftClient mod;
	private final Deque<String> queue = new ArrayDeque<>();
	/** Последние отправленные нами тексты — чтобы наш же ответ, вернувшись через EventSub, не сработал как команда. */
	private final Deque<String> recentlySent = new ArrayDeque<>();
	private boolean sending;
	private long lastSentAt;
	private volatile boolean warnedNoScope;

	public ChatSender(TwitchCraftClient mod) {
		this.mod = mod;
	}

	public boolean available() {
		return mod.tokens().hasScope(SCOPE) && !mod.tokens().userId.isBlank();
	}

	/** Автоответ зрителю — только если включён модуль «Ответы в чат». */
	public void reply(String text) {
		if (!mod.config().isEnabled(Module.CHAT_REPLIES)) {
			return;
		}
		send(text, false);
	}

	/**
	 * Отправить сообщение.
	 *
	 * @param verbose показывать ошибки игроку (для команды /twitch say)
	 */
	public void send(String text, boolean verbose) {
		String message = clean(text);
		if (message.isEmpty()) {
			return;
		}
		if (!available()) {
			if (verbose) {
				Chat.error("Нет права " + SCOPE + ". Выполни §e/twitch logout§c и §e/twitch login§c, чтобы выдать его.");
			} else if (!warnedNoScope) {
				warnedNoScope = true;
				Chat.warn("Ответы в чат Twitch отключены: нет права " + SCOPE + " (перелогинься: /twitch logout → /twitch login).");
			}
			return;
		}
		synchronized (queue) {
			if (queue.size() >= MAX_QUEUE) {
				TwitchCraftClient.LOGGER.warn("Очередь сообщений в чат переполнена, сообщение отброшено: {}", message);
				return;
			}
			queue.addLast(message);
			if (sending) {
				return;
			}
			sending = true;
		}
		mod.worker().execute(() -> drain(verbose));
	}

	private void drain(boolean verbose) {
		while (true) {
			String next;
			synchronized (queue) {
				next = queue.pollFirst();
				if (next == null) {
					sending = false;
					return;
				}
			}
			try {
				long wait = lastSentAt + MIN_INTERVAL_MS - System.currentTimeMillis();
				if (wait > 0 && deferDrain(next, wait, verbose)) {
					return; // продолжим по таймеру, не занимая общий рабочий поток сном
				}
				if (wait > 0) {
					Thread.sleep(wait);
				}
				remember(next);
				String error = mod.api().sendChatMessage(mod.tokens().userId, next);
				lastSentAt = System.currentTimeMillis();
				if (error != null) {
					TwitchCraftClient.LOGGER.warn("Не удалось отправить сообщение в чат Twitch: {}", error);
					if (verbose) {
						Chat.error("Не удалось отправить в чат: " + error);
					}
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				synchronized (queue) {
					sending = false;
				}
				return;
			} catch (Exception e) {
				TwitchCraftClient.LOGGER.warn("Ошибка отправки в чат Twitch: {}", e.toString());
				if (verbose) {
					Chat.error("Ошибка отправки в чат: " + e.getMessage());
				}
			}
		}
	}

	/**
	 * Возвращает сообщение в начало очереди и планирует продолжение через {@code wait} мс.
	 * Worker — один на весь мод (награды, DonatePay, чат), и спать в нём полторы секунды на каждое сообщение нельзя.
	 *
	 * @return false, если планировщик недоступен (тогда вызывающий просто подождёт сам)
	 */
	private boolean deferDrain(String next, long wait, boolean verbose) {
		synchronized (queue) {
			queue.addFirst(next);
		}
		try {
			mod.scheduler().schedule(() -> mod.worker().execute(() -> drain(verbose)), wait, TimeUnit.MILLISECONDS);
			return true;
		} catch (Exception e) {
			synchronized (queue) {
				queue.pollFirst();
			}
			return false;
		}
	}

	private void remember(String text) {
		synchronized (recentlySent) {
			recentlySent.addLast(text.trim());
			while (recentlySent.size() > 30) {
				recentlySent.pollFirst();
			}
		}
	}

	/** Это сообщение недавно отправили мы сами? */
	public boolean wasSentByUs(String text) {
		if (text == null) {
			return false;
		}
		synchronized (recentlySent) {
			return recentlySent.contains(text.trim());
		}
	}

	/** Убирает цветовые коды и переводы строк; обрезает до лимита Twitch. */
	public static String clean(String text) {
		if (text == null) {
			return "";
		}
		String cleaned = text
				.replaceAll("§[0-9a-fk-orA-FK-OR]", "")
				.replaceAll("&([0-9a-fk-orA-FK-OR])", "")
				.replace("§", "")
				.replaceAll("\\p{Cntrl}", " ")
				.trim();
		if (cleaned.length() > MAX_LENGTH) {
			cleaned = cleaned.substring(0, MAX_LENGTH);
		}
		// Сообщения, начинающиеся с "/" или ".", Twitch трактует как команды чата — не даём этого сделать случайно
		if (cleaned.startsWith("/") || cleaned.startsWith(".")) {
			cleaned = " " + cleaned;
		}
		return cleaned;
	}
}
