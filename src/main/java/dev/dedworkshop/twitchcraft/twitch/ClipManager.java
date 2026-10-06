package dev.dedworkshop.twitchcraft.twitch;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.action.Placeholders;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.util.Chat;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Клипы и метки стрима Twitch: по смерти, крупному донату, боссу и вручную (F10, /twitch clip).
 * <p>
 * Метка (stream marker) видна только стримеру в менеджере видео — удобно для монтажа; клип — публичный,
 * Twitch делает его асинхронно (~15 с), после чего ссылка публикуется в чаты. Оба запроса бессмысленны вне
 * эфира: Twitch отвечает 404 — такие ошибки показываем в игре не чаще раза в 5 минут.
 */
public class ClipManager {
	public static final String SCOPE_CLIPS = "clips:edit";
	public static final String SCOPE_MARKERS = "channel:manage:broadcast";
	private static final long OFFLINE_NOTE_INTERVAL_MS = 5 * 60_000;
	private static final int CLIP_CHECK_DELAY_SECONDS = 15;
	private static final int CLIP_CHECK_ATTEMPTS = 4;

	/** Что сделал менеджер по событию (для тестов и статуса). */
	public record Outcome(boolean marker, boolean clip) {
		public static final Outcome NOTHING = new Outcome(false, false);
	}

	private final TwitchCraftClient mod;
	private volatile long lastClipAt;
	private volatile long lastMarkerAt;
	private volatile long lastOfflineNoteAt;
	private volatile int clipsCreated;
	private volatile int markersCreated;
	private volatile String lastClipUrl = "";
	private volatile String lastError = "";

	public ClipManager(TwitchCraftClient mod) {
		this.mod = mod;
	}

	private ModConfig.Clips settings() {
		ModConfig.Clips clips = mod.config().clips;
		return clips == null ? new ModConfig.Clips() : clips;
	}

	public boolean enabled() {
		return mod.isModuleEnabled(Module.CLIPS);
	}

	public boolean canClip() {
		return mod.tokens() != null && mod.tokens().hasScope(SCOPE_CLIPS) && !mod.tokens().userId.isBlank();
	}

	public boolean canMark() {
		return mod.tokens() != null && mod.tokens().hasScope(SCOPE_MARKERS) && !mod.tokens().userId.isBlank();
	}

	public int clipsCreated() {
		return clipsCreated;
	}

	public int markersCreated() {
		return markersCreated;
	}

	public String lastClipUrl() {
		return lastClipUrl;
	}

	public String lastError() {
		return lastError;
	}

	// ---------- Триггеры ----------

	/** Решение по смерти (без сетевых запросов — для тестов). */
	public Outcome decideDeath() {
		if (!enabled()) {
			return Outcome.NOTHING;
		}
		return new Outcome(settings().markerOnDeath, settings().clipOnDeath);
	}

	/** Решение по донату: сумма от donationFrom (0 — выключено). Тестовые донаты не считаются. */
	public Outcome decideDonation(TwitchEvent event) {
		if (!enabled() || event == null || event.type() != TwitchEvent.Type.DONATION || event.synthetic()) {
			return Outcome.NOTHING;
		}
		ModConfig.Clips c = settings();
		if (c.donationFrom <= 0 || event.amount() < c.donationFrom) {
			return Outcome.NOTHING;
		}
		return new Outcome(c.markerOnDonation, c.clipOnDonation);
	}

	public Outcome decideBoss() {
		if (!enabled()) {
			return Outcome.NOTHING;
		}
		return new Outcome(settings().markerOnBoss, settings().clipOnBoss);
	}

	public void onDeath(String cause) {
		Outcome outcome = decideDeath();
		if (outcome.marker()) {
			marker("Смерть: " + cause, false);
		}
		if (outcome.clip()) {
			clip("смерть", false);
		}
	}

	public void onDonation(TwitchEvent event) {
		Outcome outcome = decideDonation(event);
		if (outcome.marker()) {
			marker("Донат " + event.amount() + " " + event.currencySymbol() + " от " + event.user()
					+ (event.message().isBlank() ? "" : ": " + event.message()), false);
		}
		if (outcome.clip()) {
			clip("донат " + event.amount() + " " + event.currencySymbol() + " от " + event.user(), false);
		}
	}

	public void onBoss(String boss) {
		Outcome outcome = decideBoss();
		if (outcome.marker()) {
			marker("Босс повержен: " + boss, false);
		}
		if (outcome.clip()) {
			clip("босс: " + boss, false);
		}
	}

	// ---------- Метки ----------

	/**
	 * Ставит метку стрима.
	 *
	 * @param verbose показывать все ошибки игроку (ручной вызов)
	 * @return false, если запрос даже не отправлен (нет права, кулдаун, стрим точно не идёт)
	 */
	public boolean marker(String description, boolean verbose) {
		if (!canMark()) {
			if (verbose) {
				Chat.error("Нет права " + SCOPE_MARKERS + " для меток стрима. Выполни §e/twitch logout§c и §e/twitch login§c, чтобы выдать его.");
			}
			return false;
		}
		long now = System.currentTimeMillis();
		if (!verbose && now - lastMarkerAt < settings().markerCooldownSeconds * 1000L) {
			TwitchCraftClient.LOGGER.debug("Метка пропущена (кулдаун): {}", description);
			return false;
		}
		if (!verbose && mod.streamStatus() != null && mod.streamStatus().known() && !mod.streamStatus().live()) {
			noteOffline("метка");
			return false;
		}
		lastMarkerAt = now;
		String text = description == null ? "" : description;
		mod.worker().execute(() -> {
			try {
				TwitchApi.Created result = mod.api().createStreamMarker(mod.tokens().userId, text);
				if (result.ok()) {
					markersCreated++;
					Chat.info("§7Метка стрима поставлена: §f" + text);
				} else {
					lastError = result.error();
					handleFailure("Метка не поставлена", result, verbose);
				}
			} catch (Exception e) {
				lastError = e.toString();
				TwitchCraftClient.LOGGER.warn("Ошибка создания метки стрима", e);
				if (verbose) {
					Chat.error("Метка не поставлена: " + e.getMessage());
				}
			}
		});
		return true;
	}

