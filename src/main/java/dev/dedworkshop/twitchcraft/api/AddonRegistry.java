package dev.dedworkshop.twitchcraft.api;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Что аддоны зарегистрировали в моде: свои переменные, действия, привязки к наградам
 * и кастомные триггеры. Мод обращается сюда при каждом событии и при подстановке
 * плейсхолдеров. Всё, что делает аддон, защищено try/catch: ошибка аддона пишется
 * в лог и не мешает ни моду, ни другим аддонам.
 *
 * <p>Это внутренняя часть API: аддоны пользуются методами {@link AddonContext},
 * а не этим классом напрямую.</p>
 */
public final class AddonRegistry {
	/** Сколько кастомных триггеров можно зарегистрировать ({@code v0…v3}). */
	public static final int MAX_CUSTOM_TRIGGERS = 4;

	/** Правило имени переменной: латиница, цифры и подчёркивание — как у обычных плейсхолдеров. */
	private static final Pattern VARIABLE_NAME = Pattern.compile("[a-z][a-z0-9_]{0,31}");

	/** Источник значения переменной: на событие либо без него (глобальная переменная). */
	@FunctionalInterface
	public interface VariableProvider {
		String value(TwitchEvent event);
	}

	/** Обработчик активации награды: {@code input} — то, что зритель написал в поле награды. */
	@FunctionalInterface
	public interface RewardHandler {
		void onRedeem(TwitchEvent event, String input);
	}

	/** Привязка награды за баллы канала к аддону (поиск — по id награды, не по названию). */
	public record RewardBinding(String addonId, String rewardId, String rewardTitle, RewardHandler handler) {
	}

	private record Variable(String addonId, VariableProvider provider) {
	}

	private record CustomSlot(String addonId, AddonCustomTrigger trigger) {
	}

	private record ActionSlot(String addonId, AddonAction action) {
	}

	private static final Map<String, Variable> VARIABLES = new LinkedHashMap<>();
	private static final List<ActionSlot> ACTIONS = new ArrayList<>();
	private static final Map<String, RewardBinding> REWARDS = new LinkedHashMap<>();
	private static final CustomSlot[] CUSTOM_TRIGGERS = new CustomSlot[MAX_CUSTOM_TRIGGERS];

	private AddonRegistry() {
	}

	// ---------- Регистрация (вызывают только AddonContext) ----------

	static synchronized boolean registerVariable(String addonId, String name, VariableProvider provider) {
		if (name == null || provider == null || !VARIABLE_NAME.matcher(name).matches()) {
			TwitchCraftClient.LOGGER.warn("Аддон «{}»: переменная «{}» не зарегистрирована — имя должно состоять "
					+ "из строчных латинских букв, цифр и подчёркивания (например my_var)", addonId, name);
			return false;
		}
		Variable existing = VARIABLES.get(name);
		if (existing != null && !existing.addonId().equals(addonId)) {
			TwitchCraftClient.LOGGER.warn("Аддон «{}»: переменная «{}» уже занята аддоном «{}» — не заменяю",
					addonId, name, existing.addonId());
			return false;
		}
		VARIABLES.put(name, new Variable(addonId, provider));
		TwitchCraftClient.LOGGER.debug("Аддон «{}»: переменная {{{}}} зарегистрирована", addonId, name);
		return true;
	}

	static synchronized boolean registerAction(String addonId, AddonAction action) {
		if (action == null || action.id() == null || action.id().isBlank() || action.trigger() == null) {
			TwitchCraftClient.LOGGER.warn("Аддон «{}»: действие не зарегистрировано — нужны id и триггер", addonId);
			return false;
		}
		for (ActionSlot existing : ACTIONS) {
			if (existing.action().id().equalsIgnoreCase(action.id())) {
				if (!existing.addonId().equals(addonId)) {
					TwitchCraftClient.LOGGER.warn("Аддон «{}»: действие «{}» уже зарегистрировано аддоном «{}» — пропускаю",
							addonId, action.id(), existing.addonId());
					return false;
				}
				ACTIONS.remove(existing);
				break;
			}
		}
		ACTIONS.add(new ActionSlot(addonId, action));
		TwitchCraftClient.LOGGER.info("Аддон «{}»: действие «{}» ({}) ждёт триггер {}", addonId, action.title(),
				action.id(), action.trigger());
		return true;
	}

