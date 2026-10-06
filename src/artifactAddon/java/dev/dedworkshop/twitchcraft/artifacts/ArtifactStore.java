package dev.dedworkshop.twitchcraft.artifacts;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.util.SafeFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Состояние аддона: все артефакты, проценты проклятий и статистика.
 * Файл — {@code config/twitchcraft-artifacts-state.json} (атомарная запись).
 *
 * Рост проклятия: +1 % за каждые {@code curse.intervalMinutes} минут, пока артефакт
 * лежит в инвентаре. На {@code curse.breakAt} (100 %) артефакт разрушается — аддон
 * убирает предмет из инвентаря и объявляет об этом в чаты.
 */
public final class ArtifactStore {
	public static final String FILE_NAME = "twitchcraft-artifacts-state.json";
	public static final int CURRENT_VERSION = 1;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	public int configVersion = CURRENT_VERSION;
	public List<Artifact> artifacts = new ArrayList<>();

	// --- Статистика ---
	/** Сколько артефактов выбито за всё время. */
	public long generated = 0;
	/** Сколько разрушено проклятием. */
	public long destroyed = 0;
	/** По редкостям (id → количество). */
	public Map<String, Integer> byRarity = new LinkedHashMap<>();
	/** По источникам: bits, donation, sub, gift, raid, reward, boss, manual. */
	public Map<String, Integer> bySource = new LinkedHashMap<>();
	/** По зрителям: кто «выбил» больше всех. */
	public Map<String, Integer> byViewer = new LinkedHashMap<>();

	// ---------- Боссы (BossManager): расписание и статистика ----------
	/** Сколько боссов вызвано и побеждено. */
	public long bossSpawned = 0;
	public long bossDefeats = 0;
	/** Когда вызывать следующего босса (мс) и кого запланировали. */
	public long bossNextAt = 0;
	public String bossPlannedId = "";
	/** Кто сейчас вызван (пусто — никто) и где он появился. */
	public String bossActiveId = "";
	public String bossActiveName = "";
	public double bossX = 0;
	public double bossY = 0;
	public double bossZ = 0;
	public long bossSpawnAt = 0;

	private transient Path path;

	public static ArtifactStore load(Path path) {
		ArtifactStore store = new ArtifactStore();
		store.path = path;
		if (path == null || !Files.exists(path)) {
			return store;
		}
		try {
			ArtifactStore loaded = GSON.fromJson(Files.readString(path), ArtifactStore.class);
			if (loaded == null) {
				return store;
			}
			loaded.path = path;
			loaded.normalize();
			return loaded;
		} catch (Exception e) {
			TwitchCraftClient.LOGGER.error("Аддон «Артефакты»: файл состояния {} не прочитан — начинаю с чистого листа", path, e);
			SafeFiles.backupBroken(path);
			return store;
		}
	}

	public void normalize() {
		configVersion = CURRENT_VERSION;
		if (artifacts == null) {
			artifacts = new ArrayList<>();
		}
		if (byRarity == null) {
			byRarity = new LinkedHashMap<>();
		}
		if (bySource == null) {
			bySource = new LinkedHashMap<>();
		}
		if (byViewer == null) {
			byViewer = new LinkedHashMap<>();
		}
	}

	public void save() {
		if (path == null) {
			return;
		}
		try {
			SafeFiles.writeAtomic(path, GSON.toJson(this));
		} catch (IOException e) {
			TwitchCraftClient.LOGGER.error("Аддон «Артефакты»: не удалось сохранить состояние", e);
		}
	}

	public Artifact byId(String id) {
		if (id == null) {
			return null;
		}
		String wanted = id.trim();
		for (Artifact artifact : artifacts) {
			if (artifact.id.equalsIgnoreCase(wanted)) {
				return artifact;
			}
		}
		return null;
	}

	/** Артефакты, которые ещё действуют (не разрушены проклятием). */
	public List<Artifact> active() {
		List<Artifact> result = new ArrayList<>();
		for (Artifact artifact : artifacts) {
			if (!artifact.destroyed) {
				result.add(artifact);
			}
		}
		return result;
	}

	/** Сколько артефактов лежит у игрока (без разрушенных). */
	public int activeCount() {
		return active().size();
	}

