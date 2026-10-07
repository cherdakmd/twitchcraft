package dev.dedworkshop.twitchcraft.twitch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Единое описание события, уже разобранное из Twitch, VK Video Live, YouTube Live или Minecraft.
 *
 * @param type         тип события
 * @param user         отображаемое имя зрителя (или «Аноним»)
 * @param userLogin    логин зрителя (строчными буквами)
 * @param userId       id зрителя в Twitch
 * @param amount       число: битсы / месяцы / подарки / зрители рейда / стоимость награды / целая сумма доната
 * @param message      текст зрителя (сообщение к битсам, членству/ресабу, донату, вводу к награде или текст чата)
 * @param reward       название награды за баллы канала (только для REWARD)
 * @param tier         уровень подписки Twitch / YouTube-членства или код валюты доната
 * @param rewardId     id награды (для управления наградой через API)
 * @param redemptionId id активации награды (для возврата/подтверждения)
 * @param badges       значки зрителя в чате: broadcaster, moderator, vip, subscriber, founder
 * @param color        цвет ника в чате (#RRGGBB) или ""
 * @param command      имя чат-команды без префикса (только для CHAT_COMMAND)
 * @param synthetic    true для тестовых событий и повторов: без обращений к Twitch API и без кулдаунов
 * @param sharedFrom   логин канала-партнёра, если сообщение пришло из общего чата (Shared Chat) другого канала; иначе ""
 * @param platform     откуда пришло событие: Twitch (по умолчанию), VK Video Live или YouTube Live
 */
public record TwitchEvent(
		Type type,
		String user,
		String userLogin,
		String userId,
		int amount,
		String message,
		String reward,
		String tier,
		String rewardId,
		String redemptionId,
		Set<String> badges,
		String color,
		String command,
		boolean synthetic,
		String sharedFrom,
		String platform
) {

	/** Конструктор без {@code sharedFrom} (обычное событие своего канала Twitch). */
	public TwitchEvent(Type type, String user, String userLogin, String userId, int amount, String message, String reward,
					   String tier, String rewardId, String redemptionId, Set<String> badges, String color, String command,
					   boolean synthetic) {
		this(type, user, userLogin, userId, amount, message, reward, tier, rewardId, redemptionId, badges, color, command, synthetic, "", PLATFORM_TWITCH);
	}

	/** Конструктор без {@code platform} (событие Twitch). */
	public TwitchEvent(Type type, String user, String userLogin, String userId, int amount, String message, String reward,
					   String tier, String rewardId, String redemptionId, Set<String> badges, String color, String command,
					   boolean synthetic, String sharedFrom) {
		this(type, user, userLogin, userId, amount, message, reward, tier, rewardId, redemptionId, badges, color, command, synthetic, sharedFrom, PLATFORM_TWITCH);
	}

	public TwitchEvent {
		if (sharedFrom == null) {
			sharedFrom = "";
		}
		if (platform == null || platform.isBlank()) {
			platform = PLATFORM_TWITCH;
		}
	}

	public static final String PLATFORM_TWITCH = "twitch";
	public static final String PLATFORM_VK = "vk";
	public static final String PLATFORM_YOUTUBE = "youtube";

	/** Сообщение пришло из общего чата (Shared Chat) и написано в чате другого канала. */
	public boolean isShared() {
		return !sharedFrom.isEmpty();
	}

	/** Событие пришло с VK Video Live (live.vkvideo.ru), а не с Twitch. */
	public boolean isVk() {
		return PLATFORM_VK.equals(platform);
	}

	/** Событие пришло из чата YouTube Live. */
	public boolean isYoutube() {
		return PLATFORM_YOUTUBE.equals(platform);
	}

	/** Короткое название площадки для сообщений: «Twitch» / «VK» / «YouTube» / «Игра». */
	public String platformTitle() {
		return isVk() ? "VK" : isYoutube() ? "YouTube" : isGame() ? "Игра" : "Twitch";
	}

	/** Копия события с другой площадкой (для событий VK/YouTube Live и тестов). */
	public TwitchEvent withPlatform(String newPlatform) {
		return new TwitchEvent(type, user, userLogin, userId, amount, message, reward, tier, rewardId, redemptionId,
				badges, color, command, synthetic, sharedFrom, newPlatform);
	}

	public enum Type {
		FOLLOW, SUBSCRIBE, RESUB, GIFT_SUB, CHEER, RAID, REWARD, CHAT, CHAT_COMMAND,
		/** Достигнута накопительная цель: reward — название цели, amount — порог, message — сколько раз достигнута. */
		GOAL,
		/** Сбор средств закрыт: reward — имя сбора, amount — цель, message — в который раз, tier — код валюты. */
		FUND,
		/**
		 * Донат через DonationAlerts / DonatePay / YouTube: amount — целая сумма в валюте источника,
		 * tier — код валюты, reward — источник, rewardId — id доната/сообщения.
		 */
		DONATION,
		/**
		 * Событие самой игры (1.7.0): reward — вид (death / advancement / advancementGoal / advancementChallenge / boss / dimension),
		 * user — игрок, message — текст (причина смерти, название достижения, босс, измерение), tier — дополнение
		 * (описание достижения, убийца босса, id измерения), amount — счётчик (номер смерти за стрим и т.п.).
		 */
		GAME
	}

	public static final String PLATFORM_GAME = "game";

	public static final String GAME_DEATH = "death";
	public static final String GAME_ADVANCEMENT = "advancement";
	public static final String GAME_ADVANCEMENT_GOAL = "advancementGoal";
	public static final String GAME_ADVANCEMENT_CHALLENGE = "advancementChallenge";
	public static final String GAME_BOSS = "boss";
	/** Босс аддона вышел в мир (публикует аддон, см. {@code AddonContext.publish}). */
	public static final String GAME_BOSS_SPAWN = "bossSpawn";
	/** Босс аддона повержен. */
	public static final String GAME_BOSS_DEFEAT = "bossDefeat";
	public static final String GAME_DIMENSION = "dimension";

	/**
	 * Событие игры.
	 *
	 * @param kind   вид: GAME_DEATH, GAME_ADVANCEMENT..., GAME_BOSS, GAME_BOSS_SPAWN, GAME_BOSS_DEFEAT, GAME_DIMENSION
	 * @param player имя игрока
	 * @param text   основной текст (причина смерти / название достижения / имя босса / название измерения)
	 * @param extra  дополнение (описание достижения / убийца / id измерения)
	 * @param amount счётчик (номер смерти за стрим и т.п.)
	 */
	public static TwitchEvent game(String kind, String player, String text, String extra, int amount, boolean synthetic) {
		String name = player == null || player.isBlank() ? "Игрок" : player;
		return new TwitchEvent(Type.GAME, name, name.toLowerCase(Locale.ROOT), "", Math.max(0, amount),
				text == null ? "" : text.replaceAll("\\p{Cntrl}", " ").replace("§", "").trim(),
				kind == null ? "" : kind, extra == null ? "" : extra.replaceAll("\\p{Cntrl}", " ").replace("§", "").trim(),
				"", "", Set.of("broadcaster"), "", "", synthetic, "", PLATFORM_GAME);
	}

	/** Это событие самой игры (смерть, достижение, босс, измерение), а не площадки. */
	public boolean isGame() {
		return PLATFORM_GAME.equals(platform);
	}

	/** Вид события игры (только для GAME): death, advancement, boss... */
	public String gameKind() {
		return type == Type.GAME ? reward : "";
	}

	public static final String SOURCE_DONATION_ALERTS = "donationalerts";
	public static final String SOURCE_DONATE_PAY = "donatepay";
	public static final String SOURCE_YOUTUBE_SUPER_CHAT = "youtube_superchat";
	public static final String SOURCE_YOUTUBE_SUPER_STICKER = "youtube_supersticker";
	public static final String SOURCE_YOUTUBE_FAN_FUNDING = "youtube_fanfunding";

	/** Уровень доступа зрителя для чат-команд. */
	public enum Permission {
		EVERYONE, SUBSCRIBER, VIP, MODERATOR, BROADCASTER;

		public static Permission parse(String value) {
			if (value == null) {
				return EVERYONE;
			}
			return switch (value.trim().toLowerCase(Locale.ROOT)) {
				case "sub", "subs", "subscriber", "subscribers" -> SUBSCRIBER;
				case "vip", "vips" -> VIP;
				case "mod", "mods", "moderator", "moderators" -> MODERATOR;
				case "broadcaster", "streamer", "owner" -> BROADCASTER;
				default -> EVERYONE;
			};
		}
	}

	private static final String ANONYMOUS = "Аноним";

	// ---------- Фабрики ----------

	/** Короткий конструктор для событий без чат-данных. */
	public static TwitchEvent simple(Type type, String user, String userLogin, int amount, String message, String reward, String tier) {
		return new TwitchEvent(type, user, userLogin, "", amount, message, reward, tier, "", "", Set.of(), "", "", false);
	}

	/** Тестовое событие (/twitch test ...): не трогает Twitch API и игнорирует кулдауны. */
	public static TwitchEvent test(Type type, String user, int amount, String message, String reward, String tier) {
		return new TwitchEvent(type, user, user.toLowerCase(Locale.ROOT), "0", amount, message, reward, tier, "", "",
				Set.of("broadcaster"), "", "", true);
	}

	/**
	 * Событие «цель достигнута».
	 *
	 * @param goalName название цели
	 * @param target   порог цели
	 * @param times    в который раз достигнута
	 * @param lastUser зритель, чей вклад закрыл цель
	 * @param synthetic true, если цель закрыта тестовым событием
	 */
	public static TwitchEvent goal(String goalName, int target, int times, String lastUser, String lastLogin, boolean synthetic) {
		String user = lastUser == null || lastUser.isBlank() ? ANONYMOUS : lastUser;
		return new TwitchEvent(Type.GOAL, user, lastLogin == null ? "" : lastLogin, "", target, String.valueOf(times),
				goalName, "", "", "", Set.of(), "", "", synthetic);
	}

	/**
	 * Событие «сбор закрыт» (полоса-боссбар заполнена).
	 *
	 * @param fundName имя сбора
	 * @param target   цель сбора в основной валюте
	 * @param times    в который раз закрыт (после сброса сбор можно закрыть снова)
	 * @param currency код валюты (RUB...)
	 * @param lastUser чей вклад закрыл сбор
	 */
	public static TwitchEvent fund(String fundName, int target, int times, String currency, String lastUser, String lastLogin, boolean synthetic) {
		String user = lastUser == null || lastUser.isBlank() ? ANONYMOUS : lastUser;
		return new TwitchEvent(Type.FUND, user, lastLogin == null ? "" : lastLogin, "", target, String.valueOf(times),
				fundName, currency == null ? "" : currency, "", "", Set.of(), "", "", synthetic);
	}

	/**
	 * Донат.
	 *
	 * @param source    DonationAlerts / DonatePay / YouTube Super Chat, Super Sticker or Fan Funding
		 * @param user      имя донатера
		 * @param amount    сумма в валюте источника, округлённая вниз до целого
	 * @param currency  код валюты (RUB, USD...)
	 * @param message   сообщение донатера
	 * @param id        id доната в сервисе (для защиты от повторов)
	 * @param synthetic true для тестов
	 */
	public static TwitchEvent donation(String source, String user, int amount, String currency, String message, String id, boolean synthetic) {
		String name = user == null || user.isBlank() ? ANONYMOUS : user.replaceAll("\\p{Cntrl}", " ").replace("§", "").trim();
		String text = message == null ? "" : message.replaceAll("\\p{Cntrl}", " ").replace("§", "").trim();
		return new TwitchEvent(Type.DONATION, name, name.toLowerCase(Locale.ROOT), "", Math.max(0, amount), text,
				source == null ? "" : source.toLowerCase(Locale.ROOT), currency == null ? "" : currency.toUpperCase(Locale.ROOT),
				id == null ? "" : id, "", Set.of(), "", "", synthetic);
	}

	/** Источник доната (только для DONATION). */
	public String source() {
		return type == Type.DONATION ? reward : "";
	}

	/** Код валюты доната (только для DONATION). */
	public String currency() {
		return type == Type.DONATION ? tier : "";
	}

	/** Человекочитаемое название источника доната. */
	public String sourceTitle() {
		return switch (source()) {
			case SOURCE_DONATION_ALERTS -> "DonationAlerts";
			case SOURCE_DONATE_PAY -> "DonatePay";
			case SOURCE_YOUTUBE_SUPER_CHAT -> "YouTube Super Chat";
			case SOURCE_YOUTUBE_SUPER_STICKER -> "YouTube Super Sticker";
			case SOURCE_YOUTUBE_FAN_FUNDING -> "YouTube Fan Funding";
			case "" -> "";
			default -> source();
		};
	}

	/** Копия события как чат-команды: в message остаются только аргументы после имени команды. */
	public TwitchEvent asCommand(String commandName, String args) {
		return new TwitchEvent(Type.CHAT_COMMAND, user, userLogin, userId, amount, args == null ? "" : args, reward, tier,
				rewardId, redemptionId, badges, color, commandName, synthetic, sharedFrom, platform);
	}

	/** Копия без текста зрителя (например, чтобы не показывать сообщение донатера в чате). */
	public TwitchEvent withoutMessage() {
		return new TwitchEvent(type, user, userLogin, userId, amount, "", reward, tier, rewardId, redemptionId,
				badges, color, command, synthetic, sharedFrom, platform);
	}

	/** Копия события как «искусственного» (повтор последнего события). */
	public TwitchEvent asSynthetic() {
		return new TwitchEvent(type, user, userLogin, userId, amount, message, reward, tier, rewardId, redemptionId,
				badges, color, command, true, sharedFrom, platform);
	}

	// ---------- Разбор EventSub ----------

	/**
	 * Разбирает событие из EventSub-уведомления.
	 *
	 * @param subscriptionType тип подписки, например "channel.cheer"
	 * @param e                объект payload.event
	 * @return событие или null, если тип не поддерживается / событие нужно пропустить
	 */
	public static TwitchEvent fromEventSub(String subscriptionType, JsonObject e) {
		switch (subscriptionType) {
			case "channel.follow":
				return simple(Type.FOLLOW, name(e, "user_name"), str(e, "user_login"), 0, "", "", "")
						.withUserId(str(e, "user_id"));

			case "channel.subscribe":
				// Подарочные подписки приходят отдельным событием channel.subscription.gift,
				// чтобы не срабатывать дважды — пропускаем их здесь.
				if (bool(e, "is_gift")) {
					return null;
				}
				return simple(Type.SUBSCRIBE, name(e, "user_name"), str(e, "user_login"), 1, "", "", tier(e))
						.withUserId(str(e, "user_id"));

			case "channel.subscription.message": {
				String text = "";
				if (e.has("message") && e.get("message").isJsonObject()) {
					text = str(e.getAsJsonObject("message"), "text");
				}
				int months = integer(e, "cumulative_months");
				if (months <= 0) {
					months = integer(e, "duration_months");
				}
				return simple(Type.RESUB, name(e, "user_name"), str(e, "user_login"), Math.max(months, 1), text, "", tier(e))
						.withUserId(str(e, "user_id"));
			}

			case "channel.subscription.gift": {
				boolean anonymous = bool(e, "is_anonymous");
				String user = anonymous ? ANONYMOUS : name(e, "user_name");
				String login = anonymous ? "" : str(e, "user_login");
				return simple(Type.GIFT_SUB, user, login, Math.max(integer(e, "total"), 1), "", "", tier(e))
						.withUserId(anonymous ? "" : str(e, "user_id"));
			}

			case "channel.cheer": {
				boolean anonymous = bool(e, "is_anonymous");
				String user = anonymous ? ANONYMOUS : name(e, "user_name");
				String login = anonymous ? "" : str(e, "user_login");
				return simple(Type.CHEER, user, login, integer(e, "bits"), str(e, "message"), "", "")
						.withUserId(anonymous ? "" : str(e, "user_id"));
			}

			case "channel.raid":
				return simple(Type.RAID, name(e, "from_broadcaster_user_name"), str(e, "from_broadcaster_user_login"),
						integer(e, "viewers"), "", "", "")
						.withUserId(str(e, "from_broadcaster_user_id"));

			case "channel.channel_points_custom_reward_redemption.add": {
				String title = "";
				String rewardId = "";
				int cost = 0;
				if (e.has("reward") && e.get("reward").isJsonObject()) {
					JsonObject reward = e.getAsJsonObject("reward");
					title = str(reward, "title");
					rewardId = str(reward, "id");
					cost = integer(reward, "cost");
				}
				return new TwitchEvent(Type.REWARD, name(e, "user_name"), str(e, "user_login"), str(e, "user_id"), cost,
						str(e, "user_input"), title, "", rewardId, str(e, "id"), Set.of(), "", "", false);
			}

			case "channel.chat.message": {
				String text = "";
				if (e.has("message") && e.get("message").isJsonObject()) {
					text = str(e.getAsJsonObject("message"), "text");
				}
				Set<String> badges = new HashSet<>();
				if (e.has("badges") && e.get("badges").isJsonArray()) {
					JsonArray array = e.getAsJsonArray("badges");
					for (JsonElement element : array) {
						if (element.isJsonObject()) {
							badges.add(str(element.getAsJsonObject(), "set_id").toLowerCase(Locale.ROOT));
						}
					}
				}
				int bits = 0;
				if (e.has("cheer") && e.get("cheer").isJsonObject()) {
					bits = integer(e.getAsJsonObject("cheer"), "bits");
				}
				// Shared Chat: сообщение написано в чате канала-партнёра (source_broadcaster_user_id ≠ наш канал)
				String sharedFrom = "";
				String sourceId = str(e, "source_broadcaster_user_id");
				if (!sourceId.isEmpty() && !sourceId.equals(str(e, "broadcaster_user_id"))) {
					sharedFrom = str(e, "source_broadcaster_user_login");
					if (sharedFrom.isEmpty()) {
						sharedFrom = sourceId;
					}
				}
				return new TwitchEvent(Type.CHAT, name(e, "chatter_user_name"), str(e, "chatter_user_login"), str(e, "chatter_user_id"),
						bits, text, "", "", "", "", Collections.unmodifiableSet(badges), str(e, "color"), "", false, sharedFrom);
			}

			default:
				return null;
		}
	}

	private TwitchEvent withUserId(String id) {
		return new TwitchEvent(type, user, userLogin, id, amount, message, reward, tier, rewardId, redemptionId, badges, color, command, synthetic, sharedFrom, platform);
	}

	// ---------- Удобные методы ----------

	public boolean isAnonymous() {
		return userLogin == null || userLogin.isBlank();
	}

	/** Уровень доступа по значкам чата. */
	public Permission permission() {
		if (badges == null) {
			return Permission.EVERYONE;
		}
		if (badges.contains("broadcaster")) {
			return Permission.BROADCASTER;
		}
		if (badges.contains("moderator")) {
			return Permission.MODERATOR;
		}
		if (badges.contains("vip")) {
			return Permission.VIP;
		}
		if (badges.contains("subscriber") || badges.contains("founder")) {
			return Permission.SUBSCRIBER;
		}
		return Permission.EVERYONE;
	}

	/** Ключ для кулдаунов и журнала: "reward:Зомби", "chat:zombie", "cheer", ... */
	public String actionKey() {
		return switch (type) {
			case REWARD -> "reward:" + reward.toLowerCase(Locale.ROOT);
			case CHAT_COMMAND -> "chat:" + command.toLowerCase(Locale.ROOT);
			case FOLLOW -> "follow";
			case SUBSCRIBE -> "subscribe";
			case RESUB -> "resub";
			case GIFT_SUB -> "giftSub";
			case CHEER -> "cheer";
			case RAID -> "raid";
			case CHAT -> "chat";
			case GOAL -> "goal:" + reward.toLowerCase(Locale.ROOT);
			case FUND -> "fund:" + reward.toLowerCase(Locale.ROOT);
			case DONATION -> "donation";
			case GAME -> "game:" + reward;
		};
	}

	/** Человекочитаемое описание события для чата (без цветовых кодов из текста зрителя). */
	public String describe() {
		return (isVk() ? "§9[VK] §r" : isYoutube() ? "§c[YouTube] §r" : isGame() ? "§2[Игра] §r" : "") + switch (type) {
			case FOLLOW -> "§d" + user + "§r зафолловил(а) канал";
			case SUBSCRIBE -> isYoutube()
					? "§d" + user + "§r стал(а) участником канала" + (tier.isBlank() ? "" : " (" + tier + ")")
					: "§d" + user + "§r оформил(а) подписку (Tier " + tier + ")";
			case RESUB -> isYoutube()
					? "§d" + user + "§r участник уже " + amount + " мес." + (tier.isBlank() ? "" : " (" + tier + ")") + suffix(message)
					: "§d" + user + "§r продлил(а) подписку: " + amount + " мес." + suffix(message);
			case GIFT_SUB -> isYoutube()
					? "§d" + user + "§r подарил(а) участий: " + amount + (tier.isBlank() ? "" : " (" + tier + ")")
					: "§d" + user + "§r подарил(а) подписок: " + amount;
			case CHEER -> "§d" + user + "§r отправил(а) " + amount + " битс" + suffix(message);
			case RAID -> "Рейд от §d" + user + "§r — зрителей: " + amount;
			case REWARD -> "§d" + user + "§r активировал(а) награду «" + reward + "» (" + amount + ")" + suffix(message);
			case CHAT -> "§d" + user + "§r: " + message;
			case CHAT_COMMAND -> "§d" + user + "§r использовал(а) команду !" + command;
			case GOAL -> "§6Цель «" + reward + "» достигнута" + (timesSuffix()) + "! Последний вклад: §d" + user;
			case FUND -> "§6Сбор «" + reward + "» закрыт" + (timesSuffix()) + ": собрано §e" + amount + " " + currencySymbol()
					+ "§6! Последний вклад: §d" + user;
			case DONATION -> "§d" + user + "§r задонатил(а) §6" + amount + " " + currencySymbol() + "§r"
					+ (sourceTitle().isEmpty() ? "" : " через " + sourceTitle()) + suffix(message);
			case GAME -> switch (reward) {
				case GAME_DEATH -> "§cсмерть №" + amount + "§r" + suffix(message);
				case GAME_ADVANCEMENT -> "достижение «" + message + "»" + suffix(tier);
				case GAME_ADVANCEMENT_GOAL -> "цель «" + message + "»" + suffix(tier);
				case GAME_ADVANCEMENT_CHALLENGE -> "испытание «" + message + "»" + suffix(tier);
				case GAME_BOSS -> "§6босс повержен: " + message + "§r" + (tier.isBlank() ? "" : " (" + tier + ")");
				case GAME_BOSS_SPAWN -> "§6босс появился: " + message + "§r" + (tier.isBlank() ? "" : " (" + tier + ")");
				case GAME_BOSS_DEFEAT -> "§6босс аддона повержен: " + message + "§r" + suffix(tier);
				case GAME_DIMENSION -> "переход в " + message;
				default -> reward + suffix(message);
			};
		};
	}

	/** Символ валюты для сообщений: ₽, $, €... или код, если символ неизвестен. */
	public String currencySymbol() {
		return currencySymbol(tier);
	}

	public static String currencySymbol(String code) {
		if (code == null) {
			return "";
		}
		return switch (code.toUpperCase(Locale.ROOT)) {
			case "RUB" -> "₽";
			case "USD" -> "$";
			case "EUR" -> "€";
			case "UAH" -> "₴";
			case "KZT" -> "₸";
			case "BYN" -> "Br";
			case "GBP" -> "£";
			case "TRY" -> "₺";
			case "PLN" -> "zł";
			case "" -> "";
			default -> code.toUpperCase(Locale.ROOT);
		};
	}

	private String timesSuffix() {
		try {
			int times = Integer.parseInt(message);
			return times > 1 ? " (" + times + "-й раз)" : "";
		} catch (NumberFormatException e) {
			return "";
		}
	}

	/** Короткая строка для журнала и HUD. */
	public String shortText() {
		return (isVk() ? "VK " : isYoutube() ? "YouTube " : "") + switch (type) {
			case FOLLOW -> user + " · фоллов";
			case SUBSCRIBE -> isYoutube() ? user + " · членство " + tier : user + " · саб T" + tier;
			case RESUB -> isYoutube() ? user + " · участие " + amount + " мес." : user + " · ресаб " + amount + " мес.";
			case GIFT_SUB -> isYoutube() ? user + " · подарил участий " + amount : user + " · подарил " + amount;
			case CHEER -> user + " · " + amount + " битс";
			case RAID -> user + " · рейд " + amount;
			case REWARD -> user + " · " + reward;
			case CHAT -> user + ": " + message;
			case CHAT_COMMAND -> user + " · !" + command;
			case GOAL -> "цель · " + reward;
			case FUND -> "сбор · " + reward;
			case DONATION -> user + " · донат " + amount + " " + currencySymbol();
			case GAME -> "игра · " + switch (reward) {
				case GAME_DEATH -> "смерть №" + amount;
				case GAME_BOSS -> "босс " + message;
				case GAME_BOSS_SPAWN -> "босс появился · " + message;
				case GAME_BOSS_DEFEAT -> "босс повержен · " + message;
				case GAME_DIMENSION -> message;
				default -> message;
			};
		};
	}

	private static String suffix(String text) {
		return text == null || text.isBlank() ? "" : ": " + text;
	}

	// ---------- Утилиты разбора JSON ----------

	private static String str(JsonObject obj, String key) {
		if (obj == null || !obj.has(key)) {
			return "";
		}
		JsonElement element = obj.get(key);
		if (element.isJsonNull()) {
			return "";
		}
		// Убираем переводы строк, управляющие символы и §-коды из текста зрителей
		return element.getAsString().replaceAll("\\p{Cntrl}", " ").replace("§", "").trim();
	}

	private static String name(JsonObject obj, String key) {
		String value = str(obj, key);
		return value.isBlank() ? ANONYMOUS : value;
	}

	private static int integer(JsonObject obj, String key) {
		if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
			return 0;
		}
		try {
			return obj.get(key).getAsInt();
		} catch (Exception e) {
			return 0;
		}
	}

	private static boolean bool(JsonObject obj, String key) {
		return obj != null && obj.has(key) && !obj.get(key).isJsonNull() && obj.get(key).getAsBoolean();
	}

	/** Twitch присылает "1000"/"2000"/"3000" — превращаем в "1"/"2"/"3". */
	private static String tier(JsonObject obj) {
		String raw = str(obj, "tier");
		return switch (raw) {
			case "1000" -> "1";
			case "2000" -> "2";
			case "3000" -> "3";
			case "" -> "1";
			default -> raw;
		};
	}
}