	static synchronized boolean bindReward(String addonId, String rewardId, String rewardTitle, RewardHandler handler) {
		if (rewardId == null || rewardId.isBlank() || handler == null) {
			TwitchCraftClient.LOGGER.warn("Аддон «{}»: привязка к награде не сделана — нужен id награды", addonId);
			return false;
		}
		String key = rewardId.trim();
		RewardBinding existing = REWARDS.get(key);
		if (existing != null && !existing.addonId().equals(addonId)) {
			TwitchCraftClient.LOGGER.warn("Аддон «{}»: награда {} уже привязана аддоном «{}» — не заменяю",
					addonId, key, existing.addonId());
			return false;
		}
		REWARDS.put(key, new RewardBinding(addonId, key, rewardTitle == null ? "" : rewardTitle.trim(), handler));
		TwitchCraftClient.LOGGER.info("Аддон «{}»: награда «{}» ({}) привязана к аддону", addonId,
				rewardTitle == null || rewardTitle.isBlank() ? key : rewardTitle, key);
		return true;
	}

	static synchronized boolean registerCustomTrigger(String addonId, AddonCustomTrigger trigger) {
		if (trigger == null || trigger.trigger() == null) {
			return false;
		}
		int index = trigger.index();
		if (index < 0 || index >= MAX_CUSTOM_TRIGGERS) {
			TwitchCraftClient.LOGGER.warn("Аддон «{}»: кастомный триггер «{}» не зарегистрирован — слот {} "
					+ "вне диапазона v0…v{}", addonId, trigger.name(), index, MAX_CUSTOM_TRIGGERS - 1);
			return false;
		}
		CustomSlot existing = CUSTOM_TRIGGERS[index];
		if (existing != null && !existing.addonId().equals(addonId)) {
			TwitchCraftClient.LOGGER.warn("Аддон «{}»: слот {} занят триггером «{}» аддона «{}» — не заменяю",
					addonId, trigger.slot(), existing.trigger().name(), existing.addonId());
			return false;
		}
		CUSTOM_TRIGGERS[index] = new CustomSlot(addonId, trigger);
		TwitchCraftClient.LOGGER.info("Аддон «{}»: кастомный триггер {} «{}» ({}), действий: {}", addonId,
				trigger.slot(), trigger.name(), trigger.trigger(), trigger.actions().size());
		return true;
	}

	// ---------- Что видит мод ----------

	/**
	 * Значения переменных аддонов (хук 1) — для подстановки в сообщения, команды и HUD.
	 * Ошибка одного аддона не мешает остальным: переменная просто не попадёт в набор.
	 */
	public static Map<String, String> variables(TwitchEvent event) {
		Map<String, String> values = new LinkedHashMap<>();
		for (Map.Entry<String, Variable> entry : snapshotVariables().entrySet()) {
			try {
				String value = entry.getValue().provider().value(event);
				if (value != null) {
					values.put(entry.getKey(), value);
				}
			} catch (Throwable t) {
				TwitchCraftClient.LOGGER.error("Аддон «{}»: переменная {{{}}} упала на событии {}",
						entry.getValue().addonId(), entry.getKey(), event == null ? "-" : event.type(), t);
			}
		}
		return values;
	}

	/** Значения переменных аддонов без события (HUD, оверлей, статус). */
	public static Map<String, String> globalVariables() {
		return variables(null);
	}

	/**
	 * Элементы действий, у которых сработал триггер (хуки 2 и 4): обычные действия аддонов
	 * и действия их кастомных триггеров. Выполняет их мод.
	 */
	public static List<AddonElements> elementsFor(TwitchEvent event) {
		List<AddonElements> result = new ArrayList<>();
		if (event == null) {
			return result;
		}
		for (ActionSlot slot : actionSlots()) {
			if (matches(slot.action().trigger(), event, "действие «" + slot.action().id() + "»")) {
				result.add(slot.action().elements());
			}
		}
		for (AddonCustomTrigger trigger : customTriggers()) {
			if (matches(trigger.trigger(), event, "кастомный триггер " + trigger.slot())) {
				result.addAll(trigger.actions());
			}
		}
		return result;
	}

