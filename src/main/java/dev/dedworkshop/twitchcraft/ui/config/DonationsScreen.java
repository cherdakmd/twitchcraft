package dev.dedworkshop.twitchcraft.ui.config;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.donations.DonatePayClient;
import dev.dedworkshop.twitchcraft.donations.DonationAlertsClient;
import dev.dedworkshop.twitchcraft.donations.DonationManager;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import dev.dedworkshop.twitchcraft.util.Chat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.net.URI;

/**
 * Донаты: DonationAlerts и DonatePay — вход, подключение, общие настройки и таблицы эффектов по сумме.
 * Настройки правятся в черновике и применяются кнопкой «Сохранить»; таблицы эффектов редактируются сразу.
 */
class DonationsScreen extends BaseScreen {
	private static final String DA_APPS_URL = "https://www.donationalerts.com/application/clients";
	private static final String DP_API_URL = "https://donatepay.ru/page/api";

	private final ModConfig.Donations draft = new ModConfig.Donations();
	private String dpKey = "";
	private String signature = "";
	private int ticks;

	DonationsScreen(TwitchCraftClient mod, Screen parent) {
		super(mod, parent, "Донаты");
		ModConfig.Donations current = mod.config().donations;
		draft.currency = current.currency;
		draft.minAmount = current.minAmount;
		draft.donationAlertsClientId = current.donationAlertsClientId;
		draft.donatePayPollSeconds = current.donatePayPollSeconds;
		draft.callbackPort = current.callbackPort;
		draft.showMessage = current.showMessage;
	}

