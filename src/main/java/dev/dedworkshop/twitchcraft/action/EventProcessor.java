package dev.dedworkshop.twitchcraft.action;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.api.AddonCustomTrigger;
import dev.dedworkshop.twitchcraft.api.AddonElements;
import dev.dedworkshop.twitchcraft.api.AddonRegistry;
import dev.dedworkshop.twitchcraft.config.DonationPresets;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import dev.dedworkshop.twitchcraft.ui.TwitchChatRenderer;
import dev.dedworkshop.twitchcraft.util.Chat;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Центр обработки событий Twitch. Решает, что делать с событием:
 * показать, поставить в очередь, отбросить (с возвратом баллов), проверить кулдаун,
 * права и шанс — и затем передать действие в ActionRunner.
 *
 * Все методы вызываются в основном потоке клиента.
 */
public class EventProcessor {
	private final TwitchCraftClient mod;
	private final ActionRunner runner;
	private final Cooldowns cooldowns = new Cooldowns();
	private final SessionStats stats = new SessionStats();
	private final EventLog log;
	private final GoalTracker goals;
	private final FundraiserTracker funds;
	private final Deque<TwitchEvent> queue = new ArrayDeque<>();
	private final Deque<Long> recent = new ArrayDeque<>();
	private final Random random = new Random();

	private final java.util.Set<String> warnedRewardTitles = new java.util.HashSet<>();

	private boolean paused;
	private int queueWait;
	private TwitchEvent lastEvent;

	public EventProcessor(TwitchCraftClient mod) {
		this.mod = mod;
		this.runner = new ActionRunner(mod);
		this.log = new EventLog(mod.worker(), () -> mod.config().isEnabled(Module.EVENT_LOG));
		this.goals = mod.goalTracker();
		this.funds = mod.fundraisers();
	}

	public GoalTracker goals() {
		return goals;
	}

	public ActionRunner runner() {
		return runner;
	}

	public SessionStats stats() {
		return stats;
	}

	public EventLog log() {
		return log;
	}

	public Cooldowns cooldowns() {
		return cooldowns;
	}

	// ---------- Вход ----------

	/** Главная точка входа: событие от EventSub или тестовой команды. */
	public void handle(TwitchEvent event) {
		ModConfig config = mod.config();
		Module owner = Module.forEvent(event);
		if (!event.synthetic() && owner != null && !config.isEnabled(owner)) {
			TwitchCraftClient.LOGGER.debug("Модуль {} выключен — событие пропущено: {}", owner, event.shortText());
			return;
		}
		if (event.type() == TwitchEvent.Type.CHAT) {
			handleChat(event);
			return;
		}
		if (event.type() == TwitchEvent.Type.DONATION && mod.clips() != null) {
			mod.clips().onDonation(event); // клип/метка сразу, даже если само событие встанет в очередь
		}
		Minecraft mc = Minecraft.getInstance();
		if (event.type() == TwitchEvent.Type.GAME) {
			// События игры не ждут очередь, паузу и лимит — иначе объявление смерти придёт через полчаса
			if (mc.player == null) {
				drop(event, "игрок не в мире");
			} else {
				process(event);
			}
			return;
		}
		if (mc.player == null) {
			if (config.queueWhenNotInWorld) {
				enqueue(event, "ты не в мире");
			} else {
				drop(event, "игрок не в мире");
			}
			return;
		}
		if (paused) {
			enqueue(event, "пауза");
			return;
		}
		if (rateLimited()) {
			enqueue(event, "лимит событий в минуту");
			return;
		}
		process(event);
	}

