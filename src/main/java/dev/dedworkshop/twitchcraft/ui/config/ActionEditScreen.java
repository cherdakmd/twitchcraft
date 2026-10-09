package dev.dedworkshop.twitchcraft.ui.config;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.action.Placeholders;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import dev.dedworkshop.twitchcraft.util.Chat;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Редактор одного действия (награда, чат-команда, порог, событие, цель, запись лута).
 * Правится копия; «Сохранить» передаёт её владельцу, «Отмена»/Esc — отбрасывает.
 */
class ActionEditScreen extends BaseScreen {
	private static final List<String> PERMISSIONS = List.of("everyone", "subscriber", "vip", "moderator", "broadcaster");
	private static final List<String> POOLS = List.of("", "bad", "good", "any", "xbad", "xgood");

	private final ActionKind kind;
	private final String originalKey;
	private final ModConfig.Action draft;
	private final Function<ModConfig.Action, TwitchEvent> sampleEvent;
	private final BiConsumer<String, ModConfig.Action> onSave;

	private String key;
	private EditBox keyField;
	private Button lootButton;

	/**
	 * @param key         текущий ключ (название награды / имя команды / порог) или null, если ключа нет
	 * @param original    редактируемое действие (null — новое)
	 * @param sampleEvent создаёт тестовое событие для кнопки «Тест» (null — без теста)
	 * @param onSave      получает ключ и готовое действие
	 */
	ActionEditScreen(TwitchCraftClient mod, Screen parent, String title, ActionKind kind, String key, ModConfig.Action original,
					 Function<ModConfig.Action, TwitchEvent> sampleEvent, BiConsumer<String, ModConfig.Action> onSave) {
		super(mod, parent, title);
		this.kind = kind;
		this.originalKey = key;
		this.key = key == null ? "" : key;
		this.draft = original == null ? new ModConfig.Action() : original.copy();
		ModConfig.normalizeAction(this.draft);
		this.sampleEvent = sampleEvent;
		this.onSave = onSave;
	}

