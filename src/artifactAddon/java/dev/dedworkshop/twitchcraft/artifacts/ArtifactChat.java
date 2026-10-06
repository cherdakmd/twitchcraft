package dev.dedworkshop.twitchcraft.artifacts;

import dev.dedworkshop.twitchcraft.util.Chat;
import net.minecraft.network.chat.Component;

/** Сообщения аддона в чат игры — со своим префиксом, чтобы не путать с TwitchCraft. */
final class ArtifactChat {
	static final String PREFIX = "§5[Артефакты]§r ";

	private ArtifactChat() {
	}

	static void info(String text) {
		Chat.send(Component.literal(PREFIX + text));
	}

	static void success(String text) {
		Chat.send(Component.literal(PREFIX + "§a" + text));
	}

	static void warn(String text) {
		Chat.send(Component.literal(PREFIX + "§e" + text));
	}

	static void error(String text) {
		Chat.send(Component.literal(PREFIX + "§c" + text));
	}
}
