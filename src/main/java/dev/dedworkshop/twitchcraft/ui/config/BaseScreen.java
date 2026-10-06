package dev.dedworkshop.twitchcraft.ui.config;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Общий каркас экранов настроек: заголовок, прокручиваемое содержимое, кнопки внизу.
 * Экран не ставит игру на паузу — события Twitch продолжают выполняться, пока открыты настройки.
 */
abstract class BaseScreen extends Screen {
	protected final TwitchCraftClient mod;
	protected final Screen parent;
	private HeaderAndFooterLayout layout;
	private ScrollableLayout scroll;

	BaseScreen(TwitchCraftClient mod, Screen parent, String title) {
		super(Component.literal(title));
		this.mod = mod;
		this.parent = parent;
	}

	/** Наполнение прокручиваемой части. */
	protected abstract void buildContent(LinearLayout content);

	/** Кнопки внизу. По умолчанию — «Готово». */
	protected void buildFooter(LinearLayout footer) {
		footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(Widgets.HALF).build());
	}

	@Override
	protected void init() {
		layout = new HeaderAndFooterLayout(this);
		layout.addTitleHeader(getTitle(), font);

		LinearLayout content = LinearLayout.vertical().spacing(4);
		content.defaultCellSetting().alignHorizontallyCenter();
		buildContent(content);

		scroll = new ScrollableLayout(minecraft, content, layout.getContentHeight());
		layout.addToContents(scroll);

		LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
		buildFooter(footer);

		layout.visitWidgets(this::addRenderableWidget);
		repositionElements();
	}

	@Override
	protected void repositionElements() {
		if (layout == null || scroll == null) {
			return;
		}
		scroll.arrangeElements();
		scroll.setMaxHeight(layout.getContentHeight());
		layout.arrangeElements();
	}

	/** Пересобрать экран (после добавления/удаления строк). */
	protected void refresh() {
		rebuildWidgets();
	}

	protected void open(Screen next) {
		minecraft.gui.setScreen(next);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** Вопрос «да/нет»; после ответа возвращаемся на этот экран. */
	protected void confirm(String title, String message, Runnable onYes) {
		minecraft.gui.setScreen(new ConfirmScreen(yes -> {
			minecraft.gui.setScreen(this);
			if (yes) {
				onYes.run();
				refresh();
			}
		}, Component.literal(title), Component.literal(message)));
	}

	protected boolean inWorld() {
		return minecraft.player != null;
	}
}
