package dev.dedworkshop.twitchcraft.donations;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import dev.dedworkshop.twitchcraft.util.Chat;

import java.util.ArrayList;
import java.util.List;

/**
 * Управляет сервисами донатов: автоподключение при входе в мир, реакция на переключение модулей,
 * статус и тестовые донаты. Сервисы независимы от Twitch — работают даже без входа в Twitch.
 */
public class DonationManager {
	private final TwitchCraftClient mod;
	private final DonationAlertsClient donationAlerts;
	private final DonatePayClient donatePay;
	private volatile boolean manuallyDisconnected;

	public DonationManager(TwitchCraftClient mod) {
		this.mod = mod;
		this.donationAlerts = new DonationAlertsClient(mod);
		this.donatePay = new DonatePayClient(mod);
	}

	public DonationAlertsClient donationAlerts() {
		return donationAlerts;
	}

	public DonatePayClient donatePay() {
		return donatePay;
	}

	/** Хоть один сервис настроен (есть токен или ключ). */
	public boolean anyConfigured() {
		return donationAlerts.isConfigured() || donatePay.isConfigured();
	}

	public boolean anyActive() {
		return donationAlerts.isActive() || donatePay.isActive();
	}

	/** Автоподключение при входе в мир: только включённые и настроенные сервисы. */
	public void autoConnect() {
		if (manuallyDisconnected || !mod.config().autoConnect) {
			return;
		}
		if (mod.isModuleEnabled(Module.DONATION_ALERTS) && donationAlerts.isConfigured() && !donationAlerts.isActive()) {
			donationAlerts.connect(false);
		}
		if (mod.isModuleEnabled(Module.DONATE_PAY) && donatePay.isConfigured() && !donatePay.isActive()) {
			donatePay.connect(false);
		}
	}

	/** Подключить всё, что настроено (команда /twitch donations connect). */
	public void connectAll() {
		manuallyDisconnected = false;
		if (!anyConfigured()) {
			Chat.warn("Сервисы донатов не настроены. Введи §e/twitch donations§r для инструкции.");
			return;
		}
		if (donationAlerts.isConfigured()) {
			if (mod.isModuleEnabled(Module.DONATION_ALERTS)) {
				donationAlerts.connect(true);
			} else {
				Chat.warn("Модуль DonationAlerts выключен: /twitch module donationAlerts on");
			}
		}
		if (donatePay.isConfigured()) {
			if (mod.isModuleEnabled(Module.DONATE_PAY)) {
				donatePay.connect(true);
			} else {
				Chat.warn("Модуль DonatePay выключен: /twitch module donatePay on");
			}
		}
	}

	public void disconnectAll() {
		manuallyDisconnected = true;
		donationAlerts.disconnect();
		donatePay.disconnect();
		Chat.info("Сервисы донатов отключены.");
	}

	/** Переключили модуль или изменили настройки — применяем на лету. */
	public void onModuleChanged(Module module, boolean enabled) {
		if (module == Module.DONATION_ALERTS) {
			if (enabled) {
				if (donationAlerts.isConfigured() && !donationAlerts.isActive()) {
					donationAlerts.connect(false);
				}
			} else {
				donationAlerts.disconnect();
			}
		} else if (module == Module.DONATE_PAY) {
			if (enabled) {
				if (donatePay.isConfigured() && !donatePay.isActive()) {
					donatePay.connect(false);
				}
			} else {
				donatePay.disconnect();
			}
		}
	}

	/**
	 * Приводит подключения в соответствие с конфигом после /twitch reload или сохранения настроек:
	 * выключенный модуль отключается, включённый — подключается (если не было ручного отключения).
	 */
	public void syncWithConfig() {
		for (Module module : new Module[] {Module.DONATION_ALERTS, Module.DONATE_PAY}) {
			boolean enabled = mod.isModuleEnabled(module);
			if (!enabled) {
				onModuleChanged(module, false);
			} else if (!manuallyDisconnected && mod.config().autoConnect) {
				onModuleChanged(module, true);
			}
		}
	}

	/** Тестовый донат (без сервисов): source — "test", "donationalerts" или "donatepay". */
	public void test(String source, int amount, String message) {
		String src = source == null || source.isBlank() ? "test" : source;
		TwitchEvent event = TwitchEvent.donation(src, "TestDonator", amount, mod.config().donations.currency, message, "", true);
		Chat.info("§7[тест] Имитирую донат " + amount + " " + TwitchEvent.currencySymbol(mod.config().donations.currency)
				+ (src.equals("test") ? "" : " через " + event.sourceTitle()));
		mod.onTwitchEvent(event);
	}

	public List<String> statusLines() {
		List<String> lines = new ArrayList<>();
		lines.add("§7DonationAlerts" + moduleMark(Module.DONATION_ALERTS) + ": " + donationAlerts.statusText());
		lines.add("§7DonatePay" + moduleMark(Module.DONATE_PAY) + ": " + donatePay.statusText());
		return lines;
	}

	private String moduleMark(Module module) {
		return mod.isModuleEnabled(module) ? "" : " §8[модуль выкл]§7";
	}

	/** Короткая метка для оверлея: "DA● DP○" (только для настроенных сервисов), пусто — если ничего не настроено. */
	public String overlayMark() {
		StringBuilder sb = new StringBuilder();
		if (donationAlerts.isConfigured() && mod.isModuleEnabled(Module.DONATION_ALERTS)) {
			sb.append("DA").append(donationAlerts.isActive() ? "●" : "○");
		}
		if (donatePay.isConfigured() && mod.isModuleEnabled(Module.DONATE_PAY)) {
			if (sb.length() > 0) {
				sb.append(' ');
			}
			sb.append("DP").append(donatePay.isActive() ? "●" : "○");
		}
		return sb.toString();
	}

	public void shutdown() {
		donationAlerts.cancelLogin();
		donationAlerts.disconnect();
		donatePay.disconnect();
	}
}
