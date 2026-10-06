package dev.dedworkshop.twitchcraft.vk;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Разбор событий WebSocket VK Video Live в {@link TwitchEvent}.
 *
 * Формат событий в документации DevAPI не описан, поэтому разбор терпим к вариантам имён полей
 * (camelCase и snake_case) и к разным местам, где может лежать одно и то же: сообщение чата,
 * запрос награды за баллы (cp_reward_demand / reward_demand), новый подписчик канала (following),
 * статус трансляции (stream_online_status / stream_start / stream_end).
 */
public final class VkEvents {
	private VkEvents() {
	}

	/** Что разобрали из одного события. */
	public record Parsed(
			/** Тип события, как его назвал сервер (message, cp_reward_demand, actions_journal_new_event...). */
			String type,
			/** Событие для обработчика мода (null — служебное событие или неизвестный тип). */
			TwitchEvent event,
			/** Статус запроса награды: pending / approved / rejected (только для наград). */
			String demandStatus,
			/** Id трансляции, если событие его сообщило (stream_online_status / stream_start). */
			String streamId,
			/** Трансляция идёт (true/false), если событие это сообщило; иначе null. */
			Boolean online
	) {
		public static Parsed ignored(String type) {
			return new Parsed(type, null, "", "", null);
		}

		public boolean isReward() {
			return event != null && event.type() == TwitchEvent.Type.REWARD;
		}
	}

	/** Палитра цветов ника VK (nick_color — число 0..15); порядок приблизительный. */
	private static final String[] NICK_COLORS = {
			"#D66E34", "#C8A2C8", "#FF6B6B", "#4ECDC4", "#45B7D1", "#96CEB4", "#FFD93D", "#B983FF",
			"#FF8E72", "#6BCB77", "#4D96FF", "#F38BA0", "#9BDEAC", "#F9B572", "#C7B8EA", "#7FB5FF"
	};

	/**
	 * Разбирает публикацию канала WebSocket. {@code payload} — содержимое pub.data: {"type":"...","data":{...}}.
	 */
	public static Parsed parse(JsonObject payload) {
		if (payload == null) {
			return Parsed.ignored("");
		}
		String type = str(payload, "type").toLowerCase(Locale.ROOT);
		JsonObject data = obj(payload, "data");
		if (data == null) {
			data = payload;
		}
		switch (type) {
			case "message", "chat_message", "new_message":
				return new Parsed(type, chatMessage(data), "", "", null);
			case "cp_reward_demand", "reward_demand", "channel_point_reward_demand", "channel_points_reward_demand":
				return demand(type, data);
			case "actions_journal_new_event", "actions_journal_event", "channel_action":
				return journal(type, data);
			case "stream_online_status":
				return new Parsed(type, null, "", firstStr(data, "streamId", "stream_id", "id"), bool(data, "isOnline", "is_online", "online"));
			case "stream_start", "stream_started", "stream_online":
				return new Parsed(type, null, "", firstStr(data, "streamId", "stream_id", "id"), Boolean.TRUE);
			case "stream_end", "stream_ended", "stream_offline":
				return new Parsed(type, null, "", "", Boolean.FALSE);
			default:
				// В некоторых событиях тип лежит внутри data (например, журнал действий без обёртки)
				if (type.isEmpty() && data.has("type")) {
					String inner = str(data, "type").toLowerCase(Locale.ROOT);
					if (!inner.isEmpty() && !inner.equals(type)) {
						return parse(wrap(inner, data));
					}
				}
				return Parsed.ignored(type);
		}
	}

	private static JsonObject wrap(String type, JsonObject data) {
		JsonObject wrapped = new JsonObject();
		wrapped.addProperty("type", type);
		wrapped.add("data", data);
		return wrapped;
	}

	// ---------- Чат ----------

