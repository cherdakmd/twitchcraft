package dev.dedworkshop.twitchcraft.ui.config;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.action.FundraiserTracker;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import dev.dedworkshop.twitchcraft.util.Chat;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.Screen;

import java.util.Map;

/**
 * Редактор одного сбора: имя, заголовок, цель, собранная сумма, внешний вид полосы, что считать и эффект закрытия.
 */
class FundraiserEditScreen extends BaseScreen {
	private static final Map<String, String> COLOR_NAMES = Map.of(
			"pink", "розовый", "blue", "синий", "red", "красный", "green", "зелёный",
			"yellow", "жёлтый", "purple", "фиолетовый", "white", "белый");
	private static final Map<String, String> STYLE_NAMES = Map.of(
			"progress", "сплошная", "notched_6", "6 делений", "notched_10", "10 делений",
			"notched_12", "12 делений", "notched_20", "20 делений");

	private final int index;
	private final ModConfig.Fundraiser draft;
	private final String originalName;
	private final double originalCurrent;
	private double current;

	FundraiserEditScreen(TwitchCraftClient mod, Screen parent, int index, ModConfig.Fundraiser original) {
		super(mod, parent, index < 0 ? "Новый сбор" : "Сбор: " + original.name);
		this.index = index;
		if (original == null) {
			ModConfig defaults = ModConfig.createDefault();
			ModConfig.Fundraiser sample = defaults.fundraisers.isEmpty() ? new ModConfig.Fundraiser() : defaults.fundraisers.get(0);
			draft = sample.copy();
			draft.name = "";
			draft.title = "";
			draft.target = 10000;
			originalName = null;
			originalCurrent = 0;
		} else {
			draft = original.copy();
			originalName = original.name;
			originalCurrent = mod.fundraisers().peek(original.name).current;
		}
		ModConfig.normalizeFundraiser(draft);
		current = originalCurrent;
	}

	@Override
	protected void buildContent(LinearLayout content) {
		String symbol = TwitchEvent.currencySymbol(mod.config().donations.currency);
		GridLayout form = Widgets.form();
		int r = 0;
		row(form, r++, "Имя", Widgets.textField(font, Widgets.FIELD, draft.name, v -> draft.name = v, "для команд: /twitch fund add <имя>"));
		row(form, r++, "Заголовок", Widgets.textField(font, Widgets.FIELD, draft.title, v -> draft.title = v, "над полосой; пусто — имя; &6 — цвет"));
		row(form, r++, "Цель, " + symbol, Widgets.intField(font, Widgets.FIELD, draft.target, 1, 1000000000, v -> draft.target = v));
		row(form, r++, "Собрано, " + symbol, Widgets.floatField(font, Widgets.FIELD, (float) current, 0, 1000000000f, v -> current = v));
		row(form, r++, "Цвет", Widgets.cycle("Цвет", draft.color, ModConfig.FUND_COLORS,
				c -> COLOR_NAMES.getOrDefault(c, c), c -> draft.color = c, Widgets.FIELD, null));
		row(form, r++, "Стиль", Widgets.cycle("Полоса", draft.style, ModConfig.FUND_STYLES,
				s -> STYLE_NAMES.getOrDefault(s, s), s -> draft.style = s, Widgets.FIELD, null));
		row(form, r++, "Формат текста", Widgets.textField(font, Widgets.FIELD, draft.format, v -> draft.format = v, ModConfig.DEFAULT_FUND_FORMAT));
		content.addChild(form);
		content.addChild(Widgets.gray(font, "Плейсхолдеры формата: {title} {current} {target} {left} {percent} {currency} {last} {last_amount} {donors}"));

		content.addChild(SpacerElement.height(6));
		content.addChild(Widgets.header(font, "Что засчитывать"));
		GridLayout sources = Widgets.form();
		r = 0;
		row(sources, r++, "Донаты", Widgets.toggle("DonationAlerts / DonatePay", draft.countDonations, v -> draft.countDonations = v, Widgets.FIELD,
				"Сумма доната в основной валюте (" + mod.config().donations.currency + ")"));
		row(sources, r++, "За 1 битс, " + symbol, Widgets.floatField(font, Widgets.FIELD, (float) draft.bitsRate, 0, 1000000f, v -> draft.bitsRate = v));
		row(sources, r++, "За подписку, " + symbol, Widgets.floatField(font, Widgets.FIELD, (float) draft.subValue, 0, 1000000f, v -> draft.subValue = v));
		row(sources, r++, "За 1 балл канала, " + symbol, Widgets.floatField(font, Widgets.FIELD, (float) draft.pointsRate, 0, 1000000f, v -> draft.pointsRate = v));
		content.addChild(sources);
		content.addChild(Widgets.gray(font, "0 — не считать. Например, 0.7 за битс: 100 битс = 70 " + symbol + "; 150 за подписку"));

		content.addChild(SpacerElement.height(6));
		content.addChild(Widgets.header(font, "Показ"));
		GridLayout show = Widgets.form();
		r = 0;
		row(show, r++, "Показывать", Widgets.toggle("Полоса", draft.visible, v -> draft.visible = v, Widgets.FIELD,
				"Выкл: полоса спрятана, но вклады продолжают считаться"));
		row(show, r++, "Включён", Widgets.toggle("Сбор", draft.enabled, v -> draft.enabled = v, Widgets.FIELD,
				"Выкл: сбор не считает вклады и не показывается"));
		row(show, r++, "Спрятать после закрытия, с", Widgets.intField(font, Widgets.FIELD, draft.hideWhenCompleteSeconds, 0, 86400,
				v -> draft.hideWhenCompleteSeconds = v));
		content.addChild(show);
		content.addChild(Widgets.gray(font, "0 — после закрытия полоса остаётся на экране заполненной"));

		content.addChild(SpacerElement.height(6));
		content.addChild(Widgets.header(font, "Когда сбор закрыт"));
		content.addChild(Widgets.gray(font, "Плейсхолдеры: {goal} {target} {sum} {currency} {times} {user} — чей вклад закрыл сбор"));
		content.addChild(Widgets.button(actionSummary(), Widgets.FULL,
				() -> open(new ActionEditScreen(mod, this, "Эффект закрытия сбора", ActionKind.GOAL, null, draft.action,
						a -> TwitchEvent.fund(draft.name.isBlank() ? "Сбор" : draft.name, Math.max(1, draft.target), 1,
								mod.config().donations.currency, "TestViewer", "testviewer", true),
						(key, saved) -> draft.action = saved)),
				"Сообщение, заголовок, звук, салют, ответ в чат, таблица лута"));
		content.addChild(SpacerElement.height(6));
	}

