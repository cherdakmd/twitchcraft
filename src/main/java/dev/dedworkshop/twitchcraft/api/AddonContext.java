package dev.dedworkshop.twitchcraft.api;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.action.CommandRunner;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import dev.dedworkshop.twitchcraft.util.Chat;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * То, что TwitchCraft выдаёт аддону: пути к конфигам, потоки, чат, команды и плейсхолдеры.
 *
 * <p>Все методы можно вызывать из любого потока: сообщения в чат и команды
 * TwitchCraft сам переносит в основной поток игры.</p>
 */
public final class AddonContext {
	private final TwitchCraftClient mod;
	private final CommandRunner commands;
	private final String addonId;

	AddonContext(TwitchCraftClient mod) {
		this(mod, "?");
	}

	AddonContext(TwitchCraftClient mod, String addonId) {
		this.mod = mod;
		this.commands = new CommandRunner(mod);
		this.addonId = addonId == null || addonId.isBlank() ? "?" : addonId;
	}

	// ---------- Файлы и потоки ----------

	/** Папка настроек мода ({@code .minecraft/config}) — здесь аддон хранит свои файлы. */
	public Path configDir() {
		return FabricLoader.getInstance().getConfigDir();
	}

	/** Планировщик TwitchCraft (периодические задачи). Не выключать. */
	public ScheduledExecutorService scheduler() {
		return mod.scheduler();
	}

	/** Фоновый поток TwitchCraft для сетевых и медленных задач. */
	public ExecutorService worker() {
		return mod.worker();
	}

	/** Клиент Minecraft (то же, что {@code Minecraft.getInstance()}). */
	public Minecraft client() {
		return Minecraft.getInstance();
	}

	/** Настройки TwitchCraft (только чтение — менять их аддон не должен). */
	public ModConfig config() {
		return mod.config();
	}

	/** Включён ли модуль TwitchCraft (например {@link Module#TWITCH_CHAT}). */
	public boolean moduleEnabled(Module module) {
		return mod.isModuleEnabled(module);
	}

	/** Плейсхолдеры TwitchCraft без привязки к событию: {player}, {session_time}, {stream_time} и т.п. */
	public Map<String, String> placeholders() {
		Map<String, String> vars = mod.globalPlaceholders();
		return vars == null ? new LinkedHashMap<>() : vars;
	}

	// ---------- Чат ----------

	public void info(String text) {
		Chat.info(text);
	}

	public void success(String text) {
		Chat.success(text);
	}

	public void warn(String text) {
		Chat.warn(text);
	}

	public void error(String text) {
		Chat.error(text);
	}

	/**
	 * Сообщение от имени мода в чаты площадок (Twitch и VK Video Live) — с теми же
	 * очередями и проверками прав, что и остальные объявления TwitchCraft.
	 */
	public void announce(String text) {
		announce(text, true, true);
	}

	public void announce(String text, boolean toTwitch, boolean toVk) {
		mod.announce(text, toTwitch, toVk, false);
	}

	// ---------- Команды ----------

	/**
	 * Выполнить команду Minecraft от имени игрока (например {@code give @s minecraft:diamond 1}).
	 * В одиночной игре — прямо на встроенном сервере с правами оператора,
	 * на чужом сервере — как обычный игрок (нужны права OP).
	 *
	 * @return false, если игрок не в мире или команда запрещена настройкой {@code blockedCommands}
	 */
	public boolean runCommand(String command) {
		return commands.run(command);
	}

	// ---------- Хуки: что аддон добавляет в мод ----------

