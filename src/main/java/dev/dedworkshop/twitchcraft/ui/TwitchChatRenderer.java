package dev.dedworkshop.twitchcraft.ui;

import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import dev.dedworkshop.twitchcraft.util.Chat;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Показывает сообщение чата Twitch, VK и YouTube:
 *   • табличкой в воздухе перед игроком (если включено в «Таблички в воздухе»);
 *   • строкой в чате Minecraft:  [T] ♛ Ник: текст — или только табличкой, если строку отключили.
 * Ник окрашен в цвет, выбранный зрителем на Twitch.
 */
public final class TwitchChatRenderer {
	/** Цвета шапки и текста таблички (ARGB). Совпадают с цветами строки чата. */
	private static final int SIGN_TAG = 0xFFAAAAAA;
	private static final int SIGN_BADGES = 0xFFFFAA00;
	private static final int SIGN_NAME = 0xFFFF55FF;
	private static final int SIGN_BITS = 0xFF55FFFF;
	private static final int SIGN_TEXT = 0xFFFFFFFF;

	private TwitchChatRenderer() {
	}

	/** Показывает сообщение: табличку в воздухе и/или строку в чате. Вызывается в основном потоке игры. */
	public static void show(ModConfig config, TwitchEvent event) {
		ModConfig.ChatSignSettings signs = config.chatSigns;
		boolean inAir = signs != null && signs.enabled
				&& ChatSigns.instance().add(signs, signHeader(config, event), messageText(event), SIGN_TEXT);
		// Строка в чате нужна, если табличек нет, или если её попросили оставить; без игрока табличка не встанет — выводим строкой
		if (!inAir || signs.keepInChat) {
			Chat.send(build(config, event));
		}
	}

	public static MutableComponent build(ModConfig config, TwitchEvent event) {
		MutableComponent line = Component.literal(prefixFor(config, event));

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

		if (event.amount() > 0) {
			line.append(Component.literal(" [" + event.amount() + " битс]").withStyle(ChatFormatting.AQUA));
		}
		line.append(Component.literal(": ").withStyle(ChatFormatting.GRAY));
		line.append(Component.literal(messageText(event)).withStyle(ChatFormatting.WHITE));
		return line;
	}

	/** Префикс платформы из настроек: [T], [VK] или [YT] (с §-цветами). */
	public static String prefixFor(ModConfig config, TwitchEvent event) {
		if (event.isYoutube()) {
			return config.youtube == null || config.youtube.chatPrefix == null ? "" : Chat.colorize(config.youtube.chatPrefix);
		}
		if (event.isVk()) {
			return config.vk == null || config.vk.chatPrefix == null ? "" : Chat.colorize(config.vk.chatPrefix);
		}
		return config.twitchChat.prefix == null ? "" : Chat.colorize(config.twitchChat.prefix);
	}

	/** Текст сообщения без §-кодов, как в строке чата. */
	public static String messageText(TwitchEvent event) {
		return event.message() == null ? "" : event.message().replace("§", "");
	}

	/**
	 * Шапка таблички: метка платформы, [канал] для Shared Chat, значки, ник цветом зрителя и битсы.
	 * Текст сообщения табличка выводит отдельными строками.
	 */
	public static List<ChatSignLayout.Segment> signHeader(ModConfig config, TwitchEvent event) {
		List<ChatSignLayout.Segment> head = new ArrayList<>();
		String tag = ChatSignLayout.plain(prefixFor(config, event)).trim();
		if (!tag.isEmpty()) {
			head.add(new ChatSignLayout.Segment(tag + " ", SIGN_TAG));
		}
		if (event.isShared()) {
			head.add(new ChatSignLayout.Segment("[" + event.sharedFrom() + "] ", SIGN_TAG));
		}
		if (config.twitchChat.showBadges) {
			String glyphs = badges(event.badges());
			if (!glyphs.isEmpty()) {
				head.add(new ChatSignLayout.Segment(glyphs + " ", SIGN_BADGES));
			}
		}
		int rgb = parseColor(event.color());
		head.add(new ChatSignLayout.Segment(event.user() == null ? "" : event.user(), rgb >= 0 ? (0xFF000000 | rgb) : SIGN_NAME));
		if (event.amount() > 0) {
			head.add(new ChatSignLayout.Segment(" [" + event.amount() + " битс]", SIGN_BITS));
		}
		return head;
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
