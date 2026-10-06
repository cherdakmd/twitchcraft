package dev.dedworkshop.twitchcraft.donations;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Разбор ответов DonationAlerts и DonatePay в события мода (чистые функции — покрыты тестами). */
public final class DonationParser {
	private DonationParser() {
	}

	// ---------- DonationAlerts ----------

	/**
	 * Донат DonationAlerts (объект из Centrifugo-канала $alerts:donation_* или из /alerts/donations).
	 * Поля: id, username, message, amount, currency, amount_in_user_currency.
	 *
	 * @param mainCurrency основная валюта из настроек; если валюта доната другая — берём amount_in_user_currency
	 */
	public static TwitchEvent donationAlerts(JsonObject d, String mainCurrency) {
		if (d == null) {
			return null;
		}
		String currency = str(d, "currency").toUpperCase(Locale.ROOT);
		double amount = number(d, "amount");
		String main = mainCurrency == null ? "" : mainCurrency.trim().toUpperCase(Locale.ROOT);
		String resultCurrency = currency;
		if (!main.isEmpty() && !main.equals(currency) && d.has("amount_in_user_currency") && !d.get("amount_in_user_currency").isJsonNull()) {
			amount = number(d, "amount_in_user_currency");
			resultCurrency = main;
		}
		String id = d.has("id") && !d.get("id").isJsonNull() ? d.get("id").getAsString() : "";
		return TwitchEvent.donation(TwitchEvent.SOURCE_DONATION_ALERTS, str(d, "username"), toInt(amount), resultCurrency,
				str(d, "message"), id, false);
	}

	/**
	 * Публикация Centrifugo (протокол v2): {"result":{"channel":"...","data":{"data":{...донат...}}}}.
	 *
	 * @return объект доната или null, если это не публикация (ответ на команду, join/leave и т.п.)
	 */
	public static JsonObject centrifugoPublication(JsonObject message) {
		if (message == null || message.has("id") || !message.has("result") || !message.get("result").isJsonObject()) {
			return null;
		}
		JsonObject result = message.getAsJsonObject("result");
		if (!result.has("channel") || result.has("type") || !result.has("data") || !result.get("data").isJsonObject()) {
			return null;
		}
		JsonObject data = result.getAsJsonObject("data");
		if (data.has("data") && data.get("data").isJsonObject()) {
			return data.getAsJsonObject("data");
		}
		return null;
	}

	// ---------- DonatePay ----------

	/** Транзакция DonatePay из GET /api/v1/transactions. */
	public record DonatePayTx(long id, String status, String name, String comment, double sum, String currency) {
		public boolean isSuccess() {
			return "success".equals(status);
		}

		/** Тестовый донат, созданный в кабинете DonatePay («Создать фейковое оповещение»). */
		public boolean isTest() {
			return "user".equals(status);
		}

		/** Платёж ещё не завершён — к нему нужно вернуться позже. */
		public boolean isPending() {
			return "wait".equals(status);
		}
	}

	/** Список транзакций из ответа DonatePay, отсортированный по id по возрастанию (только type=donation). */
	public static List<DonatePayTx> donatePayTransactions(JsonObject response) {
		List<DonatePayTx> list = new ArrayList<>();
		if (response == null || !response.has("data") || !response.get("data").isJsonArray()) {
			return list;
		}
		JsonArray data = response.getAsJsonArray("data");
		for (JsonElement element : data) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject tx = element.getAsJsonObject();
			String type = str(tx, "type");
			if (!type.isEmpty() && !"donation".equals(type)) {
				continue;
			}
			long id = (long) number(tx, "id");
			if (id <= 0) {
				continue;
			}
			JsonObject vars = tx.has("vars") && tx.get("vars").isJsonObject() ? tx.getAsJsonObject("vars") : null;
			String name = str(tx, "what");
			if (name.isEmpty() && vars != null) {
				name = str(vars, "name");
			}
			String comment = str(tx, "comment");
			if (comment.isEmpty() && vars != null) {
				comment = str(vars, "comment");
			}
			String currency = str(tx, "currency");
			if (currency.isEmpty() && vars != null) {
				currency = str(vars, "currency");
			}
			list.add(new DonatePayTx(id, str(tx, "status").toLowerCase(Locale.ROOT), name, comment, number(tx, "sum"),
					currency.isEmpty() ? "RUB" : currency.toUpperCase(Locale.ROOT)));
		}
		list.sort(Comparator.comparingLong(DonatePayTx::id));
		return list;
	}

	/** Ошибка в теле ответа DonatePay: {"status":"error","message":"..."} (HTTP при этом бывает 200). */
	public static String donatePayError(JsonObject response) {
		if (response == null) {
			return "пустой ответ";
		}
		String status = str(response, "status");
		if ("success".equals(status)) {
			return null;
		}
		String message = str(response, "message");
		if ("429".equals(status) || message.toLowerCase(Locale.ROOT).contains("too many")) {
			return "слишком частые запросы (лимит DonatePay — раз в 20 секунд)";
		}
		return message.isEmpty() ? (status.isEmpty() ? "неизвестный ответ" : "status=" + status) : message;
	}

	public static TwitchEvent donatePay(DonatePayTx tx, boolean synthetic) {
		return TwitchEvent.donation(TwitchEvent.SOURCE_DONATE_PAY, tx.name(), toInt(tx.sum()), tx.currency(), tx.comment(),
				String.valueOf(tx.id()), synthetic);
	}

	// ---------- Утилиты ----------

	/** Сумма → целое: округление вниз, отрицательные → 0. */
	public static int toInt(double amount) {
		if (Double.isNaN(amount) || amount <= 0) {
			return 0;
		}
		return (int) Math.min(Integer.MAX_VALUE, Math.floor(amount + 1e-9));
	}

	static String str(JsonObject obj, String key) {
		if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
			return "";
		}
		try {
			return obj.get(key).getAsString().trim();
		} catch (Exception e) {
			return "";
		}
	}

	static double number(JsonObject obj, String key) {
		if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
			return 0;
		}
		try {
			JsonElement element = obj.get(key);
			if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
				return Double.parseDouble(element.getAsString().trim().replace(',', '.').replace(" ", ""));
			}
			return element.getAsDouble();
		} catch (Exception e) {
			return 0;
		}
	}
}
