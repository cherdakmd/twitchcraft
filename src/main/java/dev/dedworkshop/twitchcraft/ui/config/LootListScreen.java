package dev.dedworkshop.twitchcraft.ui.config;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;

/**
 * Таблица лута одного действия. Записи правятся прямо в объекте-владельце
 * (это черновик редактора действия — в файл попадёт после «Сохранить» там).
 */
class LootListScreen extends BaseScreen {
	private final ModConfig.Action owner;

	LootListScreen(TwitchCraftClient mod, Screen parent, ModConfig.Action owner) {
		super(mod, parent, "Таблица лута");
		this.owner = owner;
		if (owner.loot == null) {
			owner.loot = new ArrayList<>();
		}
	}

	@Override
	protected void buildContent(LinearLayout content) {
		content.addChild(Widgets.gray(font, "Выпадает одна запись. Шанс = вес записи / сумма весов."));
		content.addChild(SpacerElement.height(2));
		int total = 0;
		for (ModConfig.Action entry : owner.loot) {
			if (entry != null && entry.enabled) {
				total += Math.max(0, entry.weight);
			}
		}
		if (owner.loot.isEmpty()) {
			content.addChild(Widgets.label(font, "Записей нет — нажми «Добавить»"));
		}
		for (int i = 0; i < owner.loot.size(); i++) {
			ModConfig.Action entry = owner.loot.get(i);
			if (entry == null) {
				continue;
			}
			int index = i;
			LinearLayout row = LinearLayout.horizontal().spacing(4);
			row.defaultCellSetting().alignVerticallyMiddle();
			row.addChild(Widgets.clipped(font, describe(entry, total), 200));
			row.addChild(Widgets.button("Изменить", 62, () -> edit(index, entry)));
			row.addChild(Widgets.button("✖", 20, () -> confirm("Удалить запись?", "«" + entry.name + "» будет удалена из таблицы.",
					() -> owner.loot.remove(index)), "Удалить"));
			content.addChild(row);
		}
	}

	@Override
	protected void buildFooter(LinearLayout footer) {
		footer.addChild(Widgets.button("+ Добавить", Widgets.HALF, () -> edit(-1, null)));
		footer.addChild(Widgets.button("Готово", Widgets.HALF, this::onClose));
	}

	private MutableComponent describe(ModConfig.Action entry, int total) {
		String name = entry.name == null || entry.name.isBlank() ? "(без названия)" : entry.name;
		int percent = total > 0 && entry.enabled ? Math.round(100f * Math.max(0, entry.weight) / total) : 0;
		int commands = entry.commands == null ? 0 : entry.commands.size();
		return Component.literal(name).withStyle(entry.enabled ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY)
				.append(Component.literal(" · вес " + entry.weight + " (" + percent + "%) · команд: " + commands + (entry.enabled ? "" : " · выкл"))
						.withStyle(ChatFormatting.GRAY));
	}

	private void edit(int index, ModConfig.Action entry) {
		ModConfig.Action template = entry;
		if (template == null) {
			template = new ModConfig.Action();
			template.weight = 10;
		}
		open(new ActionEditScreen(mod, this, index < 0 ? "Новая запись лута" : "Запись лута", ActionKind.LOOT, null, template, null,
				(key, saved) -> {
					if (index < 0 || index >= owner.loot.size()) {
						owner.loot.add(saved);
					} else {
						owner.loot.set(index, saved);
					}
				}));
	}
}
