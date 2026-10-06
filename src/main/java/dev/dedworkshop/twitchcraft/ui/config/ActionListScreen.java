package dev.dedworkshop.twitchcraft.ui.config;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import dev.dedworkshop.twitchcraft.util.Chat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Список действий из одной карты конфига: награды, чат-команды или пороги.
 * Добавить / изменить / протестировать / удалить. Изменения сохраняются в файл сразу.
 */
class ActionListScreen extends BaseScreen {
	private static final int TEXT_WIDTH = 160;

	private final ActionKind kind;
	private final Map<String, ModConfig.Action> map;
	/** Тип события для порогов (cheer → CHEER, resubTiers → RESUB...). */
	private final TwitchEvent.Type tierType;

	ActionListScreen(TwitchCraftClient mod, Screen parent, ActionKind kind, String title, Map<String, ModConfig.Action> map, TwitchEvent.Type tierType) {
		super(mod, parent, title);
		this.kind = kind;
		this.map = map;
		this.tierType = tierType;
	}

	@Override
	protected void buildContent(LinearLayout content) {
		content.addChild(Widgets.gray(font, hint()));
		content.addChild(SpacerElement.height(2));
		List<String> keys = new ArrayList<>(map.keySet());
		if (kind == ActionKind.TIER) {
			keys.sort((a, b) -> Integer.compare(parseInt(a), parseInt(b)));
		}
		if (keys.isEmpty()) {
			content.addChild(Widgets.label(font, "Список пуст — нажми «Добавить»"));
		}
		for (String key : keys) {
			ModConfig.Action action = map.get(key);
			if (action == null) {
				continue;
			}
			LinearLayout row = LinearLayout.horizontal().spacing(4);
			row.defaultCellSetting().alignVerticallyMiddle();
			row.addChild(Widgets.clipped(font, describe(key, action), TEXT_WIDTH));
			row.addChild(Widgets.button("Изменить", 62, () -> edit(key, action)));
			row.addChild(Widgets.button("Тест", 40, () -> test(key, action), "Отправить тестовое событие через обычную обработку"));
			row.addChild(Widgets.button("✖", 20, () -> confirm("Удалить «" + key + "»?", "Действие будет удалено из конфига.", () -> {
				map.remove(key);
				mod.configEdited();
			}), "Удалить"));
			content.addChild(row);
		}
	}

	@Override
	protected void buildFooter(LinearLayout footer) {
		footer.addChild(Widgets.button("+ Добавить", Widgets.HALF, () -> edit(null, null)));
		footer.addChild(Widgets.button("Готово", Widgets.HALF, this::onClose));
	}

	private String hint() {
		return switch (kind) {
			case REWARD -> "Название должно точно совпадать с наградой на Twitch. «*» — для всех остальных наград.";
			case CHAT_COMMAND -> "Зрители пишут в чат Twitch: " + mod.config().chatCommandPrefix + "имя. Права, алиасы и кулдауны — внутри.";
			case TIER -> tierType == TwitchEvent.Type.DONATION
					? "Ключ — сумма «от» в валюте " + mod.config().donations.currency + ". Срабатывает самый большой порог, не превышающий сумму доната."
					: "Срабатывает самый большой порог, не превышающий значение события.";
			default -> "";
		};
	}

	private MutableComponent describe(String key, ModConfig.Action action) {
		MutableComponent text = Component.literal(kind == ActionKind.TIER ? "от " + key : key.equals("*") ? "* (любая другая)" : key)
				.withStyle(action.enabled ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY);
		StringBuilder extra = new StringBuilder();
		if (kind == ActionKind.REWARD && action.cost > 0) {
			extra.append(" · ").append(action.cost).append(" б.");
		}
		if (kind == ActionKind.CHAT_COMMAND && action.permission != null && !action.permission.equals("everyone")) {
			extra.append(" · ").append(ActionEditScreen.permissionName(action.permission));
		}
		int commands = action.commands == null ? 0 : action.commands.size();
		if (commands > 0) {
			extra.append(" · команд: ").append(commands);
		}
		if (action.loot != null && !action.loot.isEmpty()) {
			extra.append(" · лут: ").append(action.loot.size());
		}
		if (action.cooldown > 0) {
			extra.append(" · кд ").append(action.cooldown).append(" с");
		}
		if (action.chance < 100) {
			extra.append(" · ").append(action.chance).append("%");
		}
		if (!action.enabled) {
			extra.append(" · выкл");
		}
		return text.append(Component.literal(extra.toString()).withStyle(ChatFormatting.GRAY));
	}

	private void edit(String key, ModConfig.Action action) {
		String title = key == null ? "Новое действие" : "Изменить: " + key;
		open(new ActionEditScreen(mod, this, title, kind, key, action, draft -> sample(key == null ? "Тест" : key, draft), (newKey, saved) -> {
			if (key != null && !key.equals(newKey)) {
				map.remove(key);
			}
			map.put(newKey, saved);
			mod.configEdited();
			Chat.success("Сохранено: " + newKey);
		}));
	}

	private void test(String key, ModConfig.Action action) {
		if (!inWorld()) {
			Chat.warn("Тест работает только в мире.");
			return;
		}
		TwitchEvent event = sample(key, action);
		Chat.info("§7[тест] Имитирую событие " + event.type());
		mod.onTwitchEvent(event);
	}

	/** Тестовое событие, которое попадёт именно в это действие. */
	private TwitchEvent sample(String key, ModConfig.Action action) {
		return switch (kind) {
			case REWARD -> TwitchEvent.test(TwitchEvent.Type.REWARD, "TestViewer", action.cost > 0 ? action.cost : 500, "Привет из теста", key, "");
			case CHAT_COMMAND -> TwitchEvent.test(TwitchEvent.Type.CHAT, "TestViewer", 0, "", "", "").asCommand(key, "аргументы");
			case TIER -> {
				int value = Math.max(1, parseInt(key));
				TwitchEvent.Type type = tierType == null ? TwitchEvent.Type.CHEER : tierType;
				yield switch (type) {
					case RESUB -> TwitchEvent.test(type, "TestViewer", value, "Классный стрим!", "", "1");
					case GIFT_SUB -> TwitchEvent.test(type, "TestViewer", value, "", "", "1");
					case RAID -> TwitchEvent.test(type, "TestStreamer", value, "", "", "");
					case DONATION -> TwitchEvent.donation("test", "TestDonator", value, mod.config().donations.currency, "Тестовый донат!", "", true);
					default -> TwitchEvent.test(TwitchEvent.Type.CHEER, "TestViewer", value, "Cheer! Держи!", "", "");
				};
			}
			default -> TwitchEvent.test(TwitchEvent.Type.FOLLOW, "TestViewer", 0, "", "", "");
		};
	}

	private static int parseInt(String s) {
		try {
			return Integer.parseInt(s.trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
