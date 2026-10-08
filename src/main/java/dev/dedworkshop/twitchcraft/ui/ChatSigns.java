package dev.dedworkshop.twitchcraft.ui;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Таблички чата «в воздухе»: сообщение появляется перед игроком над центром экрана и остаётся на месте в мире,
 * когда игрок поворачивается. Табличка полностью видна после появления и в конце жизни плавно гаснет.
 *
 * <p>Точка привязки фиксируется при появлении. Каждый кадр она переводится на экран через {@link WorldProjection}.
 * Если между камерой и табличкой стоит блок, табличка не рисуется (проверка лучом раз в {@link #OCCLUSION_EVERY_MS}).
 * Новые таблички встают у базы, старые — над ними. Сверх лимита «сколько сразу» самые старые гаснут быстро.
 *
 * <p>Табличку можно разбить рукой: наведи прицел (центр экрана) на табличку в пределах досягаемости
 * ({@link ChatSignLayout#BREAK_REACH}) и нажми ЛКМ. Клик забирается в начале тика, до обработки кнопок игры, и только
 * если табличка ближе блока или моба за ней. Иначе удар идёт миру как обычно.
 */
public final class ChatSigns implements HudElement {
	private static final ChatSigns INSTANCE = new ChatSigns();
	/** Точка привязки выше центра взгляда, блоки: таблички встают над прицелом. */
	private static final double UP_OFFSET = 0.7;
	/** Ближе блока к игроку табличка не встаёт. */
	private static final double MIN_DISTANCE = 1.0;
	/** Табличка встаёт перед стеной с этим отступом, блоки. */
	private static final double WALL_MARGIN = 0.4;
	/** Затухание в конце жизни, миллисекунды. */
	private static final long FADE_OUT_MS = 1000;
	/** Табличка сверх лимита «сколько сразу» гаснет за это время, миллисекунды. */
	private static final long FORCED_FADE_MS = 300;
	/** Как часто проверять, не закрыл ли блок табличку, миллисекунды. */
	private static final long OCCLUSION_EVERY_MS = 150;
	/** Скорость сглаживания стопки, 1/с. */
	private static final double SMOOTH = 14.0;
	/** Цвета таблички (RGB, прозрачность задаётся отдельно): контур, рамка, доска, блик. */
	private static final int FRAME = 0x140B05;
	private static final int BORDER = 0x6B4A2B;
	/** Рамка таблички под прицелом, когда по ней можно кликнуть. */
	private static final int BORDER_HOT = 0xD9A441;
	private static final int BOARD = 0x2B1A0E;
	private static final int BOARD_ALPHA = 0xD8;
	private static final int SHINE = 0x9C7A4C;
	/** Осколки разбитой таблички: сколько, сколько живут (миллисекунды), притяжение (пикселей/с²). */
	private static final int SHARD_COUNT = 14;
	private static final double SHARD_LIFE_MS = 700;
	private static final double SHARD_GRAVITY = 520;
	private static final int[] SHARD_COLORS = {BOARD, BORDER, SHINE, 0xFFFFFF, 0xAAAAAA};
	/** Звук разбития: стеклянный звук из ванильных. */
	private static final String BREAK_SOUND = "minecraft:block.glass.break";

	private final List<Sign> signs = new ArrayList<>();
	private final List<Shard> shards = new ArrayList<>();
	private final Random random = new Random();
	private Level lastLevel;
	private long lastFrame;
	private boolean failed;
	/** Табличка, которую клик ЛКМ разобьёт: под прицелом на последнем кадре и ближе блока или моба за ней. */
	private Sign breakTarget;

	private ChatSigns() {
	}

	public static ChatSigns instance() {
		return INSTANCE;
	}

	public static void register() {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(TwitchCraftClient.MOD_ID, "chat_signs"), INSTANCE);
		// В начале тика, до обработки кнопок игры: клик по табличке можно забрать себе
		ClientTickEvents.START_CLIENT_TICK.register(client -> INSTANCE.onClientTick());
	}

	/**
	 * Добавляет табличку для сообщения чата. Вызывается в основном потоке игры.
	 *
	 * @return {@code false}, если табличку некуда поставить (нет игрока или мира), — тогда сообщение нужно вывести строкой
	 */
	public boolean add(ModConfig.ChatSignSettings settings, List<ChatSignLayout.Segment> header, String message, int messageArgb) {
		Minecraft mc = Minecraft.getInstance();
		if (settings == null || mc == null || mc.player == null || mc.level == null || mc.font == null
				|| mc.gameRenderer == null) {
			return false;
		}
		Font font = mc.font;
		ChatSignLayout.Layout layout = ChatSignLayout.layout(header, message, messageArgb, s -> font.width(s));
		double distance = Math.max(2, Math.min(30, settings.distance));
		Vec3 anchor = anchorFor(mc, mc.gameRenderer.mainCamera(), distance);
		long now = System.currentTimeMillis();
		signs.add(new Sign(now, now + Math.max(1, settings.seconds) * 1000L, anchor, layout));
		limitActive(settings.maxVisible, now);
		return true;
	}

	/** Сверх лимита самые старые таблички (список идёт от старых к новым) гаснут быстро. */
	private void limitActive(int maxVisible, long now) {
		int limit = Math.max(1, maxVisible);
		int active = 0;
		for (Sign sign : signs) {
			if (!sign.forced) {
				active++;
			}
		}
		for (Sign sign : signs) {
			if (active <= limit) {
				break;
			}
			if (!sign.forced) {
				sign.forced = true;
				sign.fadeMs = FORCED_FADE_MS;
				sign.endAt = Math.min(sign.endAt, now + FORCED_FADE_MS);
				active--;
			}
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
		if (failed) {
			return; // один раз упали — больше не рисуем до перезапуска, чтобы не спамить ошибкой каждый кадр
		}
		try {
			render(g);
		} catch (Exception e) {
			failed = true;
			breakTarget = null;
			TwitchCraftClient.LOGGER.error("Ошибка отрисовки табличек чата — таблички отключены до перезапуска", e);
		}
	}

	/** Клик ЛКМ по табличке под прицелом разбивает её. Вызывается в начале каждого тика. */
	private void onClientTick() {
		if (failed || breakTarget == null) {
			return;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			if (!canInteract(mc) || !signs.contains(breakTarget)) {
				breakTarget = null;
				return;
			}
			if (!mc.options.keyAttack.consumeClick()) {
				return;
			}
			while (mc.options.keyAttack.consumeClick()) {
				// лишние клики этого тика тоже забираем: иначе они ударили бы мир за табличкой
			}
			breakSign(mc, breakTarget);
		} catch (Exception e) {
			failed = true;
			breakTarget = null;
			TwitchCraftClient.LOGGER.error("Ошибка при разбивании табличек — таблички отключены до перезапуска", e);
		}
	}

	/** Можно ли сейчас трогать таблички: игрок в мире, нет открытых экранов, интерфейс и F3 не скрыты. */
	private static boolean canInteract(Minecraft mc) {
		return mc != null && mc.player != null && mc.level != null && mc.gui != null && mc.gui.screen() == null
				&& !mc.gui.hud.isHidden() && !mc.getDebugOverlay().showDebugScreen();
	}

	private void breakSign(Minecraft mc, Sign sign) {
		signs.remove(sign);
		breakTarget = null;
		spawnShards(sign);
		playBreakSound(mc);
		mc.player.swing(InteractionHand.MAIN_HAND, mc.player.getMainHandItem().getAttackAnimation(), false);
	}

	private static void playBreakSound(Minecraft mc) {
		Identifier id = Identifier.tryParse(BREAK_SOUND);
		if (id == null) {
			return;
		}
		Optional<SoundEvent> sound = BuiltInRegistries.SOUND_EVENT.getOptional(id);
		if (sound.isEmpty()) {
			return;
		}
		mc.getSoundManager().play(SimpleSoundInstance.forUI(sound.get(), 1.2f, 0.8f));
	}

	/** Осколки разлетаются из прямоугольника таблички: вверх и в стороны, потом падают. */
	private void spawnShards(Sign sign) {
		double width = sign.right - sign.left;
		double height = sign.bottom - sign.top;
		double centreX = (sign.left + sign.right) / 2.0;
		for (int i = 0; i < SHARD_COUNT; i++) {
			Shard shard = new Shard();
			shard.x = sign.left + random.nextDouble() * width;
			shard.y = sign.top + random.nextDouble() * height;
			shard.vx = (shard.x - centreX) * 1.6 + (random.nextDouble() - 0.5) * 140;
			shard.vy = -(60 + random.nextDouble() * 160);
			shard.size = Math.max(1.0, (2 + random.nextDouble() * 3) * sign.scale);
			shard.rgb = SHARD_COLORS[random.nextInt(SHARD_COLORS.length)];
			shards.add(shard);
		}
	}

	private void stepShards(double dt) {
		for (Shard shard : shards) {
			shard.x += shard.vx * dt;
			shard.y += shard.vy * dt;
			shard.vy += SHARD_GRAVITY * dt;
			shard.age += dt * 1000.0;
		}
		shards.removeIf(shard -> shard.age >= SHARD_LIFE_MS);
	}

	private void drawShards(GuiGraphicsExtractor g) {
		for (Shard shard : shards) {
			int alpha = (int) Math.round(255 * Math.max(0.0, 1.0 - shard.age / SHARD_LIFE_MS));
			int x = (int) Math.round(shard.x);
			int y = (int) Math.round(shard.y);
			int size = Math.max(1, (int) Math.round(shard.size));
			g.fill(x, y, x + size, y + size, argb(alpha, shard.rgb));
		}
	}

	private void render(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		TwitchCraftClient mod = TwitchCraftClient.get();
		ModConfig.ChatSignSettings settings = mod == null || mod.config() == null ? null : mod.config().chatSigns;
		if (settings == null || !settings.enabled || mc.player == null || mc.level == null || mc.gameRenderer == null) {
			clear();
			return;
		}
		if (mc.level != lastLevel) {
			clear(); // другой мир или измерение: старые таблички остаются там, где были
			lastLevel = mc.level;
		}
		long now = System.currentTimeMillis();
		double dt = lastFrame == 0 ? 0.016 : Math.min(0.1, (now - lastFrame) / 1000.0);
		lastFrame = now;
		signs.removeIf(sign -> now >= sign.endAt);
		breakTarget = null;
		if (mc.gui == null || mc.gui.hud.isHidden() || mc.getDebugOverlay().showDebugScreen() || mc.gui.screen() != null) {
			shards.clear(); // F1, F3 и открытые экраны: осколки не ждём
			return;
		}
		stepShards(dt);
		if (!signs.isEmpty()) {
			drawSigns(g, mc, settings, now, dt);
		}
		drawShards(g);
	}

	private void drawSigns(GuiGraphicsExtractor g, Minecraft mc, ModConfig.ChatSignSettings settings, long now, double dt) {
		Camera camera = mc.gameRenderer.mainCamera();
		Vec3 eye = camera.position();
		float pitch = camera.xRot();
		float yaw = camera.yRot();
		double fov = camera.getFov();
		int w = g.guiWidth();
		int h = g.guiHeight();
		double userScale = Math.max(25, Math.min(300, settings.scale)) / 100.0;
		double reference = Math.max(2, Math.min(30, settings.distance));

		for (Sign sign : signs) {
			sign.screen = WorldProjection.project(sign.anchor.x, sign.anchor.y, sign.anchor.z,
					eye.x, eye.y, eye.z, pitch, yaw, fov, w, h);
			if (sign.screen == null) {
				sign.scale = 0;
				continue;
			}
			if (!sign.checked || now - sign.checkedAt >= OCCLUSION_EVERY_MS) {
				sign.occluded = clip(mc, eye, sign.anchor) != null;
				sign.checked = true;
				sign.checkedAt = now;
			}
			sign.scale = userScale * WorldProjection.scaleFor(sign.screen.depth(), reference);
		}

		// Стопка: самая новая у базы, старые над ней. Учитываются только видимые таблички.
		List<Sign> stack = new ArrayList<>();
		for (int i = signs.size() - 1; i >= 0; i--) {
			Sign sign = signs.get(i);
			if (sign.screen != null && !sign.occluded) {
				stack.add(sign);
			}
		}
		double[] heights = new double[stack.size()];
		for (int i = 0; i < heights.length; i++) {
			Sign sign = stack.get(i);
			heights[i] = sign.layout.height() * sign.scale;
		}
		double[] targets = ChatSignLayout.stackOffsets(heights);
		double k = 1 - Math.exp(-SMOOTH * dt);
		for (int i = 0; i < stack.size(); i++) {
			Sign sign = stack.get(i);
			if (!sign.placed) {
				sign.placed = true;
				sign.offset = targets[i] - 6; // новая табличка въезжает снизу
			}
			sign.offset += (targets[i] - sign.offset) * k;
		}

		// Дальние рисуем первыми, чтобы ближние оказались сверху
		List<Sign> drawn = new ArrayList<>(stack);
		drawn.sort(Comparator.comparingDouble((Sign sign) -> sign.screen.depth()).reversed());

		// Границы и прозрачность на этом кадре: по ним ищем табличку под прицелом и рисуем её
		for (Sign sign : drawn) {
			sign.alpha = ChatSignLayout.alpha(now - sign.createdAt, sign.endAt - now, sign.fadeMs);
			double half = sign.layout.width() * sign.scale / 2.0;
			sign.left = sign.screen.x() - half;
			sign.right = sign.screen.x() + half;
			sign.bottom = sign.screen.y() - sign.offset;
			sign.top = sign.bottom - sign.layout.height() * sign.scale;
			sign.onScreen = sign.alpha > 0 && sign.top <= h && sign.bottom >= 0 && sign.right >= 0 && sign.left <= w;
		}

		// Прицел — центр экрана. Под ним ближайшая видимая табличка; её можно разбить, если она в досягаемости
		// и за ней нет блока или моба ближе
		Sign aimed = null;
		for (Sign sign : drawn) {
			if (sign.onScreen && ChatSignLayout.contains(sign.left, sign.top, sign.right, sign.bottom, w / 2.0, h / 2.0)
					&& (aimed == null || sign.screen.depth() < aimed.screen.depth())) {
				aimed = sign;
			}
		}
		if (aimed != null && canInteract(mc)) {
			HitResult solid = mc.hitResult;
			double solidDistance = solid == null || solid.getType() == HitResult.Type.MISS
					? Double.POSITIVE_INFINITY : eye.distanceTo(solid.getLocation());
			if (ChatSignLayout.breakable(aimed.screen.depth(), solidDistance)) {
				breakTarget = aimed;
			}
		}

		for (Sign sign : drawn) {
			if (sign.onScreen) {
				draw(g, mc.font, sign.layout, sign.screen.x(), sign.bottom, sign.scale, sign.alpha, sign == breakTarget);
			}
		}
	}

	/** Рисует табличку: нижний центр в точке (x, bottom), масштаб и прозрачность заданы. hot — подсветить рамку. */
	private static void draw(GuiGraphicsExtractor g, Font font, ChatSignLayout.Layout layout,
	                         double x, double bottom, double scale, int alpha, boolean hot) {
		int w = layout.width();
		int h = layout.height();
		g.pose().pushMatrix();
		try {
			g.pose().translate((float) x, (float) bottom);
			g.pose().scale((float) scale, (float) scale);
			int x0 = -w / 2;
			int y0 = -h;
			g.fill(x0 - 1, y0 - 1, x0 + w + 1, y0 + h + 1, argb(alpha, FRAME));
			g.fill(x0, y0, x0 + w, y0 + h, argb(alpha, hot ? BORDER_HOT : BORDER));
			g.fill(x0 + 1, y0 + 1, x0 + w - 1, y0 + h - 1, argb(alpha * BOARD_ALPHA / 255, BOARD));
			g.fill(x0 + 1, y0 + 1, x0 + w - 1, y0 + 2, argb(alpha, SHINE));
			int ty = y0 + ChatSignLayout.PAD;
			for (ChatSignLayout.Row row : layout.rows()) {
				int tx = x0 + ChatSignLayout.PAD;
				for (ChatSignLayout.Segment segment : row.segments()) {
					g.text(font, segment.text(), tx, ty, argb(alpha, segment.argb()), true);
					tx += font.width(segment.text());
				}
				ty += ChatSignLayout.LINE;
			}
		} finally {
			g.pose().popMatrix();
		}
	}

	private static int argb(int alpha, int rgb) {
		return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0xFFFFFF);
	}

	/** Точка привязки: перед игроком на расстоянии distance над центром взгляда; у стены — ближе к игроку. */
	private static Vec3 anchorFor(Minecraft mc, Camera camera, double distance) {
		Vec3 eye = camera.position();
		double[] forward = WorldProjection.forward(camera.xRot(), camera.yRot());
		double[] up = WorldProjection.up(camera.xRot(), camera.yRot());
		Vec3 anchor = pointAt(eye, forward, up, distance);
		BlockHitResult hit = clip(mc, eye, anchor);
		if (hit != null) {
			double wall = eye.distanceTo(hit.getLocation()) - WALL_MARGIN;
			anchor = pointAt(eye, forward, up, Math.max(MIN_DISTANCE, wall));
		}
		return anchor;
	}

	private static Vec3 pointAt(Vec3 eye, double[] forward, double[] up, double distance) {
		return new Vec3(
				eye.x + forward[0] * distance + up[0] * UP_OFFSET,
				eye.y + forward[1] * distance + up[1] * UP_OFFSET,
				eye.z + forward[2] * distance + up[2] * UP_OFFSET);
	}

	/** Блок на пути от from до to, либо null, если путь свободен. */
	private static BlockHitResult clip(Minecraft mc, Vec3 from, Vec3 to) {
		BlockHitResult hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, mc.player));
		return hit.getType() == HitResult.Type.MISS ? null : hit;
	}

	private void clear() {
		signs.clear();
		shards.clear();
		breakTarget = null;
		lastLevel = null;
	}

	/** Одна табличка: точка в мире, раскладка, срок жизни и состояние для сглаживания. */
	private static final class Sign {
		final long createdAt;
		final Vec3 anchor;
		final ChatSignLayout.Layout layout;
		long endAt;
		long fadeMs = FADE_OUT_MS;
		boolean forced;
		boolean checked;
		long checkedAt;
		boolean occluded;
		boolean placed;
		double offset;
		double scale;
		WorldProjection.Screen screen;
		// Границы на экране и прозрачность на последнем кадре: по ним находим табличку под прицелом
		double left;
		double right;
		double top;
		double bottom;
		int alpha;
		boolean onScreen;

		Sign(long createdAt, long endAt, Vec3 anchor, ChatSignLayout.Layout layout) {
			this.createdAt = createdAt;
			this.endAt = endAt;
			this.anchor = anchor;
			this.layout = layout;
		}
	}

	/** Осколок разбитой таблички: кусочек доски, который разлетается и падает. Координаты — пиксели GUI. */
	private static final class Shard {
		double x;
		double y;
		double vx;
		double vy;
		double size;
		double age;
		int rgb;
	}
}