	/** Сообщение чата Twitch или VK Video Live: показать и/или распознать чат-команду. */
	private void handleChat(TwitchEvent event) {
		ModConfig config = mod.config();
		if (config.isIgnoredUser(event.userLogin())) {
			return;
		}
		// Каждая платформа имеет собственные флаги чата/команд; владелец события (и общий модуль) проверен выше.
		boolean vk = event.isVk();
		boolean youtube = event.isYoutube();
		boolean showChat = youtube ? config.youtube.showChat : vk ? config.vk.showChat : config.isEnabled(Module.TWITCH_CHAT);
		boolean allowCommands = youtube ? config.youtube.chatCommands
				: config.isEnabled(Module.CHAT_COMMANDS) && (!vk || config.vk.chatCommands);
		if (!showChat && !allowCommands) {
			return;
		}
		if (event.isShared() && !config.twitchChat.showSharedChat && !config.twitchChat.sharedChatCommands) {
			return; // общий чат (Shared Chat) полностью игнорируем
		}
		stats.record(event, "chat");

		String text = event.message() == null ? "" : event.message().trim();
		String prefix = config.chatCommandPrefix == null || config.chatCommandPrefix.isEmpty() ? "!" : config.chatCommandPrefix;
		String name = null;
		String args = "";
		if (text.startsWith(prefix) && text.length() > prefix.length()) {
			String rest = text.substring(prefix.length()).trim();
			if (!rest.isEmpty()) {
				int space = rest.indexOf(' ');
				name = (space < 0 ? rest : rest.substring(0, space)).toLowerCase(Locale.ROOT);
				args = space < 0 ? "" : rest.substring(space + 1).trim();
			}
		}

		ModConfig.Resolved resolved = name == null || !allowCommands ? null : config.findChatCommand(name);
		boolean sentByUs = youtube ? mod.youtube().wasSentByUs(text)
				: vk ? mod.vk().wasSentByUs(text) : mod.chatSender().wasSentByUs(text);
		if (resolved != null && sentByUs) {
			resolved = null; // наш собственный ответ вернулся через чат — не зацикливаемся
		}
		if (resolved != null && event.isShared() && !config.twitchChat.sharedChatCommands) {
			resolved = null; // зрители канала-партнёра в Shared Chat не запускают наши команды
		}
		boolean hide = resolved != null && config.twitchChat.hideCommands;
		if (showChat && !hide && (!event.isShared() || config.twitchChat.showSharedChat)) {
			TwitchChatRenderer.show(config, event);
		}
		if (resolved != null) {
			handle(event.asCommand(name, args));
		}
	}

	// ---------- Очередь ----------

	private void enqueue(TwitchEvent event, String why) {
		ModConfig config = mod.config();
		if (queue.size() >= Math.max(1, config.maxQueuedEvents)) {
			drop(event, "очередь переполнена");
			return;
		}
		queue.addLast(event);
		TwitchCraftClient.LOGGER.info("Событие поставлено в очередь ({}): {} [в очереди {}]", why, event.shortText(), queue.size());
		if (config.showEventsInChat && Minecraft.getInstance().player != null) {
			Chat.info("§7В очередь (" + why + "): §f" + event.shortText() + " §8[" + queue.size() + "]");
		}
	}

	private void drop(TwitchEvent event, String why) {
		TwitchCraftClient.LOGGER.warn("Событие пропущено ({}): {}", why, event.shortText());
		stats.record(event, "пропущено: " + why);
		log.log(event, "dropped: " + why);
		mod.rewards().refund(event, why);
		if (mod.config().showEventsInChat && Minecraft.getInstance().player != null) {
			Chat.warn("Пропущено (" + why + "): " + event.shortText());
		}
	}

	private boolean rateLimited() {
		int max = mod.config().maxEventsPerMinute;
		if (max <= 0) {
			return false;
		}
		long now = System.currentTimeMillis();
		while (!recent.isEmpty() && recent.peekFirst() < now - 60_000) {
			recent.pollFirst();
		}
		return recent.size() >= max;
	}

	public int queueSize() {
		return queue.size();
	}

	/** Очищает очередь, возвращая баллы за награды. */
	public int clearQueue() {
		int n = queue.size();
		while (!queue.isEmpty()) {
			drop(queue.pollFirst(), "очередь очищена");
		}
		return n;
	}

	public boolean isPaused() {
		return paused;
	}

	public void setPaused(boolean value) {
		paused = value;
	}

	/** @return новое состояние (true — пауза включена). */
	public boolean togglePause() {
		paused = !paused;
		return paused;
	}

	public TwitchEvent lastEvent() {
		return lastEvent;
	}

	/** Повторяет последнее событие как тестовое (без Twitch API и кулдаунов). */
	public boolean replayLast() {
		if (lastEvent == null || Minecraft.getInstance().player == null) {
			return false;
		}
		process(lastEvent.asSynthetic());
		return true;
	}

	/** Вызывается каждый игровой тик. */
	public void tick(Minecraft mc) {
		runner.tick(mc);
		if (queue.isEmpty() || paused || mc.player == null) {
			return;
		}
		if (queueWait > 0) {
			queueWait--;
			return;
		}
		if (rateLimited()) {
			return;
		}
		TwitchEvent next = queue.pollFirst();
		queueWait = Math.max(0, mod.config().queueDelayTicks);
		process(next);
	}

	// ---------- Обработка одного события ----------

