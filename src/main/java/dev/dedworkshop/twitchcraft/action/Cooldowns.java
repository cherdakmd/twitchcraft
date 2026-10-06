package dev.dedworkshop.twitchcraft.action;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Кулдауны действий: общий (на действие) и персональный (действие + зритель).
 * Используется только из основного потока игры.
 */
public class Cooldowns {
	private final Map<String, Long> global = new HashMap<>();
	private final Map<String, Long> perUser = new HashMap<>();

	/**
	 * Сколько секунд осталось ждать. 0 — можно выполнять.
	 */
	public int remaining(String key, String userLogin, int cooldownSeconds, int userCooldownSeconds) {
		long now = System.currentTimeMillis();
		long wait = 0;
		if (cooldownSeconds > 0) {
			Long until = global.get(key);
			if (until != null && until > now) {
				wait = Math.max(wait, until - now);
			}
		}
		if (userCooldownSeconds > 0 && userLogin != null && !userLogin.isBlank()) {
			Long until = perUser.get(userKey(key, userLogin));
			if (until != null && until > now) {
				wait = Math.max(wait, until - now);
			}
		}
		return (int) Math.ceil(wait / 1000.0);
	}

	/** Запомнить момент срабатывания. */
	public void mark(String key, String userLogin, int cooldownSeconds, int userCooldownSeconds) {
		long now = System.currentTimeMillis();
		if (cooldownSeconds > 0) {
			global.put(key, now + cooldownSeconds * 1000L);
		}
		if (userCooldownSeconds > 0 && userLogin != null && !userLogin.isBlank()) {
			perUser.put(userKey(key, userLogin), now + userCooldownSeconds * 1000L);
		}
		// Чтобы карты не росли бесконечно
		if (perUser.size() > 5000) {
			perUser.entrySet().removeIf(entry -> entry.getValue() <= now);
		}
	}

	public void clear() {
		global.clear();
		perUser.clear();
	}

	private static String userKey(String key, String userLogin) {
		return key + "@" + userLogin.toLowerCase(Locale.ROOT);
	}
}