	/**
	 * Хук 1 — своя переменная (плейсхолдер). Значение подставляется в сообщения, заголовки,
	 * команды и HUD мода как {@code {имя}}: например {@code context.registerVariable("boss_kills", e -> …)}
	 * даёт {@code {boss_kills}}.
	 *
	 * <p>Имя — строчными латинскими буквами, цифрами и подчёркиванием ({@code [a-z][a-z0-9_]*}).
	 * Значение считается в основном потоке игры, поэтому поставщик должен быть быстрым.
	 * Возвращает {@code false}, если имя занято или не подходит.</p>
	 *
	 * @param name     имя переменной без фигурных скобок
	 * @param provider значение на событие ({@code null}-событие — «нет события»: HUD, статус)
	 */
	public boolean registerVariable(String name, Function<TwitchEvent, String> provider) {
		AddonRegistry.VariableProvider source = provider == null ? null : provider::apply;
		return AddonRegistry.registerVariable(addonId, name, source);
	}

	/** То же, что {@link #registerVariable(String, Function)}, для значения, не зависящего от события. */
	public boolean registerVariable(String name, Supplier<String> provider) {
		AddonRegistry.VariableProvider source = provider == null ? null : event -> provider.get();
		return AddonRegistry.registerVariable(addonId, name, source);
	}

	/**
	 * Хук 2 — действие (флоу) аддона: триггер + элементы. Мод сам следит за триггером
	 * и сам выполняет элементы — в основном потоке, с плейсхолдерами и ограничениями
	 * {@code blockedCommands}, как у действий из конфига.
	 *
	 * @return {@code false}, если действие с таким id уже есть или задано неверно
	 */
	public boolean registerAction(AddonAction action) {
		return AddonRegistry.registerAction(addonId, action);
	}

	/**
	 * Хук 3 — привязать конкретную награду за баллы канала к аддону <b>по её id</b>
	 * (название стример может менять, id — нет). Обработчик получает событие и ввод зрителя
	 * ({@link TwitchEvent#message()} — то, что зритель написал в поле награды).
	 *
	 * <p>Название награды нужно только для списка {@code /twitch addons} и проверки:
	 * если стример переименует награду, мод предупредит в логе, но привязка продолжит работать.</p>
	 *
	 * @param rewardId    id награды (Twitch → управление наградами → id)
	 * @param rewardTitle название награды для человека (можно {@code null})
	 */
	public boolean bindReward(String rewardId, String rewardTitle, AddonRegistry.RewardHandler handler) {
		return AddonRegistry.bindReward(addonId, rewardId, rewardTitle, handler);
	}

	/** То же, но без названия награды. */
	public boolean bindReward(String rewardId, AddonRegistry.RewardHandler handler) {
		return bindReward(rewardId, "", handler);
	}

	/**
	 * Хук 4 — кастомный триггер в слот {@code v0…v3} (как {@code customTriggers} в TikFinity):
	 * у каждого свой триггер и свой список действий. Таких слотов у аддона не больше
	 * {@link AddonRegistry#MAX_CUSTOM_TRIGGERS четырёх}.
	 *
	 * @param index       0…3 — номер слота ({@code v0}…{@code v3})
	 * @param name        имя триггера для людей
	 * @param description что это за триггер
	 * @return {@code false}, если слот занят или номер вне диапазона
	 */
	public boolean registerCustomTrigger(int index, String name, String description, AddonTrigger trigger,
			List<AddonElements> actions) {
		return AddonRegistry.registerCustomTrigger(addonId,
				new AddonCustomTrigger(index, name, description, trigger, actions));
	}

	/** Кастомный триггер с одним действием. */
	public boolean registerCustomTrigger(int index, String name, String description, AddonTrigger trigger,
			AddonElements action) {
		return registerCustomTrigger(index, name, description, trigger,
				action == null ? List.of() : List.of(action));
	}

	// ---------- Служебное ----------

	/** Лог игры (logs/latest.log) с префиксом мода. */
	public Logger logger() {
		return TwitchCraftClient.LOGGER;
	}

	/** Версия TwitchCraft, например {@code 1.8.0}. */
	public String twitchCraftVersion() {
		Optional<ModContainer> container = FabricLoader.getInstance().getModContainer(TwitchCraftClient.MOD_ID);
		return container.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
	}
}
