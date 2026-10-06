package dev.dedworkshop.twitchcraft.donations;

import com.google.gson.JsonObject;
import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import dev.dedworkshop.twitchcraft.twitch.TwitchHttp;
import dev.dedworkshop.twitchcraft.util.Chat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * DonatePay: личный API-ключ (donatepay.ru → Настройки → API), донаты забираются опросом
 * GET /api/v1/transactions. По правилам DonatePay запросы можно делать не чаще раза в 20 секунд,
 * поэтому задержка доставки — до 20 секунд.
 */
public class DonatePayClient {
	/** Адрес можно переопределить системным свойством (нужно только для тестов). */
	static final String API = System.getProperty("twitchcraft.donatePayUrl", "https://donatepay.ru/api/v1");
	/** Минимальный интервал между запросами (правило DonatePay). В тестах уменьшается свойством. */
	static final long MIN_INTERVAL_MS = Long.getLong("twitchcraft.donatePayMinIntervalMs", 20_000L);
	private static final long PENDING_MAX_AGE_MS = 60 * 60 * 1000L;
	private static final int PAGE_LIMIT = 50;

	private final TwitchCraftClient mod;
	/** Незавершённые платежи (status=wait): id → когда впервые увидели. */
	private final Map<Long, Long> pending = new HashMap<>();
	private volatile boolean wantConnected;
	private volatile boolean ready;
	private volatile boolean keyRejected;
	private volatile String lastError = "";
	private volatile long lastRequestAt;
	private volatile long lastPollAt;
	private volatile int donationsReceived;
	private ScheduledFuture<?> pollTask;

	public DonatePayClient(TwitchCraftClient mod) {
		this.mod = mod;
	}

	// ---------- Состояние ----------

	public boolean isConfigured() {
		return mod.donationStore().hasDonatePay();
	}

	public boolean isActive() {
		return wantConnected && ready;
	}

	public int donationsReceived() {
		return donationsReceived;
	}

	public String statusText() {
		DonationStore store = mod.donationStore();
		if (!store.hasDonatePay()) {
			return "§7не подключён (нет API-ключа)";
		}
		String who = store.dpUserName == null || store.dpUserName.isBlank() ? "" : " §7(" + store.dpUserName + ")";
		if (keyRejected) {
			return "§cAPI-ключ отклонён — проверь ключ" + who;
		}
		if (!wantConnected) {
			return "§7отключено" + who;
		}
		if (ready) {
			long ago = lastPollAt == 0 ? -1 : (System.currentTimeMillis() - lastPollAt) / 1000;
			return "§aподключено" + who + " §7(опрос каждые " + pollSeconds() + " с" + (ago >= 0 ? ", последний " + ago + " с назад" : "")
					+ (donationsReceived > 0 ? ", донатов: " + donationsReceived : "") + ")"
					+ (lastError.isEmpty() ? "" : " §eпоследняя ошибка: " + lastError);
		}
		return "§eподключение..." + who + (lastError.isEmpty() ? "" : " §7(" + lastError + ")");
	}

	private int pollSeconds() {
		return Math.max((int) (MIN_INTERVAL_MS / 1000), mod.config().donations.donatePayPollSeconds);
	}

	// ---------- Ключ ----------

