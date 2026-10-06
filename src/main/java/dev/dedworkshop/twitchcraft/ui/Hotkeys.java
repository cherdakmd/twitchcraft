package dev.dedworkshop.twitchcraft.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.module.Module;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * Горячие клавиши (меняются в Настройки → Управление → TwitchCraft):
 *   F8 — пауза/продолжить обработку событий
 *   F7 — показать/скрыть оверлей
 *   F9 — повторить последнее событие
 *   F10 — сделать клип (и метку) стрима
 */
public final class Hotkeys {
	private Hotkeys() {
	}

	public static void register(TwitchCraftClient mod) {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(TwitchCraftClient.MOD_ID, "main"));

		KeyMapping pause = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.twitchcraft.pause", InputConstants.Type.KEYBOARD, InputConstants.KEY_F8, category));
		KeyMapping overlay = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.twitchcraft.overlay", InputConstants.Type.KEYBOARD, InputConstants.KEY_F7, category));
		KeyMapping replay = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.twitchcraft.replay", InputConstants.Type.KEYBOARD, InputConstants.KEY_F9, category));
		KeyMapping clip = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.twitchcraft.clip", InputConstants.Type.KEYBOARD, InputConstants.KEY_F10, category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			boolean enabled = mod.isModuleEnabled(Module.HOTKEYS);
			try {
				while (pause.consumeClick()) {
					if (enabled) mod.togglePause();
				}
				while (overlay.consumeClick()) {
					if (enabled) mod.toggleOverlay();
				}
				while (replay.consumeClick()) {
					if (enabled) mod.replayLast();
				}
				while (clip.consumeClick()) {
					if (enabled && mod.clips().clip("вручную (F10)", true) && mod.clips().canMark()) {
						mod.clips().marker("Клип: вручную (F10)", false);
					}
				}
			} catch (Exception e) {
				TwitchCraftClient.LOGGER.error("Ошибка обработки горячей клавиши TwitchCraft", e);
			}
		});
	}
}
