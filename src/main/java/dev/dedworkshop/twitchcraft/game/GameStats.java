package dev.dedworkshop.twitchcraft.game;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.util.SafeFiles;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Счётчики событий игры: за текущий сеанс (в памяти) и за всё время (config/twitchcraft-stats.json).
 * Отсюда берутся плейсхолдеры {deaths} {deaths_total} {advancements} {bosses} {session_time}.
 */
public class GameStats {
	public static final String FILE_NAME = "twitchcraft-stats.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** Что лежит в файле. */
	public static class Saved {
		public int deathsTotal;
		public int advancementsTotal;
		public int bossesTotal;
		public int sessions;
		public long lastDeathAt;
		public String lastDeath = "";
		public long updatedAt;
	}

	private final Path file;
	private final Saved saved;
	private boolean persist = true;

	// За сеанс
	public int deaths, advancements, bosses, dimensionChanges;
	public String lastDeath = "";
	public long lastDeathAt;
	public final long startedAt = System.currentTimeMillis();

	public GameStats(Path file, Saved saved) {
		this.file = file;
		this.saved = saved == null ? new Saved() : saved;
	}

	/** Загружает счётчики из config/twitchcraft-stats.json (при ошибке — с нуля, файл не трогаем до первой записи). */
	public static GameStats load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		return load(path);
	}

	public static GameStats load(Path path) {
		Saved saved = null;
		if (Files.exists(path)) {
			try {
				saved = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Saved.class);
			} catch (IOException | RuntimeException e) {
				TwitchCraftClient.LOGGER.warn("Не удалось прочитать {} — счётчики за всё время начнутся заново", path, e);
			}
		}
		GameStats stats = new GameStats(path, saved);
		stats.saved.sessions++;
		return stats;
	}

	public void setPersist(boolean value) {
		this.persist = value;
	}

	public synchronized int deathsTotal() {
		return saved.deathsTotal;
	}

	public synchronized int advancementsTotal() {
		return saved.advancementsTotal;
	}

	public synchronized int bossesTotal() {
		return saved.bossesTotal;
	}

	public synchronized int sessions() {
		return saved.sessions;
	}

	/** Записывает смерть; возвращает её номер за сеанс. */
	public synchronized int recordDeath(String cause) {
		deaths++;
		saved.deathsTotal++;
		lastDeath = cause == null || cause.isBlank() ? "смерть №" + deaths : cause;
		lastDeathAt = System.currentTimeMillis();
		saved.lastDeath = lastDeath;
		saved.lastDeathAt = lastDeathAt;
		save();
		return deaths;
	}

	public synchronized int recordAdvancement() {
		advancements++;
		saved.advancementsTotal++;
		save();
		return advancements;
	}

	public synchronized int recordBoss() {
		bosses++;
		saved.bossesTotal++;
		save();
		return bosses;
	}

	public synchronized int recordDimensionChange() {
		return ++dimensionChanges;
	}

	/** Сброс счётчиков сеанса (/twitch game reset). */
	public synchronized void resetSession() {
		deaths = 0;
		advancements = 0;
		bosses = 0;
		dimensionChanges = 0;
		lastDeath = "";
		lastDeathAt = 0;
	}

	/** Сброс счётчиков за всё время (/twitch game reset all). */
	public synchronized void resetTotals() {
		saved.deathsTotal = 0;
		saved.advancementsTotal = 0;
		saved.bossesTotal = 0;
		saved.lastDeath = "";
		saved.lastDeathAt = 0;
		save();
	}

	/** Плейсхолдеры для сообщений и чат-команд. */
	public synchronized Map<String, String> placeholders() {
		Map<String, String> vars = new LinkedHashMap<>();
		vars.put("deaths", String.valueOf(deaths));
		vars.put("deaths_total", String.valueOf(saved.deathsTotal));
		vars.put("advancements", String.valueOf(advancements));
		vars.put("advancements_total", String.valueOf(saved.advancementsTotal));
		vars.put("bosses", String.valueOf(bosses));
		vars.put("bosses_total", String.valueOf(saved.bossesTotal));
		vars.put("last_death", lastDeath);
		vars.put("session_time", formatDuration(System.currentTimeMillis() - startedAt));
		return vars;
	}

	/** «1 ч 05 мин» / «12 мин» / «меньше минуты». */
	public static String formatDuration(long millis) {
		long minutes = Math.max(0, millis) / 60_000;
		if (minutes < 1) {
			return "меньше минуты";
		}
		long hours = minutes / 60;
		long rest = minutes % 60;
		if (hours == 0) {
			return rest + " мин";
		}
		return hours + " ч " + (rest < 10 ? "0" + rest : String.valueOf(rest)) + " мин";
	}

	private void save() {
		if (!persist || file == null) {
			return;
		}
		saved.updatedAt = System.currentTimeMillis();
		try {
			SafeFiles.writeAtomic(file, GSON.toJson(saved));
		} catch (IOException e) {
			TwitchCraftClient.LOGGER.warn("Не удалось сохранить {}", file, e);
		}
	}
}
