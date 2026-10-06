package dev.dedworkshop.twitchcraft.ui.config;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * Фабрики стандартных виджетов Minecraft с нужными нам размерами и поведением.
 * Все экраны настроек собраны только из ванильных виджетов — никаких сторонних библиотек.
 */
final class Widgets {
	/** Ширина строки настроек (как в меню Minecraft: две кнопки по 150 + отступ). */
	static final int FULL = 310;
	static final int HALF = 150;
	static final int LABEL = 110;
	static final int FIELD = FULL - LABEL - 6;
	static final int HEIGHT = 20;

	private Widgets() {
	}

	static Component text(String text) {
		return Component.literal(text);
	}

	static StringWidget label(Font font, String text) {
		return new StringWidget(Component.literal(text), font);
	}

	static StringWidget gray(Font font, String text) {
		return new StringWidget(Component.literal(text).withStyle(ChatFormatting.GRAY), font);
	}

	static StringWidget header(Font font, String text) {
		return new StringWidget(Component.literal(text).withStyle(ChatFormatting.GOLD), font);
	}

	/** Текст, обрезанный по ширине (для строк списков). */
	static StringWidget clipped(Font font, Component text, int width) {
		StringWidget widget = new StringWidget(text, font);
		widget.setMaxWidth(width, StringWidget.TextOverflow.CLAMPED);
		return widget;
	}

	static Button button(String label, int width, Runnable onPress) {
		return button(label, width, onPress, null);
	}

	static Button button(String label, int width, Runnable onPress, String tooltip) {
		Button.Builder builder = Button.builder(Component.literal(label), b -> onPress.run()).width(width);
		if (tooltip != null && !tooltip.isEmpty()) {
			builder.tooltip(Tooltip.create(Component.literal(tooltip)));
		}
		return builder.build();
	}

	static CycleButton<Boolean> toggle(String label, boolean value, Consumer<Boolean> onChange, int width, String tooltip) {
		CycleButton<Boolean> button = CycleButton.onOffBuilder(value)
				.create(0, 0, width, HEIGHT, Component.literal(label), (b, v) -> onChange.accept(v));
		if (tooltip != null && !tooltip.isEmpty()) {
			button.setTooltip(Tooltip.create(Component.literal(tooltip)));
		}
		return button;
	}

	static <T> CycleButton<T> cycle(String label, T value, List<T> values, Function<T, String> name,
									Consumer<T> onChange, int width, String tooltip) {
		CycleButton<T> button = CycleButton.<T>builder(v -> Component.literal(name.apply(v)), value)
				.withValues(values)
				.create(0, 0, width, HEIGHT, Component.literal(label), (b, v) -> onChange.accept(v));
		if (tooltip != null && !tooltip.isEmpty()) {
			button.setTooltip(Tooltip.create(Component.literal(tooltip)));
		}
		return button;
	}

	/** Однострочное текстовое поле. Порядок важен: лимит → значение → обработчик. */
	static EditBox textField(Font font, int width, String value, Consumer<String> onChange, String hint) {
		EditBox box = new EditBox(font, width, HEIGHT, Component.literal(hint == null ? "" : hint));
		box.setMaxLength(4000);
		box.setValue(value == null ? "" : value);
		if (hint != null && !hint.isEmpty()) {
			box.setHint(Component.literal(hint).withStyle(ChatFormatting.DARK_GRAY));
		}
		box.setResponder(onChange);
		return box;
	}

	/** Числовое поле: значение передаётся дальше, только если это целое число в диапазоне; иначе текст краснеет. */
	static EditBox intField(Font font, int width, int value, int min, int max, IntConsumer onChange) {
		EditBox box = new EditBox(font, width, HEIGHT, Component.literal("число"));
		box.setMaxLength(10);
		box.setValue(String.valueOf(value));
		box.setResponder(s -> {
			try {
				int parsed = Integer.parseInt(s.trim());
				if (parsed < min || parsed > max) {
					throw new NumberFormatException();
				}
				box.setTextColor(0xE0E0E0);
				onChange.accept(parsed);
			} catch (NumberFormatException e) {
				box.setTextColor(0xFF5555);
			}
		});
		return box;
	}

	static EditBox floatField(Font font, int width, float value, float min, float max, Consumer<Float> onChange) {
		EditBox box = new EditBox(font, width, HEIGHT, Component.literal("число"));
		box.setMaxLength(10);
		box.setValue(trimFloat(value));
		box.setResponder(s -> {
			try {
				float parsed = Float.parseFloat(s.trim().replace(',', '.'));
				if (parsed < min || parsed > max) {
					throw new NumberFormatException();
				}
				box.setTextColor(0xE0E0E0);
				onChange.accept(parsed);
			} catch (NumberFormatException e) {
				box.setTextColor(0xFF5555);
			}
		});
		return box;
	}

	/** Многострочное поле (команды — по одной на строку). */
	static MultiLineEditBox multiline(Font font, int width, int height, String value, Consumer<String> onChange, String placeholder) {
		MultiLineEditBox box = MultiLineEditBox.builder()
				.setPlaceholder(Component.literal(placeholder == null ? "" : placeholder))
				.build(font, width, height, Component.literal(placeholder == null ? "" : placeholder));
		box.setCharacterLimit(20000);
		box.setValue(value == null ? "" : value);
		box.setValueListener(onChange);
		return box;
	}

	/** Строка «подпись слева — виджет справа». */
	static LinearLayout row(Font font, String label, LayoutElement field) {
		LinearLayout row = LinearLayout.horizontal().spacing(6);
		row.defaultCellSetting().alignVerticallyMiddle();
		StringWidget text = label(font, label);
		text.setMaxWidth(LABEL, StringWidget.TextOverflow.CLAMPED);
		row.addChild(text, settings -> settings.alignVerticallyMiddle());
		row.addChild(field);
		return row;
	}

	/** Таблица «подпись — поле» с выровненными колонками. */
	static GridLayout form() {
		GridLayout grid = new GridLayout().columnSpacing(6).rowSpacing(4);
		grid.defaultCellSetting().alignVerticallyMiddle();
		return grid;
	}

	static String trimFloat(float value) {
		String s = String.valueOf(value);
		return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
	}

	static String joinLines(List<String> list) {
		return list == null ? "" : String.join("\n", list);
	}

	static List<String> splitLines(String text) {
		java.util.ArrayList<String> result = new java.util.ArrayList<>();
		if (text == null) {
			return result;
		}
		for (String line : text.split("\\r?\\n")) {
			if (!line.isBlank()) {
				result.add(line.strip());
			}
		}
		return result;
	}

	static String joinComma(List<String> list) {
		return list == null ? "" : String.join(", ", list);
	}

	static List<String> splitComma(String text) {
		java.util.ArrayList<String> result = new java.util.ArrayList<>();
		if (text == null) {
			return result;
		}
		for (String part : text.split("[,;\\n]")) {
			if (!part.isBlank()) {
				result.add(part.strip());
			}
		}
		return result;
	}
}