	// ---------- Клипы ----------

	/**
	 * Создаёт клип (последние ~30 секунд эфира) и, когда он готов, публикует ссылку в чаты.
	 *
	 * @param why     повод — попадает в {why} текста сообщения
	 * @param verbose показывать все ошибки игроку (ручной вызов)
	 */
	public boolean clip(String why, boolean verbose) {
		if (!canClip()) {
			if (verbose) {
				Chat.error("Нет права " + SCOPE_CLIPS + " для клипов. Выполни §e/twitch logout§c и §e/twitch login§c, чтобы выдать его.");
			}
			return false;
		}
		long now = System.currentTimeMillis();
		if (!verbose && now - lastClipAt < settings().clipCooldownSeconds * 1000L) {
			TwitchCraftClient.LOGGER.info("Клип пропущен (не чаще раза в {} с): {}", settings().clipCooldownSeconds, why);
			return false;
		}
		if (!verbose && mod.streamStatus() != null && mod.streamStatus().known() && !mod.streamStatus().live()) {
			noteOffline("клип");
			return false;
		}
		lastClipAt = now;
		String reason = why == null ? "" : why;
		mod.worker().execute(() -> {
			try {
				TwitchApi.Created result = mod.api().createClip(mod.tokens().userId);
				if (!result.ok()) {
					lastError = result.error();
					handleFailure("Клип не создан", result, verbose);
					return;
				}
				clipsCreated++;
				Chat.info("§7Клип создаётся (" + reason + ")… ссылка появится через ~" + CLIP_CHECK_DELAY_SECONDS + " с.");
				scheduleCheck(result.id(), result.editUrl(), reason, 1);
			} catch (Exception e) {
				lastError = e.toString();
				TwitchCraftClient.LOGGER.warn("Ошибка создания клипа", e);
				if (verbose) {
					Chat.error("Клип не создан: " + e.getMessage());
				}
			}
		});
		return true;
	}

	private void scheduleCheck(String clipId, String editUrl, String reason, int attempt) {
		mod.scheduler().schedule(() -> mod.worker().execute(() -> checkClip(clipId, editUrl, reason, attempt)),
				attempt == 1 ? CLIP_CHECK_DELAY_SECONDS : 10, TimeUnit.SECONDS);
	}

	private void checkClip(String clipId, String editUrl, String reason, int attempt) {
		try {
			String url = mod.api().getClipUrl(clipId);
			if (url == null) {
				if (attempt < CLIP_CHECK_ATTEMPTS) {
					scheduleCheck(clipId, editUrl, reason, attempt + 1);
				} else {
					TwitchCraftClient.LOGGER.warn("Клип {} так и не появился в Twitch (возможно, стрим без задержки записи)", clipId);
					Chat.warn("Клип не готов спустя минуту — проверь на twitch.tv/" + mod.tokens().login + "/clips");
				}
				return;
			}
			lastClipUrl = url;
			publish(url, editUrl, reason);
		} catch (Exception e) {
			TwitchCraftClient.LOGGER.warn("Не удалось проверить клип {}", clipId, e);
		}
	}

	private void publish(String url, String editUrl, String reason) {
		Component line = Component.literal("§7Клип готов: ").append(link(url, url));
		if (editUrl != null && !editUrl.isBlank()) {
			line = line.copy().append(Component.literal(" §8(")).append(link("обрезать", editUrl)).append(Component.literal("§8)"));
		}
		Chat.send(line);
		ModConfig.Clips c = settings();
		String text = c.clipChatText == null || c.clipChatText.isBlank() ? "{clip_url}" : c.clipChatText;
		Map<String, String> vars = new HashMap<>(mod.globalPlaceholders());
		vars.put("clip_url", url);
		vars.put("why", reason);
		mod.announce(Placeholders.apply(text, vars), c.postClipToTwitch, c.postClipToVk, false);
	}

	// ---------- Ошибки ----------

	private void handleFailure(String what, TwitchApi.Created result, boolean verbose) {
		if (result.status() == 404) {
			if (verbose) {
				Chat.warn(what + ": Twitch говорит, что стрим сейчас не идёт (404).");
			} else {
				noteOffline(what.toLowerCase(java.util.Locale.ROOT));
			}
			return;
		}
		TwitchCraftClient.LOGGER.warn("{}: {}", what, result.error());
		if (verbose || result.status() == 401 || result.status() == 403) {
			Chat.warn(what + ": " + result.error());
		}
	}

	private void noteOffline(String what) {
		long now = System.currentTimeMillis();
		if (now - lastOfflineNoteAt > OFFLINE_NOTE_INTERVAL_MS) {
			lastOfflineNoteAt = now;
			Chat.info("§7" + capitalize(what) + " не создаётся: стрим не в эфире.");
		}
	}

	private static String capitalize(String text) {
		return text == null || text.isEmpty() ? "" : Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}

	private static Component link(String text, String url) {
		return Component.literal(text).withStyle(style -> style
				.withColor(ChatFormatting.AQUA)
				.withUnderlined(true)
				.withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Открыть в браузере"))));
	}
}
