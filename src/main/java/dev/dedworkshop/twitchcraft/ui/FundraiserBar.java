package dev.dedworkshop.twitchcraft.ui;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.action.FundraiserTracker;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.module.Module;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Полосы сборов средств в стиле ванильного боссбара: те же спрайты (boss_bar/*), та же ширина (182 px),
 * тот же отступ (12 px сверху) и шаг (19 px), что и у настоящих боссов. Рисуется сразу после ванильных
 * боссбаров; если они есть на экране одновременно — сдвинь полосу настройкой fundraiserSettings.y.
 *
 * Прячется вместе с интерфейсом (F1) и на экране отладки (F3). Видно только стримеру (и захвату игры).
 */
public class FundraiserBar implements HudElement {
	private static final int BAR_WIDTH = 182;
	private static final int BAR_HEIGHT = 5;
	private static final int STEP = 19;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int FLASH = 0xFFFFFF55;

	private final TwitchCraftClient mod;
	private final Map<String, Identifier> sprites = new HashMap<>();
	/** Текущее (анимированное) заполнение по имени сбора. */
	private final Map<String, Float> shown = new HashMap<>();
	private long lastFrame;
	private boolean failed;

	public FundraiserBar(TwitchCraftClient mod) {
		this.mod = mod;
	}

	public static void register(TwitchCraftClient mod) {
		Identifier id = Identifier.fromNamespaceAndPath(TwitchCraftClient.MOD_ID, "fundraisers");
		FundraiserBar element = new FundraiserBar(mod);
		try {
			HudElementRegistry.attachElementAfter(VanillaHudElements.BOSS_BAR, id, element);
		} catch (Exception e) {
			// другой мод убрал ванильный элемент боссбара — рисуем в конце
			HudElementRegistry.addLast(id, element);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
		if (failed) {
			return;
		}
		try {
			render(g);
		} catch (Exception e) {
			failed = true;
			TwitchCraftClient.LOGGER.error("Ошибка отрисовки полосы сбора TwitchCraft — полоса отключена до перезапуска", e);
		}
	}

	private void render(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		ModConfig config = mod.config();
		FundraiserTracker tracker = mod.fundraisers();
		if (config == null || tracker == null || !config.isEnabled(Module.FUNDRAISERS)) {
			return;
		}
		if (mc.player == null || mc.gui == null || mc.gui.hud.isHidden() || mc.getDebugOverlay().showDebugScreen()) {
			return;
		}
		List<FundraiserTracker.Line> lines = tracker.visibleLines();
		if (lines.isEmpty()) {
			shown.clear();
			return;
		}
		ModConfig.FundraiserSettings settings = config.fundraiserSettings == null ? new ModConfig.FundraiserSettings() : config.fundraiserSettings;
		long now = System.currentTimeMillis();
		float dt = lastFrame == 0 ? 0.05f : Math.min(0.5f, (now - lastFrame) / 1000f);
		lastFrame = now;

		int centerX = g.guiWidth() / 2;
		int x = centerX - BAR_WIDTH / 2;
		int y = Math.max(0, settings.y);
		for (FundraiserTracker.Line line : lines) {
			ModConfig.Fundraiser fund = line.fund();
			String key = fund.name.toLowerCase(Locale.ROOT);
			float target = line.fraction();
			float value = target;
			if (settings.animate) {
				Float previous = shown.get(key);
				float current = previous == null ? target : previous;
				current += (target - current) * Math.min(1f, dt * 4f);
				if (Math.abs(target - current) < 0.002f) {
					current = target;
				}
				shown.put(key, current);
				value = current;
			}
			drawBar(g, x, y, fund, value);

			Component label = Component.literal(line.label());
			int textWidth = mc.font.width(label);
			g.text(mc.font, label, centerX - textWidth / 2, y - 9, WHITE, true);

			FundraiserTracker.Progress p = line.progress();
			if (settings.lastContributionSeconds > 0 && p.lastAt > 0 && p.lastAmount > 0
					&& now - p.lastAt < settings.lastContributionSeconds * 1000L) {
				String who = p.lastUser == null || p.lastUser.isBlank() ? "" : " · " + p.lastUser;
				String flash = "+" + FundraiserTracker.formatAmount(p.lastAmount) + " " + line.symbol() + who;
				g.text(mc.font, flash, x + BAR_WIDTH + 5, y - 2, FLASH, true);
			}

			y += STEP;
			if (y >= g.guiHeight() / 3) {
				break; // как у ванильных боссбаров: не занимаем больше трети экрана
			}
		}
	}

	private void drawBar(GuiGraphicsExtractor g, int x, int y, ModConfig.Fundraiser fund, float fraction) {
		String color = ModConfig.FUND_COLORS.contains(fund.color) ? fund.color : "pink";
		String style = ModConfig.FUND_STYLES.contains(fund.style) ? fund.style : "notched_10";
		boolean notched = !"progress".equals(style);

		g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite("boss_bar/" + color + "_background"),
				BAR_WIDTH, BAR_HEIGHT, 0, 0, x, y, BAR_WIDTH, BAR_HEIGHT);
		if (notched) {
			g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite("boss_bar/" + style + "_background"),
					BAR_WIDTH, BAR_HEIGHT, 0, 0, x, y, BAR_WIDTH, BAR_HEIGHT);
		}
		int width = Mth.lerpDiscrete(Math.max(0f, Math.min(1f, fraction)), 0, BAR_WIDTH);
		if (width > 0) {
			g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite("boss_bar/" + color + "_progress"),
					BAR_WIDTH, BAR_HEIGHT, 0, 0, x, y, width, BAR_HEIGHT);
			if (notched) {
				g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite("boss_bar/" + style + "_progress"),
						BAR_WIDTH, BAR_HEIGHT, 0, 0, x, y, width, BAR_HEIGHT);
			}
		}
	}

	private Identifier sprite(String path) {
		return sprites.computeIfAbsent(path, Identifier::withDefaultNamespace);
	}
}
