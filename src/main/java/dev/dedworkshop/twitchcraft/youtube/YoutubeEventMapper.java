package dev.dedworkshop.twitchcraft.youtube;

import com.google.gson.JsonObject;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Converts YouTube liveChatMessages.list items to the shared event model. */
public final class YoutubeEventMapper {
	private YoutubeEventMapper() {
	}

	/**
	 * Convert a supported chat item. Unsupported/service events return {@code null}; in particular,
	 * YouTube has no Twitch follows, raids, bits, or Channel Points and we do not fabricate them.
	 */
	public static TwitchEvent fromMessage(JsonObject item) {
		if (item == null) return null;
		JsonObject snippet = object(item, "snippet");
		JsonObject author = object(item, "authorDetails");
		if (snippet == null) return null;

		String type = str(snippet, "type");
		String id = str(item, "id");
		String userId = str(author, "channelId");
		String user = str(author, "displayName");
		if (user.isBlank()) user = "Аноним";
		String login = userId.isBlank() ? user.toLowerCase(Locale.ROOT) : userId.toLowerCase(Locale.ROOT);
		Set<String> badges = badges(author);
		String tier = "";
		String message = "";
		int amount = 0;
		String source = "";
		TwitchEvent.Type eventType;

		switch (type) {
			case "textMessageEvent" -> {
				JsonObject details = object(snippet, "textMessageDetails");
				message = str(details, "messageText");
				if (message.isBlank()) return null;
				if (isAnonymous(author)) user = "Аноним";
				eventType = TwitchEvent.Type.CHAT;
			}
			case "newSponsorEvent" -> {
				JsonObject details = object(snippet, "newSponsorDetails");
				tier = first(details, "memberLevelName", "memberLevel");
				if (tier.isBlank()) tier = "Участник YouTube";
				badges.add("subscriber");
				eventType = TwitchEvent.Type.SUBSCRIBE;
				amount = 1;
			}
			case "memberMilestoneChatEvent" -> {
				JsonObject details = object(snippet, "memberMilestoneChatDetails");
				amount = Math.max(1, intValue(details, "memberMonth", 1));
				message = str(details, "userComment");
				tier = first(details, "memberLevelName", "memberLevel");
				if (tier.isBlank()) tier = "Участник YouTube";
				badges.add("subscriber");
				eventType = TwitchEvent.Type.RESUB;
			}
			case "membershipGiftingEvent" -> {
				JsonObject details = object(snippet, "membershipGiftingDetails");
				amount = Math.max(1, intValue(details, "giftMembershipsCount", 1));
				tier = first(details, "giftMembershipsLevelName", "memberLevelName", "memberLevel");
				if (tier.isBlank()) tier = "Участие YouTube";
				if (bool(details, "gifterIsAnonymous")) user = "Аноним";
				badges.add("subscriber");
				eventType = TwitchEvent.Type.GIFT_SUB;
			}
			case "superChatEvent" -> {
				JsonObject details = object(snippet, "superChatDetails");
				amount = microsAmount(details);
				message = str(details, "userComment");
				tier = str(details, "currency");
				source = TwitchEvent.SOURCE_YOUTUBE_SUPER_CHAT;
				eventType = TwitchEvent.Type.DONATION;
			}
			case "superStickerEvent" -> {
				JsonObject details = object(snippet, "superStickerDetails");
				amount = microsAmount(details);
				message = str(details, "userComment");
				tier = str(details, "currency");
				source = TwitchEvent.SOURCE_YOUTUBE_SUPER_STICKER;
				eventType = TwitchEvent.Type.DONATION;
			}
			case "fanFundingEvent" -> {
				JsonObject details = object(snippet, "fanFundingEventDetails");
				amount = microsAmount(details);
				message = str(details, "userComment");
				tier = str(details, "currency");
				source = TwitchEvent.SOURCE_YOUTUBE_FAN_FUNDING;
				eventType = TwitchEvent.Type.DONATION;
			}
			default -> {
				// giftMembershipReceivedEvent follows an aggregate membershipGiftingEvent. Ignoring
				// the per-recipient notices prevents the same purchase being counted twice.
				return null;
			}
		}

		if (isAnonymous(author) && eventType != TwitchEvent.Type.CHAT) user = "Аноним";
		if (eventType == TwitchEvent.Type.DONATION) {
			TwitchEvent event = TwitchEvent.donation(source, user, amount, tier, message, id, false);
			return event.withPlatform(TwitchEvent.PLATFORM_YOUTUBE);
		}
		return new TwitchEvent(eventType, clean(user), clean(login), clean(userId), amount, clean(message), "", tier,
				"", "", Set.copyOf(badges), "", "", false, "", TwitchEvent.PLATFORM_YOUTUBE);
	}

	public static String messageType(JsonObject item) {
		JsonObject snippet = object(item, "snippet");
		return snippet == null ? "" : str(snippet, "type");
	}

	private static Set<String> badges(JsonObject author) {
		Set<String> badges = new LinkedHashSet<>();
		if (bool(author, "isChatOwner")) badges.add("broadcaster");
		if (bool(author, "isChatModerator")) badges.add("moderator");
		if (bool(author, "isChatSponsor")) badges.add("subscriber");
		return badges;
	}

	private static boolean isAnonymous(JsonObject author) {
		return author != null && (bool(author, "isChatSponsor") && str(author, "channelId").isBlank());
	}

	private static int microsAmount(JsonObject details) {
		String raw = str(details, "amountMicros");
		if (raw.isBlank()) return 0;
		try {
			return new BigDecimal(raw).movePointLeft(6).setScale(0, RoundingMode.DOWN)
					.min(BigDecimal.valueOf(Integer.MAX_VALUE)).max(BigDecimal.ZERO).intValue();
		} catch (NumberFormatException ignored) {
			return 0;
		}
	}

	private static int intValue(JsonObject object, String key, int fallback) {
		String value = str(object, key);
		if (value.isBlank()) return fallback;
		try {
			return Math.min(Integer.MAX_VALUE, Math.max(0, new BigDecimal(value).intValue()));
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static String first(JsonObject object, String... keys) {
		for (String key : keys) {
			String value = str(object, key);
			if (!value.isBlank()) return value;
		}
		return "";
	}

	private static boolean bool(JsonObject object, String key) {
		return object != null && object.has(key) && !object.get(key).isJsonNull() && object.get(key).getAsBoolean();
	}

	private static JsonObject object(JsonObject object, String key) {
		return object != null && object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : null;
	}

	private static String str(JsonObject object, String key) {
		if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
		try {
			return object.get(key).getAsString().trim();
		} catch (Exception ignored) {
			return "";
		}
	}

	private static String clean(String value) {
		return value == null ? "" : value.replaceAll("\\p{Cntrl}", " ").replace("§", "").trim();
	}
}
