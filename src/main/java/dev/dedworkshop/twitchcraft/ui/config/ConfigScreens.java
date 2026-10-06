package dev.dedworkshop.twitchcraft.ui.config;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Точка входа в экраны настроек: из Mod Menu и из команды /twitch config.
 */
public final class ConfigScreens {
	private ConfigScreens() {
	}

	/** Главный экран настроек (его же открывает Mod Menu). */
	public static Screen main(TwitchCraftClient mod, Screen parent) {
		return new MainConfigScreen(mod, parent);
	}

	/**
	 * Открыть настройки из команды. Команда выполняется, пока открыт экран чата,
	 * и чат закрывается сразу после — поэтому экран ставим в очередь на следующий тик.
	 */
	public static void openDeferred(TwitchCraftClient mod) {
		Minecraft mc = Minecraft.getInstance();
		mc.schedule(() -> mc.gui.setScreen(new MainConfigScreen(mod, null)));
	}
}
