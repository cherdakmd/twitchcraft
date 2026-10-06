package dev.dedworkshop.twitchcraft.ui.config;

/** Где используется действие — от этого зависит набор полей в редакторе. */
enum ActionKind {
	/** Награда за баллы канала: ключ — название награды; есть cost/prompt/input/color. */
	REWARD("Название награды (как на Twitch)"),
	/** Чат-команда: ключ — имя команды; есть permission/aliases. */
	CHAT_COMMAND("Имя команды (без префикса)"),
	/** Порог по количеству (битсы, месяцы, подарки, зрители): ключ — число. */
	TIER("Порог (число, «от»)"),
	/** Одиночное событие: follow / subscribe / resub / giftSub / raid. */
	EVENT(null),
	/** Действие цели. */
	GOAL(null),
	/** Запись таблицы лута: есть name/weight, нет вложенного лута. */
	LOOT(null),
	/** Событие игры (смерть, достижение, босс, измерение): reply уходит в чаты Twitch и VK. */
	GAME_EVENT(null);

	final String keyLabel;

	ActionKind(String keyLabel) {
		this.keyLabel = keyLabel;
	}

	boolean hasKey() {
		return keyLabel != null;
	}
}
