package dev.dedworkshop.twitchcraft.ui.config;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.action.GoalTracker;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import dev.dedworkshop.twitchcraft.util.Chat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Список накопительных целей с прогрессом.
 */
class GoalsScreen extends BaseScreen {
	GoalsScreen(TwitchCraftClient mod, Screen parent) {
		super(mod, parent, "Цели");
	}

	@Override
	protected void buildContent(LinearLayout content) {
		ModConfig config = mod.config();
		if (!mod.isModuleEnabled(Module.GOALS)) {
			content.addChild(new net.minecraft.client.gui.components.StringWidget(
					Component.literal("Модуль «Цели» выключен — прогресс не считается (включи в «Модули»)").withStyle(ChatFormatting.RED), font));
		} else {
			content.addChild(Widgets.gray(font, "Прогресс виден в оверлее (F7) и по команде /twitch goals"));
		}
		content.addChild(SpacerElement.height(2));
		if (config.goals.isEmpty()) {
			content.addChild(Widgets.label(font, "Целей нет — нажми «Добавить»"));
		}
		for (int i = 0; i < config.goals.size(); i++) {
			ModConfig.Goal goal = config.goals.get(i);
			if (goal == null) {
				continue;
			}
			int index = i;
			LinearLayout row = LinearLayout.horizontal().spacing(4);
			row.defaultCellSetting().alignVerticallyMiddle();
			row.addChild(Widgets.clipped(font, describe(goal), 160));
			row.addChild(Widgets.button("Изменить", 62, () -> open(new GoalEditScreen(mod, this, index, goal))));
			row.addChild(Widgets.button("Тест", 40, () -> test(goal), "Выполнить награду цели (прогресс не меняется)"));
			row.addChild(Widgets.button("✖", 20, () -> confirm("Удалить цель «" + goal.name + "»?", "Цель и её прогресс будут удалены.", () -> {
				config.goals.remove(index);
				mod.goalTracker().reset(goal.name);
				mod.configEdited();
			}), "Удалить"));
			content.addChild(row);
		}
	}

	@Override
	protected void buildFooter(LinearLayout footer) {
		footer.addChild(Widgets.button("+ Добавить", 100, () -> open(new GoalEditScreen(mod, this, -1, null))));
		footer.addChild(Widgets.button("Сбросить прогресс", 100, () -> confirm("Сбросить прогресс всех целей?",
				"Счётчики обнулятся. Сами цели останутся.", () -> mod.goalTracker().reset(null))));
		footer.addChild(Widgets.button("Готово", 100, this::onClose));
	}

	private MutableComponent describe(ModConfig.Goal goal) {
		GoalTracker.Progress p = mod.goalTracker().peek(goal.name);
		int target = Math.max(1, goal.target);
		String progress = p.done && !goal.repeat ? "✔" : p.count + "/" + target;
		MutableComponent text = Component.literal(goal.name.isBlank() ? "(без названия)" : goal.name)
				.withStyle(goal.enabled ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY);
		return text.append(Component.literal(" · " + goal.goalType().title.toLowerCase() + " · " + progress
				+ (p.completed > 0 ? " ×" + p.completed : "") + (goal.repeat ? "" : " · один раз") + (goal.enabled ? "" : " · выкл"))
				.withStyle(ChatFormatting.GRAY));
	}

	private void test(ModConfig.Goal goal) {
		if (!inWorld()) {
			Chat.warn("Тест работает только в мире.");
			return;
		}
		GoalTracker.Progress p = mod.goalTracker().peek(goal.name);
		TwitchEvent event = TwitchEvent.goal(goal.name, Math.max(1, goal.target), p.completed + 1, "TestViewer", "testviewer", true);
		Chat.info("§7[тест] Имитирую достижение цели «" + goal.name + "»");
		mod.onTwitchEvent(event);
	}
}
