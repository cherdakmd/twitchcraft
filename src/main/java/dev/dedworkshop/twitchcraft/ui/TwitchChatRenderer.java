package dev.dedworkshop.twitchcraft.ui;

import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import dev.dedworkshop.twitchcraft.util.Chat;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;

import java.util.Set;

/**
 * Показывает сообщение чата Twitch в чате Minecraft:
 *   [T] ♛ Ник: текст
 * Ник окрашен в цвет, выбранный зрителем на Twitch.
 */
public final class TwitchChatRenderer {
	private TwitchChatRenderer() {
	}

	public static void show(ModConfig config, TwitchEvent event) {
		Chat.send(build(config, event));
	}

	public static MutableComponent build(ModConfig config, TwitchEvent event) {
		String prefix = event.isYoutube()
				? (config.youtube == null || config.youtube.chatPrefix == null ? "" : Chat.colorize(config.youtube.chatPrefix))
				: event.isVk()
					? (config.vk == null || config.vk.chatPrefix == null ? "" : Chat.colorize(config.vk.chatPrefix))
					: (config.twitchChat.prefix == null ? "" : Chat.colorize(config.twitchChat.prefix));
		MutableComponent line = Component.literal(prefix);

		if (config.twitchChat.showBadges) {
			String glyphs = badges(event.badges());
			if (!glyphs.isEmpty()) {
				line.append(Component.literal(glyphs + " ").withStyle(ChatFormatting.GOLD));
			}
		}

		if (event.isShared()) {
			line.append(Component.literal("[" + event.sharedFrom() + "] ").withStyle(ChatFormatting.DARK_GRAY));
		}
		MutableComponent name = Component.literal(event.user());
		int rgb = parseColor(event.color());
		if (rgb >= 0) {
			name.withStyle(style -> style.withColor(TextColor.fromRgb(rgb)));
		} else {
			name.withStyle(ChatFormatting.LIGHT_PURPLE);
		}
		line.append(name);

		String text = event.message() == null ? "" : event.message().replace("§", "");
		if (event.amount() > 0) {
			line.append(Component.literal(" [" + event.amount() + " битс]").withStyle(ChatFormatting.AQUA));
		}
		line.append(Component.literal(": ").withStyle(ChatFormatting.GRAY));
		line.append(Component.literal(text).withStyle(ChatFormatting.WHITE));
		return line;
	}

	/** Значки: ♛ стример, ⚔ модератор, ◆ VIP, ★ подписчик. */
	public static String badges(Set<String> badges) {
		if (badges == null || badges.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		if (badges.contains("broadcaster")) sb.append('♛');
		if (badges.contains("moderator")) sb.append('⚔');
		if (badges.contains("vip")) sb.append('◆');
		if (badges.contains("subscriber") || badges.contains("founder")) sb.append('★');
		return sb.toString();
	}

	/** "#9146FF" → 0x9146FF, иначе -1. Слишком тёмные цвета осветляем, чтобы читались на фоне чата. */
	public static int parseColor(String color) {
		if (color == null || !color.matches("#[0-9a-fA-F]{6}")) {
			return -1;
		}
		int rgb = Integer.parseInt(color.substring(1), 16);
		int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
		int brightness = (r * 299 + g * 587 + b * 114) / 1000;
		if (brightness < 70) {
			r = Math.min(255, r + 90);
			g = Math.min(255, g + 90);
			b = Math.min(255, b + 90);
			rgb = (r << 16) | (g << 8) | b;
		}
		return rgb;
	}
}
