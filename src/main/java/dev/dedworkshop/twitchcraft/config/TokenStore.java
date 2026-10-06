package dev.dedworkshop.twitchcraft.config;

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
 * Хранилище токенов Twitch. Лежит отдельно от основного конфига
 * в файле .minecraft/config/twitchcraft-tokens.json.
 *
 * ВНИМАНИЕ: этот файл — секретный. Не показывай его на стриме и никому не отправляй!
 *
 * Поля volatile: к ним обращаются из нескольких потоков (игра, сеть, планировщик).
 */
public class TokenStore {
	public static final String FILE_NAME = "twitchcraft-tokens.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public volatile String accessToken = "";
	public volatile String refreshToken = "";
	public volatile String userId = "";
	public volatile String login = "";
	public volatile String displayName = "";
	/** Время истечения access-токена (миллисекунды эпохи). */
	public volatile long expiresAt = 0;
	/** Права, которые реально есть у токена (заполняется при проверке). */
	public volatile List<String> scopes = new ArrayList<>();

	public boolean hasTokens() {
		return accessToken != null && !accessToken.isBlank();
	}

	public boolean hasScope(String scope) {
		List<String> current = scopes;
		return current != null && current.contains(scope);
	}

	/** Сколько секунд осталось до истечения токена (может быть отрицательным). */
	public long secondsLeft() {
		return (expiresAt - System.currentTimeMillis()) / 1000;
	}

	public String displayOrLogin() {
		if (displayName != null && !displayName.isBlank()) {
			return displayName;
		}
		return login == null ? "" : login;
	}

	public synchronized void clear() {
		accessToken = "";
		refreshToken = "";
		userId = "";
		login = "";
		displayName = "";
		expiresAt = 0;
		scopes = new ArrayList<>();
		save();
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	public static TokenStore load() {
		Path path = path();
		if (Files.exists(path)) {
			try {
				TokenStore store = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), TokenStore.class);
				if (store != null) {
					if (store.scopes == null) {
						store.scopes = new ArrayList<>();
					}
					return store;
				}
			} catch (Exception e) {
				TwitchCraftClient.LOGGER.error("Не удалось прочитать файл токенов {}", path, e);
			}
		}
		return new TokenStore();
	}

	public synchronized void save() {
		try {
			dev.dedworkshop.twitchcraft.util.SafeFiles.writeAtomic(path(), GSON.toJson(this), true);
		} catch (IOException e) {
			TwitchCraftClient.LOGGER.error("Не удалось сохранить файл токенов", e);
		}
	}
}