	@Override
	protected void buildContent(LinearLayout content) {
		signature = signature();
		DonationManager donations = mod.donations();
		DonationAlertsClient da = donations.donationAlerts();
		DonatePayClient dp = donations.donatePay();

		String last = Chat.lastText();
		if (!last.isEmpty() && System.currentTimeMillis() - Chat.lastTime() < 120_000) {
			content.addChild(Widgets.clipped(font, Component.literal("» " + last).withStyle(ChatFormatting.YELLOW), Widgets.FULL));
			content.addChild(SpacerElement.height(2));
		}

		// ---------- Эффекты
		content.addChild(Widgets.header(font, "Эффекты за донаты"));
		content.addChild(Widgets.gray(font, "Срабатывает самый большой порог, не превышающий сумму (в основной валюте)"));
		GridLayout effects = new GridLayout().columnSpacing(10).rowSpacing(4);
		GridLayout.RowHelper effectRows = effects.createRowHelper(2);
		effectRows.addChild(Widgets.button("Общие эффекты (" + mod.config().donationTiers.size() + ")", Widgets.HALF,
				() -> open(new ActionListScreen(mod, this, ActionKind.TIER, "Эффекты за донаты (все сервисы)", mod.config().donationTiers, TwitchEvent.Type.DONATION)),
				"Используются для обоих сервисов, если у сервиса нет своей таблицы"));
		effectRows.addChild(Widgets.button("Тест доната", Widgets.HALF, () -> {
			if (!inWorld()) {
				Chat.warn("Тест работает только в мире.");
				return;
			}
			donations.test("test", 100, "Тест из настроек");
		}, "Имитировать донат на 100 (как /twitch test donation 100)"));
		effectRows.addChild(Widgets.button("Только DonationAlerts (" + mod.config().donationAlertsTiers.size() + ")", Widgets.HALF,
				() -> open(new ActionListScreen(mod, this, ActionKind.TIER, "Эффекты DonationAlerts", mod.config().donationAlertsTiers, TwitchEvent.Type.DONATION)),
				"Если таблица не пуста — DonationAlerts использует её вместо общей"));
		effectRows.addChild(Widgets.button("Только DonatePay (" + mod.config().donatePayTiers.size() + ")", Widgets.HALF,
				() -> open(new ActionListScreen(mod, this, ActionKind.TIER, "Эффекты DonatePay", mod.config().donatePayTiers, TwitchEvent.Type.DONATION)),
				"Если таблица не пуста — DonatePay использует её вместо общей"));
		effectRows.addChild(Widgets.button("Ценник по умолчанию (81)", Widgets.HALF, () -> confirm("Загрузить ценник по умолчанию?",
				"Общая таблица (" + mod.config().donationTiers.size() + " записей) будет заменена: 25 плохих ☠, 25 хороших ★, 30 сверхсобытий (от 6000) и «Спасибо». "
						+ "Копия старого конфига сохранится рядом с ним.", () -> {
					dev.dedworkshop.twitchcraft.util.SafeFiles.backupCopy(ModConfig.path(), ".bak-" + java.time.LocalDate.now());
					mod.config().donationTiers = dev.dedworkshop.twitchcraft.config.DonationPresets.defaults();
					mod.configEdited();
					Chat.success("Ценник донатов загружен: " + mod.config().donationTiers.size() + " записей.");
				}), "Вернуть готовый ценник: 25 ☠ + 25 ★ + 30 сверхсобытий. Посмотреть: /twitch donations prices"));
		effectRows.addChild(Widgets.button("Показать ценник в чате", Widgets.HALF, () -> {
			if (!inWorld()) {
				Chat.warn("Чат игры доступен только в мире.");
				return;
			}
			dev.dedworkshop.twitchcraft.command.TwitchCommands.printDonationPrices(mod);
		}, "То же, что /twitch donations prices"));
		content.addChild(effects);

		// ---------- Общие настройки
		section(content, "Общие настройки");
		GridLayout general = Widgets.form();
		int r = 0;
		row(general, r++, "Валюта (код)", Widgets.textField(font, Widgets.FIELD, draft.currency, v -> draft.currency = v.trim().toUpperCase(), "RUB, USD, EUR..."));
		row(general, r++, "Мин. сумма", Widgets.intField(font, Widgets.FIELD, draft.minAmount, 0, 1_000_000, v -> draft.minAmount = v));
		row(general, r++, "Текст донатера", Widgets.toggle("Показывать", draft.showMessage, v -> draft.showMessage = v, Widgets.FIELD,
				"Показывать сообщение донатера в чате Minecraft"));
		content.addChild(general);

		// ---------- DonationAlerts
		section(content, "DonationAlerts" + (mod.isModuleEnabled(Module.DONATION_ALERTS) ? "" : " (модуль выключен)"));
		content.addChild(Widgets.clipped(font, Component.literal("Статус: ").withStyle(ChatFormatting.GRAY).append(Component.literal(da.statusText())), Widgets.FULL));
		GridLayout daForm = Widgets.form();
		r = 0;
		row(daForm, r++, "Client ID", Widgets.textField(font, Widgets.FIELD, draft.donationAlertsClientId,
				v -> draft.donationAlertsClientId = v.trim(), "ID приложения (число)"));
		row(daForm, r++, "Порт входа", Widgets.intField(font, Widgets.FIELD, draft.callbackPort, 1024, 65535, v -> draft.callbackPort = v));
		content.addChild(daForm);
		content.addChild(Widgets.gray(font, "Redirect URI приложения: " + da.redirectUri() + "  (сохрани настройки перед входом)"));
		GridLayout daButtons = new GridLayout().columnSpacing(10).rowSpacing(4);
		GridLayout.RowHelper daRows = daButtons.createRowHelper(2);
		daRows.addChild(Widgets.button("Открыть страницу приложений", Widgets.HALF,
				() -> ConfirmLinkScreen.confirmLinkNow(this, URI.create(DA_APPS_URL)),
				"donationalerts.com → Приложения: создай приложение и укажи Redirect URI"));
		daRows.addChild(Widgets.button("Скопировать Redirect URI", Widgets.HALF, () -> {
			minecraft.keyboardHandler.setClipboard(da.redirectUri());
			Chat.info("Скопировано: " + da.redirectUri());
		}));
		daRows.addChild(Widgets.button(daLoginLabel(), Widgets.HALF, this::daLoginOrLogout,
				"Вход через браузер (разреши доступ, токен придёт в игру сам)"));
		daRows.addChild(Widgets.button(da.isActive() ? "Отключить DonationAlerts" : "Подключить DonationAlerts", Widgets.HALF, () -> {
			if (da.isActive()) {
				da.disconnect();
			} else {
				da.connect(true);
			}
			refresh();
		}));
		content.addChild(daButtons);

		// ---------- DonatePay
		section(content, "DonatePay" + (mod.isModuleEnabled(Module.DONATE_PAY) ? "" : " (модуль выключен)"));
		content.addChild(Widgets.clipped(font, Component.literal("Статус: ").withStyle(ChatFormatting.GRAY).append(Component.literal(dp.statusText())), Widgets.FULL));
		GridLayout dpForm = Widgets.form();
		r = 0;
		row(dpForm, r++, "API-ключ", Widgets.textField(font, Widgets.FIELD, dpKey, v -> dpKey = v.trim(),
				dp.isConfigured() ? "ключ сохранён — введи новый, чтобы заменить" : "donatepay.ru → Настройки → API"));
		row(dpForm, r++, "Опрос, секунд", Widgets.intField(font, Widgets.FIELD, draft.donatePayPollSeconds, 20, 600, v -> draft.donatePayPollSeconds = v));
		content.addChild(dpForm);
		GridLayout dpButtons = new GridLayout().columnSpacing(10).rowSpacing(4);
		GridLayout.RowHelper dpRows = dpButtons.createRowHelper(2);
		dpRows.addChild(Widgets.button("Применить ключ", Widgets.HALF, () -> {
			if (dpKey.isEmpty()) {
				Chat.warn("Введи API-ключ DonatePay в поле выше.");
				return;
			}
			dp.setKey(dpKey);
			dpKey = "";
			refresh();
		}, "Проверить ключ и подключиться"));
		dpRows.addChild(Widgets.button("Где взять ключ", Widgets.HALF,
				() -> ConfirmLinkScreen.confirmLinkNow(this, URI.create(DP_API_URL)), "Откроется страница API DonatePay"));
		dpRows.addChild(Widgets.button(dp.isActive() ? "Отключить DonatePay" : "Подключить DonatePay", Widgets.HALF, () -> {
			if (dp.isActive()) {
				dp.disconnect();
			} else {
				dp.connect(true);
			}
			refresh();
		}));
		dpRows.addChild(Widgets.button("Удалить ключ", Widgets.HALF, () -> {
			if (!dp.isConfigured()) {
				Chat.warn("Ключ DonatePay не сохранён.");
				return;
			}
			confirm("Удалить ключ DonatePay?", "Опрос остановится; ключ можно будет ввести заново.", () -> {
				dp.logout();
				refresh();
			});
		}));
		content.addChild(dpButtons);
		content.addChild(SpacerElement.height(4));
		content.addChild(Widgets.gray(font, "Плейсхолдеры: {user} {amount} {sum} {currency} {message} {source} {player}"));
		content.addChild(SpacerElement.height(6));
	}

