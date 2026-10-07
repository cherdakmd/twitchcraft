package dev.dedworkshop.twitchcraft.youtube;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.util.SafeFiles;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Секреты YouTube OAuth: access/refresh-токены и профиль канала.
 * Файл config/twitchcraft-youtube.json — не показывай его на стриме и никому не отправляй.
 */
public class YoutubeStore {
	public static final String FILE_NAME = "twitchcraft-youtube.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public volatile String accessToken = "";
	public volatile String refreshToken = "";
	/** Момент истечения access-токена, мс (0 — неизвестно). */
	public volatile long expiresAt;
	/** OAuth scopes, выданные Google (разделены пробелами). */
	public volatile String scopes = "";
	public volatile String channelId = "";
	public volatile String channelTitle = "";
	/** Учёт дневной квоты YouTube Data API: Client ID проекта, к которому относится счётчик. */
	public volatile String quotaClientId = "";
	/** Ключ квотных суток (дата в тихоокеанской зоне, когда Google обнуляет расход). */
	public volatile String quotaDay = "";
	/** Израсходовано единиц за текущие квотные сутки (оценка мода). */
	public volatile int quotaUnits;

	private transient Path path;

	public boolean hasTokens() {
		return accessToken != null && !accessToken.isBlank();
	}

	public boolean hasRefreshToken() {
		return refreshToken != null && !refreshToken.isBlank();
	}

	public boolean hasScope(String scope) {
		if (scopes == null || scope == null || scope.isBlank()) {
			return false;
		}
		String wanted = scope.trim().toLowerCase(Locale.ROOT);
		for (String granted : scopes.trim().split("\\s+")) {
			if (granted.equalsIgnoreCase(wanted)) {
				return true;
			}
		}
		return false;
	}

	public boolean canReadChat() {
		return hasScope(YoutubeApi.SCOPE_READ) || canWriteChat();
	}

	public boolean canWriteChat() {
		return hasScope(YoutubeApi.SCOPE_WRITE) || hasScope(YoutubeApi.SCOPE_MANAGE);
	}

	public boolean expiresSoon() {
		return expiresAt > 0 && System.currentTimeMillis() >= expiresAt - 60_000;
	}

	/** Update/refresh the current account, preserving its refresh token if Google omits it. */
	public synchronized void setTokens(String access, String refresh, long expiresInSeconds, String grantedScopes) {
		updateTokens(access, refresh, expiresInSeconds, grantedScopes, true);
	}

	/** Replace credentials after a fresh OAuth login; never carry a refresh token across accounts. */
	public synchronized void replaceTokens(String access, String refresh, long expiresInSeconds, String grantedScopes) {
		updateTokens(access, refresh, expiresInSeconds, grantedScopes, false);
	}

	private void updateTokens(String access, String refresh, long expiresInSeconds, String grantedScopes, boolean preserveMissingRefresh) {
		accessToken = access == null ? "" : access;
		if (refresh != null && !refresh.isBlank()) {
			refreshToken = refresh;
		} else if (!preserveMissingRefresh) {
			refreshToken = "";
		}
		expiresAt = expiresInSeconds > 0 ? System.currentTimeMillis() + expiresInSeconds * 1000L : 0;
		if (grantedScopes != null && !grantedScopes.isBlank()) {
			scopes = grantedScopes.trim();
		}
		save();
	}

	public synchronized void clearTokens() {
		accessToken = "";
		refreshToken = "";
		expiresAt = 0;
		scopes = "";
		channelId = "";
		channelTitle = "";
		save();
	}

	public static Path defaultPath() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	public static YoutubeStore load() {
		return load(defaultPath());
	}

	public static YoutubeStore load(Path path) {
		YoutubeStore store = null;
		if (Files.exists(path)) {
			try {
				store = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), YoutubeStore.class);
			} catch (Exception e) {
				TwitchCraftClient.LOGGER.error("Не удалось прочитать файл {}", path, e);
			}
		}
		if (store == null) {
			store = new YoutubeStore();
		}
		if (store.accessToken == null) store.accessToken = "";
		if (store.refreshToken == null) store.refreshToken = "";
		if (store.scopes == null) store.scopes = "";
		if (store.channelId == null) store.channelId = "";
		if (store.channelTitle == null) store.channelTitle = "";
		if (store.quotaClientId == null) store.quotaClientId = "";
		if (store.quotaDay == null) store.quotaDay = "";
		if (store.quotaUnits < 0) store.quotaUnits = 0;
		store.path = path;
		return store;
	}

	/** Записать оценку дневного расхода квоты (не чаще раза в минуту — см. YoutubeLive). */
	public synchronized void saveQuota(String forClientId, String forDay, int units) {
		quotaClientId = forClientId == null ? "" : forClientId;
		quotaDay = forDay == null ? "" : forDay;
		quotaUnits = Math.max(0, units);
		save();
	}

	public synchronized void save() {
		if (path == null) {
			return; // хранилище в памяти (тесты)
		}
		try {
			SafeFiles.writeAtomic(path, GSON.toJson(this), true);
		} catch (IOException e) {
			TwitchCraftClient.LOGGER.error("Не удалось сохранить файл {}", path, e);
		}
	}
}