	private void process(TwitchEvent event) {
		Minecraft mc = Minecraft.getInstance();
		ModConfig config = mod.config();
		recent.addLast(System.currentTimeMillis());
		lastEvent = event;

		boolean chatShown = event.isYoutube() ? config.youtube.showChat
				: event.isVk() ? config.vk.showChat : config.isEnabled(Module.TWITCH_CHAT);
		boolean chatCommandShown = event.type() == TwitchEvent.Type.CHAT_COMMAND && chatShown && !config.twitchChat.hideCommands;
		if (config.showEventsInChat && !chatCommandShown) {
			boolean hideMessage = event.type() == TwitchEvent.Type.DONATION && !config.donations.showMessage;
			Chat.info(hideMessage ? event.withoutMessage().describe() : event.describe());
		}

		String playerName = mc.player != null ? mc.player.getName().getString() : "";
		Map<String, String> vars = Placeholders.forPending(event, playerName, stats);
		// Хук 1: переменные аддонов — только для свободных имён, чтобы аддон не мог перехватить
		// {user}, {amount} или {deaths} и молча сломать все тексты и команды мода.
		AddonRegistry.applyVariables(vars, event);
		if (funds != null) {
			vars.putAll(funds.placeholders()); // {fund} {fund_current} {fund_target} {fund_percent} {fund_left} {fund_currency}
		}
		vars.putAll(DonationPresets.placeholders(config.donationTiers)); // {donation_prices_bad} {donation_prices_good} {donation_prices}
		vars.put("donation_currency", TwitchEvent.currencySymbol(config.donations == null ? "" : config.donations.currency));
		if (mod.gameStats() != null) {
			vars.putAll(mod.gameStats().placeholders()); // {deaths} {deaths_total} {advancements} {bosses} {session_time}
		}
		if (mod.streamStatus() != null) {
			vars.putAll(mod.streamStatus().placeholders()); // {stream_time} {viewers} {live}
		}
		if (mod.youtube() != null) {
			// {youtube_viewers} {youtube_live_time} {youtube_broadcast_url} {youtube_title} {youtube_channel}
			vars.putAll(mod.youtube().placeholders());
		}

		// Хуки 2–4: действия аддонов и привязка награды по id. Работают независимо от того, настроено ли
		// действие в конфиге; кулдаунами и правами аддон управляет сам (условием своего триггера).
		boolean addonHandled = runAddonHooks(event, vars);

		ModConfig.Resolved resolved = config.findAction(event);
		if (resolved == null) {
			if (event.type() == TwitchEvent.Type.REWARD && !event.synthetic() && config.showEventsInChat) {
				// Зритель потратил баллы, а выполнять нечего: без этой подсказки выглядит как «мод ничего не сделал».
				Chat.warn("Для награды «" + event.reward() + "» нет действия: добавь запись с таким названием в раздел rewards "
						+ "(или «*» для всех остальных) — /twitch config → Награды за баллы.");
			}
			finish(event, addonHandled ? "выполнено (аддон)" : "нет действия");
			countForGoals(event);
			return;
		}
		ModConfig.Action action = resolved.action();
		if (!action.enabled) {
			finish(event, "действие выключено");
			countForGoals(event);
			return;
		}

		// Права (только чат-команды; тестовые события — без проверки)
		if (event.type() == TwitchEvent.Type.CHAT_COMMAND && !event.synthetic()) {
			TwitchEvent.Permission need = action.permissionLevel();
			if (event.permission().ordinal() < need.ordinal()) {
				String reply = config.chatReplies.noPermissionReply;
				if (notBlank(reply)) {
					Map<String, String> replyVars = new HashMap<>(vars);
					replyVars.put("permission", permissionName(need));
					mod.reply(event, Placeholders.apply(reply, replyVars));
				}
				finish(event, "нет прав: нужно " + permissionName(need));
				return;
			}
		}

		// Кулдаун
		if (!event.synthetic()) {
			int wait = cooldowns.remaining(resolved.key(), event.userLogin(), action.cooldown, action.userCooldown);
			if (wait > 0) {
				String reply = config.chatReplies.cooldownReply;
				if (notBlank(reply)) {
					Map<String, String> replyVars = new HashMap<>(vars);
					replyVars.put("seconds", String.valueOf(wait));
					mod.reply(event, Placeholders.apply(reply, replyVars));
				}
				mod.rewards().refund(event, "кулдаун");
				if (config.showEventsInChat) {
					Chat.warn("Кулдаун: ещё " + wait + " с — " + event.shortText());
				}
				finish(event, "кулдаун " + wait + " с");
				return;
			}
		}

		// Шанс
		if (action.chance < 100 && random.nextInt(100) >= action.chance) {
			if (notBlank(action.failMessage)) {
				Chat.send(Component.literal(Placeholders.apply(Chat.colorize(action.failMessage), vars)));
			}
			markCooldown(event, resolved.key(), action);
			mod.rewards().fulfill(event); // баллы потрачены честно — это лотерея
			finish(event, "не повезло (шанс " + action.chance + "%)");
			countForGoals(event);
			return;
		}

		markCooldown(event, resolved.key(), action);
		finish(event, "выполнено");
		runner.run(event, action, vars, () -> mod.rewards().fulfill(event));
		countForGoals(event);
	}

