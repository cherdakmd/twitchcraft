package dev.dedworkshop.twitchcraft.ui.config;

/** Где используется действие — от этого зависит набор полей в редакторе. */
enum ActionKind {
	/** Награда за баллы канала: ключ — название награды; есть cost/prompt/input/color. */
	REWARD("Название награды (как на Twitch)"),
	/** Чат-команда: ключ — имя команды; есть permission/aliases. */
	CHAT_COMMAND("Имя команды (без префикса)"),
	/** Порог по количеству (битсы, месяцы, подарки, зрители): ключ — число. */
	TIER("Порог (число, «от»)"),
	/** Действие по уровню подписки (1/2/3/prime): ключ — уровень. */
	SUB_TIER("Уровень подписки: 1, 2, 3 или prime"),
	/** Одиночное событие: follow / subscribe / resub / giftSub / raid. */
	EVENT(null),
	/** Действие цели. */
	GOAL(null),
	/** Запись таблицы лута: есть name/weight, нет вложенного лута. */
	LOOT(null),
	/** Событие игры (смерть, достижение, босс, измерение): reply уходит в выбранные чаты Twitch, VK и YouTube. */
	GAME_EVENT(null),
	/** Действие из конфига, привязанное к кастомному триггеру аддона (слот v0…v3). */
	ADDON_TRIGGER(null);

	final String keyLabel;

	ActionKind(String keyLabel) {
		this.keyLabel = keyLabel;
	}

	boolean hasKey() {
		return keyLabel != null;
	}
}
