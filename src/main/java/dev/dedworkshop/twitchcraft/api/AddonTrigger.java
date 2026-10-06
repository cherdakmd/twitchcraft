package dev.dedworkshop.twitchcraft.api;

import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Триггер аддона — то, что должно произойти, чтобы мод выполнил действие аддона.
 * Полный аналог объекта {@code trigger} из схемы TikFinity: у него есть имя
 * (например {@code redemption}, {@code cheer}, {@code custom}) и параметры
 * ({@code id}, {@code title}, {@code minAmount}…). Только здесь всё описано на Java,
 * без JSON: условие срабатывания — обычный предикат {@link Predicate}.
 *
 * <p>Примеры:</p>
 * <pre>
 * AddonTrigger.redemption("f0a1-…")            // активация конкретной награды по id
 * AddonTrigger.redemptionTitle("Артефакт")     // по названию награды
 * AddonTrigger.cheer(100)                      // от 100 битсов
 * AddonTrigger.donation(50)                    // донат от 50
 * AddonTrigger.chatCommand("артефакт")         // !артефакт в чате
 * AddonTrigger.custom("mob_kill", Map.of("mob", "wither"), event -&gt; …) // свой триггер
 * </pre>
 */
public final class AddonTrigger {
	private final String name;
	private final Map<String, String> params;
	private final Predicate<TwitchEvent> match;

	private AddonTrigger(String name, Map<String, String> params, Predicate<TwitchEvent> match) {
		this.name = name == null || name.isBlank() ? "custom" : name;
		this.params = params == null ? Map.of() : Map.copyOf(params);
		this.match = match == null ? event -> false : match;
	}

	/** Имя триггера ({@code follow}, {@code cheer}, {@code redemption}, {@code custom}…). */
	public String name() {
		return name;
	}

	/** Параметры триггера — как {@code params} в TikFinity. */
	public Map<String, String> params() {
		return params;
	}

	/** Подходит ли событие (ошибки предиката мод гасит и пишет в лог). */
	public boolean matches(TwitchEvent event) {
		return event != null && match.test(event);
	}

	@Override
	public String toString() {
		return params.isEmpty() ? name : name + params;
	}

	// ---------- Готовые триггеры ----------

	/** Новый подписчик (подписка, продление, подарочная). */
	public static AddonTrigger subscribe() {
		return new AddonTrigger("subscribe", Map.of(), event -> switch (event.type()) {
			case SUBSCRIBE, RESUB, GIFT_SUB -> true;
			default -> false;
		});
	}

	public static AddonTrigger follow() {
		return new AddonTrigger("follow", Map.of(), event -> event.type() == TwitchEvent.Type.FOLLOW);
	}

	/** Подарочные подписки: минимум {@code minCount} штук за раз. */
	public static AddonTrigger giftSub(int minCount) {
		return new AddonTrigger("giftSub", params("minAmount", Math.max(0, minCount)),
				event -> event.type() == TwitchEvent.Type.GIFT_SUB && event.amount() >= Math.max(0, minCount));
	}

	/** Битсы: минимум {@code minBits}. */
	public static AddonTrigger cheer(int minBits) {
		return new AddonTrigger("cheer", params("minAmount", Math.max(0, minBits)),
				event -> event.type() == TwitchEvent.Type.CHEER && event.amount() >= Math.max(0, minBits));
	}

	/** Рейд: минимум {@code minViewers} зрителей. */
	public static AddonTrigger raid(int minViewers) {
		return new AddonTrigger("raid", params("minAmount", Math.max(0, minViewers)),
				event -> event.type() == TwitchEvent.Type.RAID && event.amount() >= Math.max(0, minViewers));
	}

	/** Донат (DonationAlerts / DonatePay): минимум {@code minAmount} в валюте доната. */
	public static AddonTrigger donation(int minAmount) {
		return new AddonTrigger("donation", params("minAmount", Math.max(0, minAmount)),
				event -> event.type() == TwitchEvent.Type.DONATION && event.amount() >= Math.max(0, minAmount));
	}

	/**
	 * Активация награды за баллы канала по её <b>id</b> (не по названию — название стример
	 * может поменять в любой момент). Параметры — как в TikFinity: {@code id}.
	 */
	public static AddonTrigger redemption(String rewardId) {
		String wanted = rewardId == null ? "" : rewardId.trim();
		return new AddonTrigger("redemption", params("id", wanted),
				event -> event.type() == TwitchEvent.Type.REWARD
						&& (wanted.isEmpty() || wanted.equals(event.rewardId())));
	}

	/** Активация награды по названию (если id неизвестен). */
	public static AddonTrigger redemptionTitle(String title) {
		String wanted = title == null ? "" : title.trim();
		return new AddonTrigger("redemptionTitle", params("title", wanted),
				event -> event.type() == TwitchEvent.Type.REWARD && wanted.equalsIgnoreCase(event.reward()));
	}

	/** Чат-команда (с учётом префикса из настроек) и её синонимы. */
	public static AddonTrigger chatCommand(String command, String... aliases) {
		Map<String, String> params = new LinkedHashMap<>();
		params.put("command", command == null ? "" : command);
		if (aliases != null && aliases.length > 0) {
			params.put("aliases", String.join(",", aliases));
		}
		return new AddonTrigger("chatCommand", params, event -> {
			if (event.type() != TwitchEvent.Type.CHAT_COMMAND) {
				return false;
			}
			String actual = event.command() == null ? "" : event.command().toLowerCase(Locale.ROOT);
			if (actual.equalsIgnoreCase(command)) {
				return true;
			}
			if (aliases != null) {
				for (String alias : aliases) {
					if (actual.equalsIgnoreCase(alias)) {
						return true;
					}
				}
			}
			return false;
		});
	}

	/** Событие игры: {@link TwitchEvent#GAME_DEATH}, {@link TwitchEvent#GAME_BOSS} и т.п. */
	public static AddonTrigger game(String kind) {
		return new AddonTrigger("game", params("kind", kind == null ? "" : kind),
				event -> event.type() == TwitchEvent.Type.GAME && event.gameKind().equalsIgnoreCase(kind));
	}

	/** Любое событие (обычно не нужно — аддон и так получает все события в {@code onEvent}). */
	public static AddonTrigger every() {
		return new AddonTrigger("every", Map.of(), event -> true);
	}

	/**
	 * Свой триггер: имя, параметры для списка {@code /twitch addons triggers} и условие на Java.
	 * Именно так описываются кастомные триггеры аддона (слоты {@code v0…v3}).
	 */
	public static AddonTrigger custom(String name, Map<String, String> params, Predicate<TwitchEvent> match) {
		return new AddonTrigger(name, params, match);
	}

	private static Map<String, String> params(String key, int value) {
		return Map.of(key, String.valueOf(value));
	}

	private static Map<String, String> params(String key, String value) {
		return Map.of(key, value == null ? "" : value);
	}
}