	/** Привязка награды по её id (хук 3); {@code null}, если награда аддонам не отдана. */
	public static RewardBinding reward(String rewardId) {
		if (rewardId == null || rewardId.isBlank()) {
			return null;
		}
		return REWARDS.get(rewardId.trim());
	}

	/** Зарегистрированные действия (для {@code /twitch addons}). */
	public static List<AddonAction> actions() {
		List<AddonAction> list = new ArrayList<>();
		for (ActionSlot slot : actionSlots()) {
			list.add(slot.action());
		}
		return list;
	}

	/** Идентификатор аддона, зарегистрировавшего действие (или null). */
	public static String actionAddon(String actionId) {
		if (actionId == null) {
			return null;
		}
		for (ActionSlot slot : actionSlots()) {
			if (slot.action().id().equalsIgnoreCase(actionId)) {
				return slot.addonId();
			}
		}
		return null;
	}

	private static List<ActionSlot> actionSlots() {
		synchronized (AddonRegistry.class) {
			return List.copyOf(ACTIONS);
		}
	}

	/** Привязки наград (для {@code /twitch addons}). */
	public static List<RewardBinding> rewards() {
		synchronized (AddonRegistry.class) {
			return List.copyOf(REWARDS.values());
		}
	}

	/** Кастомные триггеры {@code v0…v3} (для {@code /twitch addons triggers}). */
	public static List<AddonCustomTrigger> customTriggers() {
		List<AddonCustomTrigger> list = new ArrayList<>();
		synchronized (AddonRegistry.class) {
			for (CustomSlot slot : CUSTOM_TRIGGERS) {
				if (slot != null) {
					list.add(slot.trigger());
				}
			}
		}
		return list;
	}

	public static AddonCustomTrigger customTrigger(int index) {
		synchronized (AddonRegistry.class) {
			CustomSlot slot = index >= 0 && index < CUSTOM_TRIGGERS.length ? CUSTOM_TRIGGERS[index] : null;
			return slot == null ? null : slot.trigger();
		}
	}

	/** Какой аддон занял слот {@code v0…v3} (или null). */
	public static String customTriggerAddon(int index) {
		synchronized (AddonRegistry.class) {
			CustomSlot slot = index >= 0 && index < CUSTOM_TRIGGERS.length ? CUSTOM_TRIGGERS[index] : null;
			return slot == null ? null : slot.addonId();
		}
	}

	/** Имена зарегистрированных переменных. */
	public static List<String> variableNames() {
		synchronized (AddonRegistry.class) {
			return List.copyOf(VARIABLES.keySet());
		}
	}

	/** Что зарегистрировано — одной строкой (для {@code /twitch addons}). */
	public static String summary() {
		return "переменных: " + variableNames().size() + ", действий: " + actions().size()
				+ ", наград: " + rewards().size() + ", кастомных триггеров: " + customTriggers().size()
				+ "/" + MAX_CUSTOM_TRIGGERS;
	}

	/** Только для тестов: очистить всё зарегистрированное. */
	public static synchronized void clear() {
		VARIABLES.clear();
		ACTIONS.clear();
		REWARDS.clear();
		java.util.Arrays.fill(CUSTOM_TRIGGERS, null);
	}

	// ---------- Служебное ----------

	private static Map<String, Variable> snapshotVariables() {
		synchronized (AddonRegistry.class) {
			return new LinkedHashMap<>(VARIABLES);
		}
	}

	private static boolean matches(AddonTrigger trigger, TwitchEvent event, String what) {
		try {
			return trigger.matches(event);
		} catch (Throwable t) {
			TwitchCraftClient.LOGGER.error("Аддон: ошибка условия у «{}» на событии {} (считаю, что не сработал)",
					what, event.type(), t);
			return false;
		}
	}
}
