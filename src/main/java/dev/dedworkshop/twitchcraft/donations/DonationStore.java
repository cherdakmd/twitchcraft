package dev.dedworkshop.twitchcraft.donations;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Секреты и служебные данные сервисов донатов: config/twitchcraft-donations.json.
 * Хранится отдельно от основного конфига, чтобы его можно было спокойно показывать и копировать.
 */
public class DonationStore {
	public static final String FILE_NAME = "twitchcraft-donations.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int SEEN_LIMIT = 300;

	// DonationAlerts
	public volatile String daAccessToken = "";
	public volatile String daUserId = "";
	public volatile String daUserName = "";

	// DonatePay
	public volatile String dpApiKey = "";
	public volatile String dpUserId = "";
	public volatile String dpUserName = "";
	/** id последней обработанной транзакции DonatePay (чтобы после перезапуска не повторять старые донаты). */
	public volatile long dpCursor = 0;
	/** Недавно обработанные id DonatePay — защита от повторов. */
	public volatile List<Long> dpSeen = new ArrayList<>();

	private transient Path path;

	public boolean hasDonationAlerts() {
		return daAccessToken != null && !daAccessToken.isBlank();
	}

	public boolean hasDonatePay() {
		return dpApiKey != null && !dpApiKey.isBlank();
	}

	public synchronized void clearDonationAlerts() {
		daAccessToken = "";
		daUserId = "";
		daUserName = "";
		save();
	}

	public synchronized void clearDonatePay() {
		dpApiKey = "";
		dpUserId = "";
		dpUserName = "";
		dpCursor = 0;
		dpSeen = new ArrayList<>();
		save();
	}

	public synchronized boolean dpWasSeen(long id) {
		return dpSeen != null && dpSeen.contains(id);
	}

	public synchronized void dpMarkSeen(long id) {
		if (dpSeen == null) {
			dpSeen = new ArrayList<>();
		}
		if (!dpSeen.contains(id)) {
			dpSeen.add(id);
			while (dpSeen.size() > SEEN_LIMIT) {
				dpSeen.remove(0);
			}
		}
	}

	public static Path defaultPath() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	public static DonationStore load() {
		return load(defaultPath());
	}

	public static DonationStore load(Path path) {
		DonationStore store = null;
		if (Files.exists(path)) {
			try {
				store = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), DonationStore.class);
			} catch (Exception e) {
				TwitchCraftClient.LOGGER.error("Не удалось прочитать файл {}", path, e);
			}
		}
		if (store == null) {
			store = new DonationStore();
		}
		if (store.dpSeen == null) store.dpSeen = new ArrayList<>();
		if (store.daAccessToken == null) store.daAccessToken = "";
		if (store.daUserId == null) store.daUserId = "";
		if (store.daUserName == null) store.daUserName = "";
		if (store.dpApiKey == null) store.dpApiKey = "";
		if (store.dpUserId == null) store.dpUserId = "";
		if (store.dpUserName == null) store.dpUserName = "";
		store.path = path;
		return store;
	}

	public synchronized void save() {
		if (path == null) {
			return; // хранилище в памяти (тесты)
		}
		try {
			dev.dedworkshop.twitchcraft.util.SafeFiles.writeAtomic(path, GSON.toJson(this), true);
		} catch (IOException e) {
			TwitchCraftClient.LOGGER.error("Не удалось сохранить файл {}", path, e);
		}
	}
}
