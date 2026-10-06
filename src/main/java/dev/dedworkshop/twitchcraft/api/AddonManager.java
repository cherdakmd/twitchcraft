package dev.dedworkshop.twitchcraft.api;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import net.fabricmc.loader.api.EntrypointContainer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Находит и обслуживает аддоны TwitchCraft.
 *
 * <p>Аддон — это отдельный мод, который объявил точку входа {@code twitchcraft-addon}
 * (класс, реализующий {@link TwitchCraftAddon}). TwitchCraft вызывает её сам; если аддон
 * падает, ошибка пишется в лог, а остальные аддоны и сам мод продолжают работать.</p>
 */
public final class AddonManager {
	/** Имя точки входа Fabric, которой пользуются аддоны. */
	public static final String ENTRYPOINT = "twitchcraft-addon";

	private static final List<TwitchCraftAddon> ADDONS = new ArrayList<>();
	private static AddonContext context;
	private static boolean loaded;

	private AddonManager() {
	}

	/** Вызывается один раз при запуске игры (из TwitchCraftClient). */
	public static void loadAll(TwitchCraftClient mod) {
		if (loaded) {
			return;
		}
		loaded = true;
		context = new AddonContext(mod);
		List<EntrypointContainer<TwitchCraftAddon>> containers;
		try {
			containers = FabricLoader.getInstance().getEntrypointContainers(ENTRYPOINT, TwitchCraftAddon.class);
		} catch (Throwable t) {
			TwitchCraftClient.LOGGER.warn("Не удалось получить список аддонов TwitchCraft: {}", t.toString());
			return;
		}
		for (EntrypointContainer<TwitchCraftAddon> container : containers) {
			String modId = container.getProvider() == null ? "?" : container.getProvider().getMetadata().getId();
			try {
				TwitchCraftAddon addon = container.getEntrypoint();
				if (addon == null) {
					continue;
				}
				addon.onReady(context);
				ADDONS.add(addon);
				TwitchCraftClient.LOGGER.info("TwitchCraft: аддон «{}» (мод {}, версия {}) подключён",
						addon.title(), modId, addon.version());
			} catch (Throwable t) {
				TwitchCraftClient.LOGGER.error("TwitchCraft: аддон из мода {} не загрузился (мод продолжает работать)", modId, t);
			}
		}
	}

	/** Каждое событие Twitch / VK / игры — всем аддонам. */
	public static void dispatchEvent(TwitchEvent event) {
		if (ADDONS.isEmpty() || event == null) {
			return;
		}
		for (TwitchCraftAddon addon : snapshot()) {
			try {
				addon.onEvent(event);
			} catch (Throwable t) {
				TwitchCraftClient.LOGGER.error("Аддон «{}»: ошибка обработки события {} (пропущено)", addon.title(), event.type(), t);
			}
		}
	}

	/** Каждый тик клиента — всем аддонам. */
	public static void tick(Minecraft client) {
		if (ADDONS.isEmpty()) {
			return;
		}
		for (TwitchCraftAddon addon : snapshot()) {
			try {
				addon.onTick(client);
			} catch (Throwable t) {
				TwitchCraftClient.LOGGER.error("Аддон «{}»: ошибка в тике (пропущено)", addon.title(), t);
			}
		}
	}

	/** Конфиг TwitchCraft перечитан или изменён в экране настроек. */
	public static void configChanged() {
		for (TwitchCraftAddon addon : snapshot()) {
			try {
				addon.onConfigChanged();
			} catch (Throwable t) {
				TwitchCraftClient.LOGGER.error("Аддон «{}»: ошибка при обновлении настроек", addon.title(), t);
			}
		}
	}

	/** Игра закрывается — аддоны сохраняют состояние. */
	public static void shutdown() {
		for (TwitchCraftAddon addon : snapshot()) {
			try {
				addon.onShutdown();
			} catch (Throwable t) {
				TwitchCraftClient.LOGGER.error("Аддон «{}»: ошибка при выходе из игры", addon.title(), t);
			}
		}
	}

	/** Сколько аддонов подключено. */
	public static int count() {
		return ADDONS.size();
	}

	/** Идентификаторы подключённых аддонов (для {@code /twitch addons}). */
	public static List<String> loadedIds() {
		List<String> ids = new ArrayList<>();
		for (TwitchCraftAddon addon : snapshot()) {
			ids.add(addon.id());
		}
		return ids;
	}

	private static List<TwitchCraftAddon> snapshot() {
		return Collections.unmodifiableList(new ArrayList<>(ADDONS));
	}
}
