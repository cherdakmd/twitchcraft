package dev.dedworkshop.twitchcraft.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Настройки мода. Хранятся в файле .minecraft/config/twitchcraft.json.
 * Файл создаётся автоматически с примерами при первом запуске.
 *
 * Плейсхолдеры в сообщениях и командах:
 *   {user} {user_login} {amount} {message} {reward} {tier} {player} {command} {loot}
 *   {repeat} {i} — число повторов и номер текущего повтора
 *   {session_follows} {session_subs} {session_gifts} {session_bits} {session_raids}
 *   {session_rewards} {session_events} — статистика текущей сессии
 */
public class ModConfig {
	public static final String FILE_NAME = "twitchcraft.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	// ---------- Основные настройки ----------

	/** Версия формата файла (служебное, не менять). */
	public int configVersion = 13;

	/** Client ID твоего приложения с https://dev.twitch.tv/console/apps */
	public String clientId = "";

	/** Подключаться к Twitch автоматически при входе в мир. */
	public boolean autoConnect = true;

	/** Показывать все события Twitch в чате (фоллов, саб, награда...). */
	public boolean showEventsInChat = true;

	/** Показывать в чате ответы команд (например, "Призван Зомби"). Полезно для отладки. */
	public boolean showCommandOutput = false;

	/** Сколько тиков держать заголовок на экране (20 тиков = 1 секунда). */
	public int titleTicks = 60;

	// ---------- Модули ----------

	/**
	 * Включённые модули: follows, subscriptions, bits, raids, channelPoints, twitchChat, chatCommands,
	 * chatReplies, rewardManagement, goals, fundraisers, overlay, hotkeys, eventLog, donationAlerts, donatePay,
	 * vkVideoLive, youtubeLive, gameEvents, chatTimers, clips.
	 * Переключение: /twitch module <id> on|off или экран настроек (Mod Menu).
	 */
	public Map<String, Boolean> modules = new LinkedHashMap<>();

	// ---------- Очередь, пауза и защита от спама ----------

	/** Копить события, пока ты не в мире (меню, загрузка), и выполнить их после входа. */
	public boolean queueWhenNotInWorld = true;

	/** Максимум событий в очереди (лишние отбрасываются, баллы возвращаются). */
	public int maxQueuedEvents = 20;

	/** Пауза между событиями из очереди, в тиках. */
	public int queueDelayTicks = 20;

	/** Не больше стольких событий в минуту (0 — без ограничения). Лишние встают в очередь. */
	public int maxEventsPerMinute = 30;

	/** Команды, которые мод никогда не выполнит (защита от опасных настроек). */
	public List<String> blockedCommands = new ArrayList<>(List.of(
			"stop", "op", "deop", "ban", "ban-ip", "pardon", "pardon-ip", "kick", "whitelist",
			"save-off", "save-on", "publish", "debug", "reload", "perf", "jfr", "transfer"
	));

	/** Логины, чьи сообщения и команды игнорируются (боты). */
	public List<String> ignoredUsers = new ArrayList<>(List.of(
			"nightbot", "streamelements", "streamlabs", "moobot", "fossabot", "wizebot", "sery_bot"
	));

	// ---------- Чат Twitch ----------

	public TwitchChat twitchChat = new TwitchChat();

	public static class TwitchChat {
		/** Показывать значки: ♛ стример, ⚔ модератор, ◆ VIP, ★ подписчик. */
		public boolean showBadges = true;
		/** Не показывать сообщения-команды (!zombie), только выполнять их. */
		public boolean hideCommands = false;
		/** Префикс сообщений Twitch в чате Minecraft. */
		public String prefix = "&5[T]&r ";
		/** Показывать сообщения из общего чата (Shared Chat), написанные в чате канала-партнёра. */
		public boolean showSharedChat = true;
		/** Разрешать зрителям канала-партнёра (Shared Chat) запускать наши чат-команды. По умолчанию — нет. */
		public boolean sharedChatCommands = false;
	}

	// ---------- Таблички чата в воздухе ----------

	public ChatSignSettings chatSigns = new ChatSignSettings();

	/** Сообщения Twitch, VK и YouTube висят в воздухе перед игроком, как таблички (см. ui/ChatSigns). */
	public static class ChatSignSettings {
		/** Показывать сообщения чата табличками в воздухе перед игроком. */
		public boolean enabled = true;
		/** Дублировать сообщение строкой в окне чата Minecraft. Выключите, если нужны только таблички. */
		public boolean keepInChat = true;
		/** Сколько секунд табличка висит в воздухе. */
		public int seconds = 10;
		/** Сколько табличек одновременно. Лишние самые старые гаснут раньше срока. */
		public int maxVisible = 5;
		/** Расстояние до табличек, блоки. На этом расстоянии они имеют размер «100 %». */
		public int distance = 6;
		/** Размер табличек, проценты. */
		public int scale = 100;
	}

	// ---------- Ответы в чат Twitch ----------

	public ChatReplies chatReplies = new ChatReplies();

	public static class ChatReplies {
		/** Ответ зрителю, если команда на кулдауне. Пусто — не отвечать. */
		public String cooldownReply = "@{user}, подожди ещё {seconds} с";
		/** Ответ, если у зрителя нет прав на команду. Пусто — не отвечать. */
		public String noPermissionReply = "@{user}, эта команда только для {permission}";
	}

	// ---------- Награды: управление через API ----------

	public RewardsSettings rewardsSettings = new RewardsSettings();

	public static class RewardsSettings {
		/** После выполнения действия отмечать активацию выполненной (убирает её из очереди на Twitch). */
		public boolean autoFulfill = true;
		/** Возвращать баллы, если событие не удалось выполнить (очередь переполнена, кулдаун, команда заблокирована). */
		public boolean autoRefund = true;
		/** Стоимость по умолчанию при создании наград командой /twitch rewards sync. */
		public int defaultCost = 500;
	}

	// ---------- HUD-оверлей ----------

	public Overlay overlay = new Overlay();

	public static class Overlay {
		/** Угол экрана: top-left, top-right, bottom-left, bottom-right. */
		public String corner = "top-left";
		/** Сколько последних событий показывать. */
		public int maxEvents = 5;
		/** Сколько секунд событие остаётся в оверлее (0 — не убирать). */
		public int eventSeconds = 45;
		/** Показывать строку статистики сессии. */
		public boolean showStats = true;
		/** Показывать прогресс целей. */
		public boolean showGoals = true;
		/** Отступ от края экрана в пикселях. */
		public int margin = 4;
	}

	// ---------- Донаты (DonationAlerts / DonatePay) ----------

	public Donations donations = new Donations();

	public static class Donations {
		/** Основная валюта (код ISO): пороги эффектов и цели считаются в ней. */
		public String currency = "RUB";
		/** Донаты меньше этой суммы (в основной валюте) игнорируются (0 — не игнорировать). */
		public int minAmount = 1;
		/** Client ID приложения DonationAlerts (https://www.donationalerts.com/application/clients). */
		public String donationAlertsClientId = "";
		/** Как часто опрашивать DonatePay, секунд (правила DonatePay: не чаще раза в 20 секунд). */
		public int donatePayPollSeconds = 20;
		/** Порт локального адреса для входа в DonationAlerts: Redirect URI = http://localhost:ПОРТ/da */
		public int callbackPort = 8631;
		/** Показывать сообщение донатера в чате Minecraft. */
		public boolean showMessage = true;
	}

	/**
	 * Эффекты за донаты: ключ — минимальная сумма в основной валюте, выбирается самый большой подходящий порог.
	 * Общая таблица для всех сервисов. Плейсхолдеры: {user} {amount} {sum} {currency} {message} {source}.
	 */
	public Map<String, Action> donationTiers = new LinkedHashMap<>();
	/** Отдельные пороги только для DonationAlerts (пусто — используется общая таблица donationTiers). */
	public Map<String, Action> donationAlertsTiers = new LinkedHashMap<>();
	/** Отдельные пороги только для DonatePay (пусто — используется общая таблица donationTiers). */
	public Map<String, Action> donatePayTiers = new LinkedHashMap<>();

	// ---------- VK Video Live ----------

	public Vk vk = new Vk();

	/**
	 * Интеграция с VK Video Live (live.vkvideo.ru). Секрет приложения и токены хранятся отдельно —
	 * в config/twitchcraft-vk.json. Настройка: /twitch vk или экран «VK Video Live» в настройках мода.
	 */
	public static class Vk {
		/** Канал: ссылка (https://live.vkvideo.ru/dedworkshop) или только имя (dedworkshop). Пусто — свой канал после входа. */
		public String channelUrl = "";
		/** ID приложения с dev.live.vkvideo.ru/apps (секрет — командой /twitch vk app <id> <секрет>). */
		public String clientId = "";
		/** Порт локального адреса для входа: Redirect URI приложения = http://localhost:ПОРТ/vk */
		public int callbackPort = 8638;
		/** Показывать чат VK в чате Minecraft (с префиксом chatPrefix). */
		public boolean showChat = true;
		/** Префикс сообщений чата VK в игре. */
		public String chatPrefix = "&9[VK]&r ";
		/** Выполнять чат-команды зрителей VK (те же chatCommands, что и для Twitch). */
		public boolean chatCommands = true;
		/** Обрабатывать награды за баллы канала VK (те же rewards, что и для Twitch; совпадение по названию). */
		public boolean rewards = true;
		/** Новые подписчики (фолловеры) канала VK → действие follow. */
		public boolean follows = true;
		/** Отвечать зрителям в чат VK (reply, кулдауны, права) — нужно право chat:message:send. */
		public boolean replies = true;
		/** Подтверждать выполненные запросы наград (accept) и отклонять невыполненные (reject — баллы вернутся зрителю). */
		public boolean manageDemands = true;
		/** Писать все события WebSocket VK в лог игры (для диагностики незнакомых событий). */
		public boolean debugEvents = false;

		/** Имя канала без ссылки: "https://live.vkvideo.ru/dedworkshop/" → "dedworkshop". */
		public String slug() {
			return slugOf(channelUrl);
		}

		public static String slugOf(String value) {
			if (value == null) {
				return "";
			}
			String v = value.trim();
			if (v.isEmpty()) {
				return "";
			}
			int scheme = v.indexOf("://");
			if (scheme >= 0) {
				v = v.substring(scheme + 3);
			}
			int slash = v.indexOf('/');
			if (slash >= 0 && (v.startsWith("live.vkvideo.ru") || v.startsWith("vkplay.live") || v.startsWith("www.") || v.contains("."))) {
				v = v.substring(slash + 1);
			}
			int query = v.indexOf('?');
			if (query >= 0) {
				v = v.substring(0, query);
			}
			int hash = v.indexOf('#');
			if (hash >= 0) {
				v = v.substring(0, hash);
			}
			if (v.startsWith("@")) {
				v = v.substring(1);
			}
			slash = v.indexOf('/');
			if (slash >= 0) {
				v = v.substring(0, slash);
			}
			return v.trim().toLowerCase(Locale.ROOT);
		}
	}

	// ---------- YouTube Live ----------

	public Youtube youtube = new Youtube();

