package dev.dedworkshop.twitchcraft.game;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.action.Placeholders;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.module.Module;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Таймеры чата: периодические сообщения бота в Twitch, VK и YouTube.
 * <p>
 * Таймер срабатывает, когда прошёл его интервал И (если задано minChatMessages) с прошлого срабатывания
 * в чатах было достаточно сообщений — чтобы бот не разговаривал с пустым чатом вне эфира.
 * Если чат тихий, проверка повторяется раз в минуту, пока чат не оживёт.
 */
public class ChatTimers {
	private static final long RECHECK_MS = 60_000;

	/** Состояние одного таймера. */
	static final class State {
		long nextAt;
		int chatCountAtLastPost;
		int posts;
	}

	private final TwitchCraftClient mod;
	private final LongSupplier clock;
	private final IntSupplier chatCounter;
	private final Map<String, State> states = new HashMap<>();
	private long lastTickAt;

	public ChatTimers(TwitchCraftClient mod) {
		this(mod, System::currentTimeMillis, () -> mod.events() == null ? 0 : mod.events().stats().chatMessages);
	}

	/** Для тестов: своё время и свой счётчик сообщений чата. */
	public ChatTimers(TwitchCraftClient mod, LongSupplier clock, IntSupplier chatCounter) {
		this.mod = mod;
		this.clock = clock;
		this.chatCounter = chatCounter;
	}

	/** Раз в секунду достаточно; вызывать можно каждый тик. Возвращает, сколько таймеров сработало. */
	public int tick() {
		long now = clock.getAsLong();
		if (now - lastTickAt < 1000) {
			return 0;
		}
		lastTickAt = now;
		if (!mod.isModuleEnabled(Module.CHAT_TIMERS)) {
			return 0;
		}
		List<ModConfig.ChatTimer> timers = mod.config().timers;
		if (timers == null || timers.isEmpty()) {
			return 0;
		}
		int fired = 0;
		int chatCount = chatCounter.getAsInt();
		for (int i = 0; i < timers.size(); i++) {
			ModConfig.ChatTimer timer = timers.get(i);
			if (timer == null || !timer.enabled || timer.text == null || timer.text.isBlank()) {
				continue;
			}
			State state = states.computeIfAbsent(stateKey(timer, i), k -> {
				State s = new State();
				s.nextAt = now + timer.intervalMinutes * 60_000L;
				s.chatCountAtLastPost = chatCount;
				return s;
			});
			if (now < state.nextAt) {
				continue;
			}
			if (timer.minChatMessages > 0 && chatCount - state.chatCountAtLastPost < timer.minChatMessages) {
				state.nextAt = now + RECHECK_MS; // чат тихий — проверим через минуту
				continue;
			}
			post(timer, false);
			state.posts++;
			state.chatCountAtLastPost = chatCount;
			state.nextAt = now + timer.intervalMinutes * 60_000L;
			fired++;
		}
		return fired;
	}

	/** Отправить текст таймера сейчас (команда /twitch timers post). */
	public boolean post(ModConfig.ChatTimer timer, boolean verbose) {
		if (timer == null || timer.text == null || timer.text.isBlank()) {
			return false;
		}
		String text = Placeholders.apply(timer.text, mod.globalPlaceholders());
		send(timer, text, verbose);
		return true;
	}

	/** Отправка в чаты (переопределяется в тестах). */
	protected void send(ModConfig.ChatTimer timer, String text, boolean verbose) {
		mod.announce(text, timer.twitch, timer.vk, timer.youtube, verbose);
	}

	/** Сколько секунд до следующего срабатывания (или -1, если таймер не активен). */
	public long secondsLeft(ModConfig.ChatTimer timer) {
		List<ModConfig.ChatTimer> timers = mod.config().timers;
		int index = timers == null ? -1 : timers.indexOf(timer);
		State state = index < 0 ? null : states.get(stateKey(timer, index));
		if (state == null || !timer.enabled) {
			return -1;
		}
		return Math.max(0, (state.nextAt - clock.getAsLong()) / 1000);
	}

	public int posts(ModConfig.ChatTimer timer) {
		List<ModConfig.ChatTimer> timers = mod.config().timers;
		int index = timers == null ? -1 : timers.indexOf(timer);
		State state = index < 0 ? null : states.get(stateKey(timer, index));
		return state == null ? 0 : state.posts;
	}

	/** После правки списка таймеров: забыть состояния, которых больше нет, новые начнут отсчёт заново. */
	public void syncWithConfig() {
		List<ModConfig.ChatTimer> timers = mod.config().timers;
		if (timers == null) {
			states.clear();
			return;
		}
		java.util.Set<String> keep = new java.util.HashSet<>();
		for (int i = 0; i < timers.size(); i++) {
			if (timers.get(i) != null) {
				keep.add(stateKey(timers.get(i), i));
			}
		}
		states.keySet().retainAll(keep);
	}

	private static String stateKey(ModConfig.ChatTimer timer, int index) {
		String name = timer.name == null ? "" : timer.name.trim().toLowerCase(Locale.ROOT);
		return name.isEmpty() ? "#" + index : name;
	}
}
