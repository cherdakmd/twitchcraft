package dev.dedworkshop.twitchcraft.vk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.util.SafeFiles;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Секреты VK Video Live: секрет приложения, access/refresh-токены и данные своего профиля.
 * Файл config/twitchcraft-vk.json — не показывай его на стриме и никому не отправляй.
 */
public class VkStore {
	public static final String FILE_NAME = "twitchcraft-vk.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Секрет приложения с dev.live.vkvideo.ru/apps (нужен для обмена кода на токен и продления токена). */
	public volatile String clientSecret = "";
	public volatile String accessToken = "";
	public volatile String refreshToken = "";
	/** Момент истечения access-токена, мс (0 — неизвестно). */
	public volatile long expiresAt = 0;
	/** Какие права были выданы при входе (через запятую). */
	public volatile String scopes = "";
	/** Профиль владельца токена. */
	public volatile String userId = "";
	public volatile String userNick = "";
	/** Имя своего канала (channel.url из /v1/current_user). */
	public volatile String ownChannelUrl = "";

	private transient Path path;

	public boolean hasTokens() {
		return accessToken != null && !accessToken.isBlank();
	}

	public boolean hasSecret() {
		return clientSecret != null && !clientSecret.isBlank();
	}

	public boolean hasScope(String scope) {
		if (scopes == null || scope == null) {
			return false;
		}
		for (String s : scopes.split("[,\\s]+")) {
			if (s.equalsIgnoreCase(scope)) {
				return true;
			}
		}
		return false;
	}

	/** Токен истекает в ближайшую минуту (или уже истёк). */
	public boolean expiresSoon() {
		return expiresAt > 0 && System.currentTimeMillis() > expiresAt - 60_000;
	}

	public synchronized void setTokens(String access, String refresh, long expiresInSeconds, String grantedScopes) {
		accessToken = access == null ? "" : access;
		if (refresh != null && !refresh.isBlank()) {
			refreshToken = refresh; // при продлении VK может не вернуть новый refresh-токен — тогда остаётся старый
		}
		expiresAt = expiresInSeconds > 0 ? System.currentTimeMillis() + expiresInSeconds * 1000 : 0;
		if (grantedScopes != null && !grantedScopes.isBlank()) {
			scopes = grantedScopes;
		}
		save();
	}

	/** Удаляет токены и профиль, но оставляет секрет приложения (чтобы войти снова без повторного ввода). */
	public synchronized void clearTokens() {
		accessToken = "";
		refreshToken = "";
		expiresAt = 0;
		scopes = "";
		userId = "";
		userNick = "";
		ownChannelUrl = "";
		save();
	}

	public static Path defaultPath() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	public static VkStore load() {
		return load(defaultPath());
	}

	public static VkStore load(Path path) {
		VkStore store = null;
		if (Files.exists(path)) {
			try {
				store = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), VkStore.class);
			} catch (Exception e) {
				TwitchCraftClient.LOGGER.error("Не удалось прочитать файл {}", path, e);
			}
		}
		if (store == null) {
			store = new VkStore();
		}
		if (store.clientSecret == null) store.clientSecret = "";
		if (store.accessToken == null) store.accessToken = "";
		if (store.refreshToken == null) store.refreshToken = "";
		if (store.scopes == null) store.scopes = "";
		if (store.userId == null) store.userId = "";
		if (store.userNick == null) store.userNick = "";
		if (store.ownChannelUrl == null) store.ownChannelUrl = "";
		store.path = path;
		return store;
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
