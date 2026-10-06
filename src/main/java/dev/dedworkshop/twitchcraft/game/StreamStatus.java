package dev.dedworkshop.twitchcraft.game;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.twitch.TwitchApi;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Состояние стрима Twitch (в эфире / время начала / зрители) — опрос GET /streams раз в минуту,
 * пока есть токен. Нужен для {stream_time} и {viewers}, а также чтобы не дёргать клипы вне эфира.
 */
public class StreamStatus {
	public static final long POLL_INTERVAL_MS = 60_000;

	private final TwitchCraftClient mod;
	private volatile TwitchApi.Stream stream;
	private volatile long checkedAt;
	private volatile boolean checking;

	public StreamStatus(TwitchCraftClient mod) {
		this.mod = mod;
	}

	/** null — ещё не проверяли (неизвестно). */
	public TwitchApi.Stream stream() {
		return stream;
	}

	/** Известно ли состояние (проверка была и не старше 5 минут). */
	public boolean known() {
		return stream != null && System.currentTimeMillis() - checkedAt < 5 * POLL_INTERVAL_MS;
	}

	public boolean live() {
		TwitchApi.Stream s = stream;
		return s != null && s.live();
	}

	public long checkedAt() {
		return checkedAt;
	}

	/** Для тестов и ручной установки. */
	public void set(TwitchApi.Stream value) {
		this.stream = value;
		this.checkedAt = System.currentTimeMillis();
	}

	public void forget() {
		stream = null;
		checkedAt = 0;
	}

	/** Вызывается планировщиком: если есть токен и прошло больше минуты — спрашивает Twitch. */
	public void poll(boolean force) {
		if (checking || mod.tokens() == null || !mod.tokens().hasTokens() || mod.tokens().userId.isBlank()) {
			return;
		}
		if (!force && System.currentTimeMillis() - checkedAt < POLL_INTERVAL_MS) {
			return;
		}
		checking = true;
		try {
			TwitchApi.Stream fresh = mod.api().getStream(mod.tokens().userId);
			boolean wasLive = live();
			stream = fresh;
			checkedAt = System.currentTimeMillis();
			if (fresh.live() != wasLive) {
				TwitchCraftClient.LOGGER.info("Twitch: стрим {}", fresh.live() ? "в эфире" : "не в эфире");
			}
		} catch (Exception e) {
			TwitchCraftClient.LOGGER.debug("Не удалось проверить состояние стрима: {}", e.toString());
		} finally {
			checking = false;
		}
	}

	/** {stream_time} {viewers} {stream_title} {stream_game} {live}. */
	public Map<String, String> placeholders() {
		Map<String, String> vars = new LinkedHashMap<>();
		TwitchApi.Stream s = stream;
		boolean live = s != null && s.live();
		vars.put("live", live ? "да" : "нет");
		vars.put("stream_time", live ? (s.startedAt() > 0 ? GameStats.formatDuration(System.currentTimeMillis() - s.startedAt()) : "неизвестно")
				: s == null ? "неизвестно" : "не в эфире");
		vars.put("viewers", live ? String.valueOf(s.viewers()) : "0");
		vars.put("stream_title", live ? s.title() : "");
		vars.put("stream_game", live ? s.game() : "");
		return vars;
	}

	/** Строка для /twitch game и оверлея. */
	public String describe() {
		TwitchApi.Stream s = stream;
		if (s == null) {
			return "§7неизвестно (нет данных от Twitch)";
		}
		if (!s.live()) {
			return "§7не в эфире";
		}
		return "§aв эфире§7 " + (s.startedAt() > 0 ? GameStats.formatDuration(System.currentTimeMillis() - s.startedAt()) : "")
				+ ", зрителей: §f" + s.viewers() + (s.title().isBlank() ? "" : "§7 — " + s.title());
	}
}
