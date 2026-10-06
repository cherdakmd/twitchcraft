package dev.dedworkshop.twitchcraft.ui.config;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.util.Chat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/**
 * Список таймеров чата: имя, интервал, условие «живой чат», куда писать; кнопки «Изменить», «Сейчас», удалить.
 */
class TimersScreen extends BaseScreen {
	TimersScreen(TwitchCraftClient mod, Screen parent) {
		super(mod, parent, "Таймеры чата");
	}

	@Override
	protected void buildContent(LinearLayout content) {
		ModConfig config = mod.config();
		if (!mod.isModuleEnabled(Module.CHAT_TIMERS)) {
			content.addChild(new StringWidget(Component.literal("Модуль «Таймеры чата» выключен — напоминания не отправляются (включи в «Модули»)")
					.withStyle(ChatFormatting.RED), font));
		} else {
			content.addChild(Widgets.gray(font, "Сообщение уходит в чат раз в N минут, но только если за это время зрители сами что-то писали"));
		}
		content.addChild(Widgets.gray(font, "В тексте работают {donation_prices} {donation_prices_bad} {donation_prices_good} {deaths} {stream_time} {viewers} и другие"));
		content.addChild(SpacerElement.height(2));
		List<ModConfig.ChatTimer> timers = config.timers;
		if (timers.isEmpty()) {
			content.addChild(Widgets.label(font, "Таймеров нет — нажми «Добавить»"));
		}
		for (int i = 0; i < timers.size(); i++) {
			ModConfig.ChatTimer timer = timers.get(i);
			if (timer == null) {
				continue;
			}
			int index = i;
			LinearLayout row = LinearLayout.horizontal().spacing(4);
			row.defaultCellSetting().alignVerticallyMiddle();
			row.addChild(Widgets.clipped(font, describe(timer), 160));
			row.addChild(Widgets.button("Изменить", 62, () -> open(new TimerEditScreen(mod, this, index, timer))));
			row.addChild(Widgets.button("Сейчас", 44, () -> {
				if (mod.timers().post(timer, true)) {
					Chat.success("Таймер «" + timer.name + "» отправлен в чат.");
				} else {
					Chat.warn("У таймера пустой текст.");
				}
			}, "Отправить текст таймера в чат прямо сейчас"));
			row.addChild(Widgets.button("✖", 20, () -> confirm("Удалить таймер «" + timer.name + "»?", "", () -> {
				config.timers.remove(index);
				mod.configEdited();
			}), "Удалить"));
			content.addChild(row);
		}
		content.addChild(SpacerElement.height(6));
	}

	@Override
	protected void buildFooter(LinearLayout footer) {
		footer.addChild(Widgets.button("+ Добавить", 100, () -> open(new TimerEditScreen(mod, this, -1, null))));
		footer.addChild(Widgets.button("Готово", 100, this::onClose));
	}

	private MutableComponent describe(ModConfig.ChatTimer timer) {
		long left = mod.timers().secondsLeft(timer);
		String when = !timer.enabled ? "выкл" : left < 0 ? "ждёт" : left == 0 ? "ждёт живого чата" : "через " + (left / 60) + " мин";
		MutableComponent text = Component.literal(timer.name.isBlank() ? "(без имени)" : timer.name)
				.withStyle(timer.enabled ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY);
		return text.append(Component.literal(" · " + timer.intervalMinutes + " мин"
				+ (timer.minChatMessages > 0 ? " · чат " + timer.minChatMessages + "+" : "")
				+ " · " + (timer.twitch && timer.vk ? "Twitch+VK" : timer.twitch ? "Twitch" : timer.vk ? "VK" : "никуда")
				+ " · " + when).withStyle(ChatFormatting.GRAY));
	}
}
