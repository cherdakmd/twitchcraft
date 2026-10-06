package dev.dedworkshop.twitchcraft.api;

import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import net.minecraft.client.Minecraft;

/**
 * Аддон TwitchCraft — отдельный мод (свой jar), который хочет получать события
 * TwitchCraft и пользоваться его возможностями (чат, команды, планировщик).
 *
 * <h2>Как это работает</h2>
 * Аддон объявляет в своём {@code fabric.mod.json} точку входа с именем
 * {@code twitchcraft-addon} и указывает {@code "twitchcraft"} в {@code depends}:
 * <pre>
 * "entrypoints": {
 *   "twitchcraft-addon": [ "ru.example.MyAddon" ]
 * }
 * </pre>
 * TwitchCraft при запуске сам находит такие точки входа и вызывает
 * {@link #onReady(AddonContext)}, а затем — {@link #onEvent(TwitchEvent)} на каждое
 * событие, {@link #onTick(Minecraft)} каждый тик клиента и {@link #onShutdown()}
 * при выходе из игры. Если аддон не установлен или падает — TwitchCraft работает
 * как обычно: каждый вызов обёрнут в try/catch.
 *
 * <p>Ошибка в аддоне никогда не должна ронять игру: всё, что аддон делает на своём
 * потоке, он обязан сам защищать try/catch (пример — аддон «Артефакты»).</p>
 */
public interface TwitchCraftAddon {
	/** Версия API аддонов. TwitchCraft 1.8.0 — версия 1. */
	int API_VERSION = 1;

	/** Идентификатор аддона (латиницей, например {@code artifacts}). */
	String id();

	/** Название аддона для логов и сообщений. */
	default String title() {
		return id();
	}

	/** Версия аддона (для логов). */
	default String version() {
		return "?";
	}

	/** Вызывается один раз при запуске игры — здесь аддон получает контекст. */
	void onReady(AddonContext context);

	/**
	 * Событие Twitch / VK / игры. Приходит уже в основном потоке игры.
	 * Тестовые события ({@link TwitchEvent#synthetic()}) аддон получает тоже —
	 * чтобы можно было проверить механику без зрителей.
	 */
	default void onEvent(TwitchEvent event) {
	}

	/** Каждый тик клиента (20 раз в секунду). Тяжёлую работу делать не здесь. */
	default void onTick(Minecraft client) {
	}

	/** Владелец мода изменил настройки TwitchCraft ({@code /twitch reload} или экран настроек). */
	default void onConfigChanged() {
	}

	/** Игра закрывается: сохранить состояние. */
	default void onShutdown() {
	}
}
