package dev.dedworkshop.twitchcraft.ui.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.dedworkshop.twitchcraft.TwitchCraftClient;

/**
 * Интеграция с Mod Menu: кнопка «Настроить» у мода открывает наш главный экран.
 * Класс загружается только если Mod Menu установлен (entrypoint "modmenu" в fabric.mod.json);
 * без Mod Menu тот же экран открывается командой /twitch config.
 */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> ConfigScreens.main(TwitchCraftClient.get(), parent);
	}
}
