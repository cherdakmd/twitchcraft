package dev.dedworkshop.twitchcraft.api;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.action.CommandRunner;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.util.Chat;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/**
 * То, что TwitchCraft выдаёт аддону: пути к конфигам, потоки, чат, команды и плейсхолдеры.
 *
 * <p>Все методы можно вызывать из любого потока: сообщения в чат и команды
 * TwitchCraft сам переносит в основной поток игры.</p>
 */
public final class AddonContext {
	private final TwitchCraftClient mod;
	private final CommandRunner commands;

	AddonContext(TwitchCraftClient mod) {
		this.mod = mod;
		this.commands = new CommandRunner(mod);
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
