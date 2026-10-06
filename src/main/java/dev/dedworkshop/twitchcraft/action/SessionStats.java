package dev.dedworkshop.twitchcraft.action;

import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Статистика текущей игровой сессии и история последних событий.
 * Используется для /twitch stats, /twitch history, оверлея и плейсхолдеров {session_*}.
 */
public class SessionStats {
	private static final int HISTORY_LIMIT = 100;

	/** Запись истории. */
	public record Entry(long time, TwitchEvent event, String status) {
	}

	public int follows, subs, resubs, giftedSubs, bits, cheers, raids, raidViewers, rewards, rewardPoints, chatMessages, commands, events, goals;
	public int donations, donationSum;
	public long startedAt = System.currentTimeMillis();

	private final Deque<Entry> history = new ArrayDeque<>();

	/** Учитывает событие (кроме обычных сообщений чата — они только считаются). */
	public synchronized void record(TwitchEvent event, String status) {
		if (event.type() == TwitchEvent.Type.CHAT) {
			chatMessages++;
			return;
		}
		if (event.synthetic()) {
			// тесты и повторы — только в историю, в счётчики не попадают
			addHistory(event, status);
			return;
		}
		if (event.type() == TwitchEvent.Type.GOAL || event.type() == TwitchEvent.Type.FUND) {
			goals++;
			addHistory(event, status);
			return;
		}
		events++;
		switch (event.type()) {
			case FOLLOW -> follows++;
			case SUBSCRIBE -> subs++;
			case RESUB -> resubs++;
			case GIFT_SUB -> giftedSubs += event.amount();
			case CHEER -> {
				cheers++;
				bits += event.amount();
			}
			case RAID -> {
				raids++;
				raidViewers += event.amount();
			}
			case REWARD -> {
				rewards++;
				rewardPoints += event.amount();
			}
			case CHAT_COMMAND -> commands++;
			case DONATION -> {
				donations++;
				donationSum += event.amount();
			}
			default -> {
			}
		}
		addHistory(event, status);
	}

	private void addHistory(TwitchEvent event, String status) {
		history.addFirst(new Entry(System.currentTimeMillis(), event, status));
		while (history.size() > HISTORY_LIMIT) {
			history.removeLast();
		}
	}

	/** Обновляет статус последней записи (например, «выполнено» → «возврат»). */
	public synchronized void updateLastStatus(TwitchEvent event, String status) {
		Entry first = history.peekFirst();
		if (first != null && first.event() == event) {
			history.pollFirst();
			history.addFirst(new Entry(first.time(), event, status));
		}
	}

	/** Последние n событий, новые первыми. */
	public synchronized List<Entry> recent(int n) {
		List<Entry> result = new ArrayList<>();
		for (Entry entry : history) {
			if (result.size() >= n) {
				break;
			}
			result.add(entry);
		}
		return result;
	}

	public synchronized TwitchEvent lastEvent() {
		Entry first = history.peekFirst();
		return first == null ? null : first.event();
	}

	public synchronized void reset() {
		follows = subs = resubs = giftedSubs = bits = cheers = raids = raidViewers = rewards = rewardPoints = chatMessages = commands = events = goals = 0;
		startedAt = System.currentTimeMillis();
		history.clear();
	}

	public synchronized Map<String, String> placeholders() {
		Map<String, String> vars = new LinkedHashMap<>();
		vars.put("session_follows", String.valueOf(follows));
		vars.put("session_subs", String.valueOf(subs + resubs));
		vars.put("session_new_subs", String.valueOf(subs));
		vars.put("session_resubs", String.valueOf(resubs));
		vars.put("session_gifts", String.valueOf(giftedSubs));
		vars.put("session_bits", String.valueOf(bits));
		vars.put("session_raids", String.valueOf(raids));
		vars.put("session_rewards", String.valueOf(rewards));
		vars.put("session_points", String.valueOf(rewardPoints));
		vars.put("session_commands", String.valueOf(commands));
		vars.put("session_events", String.valueOf(events));
		vars.put("session_goals", String.valueOf(goals));
		vars.put("session_donations", String.valueOf(donations));
		vars.put("session_donation_sum", String.valueOf(donationSum));
		vars.put("session_minutes", String.valueOf(minutes()));
		return vars;
	}