	/** Сообщение чата → TwitchEvent.CHAT (платформа vk). Null, если нет автора. */
	public static TwitchEvent chatMessage(JsonObject data) {
		JsonObject author = firstObj(data, "author", "user", "sender");
		if (author == null) {
			return null;
		}
		String nick = firstStr(author, "nick", "name", "login");
		String display = firstStr(author, "displayName", "display_name");
		if (display.isEmpty()) {
			display = nick;
		}
		if (display.isEmpty()) {
			return null;
		}
		JsonElement parts = first(data, "data", "parts", "content", "message_parts", "messageParts", "blocks");
		String text = partsText(parts);
		if (text.isEmpty()) {
			text = firstStr(data, "text", "message");
		}
		Set<String> badges = badges(author);
		String color = nickColor(author);
		String id = firstStr(author, "id");
		return new TwitchEvent(TwitchEvent.Type.CHAT, clean(display), clean(nick).toLowerCase(Locale.ROOT), id, 0,
				clean(text), "", "", "", "", Collections.unmodifiableSet(badges), color, "", false, "", TwitchEvent.PLATFORM_VK);
	}

	/**
	 * Текст из списка блоков сообщения. Поддерживаются оба формата:
	 * REST — [{"text":{"content":".."}},{"mention":{"nick":".."}},{"smile":{"name":".."}},{"link":{"url":".."}}];
	 * WebSocket — [{"type":"text","content":"[\"..\",\"unstyled\",[]]"},{"type":"mention","nick":".."},...].
	 */
	public static String partsText(JsonElement parts) {
		if (parts == null || parts.isJsonNull()) {
			return "";
		}
		if (parts.isJsonPrimitive()) {
			return decodeContent(parts.getAsString());
		}
		if (!parts.isJsonArray()) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (JsonElement element : parts.getAsJsonArray()) {
			if (!element.isJsonObject()) {
				if (element.isJsonPrimitive()) {
					append(sb, decodeContent(element.getAsString()));
				}
				continue;
			}
			JsonObject part = element.getAsJsonObject();
			String type = str(part, "type").toLowerCase(Locale.ROOT);
			if (part.has("text") && part.get("text").isJsonObject()) {
				append(sb, decodeContent(str(part.getAsJsonObject("text"), "content")));
			} else if (part.has("mention") && part.get("mention").isJsonObject()) {
				JsonObject mention = part.getAsJsonObject("mention");
				append(sb, "@" + firstStr(mention, "displayName", "display_name", "nick", "name"));
			} else if (part.has("smile") && part.get("smile").isJsonObject()) {
				append(sb, ":" + firstStr(part.getAsJsonObject("smile"), "name", "id") + ":");
			} else if (part.has("link") && part.get("link").isJsonObject()) {
				JsonObject link = part.getAsJsonObject("link");
				append(sb, firstStr(link, "content", "url"));
			} else {
				switch (type) {
					case "text" -> append(sb, decodeContent(str(part, "content")));
					case "mention" -> append(sb, "@" + firstStr(part, "displayName", "display_name", "nick", "name"));
					case "smile" -> append(sb, ":" + firstStr(part, "name", "id") + ":");
					case "link" -> append(sb, firstStr(part, "content", "url"));
					default -> {
						String content = str(part, "content");
						if (!content.isEmpty()) {
							append(sb, decodeContent(content));
						}
					}
				}
			}
		}
		return sb.toString().trim();
	}

	private static void append(StringBuilder sb, String piece) {
		if (piece == null || piece.isEmpty()) {
			return;
		}
		if (sb.length() > 0 && !Character.isWhitespace(sb.charAt(sb.length() - 1)) && !Character.isWhitespace(piece.charAt(0))) {
			sb.append(' ');
		}
		sb.append(piece);
	}

