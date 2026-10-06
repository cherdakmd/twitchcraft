package dev.dedworkshop.twitchcraft.module;

import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;

import java.util.Locale;

/**
 * Модули мода. Каждый можно выключить в конфиге (раздел "modules"),
 * командой /twitch module <id> on|off или в экране настроек (Mod Menu).
 *
 * Выключенный модуль: не создаёт подписок EventSub, не обрабатывает свои события,
 * не показывает свои элементы интерфейса.
 */
public enum Module {
	FOLLOWS("follows", "Фолловы", "Событие «новый фолловер» → действие follow", Kind.EVENTS, "moderator:read:followers"),
	SUBSCRIPTIONS("subscriptions", "Подписки", "Новые подписки, продления и подарочные подписки", Kind.EVENTS, "channel:read:subscriptions"),
	BITS("bits", "Битсы", "Донаты битсами с порогами по сумме", Kind.EVENTS, "bits:read"),
	RAIDS("raids", "Рейды", "Входящие рейды с порогами по зрителям", Kind.EVENTS, null),
	CHANNEL_POINTS("channelPoints", "Баллы канала", "Активации наград за баллы канала", Kind.EVENTS, "channel:read:redemptions"),
	TWITCH_CHAT("twitchChat", "Чат Twitch в игре", "Сообщения чата Twitch показываются в чате Minecraft", Kind.CHAT, "user:read:chat"),
	CHAT_COMMANDS("chatCommands", "Чат-команды", "Команды зрителей вида !zombie из чата Twitch", Kind.CHAT, "user:read:chat"),
	CHAT_REPLIES("chatReplies", "Ответы в чат", "Автоответы зрителям в чат Twitch (reply, кулдауны, права)", Kind.CHAT, "user:write:chat"),
	REWARD_MANAGEMENT("rewardManagement", "Управление наградами", "Создание наград на Twitch, подтверждение и возврат баллов", Kind.FEATURE, "channel:manage:redemptions"),
	GOALS("goals", "Цели", "Накопительные цели: каждые N фолловов / сабов / битсов — награда", Kind.FEATURE, null),
	FUNDRAISERS("fundraisers", "Сборы средств", "Полоса сбора в стиле боссбара вверху экрана: донаты (и битсы/подписки) заполняют цель", Kind.FEATURE, null),
	OVERLAY("overlay", "Оверлей", "Статус, статистика, цели и последние события на экране (F7)", Kind.UI, null),
	HOTKEYS("hotkeys", "Горячие клавиши", "F7 — оверлей, F8 — пауза, F9 — повтор события", Kind.UI, null),
	EVENT_LOG("eventLog", "Журнал событий", "Запись всех событий в logs/twitchcraft-events.log", Kind.FEATURE, null),
	DONATION_ALERTS("donationAlerts", "DonationAlerts", "Донаты через DonationAlerts в реальном времени → эффекты по сумме", Kind.DONATIONS, null),
	DONATE_PAY("donatePay", "DonatePay", "Донаты через DonatePay (опрос API по ключу раз в 20 с) → эффекты по сумме", Kind.DONATIONS, null),
	VK_VIDEO_LIVE("vkVideoLive", "VK Video Live", "Чат, чат-команды, награды за баллы и фолловы канала на live.vkvideo.ru — те же действия, что и для Twitch", Kind.PLATFORMS, null),
	GAME_EVENTS("gameEvents", "События игры → чат", "Смерти, достижения, боссы и смена измерения объявляются в чат Twitch и VK; счётчик смертей (!смерти, !время)", Kind.FEATURE, null),
	CHAT_TIMERS("chatTimers", "Таймеры чата", "Периодические сообщения бота в чаты Twitch и VK: напоминание о ценнике, соцсети и т.п.", Kind.FEATURE, null),
	CLIPS("clips", "Клипы и метки Twitch", "Клип и метка стрима при смерти, донате от N и победе над боссом; F10 / /twitch clip — вручную", Kind.FEATURE, "clips:edit channel:manage:broadcast");