	/** Проверяет ключ запросом профиля, сохраняет и подключается. */
	public void setKey(String key) {
		String clean = key == null ? "" : key.trim();
		if (clean.isEmpty()) {
			Chat.error("DonatePay: ключ пустой. Возьми его на donatepay.ru → Настройки → API.");
			return;
		}
		disconnect();
		mod.worker().execute(() -> {
			try {
				waitForRateLimit();
				TwitchHttp.Response response = TwitchHttp.get(API + "/user?access_token=" + encode(clean), Map.of("Accept", "application/json"));
				lastRequestAt = System.currentTimeMillis();
				JsonObject json = response.json();
				String error = DonationParser.donatePayError(json);
				if (!response.ok() || error != null) {
					Chat.error("DonatePay не принял ключ: " + (error == null ? "HTTP " + response.status() : error));
					return;
				}
				JsonObject data = json.has("data") && json.get("data").isJsonObject() ? json.getAsJsonObject("data") : new JsonObject();
				DonationStore store = mod.donationStore();
				boolean sameAccount = clean.equals(store.dpApiKey);
				store.dpApiKey = clean;
				store.dpUserId = data.has("id") && !data.get("id").isJsonNull() ? data.get("id").getAsString() : "";
				store.dpUserName = DonationParser.str(data, "name");
				if (!sameAccount) {
					store.dpCursor = 0;
					store.dpSeen = new ArrayList<>();
				}
				store.save();
				keyRejected = false;
				Chat.success("DonatePay: ключ принят, аккаунт §d" + store.dpUserName + "§r. Подключаюсь...");
				connect(false);
			} catch (Exception e) {
				TwitchCraftClient.LOGGER.error("DonatePay: ошибка проверки ключа", e);
				Chat.error("DonatePay: не удалось проверить ключ: " + e.getMessage());
			}
		});
	}

	public void logout() {
		disconnect();
		mod.donationStore().clearDonatePay();
		keyRejected = false;
		Chat.info("DonatePay: ключ удалён.");
	}

	// ---------- Опрос ----------

	public synchronized void connect(boolean verbose) {
		if (!mod.donationStore().hasDonatePay()) {
			if (verbose) {
				Chat.warn("DonatePay: сначала укажи API-ключ — /twitch donations dp key <ключ>");
			}
			return;
		}
		if (pollTask != null && !pollTask.isDone()) {
			if (verbose) {
				Chat.info("DonatePay: уже подключено.");
			}
			return;
		}
		wantConnected = true;
		keyRejected = false;
		lastError = "";
		ready = false;
		int period = pollSeconds();
		long sinceLast = System.currentTimeMillis() - lastRequestAt;
		long initial = sinceLast >= MIN_INTERVAL_MS ? 1 : Math.max(1, (MIN_INTERVAL_MS - sinceLast) / 1000 + 1);
		pollTask = mod.scheduler().scheduleWithFixedDelay(() -> mod.worker().execute(this::pollSafely), initial, period, TimeUnit.SECONDS);
		if (verbose) {
			Chat.info("DonatePay: подключаюсь (опрос каждые " + period + " с)...");
		}
	}

	public synchronized void disconnect() {
		wantConnected = false;
		ready = false;
		if (pollTask != null) {
			pollTask.cancel(false);
			pollTask = null;
		}
	}

	private void pollSafely() {
		if (!wantConnected) {
			return;
		}
		try {
			poll();
		} catch (Exception e) {
			lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			TwitchCraftClient.LOGGER.warn("DonatePay: ошибка опроса: {}", lastError);
		}
	}