	/**
	 * Хуки аддонов на одном событии: привязка награды по id (хук 3) и действия с подходящим
	 * триггером — обычные (хук 2) и из кастомных триггеров {@code v0…v3} (хук 4).
	 * К сработавшему кастомному триггеру дополнительно выполняется действие из конфига
	 * (раздел {@code addonTriggers}), если стример привязал его к слоту.
	 * Мод выполняет элементы сам, ошибки аддона гасятся и пишутся в лог.
	 *
	 * @return true, если сработал хотя бы один хук аддона
	 */
	private boolean runAddonHooks(TwitchEvent event, Map<String, String> vars) {
		boolean handled = false;
		AddonRegistry.RewardBinding binding = AddonRegistry.reward(event.rewardId());
		if (binding != null) {
			handled = true;
			warnAboutRenamedReward(binding, event);
			try {
				binding.handler().onRedeem(event, event.message() == null ? "" : event.message());
			} catch (Throwable t) {
				TwitchCraftClient.LOGGER.error("Аддон «{}»: ошибка обработки награды «{}» ({}) с вводом «{}»",
						binding.addonId(), binding.rewardTitle(), binding.rewardId(), event.message(), t);
			}
		}
		for (AddonElements elements : AddonRegistry.actionElementsFor(event)) {
			handled = true;
			if (elements == null || elements.isEmpty()) {
				continue;
			}
			ModConfig.Action action = elements.toConfigAction();
			if (action.chance < 100 && random.nextInt(100) >= action.chance) {
				if (notBlank(action.failMessage)) {
					Chat.send(Component.literal(Placeholders.apply(Chat.colorize(action.failMessage), vars)));
				}
				continue;
			}
			runner.run(event, action, vars, () -> { });
		}
		for (AddonCustomTrigger trigger : AddonRegistry.matchedCustomTriggers(event)) {
			handled = true;
			for (AddonElements elements : trigger.actions()) {
				if (elements == null || elements.isEmpty()) {
					continue;
				}
				ModConfig.Action action = elements.toConfigAction();
				if (action.chance < 100 && random.nextInt(100) >= action.chance) {
					if (notBlank(action.failMessage)) {
						Chat.send(Component.literal(Placeholders.apply(Chat.colorize(action.failMessage), vars)));
					}
					continue;
				}
				runner.run(event, action, vars, () -> { });
			}
			runAddonTriggerAction(event, trigger.slot(), trigger.name(), vars);
		}
		return handled;
	}

	/**
	 * Выполняет действие из конфига (раздел {@code addonTriggers}), привязанное к слоту
	 * кастомного триггера аддона: шанс и кулдауны работают как у действий из конфига,
	 * к плейсхолдерам добавляются {trigger} (имя триггера) и {slot} (например, "v2").
	 * Тестовые события кулдауны не ставят.
	 *
	 * @return true, если действие выполнено
	 */
	public boolean runAddonTriggerAction(TwitchEvent event, String slot, String triggerName, Map<String, String> vars) {
		ModConfig.Resolved resolved = mod.config().findAddonTriggerAction(slot);
		if (resolved == null || !resolved.action().enabled || resolved.action().isEmpty()) {
			return false;
		}
		ModConfig.Action action = resolved.action();
		String slotName = resolved.key().substring("addonTrigger:".length());
		if (!event.synthetic()) {
			int wait = cooldowns.remaining(resolved.key(), event.userLogin(), action.cooldown, action.userCooldown);
			if (wait > 0) {
				if (mod.config().showEventsInChat && Minecraft.getInstance().player != null) {
					Chat.warn("Кулдаун: ещё " + wait + " с — триггер аддона " + slotName);
				}
				TwitchCraftClient.LOGGER.debug("Триггер аддона {} на кулдауне ({} с): {}", slotName, wait, event.shortText());
				return false;
			}
		}
		if (action.chance < 100 && random.nextInt(100) >= action.chance) {
			if (notBlank(action.failMessage)) {
				Map<String, String> failVars = new HashMap<>(vars);
				failVars.put("trigger", triggerName == null ? "" : triggerName);
				failVars.put("slot", slotName);
				Chat.send(Component.literal(Placeholders.apply(Chat.colorize(action.failMessage), failVars)));
			}
			markCooldown(event, resolved.key(), action);
			return false;
		}
		markCooldown(event, resolved.key(), action);
		Map<String, String> boundVars = new HashMap<>(vars);
		boundVars.put("trigger", triggerName == null ? "" : triggerName);
		boundVars.put("slot", slotName);
		runner.run(event, action, boundVars, () -> { });
		TwitchCraftClient.LOGGER.info("Триггер аддона {}: выполнено действие из конфига ({})", slotName, event.shortText());
		return true;
	}