	@Override
	protected void buildFooter(LinearLayout footer) {
		footer.addChild(Widgets.button("Сохранить", Widgets.HALF, this::save));
		footer.addChild(Widgets.button("Отмена", Widgets.HALF, this::onClose));
	}

	private String actionSummary() {
		int commands = draft.action.commands == null ? 0 : draft.action.commands.size();
		int loot = draft.action.loot == null ? 0 : draft.action.loot.size();
		return "Эффект: команд " + commands + (loot > 0 ? ", лут " + loot : "") + "…";
	}

	private void save() {
		String name = draft.name == null ? "" : draft.name.trim();
		if (name.isEmpty()) {
			Chat.error("Укажи имя сбора.");
			return;
		}
		if (name.contains("\"")) {
			Chat.error("Имя сбора не может содержать кавычки.");
			return;
		}
		ModConfig config = mod.config();
		for (int i = 0; i < config.fundraisers.size(); i++) {
			ModConfig.Fundraiser other = config.fundraisers.get(i);
			if (i != index && other != null && other.name != null && other.name.trim().equalsIgnoreCase(name)) {
				Chat.error("Сбор с именем «" + name + "» уже есть.");
				return;
			}
		}
		draft.name = name;
		ModConfig.normalizeFundraiser(draft);
		if (index >= 0 && index < config.fundraisers.size()) {
			config.fundraisers.set(index, draft);
		} else {
			config.fundraisers.add(draft);
		}
		FundraiserTracker tracker = mod.fundraisers();
		if (originalName != null && !originalName.trim().equalsIgnoreCase(name)) {
			tracker.rename(originalName, name);
		}
		if (Math.abs(current - originalCurrent) > 0.0001 || originalName == null) {
			tracker.set(draft, current);
		} else {
			tracker.set(draft, tracker.peek(name).current); // пересчитать флаг закрытия под новую цель
		}
		mod.configEdited();
		Chat.success("Сбор сохранён: " + name + " — " + tracker.line(draft).plainText());
		onClose();
	}

	private void row(GridLayout grid, int row, String label, LayoutElement field) {
		grid.addChild(Widgets.label(font, label), row, 0, settings -> settings.alignVerticallyMiddle().alignHorizontallyLeft());
		grid.addChild(field, row, 1);
	}
}
