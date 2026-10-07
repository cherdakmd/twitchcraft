package dev.dedworkshop.twitchcraft.ui.config;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.api.AddonCustomTrigger;
import dev.dedworkshop.twitchcraft.api.AddonManager;
import dev.dedworkshop.twitchcraft.api.AddonRegistry;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import dev.dedworkshop.twitchcraft.util.Chat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Кастомные триггеры аддонов (слоты v0…v3): что зарегистрировано в каждом слоте
 * и привязанное к слоту действие из конфига (раздел {@code addonTriggers}).
 * Привязанное действие выполняется вместе с собственными действиями триггера.
 */
class AddonTriggersScreen extends BaseScreen {

	AddonTriggersScreen(TwitchCraftClient mod, Screen parent) {
		super(mod, parent, "Триггеры аддонов");
	}

	@Override
	protected void buildContent(LinearLayout content) {
		content.addChild(Widgets.gray(font, "Аддоны регистрируют до " + AddonRegistry.MAX_CUSTOM_TRIGGERS
				+ " кастомных триггеров (слоты v0…v3). К каждому можно привязать своё действие из конфига —"));
		content.addChild(Widgets.gray(font, "оно выполнится вместе с действиями триггера (шанс, кулдауны, лут и повторы работают как обычно)."));
		content.addChild(SpacerElement.height(4));

		ModConfig config = mod.config();
		GridLayout grid = new GridLayout().columnSpacing(4).rowSpacing(4);
		grid.defaultCellSetting().alignVerticallyMiddle();
		for (int index = 0; index < AddonRegistry.MAX_CUSTOM_TRIGGERS; index++) {
			String slot = "v" + index;
			AddonCustomTrigger trigger = AddonRegistry.customTrigger(index);
			ModConfig.Resolved resolved = config.findAddonTriggerAction(slot);
			ModConfig.Action action = resolved == null ? null : resolved.action();
			boolean active = action != null && action.enabled && !action.isEmpty();
			String label = slot + " · " + (trigger == null ? "слот пуст" : trigger.name()) + (active ? "" : " — действие не задано");
			StringWidget text = new StringWidget(Component.literal(label)
					.withStyle(active ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY), font);
			text.setMaxWidth(160, StringWidget.TextOverflow.CLAMPED);
			grid.addChild(text, index, 0, s -> s.alignHorizontallyLeft().alignVerticallyMiddle());
			grid.addChild(Widgets.button("Изменить", 70, () -> open(new ActionEditScreen(mod, this,
					"Триггер аддона " + slot + " — действие из конфига", ActionKind.ADDON_TRIGGER, null, action,
					draft -> TwitchEvent.test(TwitchEvent.Type.CHAT_COMMAND, playerName(), 0, "",
							trigger == null ? slot : trigger.name(), ""),
					(k, saved) -> {
						config.addonTriggers.put(slot, saved);
						mod.configEdited();
						Chat.success("Сохранено: триггер аддона " + slot);
					})), trigger == null
					? "Слот пока пуст — действие будет ждать, пока аддон зарегистрирует здесь триггер"
					: "Действие из конфига для триггера «" + trigger.name() + "» (раздел addonTriggers)"), index, 1);
			grid.addChild(Widgets.button("Тест", 44, () -> fire(index, slot),
					"Запустить триггер вручную, как /twitch addons fire " + slot + " (также выполняет привязанное действие)"), index, 2);
		}
		content.addChild(grid);

		content.addChild(SpacerElement.height(6));
		content.addChild(Widgets.header(font, "Зарегистрировано аддонами"));
		var triggers = AddonRegistry.customTriggers();
		if (triggers.isEmpty()) {
			content.addChild(Widgets.gray(font, AddonManager.count() == 0
					? "Аддоны не подключены — поставь аддон-мод рядом с TwitchCraft (например, «Артефакты»)."
					: "Подключённые аддоны пока не зарегистрировали кастомных триггеров."));
			content.addChild(Widgets.gray(font, "Привязанные действия сохранятся в конфиге и заработают, как только слот займёт аддон."));
		}
		for (AddonCustomTrigger trigger : triggers) {
			String addon = AddonRegistry.customTriggerAddon(trigger.index());
			String line = trigger.slot() + " «" + trigger.name() + "» — аддон «" + (addon == null ? "?" : addon)
					+ "», действий: " + trigger.actions().size()
					+ (trigger.description().isBlank() ? "" : "; " + trigger.description());
			content.addChild(Widgets.gray(font, line));
		}
		content.addChild(SpacerElement.height(4));
		content.addChild(Widgets.gray(font, "Команды: /twitch addons triggers — список, /twitch addons fire v0…v3 — запуск вручную"));
		content.addChild(SpacerElement.height(6));
	}

	@Override
	protected void buildFooter(LinearLayout footer) {
		footer.addChild(Widgets.button("Готово", 100, this::onClose));
	}

	private void fire(int index, String slot) {
		if (!inWorld()) {
			Chat.warn("Тест работает только в мире.");
			return;
		}
		if (mod.events() == null) {
			Chat.error("Обработчик событий ещё не запущен — попробуй в мире.");
			return;
		}
		AddonCustomTrigger trigger = AddonRegistry.customTrigger(index);
		if (trigger == null) {
			Chat.warn("Слот " + slot + " пуст: этот кастомный триггер никто не зарегистрировал.");
			return;
		}
		int fired = mod.events().fireCustomTrigger(index);
		Chat.info("§7[тест] Триггер аддона " + slot + " «" + trigger.name() + "» запущен (действий: " + fired + ")");
	}

	private String playerName() {
		return minecraft.player != null ? minecraft.player.getName().getString() : "Игрок";
	}
}
