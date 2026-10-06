package dev.dedworkshop.twitchcraft.twitch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.util.Chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Управление наградами за баллы канала через Twitch API:
 *  - создание наград из конфига одной командой (/twitch rewards sync);
 *  - подтверждение (FULFILLED) после выполнения действия;
 *  - возврат баллов (CANCELED), если событие не удалось выполнить.
 *
 * Важно: Twitch разрешает менять статус активации только у наград, которые созданы
 * этим же приложением (Client ID). Для «чужих» наград API вернёт 403 — мы это запоминаем
 * и больше не пытаемся.
 */
public class RewardManager {
	public static final String SCOPE = "channel:manage:redemptions";

	private final TwitchCraftClient mod;
	/** Награды, которые нельзя менять (созданы не нашим приложением). */
	private final Set<String> unmanageable = ConcurrentHashMap.newKeySet();

	public RewardManager(TwitchCraftClient mod) {
		this.mod = mod;
	}

	public boolean available() {
		return mod.config().isEnabled(Module.REWARD_MANAGEMENT) && mod.tokens().hasScope(SCOPE) && !mod.tokens().userId.isBlank();
	}

	/** Отметить активацию выполненной (Twitch: FULFILLED; VK Video Live: accept запроса награды). */
	public void fulfill(TwitchEvent event) {
		if (event.isVk()) {
			mod.vk().acceptDemand(event);
			return;
		}
		if (mod.config().rewardsSettings.autoFulfill) {
			update(event, "FULFILLED");
		}
	}

	/** Вернуть зрителю баллы (Twitch: CANCELED; VK Video Live: reject запроса награды). */
	public void refund(TwitchEvent event, String reason) {
		if (event.isVk()) {
			mod.vk().rejectDemand(event, reason);
			return;
		}
		if (mod.config().rewardsSettings.autoRefund && update(event, "CANCELED")) {
			TwitchCraftClient.LOGGER.info("Возврат баллов {} за «{}»: {}", event.user(), event.reward(), reason);
		}
	}

	private boolean update(TwitchEvent event, String status) {
		if (event.synthetic() || event.type() != TwitchEvent.Type.REWARD) {
			return false;
		}
		if (event.redemptionId().isBlank() || event.rewardId().isBlank() || !available()) {
			return false;
		}
		if (unmanageable.contains(event.rewardId())) {
			return false;
		}
		mod.worker().execute(() -> {
			if (unmanageable.contains(event.rewardId())) {
				return; // пока задача ждала очереди, выяснилось, что награда чужая
			}
			try {
				int code = mod.api().updateRedemptionStatus(mod.tokens().userId, event.rewardId(), event.redemptionId(), status);
				if (code == 403) {
					unmanageable.add(event.rewardId());
					TwitchCraftClient.LOGGER.info("Награда «{}» создана не этим приложением — статус активаций менять нельзя. "
							+ "Создай её через /twitch rewards sync, чтобы работали возврат и подтверждение.", event.reward());
				}
			} catch (Exception e) {
				TwitchCraftClient.LOGGER.warn("Ошибка изменения статуса награды: {}", e.toString());
			}
		});
		return true;
	}

	/** Создаёт на Twitch награды из конфига, которых там ещё нет. */
	public void sync() {
		if (!available()) {
			Chat.error("Нет права " + SCOPE + ". Выполни §e/twitch logout§c и §e/twitch login§c, чтобы выдать его.");
			return;
		}
		Chat.info("Сверяю награды с Twitch...");
		mod.worker().execute(() -> {
			try {
				JsonArray existing = mod.api().listCustomRewards(mod.tokens().userId);
				Set<String> existingTitles = ConcurrentHashMap.newKeySet();
				for (JsonElement element : existing) {
					if (element.isJsonObject() && element.getAsJsonObject().has("title")) {
						existingTitles.add(element.getAsJsonObject().get("title").getAsString().trim().toLowerCase(Locale.ROOT));
					}
				}

				List<String> created = new ArrayList<>();
				List<String> skipped = new ArrayList<>();
				List<String> failed = new ArrayList<>();
				Map<String, ModConfig.Action> rewards = mod.config().rewards;
				if (rewards != null) {
					for (Map.Entry<String, ModConfig.Action> entry : rewards.entrySet()) {
						String title = entry.getKey().trim();
						ModConfig.Action action = entry.getValue();
						if (title.equals("*") || title.isEmpty() || action == null) {
							continue;
						}
						if (existingTitles.contains(title.toLowerCase(Locale.ROOT))) {
							skipped.add(title);
							continue;
						}
						String error = mod.api().createCustomReward(mod.tokens().userId, buildReward(title, action));
						if (error == null) {
							created.add(title);
						} else {
							failed.add(title + " (" + error + ")");
						}
					}
				}

				Chat.success("Награды: создано " + created.size() + ", уже было " + skipped.size() + ", ошибок " + failed.size());
				if (!created.isEmpty()) {
					Chat.info("§7Созданы: §d" + String.join("§7, §d", created));
				}
				for (String f : failed) {
					Chat.warn("Не создана: " + f);
				}
				if (!failed.isEmpty()) {
					Chat.warn("Частые причины: канал не аффилиат, лимит 50 наград, повтор названия.");
				}
			} catch (Exception e) {
				TwitchCraftClient.LOGGER.error("Ошибка синхронизации наград", e);
				Chat.error("Ошибка синхронизации наград: " + e.getMessage());
			}
		});
	}

	private JsonObject buildReward(String title, ModConfig.Action action) {
		JsonObject body = new JsonObject();
		body.addProperty("title", title.length() > 45 ? title.substring(0, 45) : title);
		body.addProperty("cost", Math.max(1, action.cost > 0 ? action.cost : mod.config().rewardsSettings.defaultCost));
		if (action.prompt != null && !action.prompt.isBlank()) {
			body.addProperty("prompt", action.prompt.length() > 200 ? action.prompt.substring(0, 200) : action.prompt);
		}
		body.addProperty("is_user_input_required", action.input);
		body.addProperty("is_enabled", true);
		body.addProperty("should_redemptions_skip_request_queue", false);
		if (action.color != null && action.color.matches("#[0-9a-fA-F]{6}")) {
			body.addProperty("background_color", action.color.toUpperCase(Locale.ROOT));
		}
		if (action.cooldown > 0) {
			body.addProperty("is_global_cooldown_enabled", true);
			body.addProperty("global_cooldown_seconds", action.cooldown);
		}
		return body;
	}
}