	@Override
	protected void buildContent(LinearLayout content) {
		GridLayout main = Widgets.form();
		int r = 0;
		if (kind.hasKey()) {
			keyField = Widgets.textField(font, Widgets.FIELD, key, v -> key = v, kind == ActionKind.TIER ? "например, 100" : "");
			row(main, r++, kind.keyLabel, keyField);
		}
		if (kind == ActionKind.LOOT) {
			row(main, r++, "Название (→ {loot})", Widgets.textField(font, Widgets.FIELD, draft.name, v -> draft.name = v, "например, алмаз"));
			row(main, r++, "Вес (частота)", Widgets.intField(font, Widgets.FIELD, draft.weight, 0, 100000, v -> draft.weight = v));
		}
		row(main, r++, "Включено", Widgets.toggle("Действие", draft.enabled, v -> draft.enabled = v, Widgets.FIELD,
				"Выключенное действие остаётся в списке, но не выполняется"));
		if (kind == ActionKind.CHAT_COMMAND) {
			row(main, r++, "Кому доступна", Widgets.cycle("Доступ", PERMISSIONS.contains(draft.permission) ? draft.permission : "everyone",
					PERMISSIONS, ActionEditScreen::permissionName, v -> draft.permission = v, Widgets.FIELD, null));
			row(main, r++, "Другие имена", Widgets.textField(font, Widgets.FIELD, Widgets.joinComma(draft.aliases),
					v -> draft.aliases = Widgets.splitComma(v), "через запятую"));
		}
		if (kind == ActionKind.REWARD) {
			row(main, r++, "Стоимость, баллов", Widgets.intField(font, Widgets.FIELD, draft.cost, 0, 10000000, v -> draft.cost = v));
			row(main, r++, "Описание для зрителей", Widgets.textField(font, Widgets.FIELD, draft.prompt, v -> draft.prompt = v, "показывается на Twitch"));
			row(main, r++, "Требовать текст", Widgets.toggle("Ввод", draft.input, v -> draft.input = v, Widgets.FIELD,
					"Зритель должен ввести текст — он попадёт в {message}"));
			row(main, r++, "Цвет плашки", Widgets.textField(font, Widgets.FIELD, draft.color, v -> draft.color = v, "#9146FF"));
			String pool = draft.pool == null ? "" : draft.pool.trim().toLowerCase(java.util.Locale.ROOT);
			row(main, r++, "Случайное из ценника", Widgets.cycle("Пул", POOLS.contains(pool) ? pool : "", POOLS, ActionEditScreen::poolName,
					v -> draft.pool = v, Widgets.FIELD,
					"Награда выбирает случайную запись ценника донатов (☠ / ★ / любую; ☠☠ / ★★ — сверхсобытия от 5500) и выполняет её после своего действия. "
							+ "В сообщении доступны {picked} и {picked_text}. «Нет» — обычная награда"));
		}
		content.addChild(main);

		section(content, "Что показать на экране");
		GridLayout show = Widgets.form();
		r = 0;
		row(show, r++, "Сообщение в чат", Widgets.textField(font, Widgets.FIELD, draft.message, v -> draft.message = v, "&d{user} &7сделал что-то"));
		row(show, r++, "Заголовок", Widgets.textField(font, Widgets.FIELD, draft.title, v -> draft.title = v, "крупно по центру"));
		row(show, r++, "Подзаголовок", Widgets.textField(font, Widgets.FIELD, draft.subtitle, v -> draft.subtitle = v, ""));
		row(show, r++, "Над хотбаром", Widgets.textField(font, Widgets.FIELD, draft.actionbar, v -> draft.actionbar = v, ""));
		row(show, r++, "Уведомление", Widgets.textField(font, Widgets.FIELD, draft.toast, v -> draft.toast = v, "заголовок тоста"));
		row(show, r++, "Текст уведомления", Widgets.textField(font, Widgets.FIELD, draft.toastText, v -> draft.toastText = v, ""));
		row(show, r++, "Звук", Widgets.textField(font, Widgets.FIELD, draft.sound, v -> draft.sound = v, "minecraft:entity.player.levelup"));
		LinearLayout soundParams = LinearLayout.horizontal().spacing(6);
		soundParams.addChild(Widgets.floatField(font, (Widgets.FIELD - 6) / 2, draft.volume, 0f, 2f, v -> draft.volume = v));
		soundParams.addChild(Widgets.floatField(font, (Widgets.FIELD - 6) / 2, draft.pitch, 0.5f, 2f, v -> draft.pitch = v));
		row(show, r++, "Громкость / высота", soundParams);
		if (kind == ActionKind.GAME_EVENT) {
			row(show, r++, "Сообщение в чат Twitch, VK и YouTube", Widgets.textField(font, Widgets.FIELD, draft.reply, v -> draft.reply = v,
					"💀 {cause} — смерть №{deaths}"));
		} else if (kind == ActionKind.ADDON_TRIGGER) {
			row(show, r++, "Сообщение в чат", Widgets.textField(font, Widgets.FIELD, draft.reply, v -> draft.reply = v,
					"Сработал триггер {trigger}!"));
		} else {
			row(show, r++, "Ответ в чат площадки-источника", Widgets.textField(font, Widgets.FIELD, draft.reply, v -> draft.reply = v, "Спасибо, {user}!"));
		}
		content.addChild(show);
		if (kind == ActionKind.GAME_EVENT) {
			content.addChild(Widgets.gray(font, "Переменные: {cause} {deaths} {deaths_total} {advancement} {advancement_text} {boss} {killer} {dimension} "
					+ "{session_time} {stream_time} {viewers} {player}"));
		}
		if (kind == ActionKind.ADDON_TRIGGER) {
			content.addChild(Widgets.gray(font, "Переменные: {trigger} — имя триггера, {slot} — слот (v0…v3); плюс переменные события, "
					+ "на котором сработал триггер ({user}, {amount}, {message}…). Сообщение уходит в чат, откуда пришло событие "
					+ "(для событий игры — в выбранные чаты Twitch, VK и YouTube)."));
		}

		section(content, "Команды (по одной на строку; «delay N» — пауза N тиков)");
		content.addChild(Widgets.multiline(font, Widgets.FULL, 90, Widgets.joinLines(draft.commands),
				v -> draft.commands = Widgets.splitLines(v), "give @s minecraft:diamond 1"));
		GridLayout cmd = Widgets.form();
		r = 0;
		row(cmd, r++, "Одна случайная", Widgets.toggle("Случайная", draft.randomOne, v -> draft.randomOne = v, Widgets.FIELD,
				"Выполнить только одну случайную команду из списка"));
		row(cmd, r++, "Шанс, %", Widgets.intField(font, Widgets.FIELD, draft.chance, 0, 100, v -> draft.chance = v));
		row(cmd, r++, "Если не повезло", Widgets.textField(font, Widgets.FIELD, draft.failMessage, v -> draft.failMessage = v, "сообщение при неудаче"));
		content.addChild(cmd);

		section(content, "Повторы");
		GridLayout rep = Widgets.form();
		r = 0;
		row(rep, r++, "Повторить раз", Widgets.textField(font, Widgets.FIELD, draft.repeat, v -> draft.repeat = v, "число или {amount}"));
		row(rep, r++, "Один повтор на каждые", Widgets.intField(font, Widgets.FIELD, draft.repeatPer, 1, 1000000, v -> draft.repeatPer = v));
		row(rep, r++, "Максимум повторов", Widgets.intField(font, Widgets.FIELD, draft.maxRepeat, 1, 1000, v -> draft.maxRepeat = v));
		row(rep, r++, "Пауза между, тиков", Widgets.intField(font, Widgets.FIELD, draft.repeatDelay, 0, 1200, v -> draft.repeatDelay = v));
		content.addChild(rep);

		if (kind != ActionKind.LOOT) {
			section(content, "Кулдауны, секунд");
			GridLayout cd = Widgets.form();
			r = 0;
			row(cd, r++, "Общий", Widgets.intField(font, Widgets.FIELD, draft.cooldown, 0, 86400, v -> draft.cooldown = v));
			row(cd, r++, "На одного зрителя", Widgets.intField(font, Widgets.FIELD, draft.userCooldown, 0, 86400, v -> draft.userCooldown = v));
			content.addChild(cd);

			section(content, "Таблица лута");
			content.addChild(Widgets.gray(font, "Из списка случайно выпадает одна запись; её название → {loot}"));
			lootButton = content.addChild(Widgets.button(lootLabel(), Widgets.FULL, () -> open(new LootListScreen(mod, this, draft)),
					"Редактировать записи таблицы лута (вес = частота выпадения)"));
		}
		content.addChild(SpacerElement.height(6));
	}

