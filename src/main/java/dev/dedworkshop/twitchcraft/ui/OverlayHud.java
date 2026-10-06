package dev.dedworkshop.twitchcraft.ui;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.action.GoalTracker;
import dev.dedworkshop.twitchcraft.action.SessionStats;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Небольшой оверлей в углу экрана: статус подключения, статистика сессии
 * и последние события. Переключается клавишей F7 или командой /twitch overlay.
 */
public class OverlayHud implements HudElement {
	private static final int BG = 0x80000000;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFAAAAAA;
	private static final int PURPLE = 0xFFB46CFF;
	private static final int GREEN = 0xFF55FF55;
	private static final int RED = 0xFFFF5555;
	private static final int YELLOW = 0xFFFFFF55;
	private static final int GOLD = 0xFFFFAA00;
	private static final int LINE = 10;
	private static final int PAD = 3;

	private final TwitchCraftClient mod;

	public OverlayHud(TwitchCraftClient mod) {
		this.mod = mod;
	}

	public static void register(TwitchCraftClient mod) {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(TwitchCraftClient.MOD_ID, "overlay"), new OverlayHud(mod));
	}

	private boolean failed;

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
		if (failed) {
			return; // один раз упали — больше не рисуем до перезапуска, чтобы не спамить ошибкой каждый кадр
		}
		try {
			render(g);
		} catch (Exception e) {
			failed = true;
			TwitchCraftClient.LOGGER.error("Ошибка отрисовки оверлея TwitchCraft — оверлей отключён до перезапуска", e);
		}
	}

	private void render(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		if (!mod.isOverlayVisible() || mc.player == null || mc.gui == null) {
			return;
		}
		if (mc.gui.hud.isHidden() || mc.getDebugOverlay().showDebugScreen()) {
			return;
		}
		ModConfig.Overlay settings = mod.config().overlay;
		List<Line> lines = buildLines(settings);
		if (lines.isEmpty()) {
			return;
		}

		int width = 0;
		for (Line line : lines) {
			width = Math.max(width, mc.font.width(line.text));
		}
		width += PAD * 2;
		int height = lines.size() * LINE + PAD * 2 - 1;

		String corner = settings.corner == null ? "top-left" : settings.corner.toLowerCase(Locale.ROOT);
		int margin = Math.max(0, settings.margin);
		int x = corner.endsWith("right") ? g.guiWidth() - width - margin : margin;
		int y = corner.startsWith("bottom") ? g.guiHeight() - height - margin - 40 : margin;

		g.fill(x, y, x + width, y + height, BG);
		int ty = y + PAD;
		for (Line line : lines) {
			g.text(mc.font, line.text, x + PAD, ty, line.color, true);
			ty += LINE;
		}
	}

	private record Line(String text, int color) {
	}

	private List<Line> buildLines(ModConfig.Overlay settings) {
		List<Line> lines = new ArrayList<>();
		boolean connected = mod.eventSub().isActive();
		boolean paused = mod.events().isPaused();
		int queued = mod.events().queueSize();

		String status;
		int color;
		if (paused) {
			status = "Twitch ‖ пауза";
			color = YELLOW;
		} else if (connected) {
			status = "Twitch ● онлайн";
			color = GREEN;
		} else {
			status = "Twitch ○ оффлайн";
			color = RED;
		}
		if (queued > 0) {
			status += "  очередь: " + queued;
		}
		String donationMark = mod.donations().overlayMark();
		if (!donationMark.isEmpty()) {
			status += "  " + donationMark;
		}
		String vkMark = mod.vk().overlayMark();
		if (!vkMark.isEmpty()) {
			status += "  " + vkMark;
		}
		lines.add(new Line(status, color));

		SessionStats stats = mod.events().stats();
		if (settings.showStats) {
			lines.add(new Line(stats.summaryLine(TwitchEvent.currencySymbol(mod.config().donations.currency)), GRAY));
		}

		if (settings.showGoals && mod.config().isEnabled(Module.GOALS) && mod.goalTracker() != null) {
			for (GoalTracker.Line goal : mod.goalTracker().lines()) {
				lines.add(new Line("◎ " + trim(goal.text(), 40), GOLD));
			}
		}

		int max = Math.max(0, settings.maxEvents);
		if (max > 0) {
			long cutoff = settings.eventSeconds > 0 ? System.currentTimeMillis() - settings.eventSeconds * 1000L : Long.MIN_VALUE;
			for (SessionStats.Entry entry : stats.recent(max)) {
				if (entry.time() < cutoff) {
					continue;
				}
				TwitchEvent event = entry.event();
				String text = trim(event.shortText(), 40);
				lines.add(new Line(text, event.type() == TwitchEvent.Type.REWARD ? PURPLE : WHITE));
			}
		}
		return lines;
	}

	private static String trim(String text, int max) {
		String clean = text.replace("§", "");
		return clean.length() > max ? clean.substring(0, max - 1) + "…" : clean;
	}
}