	@Override
	protected void buildFooter(LinearLayout footer) {
		footer.addChild(Widgets.button("Сохранить", Widgets.HALF, this::save));
		footer.addChild(Widgets.button("Отмена", Widgets.HALF, this::onClose));
	}

	@Override
	public void tick() {
		super.tick();
		if (++ticks % 10 == 0 && !signature.equals(signature())) {
			refresh();
		}
	}

	private String signature() {
		DonationAlertsClient da = mod.donations().donationAlerts();
		DonatePayClient dp = mod.donations().donatePay();
		String last = System.currentTimeMillis() - Chat.lastTime() < 120_000 ? Chat.lastText() : "";
		return da.isConfigured() + "|" + da.isActive() + "|" + da.isLoginInProgress() + "|" + dp.isConfigured() + "|" + dp.isActive()
				+ "|" + last + "|" + mod.config().donationTiers.size() + "|" + mod.config().donationAlertsTiers.size()
				+ "|" + mod.config().donatePayTiers.size();
	}

	private String daLoginLabel() {
		DonationAlertsClient da = mod.donations().donationAlerts();
		if (da.isLoginInProgress()) {
			return "Отменить вход";
		}
		return da.isConfigured() ? "Выйти из DonationAlerts" : "Войти в DonationAlerts";
	}

	private void daLoginOrLogout() {
		DonationAlertsClient da = mod.donations().donationAlerts();
		if (da.isLoginInProgress()) {
			da.cancelLogin();
			refresh();
			return;
		}
		if (da.isConfigured()) {
			confirm("Выйти из DonationAlerts?", "Токен будет удалён, подключение закрыто.", () -> {
				da.logout();
				refresh();
			});
			return;
		}
		applyDraft(); // чтобы Client ID и порт из полей точно использовались для входа
		String url = da.beginLogin();
		if (url != null) {
			ConfirmLinkScreen.confirmLinkNow(this, URI.create(url));
			refresh();
		}
	}

	private void applyDraft() {
		ModConfig.Donations target = mod.config().donations;
		target.currency = draft.currency;
		target.minAmount = draft.minAmount;
		target.donationAlertsClientId = draft.donationAlertsClientId;
		target.donatePayPollSeconds = draft.donatePayPollSeconds;
		target.callbackPort = draft.callbackPort;
		target.showMessage = draft.showMessage;
		mod.config().normalize();
		mod.configEdited();
	}

	private void save() {
		applyDraft();
		Chat.success("Настройки донатов сохранены.");
		onClose();
	}

	private void section(LinearLayout content, String title) {
		content.addChild(SpacerElement.height(4));
		content.addChild(Widgets.header(font, title));
	}

	private void row(GridLayout grid, int row, String label, LayoutElement field) {
		grid.addChild(Widgets.label(font, label), row, 0, settings -> settings.alignVerticallyMiddle().alignHorizontallyLeft());
		grid.addChild(field, row, 1);
	}
}
