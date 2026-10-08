package dev.dedworkshop.twitchcraft.ui.config;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

/**
 * Общие настройки. Правится копия конфига; «Сохранить» записывает её в файл и применяет.
 */
class SettingsScreen extends BaseScreen {
	private static final List<String> CORNERS = List.of("top-left", "top-right", "bottom-left", "bottom-right");

	private final ModConfig draft;

	SettingsScreen(TwitchCraftClient mod, Screen parent) {
		super(mod, parent, "Общие настройки");
		this.draft = mod.config().copy();
	}

	@Override
	protected void buildContent(LinearLayout content) {
		// --- Twitch
		section(content, "Twitch");
		GridLayout twitch = Widgets.form();
		int r = 0;
		row(twitch, r++, "Client ID", Widgets.textField(font, Widgets.FIELD, draft.clientId, v -> draft.clientId = v.trim(),
				"из dev.twitch.tv/console/apps"));
		row(twitch, r++, "Автоподключение", Widgets.toggle("При входе в мир", draft.autoConnect, v -> draft.autoConnect = v, Widgets.FIELD,
				"Подключаться к Twitch автоматически при входе в мир"));
		content.addChild(twitch);

		// --- Отображение
		section(content, "Отображение");
		GridLayout display = Widgets.form();
		r = 0;
		row(display, r++, "События в чате", Widgets.toggle("Показывать", draft.showEventsInChat, v -> draft.showEventsInChat = v, Widgets.FIELD,
				"Писать в чат Minecraft о каждом событии (фоллов, саб, награда...)"));
		row(display, r++, "Вывод команд", Widgets.toggle("Показывать", draft.showCommandOutput, v -> draft.showCommandOutput = v, Widgets.FIELD,
				"Показывать ответы выполненных команд (для отладки)"));
		row(display, r++, "Заголовок, тиков", Widgets.intField(font, Widgets.FIELD, draft.titleTicks, 10, 1200, v -> draft.titleTicks = v));
		content.addChild(display);

		// --- Очередь
		section(content, "Очередь и защита от спама");
		GridLayout queue = Widgets.form();
		r = 0;
		row(queue, r++, "Копить вне мира", Widgets.toggle("Очередь", draft.queueWhenNotInWorld, v -> draft.queueWhenNotInWorld = v, Widgets.FIELD,
				"Если ты в меню — события ждут входа в мир (иначе пропускаются с возвратом баллов)"));
		row(queue, r++, "Макс. в очереди", Widgets.intField(font, Widgets.FIELD, draft.maxQueuedEvents, 1, 500, v -> draft.maxQueuedEvents = v));
		row(queue, r++, "Пауза, тиков", Widgets.intField(font, Widgets.FIELD, draft.queueDelayTicks, 0, 1200, v -> draft.queueDelayTicks = v));
		row(queue, r++, "Событий в минуту", Widgets.intField(font, Widgets.FIELD, draft.maxEventsPerMinute, 0, 1000, v -> draft.maxEventsPerMinute = v));
		row(queue, r++, "Запрещённые команды", Widgets.textField(font, Widgets.FIELD, Widgets.joinComma(draft.blockedCommands),
				v -> draft.blockedCommands = Widgets.splitComma(v), "через запятую"));
		row(queue, r++, "Игнорировать логины", Widgets.textField(font, Widgets.FIELD, Widgets.joinComma(draft.ignoredUsers),
				v -> draft.ignoredUsers = Widgets.splitComma(v), "боты, через запятую"));
		content.addChild(queue);

		// --- Чат
		section(content, "Чат Twitch");
		GridLayout chat = Widgets.form();
		r = 0;
		row(chat, r++, "Префикс в игре", Widgets.textField(font, Widgets.FIELD, draft.twitchChat.prefix, v -> draft.twitchChat.prefix = v, "&5[T]&r "));
		row(chat, r++, "Значки", Widgets.toggle("Показывать", draft.twitchChat.showBadges, v -> draft.twitchChat.showBadges = v, Widgets.FIELD,
				"♛ стример, ⚔ модератор, ◆ VIP, ★ подписчик"));
		row(chat, r++, "Скрывать команды", Widgets.toggle("Скрывать", draft.twitchChat.hideCommands, v -> draft.twitchChat.hideCommands = v, Widgets.FIELD,
				"Не показывать сообщения-команды (!zombie), только выполнять"));
		row(chat, r++, "Общий чат: показ", Widgets.toggle("Показывать", draft.twitchChat.showSharedChat, v -> draft.twitchChat.showSharedChat = v, Widgets.FIELD,
				"Shared Chat: показывать сообщения, написанные в чате канала-партнёра (с пометкой [канал])"));
		row(chat, r++, "Общий чат: команды", Widgets.toggle("Разрешить", draft.twitchChat.sharedChatCommands, v -> draft.twitchChat.sharedChatCommands = v, Widgets.FIELD,
				"Shared Chat: разрешать зрителям канала-партнёра запускать наши чат-команды"));
		row(chat, r++, "Префикс команд", Widgets.textField(font, Widgets.FIELD, draft.chatCommandPrefix,
				v -> draft.chatCommandPrefix = v.isEmpty() ? "!" : v, "!"));
		row(chat, r++, "Ответ: кулдаун", Widgets.textField(font, Widgets.FIELD, draft.chatReplies.cooldownReply,
				v -> draft.chatReplies.cooldownReply = v, "{user} {seconds}; пусто — не отвечать"));
		row(chat, r++, "Ответ: нет прав", Widgets.textField(font, Widgets.FIELD, draft.chatReplies.noPermissionReply,
				v -> draft.chatReplies.noPermissionReply = v, "{user} {permission}"));
		content.addChild(chat);

		// --- Таблички чата в воздухе
		section(content, "Таблички в воздухе");
		GridLayout signs = Widgets.form();
		r = 0;
		row(signs, r++, "Показывать", Widgets.toggle("Таблички", draft.chatSigns.enabled, v -> draft.chatSigns.enabled = v, Widgets.FIELD,
				"Сообщения Twitch, VK и YouTube висят в воздухе перед игроком, как таблички"));
		row(signs, r++, "В окне чата", Widgets.toggle("Дублировать", draft.chatSigns.keepInChat, v -> draft.chatSigns.keepInChat = v, Widgets.FIELD,
				"Дублировать сообщение строкой в окне чата. Выключите, если нужны только таблички"));
		row(signs, r++, "Держать, секунд", Widgets.intField(font, Widgets.FIELD, draft.chatSigns.seconds, 1, 120, v -> draft.chatSigns.seconds = v));
		row(signs, r++, "Сколько сразу", Widgets.intField(font, Widgets.FIELD, draft.chatSigns.maxVisible, 1, 12, v -> draft.chatSigns.maxVisible = v));
		row(signs, r++, "Расстояние, блоков", Widgets.intField(font, Widgets.FIELD, draft.chatSigns.distance, 2, 30, v -> draft.chatSigns.distance = v));
		row(signs, r++, "Размер, %", Widgets.intField(font, Widgets.FIELD, draft.chatSigns.scale, 25, 300, v -> draft.chatSigns.scale = v));
		content.addChild(signs);

		// --- Награды
		section(content, "Награды за баллы (API)");
		GridLayout rewards = Widgets.form();
		r = 0;
		row(rewards, r++, "Подтверждать", Widgets.toggle("Авто", draft.rewardsSettings.autoFulfill, v -> draft.rewardsSettings.autoFulfill = v, Widgets.FIELD,
				"После выполнения отмечать активацию выполненной на Twitch"));
		row(rewards, r++, "Возвращать баллы", Widgets.toggle("Авто", draft.rewardsSettings.autoRefund, v -> draft.rewardsSettings.autoRefund = v, Widgets.FIELD,
				"Если событие не выполнено (очередь, кулдаун) — вернуть баллы зрителю"));
		row(rewards, r++, "Стоимость по умолч.", Widgets.intField(font, Widgets.FIELD, draft.rewardsSettings.defaultCost, 1, 1000000, v -> draft.rewardsSettings.defaultCost = v));
		content.addChild(rewards);

		// --- Оверлей
		section(content, "Оверлей (F7)");
		GridLayout overlay = Widgets.form();
		r = 0;
		row(overlay, r++, "Угол экрана", Widgets.cycle("Угол", CORNERS.contains(draft.overlay.corner) ? draft.overlay.corner : "top-left", CORNERS,
				SettingsScreen::cornerName, v -> draft.overlay.corner = v, Widgets.FIELD, null));
		row(overlay, r++, "Событий", Widgets.intField(font, Widgets.FIELD, draft.overlay.maxEvents, 0, 20, v -> draft.overlay.maxEvents = v));
		row(overlay, r++, "Держать, секунд", Widgets.intField(font, Widgets.FIELD, draft.overlay.eventSeconds, 0, 3600, v -> draft.overlay.eventSeconds = v));
		row(overlay, r++, "Статистика", Widgets.toggle("Показывать", draft.overlay.showStats, v -> draft.overlay.showStats = v, Widgets.FIELD, null));
		row(overlay, r++, "Цели", Widgets.toggle("Показывать", draft.overlay.showGoals, v -> draft.overlay.showGoals = v, Widgets.FIELD, null));
		row(overlay, r++, "Отступ, px", Widgets.intField(font, Widgets.FIELD, draft.overlay.margin, 0, 200, v -> draft.overlay.margin = v));
		content.addChild(overlay);

		// --- Цели
		section(content, "Цели");
		GridLayout goals = Widgets.form();
		r = 0;
		row(goals, r++, "Сброс через, часов", Widgets.intField(font, Widgets.FIELD, draft.goalsSettings.resetAfterHours, 0, 10000,
				v -> draft.goalsSettings.resetAfterHours = v));
		row(goals, r++, "Прогресс в чат", Widgets.toggle("Сообщать", draft.goalsSettings.announceProgress, v -> draft.goalsSettings.announceProgress = v, Widgets.FIELD,
				"После каждого вклада писать «Цель: 7/10»"));
		content.addChild(goals);
		content.addChild(SpacerElement.height(6));
	}

	@Override
	protected void buildFooter(LinearLayout footer) {
		footer.addChild(Widgets.button("Сохранить", Widgets.HALF, this::save));
		footer.addChild(Widgets.button("Отмена", Widgets.HALF, this::onClose));
	}

	private void save() {
		mod.applyConfig(draft);
		dev.dedworkshop.twitchcraft.util.Chat.success("Настройки сохранены.");
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

	private static String cornerName(String corner) {
		return switch (corner) {
			case "top-right" -> "справа сверху";
			case "bottom-left" -> "слева снизу";
			case "bottom-right" -> "справа снизу";
			default -> "слева сверху";
		};
	}
}