	/**
	 * YouTube Data API v3: чтение/отправка чата, платные сообщения и членство канала.
	 * OAuth-токены хранятся отдельно — в config/twitchcraft-youtube.json.
	 */
	public static class Youtube {
		/** OAuth Client ID приложения типа Desktop из Google Cloud Console. */
		public String clientId = "";
		/** Порт loopback-адреса для Desktop OAuth: http://localhost:ПОРТ (без пути). */
		public int callbackPort = 8640;
		/** Показывать обычный чат YouTube в Minecraft. */
		public boolean showChat = true;
		/** Префикс сообщений YouTube в чате Minecraft. */
		public String chatPrefix = "&c[YT]&r ";
		/** Выполнять общие чат-команды из YouTube Live. */
		public boolean chatCommands = true;
		/** Обрабатывать Super Chat, Super Stickers и Fan Funding как донаты. */
		public boolean paidMessages = true;
		/** Обрабатывать новые и продлённые платные членства и подарки участий во время эфира. */
		public boolean memberships = true;
		/** Отправлять автоответы зрителям в YouTube Live (нужен OAuth scope youtube.force-ssl). */
		public boolean replies = true;
		/** Писать все неизвестные и служебные сообщения API в logs/latest.log. */
		public boolean debugEvents = false;
		/** Сколько сообщений live chat запрашивать за один опрос (200…2000): больше — меньше запросов и расхода квоты. */
		public int pollMaxResults = 2000;
		/** Следить за зрителями эфира (videos.list: {youtube_viewers}, {youtube_live_time}, {youtube_title}). */
		public boolean trackViewers = true;
		/** Как часто обновлять данные эфира, секунд (не чаще раза в 15 с; 1 запрос = 1 единица квоты). */
		public int viewersIntervalSeconds = 60;
		/** Дневной бюджет квоты YouTube Data API в единицах (0 — не ограничивать; лимит нового проекта Google — 10 000). */
		public int quotaBudget = 9000;
		/** Останавливать опрос чата, когда бюджет квоты исчерпан: возобновится сам после сброса Google. */
		public boolean quotaGuard = true;
		/** Управление эфиром из игры: go live/testing/complete, title, ban, unban, delete (scope youtube.force-ssl). */
		public boolean control = true;
		/** Показывать в чате игры действия модераторов YouTube: баны, тайм-ауты, удаления и пометки спама. */
		public boolean showModeration = false;
		/** Тайм-аут зрителя по умолчанию для /twitch youtube ban <ник> (секунд; 0 — постоянный бан). */
		public int defaultTimeoutSeconds = 300;
	}

	// ---------- Цели ----------

	public GoalsSettings goalsSettings = new GoalsSettings();

	public static class GoalsSettings {
		/** Сбрасывать прогресс целей, если мод не запускался дольше стольких часов (0 — никогда). */
		public int resetAfterHours = 12;
		/** Сообщать в чат о прогрессе после каждого вклада (например, «Фолловеры: 7/10»). */
		public boolean announceProgress = true;
	}

	/**
	 * Накопительные цели. Тип (type) определяет, что считаем:
	 * follows — фолловы; subs — подписки, продления и подарки; gifts — только подарочные подписки;
	 * bits — битсы; raids — рейды; raidViewers — зрители из рейдов; rewards — активации наград;
	 * points — потраченные баллы; events — любые события.
	 */
	public List<Goal> goals = new ArrayList<>();

	public static class Goal {
		public boolean enabled = true;
		/** Название цели (уникальное, показывается в оверлее). */
		public String name = "";
		/** Что считаем — см. список типов выше. */
		public String type = "follows";
		/** Сколько нужно набрать. */
		public int target = 10;
		/** Повторять цель заново после достижения (каждые N). */
		public boolean repeat = true;
		/** Что происходит при достижении. Плейсхолдеры: {goal} {target} {times} {user} — последний внёсший вклад. */
		public Action action = new Action();

		public Goal() {
		}

		public Goal(String name, String type, int target, boolean repeat, Action action) {
			this.name = name;
			this.type = type;
			this.target = target;
			this.repeat = repeat;
			this.action = action;
		}

		public GoalType goalType() {
			return GoalType.parse(type);
		}
	}

	public enum GoalType {
		FOLLOWS("follows", "Фолловы"),
		SUBS("subs", "Подписки"),
		GIFTS("gifts", "Подарочные подписки"),
		BITS("bits", "Битсы"),
		RAIDS("raids", "Рейды"),
		RAID_VIEWERS("raidViewers", "Зрители из рейдов"),
		REWARDS("rewards", "Активации наград"),
		POINTS("points", "Потраченные баллы"),
		DONATIONS("donations", "Донаты (количество)"),
		DONATION_SUM("donationSum", "Сумма донатов"),
		EVENTS("events", "Любые события");

		public final String id;
		public final String title;

		GoalType(String id, String title) {
			this.id = id;
			this.title = title;
		}

		public static GoalType parse(String id) {
			if (id != null) {
				for (GoalType type : values()) {
					if (type.id.equalsIgnoreCase(id.trim()) || type.name().equalsIgnoreCase(id.trim())) {
						return type;
					}
				}
			}
			return FOLLOWS;
		}

		/** Сколько единиц прогресса даёт событие (0 — не считается). */
		public int contribution(TwitchEvent event) {
			if (event == null || event.synthetic()) {
				return 0;
			}
			int amount = Math.max(0, event.amount());
			return switch (this) {
				case FOLLOWS -> event.type() == TwitchEvent.Type.FOLLOW ? 1 : 0;
				case SUBS -> switch (event.type()) {
					case SUBSCRIBE, RESUB -> 1;
					case GIFT_SUB -> Math.max(1, amount);
					default -> 0;
				};
				case GIFTS -> event.type() == TwitchEvent.Type.GIFT_SUB ? Math.max(1, amount) : 0;
				case BITS -> event.type() == TwitchEvent.Type.CHEER ? amount : 0;
				case RAIDS -> event.type() == TwitchEvent.Type.RAID ? 1 : 0;
				case RAID_VIEWERS -> event.type() == TwitchEvent.Type.RAID ? amount : 0;
				case REWARDS -> event.type() == TwitchEvent.Type.REWARD ? 1 : 0;
				case POINTS -> event.type() == TwitchEvent.Type.REWARD ? amount : 0;
				case DONATIONS -> event.type() == TwitchEvent.Type.DONATION ? 1 : 0;
				case DONATION_SUM -> event.type() == TwitchEvent.Type.DONATION ? amount : 0;
				case EVENTS -> switch (event.type()) {
					case CHAT, CHAT_COMMAND, GOAL, FUND, GAME -> 0;
					default -> 1;
				};
			};
		}
	}

	// ---------- Сборы средств (боссбар) ----------

	public FundraiserSettings fundraiserSettings = new FundraiserSettings();

	public static class FundraiserSettings {
		/** Отступ полосы от верхнего края экрана в пикселях (ванильные боссбары начинаются с 12). */
		public int y = 12;
		/** Сколько секунд показывать справа от полосы последний вклад («+500 ₽ · Ник»); 0 — не показывать. */
		public int lastContributionSeconds = 8;
		/** Плавно заполнять полосу (как у настоящих боссов). */
		public boolean animate = true;
	}

	/**
	 * Сборы средств: полоса в стиле боссбара вверху экрана, которую заполняют донаты
	 * (и, по желанию, битсы, подписки, баллы канала). Прогресс хранится в config/twitchcraft-fundraisers.json
	 * и не сбрасывается между стримами, пока сбор не сбросить вручную.
	 */
	public List<Fundraiser> fundraisers = new ArrayList<>();

	public static class Fundraiser {
		/** Выключенный сбор не считает вклады и не показывается. */
		public boolean enabled = true;
		/** Показывать полосу на экране (можно спрятать, продолжая считать). */
		public boolean visible = true;
		/** Уникальное имя для команд (/twitch fund add <имя> ...). */
		public String name = "";
		/** Заголовок над полосой; пусто — используется имя. Поддерживает &-цвета. */
		public String title = "";
		/** Сколько нужно собрать, в основной валюте донатов (donations.currency). */
		public int target = 1000;
		/** Цвет полосы: pink, blue, red, green, yellow, purple, white. */
		public String color = "pink";
		/** Стиль полосы: progress (сплошная), notched_6, notched_10, notched_12, notched_20 (с делениями). */
		public String style = "notched_10";
		/**
		 * Текст над полосой. Плейсхолдеры: {title} {name} {current} {target} {left} {percent} {currency}
		 * {last} {last_amount} {donors}.
		 */
		public String format = DEFAULT_FUND_FORMAT;
		/** Засчитывать донаты (DonationAlerts / DonatePay) — сумма в основной валюте. */
		public boolean countDonations = true;
		/** Сколько единиц валюты даёт 1 битс (0 — битсы не считать). Например, 0.7 — 100 битс = 70 ₽. */
		public double bitsRate = 0;
		/** Сколько единиц валюты даёт одна подписка, продление или подарочная подписка (0 — не считать). */
		public double subValue = 0;
		/** Сколько единиц валюты даёт 1 балл канала при активации награды (0 — не считать). */
		public double pointsRate = 0;
		/** Через сколько секунд после закрытия сбора спрятать полосу (0 — оставить заполненной на экране). */
		public int hideWhenCompleteSeconds = 0;
		/** Что происходит, когда сбор закрыт. Плейсхолдеры: {goal} {target} {sum} {currency} {times} {user}. */
		public Action action = new Action();

		public Fundraiser() {
		}

		public Fundraiser(String name, String title, int target, Action action) {
			this.name = name;
			this.title = title;
			this.target = target;
			this.action = action;
		}

		/** Заголовок для показа: title или, если пусто, name. */
		public String displayTitle() {
			return title == null || title.isBlank() ? (name == null ? "" : name) : title;
		}

		public Fundraiser copy() {
			Fundraiser f = new Fundraiser(name, title, target, action == null ? new Action() : action.copy());
			f.enabled = enabled;
			f.visible = visible;
			f.color = color;
			f.style = style;
			f.format = format;
			f.countDonations = countDonations;
			f.bitsRate = bitsRate;
			f.subValue = subValue;
			f.pointsRate = pointsRate;
			f.hideWhenCompleteSeconds = hideWhenCompleteSeconds;
			return f;
		}
	}

	public static final String DEFAULT_FUND_FORMAT = "{title}: {current} / {target} {currency} ({percent}%)";
	public static final List<String> FUND_COLORS = List.of("pink", "blue", "red", "green", "yellow", "purple", "white");
	public static final List<String> FUND_STYLES = List.of("progress", "notched_6", "notched_10", "notched_12", "notched_20");

	// ---------- Чат-команды зрителей ----------

	/** Префикс чат-команд. */
	public String chatCommandPrefix = "!";

	/** Ключ — имя команды без префикса. У действия можно указать aliases, permission, cooldown, userCooldown. */
	public Map<String, Action> chatCommands = new LinkedHashMap<>();

	// ---------- Действия на события ----------

	public Action follow = new Action();
	public Action subscribe = new Action();
	public Action resub = new Action();
	public Action giftSub = new Action();
	public Action raid = new Action();

	/** Пороги по количеству. Ключ — минимальное значение; выбирается самый большой подходящий порог. */
	public Map<String, Action> cheer = new LinkedHashMap<>();
	public Map<String, Action> resubTiers = new LinkedHashMap<>();
	public Map<String, Action> giftSubTiers = new LinkedHashMap<>();
	public Map<String, Action> raidTiers = new LinkedHashMap<>();

	/**
	 * Действия по уровню подписки (ветвление по {tier}). Ключ — уровень: "1", "2", "3" или "prime"
	 * (регистр не важен; годятся и сырые значения Twitch "1000"/"2000"/"3000").
	 * subscribeByTier — новая подписка: действие уровня выполняется ВМЕСТО базового {@code subscribe}.
	 * resubByTier / giftSubByTier — ресаб и подарки: если ни один порог по количеству
	 * ({@code resubTiers} / {@code giftSubTiers}) не подошёл, берётся действие уровня, иначе базовое.
	 * Экран настроек: /twitch config → События Twitch → «Уровни».
	 */
	public Map<String, Action> subscribeByTier = new LinkedHashMap<>();
	public Map<String, Action> resubByTier = new LinkedHashMap<>();
	public Map<String, Action> giftSubByTier = new LinkedHashMap<>();

	/** Ключ — точное название награды за баллы канала (регистр не важен). "*" — для всех остальных наград. */
	public Map<String, Action> rewards = new LinkedHashMap<>();

	// ---------- События игры → чат (1.7.0) ----------

	/**
	 * Действия на события самой игры. Ключи: death (смерть), advancement (обычное достижение),
	 * advancementGoal (цель), advancementChallenge (испытание), boss (убит босс), dimension (смена измерения).
	 * Поле reply уходит сообщением в чаты Twitch, VK и YouTube; message/title/sound/commands работают как обычно.
	 * Плейсхолдеры: {cause} {deaths} {deaths_total} {advancement} {advancement_text} {advancement_kind}
	 * {boss} {killer} {dimension} {stream_time} {session_time} {viewers}.
	 */
	public Map<String, Action> gameEvents = new LinkedHashMap<>();

