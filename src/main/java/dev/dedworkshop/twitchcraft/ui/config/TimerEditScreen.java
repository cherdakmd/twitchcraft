package dev.dedworkshop.twitchcraft.ui.config;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.action.Placeholders;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.util.Chat;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.Screen;

/**
 * Редактор одного таймера чата: имя, интервал, минимум сообщений, текст, куда писать.
 */
class TimerEditScreen extends BaseScreen {
	private final int index;
	private final ModConfig.ChatTimer draft;

	TimerEditScreen(TwitchCraftClient mod, Screen parent, int index, ModConfig.ChatTimer original) {
		super(mod, parent, index < 0 ? "Новый таймер" : "Таймер: " + original.name);
		this.index = index;
		this.draft = original == null ? new ModConfig.ChatTimer() : original.copy();
	}

	@Override
	protected void buildContent(LinearLayout content) {
		GridLayout form = Widgets.form();
		int r = 0;
		row(form, r++, "Имя", Widgets.textField(font, Widgets.FIELD, draft.name, v -> draft.name = v, "например, ценник"));
		row(form, r++, "Включён", Widgets.toggle("Таймер", draft.enabled, v -> draft.enabled = v, Widgets.FIELD, null));
		row(form, r++, "Каждые, минут", Widgets.intField(font, Widgets.FIELD, draft.intervalMinutes, 1, 1440, v -> draft.intervalMinutes = v));
		row(form, r++, "Минимум сообщений", Widgets.intField(font, Widgets.FIELD, draft.minChatMessages, 0, 1000, v -> draft.minChatMessages = v));
		row(form, r++, "Чат Twitch", Widgets.toggle("Писать", draft.twitch, v -> draft.twitch = v, Widgets.FIELD, "Нужно право user:write:chat"));
		row(form, r++, "Чат VK Video Live", Widgets.toggle("Писать", draft.vk, v -> draft.vk = v, Widgets.FIELD, "Если VK подключён"));
		content.addChild(form);
		content.addChild(Widgets.gray(font, "Минимум сообщений — сколько сообщений зрителей должно появиться с прошлого напоминания; 0 — писать всегда"));

		content.addChild(SpacerElement.height(4));
		content.addChild(Widgets.header(font, "Текст сообщения"));
		content.addChild(Widgets.multiline(font, Widgets.FULL, 70, draft.text, v -> draft.text = v.replace('\n', ' '),
				"Ценник: {donation_prices_bad} ☠ / {donation_prices_good} ★"));
		content.addChild(Widgets.gray(font, "Переменные: {donation_prices} {donation_prices_bad} {donation_prices_good} {donation_currency} "
				+ "{deaths} {deaths_total} {session_time} {stream_time} {viewers} {fund_*} {session_*} {player}"));
		content.addChild(SpacerElement.height(6));
	}

	@Override
	protected void buildFooter(LinearLayout footer) {
		footer.addChild(Widgets.button("Сохранить", 100, this::save));
		footer.addChild(Widgets.button("Предпросмотр", 100, () -> {
			String text = Placeholders.apply(draft.text, mod.globalPlaceholders());
			Chat.info("§7[предпросмотр] §f" + text);
		}, "Показать текст с подставленными значениями (только тебе, в чат игры)"));
		footer.addChild(Widgets.button("Отмена", 100, this::onClose));
	}

	private void save() {
		draft.name = draft.name == null ? "" : draft.name.trim();
		if (draft.name.isBlank()) {
			Chat.error("Укажи имя таймера.");
			return;
		}
		if (draft.text == null || draft.text.isBlank()) {
			Chat.error("Текст сообщения пустой.");
			return;
		}
		ModConfig config = mod.config();
		for (int i = 0; i < config.timers.size(); i++) {
			if (i != index && config.timers.get(i) != null && draft.name.equalsIgnoreCase(config.timers.get(i).name)) {
				Chat.error("Таймер с именем «" + draft.name + "» уже есть.");
				return;
			}
		}
		if (index < 0) {
			config.timers.add(draft);
		} else {
			config.timers.set(index, draft);
		}
		mod.configEdited();
		Chat.success("Таймер «" + draft.name + "» сохранён.");
		onClose();
	}

	private void row(GridLayout grid, int row, String label, LayoutElement field) {
		grid.addChild(Widgets.label(font, label), row, 0, settings -> settings.alignVerticallyMiddle().alignHorizontallyLeft());
		grid.addChild(field, row, 1);
	}
}