	/** Текстовый блок WebSocket приходит как JSON-массив ["текст","unstyled",[]] в строке — достаём текст. */
	public static String decodeContent(String content) {
		if (content == null) {
			return "";
		}
		String trimmed = content.trim();
		if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
			try {
				JsonElement element = JsonParser.parseString(trimmed);
				if (element.isJsonArray()) {
					JsonArray array = element.getAsJsonArray();
					if (!array.isEmpty() && array.get(0).isJsonPrimitive()) {
						return array.get(0).getAsString();
					}
					return "";
				}
			} catch (Exception ignored) {
				// обычный текст в квадратных скобках
			}
		}
		return content;
	}

	private static Set<String> badges(JsonObject author) {
		Set<String> badges = new HashSet<>();
		if (bool(author, "isOwner", "is_owner") == Boolean.TRUE) {
			badges.add("broadcaster");
		}
		if (bool(author, "isChatModerator", "isChannelModerator", "is_moderator", "isModerator", "is_chat_moderator", "is_channel_moderator") == Boolean.TRUE) {
			badges.add("moderator");
		}
		if (bool(author, "isSubscriber", "is_subscriber", "isPaidSubscriber") == Boolean.TRUE) {
			badges.add("subscriber");
		}
		for (String listKey : new String[]{"roles", "badges"}) {
			JsonElement list = author.get(listKey);
			if (list == null || !list.isJsonArray()) {
				continue;
			}
			for (JsonElement element : list.getAsJsonArray()) {
				if (!element.isJsonObject()) {
					continue;
				}
				JsonObject item = element.getAsJsonObject();
				String text = (firstStr(item, "type") + " " + firstStr(item, "id") + " " + firstStr(item, "name") + " " + firstStr(item, "title")).toLowerCase(Locale.ROOT);
				if (text.contains("moder") || text.contains("модер")) {
					badges.add("moderator");
				} else if (text.contains("owner") || text.contains("streamer") || text.contains("стример") || text.contains("владел")) {
					badges.add("broadcaster");
				} else if (text.contains("subscri") || text.contains("подпис") || text.contains("sponsor")) {
					badges.add("subscriber");
				} else if (text.contains("vip")) {
					badges.add("vip");
				}
			}
		}
		return badges;
	}

	private static String nickColor(JsonObject author) {
		JsonElement color = first(author, "nickColor", "nick_color", "color");
		if (color == null || color.isJsonNull() || !color.isJsonPrimitive()) {
			return "";
		}
		if (color.getAsJsonPrimitive().isNumber()) {
			int index = color.getAsInt();
			return index >= 0 && index < NICK_COLORS.length ? NICK_COLORS[index] : "";
		}
		String text = color.getAsString().trim();
		if (text.matches("#[0-9a-fA-F]{6}")) {
			return text;
		}
		if (text.matches("\\d+")) {
			int index = Integer.parseInt(text);
			return index >= 0 && index < NICK_COLORS.length ? NICK_COLORS[index] : "";
		}
		return "";
	}

	// ---------- Награды за баллы ----------

	private static Parsed demand(String type, JsonObject data) {
		JsonObject demand = data;
		JsonObject nested = firstObj(data, "demand", "reward_demand", "rewardDemand");
		if (nested != null) {
			demand = nested;
		}
		JsonObject reward = firstObj(demand, "reward");
		JsonObject user = firstObj(demand, "user", "author", "buyer");
		if (reward == null && user == null) {
			return Parsed.ignored(type);
		}
		String demandId = firstStr(demand, "demandId", "demand_id", "id");
		String status = firstStr(demand, "status").toLowerCase(Locale.ROOT);
		String title = reward == null ? "" : firstStr(reward, "name", "title");
		String rewardId = reward == null ? "" : firstStr(reward, "id");
		int cost = reward == null ? 0 : integer(reward, "price", "cost");
		String nick = user == null ? "" : firstStr(user, "nick", "name", "login");
		String display = user == null ? "" : firstStr(user, "displayName", "display_name");
		if (display.isEmpty()) {
			display = nick;
		}
		if (display.isEmpty()) {
			display = "Аноним";
		}
		String userId = user == null ? "" : firstStr(user, "id");
		JsonElement parts = first(demand, "activationMessage", "activation_message", "message_parts", "messageParts", "message", "text");
		String text = partsText(parts);
		TwitchEvent event = new TwitchEvent(TwitchEvent.Type.REWARD, clean(display), clean(nick).toLowerCase(Locale.ROOT), userId, cost,
				clean(text), clean(title), "", rewardId, demandId, Set.of(), "", "", false, "", TwitchEvent.PLATFORM_VK);
		return new Parsed(type, event, status, "", null);
	}

	// ---------- Журнал действий канала (подписчики, награды) ----------

	private static Parsed journal(String type, JsonObject data) {
		String kind = firstStr(data, "type", "action_type", "actionType", "event_type", "eventType").toLowerCase(Locale.ROOT);
		String full = type + ":" + kind;
		if (kind.contains("follow")) {
			JsonObject user = firstObj(data, "follower", "user", "author", "subscriber");
			if (user == null) {
				return Parsed.ignored(full);
			}
			return new Parsed(full, person(TwitchEvent.Type.FOLLOW, user, 0, "", "", ""), "", "", null);
		}
		if (kind.contains("reward") || kind.contains("demand")) {
			Parsed parsed = demand(full, data);
			return parsed.event() == null ? Parsed.ignored(full) : parsed;
		}
		if (kind.contains("subscri") || kind.contains("sponsor")) {
			// платная подписка на канал (если VK присылает её в журнал): считаем как подписку Tier 1
			JsonObject user = firstObj(data, "subscriber", "user", "author", "sponsor");
			if (user == null) {
				return Parsed.ignored(full);
			}
			int months = integer(data, "months", "month", "duration");
			TwitchEvent.Type eventType = months > 1 ? TwitchEvent.Type.RESUB : TwitchEvent.Type.SUBSCRIBE;
			return new Parsed(full, person(eventType, user, Math.max(1, months), "", "", "1"), "", "", null);
		}
		return Parsed.ignored(full);
	}

	private static TwitchEvent person(TwitchEvent.Type type, JsonObject user, int amount, String message, String reward, String tier) {
		String nick = firstStr(user, "nick", "name", "login");
		String display = firstStr(user, "displayName", "display_name");
		if (display.isEmpty()) {
			display = nick;
		}
		if (display.isEmpty()) {
			display = "Аноним";
		}
		return new TwitchEvent(type, clean(display), clean(nick).toLowerCase(Locale.ROOT), firstStr(user, "id"), amount, message, reward, tier,
				"", "", Set.of(), "", "", false, "", TwitchEvent.PLATFORM_VK);
	}

	/** Разбор запроса награды из REST-списка /channel_point/reward/demands (snake_case, reward только с id). */
	public static Parsed restDemand(JsonObject demand, String rewardTitle, int price) {
		Parsed parsed = demand("demand", demand);
		if (parsed.event() == null) {
			return parsed;
		}
		TwitchEvent e = parsed.event();
		TwitchEvent event = new TwitchEvent(e.type(), e.user(), e.userLogin(), e.userId(), price > 0 ? price : e.amount(), e.message(),
				rewardTitle == null || rewardTitle.isEmpty() ? e.reward() : clean(rewardTitle), e.tier(), e.rewardId(), e.redemptionId(),
				e.badges(), e.color(), e.command(), false, "", TwitchEvent.PLATFORM_VK);
		return new Parsed(parsed.type(), event, parsed.demandStatus(), "", null);
	}

	// ---------- Утилиты JSON ----------

	static String str(JsonObject obj, String key) {
		return VkApi.str(obj, key);
	}

	static JsonObject obj(JsonObject parent, String key) {
		if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) {
			return null;
		}
		return parent.getAsJsonObject(key);
	}

	static JsonElement first(JsonObject obj, String... keys) {
		if (obj == null) {
			return null;
		}
		for (String key : keys) {
			if (obj.has(key) && !obj.get(key).isJsonNull()) {
				return obj.get(key);
			}
		}
		return null;
	}

	static JsonObject firstObj(JsonObject obj, String... keys) {
		JsonElement element = first(obj, keys);
		return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
	}

	static String firstStr(JsonObject obj, String... keys) {
		JsonElement element = first(obj, keys);
		if (element == null) {
			return "";
		}
		if (element.isJsonPrimitive()) {
			return element.getAsString().trim();
		}
		return "";
	}

	static int integer(JsonObject obj, String... keys) {
		JsonElement element = first(obj, keys);
		if (element == null || !element.isJsonPrimitive()) {
			return 0;
		}
		try {
			return (int) Math.min(Integer.MAX_VALUE, Math.max(0, (long) element.getAsDouble()));
		} catch (Exception e) {
			return 0;
		}
	}

	static Boolean bool(JsonObject obj, String... keys) {
		JsonElement element = first(obj, keys);
		if (element == null || !element.isJsonPrimitive()) {
			return null;
		}
		if (element.getAsJsonPrimitive().isBoolean()) {
			return element.getAsBoolean();
		}
		String text = element.getAsString().trim().toLowerCase(Locale.ROOT);
		if (text.equals("true") || text.equals("1") || text.equals("yes")) {
			return Boolean.TRUE;
		}
		if (text.equals("false") || text.equals("0") || text.equals("no")) {
			return Boolean.FALSE;
		}
		return null;
	}

	/** Убирает переводы строк, управляющие символы и §-коды из текста зрителей. */
	static String clean(String text) {
		if (text == null) {
			return "";
		}
		return text.replaceAll("\\p{Cntrl}", " ").replace("§", "").trim();
	}
}