	public GameEventsSettings gameEventsSettings = new GameEventsSettings();

	public static class GameEventsSettings {
		/** Писать события игры в чат Twitch. */
		public boolean toTwitch = true;
		/** Писать события игры в чат VK Video Live. */
		public boolean toVk = true;
		/** Писать события игры в чат YouTube Live. */
		public boolean toYoutube = true;
		/** На сервере: объявлять и события других игроков (по умолчанию — только свои). */
		public boolean otherPlayers = false;
		/** Первые N секунд после входа в мир события не объявляются (загрузка, телепорт в точку возрождения). */
		public int quietSecondsAfterJoin = 5;
		/** Хранить счётчик смертей за всё время в config/twitchcraft-stats.json. */
		public boolean persistStats = true;
	}

	// ---------- Кастомные триггеры аддонов (1.9.0) ----------

	/**
	 * Действия из конфига, привязанные к кастомным триггерам аддонов (слоты {@code v0…v3}).
	 * Ключ — слот ("v0"…"v3", регистр не важен, годится и просто "0"…"3"). Когда условие
	 * триггера аддона срабатывает на событии, мод выполняет и собственные действия триггера,
	 * и это действие — со всеми возможностями (шанс, кулдауны, лут, повторы, ответ в чат).
	 * Дополнительно доступны плейсхолдеры {trigger} (имя триггера) и {slot} (например, "v2").
	 * Экран настроек: /twitch config → Триггеры аддонов.
	 */
	public Map<String, Action> addonTriggers = new LinkedHashMap<>();