	public enum Kind {
		EVENTS("События Twitch"), CHAT("Чат"), DONATIONS("Донаты"), PLATFORMS("Другие платформы"), FEATURE("Функции"), UI("Интерфейс");

		public final String title;

		Kind(String title) {
			this.title = title;
		}
	}

	public final String id;
	public final String title;
	public final String description;
	public final Kind kind;
	/** Право Twitch, без которого модуль не работает (null — не нужно). */
	public final String scope;

	Module(String id, String title, String description, Kind kind, String scope) {
		this.id = id;
		this.title = title;
		this.description = description;
		this.kind = kind;
		this.scope = scope;
	}

	/** Меняет ли переключение модуля список подписок EventSub (нужно переподключение). */
	public boolean affectsSubscriptions() {
		return kind == Kind.EVENTS || this == TWITCH_CHAT || this == CHAT_COMMANDS;
	}

	public static Module byId(String id) {
		if (id == null) {
			return null;
		}
		String wanted = id.trim();
		for (Module module : values()) {
			if (module.id.equalsIgnoreCase(wanted) || module.name().equalsIgnoreCase(wanted)) {
				return module;
			}
		}
		return null;
	}

	/** Модуль, отвечающий за тип события (для донатов — DonationAlerts; точнее — forEvent(TwitchEvent)). */
	public static Module forEvent(TwitchEvent.Type type) {
		return switch (type) {
			case FOLLOW -> FOLLOWS;
			case SUBSCRIBE, RESUB, GIFT_SUB -> SUBSCRIPTIONS;
			case CHEER -> BITS;
			case RAID -> RAIDS;
			case REWARD -> CHANNEL_POINTS;
			case CHAT -> TWITCH_CHAT;
			case CHAT_COMMAND -> CHAT_COMMANDS;
			case GOAL -> GOALS;
			case FUND -> FUNDRAISERS;
			case DONATION -> DONATION_ALERTS;
			case GAME -> GAME_EVENTS;
		};
	}

	/** Все права Twitch, нужные модулю (в scope они перечислены через пробел). */
	public java.util.List<String> scopes() {
		return scope == null || scope.isBlank() ? java.util.List.of() : java.util.List.of(scope.trim().split("\\s+"));
	}

	/** Первое право модуля, которого нет в токене, или null, если всё выдано (или прав не нужно). */
	public String missingScope(dev.dedworkshop.twitchcraft.config.TokenStore tokens) {
		for (String s : scopes()) {
			if (tokens == null || !tokens.hasScope(s)) {
				return s;
			}
		}
		return null;
	}

	/** Модуль, отвечающий за конкретное событие: для доната — по источнику. */
	public static Module forEvent(TwitchEvent event) {
		if (event.isVk()) {
			return VK_VIDEO_LIVE; // все события VK Video Live живут в одном модуле (внутри него — свои флаги в разделе vk)
		}
		if (event.type() == TwitchEvent.Type.DONATION) {
			return forDonationSource(event.source());
		}
		return forEvent(event.type());
	}

	/** Это интеграция с другой стриминговой площадкой (VK Video Live). */
	public boolean isPlatform() {
		return kind == Kind.PLATFORMS;
	}

	/** Модуль по источнику доната; для тестовых («test») — null (не блокируется). */
	public static Module forDonationSource(String source) {
		if (source == null) {
			return null;
		}
		return switch (source.toLowerCase(Locale.ROOT)) {
			case TwitchEvent.SOURCE_DONATION_ALERTS -> DONATION_ALERTS;
			case TwitchEvent.SOURCE_DONATE_PAY -> DONATE_PAY;
			default -> null;
		};
	}

	/** Это модуль-интеграция с сервисом донатов. */
	public boolean isDonationService() {
		return kind == Kind.DONATIONS;
	}

	@Override
	public String toString() {
		return id.toLowerCase(Locale.ROOT);
	}
}
