package dev.dedworkshop.twitchcraft.action;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/**
 * Журнал событий: .minecraft/logs/twitchcraft-events.log
 * Одна строка — одно событие. Удобно для разборов «кто и что активировал» после стрима.
 */
public class EventLog {
	public static final String FILE_NAME = "twitchcraft-events.log";
	/** При превышении этого размера журнал переименовывается в {@code .1} (одна старая копия), и начинается новый. */
	static final long ROTATE_BYTES = 5L * 1024 * 1024;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final Executor executor;
	private final BooleanSupplier enabled;
	private final Path path;
	private boolean failed;

	public EventLog(Executor executor) {
		this(executor, () -> true);
	}

	public EventLog(Executor executor, BooleanSupplier enabled) {
		this.executor = executor;
		this.enabled = enabled;
		Path dir;
		try {
			dir = FabricLoader.getInstance().getGameDir().resolve("logs");
		} catch (Exception e) {
			dir = Path.of("logs");
		}
		this.path = dir.resolve(FILE_NAME);
	}

	public Path path() {
		return path;
	}

	public void log(TwitchEvent event, String status) {
		if (event.type() == TwitchEvent.Type.CHAT) {
			return; // чат не пишем — слишком много строк и это чужие сообщения
		}
		if (!enabled.getAsBoolean()) {
			return; // модуль «Журнал событий» выключен
		}
		String line = TIME.format(LocalDateTime.now())
				+ " | " + event.type()
				+ (event.isYoutube() ? " | YouTube" : event.isVk() ? " | VK" : "")
				+ " | " + clean(event.user())
				+ (event.userLogin() != null && !event.userLogin().isBlank() ? " (" + event.userLogin() + ")" : "")
				+ " | amount=" + event.amount()
				+ (event.reward() != null && !event.reward().isBlank() ? " | reward=" + clean(event.reward()) : "")
				+ (event.command() != null && !event.command().isBlank() ? " | command=!" + clean(event.command()) : "")
				+ (event.tier() != null && !event.tier().isBlank() ? " | tier=" + event.tier() : "")
				+ " | " + status
				+ (event.message() != null && !event.message().isBlank() ? " | \"" + clean(event.message()) + "\"" : "")
				+ (event.synthetic() ? " | test" : "")
				+ System.lineSeparator();
		executor.execute(() -> append(line));
	}

	private synchronized void append(String line) {
		if (failed) {
			return;
		}
		try {
			Files.createDirectories(path.getParent());
			rotateIfNeeded();
			Files.writeString(path, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			failed = true;
			TwitchCraftClient.LOGGER.warn("Не удалось записать журнал событий {}: {}", path, e.toString());
		}
	}

	private void rotateIfNeeded() {
		try {
			if (Files.exists(path) && Files.size(path) > ROTATE_BYTES) {
				Path old = path.resolveSibling(path.getFileName() + ".1");
				Files.move(path, old, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			TwitchCraftClient.LOGGER.debug("Не удалось ротировать журнал событий: {}", e.toString());
		}
	}

	private static String clean(String text) {
		return text == null ? "" : text.replace("§", "").replace('\n', ' ').replace('\r', ' ').replace("|", "/");
	}
}