	/**
	 * Нормализует имя слота кастомного триггера: "V1" / " 1 " → "v1".
	 *
	 * @return "v0"…"v3" или null, если это не слот (мусор или номер вне диапазона)
	 */
	public static String normalizeTriggerSlot(String slot) {
		if (slot == null) {
			return null;
		}
		String value = slot.trim().toLowerCase(Locale.ROOT);
		if (value.startsWith("v")) {
			value = value.substring(1);
		}
		try {
			int index = Integer.parseInt(value);
			if (index < 0 || index >= dev.dedworkshop.twitchcraft.api.AddonRegistry.MAX_CUSTOM_TRIGGERS) {
				return null;
			}
			return "v" + index;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Действие, привязанное к слоту кастомного триггера аддона; ключ кулдауна — "addonTrigger:v0…v3". */
	public Resolved findAddonTriggerAction(String slot) {
		String normalized = normalizeTriggerSlot(slot);
		if (normalized == null || addonTriggers == null) {
			return null;
		}
		for (Map.Entry<String, Action> entry : addonTriggers.entrySet()) {
			if (entry.getValue() != null && normalized.equals(normalizeTriggerSlot(entry.getKey()))) {
				return new Resolved(entry.getValue(), "addonTrigger:" + normalized);
			}
		}
		return null;
	}

	/**
	 * Нормализует уровень подписки: "1"/"2"/"3"/"prime" (регистр не важен; "2000" → "2",
	 * "Tier 3" → "3").
	 *
	 * @return "1", "2", "3" или "prime"; null, если это не уровень подписки
	 */
	public static String normalizeSubTier(String tier) {
		if (tier == null) {
			return null;
		}
		String value = tier.trim().toLowerCase(Locale.ROOT);
		if (value.startsWith("tier")) {
			value = value.substring(4).trim();
		}
		return switch (value) {
			case "1", "1000" -> "1";
			case "2", "2000" -> "2";
			case "3", "3000" -> "3";
			case "prime" -> "prime";
			default -> null;
		};
	}

	/** Действие из таблицы уровней подписки (ключи сравниваются нормализованными); null, если уровня нет. */
	private static Action byTierAction(Map<String, Action> byTier, String tier) {
		String normalized = normalizeSubTier(tier);
		if (normalized == null || byTier == null) {
			return null;
		}
		for (Map.Entry<String, Action> entry : byTier.entrySet()) {
			if (entry.getValue() != null && normalized.equals(normalizeSubTier(entry.getKey()))) {
				return entry.getValue();
			}
		}
		return null;
	}

	// ---------- Таймеры чата (1.7.0) ----------

	/** Периодические сообщения бота в чаты Twitch, VK и YouTube. */
	public List<ChatTimer> timers = new ArrayList<>();

	public static class ChatTimer {
		public boolean enabled = true;
		/** Имя таймера (для /twitch timers post <имя>). */
		public String name = "";
		/** Интервал в минутах. */
		public int intervalMinutes = 15;
		/** Писать, только если с прошлого раза в чатах было хотя бы столько сообщений (0 — писать всегда). */
		public int minChatMessages = 3;
		/** Текст (плейсхолдеры {session_*}, {deaths}, {stream_time}, {donation_prices_*}, {donation_currency}). */
		public String text = "";
		/** Куда писать. */
		public boolean twitch = true;
		public boolean vk = true;
		public boolean youtube = true;

		public ChatTimer() {
		}

		public ChatTimer(String name, int intervalMinutes, int minChatMessages, String text) {
			this.name = name;
			this.intervalMinutes = intervalMinutes;
			this.minChatMessages = minChatMessages;
			this.text = text;
		}

		public ChatTimer with(java.util.function.Consumer<ChatTimer> setup) {
			setup.accept(this);
			return this;
		}

		public ChatTimer copy() {
			return GSON.fromJson(GSON.toJson(this), ChatTimer.class);
		}
	}

	// ---------- Клипы и метки Twitch (1.7.0) ----------

	public Clips clips = new Clips();

	public static class Clips {
		/** Смерть персонажа: метка стрима / клип. */
		public boolean markerOnDeath = true;
		public boolean clipOnDeath = true;
		/** Донат от этой суммы (в валюте донатов) — метка / клип. 0 — выключено. */
		public int donationFrom = 50;
		public boolean markerOnDonation = true;
		public boolean clipOnDonation = true;
		/** Победа над боссом (дракон, иссушитель, варден, древний страж). */
		public boolean markerOnBoss = true;
		public boolean clipOnBoss = true;
		/** Публиковать ссылку на готовый клип в чаты Twitch, VK и YouTube. */
		public boolean postClipToTwitch = true;
		public boolean postClipToVk = true;
		public boolean postClipToYoutube = true;
		/** Текст сообщения со ссылкой ({clip_url}, {why}). */
		public String clipChatText = "🎬 Клип: {clip_url}";
		/** Не делать клипы чаще, чем раз в N секунд (Twitch сам ограничивает частоту). */
		public int clipCooldownSeconds = 60;
		/** Не ставить метки чаще, чем раз в N секунд. */
		public int markerCooldownSeconds = 10;
	}

	/** Ключи событий игры (порядок — для экрана настроек). */
	public static final List<String> GAME_EVENT_KEYS = List.of("death", "advancement", "advancementGoal", "advancementChallenge", "boss", "dimension");

	/** Действие события игры: для целей/испытаний — своё, иначе общее "advancement". */
	public Resolved findGameAction(String kind) {
		if (kind == null || gameEvents == null) {
			return null;
		}
		Action action = gameEvents.get(kind);
		String key = kind;
		if ((action == null || action.isEmpty()) && kind.startsWith("advancement")) {
			action = gameEvents.get("advancement");
			key = "advancement";
		}
		return action == null ? null : new Resolved(action, "game:" + key);
	}

	/** Таймер по имени (регистр не важен), иначе null. */
	public ChatTimer findTimer(String name) {
		if (name == null || timers == null) {
			return null;
		}
		for (ChatTimer timer : timers) {
			if (timer != null && timer.name != null && timer.name.equalsIgnoreCase(name.trim())) {
				return timer;
			}
		}
		return null;
	}

	// ---------- Служебное (не сохраняется) ----------

	/** Предупреждения, найденные при загрузке файла (опечатки в ключах и т.п.). */
	public transient List<String> warnings = new ArrayList<>();
	/** Текст ошибки, если файл не удалось прочитать и используются настройки по умолчанию. */
	public transient String loadError = null;

	// ---------- Класс действия ----------

	public static class Action {
		/** Включено ли действие. */
		public boolean enabled = true;

		/** Название (для записей таблицы лута — подставляется в {loot}). */
		public String name = "";

		// Что показать
		public String message = "";
		public String title = "";
		public String subtitle = "";
		public String actionbar = "";
		public String toast = "";
		public String toastText = "";

		// Звук (клиентский, слышно только тебе): id звука, громкость, высота
		public String sound = "";
		public float volume = 1.0f;
		public float pitch = 1.0f;

		// Команды. "delay N" — пауза N тиков.
		public List<String> commands = new ArrayList<>();

		/** Выполнить только одну случайную команду из списка вместо всех. */
		public boolean randomOne = false;

		/**
		 * Таблица лута: список действий, из которых случайно выбирается ОДНО (с учётом weight)
		 * и выполняется после основного. Название выбранного попадает в {loot}.
		 */
		public List<Action> loot = new ArrayList<>();
		/** Вес записи в таблице лута (чем больше, тем чаще выпадает). */
		public int weight = 1;

		/** Шанс срабатывания, 0–100. */
		public int chance = 100;
		/** Сообщение, если шанс не выпал. */
		public String failMessage = "";

		/** Сколько раз повторить команды: число или плейсхолдер, например "{amount}". */
		public String repeat = "";
		/** Делитель для repeat: при "repeat": "{amount}", "repeatPer": 100 — один повтор на каждые 100. */
		public int repeatPer = 1;
		/** Максимум повторов. */
		public int maxRepeat = 10;
		/** Пауза между повторами в тиках. */
		public int repeatDelay = 5;

		/** Общий кулдаун действия в секундах (0 — без кулдауна). */
		public int cooldown = 0;
		/** Кулдаун на одного зрителя в секундах. */
		public int userCooldown = 0;

		/** Ответ в чат Twitch после выполнения (нужно право user:write:chat). */
		public String reply = "";

		// Только для чат-команд
		/** Кому доступна: everyone, subscriber, vip, moderator, broadcaster. */
		public String permission = "everyone";
		/** Другие имена команды. */
		public List<String> aliases = new ArrayList<>();

		// Только для наград (используется /twitch rewards sync)
		/** Стоимость в баллах при создании награды на Twitch. */
		public int cost = 0;
		/** Описание награды для зрителей. */
		public String prompt = "";
		/** Требовать от зрителя ввести текст (попадёт в {message}). */
		public boolean input = false;
		/** Цвет плашки награды, например "#9146FF". */
		public String color = "";

		/**
		 * Случайное событие из ценника донатов: "bad" — одна из ☠ записей, "good" — одна из ★, "any" — любая из них
		 * (равновероятно, порог суммы не важен). Выпавшая запись даёт заголовок, звук и команды, а своё действие —
		 * сообщение/ответ с плейсхолдерами {picked} (название), {picked_full} (с значком) и {picked_text} (описание).
		 */
		public String pool = "";

		/**
		 * Сверхсобытие ценника донатов (от 5500): не попадает в обычные награды «Пакость» / «Подарок»,
		 * а выбирается наградами «Катастрофа» (pool = xbad) и «Чудо» (pool = xgood).
		 */
		public boolean extreme = false;

		public Action() {
		}

		public Action(String message, String title, String subtitle, String... commands) {
			this.message = message;
			this.title = title;
			this.subtitle = subtitle;
			this.commands = new ArrayList<>(List.of(commands));
		}

		/** Удобная настройка полей в одну строку: action.with(a -> a.sound = "..."). */
		public Action with(java.util.function.Consumer<Action> setup) {
			setup.accept(this);
			return this;
		}

		public boolean isEmpty() {
			return isBlank(message) && isBlank(title) && isBlank(subtitle) && isBlank(actionbar) && isBlank(toast)
					&& isBlank(sound) && isBlank(reply) && (commands == null || commands.isEmpty())
					&& (loot == null || loot.isEmpty());
		}

		/** Запись таблицы лута: название, вес, команды. */
		public static Action lootEntry(String name, int weight, String... commands) {
			Action entry = new Action();
			entry.name = name;
			entry.weight = weight;
			entry.commands = new ArrayList<>(List.of(commands));
			return entry;
		}

		/** Выбирает случайную запись таблицы лута с учётом весов (null, если таблица пуста). */
		public Action pickLoot(java.util.random.RandomGenerator random) {
			if (loot == null || loot.isEmpty()) {
				return null;
			}
			int total = 0;
			for (Action entry : loot) {
				if (entry != null && entry.enabled) {
					total += Math.max(0, entry.weight);
				}
			}
			if (total <= 0) {
				return null;
			}
			int roll = random.nextInt(total);
			for (Action entry : loot) {
				if (entry == null || !entry.enabled) {
					continue;
				}
				roll -= Math.max(0, entry.weight);
				if (roll < 0) {
					return entry;
				}
			}
			return null;
		}

		/** Глубокая копия (для редактора: правим копию, при отмене оригинал не трогаем). */
		public Action copy() {
			return GSON.fromJson(GSON.toJson(this), Action.class);
		}

		public TwitchEvent.Permission permissionLevel() {
			return TwitchEvent.Permission.parse(permission);
		}
	}

	/** Найденное действие и ключ для кулдаунов. */
	public record Resolved(Action action, String key) {
	}

	// ---------- Поиск действия для события ----------

	/** Возвращает действие для события или null, если ничего не настроено. */
	public Resolved findAction(TwitchEvent event) {
		Resolved resolved = switch (event.type()) {
			case FOLLOW -> single(follow, "follow");
			case SUBSCRIBE -> levelOrBase(subscribeByTier, event.tier(), subscribe, "subscribe");
			case RESUB -> thresholdsThenLevel(resubTiers, resubByTier, event, "resub", resub);
			case GIFT_SUB -> thresholdsThenLevel(giftSubTiers, giftSubByTier, event, "giftSub", giftSub);
			case RAID -> tiered(raidTiers, event.amount(), "raid", raid);
			case CHEER -> tiered(cheer, event.amount(), "cheer", null);
			case REWARD -> findRewardAction(event.reward());
			case CHAT_COMMAND -> findChatCommand(event.command());
			case GOAL -> findGoalAction(event.reward());
			case FUND -> findFundAction(event.reward());
			case DONATION -> findDonationAction(event);
			case GAME -> findGameAction(event.reward());
			case CHAT -> null;
		};
		if (resolved == null || resolved.action() == null || resolved.action().isEmpty()) {
			return null;
		}
		return resolved;
	}

	/** Донат: отдельная таблица сервиса, если она не пуста, иначе общая donationTiers. */
	private Resolved findDonationAction(TwitchEvent event) {
		Map<String, Action> own = switch (event.source()) {
			case TwitchEvent.SOURCE_DONATION_ALERTS -> donationAlertsTiers;
			case TwitchEvent.SOURCE_DONATE_PAY -> donatePayTiers;
			default -> null;
		};
		if (own != null && !own.isEmpty()) {
			return tiered(own, event.amount(), "donation:" + event.source(), null);
		}
		return tiered(donationTiers, event.amount(), "donation", null);
	}

	/** Таблица порогов, которая реально применяется к донатам источника (для экрана настроек и статуса). */
	public Map<String, Action> effectiveDonationTiers(String source) {
		Map<String, Action> own = switch (source == null ? "" : source) {
			case TwitchEvent.SOURCE_DONATION_ALERTS -> donationAlertsTiers;
			case TwitchEvent.SOURCE_DONATE_PAY -> donatePayTiers;
			default -> null;
		};
		return own != null && !own.isEmpty() ? own : donationTiers;
	}

	private static Resolved single(Action action, String key) {
		return action == null ? null : new Resolved(action, key);
	}

	/** Действие уровня подписки (1/2/3/prime); если для уровня ничего нет — базовое действие. */
	private Resolved levelOrBase(Map<String, Action> byTier, String tier, Action base, String key) {
		Action action = byTierAction(byTier, tier);
		if (action != null) {
			return new Resolved(action, key + ":tier:" + normalizeSubTier(tier));
		}
		return single(base, key);
	}

	/** Порог по количеству; если ни один не подошёл — действие уровня подписки, затем базовое. */
	private Resolved thresholdsThenLevel(Map<String, Action> thresholds, Map<String, Action> byTier, TwitchEvent event,
			String key, Action base) {
		Resolved threshold = tiered(thresholds, event.amount(), key, null);
		if (threshold != null) {
			return threshold;
		}
		return levelOrBase(byTier, event.tier(), base, key);
	}

	/** Порог: самый большой ключ, не превышающий value. Если порогов нет — запасное действие. */
	private static Resolved tiered(Map<String, Action> tiers, int value, String key, Action fallback) {
		Action best = null;
		int bestThreshold = -1;
		if (tiers != null) {
			for (Map.Entry<String, Action> entry : tiers.entrySet()) {
				int threshold;
				try {
					threshold = Integer.parseInt(entry.getKey().trim());
				} catch (NumberFormatException e) {
					continue;
				}
				if (threshold <= value && threshold > bestThreshold && entry.getValue() != null) {
					bestThreshold = threshold;
					best = entry.getValue();
				}
			}
		}
		if (best != null) {
			return new Resolved(best, key + ":" + bestThreshold);
		}
		return single(fallback, key);
	}

	private Resolved findRewardAction(String title) {
		if (rewards == null || title == null) {
			return null;
		}
		String wanted = title.trim();
		for (Map.Entry<String, Action> entry : rewards.entrySet()) {
			if (entry.getKey().trim().equalsIgnoreCase(wanted)) {
				return new Resolved(entry.getValue(), "reward:" + entry.getKey().toLowerCase(Locale.ROOT));
			}
		}
		Action any = rewards.get("*");
		return any == null ? null : new Resolved(any, "reward:" + wanted.toLowerCase(Locale.ROOT));
	}

	/** Цель по названию (регистр не важен). */
	public Goal findGoal(String name) {
		if (goals == null || name == null) {
			return null;
		}
		for (Goal goal : goals) {
			if (goal != null && goal.name != null && goal.name.trim().equalsIgnoreCase(name.trim())) {
				return goal;
			}
		}
		return null;
	}

	private Resolved findGoalAction(String name) {
		Goal goal = findGoal(name);
		if (goal == null || goal.action == null) {
			return null;
		}
		return new Resolved(goal.action, "goal:" + goal.name.trim().toLowerCase(Locale.ROOT));
	}

	/** Ищет сбор по имени (регистр не важен). */
	public Fundraiser findFundraiser(String name) {
		if (fundraisers == null || name == null) {
			return null;
		}
		for (Fundraiser fund : fundraisers) {
			if (fund != null && fund.name != null && fund.name.trim().equalsIgnoreCase(name.trim())) {
				return fund;
			}
		}
		return null;
	}

	private Resolved findFundAction(String name) {
		Fundraiser fund = findFundraiser(name);
		if (fund == null || fund.action == null) {
			return null;
		}
		return new Resolved(fund.action, "fund:" + fund.name.trim().toLowerCase(Locale.ROOT));
	}

	// ---------- Модули ----------

	public boolean isEnabled(Module module) {
		if (module == null) {
			return true;
		}
		Boolean value = modules == null ? null : modules.get(module.id);
		return value == null || value;
	}

	public void setEnabled(Module module, boolean enabled) {
		if (modules == null) {
			modules = new LinkedHashMap<>();
		}
		modules.put(module.id, enabled);
	}

	/** Ищет чат-команду по имени или алиасу (без префикса, регистр не важен). */
	public Resolved findChatCommand(String name) {
		if (chatCommands == null || name == null || name.isBlank()) {
			return null;
		}
		String wanted = name.trim().toLowerCase(Locale.ROOT);
		for (Map.Entry<String, Action> entry : chatCommands.entrySet()) {
			String key = entry.getKey().trim().toLowerCase(Locale.ROOT);
			if (key.startsWith(chatCommandPrefix)) {
				key = key.substring(chatCommandPrefix.length());
			}
			Action action = entry.getValue();
			if (action == null) {
				continue;
			}
			boolean match = key.equals(wanted);
			if (!match && action.aliases != null) {
				for (String alias : action.aliases) {
					String a = alias.trim().toLowerCase(Locale.ROOT);
					if (a.startsWith(chatCommandPrefix)) {
						a = a.substring(chatCommandPrefix.length());
					}
					if (a.equals(wanted)) {
						match = true;
						break;
					}
				}
			}
			if (match) {
				return new Resolved(action, "chat:" + key);
			}
		}
		return null;
	}

	public boolean isIgnoredUser(String login) {
		if (ignoredUsers == null || login == null) {
			return false;
		}
		for (String ignored : ignoredUsers) {
			if (ignored != null && ignored.trim().equalsIgnoreCase(login.trim())) {
				return true;
			}
		}
		return false;
	}

	public boolean isCommandBlocked(String command) {
		if (blockedCommands == null || command == null) {
			return false;
		}
		String trimmed = command.trim();
		if (trimmed.startsWith("/")) {
			trimmed = trimmed.substring(1);
		}
		String[] words = trimmed.split("\\s+");
		if (isRootBlocked(words[0])) {
			return true;
		}
		// Вложенные команды: "execute as @a run op @s", "return run stop", "execute ... run execute ... run ban ..."
		// Проверяем всё, что идёт после каждого слова "run" — иначе чёрный список обходился бы через execute.
		for (int i = 1; i < words.length - 1; i++) {
			if (words[i].equalsIgnoreCase("run") && isRootBlocked(words[i + 1])) {
				return true;
			}
		}
		return false;
	}

	private boolean isRootBlocked(String word) {
		String root = word.toLowerCase(Locale.ROOT);
		if (root.startsWith("/")) {
			root = root.substring(1);
		}
		if (root.startsWith("minecraft:")) {
			root = root.substring("minecraft:".length());
		}
		for (String blocked : blockedCommands) {
			if (blocked != null && blocked.trim().equalsIgnoreCase(root)) {
				return true;
			}
		}
		return false;
	}

	/** Нужна ли подписка на чат Twitch. */
	public boolean needsChat() {
		return isEnabled(Module.TWITCH_CHAT)
				|| (isEnabled(Module.CHAT_COMMANDS) && chatCommands != null && !chatCommands.isEmpty());
	}

	// ---------- Загрузка / сохранение ----------

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	public static ModConfig load() {
		Path path = path();
		if (!Files.exists(path)) {
			ModConfig config = createDefault();
			config.save();
			TwitchCraftClient.LOGGER.info("Создан конфиг по умолчанию: {}", path);
			return config;
		}
		String json;
		try {
			json = Files.readString(path, StandardCharsets.UTF_8);
		} catch (IOException e) {
			ModConfig config = createDefault();
			config.loadError = "не удалось прочитать файл: " + e.getMessage();
			return config;
		}
		try {
			ModConfig config = GSON.fromJson(json, ModConfig.class);
			if (config == null) {
				config = createDefault();
				config.loadError = "файл пустой";
				return config;
			}
			config.normalize();
			config.warnings = findWarnings(json);
			int oldVersion = config.configVersion;
			if (config.upgradeFrom(json)) {
				Path backup = dev.dedworkshop.twitchcraft.util.SafeFiles.backupCopy(path,
						json.contains("\"configVersion\"") ? ".bak-v" + oldVersion : ".bak-old");
				config.save();
				TwitchCraftClient.LOGGER.info("Конфиг обновлён до версии {} — добавлены новые настройки и примеры{}", config.configVersion,
						backup == null ? "" : " (копия старого файла: " + backup.getFileName() + ")");
			}
			TwitchCraftClient.LOGGER.info("Конфиг загружен: {} (предупреждений: {})", path, config.warnings.size());
			return config;
		} catch (Exception e) {
			TwitchCraftClient.LOGGER.error("Ошибка чтения конфига {} — используются настройки по умолчанию", path, e);
			ModConfig config = createDefault();
			config.loadError = humanJsonError(e);
			// Копия сломанного файла: если мод потом сохранит настройки по умолчанию, правки пользователя не пропадут
			Path backup = dev.dedworkshop.twitchcraft.util.SafeFiles.backupBroken(path);
			if (backup != null) {
				config.loadError += " (копия файла: " + backup.getFileName() + ")";
			}
			return config;
		}
	}

	/**
	 * Миграция со старого формата: если в файле нет новых разделов — добавляем примеры из настроек по умолчанию.
	 *
	 * @return true, если файл нужно пересохранить
	 */
	public boolean upgradeFrom(String json) {
		Set<String> present;
		JsonObject rootObject;
		try {
			JsonElement root = JsonParser.parseString(json);
			if (!root.isJsonObject()) {
				return false;
			}
			rootObject = root.getAsJsonObject();
			present = rootObject.keySet();
		} catch (Exception e) {
			return false;
		}
		boolean changed = false;
		ModConfig defaults = createDefault();
		int fromVersion = present.contains("configVersion") ? configVersion : 11; // версия файла до миграций (ниже configVersion перезаписывается)
		boolean fromV2 = !present.contains("configVersion") || configVersion < 3;
		// v2 → v3: флаги enabled переехали в modules
		if (!present.contains("modules")) {
			if (oldEnabledFlag(rootObject, "twitchChat") == Boolean.FALSE) setEnabled(Module.TWITCH_CHAT, false);
			if (oldEnabledFlag(rootObject, "chatReplies") == Boolean.FALSE) setEnabled(Module.CHAT_REPLIES, false);
			if (oldEnabledFlag(rootObject, "overlay") == Boolean.FALSE) setEnabled(Module.OVERLAY, false);
			changed = true;
		}
		if (!present.contains("goals")) {
			goals = defaults.goals;
			changed = true;
		}
		if (!present.contains("goalsSettings")) {
			goalsSettings = defaults.goalsSettings;
			changed = true;
		}
		// v2 → v3: подарки за фоллов/саб/рейд — добавляем таблицы лута, если их ещё нет
		if (fromV2 && !hasLoot(rootObject, "follow") && follow != null && (follow.loot == null || follow.loot.isEmpty())) {
			follow.loot = defaults.follow.loot;
			if (follow.message != null && !follow.message.contains("{loot}")) {
				follow.message = follow.message + " &7Подарок: &6{loot}";
			}
			changed = true;
		}
		if (fromV2 && !hasLoot(rootObject, "subscribe") && subscribe != null && (subscribe.loot == null || subscribe.loot.isEmpty())) {
			subscribe.loot = defaults.subscribe.loot;
			changed = true;
		}
		if (fromV2 && !hasLoot(rootObject, "raid") && raid != null && (raid.loot == null || raid.loot.isEmpty())) {
			raid.loot = defaults.raid.loot;
			if (raid.message != null && !raid.message.contains("{loot}")) {
				raid.message = raid.message + " &7Бонус: &6{loot}";
			}
			changed = true;
		}
		if (!present.contains("chatCommands")) {
			chatCommands = defaults.chatCommands;
			changed = true;
		}
		if (!present.contains("resubTiers")) {
			resubTiers = defaults.resubTiers;
			changed = true;
		}
		if (!present.contains("giftSubTiers")) {
			giftSubTiers = defaults.giftSubTiers;
			changed = true;
		}
		if (!present.contains("raidTiers")) {
			raidTiers = defaults.raidTiers;
			changed = true;
		}
		// v3 → v4: донаты (DonationAlerts / DonatePay)
		if (!present.contains("donations")) {
			donations = defaults.donations;
			changed = true;
		}
		if (!present.contains("donationTiers")) {
			donationTiers = defaults.donationTiers;
			changed = true;
		}
		if (configVersion < 4 && goals != null && goals.stream().noneMatch(g -> g.goalType() == GoalType.DONATION_SUM)) {
			for (Goal goal : defaults.goals) {
				if (goal.goalType() == GoalType.DONATION_SUM && findGoal(goal.name) == null) {
					goals.add(goal);
					changed = true;
				}
			}
		}
		// v4 → v5: сборы средств (боссбар)
		if (!present.contains("fundraisers")) {
			fundraisers = defaults.fundraisers;
			changed = true;
		}
		if (!present.contains("fundraiserSettings")) {
			fundraiserSettings = defaults.fundraiserSettings;
			changed = true;
		}
		if (configVersion < 5 && chatCommands != null && findChatCommand("fund") == null && findChatCommand("сбор") == null) {
			Action fundCommand = defaults.chatCommands.get("fund");
			if (fundCommand != null) {
				chatCommands.put("fund", fundCommand);
				changed = true;
			}
		}
		// v5 → v6: ценник донатов 25 ☠ + 25 ★. Старый ценник по умолчанию (10 записей), если его не трогали, заменяем;
		// свою таблицу пользователя не меняем — новый ценник можно загрузить командой /twitch donations preset.
		if (configVersion < 6 && DonationPresets.isLegacyDefault(donationTiers)) {
			donationTiers = DonationPresets.defaults();
			changed = true;
		}
		if (configVersion < 6 && chatCommands != null) {
			for (String name : List.of("ценник", "плохое", "хорошее")) {
				if (findChatCommand(name) == null && defaults.chatCommands.get(name) != null) {
					chatCommands.put(name, defaults.chatCommands.get(name));
					changed = true;
				}
			}
		}
		// v6 → v7: награды за баллы — две случайные («Пакость» ☠ и «Подарок» ★ из ценника донатов) вместо 12 примеров.
		// Примеры заменяем, только если их не трогали; свои награды пользователя не меняем — лишь добавляем две новые.
		if (configVersion < 7 && rewards != null) {
			if (isLegacyDefaultRewards(rewards)) {
				rewards = defaults.rewards;
				changed = true;
			} else {
				for (Map.Entry<String, Action> entry : defaults.rewards.entrySet()) {
					if (entry.getKey().equals("*") || findReward(entry.getKey()) != null) {
						continue;
					}
					rewards.put(entry.getKey(), entry.getValue());
					changed = true;
				}
			}
		}
		if (!present.contains("vk")) {
			vk = defaults.vk;
			changed = true;
		}
		if (!present.contains("youtube")) {
			youtube = defaults.youtube;
			changed = true;
		}
		// v7 → v8: события игры → чат, таймеры чата, клипы и метки; чат-команды !смерти и !время;
		// порт входа VK по умолчанию 8638 (как в инструкции), если стоял старый 8632.
		if (!present.contains("gameEvents")) {
			gameEvents = defaults.gameEvents;
			changed = true;
		}
		if (!present.contains("gameEventsSettings")) {
			gameEventsSettings = defaults.gameEventsSettings;
			changed = true;
		}
		if (!present.contains("timers")) {
			timers = defaults.timers;
			changed = true;
		}
		if (!present.contains("clips")) {
			clips = defaults.clips;
			changed = true;
		}
		if (configVersion < 8 && chatCommands != null) {
			for (String name : List.of("смерти", "время")) {
				if (findChatCommand(name) == null && defaults.chatCommands.get(name) != null) {
					chatCommands.put(name, defaults.chatCommands.get(name));
					changed = true;
				}
			}
		}
		if (configVersion < 8 && vk != null && vk.callbackPort == 8632) {
			vk.callbackPort = 8638;
			changed = true;
		}
		if (!present.contains("configVersion") || configVersion < 9) {
			configVersion = 9;
			changed = true;
		}
		// v9 → v10: YouTube Live — учёт квоты Data API, зрители эфира, управление трансляцией и модерация
		if (!present.contains("configVersion") || configVersion < 10) {
			configVersion = 10;
			changed = true;
		}
		// v10 → v11: таблички чата в воздухе
		if (!present.contains("chatSigns")) {
			chatSigns = defaults.chatSigns;
			changed = true;
		}
		if (!present.contains("configVersion") || configVersion < 11) {
			configVersion = 11;
			changed = true;
		}
		// v12 → v13: сверхсобытия переехали с 6000–20500 ₽ на 5500–12750 ₽ (шаг 250), «Катастрофа» и «Чудо» — 500 баллов.
		// Непринятые пользователем записи старого ценника и цену 1000 заменяем; изменённые записи и свои награды не трогаем.
		if (fromVersion == 12) {
			if (donationTiers != null) {
				for (Map.Entry<String, String> old : DonationPresets.legacyV12Extremes().entrySet()) {
					Action current = donationTiers.get(old.getKey());
					if (current != null && current.extreme && old.getValue().equals(current.name)) {
						donationTiers.remove(old.getKey());
						changed = true;
					}
				}
			}
			if (rewards != null) {
				for (String name : List.of(DonationPresets.REWARD_MAX_BAD, DonationPresets.REWARD_MAX_GOOD)) {
					Action reward = rewards.get(name);
					if (reward != null && reward.cost == 1000) {
						reward.cost = DonationPresets.REWARD_MAX_COST;
						changed = true;
					}
				}
			}
		}
		// v11 → v13: сверхсобытия ценника донатов и награды «Катастрофа» / «Чудо».
		// Добавляем только то, чего в конфиге ещё нет: свои записи и награды пользователя не трогаем.
		if (fromVersion == 11 || fromVersion == 12) {
			if (donationTiers != null) {
				for (Map.Entry<String, Action> entry : defaults.donationTiers.entrySet()) {
					if (entry.getValue().extreme && !donationTiers.containsKey(entry.getKey())) {
						donationTiers.put(entry.getKey(), entry.getValue());
						changed = true;
					}
				}
			}
			if (fromVersion == 11 && rewards != null) {
				for (String name : List.of(DonationPresets.REWARD_MAX_BAD, DonationPresets.REWARD_MAX_GOOD)) {
					if (findReward(name) == null && defaults.rewards.get(name) != null) {
						rewards.put(name, defaults.rewards.get(name));
						changed = true;
					}
				}
			}
		}
		if (!present.contains("configVersion") || configVersion < 13) {
			configVersion = 13;
			changed = true;
		}
		return changed;
	}

	/** Названия наград-примеров из конфигов до 1.6.0 (configVersion ≤ 6). */
	static final List<String> LEGACY_REWARDS = List.of("Зомби", "Крипер", "Молния", "Лечение", "Голод", "Прыгучесть", "Ночь",
			"Телепорт", "Алмаз", "Сюрприз", "Лотерея", "Сообщение", "*");

	/** Таблица наград — ровно старые примеры (их не редактировали и не дополняли). */
	public static boolean isLegacyDefaultRewards(Map<String, Action> map) {
		if (map == null || map.size() != LEGACY_REWARDS.size()) {
			return false;
		}
		for (String title : LEGACY_REWARDS) {
			if (!map.containsKey(title)) {
				return false;
			}
		}
		return true;
	}

	/** Награда по названию без учёта регистра (null, если нет). */
	public Action findReward(String title) {
		if (rewards == null || title == null) {
			return null;
		}
		for (Map.Entry<String, Action> entry : rewards.entrySet()) {
			if (entry.getKey().trim().equalsIgnoreCase(title.trim())) {
				return entry.getValue();
			}
		}
		return null;
	}

	private static Boolean oldEnabledFlag(JsonObject root, String section) {
		JsonElement element = root.get(section);
		if (element == null || !element.isJsonObject()) {
			return null;
		}
		JsonElement enabled = element.getAsJsonObject().get("enabled");
		if (enabled == null || !enabled.isJsonPrimitive() || !enabled.getAsJsonPrimitive().isBoolean()) {
			return null;
		}
		return enabled.getAsBoolean();
	}

	private static boolean hasLoot(JsonObject root, String action) {
		JsonElement element = root.get(action);
		return element != null && element.isJsonObject() && element.getAsJsonObject().has("loot");
	}

	/** Заменяет null на пустые значения, чтобы остальной код не проверял каждое поле. */
	public void normalize() {
		if (modules == null) modules = new LinkedHashMap<>();
		Map<String, Boolean> ordered = new LinkedHashMap<>();
		for (Module module : Module.values()) {
			Boolean value = modules.get(module.id);
			ordered.put(module.id, value == null || value);
		}
		modules = ordered;
		if (goalsSettings == null) goalsSettings = new GoalsSettings();
		if (goals == null) goals = new ArrayList<>();
		goals.removeIf(java.util.Objects::isNull);
		for (Goal goal : goals) {
			if (goal.name == null) goal.name = "";
			if (goal.type == null) goal.type = "follows";
			if (goal.action == null) goal.action = new Action();
			goal.target = Math.max(1, goal.target);
			normalizeAction(goal.action);
		}
		if (fundraiserSettings == null) fundraiserSettings = new FundraiserSettings();
		fundraiserSettings.y = Math.max(0, Math.min(fundraiserSettings.y, 400));
		fundraiserSettings.lastContributionSeconds = Math.max(0, Math.min(fundraiserSettings.lastContributionSeconds, 600));
		if (fundraisers == null) fundraisers = new ArrayList<>();
		fundraisers.removeIf(java.util.Objects::isNull);
		for (Fundraiser fund : fundraisers) {
			normalizeFundraiser(fund);
		}
		if (blockedCommands == null) blockedCommands = new ArrayList<>();
		if (ignoredUsers == null) ignoredUsers = new ArrayList<>();
		if (twitchChat == null) twitchChat = new TwitchChat();
		if (chatReplies == null) chatReplies = new ChatReplies();
		if (rewardsSettings == null) rewardsSettings = new RewardsSettings();
		if (overlay == null) overlay = new Overlay();
		if (chatSigns == null) chatSigns = new ChatSignSettings();
		chatSigns.seconds = Math.max(1, Math.min(120, chatSigns.seconds));
		chatSigns.maxVisible = Math.max(1, Math.min(12, chatSigns.maxVisible));
		chatSigns.distance = Math.max(2, Math.min(30, chatSigns.distance));
		chatSigns.scale = Math.max(25, Math.min(300, chatSigns.scale));
		if (chatCommandPrefix == null || chatCommandPrefix.isEmpty()) chatCommandPrefix = "!";
		if (chatCommands == null) chatCommands = new LinkedHashMap<>();
		if (cheer == null) cheer = new LinkedHashMap<>();
		if (resubTiers == null) resubTiers = new LinkedHashMap<>();
		if (giftSubTiers == null) giftSubTiers = new LinkedHashMap<>();
		if (raidTiers == null) raidTiers = new LinkedHashMap<>();
		if (subscribeByTier == null) subscribeByTier = new LinkedHashMap<>();
		if (resubByTier == null) resubByTier = new LinkedHashMap<>();
		if (giftSubByTier == null) giftSubByTier = new LinkedHashMap<>();
		if (rewards == null) rewards = new LinkedHashMap<>();
		if (donationTiers == null) donationTiers = new LinkedHashMap<>();
		if (donationAlertsTiers == null) donationAlertsTiers = new LinkedHashMap<>();
		if (donatePayTiers == null) donatePayTiers = new LinkedHashMap<>();
		if (donations == null) donations = new Donations();
		if (donations.currency == null || donations.currency.isBlank()) donations.currency = "RUB";
		donations.currency = donations.currency.trim().toUpperCase(Locale.ROOT);
		if (donations.donationAlertsClientId == null) donations.donationAlertsClientId = "";
		donations.donatePayPollSeconds = Math.max(20, donations.donatePayPollSeconds);
		donations.minAmount = Math.max(0, donations.minAmount);
		if (donations.callbackPort < 1024 || donations.callbackPort > 65535) donations.callbackPort = 8631;
		if (vk == null) vk = new Vk();
		if (vk.channelUrl == null) vk.channelUrl = "";
		if (vk.clientId == null) vk.clientId = "";
		vk.clientId = vk.clientId.trim();
		if (vk.chatPrefix == null) vk.chatPrefix = "";
		if (vk.callbackPort < 1024 || vk.callbackPort > 65535) vk.callbackPort = 8638;
		if (vk.callbackPort == donations.callbackPort) vk.callbackPort = donations.callbackPort == 8638 ? 8639 : 8638;
		if (youtube == null) youtube = new Youtube();
		if (youtube.clientId == null) youtube.clientId = "";
		youtube.clientId = youtube.clientId.trim();
		if (youtube.chatPrefix == null) youtube.chatPrefix = "";
		if (youtube.callbackPort < 1024 || youtube.callbackPort > 65535) youtube.callbackPort = 8640;
		if (youtube.callbackPort == donations.callbackPort || youtube.callbackPort == vk.callbackPort) {
			int port = 8640;
			while (port == donations.callbackPort || port == vk.callbackPort) port++;
			youtube.callbackPort = port;
		}
		youtube.pollMaxResults = Math.max(200, Math.min(2000, youtube.pollMaxResults));
		youtube.viewersIntervalSeconds = Math.max(15, Math.min(3600, youtube.viewersIntervalSeconds));
		youtube.quotaBudget = Math.max(0, Math.min(100_000, youtube.quotaBudget));
		youtube.defaultTimeoutSeconds = Math.max(0, Math.min(7 * 24 * 3600, youtube.defaultTimeoutSeconds));
		if (gameEvents == null) gameEvents = new LinkedHashMap<>();
		if (gameEventsSettings == null) gameEventsSettings = new GameEventsSettings();
		gameEventsSettings.quietSecondsAfterJoin = Math.max(0, Math.min(gameEventsSettings.quietSecondsAfterJoin, 600));
		if (addonTriggers == null) addonTriggers = new LinkedHashMap<>();
		if (timers == null) timers = new ArrayList<>();
		timers.removeIf(java.util.Objects::isNull);
		for (ChatTimer timer : timers) {
			if (timer.name == null) timer.name = "";
			if (timer.text == null) timer.text = "";
			timer.intervalMinutes = Math.max(1, Math.min(timer.intervalMinutes, 24 * 60));
			timer.minChatMessages = Math.max(0, timer.minChatMessages);
		}
		if (clips == null) clips = new Clips();
		clips.donationFrom = Math.max(0, clips.donationFrom);
		clips.clipCooldownSeconds = Math.max(0, clips.clipCooldownSeconds);
		clips.markerCooldownSeconds = Math.max(0, clips.markerCooldownSeconds);
		if (clips.clipChatText == null) clips.clipChatText = "";
		if (warnings == null) warnings = new ArrayList<>();
		for (Map<String, Action> map : List.of(chatCommands, cheer, resubTiers, giftSubTiers, raidTiers, rewards,
				donationTiers, donationAlertsTiers, donatePayTiers, gameEvents, addonTriggers,
				subscribeByTier, resubByTier, giftSubByTier)) {
			for (Action action : map.values()) {
				normalizeAction(action);
			}
		}
		if (follow == null) follow = new Action();
		if (subscribe == null) subscribe = new Action();
		if (resub == null) resub = new Action();
		if (giftSub == null) giftSub = new Action();
		if (raid == null) raid = new Action();
		for (Action action : new Action[]{follow, subscribe, resub, giftSub, raid}) {
			normalizeAction(action);
		}
	}

	/** Приводит поля сбора к допустимым значениям (неизвестный цвет/стиль → по умолчанию, отрицательные ставки → 0). */
	public static void normalizeFundraiser(Fundraiser fund) {
		if (fund == null) {
			return;
		}
		fund.name = fund.name == null ? "" : fund.name.trim();
		if (fund.title == null) fund.title = "";
		fund.target = Math.max(1, fund.target);
		fund.color = fund.color == null ? "pink" : fund.color.trim().toLowerCase(Locale.ROOT);
		if (!FUND_COLORS.contains(fund.color)) fund.color = "pink";
		fund.style = fund.style == null ? "notched_10" : fund.style.trim().toLowerCase(Locale.ROOT);
		if (!FUND_STYLES.contains(fund.style)) fund.style = "notched_10";
		if (fund.format == null || fund.format.isBlank()) fund.format = DEFAULT_FUND_FORMAT;
		if (!(fund.bitsRate >= 0)) fund.bitsRate = 0;       // отсекает и NaN
		if (!(fund.subValue >= 0)) fund.subValue = 0;
		if (!(fund.pointsRate >= 0)) fund.pointsRate = 0;
		fund.hideWhenCompleteSeconds = Math.max(0, fund.hideWhenCompleteSeconds);
		if (fund.action == null) fund.action = new Action();
		normalizeAction(fund.action);
	}

	public static void normalizeAction(Action action) {
		if (action == null) {
			return;
		}
		if (action.name == null) action.name = "";
		if (action.loot == null) action.loot = new ArrayList<>();
		action.loot.removeIf(java.util.Objects::isNull);
		for (Action entry : action.loot) {
			normalizeAction(entry);
		}
		action.weight = Math.max(0, action.weight);
		if (action.commands == null) action.commands = new ArrayList<>();
		if (action.aliases == null) action.aliases = new ArrayList<>();
		if (action.message == null) action.message = "";
		if (action.title == null) action.title = "";
		if (action.subtitle == null) action.subtitle = "";
		if (action.actionbar == null) action.actionbar = "";
		if (action.toast == null) action.toast = "";
		if (action.toastText == null) action.toastText = "";
		if (action.sound == null) action.sound = "";
		if (action.failMessage == null) action.failMessage = "";
		if (action.repeat == null) action.repeat = "";
		if (action.reply == null) action.reply = "";
		if (action.permission == null) action.permission = "everyone";
		if (action.prompt == null) action.prompt = "";
		if (action.color == null) action.color = "";
		action.pool = action.pool == null ? "" : action.pool.trim().toLowerCase(Locale.ROOT);
		if (!action.pool.isEmpty() && !List.of("bad", "good", "any", "xbad", "xgood").contains(action.pool)) {
			action.pool = "";
		}
		action.chance = Math.max(0, Math.min(100, action.chance));
		action.maxRepeat = Math.max(1, action.maxRepeat);
		action.repeatPer = Math.max(1, action.repeatPer);
	}

	/** Ищет опечатки: неизвестные ключи на верхнем уровне и внутри действий. */
	public static List<String> findWarnings(String json) {
		List<String> result = new ArrayList<>();
		try {
			JsonElement root = JsonParser.parseString(json);
			if (!root.isJsonObject()) {
				return result;
			}
			JsonObject obj = root.getAsJsonObject();
			Set<String> known = fieldNames(ModConfig.class);
			for (String key : obj.keySet()) {
				if (!known.contains(key)) {
					result.add("неизвестный ключ \"" + key + "\" — опечатка? (например, rewards, cheer, chatCommands)");
				}
			}
			JsonElement modulesElement = obj.get("modules");
			if (modulesElement != null && modulesElement.isJsonObject()) {
				for (String key : modulesElement.getAsJsonObject().keySet()) {
					if (Module.byId(key) == null) {
						result.add("неизвестный модуль \"" + key + "\" в modules");
					}
				}
			}
			Set<String> actionFields = fieldNames(Action.class);
			for (String single : List.of("follow", "subscribe", "resub", "giftSub", "raid")) {
				checkAction(obj.get(single), single, actionFields, result);
			}
			JsonElement goalsElement = obj.get("goals");
			if (goalsElement != null && goalsElement.isJsonArray()) {
				Set<String> goalFields = fieldNames(Goal.class);
				Set<String> names = new HashSet<>();
				int index = 0;
				for (JsonElement goalElement : goalsElement.getAsJsonArray()) {
					String where = "goals[" + index++ + "]";
					if (!goalElement.isJsonObject()) {
						result.add(where + " должно быть объектом { ... }");
						continue;
					}
					JsonObject goal = goalElement.getAsJsonObject();
					for (String key : goal.keySet()) {
						if (!goalFields.contains(key)) {
							result.add("неизвестное поле \"" + key + "\" в " + where);
						}
					}
					JsonElement type = goal.get("type");
					if (type != null && type.isJsonPrimitive()) {
						boolean knownType = false;
						for (GoalType goalType : GoalType.values()) {
							if (goalType.id.equalsIgnoreCase(type.getAsString().trim())) {
								knownType = true;
							}
						}
						if (!knownType) {
							result.add("неизвестный тип цели \"" + type.getAsString() + "\" в " + where
									+ " (follows, subs, gifts, bits, raids, raidViewers, rewards, points, events)");
						}
					}
					JsonElement name = goal.get("name");
					if (name != null && name.isJsonPrimitive() && !names.add(name.getAsString().trim().toLowerCase(Locale.ROOT))) {
						result.add("цель \"" + name.getAsString() + "\" указана дважды");
					}
					checkAction(goal.get("action"), where + ".action", actionFields, result);
				}
			} else if (goalsElement != null && !goalsElement.isJsonNull()) {
				result.add("goals должно быть списком [ ... ]");
			}
			JsonElement fundsElement = obj.get("fundraisers");
			if (fundsElement != null && fundsElement.isJsonArray()) {
				Set<String> fundFields = fieldNames(Fundraiser.class);
				Set<String> names = new HashSet<>();
				int index = 0;
				for (JsonElement fundElement : fundsElement.getAsJsonArray()) {
					String where = "fundraisers[" + index++ + "]";
					if (!fundElement.isJsonObject()) {
						result.add(where + " должно быть объектом { ... }");
						continue;
					}
					JsonObject fund = fundElement.getAsJsonObject();
					for (String key : fund.keySet()) {
						if (!fundFields.contains(key)) {
							result.add("неизвестное поле \"" + key + "\" в " + where);
						}
					}
					JsonElement color = fund.get("color");
					if (color != null && color.isJsonPrimitive() && !FUND_COLORS.contains(color.getAsString().trim().toLowerCase(Locale.ROOT))) {
						result.add("неизвестный цвет \"" + color.getAsString() + "\" в " + where + " (" + String.join(", ", FUND_COLORS) + ")");
					}
					JsonElement style = fund.get("style");
					if (style != null && style.isJsonPrimitive() && !FUND_STYLES.contains(style.getAsString().trim().toLowerCase(Locale.ROOT))) {
						result.add("неизвестный стиль \"" + style.getAsString() + "\" в " + where + " (" + String.join(", ", FUND_STYLES) + ")");
					}
					JsonElement name = fund.get("name");
					if (name != null && name.isJsonPrimitive() && !names.add(name.getAsString().trim().toLowerCase(Locale.ROOT))) {
						result.add("сбор \"" + name.getAsString() + "\" указан дважды");
					}
					checkAction(fund.get("action"), where + ".action", actionFields, result);
				}
			} else if (fundsElement != null && !fundsElement.isJsonNull()) {
				result.add("fundraisers должно быть списком [ ... ]");
			}
			JsonElement addonTriggersElement = obj.get("addonTriggers");
			if (addonTriggersElement != null && addonTriggersElement.isJsonObject()) {
				for (Map.Entry<String, JsonElement> entry : addonTriggersElement.getAsJsonObject().entrySet()) {
					checkAction(entry.getValue(), "addonTriggers." + entry.getKey(), actionFields, result);
					if (normalizeTriggerSlot(entry.getKey()) == null) {
						result.add("ключ \"" + entry.getKey() + "\" в addonTriggers должен быть слотом кастомного триггера: v0…v3");
					}
				}
			} else if (addonTriggersElement != null && !addonTriggersElement.isJsonNull()) {
				result.add("addonTriggers должно быть объектом { ... }");
			}
			for (String tierMapName : List.of("subscribeByTier", "resubByTier", "giftSubByTier")) {
				JsonElement tierMap = obj.get(tierMapName);
				if (tierMap != null && tierMap.isJsonObject()) {
					for (Map.Entry<String, JsonElement> entry : tierMap.getAsJsonObject().entrySet()) {
						checkAction(entry.getValue(), tierMapName + "." + entry.getKey(), actionFields, result);
						if (normalizeSubTier(entry.getKey()) == null) {
							result.add("ключ \"" + entry.getKey() + "\" в " + tierMapName
									+ " должен быть уровнем подписки: 1, 2, 3 или prime");
						}
					}
				} else if (tierMap != null && !tierMap.isJsonNull()) {
					result.add(tierMapName + " должно быть объектом { ... }");
				}
			}
			for (String mapName : List.of("cheer", "resubTiers", "giftSubTiers", "raidTiers", "rewards", "chatCommands")) {
				JsonElement map = obj.get(mapName);
				if (map != null && map.isJsonObject()) {
					for (Map.Entry<String, JsonElement> entry : map.getAsJsonObject().entrySet()) {
						checkAction(entry.getValue(), mapName + "." + entry.getKey(), actionFields, result);
						if (!mapName.equals("rewards") && !mapName.equals("chatCommands")) {
							try {
								Integer.parseInt(entry.getKey().trim());
							} catch (NumberFormatException e) {
								result.add("ключ \"" + entry.getKey() + "\" в " + mapName + " должен быть числом (порог)");
							}
						}
					}
				}
			}
		} catch (Exception ignored) {
		}
		return result;
	}

	private static void checkAction(JsonElement element, String where, Set<String> actionFields, List<String> result) {
		if (element == null || !element.isJsonObject()) {
			if (element != null && !element.isJsonNull()) {
				result.add(where + " должно быть объектом { ... }");
			}
			return;
		}
		for (String key : element.getAsJsonObject().keySet()) {
			if (!actionFields.contains(key)) {
				result.add("неизвестное поле \"" + key + "\" в " + where);
			}
		}
		JsonElement loot = element.getAsJsonObject().get("loot");
		if (loot != null && loot.isJsonArray()) {
			int index = 0;
			for (JsonElement entry : loot.getAsJsonArray()) {
				checkAction(entry, where + ".loot[" + index++ + "]", actionFields, result);
			}
		} else if (loot != null && !loot.isJsonNull()) {
			result.add(where + ".loot должно быть списком [ ... ]");
		}
	}

	private static Set<String> fieldNames(Class<?> type) {
		Set<String> names = new HashSet<>();
		for (Field field : type.getFields()) {
			if (!Modifier.isStatic(field.getModifiers()) && !Modifier.isTransient(field.getModifiers())) {
				names.add(field.getName());
			}
		}
		return names;
	}

	private static String humanJsonError(Exception e) {
		String message = e.getMessage() == null ? e.toString() : e.getMessage();
		// Gson пишет что-то вроде "... at line 12 column 5 path $.rewards" — оставляем самое полезное
		int at = message.indexOf(" at line");
		if (at > 0) {
			return message.substring(0, at).replace("com.google.gson.stream.MalformedJsonException: ", "")
					+ message.substring(at);
		}
		return message;
	}

	public synchronized void save() {
		try {
			dev.dedworkshop.twitchcraft.util.SafeFiles.writeAtomic(path(), GSON.toJson(this));
		} catch (IOException e) {
			TwitchCraftClient.LOGGER.error("Не удалось сохранить конфиг", e);
		}
	}

	public String toJson() {
		return GSON.toJson(this);
	}

	/** Разбор JSON без обращения к диску (для тестов и отмены изменений в редакторе). */
	public static ModConfig fromJson(String json) {
		ModConfig config = GSON.fromJson(json, ModConfig.class);
		if (config == null) {
			config = createDefault();
		}
		config.normalize();
		return config;
	}

	/** Глубокая копия. */
	public ModConfig copy() {
		ModConfig copy = fromJson(toJson());
		copy.warnings = new ArrayList<>(warnings == null ? List.of() : warnings);
		copy.loadError = loadError;
		return copy;
	}

	// ---------- Настройки по умолчанию (примеры) ----------

	public static ModConfig createDefault() {
		ModConfig c = new ModConfig();

		c.follow = new Action(
				"&d{user} &7теперь следит за каналом! &c❤ &7Подарок: &6{loot}",
				"", "",
				"particle minecraft:heart ~ ~2 ~ 1 1 1 0 20"
		).with(a -> {
			a.sound = "minecraft:entity.experience_orb.pickup";
			a.toast = "&dНовый фолловер";
			a.toastText = "{user} — {loot}";
			// Таблица лута: за фоллов выпадает ОДНА случайная запись (чем больше weight, тем чаще)
			a.loot = new ArrayList<>(List.of(
					Action.lootEntry("3 печенья", 40, "give @s minecraft:cookie 3"),
					Action.lootEntry("золотое яблоко", 20, "give @s minecraft:golden_apple 1"),
					Action.lootEntry("2 железных слитка", 20, "give @s minecraft:iron_ingot 2"),
					Action.lootEntry("изумруд", 10, "give @s minecraft:emerald 1"),
					Action.lootEntry("алмаз", 8, "give @s minecraft:diamond 1"),
					Action.lootEntry("ЗАЧАРОВАННОЕ яблоко", 2, "give @s minecraft:enchanted_golden_apple 1",
							"particle minecraft:totem_of_undying ~ ~1 ~ 0.5 0.5 0.5 0.3 60")
			));
		});

		c.subscribe = new Action(
				"&d{user} &7оформил подписку &5Tier {tier}&7! Алмаз + бонус: &6{loot}",
				"&dНовый саб!", "&f{user}",
				"particle minecraft:totem_of_undying ~ ~1 ~ 0.5 0.5 0.5 0.3 80",
				"give @s minecraft:diamond 1"
		).with(a -> {
			a.sound = "minecraft:ui.toast.challenge_complete";
			a.reply = "Спасибо за подписку, {user}! ❤";
			a.loot = new ArrayList<>(List.of(
					Action.lootEntry("3 золотых яблока", 30, "give @s minecraft:golden_apple 3"),
					Action.lootEntry("5 изумрудов", 30, "give @s minecraft:emerald 5"),
					Action.lootEntry("20 уровней опыта", 25, "xp add @s 20 levels"),
					Action.lootEntry("ТОТЕМ БЕССМЕРТИЯ", 10, "give @s minecraft:totem_of_undying 1"),
					Action.lootEntry("незеритовый слиток", 5, "give @s minecraft:netherite_ingot 1")
			));
		});

		c.resub = new Action(
				"&d{user} &7продлил подписку — уже &5{amount} мес.&7! {message}",
				"&dРесаб {amount} мес.", "&f{user}",
				"give @s minecraft:diamond 1"
		).with(a -> a.sound = "minecraft:ui.toast.challenge_complete");
		c.resubTiers.put("12", new Action(
				"&d{user} &7с нами уже &5{amount} мес.&7 — целый год! {message}",
				"&6ГОД ВМЕСТЕ!", "&f{user} — {amount} мес.",
				"give @s minecraft:netherite_ingot 1",
				"particle minecraft:firework ~ ~1 ~ 1 1 1 0.2 100"
		).with(a -> a.sound = "minecraft:ui.toast.challenge_complete"));

		c.giftSub = new Action(
				"&d{user} &7подарил &5{amount}&7 подписок! Щедрость!",
				"&dПодарочные сабы!", "&f{user} ×{amount}",
				"give @s minecraft:emerald 1"
		).with(a -> {
			a.sound = "minecraft:entity.player.levelup";
			a.repeat = "{amount}";   // по изумруду за каждую подаренную подписку
			a.maxRepeat = 20;
		});
		c.giftSubTiers.put("10", new Action(
				"&d{user} &7подарил &5{amount}&7 подписок! ЛЕГЕНДА!",
				"&6×{amount} ПОДАРКОВ!", "&f{user}",
				"give @s minecraft:diamond_block 1",
				"particle minecraft:totem_of_undying ~ ~1 ~ 1 1 1 0.5 200"
		).with(a -> a.sound = "minecraft:ui.toast.challenge_complete"));

		c.raid = new Action(
				"&6РЕЙД! &d{user} &7привёл &6{amount}&7 зрителей! Золото за каждые 5 зрителей + бонус: &6{loot}",
				"&6РЕЙД!", "&f{user} — {amount} зрителей",
				"effect give @s minecraft:speed 30 1 true",
				"effect give @s minecraft:resistance 30 1 true",
				"give @s minecraft:gold_ingot 1"
		).with(a -> {
			a.sound = "minecraft:event.raid.horn";
			a.reply = "Спасибо за рейд, {user}! Добро пожаловать, {amount} зрителей!";
			a.repeat = "{amount}";
			a.repeatPer = 5;        // команды выполняются по разу на каждые 5 зрителей (эффекты просто продлеваются)
			a.maxRepeat = 20;
			a.repeatDelay = 2;
			// Бонус рейда: одна случайная запись (выпадает один раз, независимо от повторов)
			a.loot = new ArrayList<>(List.of(
					Action.lootEntry("5 золотых слитков", 40, "give @s minecraft:gold_ingot 5"),
					Action.lootEntry("8 золотых морковок", 25, "give @s minecraft:golden_carrot 8"),
					Action.lootEntry("16 пузырьков опыта", 20, "give @s minecraft:experience_bottle 16"),
					Action.lootEntry("алмазный меч", 10, "give @s minecraft:diamond_sword 1"),
					Action.lootEntry("ЭЛИТРЫ", 5, "give @s minecraft:elytra 1")
			));
		});
		c.raidTiers.put("50", new Action(
				"&6БОЛЬШОЙ РЕЙД! &d{user} &7привёл &6{amount}&7 зрителей!",
				"&6БОЛЬШОЙ РЕЙД!", "&f{user} — {amount} зрителей",
				"effect give @s minecraft:speed 60 2 true",
				"effect give @s minecraft:resistance 60 1 true",
				"summon minecraft:lightning_bolt ~5 ~ ~5"
		).with(a -> a.sound = "minecraft:event.raid.horn"));

		// Битсы: порог → действие
		c.cheer.put("1", new Action(
				"&d{user} &7закинул &b{amount} битс&7! {message}",
				"", "",
				"summon minecraft:chicken ~ ~1 ~"
		).with(a -> a.sound = "minecraft:entity.chicken.egg"));
		c.cheer.put("100", new Action(
				"&d{user} &7закинул &b{amount} битс&7! Осторожно, криперы! {message}",
				"&a{amount} битс!", "&f{user}",
				"summon minecraft:creeper ~ ~ ~"
		).with(a -> {
			a.repeat = "{amount}";
			a.repeatPer = 100;      // один крипер за каждые 100 битс
			a.maxRepeat = 5;
		}));
		c.cheer.put("1000", new Action(
				"&d{user} &7закинул &b{amount} битс&7! ГРОМ И МОЛНИЯ! {message}",
				"&e⚡ {amount} битс! ⚡", "&f{user}",
				"summon minecraft:lightning_bolt ~3 ~ ~3",
				"delay 10",
				"summon minecraft:lightning_bolt ~-3 ~ ~-3",
				"delay 10",
				"summon minecraft:lightning_bolt ~3 ~ ~-3"
		).with(a -> a.sound = "minecraft:entity.lightning_bolt.thunder"));

		// Донаты (DonationAlerts / DonatePay): готовый ценник — 25 негативных (☠) и 25 позитивных (★) событий по сумме
		// + «Спасибо» за любую мелочь. Полный список и команды — DonationPresets.
		c.donationTiers.putAll(DonationPresets.defaults());

		// Награды за баллы канала. Название должно ТОЧНО совпадать с названием награды на Twitch / VK Video Live.
		// cost/prompt/color используются командами /twitch rewards sync и /twitch vk rewards sync для создания наград.
		// По умолчанию наград две: каждая запускает СЛУЧАЙНОЕ событие из ценника донатов (pool = bad / good).
		c.rewards.putAll(DonationPresets.poolRewards());
		// Любая другая награда, для которой нет своего действия:
		c.rewards.put("*", new Action(
				"&d{user} &7активировал награду &5«{reward}»&7 за {amount} баллов. {message}",
				"", ""
		));

		// Чат-команды зрителей (пишут в чат Twitch: !zombie)
		c.chatCommands.put("zombie", new Action(
				"&c{user} &7написал !zombie — лови зомби!",
				"", "",
				"summon minecraft:zombie ~2 ~ ~2"
		).with(a -> {
			a.aliases = new ArrayList<>(List.of("зомби"));
			a.cooldown = 60;
			a.userCooldown = 300;
		}));
		c.chatCommands.put("heal", new Action(
				"&a{user} &7подлечил стримера командой!",
				"", "",
				"effect give @s minecraft:regeneration 5 1 true"
		).with(a -> {
			a.aliases = new ArrayList<>(List.of("хил"));
			a.permission = "subscriber";
			a.cooldown = 120;
		}));
		c.chatCommands.put("stats", new Action().with(a -> {
			a.reply = "За стрим: фолловов {session_follows}, сабов {session_subs}, битсов {session_bits}, наград {session_rewards}";
			a.cooldown = 30;
		}));
		c.chatCommands.put("fund", new Action().with(a -> {
			a.aliases = new ArrayList<>(List.of("сбор"));
			a.reply = "Сбор «{fund}»: {fund_current} / {fund_target} {fund_currency} ({fund_percent}%), осталось {fund_left}";
			a.cooldown = 15;
		}));
		// Ценник донатов для зрителей: !ценник — как это работает, !плохое / !хорошее — списки (строятся из donationTiers)
		c.chatCommands.put("ценник", new Action().with(a -> {
			a.aliases = new ArrayList<>(List.of("prices", "донат", "цены"));
			a.reply = "Донат = событие в игре! ☠ пакости — !плохое, ★ подарки — !хорошее. Донать ровно указанную сумму "
					+ "(или чуть больше, но меньше следующей ступени), валюта — {donation_currency}";
			a.cooldown = 20;
		}));
		c.chatCommands.put("плохое", new Action().with(a -> {
			a.aliases = new ArrayList<>(List.of("bad", "пакости"));
			a.reply = "☠ Пакости ({donation_currency}): {donation_prices_bad}";
			a.cooldown = 20;
		}));
		c.chatCommands.put("хорошее", new Action().with(a -> {
			a.aliases = new ArrayList<>(List.of("good", "подарки"));
			a.reply = "★ Подарки ({donation_currency}): {donation_prices_good}";
			a.cooldown = 20;
		}));
		// Счётчик смертей и время стрима (события игры, 1.7.0)
		c.chatCommands.put("смерти", new Action().with(a -> {
			a.aliases = new ArrayList<>(List.of("deaths", "смерть", "f"));
			a.reply = "💀 Смертей за стрим: {deaths}, за всё время: {deaths_total}";
			a.cooldown = 15;
		}));
		c.chatCommands.put("время", new Action().with(a -> {
			a.aliases = new ArrayList<>(List.of("uptime", "time", "аптайм"));
			a.reply = "⏱ Стрим идёт {stream_time}; в игре — {session_time}, смертей {deaths}, достижений {advancements}";
			a.cooldown = 15;
		}));

		// События игры → чаты Twitch, VK и YouTube (reply). message/title/commands тоже работают, если захочется эффектов.
		c.gameEvents.put("death", new Action().with(a -> {
			a.reply = "💀 {cause} — смерть №{deaths} за стрим (всего {deaths_total}). F в чат!";
		}));
		c.gameEvents.put("advancement", new Action().with(a -> {
			a.reply = "🏆 {player} получил достижение «{advancement}»: {advancement_text}";
		}));
		c.gameEvents.put("advancementGoal", new Action().with(a -> {
			a.reply = "🎯 {player} выполнил цель «{advancement}»: {advancement_text}";
		}));
		c.gameEvents.put("advancementChallenge", new Action().with(a -> {
			a.reply = "🔥 {player} прошёл испытание «{advancement}»: {advancement_text}! Это серьёзно";
		}));
		c.gameEvents.put("boss", new Action("", "&6БОСС ПОВЕРЖЕН", "&f{boss}").with(a -> {
			a.reply = "⚔ {player} победил босса: {boss}! Боссов за стрим: {bosses}";
			a.sound = "minecraft:ui.toast.challenge_complete";
		}));
		c.gameEvents.put("dimension", new Action().with(a -> {
			a.reply = "🌀 {player} отправился в {dimension}";
			a.cooldown = 60;
		}));

		// Таймеры чата: напоминание о ценнике раз в 15 минут, если чат живой (3+ сообщений с прошлого раза)
		c.timers.add(new ChatTimer("ценник", 15, 3,
				"Донат = событие в игре: ☠ пакости и ★ подарки — !ценник, !плохое, !хорошее. "
						+ "Награды за баллы канала «Пакость» и «Подарок» (250) — случайное событие из ценника!"));
		c.timers.add(new ChatTimer("соцсети", 30, 5,
				"Подписывайся, чтобы не пропустить стрим: Twitch twitch.tv/dedworkshop, VK live.vkvideo.ru/dedworkshop").with(t -> t.enabled = false));

		// Накопительные цели: прогресс виден в оверлее (F7) и по команде /twitch goals
		c.goals.add(new Goal("Фолловеры", "follows", 10, true, new Action(
				"&6ЦЕЛЬ! &7Ещё &6{target}&7 фолловеров (уже {times}-й раз)! Награда — 3 алмаза!",
				"&6+{target} ФОЛЛОВЕРОВ!", "&fспасибо, {user} и все остальные!",
				"give @s minecraft:diamond 3",
				"particle minecraft:firework ~ ~1 ~ 1 1 1 0.2 100"
		).with(a -> {
			a.sound = "minecraft:ui.toast.challenge_complete";
			a.reply = "Цель «{goal}» достигнута: +{target} фолловеров! Спасибо всем ❤";
		})));
		c.goals.add(new Goal("Подписки", "subs", 5, true, new Action(
				"&6ЦЕЛЬ! &7Ещё &6{target}&7 подписок! Незеритовый слиток!",
				"&6+{target} ПОДПИСОК!", "&f{user} закрыл цель",
				"give @s minecraft:netherite_ingot 1",
				"summon minecraft:firework_rocket ~ ~1 ~"
		).with(a -> {
			a.sound = "minecraft:ui.toast.challenge_complete";
			a.reply = "Цель «{goal}» достигнута: +{target} подписок! Вы лучшие!";
		})));
		c.goals.add(new Goal("Битсы", "bits", 1000, true, new Action(
				"&6ЦЕЛЬ! &b{target} битсов&7 набрано! Алмазный блок!",
				"&b{target} БИТСОВ!", "&fспасибо, {user}!",
				"give @s minecraft:diamond_block 1",
				"particle minecraft:totem_of_undying ~ ~1 ~ 1 1 1 0.5 150"
		).with(a -> a.sound = "minecraft:ui.toast.challenge_complete")));
		c.goals.add(new Goal("Донаты", "donationSum", 1000, true, new Action(
				"&6ЦЕЛЬ! &7Донатами собрано ещё &6{target}&7! Незеритовая броня в подарок!",
				"&6{target} СОБРАНО!", "&fспасибо, {user} и все остальные!",
				"give @s minecraft:netherite_chestplate 1",
				"particle minecraft:firework ~ ~1 ~ 1 1 1 0.2 100"
		).with(a -> a.sound = "minecraft:ui.toast.challenge_complete")));
		c.goals.add(new Goal("Рейдеры", "raidViewers", 100, true, new Action(
				"&6ЦЕЛЬ! &7Рейдами пришло уже &6{target}&7 зрителей! Тотем бессмертия!",
				"&6{target} РЕЙДЕРОВ!", "&fпоследний рейд — {user}",
				"give @s minecraft:totem_of_undying 1"
		).with(a -> a.sound = "minecraft:event.raid.horn")));

		// Сбор средств: полоса-боссбар вверху экрана. Заполняется донатами; /twitch fund — управление.
		Fundraiser fund = new Fundraiser("Сбор", "&6Сбор на стрим", 5000, new Action(
				"&6СБОР ЗАКРЫТ! &7Собрано &6{sum}&7 — спасибо всем! Последний вклад: &d{user}",
				"&6СБОР ЗАКРЫТ!", "&f{sum} — спасибо всем!",
				"particle minecraft:firework ~ ~1 ~ 1 1 1 0.2 150",
				"summon minecraft:firework_rocket ~2 ~ ~2 {LifeTime:30}",
				"delay 10",
				"summon minecraft:firework_rocket ~-2 ~ ~2 {LifeTime:30}",
				"delay 10",
				"summon minecraft:firework_rocket ~ ~ ~-2 {LifeTime:30}"
		).with(a -> {
			a.sound = "minecraft:ui.toast.challenge_complete";
			a.toast = "&6Сбор закрыт!";
			a.toastText = "{goal}: {sum}";
			a.reply = "Сбор «{goal}» закрыт: {sum}! Спасибо всем ❤";
		}));
		c.fundraisers.add(fund);

		return c;
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}
}
