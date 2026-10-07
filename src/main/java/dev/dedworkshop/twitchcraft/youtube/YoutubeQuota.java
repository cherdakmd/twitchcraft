package dev.dedworkshop.twitchcraft.youtube;

import dev.dedworkshop.twitchcraft.game.GameStats;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Учёт дневной квоты YouTube Data API v3 (по умолчанию 10 000 единиц на проект,
 * сброс — в полночь по тихоокеанскому времени США).
 *
 * <p>Google не возвращает фактический расход в ответах, поэтому мод считает его сам по
 * опубликованной стоимости методов. Значения намеренно округлены в большую сторону:
 * для методов без опубликованной цены (например, {@code liveBroadcasts.transition})
 * берётся стоимость обычной записи — лучше показать запас, чем дать пользователю
 * исчерпать квоту.
 *
 * <p>Класс ничего не знает о сети и файлах: счётчик можно восстановить из
 * {@link YoutubeStore}, а сохранить — когда удобно вызывающему коду.
 */
public final class YoutubeQuota {
	/** Зона, в которой Google обнуляет дневную квоту. */
	public static final ZoneId RESET_ZONE = ZoneId.of("America/Los_Angeles");
	/** Дневной лимит нового проекта Google Cloud. */
	public static final int GOOGLE_DAILY_LIMIT = 10_000;

	/** liveChatMessages.list — основной расход при чтении чата. */
	public static final int COST_CHAT_LIST = 5;
	/** liveChatMessages.insert (сообщение бота, ответ зрителю). */
	public static final int COST_CHAT_INSERT = 50;
	/** liveChatMessages.delete (удаление сообщения). */
	public static final int COST_CHAT_DELETE = 50;
	/** liveChatBans.insert (бан/тайм-аут зрителя). */
	public static final int COST_BAN_INSERT = 50;
	/** liveChatBans.delete (разбан). */
	public static final int COST_BAN_DELETE = 50;
	/** liveBroadcasts.list (поиск активной трансляции). */
	public static final int COST_BROADCAST_LIST = 1;
	/** liveBroadcasts.update (заголовок/описание эфира). */
	public static final int COST_BROADCAST_UPDATE = 50;
	/** liveBroadcasts.transition (тест/эфир/завершить) — точная цена не опубликована, считаем с запасом. */
	public static final int COST_BROADCAST_TRANSITION = 50;
	/** channels.list (канал вошедшего аккаунта). */
	public static final int COST_CHANNEL_LIST = 1;
	/** videos.list (liveStreamingDetails: зрители и время начала). */
	public static final int COST_VIDEO_LIST = 1;

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

	private final Object lock = new Object();
	private String clientId = "";
	private String day = "";
	private int used;

	public YoutubeQuota() {
	}

	public YoutubeQuota(String clientId) {
		this.clientId = clientId == null ? "" : clientId;
	}

	/** Ключ текущих квотных суток (дата в тихоокеанской зоне). */
	public static String dayKey() {
		return LocalDate.now(RESET_ZONE).toString();
	}

	/** Сколько миллисекунд осталось до сброса квоты Google. */
	public static long millisUntilReset() {
		ZonedDateTime now = ZonedDateTime.now(RESET_ZONE);
		return java.time.Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.getZone())).toMillis();
	}

	/** Во сколько (по часам пользователя) сбросится квота Google. */
	public static String resetTimeText() {
		return ZonedDateTime.now(RESET_ZONE).toLocalDate().plusDays(1).atStartOfDay(RESET_ZONE)
				.withZoneSameInstant(ZoneId.systemDefault()).format(TIME);
	}

	/**
	 * Привязать счётчик к OAuth Client ID: при смене проекта Google счётчик обнуляется,
	 * потому что квота считается на проект, а не на аккаунт.
	 */
	public void sync(String currentClientId) {
		String id = currentClientId == null ? "" : currentClientId.trim();
		synchronized (lock) {
			if (!id.equals(clientId)) {
				clientId = id;
				day = "";
				used = 0;
			}
			rollDay();
		}
	}

	/** Восстановить счётчик из сохранённых значений (день и Client ID должны совпасть). */
	public void restore(String savedClientId, String savedDay, int savedUsed) {
		synchronized (lock) {
			clientId = savedClientId == null ? "" : savedClientId.trim();
			rollDay();
			if (savedDay != null && savedDay.equals(day)) {
				used = Math.max(0, savedUsed);
			}
		}
	}

	/**
	 * Начислить расход и вернуть сумму за текущие квотные сутки.
	 * Переход через полночь по тихоокеанскому времени обнуляет счётчик.
	 */
	public int charge(int units) {
		synchronized (lock) {
			rollDay();
			if (units > 0) {
				used += units;
			}
			return used;
		}
	}

	/** Израсходовано единиц за текущие квотные сутки. */
	public int used() {
		synchronized (lock) {
			rollDay();
			return used;
		}
	}

	/** Client ID, к которому привязан счётчик. */
	public String clientId() {
		synchronized (lock) {
			return clientId;
		}
	}

	/**
	 * Дневной бюджет исчерпан.
	 *
	 * @param budget порог пользователя в единицах (0 или меньше — ограничение выключено,
	 *               остаётся только лимит Google)
	 */
	public boolean exhausted(int budget) {
		int limit = budget > 0 ? Math.min(budget, GOOGLE_DAILY_LIMIT) : GOOGLE_DAILY_LIMIT;
		return used() >= limit;
	}

	/** Осталось единиц до порога (не меньше нуля). */
	public int remaining(int budget) {
		int limit = budget > 0 ? Math.min(budget, GOOGLE_DAILY_LIMIT) : GOOGLE_DAILY_LIMIT;
		return Math.max(0, limit - used());
	}

	/** «≈1 234 / 9 000 ед., сброс в 10:00 (через 3 ч 05 мин)». */
	public String describe(int budget) {
		int limit = budget > 0 ? Math.min(budget, GOOGLE_DAILY_LIMIT) : GOOGLE_DAILY_LIMIT;
		int current = used();
		return "≈" + current + " / " + limit + " ед."
				+ (current >= limit ? " §c(исчерпана)" : "")
				+ ", сброс в " + resetTimeText() + " (через " + GameStats.formatDuration(millisUntilReset()) + ")";
	}

	/** Оценка стоимости одного часа чтения чата при заданном интервале опроса. */
	public static int unitsPerHour(int pollIntervalMillis, int costPerRequest) {
		int interval = Math.max(1000, pollIntervalMillis);
		return (int) Math.min(Integer.MAX_VALUE, 3_600_000L / interval * Math.max(0, costPerRequest));
	}

	private void rollDay() {
		String today = dayKey();
		if (!today.equals(day)) {
			day = today;
			used = 0;
		}
	}
}
