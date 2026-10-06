package dev.dedworkshop.twitchcraft.ui.config;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.action.FundraiserTracker;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import dev.dedworkshop.twitchcraft.util.Chat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Список сборов средств (полосы-боссбары) с прогрессом и общие настройки полос.
 */
class FundraisersScreen extends BaseScreen {
	private boolean settingsChanged;

	FundraisersScreen(TwitchCraftClient mod, Screen parent) {
		super(mod, parent, "Сборы средств (боссбар)");
	}

	@Override
	protected void buildContent(LinearLayout content) {
		ModConfig config = mod.config();
		if (!mod.isModuleEnabled(Module.FUNDRAISERS)) {
			content.addChild(new net.minecraft.client.gui.components.StringWidget(
					Component.literal("Модуль «Сборы средств» выключен — полоса не показывается, вклады не считаются (включи в «Модули»)")
							.withStyle(ChatFormatting.RED), font));
		} else {
			content.addChild(Widgets.gray(font, "Полоса вверху экрана, как у босса. Донаты заполняют её сами; вручную: /twitch fund add <имя> <сумма>"));
		}
		content.addChild(SpacerElement.height(2));
		if (config.fundraisers.isEmpty()) {
			content.addChild(Widgets.label(font, "Сборов нет — нажми «Добавить»"));
		}
		for (int i = 0; i < config.fundraisers.size(); i++) {
			ModConfig.Fundraiser fund = config.fundraisers.get(i);
			if (fund == null) {
				continue;
			}
			int index = i;
			LinearLayout row = LinearLayout.horizontal().spacing(4);
			row.defaultCellSetting().alignVerticallyMiddle();
			row.addChild(Widgets.clipped(font, describe(fund), 160));
			row.addChild(Widgets.button("Изменить", 62, () -> open(new FundraiserEditScreen(mod, this, index, fund))));
			row.addChild(Widgets.button("Тест", 40, () -> test(fund), "Выполнить эффект закрытия сбора (прогресс не меняется)"));
			row.addChild(Widgets.button("✖", 20, () -> confirm("Удалить сбор «" + fund.name + "»?", "Сбор и его прогресс будут удалены.", () -> {
				config.fundraisers.remove(index);
				mod.fundraisers().reset(fund.name);
				mod.configEdited();
			}), "Удалить"));
			content.addChild(row);
		}

		content.addChild(SpacerElement.height(6));
		content.addChild(Widgets.header(font, "Настройки полос"));
		ModConfig.FundraiserSettings settings = config.fundraiserSettings;
		GridLayout form = Widgets.form();
		int r = 0;
		row(form, r++, "Отступ сверху, px", Widgets.intField(font, Widgets.FIELD, settings.y, 0, 400, v -> {
			settings.y = v;
			settingsChanged = true;
		}));
		row(form, r++, "Последний вклад, с", Widgets.intField(font, Widgets.FIELD, settings.lastContributionSeconds, 0, 600, v -> {
			settings.lastContributionSeconds = v;
			settingsChanged = true;
		}));
		row(form, r++, "Анимация", Widgets.toggle("Плавное заполнение", settings.animate, v -> {
			settings.animate = v;
			settingsChanged = true;
		}, Widgets.FIELD, "Полоса заполняется плавно, как у настоящих боссов"));
		content.addChild(form);
		content.addChild(Widgets.gray(font, "Ванильные боссбары начинаются с 12 px; если дерёшься с драконом — поставь отступ 31 или больше"));
		content.addChild(SpacerElement.height(6));
	}

	@Override
	protected void buildFooter(LinearLayout footer) {
		footer.addChild(Widgets.button("+ Добавить", 100, () -> open(new FundraiserEditScreen(mod, this, -1, null))));
		footer.addChild(Widgets.button("Сбросить прогресс", 100, () -> confirm("Сбросить прогресс всех сборов?",
				"Собранные суммы обнулятся. Сами сборы останутся.", () -> mod.fundraisers().reset(null))));
		footer.addChild(Widgets.button("Готово", 100, this::onClose));
	}

	@Override
	public void onClose() {
		if (settingsChanged) {
			mod.configEdited();
			settingsChanged = false;
		}
		super.onClose();
	}

	private MutableComponent describe(ModConfig.Fundraiser fund) {
		FundraiserTracker.Line line = mod.fundraisers().line(fund);
		MutableComponent text = Component.literal(fund.name.isBlank() ? "(без имени)" : fund.name)
				.withStyle(fund.enabled ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY);
		return text.append(Component.literal(" · " + FundraiserTracker.formatAmount(line.current()) + "/"
				+ FundraiserTracker.formatAmount(line.target()) + " " + line.symbol() + " · " + line.percent() + "%"
				+ (line.done() ? " ✔" : "") + (fund.visible ? "" : " · скрыт") + (fund.enabled ? "" : " · выкл"))
				.withStyle(ChatFormatting.GRAY));
	}

	private void test(ModConfig.Fundraiser fund) {
		if (!inWorld()) {
			Chat.warn("Тест работает только в мире.");
			return;
		}
		FundraiserTracker.Progress p = mod.fundraisers().peek(fund.name);
		TwitchEvent event = TwitchEvent.fund(fund.name, Math.max(1, fund.target), p.completed + 1,
				mod.config().donations.currency, "TestViewer", "testviewer", true);
		Chat.info("§7[тест] Имитирую закрытие сбора «" + fund.name + "»");
		mod.onTwitchEvent(event);
	}

	private void row(GridLayout grid, int row, String label, LayoutElement field) {
		grid.addChild(Widgets.label(font, label), row, 0, settings -> settings.alignVerticallyMiddle().alignHorizontallyLeft());
		grid.addChild(field, row, 1);
	}
}
