package dev.dedworkshop.twitchcraft.ui.config;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import dev.dedworkshop.twitchcraft.util.Chat;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

/**
 * Редактор одной цели: название, тип, порог, повтор и действие при достижении.
 */
class GoalEditScreen extends BaseScreen {
	private final int index;
	private final ModConfig.Goal draft;

	GoalEditScreen(TwitchCraftClient mod, Screen parent, int index, ModConfig.Goal original) {
		super(mod, parent, index < 0 ? "Новая цель" : "Цель: " + original.name);
		this.index = index;
		if (original == null) {
			draft = new ModConfig.Goal("", "follows", 10, true, new ModConfig.Action());
			draft.action.message = "&6ЦЕЛЬ «{goal}» достигнута! &7Спасибо, {user}!";
			draft.action.sound = "minecraft:ui.toast.challenge_complete";
		} else {
			draft = new ModConfig.Goal(original.name, original.type, original.target, original.repeat,
					original.action == null ? new ModConfig.Action() : original.action.copy());
			draft.enabled = original.enabled;
		}
		ModConfig.normalizeAction(draft.action);
	}

	@Override
	protected void buildContent(LinearLayout content) {
		GridLayout form = Widgets.form();
		int r = 0;
		row(form, r++, "Название", Widgets.textField(font, Widgets.FIELD, draft.name, v -> draft.name = v, "показывается в оверлее"));
		row(form, r++, "Что считаем", Widgets.cycle("Тип", draft.goalType(), List.of(ModConfig.GoalType.values()),
				t -> t.title, t -> draft.type = t.id, Widgets.FIELD, typeHelp()));
		row(form, r++, "Нужно набрать", Widgets.intField(font, Widgets.FIELD, draft.target, 1, 100000000, v -> draft.target = v));
		row(form, r++, "Повторять", Widgets.toggle("Каждые N", draft.repeat, v -> draft.repeat = v, Widgets.FIELD,
				"Вкл: цель начинается заново после достижения (каждые N). Выкл: срабатывает один раз."));
		row(form, r++, "Включена", Widgets.toggle("Цель", draft.enabled, v -> draft.enabled = v, Widgets.FIELD, null));
		content.addChild(form);

		content.addChild(SpacerElement.height(6));
		content.addChild(Widgets.header(font, "При достижении"));
		content.addChild(Widgets.gray(font, "Плейсхолдеры: {goal} {target} {times} {user} — кто закрыл цель"));
		content.addChild(Widgets.button(actionSummary(), Widgets.FULL,
				() -> open(new ActionEditScreen(mod, this, "Награда за цель", ActionKind.GOAL, null, draft.action,
						a -> TwitchEvent.goal(draft.name.isBlank() ? "Цель" : draft.name, Math.max(1, draft.target), 1, "TestViewer", "testviewer", true),
						(key, saved) -> draft.action = saved)),
				"Сообщение, заголовок, звук, команды, таблица лута"));
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
		return "Действие: команд " + commands + (loot > 0 ? ", лут " + loot : "") + "…";
	}

	private static String typeHelp() {
		StringBuilder sb = new StringBuilder();
		for (ModConfig.GoalType type : ModConfig.GoalType.values()) {
			sb.append(type.id).append(" — ").append(type.title).append('\n');
		}
		return sb.toString().trim();
	}

	private void save() {
		String name = draft.name == null ? "" : draft.name.trim();
		if (name.isEmpty()) {
			Chat.error("Укажи название цели.");
			return;
		}
		ModConfig config = mod.config();
		for (int i = 0; i < config.goals.size(); i++) {
			ModConfig.Goal other = config.goals.get(i);
			if (i != index && other != null && other.name != null && other.name.trim().equalsIgnoreCase(name)) {
				Chat.error("Цель с названием «" + name + "» уже есть.");
				return;
			}
		}
		draft.name = name;
		if (index >= 0 && index < config.goals.size()) {
			ModConfig.Goal old = config.goals.get(index);
			if (old != null && old.name != null && !old.name.trim().equalsIgnoreCase(name)) {
				mod.goalTracker().reset(old.name);
			}
			config.goals.set(index, draft);
		} else {
			config.goals.add(draft);
		}
		mod.configEdited();
		Chat.success("Цель сохранена: " + name);
		onClose();
	}

	private void row(GridLayout grid, int row, String label, LayoutElement field) {
		grid.addChild(Widgets.label(font, label), row, 0, settings -> settings.alignVerticallyMiddle().alignHorizontallyLeft());
		grid.addChild(field, row, 1);
	}
}
