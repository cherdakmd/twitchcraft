package dev.dedworkshop.twitchcraft.api;

import java.util.List;

/**
 * Кастомный триггер аддона — слот {@code v0…v3} из {@code customTriggers} в схеме TikFinity:
 * {@code { "trigger": { "trigger": "…", "params": {…} }, "actions": [ … ] }}.
 * Таких слотов у аддона не больше {@link AddonRegistry#MAX_CUSTOM_TRIGGERS четырёх}.
 *
 * <p>Отличие от обычного действия ({@link AddonAction}) только в том, что кастомный триггер —
 * именованный слот: он виден в {@code /twitch addons triggers} и его можно запустить вручную
 * ({@code /twitch addons fire v0}) — удобно проверить механику без зрителей.</p>
 *
 * @param index       слот 0…3 (он же {@code v0…v3})
 * @param name        имя триггера для людей
 * @param description описание (что это за триггер)
 * @param trigger     условие срабатывания
 * @param actions     что выполнить (по очереди)
 */
public record AddonCustomTrigger(int index, String name, String description, AddonTrigger trigger,
		List<AddonElements> actions) {

	public AddonCustomTrigger {
		actions = actions == null ? List.of() : List.copyOf(actions);
	}

	/** Имя слота: {@code v0}…{@code v3}. */
	public String slot() {
		return "v" + index;
	}
}