	/**
	 * Плейсхолдеры {session_*} «с учётом текущего события»: оно ещё не записано в статистику
	 * (запишется после выполнения), а в сообщении хочется видеть «это уже 5-й фоллов», а не 4-й.
	 */
	public synchronized Map<String, String> placeholdersIncluding(TwitchEvent event) {
		Map<String, String> vars = placeholders();
		if (event == null || event.synthetic() || event.type() == TwitchEvent.Type.CHAT) {
			return vars;
		}
		switch (event.type()) {
			case FOLLOW -> bump(vars, "session_follows", 1);
			case SUBSCRIBE -> {
				bump(vars, "session_subs", 1);
				bump(vars, "session_new_subs", 1);
			}
			case RESUB -> {
				bump(vars, "session_subs", 1);
				bump(vars, "session_resubs", 1);
			}
			case GIFT_SUB -> bump(vars, "session_gifts", event.amount());
			case CHEER -> bump(vars, "session_bits", event.amount());
			case RAID -> bump(vars, "session_raids", 1);
			case REWARD -> {
				bump(vars, "session_rewards", 1);
				bump(vars, "session_points", event.amount());
			}
			case CHAT_COMMAND -> bump(vars, "session_commands", 1);
			case DONATION -> {
				bump(vars, "session_donations", 1);
				bump(vars, "session_donation_sum", event.amount());
			}
			case GOAL, FUND -> bump(vars, "session_goals", 1);
			default -> {
			}
		}
		if (event.type() != TwitchEvent.Type.GOAL && event.type() != TwitchEvent.Type.FUND) {
			bump(vars, "session_events", 1);
		}
		return vars;
	}

	private static void bump(Map<String, String> vars, String key, long by) {
		try {
			vars.put(key, String.valueOf(Long.parseLong(vars.getOrDefault(key, "0")) + by));
		} catch (NumberFormatException ignored) {
		}
	}

	public long minutes() {
		return Math.max(0, (System.currentTimeMillis() - startedAt) / 60000);
	}

	/** Короткая строка для оверлея. */
	public synchronized String summaryLine() {
		return summaryLine("");
	}

	/** Короткая строка для оверлея; сумма донатов показывается, если они были. */
	public synchronized String summaryLine(String currencySymbol) {
		String line = "❤" + follows + "  ★" + (subs + resubs + giftedSubs) + "  ◆" + bits + "  ⚡" + rewards;
		if (donations > 0) {
			line += "  " + (currencySymbol == null || currencySymbol.isBlank() ? "$" : currencySymbol) + donationSum;
		}
		return line;
	}

	/** Многострочный отчёт для чата. */
	public synchronized List<String> report() {
		List<String> lines = new ArrayList<>();
		lines.add("§7Сессия: §f" + minutes() + " мин§7, событий: §f" + events + "§7, сообщений чата: §f" + chatMessages);
		lines.add("§7Фолловы: §d" + follows + "  §7Сабы: §d" + subs + " §7(+ресабов §d" + resubs + "§7, подарено §d" + giftedSubs + "§7)");
		lines.add("§7Битсы: §b" + bits + " §7(" + cheers + " раз)  §7Рейды: §6" + raids + " §7(" + raidViewers + " зрителей)");
		lines.add("§7Награды: §5" + rewards + " §7(" + rewardPoints + " баллов)  §7Чат-команды: §a" + commands + "  §7Целей достигнуто: §6" + goals);
		lines.add("§7Донаты: §6" + donations + " §7на сумму §6" + donationSum);
		return lines;
	}
}
