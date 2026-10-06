package dev.dedworkshop.twitchcraft.action;

import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Подстановка плейсхолдеров вида {user}, {amount} в сообщения и команды.
 *
 * Подстановка делается за один проход: значение одного плейсхолдера не может
 * «раскрыться» в другой (зритель, написавший в сообщении «{player}», ничего не добьётся).
 */
public final class Placeholders {
	private static final int MAX_TEXT_LENGTH = 200;
	private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");

	private Placeholders() {
	}

	public static Map<String, String> of(TwitchEvent event, String playerName) {
		return of(event, playerName, null);
	}

	/**
	 * Как {@link #of(TwitchEvent, String, SessionStats)}, но счётчики {session_*} уже включают само событие
	 * (используется при выполнении действия: событие запишется в статистику после него).
	 */
	public static Map<String, String> forPending(TwitchEvent event, String playerName, SessionStats stats) {
		Map<String, String> vars = of(event, playerName, null);
		if (stats != null) {
			vars.putAll(stats.placeholdersIncluding(event));
		}
		return vars;
	}

	public static Map<String, String> of(TwitchEvent event, String playerName, SessionStats stats) {
		Map<String, String> vars = new LinkedHashMap<>();
		vars.put("user", safe(event.user()));
		vars.put("user_login", safe(event.userLogin()));
		vars.put("amount", String.valueOf(event.amount()));
		vars.put("message", safe(event.message()));
		vars.put("reward", safe(event.reward()));
		vars.put("tier", safe(event.tier()));
		vars.put("command", safe(event.command()));
		vars.put("player", playerName == null ? "" : playerName);
		vars.put("repeat", "1");
		vars.put("i", "1");
		vars.put("loot", "");
		vars.put("source", "");
		vars.put("platform", event.isVk() ? "VK Video Live" : event.isGame() ? "Minecraft" : "Twitch");
		vars.put("currency", "");
		vars.put("sum", String.valueOf(event.amount()));
		if (event.type() == TwitchEvent.Type.DONATION) {
			vars.put("source", event.sourceTitle());
			vars.put("currency", safe(event.currency()));
			vars.put("sum", (event.amount() + " " + event.currencySymbol()).trim());
		}
		if (event.type() == TwitchEvent.Type.GOAL || event.type() == TwitchEvent.Type.FUND) {
			vars.put("goal", safe(event.reward()));
			vars.put("target", String.valueOf(event.amount()));
			vars.put("times", safe(event.message()));
		}
		if (event.type() == TwitchEvent.Type.FUND) {
			vars.put("currency", safe(event.tier()));
			vars.put("sum", (event.amount() + " " + event.currencySymbol()).trim());
		}
		if (event.type() == TwitchEvent.Type.GAME) {
			// Текст события игры: причина смерти / достижение / босс / измерение
			vars.put("cause", safe(event.message()));
			vars.put("advancement", safe(event.message()));
			vars.put("advancement_text", safe(event.tier()));
			vars.put("advancement_kind", switch (event.gameKind()) {
				case TwitchEvent.GAME_ADVANCEMENT_GOAL -> "цель";
				case TwitchEvent.GAME_ADVANCEMENT_CHALLENGE -> "испытание";
				default -> "достижение";
			});
			vars.put("boss", safe(event.message()));
			vars.put("killer", safe(event.tier()));
			vars.put("dimension", safe(event.message()));
			vars.put("dimension_id", safe(event.tier()));
			vars.put("game_kind", event.gameKind());
		}
		if (stats != null) {
			vars.putAll(stats.placeholders());
		}
		return vars;
	}

	/** Заменяет {name} на значение из vars. Неизвестные плейсхолдеры остаются как есть. */
	public static String apply(String template, Map<String, String> vars) {
		if (template == null || template.isEmpty()) {
			return "";
		}
		if (template.indexOf('{') < 0) {
			return template;
		}
		Matcher matcher = PLACEHOLDER.matcher(template);
		StringBuilder result = new StringBuilder();
		while (matcher.find()) {
			String value = vars.get(matcher.group(1));
			matcher.appendReplacement(result, Matcher.quoteReplacement(value != null ? value : matcher.group()));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	/**
	 * Очищает текст зрителя: убирает переводы строк и управляющие символы,
	 * заменяет кавычки и обратные слэши — чтобы чужой текст не ломал команды
	 * (например, JSON внутри /tellraw).
	 */
	public static String safe(String text) {
		if (text == null) {
			return "";
		}
		String cleaned = text
				.replaceAll("\\p{Cntrl}", " ")
				.replace('"', '\'')
				.replace("\\", "")
				.replace("§", "");
		if (cleaned.length() > MAX_TEXT_LENGTH) {
			cleaned = cleaned.substring(0, MAX_TEXT_LENGTH);
		}
		return cleaned.trim();
	}
}
