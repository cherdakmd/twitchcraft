package dev.dedworkshop.twitchcraft.ui.config;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.game.GameStats;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import dev.dedworkshop.twitchcraft.util.Chat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Map;

/**
 * События игры → чат: смерть, достижения (обычные / цели / испытания), боссы, смена измерения.
 * У каждого — обычное действие (reply уходит в чаты Twitch и VK) и кнопка «Тест».
 */
class GameEventsScreen extends BaseScreen {
	private static final Map<String, String> LABELS = Map.of(
			TwitchEvent.GAME_DEATH, "Смерть",
			TwitchEvent.GAME_ADVANCEMENT, "Достижение",
			TwitchEvent.GAME_ADVANCEMENT_GOAL, "Цель",
			TwitchEvent.GAME_ADVANCEMENT_CHALLENGE, "Испытание",
			TwitchEvent.GAME_BOSS, "Босс повержен",
			TwitchEvent.GAME_DIMENSION, "Смена измерения");
	private static final Map<String, String> HINTS = Map.of(
			TwitchEvent.GAME_DEATH, "{cause} — причина, {deaths} / {deaths_total} — счётчики",
			TwitchEvent.GAME_ADVANCEMENT, "{advancement} — название, {advancement_text} — описание",
			TwitchEvent.GAME_ADVANCEMENT_GOAL, "если пусто — используется действие «Достижение»",
			TwitchEvent.GAME_ADVANCEMENT_CHALLENGE, "если пусто — используется действие «Достижение»",
			TwitchEvent.GAME_BOSS, "{boss} — кто, {killer} — кем (если не ты), {bosses} — счётчик",
			TwitchEvent.GAME_DIMENSION, "{dimension} — куда (Нижний мир / Край / Верхний мир)");

	private boolean settingsChanged;

	GameEventsScreen(TwitchCraftClient mod, Screen parent) {
		super(mod, parent, "События игры → чат");
	}

	@Override
	protected void buildContent(LinearLayout content) {
		ModConfig config = mod.config();
		if (!mod.isModuleEnabled(Module.GAME_EVENTS)) {
			content.addChild(new StringWidget(Component.literal("Модуль «События игры → чат» выключен — ничего не объявляется (включи в «Модули»)")
					.withStyle(ChatFormatting.RED), font));
		} else {
			content.addChild(Widgets.gray(font, "Смерть, достижения, боссы и смена измерения уходят сообщением (поле «Сообщение в чат Twitch и VK»)"));
		}
		GameStats stats = mod.gameStats();
		content.addChild(Widgets.gray(font, "За сеанс: смертей " + stats.deaths + ", достижений " + stats.advancements + ", боссов " + stats.bosses
				+ "; за всё время: смертей " + stats.deathsTotal() + ", достижений " + stats.advancementsTotal()));
		content.addChild(SpacerElement.height(2));

		GridLayout grid = new GridLayout().columnSpacing(4).rowSpacing(4);
		grid.defaultCellSetting().alignVerticallyMiddle();
		int r = 0;
		for (String key : ModConfig.GAME_EVENT_KEYS) {
			ModConfig.Action action = config.gameEvents.get(key);
			String label = LABELS.getOrDefault(key, key);
			boolean active = action != null && action.enabled && !action.isEmpty();
			StringWidget text = new StringWidget(Component.literal(label + (active ? "" : " (пусто)"))
					.withStyle(active ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY), font);
			text.setMaxWidth(120, StringWidget.TextOverflow.CLAMPED);
			grid.addChild(text, r, 0, s -> s.alignHorizontallyLeft().alignVerticallyMiddle());
			grid.addChild(Widgets.button("Изменить", 70, () -> open(new ActionEditScreen(mod, this, label, ActionKind.GAME_EVENT, null,
					action == null ? new ModConfig.Action() : action,
					draft -> mod.game().testEvent(key), (k, saved) -> {
						config.gameEvents.put(key, saved);
						mod.configEdited();
						Chat.success("Сохранено: " + label);
					})), HINTS.getOrDefault(key, "")), r, 1);
			grid.addChild(Widgets.button("Тест", 44, () -> {
				if (!inWorld()) {
					Chat.warn("Тест работает только в мире.");
					return;
				}
				TwitchEvent event = mod.game().testEvent(key);
				Chat.info("§7[тест] Имитирую событие игры: " + label);
				mod.onTwitchEvent(event);
			}, "Отправить тестовое событие (счётчики не меняются)"), r, 2);
			r++;
		}
		content.addChild(grid);

		content.addChild(SpacerElement.height(6));
		content.addChild(Widgets.header(font, "Куда писать"));
		ModConfig.GameEventsSettings settings = config.gameEventsSettings;
		GridLayout form = Widgets.form();
		r = 0;
		row(form, r++, "Чат Twitch", Widgets.toggle("Писать", settings.toTwitch, v -> {
			settings.toTwitch = v;
			settingsChanged = true;
		}, Widgets.FIELD, "Нужно право user:write:chat (как для ответов зрителям)"));
		row(form, r++, "Чат VK Video Live", Widgets.toggle("Писать", settings.toVk, v -> {
			settings.toVk = v;
			settingsChanged = true;
		}, Widgets.FIELD, "Если VK подключён"));
		row(form, r++, "Чужие достижения", Widgets.toggle("Объявлять", settings.otherPlayers, v -> {
			settings.otherPlayers = v;
			settingsChanged = true;
		}, Widgets.FIELD, "На сервере: объявлять достижения других игроков (смерти считаются только свои)"));
		row(form, r++, "Тишина после входа, с", Widgets.intField(font, Widgets.FIELD, settings.quietSecondsAfterJoin, 0, 600, v -> {
			settings.quietSecondsAfterJoin = v;
			settingsChanged = true;
		}));
		row(form, r++, "Счётчик за всё время", Widgets.toggle("Хранить", settings.persistStats, v -> {
			settings.persistStats = v;
			settingsChanged = true;
		}, Widgets.FIELD, "config/twitchcraft-stats.json — {deaths_total} между стримами"));
		content.addChild(form);
		content.addChild(SpacerElement.height(4));
		content.addChild(Widgets.gray(font, "Достижения объявляются, если в мире включено правило announceAdvancements (по умолчанию да)"));
		content.addChild(Widgets.gray(font, "Чат-команды зрителей: !смерти и !время (раздел «Чат-команды»)"));
		content.addChild(SpacerElement.height(6));
	}

	@Override
	protected void buildFooter(LinearLayout footer) {
		footer.addChild(Widgets.button("Обнулить счётчики сеанса", 150, () -> {
			mod.gameStats().resetSession();
			Chat.success("Счётчики событий игры за сеанс обнулены.");
			refresh();
		}));
		footer.addChild(Widgets.button("Готово", 100, this::onClose));
	}

	@Override
	public void onClose() {
		if (settingsChanged) {
			mod.applyConfig(mod.config());
			settingsChanged = false;
		}
		super.onClose();
	}

	private void row(GridLayout grid, int row, String label, LayoutElement field) {
		grid.addChild(Widgets.label(font, label), row, 0, settings -> settings.alignVerticallyMiddle().alignHorizontallyLeft());
		grid.addChild(field, row, 1);
	}
}
