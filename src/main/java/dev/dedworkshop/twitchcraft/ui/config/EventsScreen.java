package dev.dedworkshop.twitchcraft.ui.config;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import dev.dedworkshop.twitchcraft.util.Chat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Действия на события Twitch: фоллов, подписка, ресаб, подарки, рейд, битсы.
 * У ресаба/подарков/рейда/битсов есть пороги — отдельные действия для больших значений.
 */
class EventsScreen extends BaseScreen {
	EventsScreen(TwitchCraftClient mod, Screen parent) {
		super(mod, parent, "События Twitch");
	}

	@Override
	protected void buildContent(LinearLayout content) {
		ModConfig config = mod.config();
		content.addChild(Widgets.gray(font, "Основное действие срабатывает всегда; порог заменяет его при большом значении"));
		content.addChild(Widgets.gray(font, "Уровни (1/2/3/Prime) — своё действие для уровня подписки, если порог не сработал"));
		content.addChild(SpacerElement.height(2));

		GridLayout grid = new GridLayout().columnSpacing(4).rowSpacing(4);
		grid.defaultCellSetting().alignVerticallyMiddle();
		int r = 0;

		eventRow(grid, r++, Module.FOLLOWS, "Фоллов", () -> config.follow, a -> config.follow = a,
				() -> TwitchEvent.test(TwitchEvent.Type.FOLLOW, "TestViewer", 0, "", "", ""), null, null, null,
				null, null, null);
		eventRow(grid, r++, Module.SUBSCRIPTIONS, "Подписка", () -> config.subscribe, a -> config.subscribe = a,
				() -> TwitchEvent.test(TwitchEvent.Type.SUBSCRIBE, "TestViewer", 1, "", "", "1"), null, null, null,
				"Уровни подписки (1/2/3/Prime)", config.subscribeByTier, TwitchEvent.Type.SUBSCRIBE);
		eventRow(grid, r++, Module.SUBSCRIPTIONS, "Продление (ресаб)", () -> config.resub, a -> config.resub = a,
				() -> TwitchEvent.test(TwitchEvent.Type.RESUB, "TestViewer", 6, "Классный стрим!", "", "1"),
				"Пороги ресаба (месяцев)", config.resubTiers, TwitchEvent.Type.RESUB,
				"Уровни ресаба (1/2/3/Prime)", config.resubByTier, TwitchEvent.Type.RESUB);
		eventRow(grid, r++, Module.SUBSCRIPTIONS, "Подарочные сабы", () -> config.giftSub, a -> config.giftSub = a,
				() -> TwitchEvent.test(TwitchEvent.Type.GIFT_SUB, "TestViewer", 5, "", "", "1"),
				"Пороги подарков (штук)", config.giftSubTiers, TwitchEvent.Type.GIFT_SUB,
				"Уровни подарков (1/2/3/Prime)", config.giftSubByTier, TwitchEvent.Type.GIFT_SUB);
		eventRow(grid, r++, Module.RAIDS, "Рейд", () -> config.raid, a -> config.raid = a,
				() -> TwitchEvent.test(TwitchEvent.Type.RAID, "TestStreamer", 42, "", "", ""),
				"Пороги рейда (зрителей)", config.raidTiers, TwitchEvent.Type.RAID,
				null, null, null);
		eventRow(grid, r++, Module.BITS, "Битсы", null, null, null, "Пороги битсов", config.cheer, TwitchEvent.Type.CHEER,
				null, null, null);
		eventRow(grid, r++, null, "Донаты (" + config.donations.currency + ")", null, null, null,
				"Эффекты за донаты (все сервисы)", config.donationTiers, TwitchEvent.Type.DONATION,
				null, null, null);

		content.addChild(grid);
		content.addChild(SpacerElement.height(6));
		content.addChild(Widgets.gray(font, "Плейсхолдеры: {user} {amount} {sum} {tier} {message} {loot} {player} {session_*}"));
	}

	private void eventRow(GridLayout grid, int row, Module module, String label,
						  Supplier<ModConfig.Action> getter, Consumer<ModConfig.Action> setter, Supplier<TwitchEvent> sample,
						  String tiersTitle, Map<String, ModConfig.Action> tiers, TwitchEvent.Type tierType,
						  String levelsTitle, Map<String, ModConfig.Action> levels, TwitchEvent.Type levelType) {
		boolean enabled = module == null
				? mod.isModuleEnabled(Module.DONATION_ALERTS) || mod.isModuleEnabled(Module.DONATE_PAY)
				: mod.isModuleEnabled(module);
		StringWidget text = new StringWidget(Component.literal(label + (enabled ? "" : " (модуль выкл)"))
				.withStyle(enabled ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY), font);
		text.setMaxWidth(104, StringWidget.TextOverflow.CLAMPED);
		grid.addChild(text, row, 0, settings -> settings.alignHorizontallyLeft().alignVerticallyMiddle());

		if (getter != null) {
			grid.addChild(Widgets.button("Изменить", 66, () -> open(new ActionEditScreen(mod, this, label, ActionKind.EVENT, null, getter.get(),
					draft -> sample.get(), (key, saved) -> {
						setter.accept(saved);
						mod.configEdited();
						Chat.success("Сохранено: " + label);
					})), null), row, 1);
			grid.addChild(Widgets.button("Тест", 44, () -> {
				if (!inWorld()) {
					Chat.warn("Тест работает только в мире.");
					return;
				}
				TwitchEvent event = sample.get();
				Chat.info("§7[тест] Имитирую событие " + event.type());
				mod.onTwitchEvent(event);
			}, "Отправить тестовое событие"), row, 2);
		} else {
			grid.addChild(SpacerElement.width(66), row, 1);
			grid.addChild(SpacerElement.width(44), row, 2);
		}
		if (tiers != null) {
			grid.addChild(Widgets.button("Пороги (" + tiers.size() + ")", 84,
					() -> open(new ActionListScreen(mod, this, ActionKind.TIER, tiersTitle, tiers, tierType)),
					"Отдельные действия для больших значений: от 100, от 1000..."), row, 3);
		} else {
			grid.addChild(SpacerElement.width(84), row, 3);
		}
		if (levels != null) {
			grid.addChild(Widgets.button("Уровни (" + levels.size() + ")", 84,
					() -> open(new ActionListScreen(mod, this, ActionKind.SUB_TIER, levelsTitle, levels, levelType)),
					"Отдельные действия по уровню подписки: 1, 2, 3, Prime"), row, 4);
		} else {
			grid.addChild(SpacerElement.width(84), row, 4);
		}
	}
}