	/**
	 * Ручной запуск кастомного триггера аддона (слот {@code v0…v3}): собственные действия
	 * триггера плюс привязанное к слоту действие из конфига. Событие синтетическое —
	 * кулдауны не ставятся, к площадкам обращений нет.
	 *
	 * @return сколько действий запущено; -1, если слот пуст (триггер никто не зарегистрировал)
	 */
	public int fireCustomTrigger(int index) {
		AddonCustomTrigger trigger = AddonRegistry.customTrigger(index);
		if (trigger == null) {
			return -1;
		}
		Minecraft mc = Minecraft.getInstance();
		String player = mc.player != null ? mc.player.getName().getString() : "Игрок";
		TwitchEvent event = TwitchEvent.test(TwitchEvent.Type.CHAT_COMMAND, player, 0, "", trigger.name(), "");
		int fired = 0;
		Map<String, String> vars = new HashMap<>(mod.globalPlaceholders());
		vars.putAll(AddonRegistry.variables(event));
		for (AddonElements elements : trigger.actions()) {
			if (elements == null || elements.isEmpty()) {
				continue;
			}
			runner.run(event, elements.toConfigAction(), vars, () -> { });
			fired++;
		}
		if (runAddonTriggerAction(event, trigger.slot(), trigger.name(), vars)) {
			fired++;
		}
		return fired;
	}

	/** Награду переименовали в Twitch — предупреждаем один раз: привязка по id продолжает работать. */
	private void warnAboutRenamedReward(AddonRegistry.RewardBinding binding, TwitchEvent event) {
		String expected = binding.rewardTitle();
		String actual = event.reward();
		if (expected == null || expected.isBlank() || actual == null || actual.isBlank()
				|| expected.equalsIgnoreCase(actual)) {
			return;
		}
		String key = binding.rewardId() + "|" + expected + "|" + actual;
		if (warnedRewardTitles.add(key)) {
			Chat.warn("Аддон «" + binding.addonId() + "» привязан к награде «" + expected + "» (id " + binding.rewardId()
					+ "), а сейчас активирована «" + actual + "». Привязка по id работает, но название стоит обновить.");
		}
	}

	/** Засчитывает событие в цели и сборы; достигнутые цели / закрытые сборы обрабатываются как отдельные события. */
	private void countForGoals(TwitchEvent event) {
		if (event.type() == TwitchEvent.Type.GOAL || event.type() == TwitchEvent.Type.FUND || event.type() == TwitchEvent.Type.GAME
				|| event.synthetic()) {
			return;
		}
		if (goals != null) {
			List<TwitchEvent> reached = goals.onEvent(event);
			for (TwitchEvent goalEvent : reached) {
				handle(goalEvent);
			}
		}
		if (funds != null) {
			List<TwitchEvent> closed = funds.onEvent(event);
			for (TwitchEvent fundEvent : closed) {
				handle(fundEvent);
			}
		}
	}

	/** Тестовые события и повторы не ставят настоящие действия на кулдаун. */
	private void markCooldown(TwitchEvent event, String key, ModConfig.Action action) {
		if (!event.synthetic()) {
			cooldowns.mark(key, event.userLogin(), action.cooldown, action.userCooldown);
		}
	}

	private void finish(TwitchEvent event, String status) {
		stats.record(event, status);
		log.log(event, status);
	}

	private static String permissionName(TwitchEvent.Permission permission) {
		return switch (permission) {
			case EVERYONE -> "всех";
			case SUBSCRIBER -> "подписчиков";
			case VIP -> "VIP";
			case MODERATOR -> "модераторов";
			case BROADCASTER -> "стримера";
		};
	}

	private static boolean notBlank(String s) {
		return s != null && !s.isBlank();
	}
}
