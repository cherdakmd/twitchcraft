package dev.dedworkshop.twitchcraft.util;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Вспомогательный класс для вывода сообщений в чат игрока.
 * Можно безопасно вызывать из любого потока — сообщение будет
 * показано в основном потоке игры.
 */
public final class Chat {
	/** Префикс всех сообщений мода (фиолетовый, как у Twitch). */
	public static final String PREFIX = "§5[Twitch]§r ";

	/** Последнее сообщение мода — показывается на экране настроек (из главного меню чата не видно). */
	private static volatile String lastText = "";
	private static volatile long lastTime;

	public static String lastText() {
		return lastText;
	}

	public static long lastTime() {
		return lastTime;
	}

	private Chat() {
	}

	public static void info(String text) {
		send(Component.literal(PREFIX + colorize(text)));
	}

	public static void success(String text) {
		send(Component.literal(PREFIX + "§a" + colorize(text)));
	}

	public static void warn(String text) {
		send(Component.literal(PREFIX + "§e" + colorize(text)));
	}

	public static void error(String text) {
		send(Component.literal(PREFIX + "§c" + colorize(text)));
	}

	/** Показывает готовый компонент (с кликабельными ссылками и т.п.). */
	public static void send(Component component) {
		String plain = component.getString().replaceAll("§[0-9a-fk-orA-FK-OR]", "");
		if (plain.startsWith("[Twitch] ")) {
			plain = plain.substring("[Twitch] ".length());
		}
		lastText = plain;
		lastTime = System.currentTimeMillis();
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.gui == null) {
			TwitchCraftClient.LOGGER.info("[chat] {}", component.getString());
			return;
		}
		mc.execute(() -> mc.gui.hud.getChat().addClientSystemMessage(component));
	}

	/** Переводит &-коды цветов в §-коды: "&cКрасный" → "§cКрасный". */
	public static String colorize(String text) {
		if (text == null) {
			return "";
		}
		return text.replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1");
	}
}