	/** Можно ли выдать ещё один артефакт (лимит {@code max-artifacts}, как на сервере). */
	public boolean canAdd(int maxArtifacts) {
		return activeCount() < Math.max(1, maxArtifacts);
	}

	/** Добавить артефакт: выдать уникальный id и записать в статистику. */
	public Artifact add(Artifact artifact, Random random) {
		while (byId(artifact.id) != null) {
			artifact.id = ArtifactFactory.newId(random);
		}
		artifacts.add(artifact);
		generated++;
		byRarity.merge(artifact.rarity, 1, Integer::sum);
		bySource.merge(artifact.source == null || artifact.source.isEmpty() ? "manual" : artifact.source, 1, Integer::sum);
		if (artifact.foundBy != null && !artifact.foundBy.isEmpty()) {
			byViewer.merge(artifact.foundBy, 1, Integer::sum);
		}
		return artifact;
	}

	/**
	 * Продвинуть проклятия на {@code millis} миллисекунд «ношения».
	 *
	 * @return артефакты, которые дошли до {@code breakAt} и должны рассыпаться
	 */
	public List<Artifact> advanceCurse(long millis, ArtifactConfig config) {
		List<Artifact> broken = new ArrayList<>();
		if (millis <= 0 || config == null || config.curse == null || !config.curse.enabled) {
			return broken;
		}
		long intervalMs = Math.max(1, config.curse.intervalMinutes) * 60_000L;
		long fragileMs = Math.max(1, config.curse.fragileHours) * 3_600_000L;
		for (Artifact artifact : artifacts) {
			if (!artifact.active() || !artifact.present || !artifact.hasCurse()) {
				continue; // действует только то, что лежит в инвентаре; мифические не стареют
			}
			artifact.wornMillis += millis;
			double percent = Math.min(config.curse.breakAt, (double) artifact.wornMillis / intervalMs);
			if ("FRAGILE".equalsIgnoreCase(artifact.curse) && artifact.wornMillis >= fragileMs) {
				percent = config.curse.breakAt; // хрупкость: разрушается по времени, даже если проклятие «ниже»
			}
			artifact.cursePercent = percent;
			if (percent >= config.curse.breakAt) {
				broken.add(artifact);
			}
		}
		for (Artifact artifact : broken) {
			destroy(artifact);
		}
		return broken;
	}

	/** Разрушить артефакт (проклятие или команда {@code /artifact break}). */
	public void destroy(Artifact artifact) {
		if (artifact.destroyed) {
			return;
		}
		artifact.destroyed = true;
		artifact.destroyedAt = System.currentTimeMillis();
		artifact.cursePercent = Math.max(artifact.cursePercent, 0);
		destroyed++;
	}

	/** Убрать запись полностью (например, игрок выбросил «выгоревший» артефакт). */
	public void forget(Artifact artifact) {
		artifacts.remove(artifact);
	}

	/**
	 * Убрать записи «выгоревших» артефактов, предмета которых в инвентаре уже нет.
	 *
	 * <p>«Выгоревший» ({@code inactive}) — это артефакт, который мод не смог вынуть из инвентаря
	 * (например, чужой сервер без прав OP). Он больше не действует, но его запись продолжала
	 * занимать место в лимите {@code maxArtifacts} и навсегда оставалась в файле состояния.
	 * Как только игрок выбросил предмет — запись больше не нужна.</p>
	 *
	 * @return сколько записей убрано
	 */
	public int forgetBurntWithoutItem() {
		List<Artifact> gone = new ArrayList<>();
		for (Artifact artifact : artifacts) {
			if (artifact.inactive && !artifact.present) {
				gone.add(artifact);
			}
		}
		if (!gone.isEmpty()) {
			artifacts.removeAll(gone);
		}
		return gone.size();
	}

	/** Топ зрителей по артефактам (для {@code /artifact stats}). */
	public List<Map.Entry<String, Integer>> topViewers(int limit) {
		List<Map.Entry<String, Integer>> entries = new ArrayList<>(byViewer.entrySet());
		entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
		return entries.size() > limit ? entries.subList(0, limit) : entries;
	}
}