	@Override
	protected void buildFooter(LinearLayout footer) {
		footer.addChild(Widgets.button("Сохранить", 100, this::save));
		if (sampleEvent != null) {
			footer.addChild(Widgets.button("Тест", 100, this::test, "Выполнить это действие прямо сейчас (нужно быть в мире)"));
		}
		footer.addChild(Widgets.button("Отмена", 100, this::onClose));
	}

	private String lootLabel() {
		int n = draft.loot == null ? 0 : draft.loot.size();
		return "Таблица лута: " + (n == 0 ? "пусто" : n + " запис" + plural(n)) + "…";
	}

	private static String plural(int n) {
		int mod10 = n % 10;
		int mod100 = n % 100;
		if (mod10 == 1 && mod100 != 11) return "ь";
		if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) return "и";
		return "ей";
	}

	private void save() {
		String newKey = key == null ? "" : key.trim();
		if (kind.hasKey()) {
			if (newKey.isEmpty()) {
				Chat.error("Укажи " + kind.keyLabel.toLowerCase() + ".");
				return;
			}
			if (kind == ActionKind.TIER) {
				try {
					Integer.parseInt(newKey);
				} catch (NumberFormatException e) {
					Chat.error("Порог должен быть числом, например 100.");
					return;
				}
			}
			if (kind == ActionKind.SUB_TIER && ModConfig.normalizeSubTier(newKey) == null) {
				Chat.error("Уровень подписки — 1, 2, 3 или prime.");
				return;
			}
			if (kind == ActionKind.CHAT_COMMAND && newKey.contains(" ")) {
				Chat.error("Имя команды — одно слово без пробелов.");
				return;
			}
		}
		ModConfig.normalizeAction(draft);
		String saveKey = newKey;
		if (kind == ActionKind.SUB_TIER) {
			saveKey = ModConfig.normalizeSubTier(newKey); // «Tier 2» / «2000» → «2»
		}
		onSave.accept(kind.hasKey() ? saveKey : originalKey, draft);
		onClose();
	}

	private void test() {
		if (!inWorld()) {
			Chat.warn("Тест работает только в мире.");
			return;
		}
		TwitchEvent event = sampleEvent.apply(draft);
		Map<String, String> vars = Placeholders.of(event, minecraft.player.getName().getString(), mod.events().stats());
		Chat.info("§7[тест] " + event.describe());
		mod.events().runner().run(event, draft, vars, () -> {
		});
	}

	private void section(LinearLayout content, String title) {
		content.addChild(SpacerElement.height(4));
		content.addChild(Widgets.header(font, title));
	}

	private void row(GridLayout grid, int row, String label, LayoutElement field) {
		grid.addChild(Widgets.label(font, label), row, 0, settings -> settings.alignVerticallyMiddle().alignHorizontallyLeft());
		grid.addChild(field, row, 1);
	}

	static String poolName(String value) {
		return switch (value) {
			case "bad" -> "☠ Пакость";
			case "good" -> "★ Подарок";
			case "any" -> "☠/★ Любое";
			case "xbad" -> "☠☠ Катастрофа";
			case "xgood" -> "★★ Чудо";
			default -> "Нет";
		};
	}

	static String permissionName(String permission) {
		return switch (permission) {
			case "subscriber" -> "подписчики";
			case "vip" -> "VIP";
			case "moderator" -> "модераторы";
			case "broadcaster" -> "только стример";
			default -> "все";
		};
	}
}