	/** Один опрос: первый раз — только запоминаем последний id (историю не проигрываем), дальше — всё новое. */
	void poll() throws Exception {
		DonationStore store = mod.donationStore();
		String key = store.dpApiKey;
		if (key == null || key.isBlank()) {
			disconnect();
			return;
		}
		String url;
		boolean firstRun = store.dpCursor <= 0;
		if (firstRun) {
			url = API + "/transactions?access_token=" + encode(key) + "&type=donation&limit=1&order=DESC";
		} else {
			long after = store.dpCursor;
			synchronized (pending) {
				for (Long id : pending.keySet()) {
					after = Math.min(after, id - 1);
				}
			}
			url = API + "/transactions?access_token=" + encode(key) + "&type=donation&limit=" + PAGE_LIMIT + "&order=ASC&after=" + after;
		}
		if (lastRequestAt > 0 && System.currentTimeMillis() - lastRequestAt < MIN_INTERVAL_MS) {
			return; // слишком рано — подождём следующего срабатывания, чтобы не нарушать лимит DonatePay
		}
		TwitchHttp.Response response = TwitchHttp.get(url, Map.of("Accept", "application/json"));
		lastRequestAt = System.currentTimeMillis();
		lastPollAt = lastRequestAt;
		JsonObject json = response.json();
		if (response.status() == 401 || response.status() == 403) {
			keyRejected = true;
			lastError = "ключ отклонён (HTTP " + response.status() + ")";
			disconnect();
			Chat.error("DonatePay: API-ключ отклонён. Проверь ключ: /twitch donations dp key <ключ>");
			return;
		}
		String error = DonationParser.donatePayError(json);
		if (!response.ok() || error != null) {
			String text = error == null ? "HTTP " + response.status() : error;
			if (text.toLowerCase().contains("token") || text.toLowerCase().contains("ключ") || text.toLowerCase().contains("unauth")) {
				keyRejected = true;
				disconnect();
				Chat.error("DonatePay: API-ключ отклонён (" + text + "). Проверь ключ: /twitch donations dp key <ключ>");
				return;
			}
			throw new IllegalStateException(text);
		}
		lastError = "";
		List<DonationParser.DonatePayTx> list = DonationParser.donatePayTransactions(json);
		if (firstRun) {
			long max = 0;
			for (DonationParser.DonatePayTx tx : list) {
				max = Math.max(max, tx.id());
				if (tx.isPending()) {
					// самый свежий платёж ещё «в ожидании» — запомним, чтобы не потерять его, когда он пройдёт
					synchronized (pending) {
						pending.putIfAbsent(tx.id(), System.currentTimeMillis());
					}
				}
			}
			// ещё нет ни одной транзакции — ставим курсор на 1, чтобы следующий опрос забирал всё новое
			store.dpCursor = Math.max(1, max);
			store.save();
			if (!ready) {
				ready = true;
				Chat.success("DonatePay: §aподключено§r — новые донаты будут приходить в игру (задержка до " + pollSeconds() + " с).");
			}
			return;
		}
		ready = true;
		handle(list, store);
	}

	/** Обрабатывает порцию транзакций: выдаёт события, двигает курсор, помнит незавершённые платежи (public — для тестов). */
	public void handle(List<DonationParser.DonatePayTx> list, DonationStore store) {
		long now = System.currentTimeMillis();
		long maxFinal = store.dpCursor;
		boolean changed = false;
		for (DonationParser.DonatePayTx tx : list) {
			if (tx.isPending()) {
				synchronized (pending) {
					Long since = pending.get(tx.id());
					if (since == null) {
						pending.put(tx.id(), now);
					} else if (now - since > PENDING_MAX_AGE_MS) {
						pending.remove(tx.id()); // платёж так и не прошёл за час — забываем
						store.dpMarkSeen(tx.id());
						changed = true;
					}
				}
				continue;
			}
			synchronized (pending) {
				pending.remove(tx.id());
			}
			maxFinal = Math.max(maxFinal, tx.id());
			if (store.dpWasSeen(tx.id())) {
				continue;
			}
			store.dpMarkSeen(tx.id());
			changed = true;
			if (!tx.isSuccess() && !tx.isTest()) {
				continue; // cancel и прочее
			}
			TwitchEvent event = DonationParser.donatePay(tx, tx.isTest());
			donationsReceived++;
			if (event.amount() < mod.config().donations.minAmount) {
				TwitchCraftClient.LOGGER.info("DonatePay: донат {} меньше minAmount — пропущен", event.shortText());
				continue;
			}
			if (tx.isTest()) {
				Chat.info("§7[DonatePay] тестовый донат из кабинета — выполняю как тест");
			}
			mod.onTwitchEvent(event);
		}
		// курсор: всё до maxFinal обработано; незавершённые платежи с меньшим id мы перечитаем через after=min(pending)-1
		if (maxFinal != store.dpCursor) {
			store.dpCursor = maxFinal;
			changed = true;
		}
		if (changed) {
			store.save();
		}
	}

	/** Пауза перед проверкой ключа, чтобы не нарушить правило «не чаще раза в 20 секунд» (только при вводе ключа). */
	private void waitForRateLimit() throws InterruptedException {
		long wait = MIN_INTERVAL_MS - (System.currentTimeMillis() - lastRequestAt);
		if (wait > 0 && lastRequestAt > 0) {
			Thread.sleep(wait);
		}
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
