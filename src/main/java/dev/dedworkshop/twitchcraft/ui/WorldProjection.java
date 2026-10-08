package dev.dedworkshop.twitchcraft.ui;

/**
 * Перевод точки мира в пиксели экрана. Без Minecraft, поэтому проверяется в dev-tests/LogicTest.
 *
 * <p>Базис камеры Minecraft без крена (углы в градусах, как {@code Camera.xRot()} и {@code Camera.yRot()}):
 * <pre>
 *   вперёд = (-sin(yaw)·cos(pitch), -sin(pitch), cos(yaw)·cos(pitch))
 *   вправо = (-cos(yaw), 0, -sin(yaw))
 *   вверх  = (-sin(pitch)·sin(yaw), cos(pitch), sin(pitch)·cos(yaw))
 * </pre>
 * «Вперёд» совпадает с {@code Vec3.directionFromRotation(xRot, yRot)}. Базис записан в явном виде, без векторного
 * произведения, поэтому не вырождается, когда смотришь строго вверх или вниз.
 *
 * <p>Угол обзора — вертикальный, как в {@code Projection.setPerspective}: фокус в пикселях = (H/2) / tg(fov/2).
 */
public final class WorldProjection {
	/** Ближе этой глубины (в блоках) точка считается за камерой и не рисуется. */
	public static final double NEAR = 0.05;
	private static final double MIN_SCALE = 0.5;
	private static final double MAX_SCALE = 1.6;

	/** Экранная точка (пиксели GUI) и глубина вдоль взгляда (блоки). */
	public record Screen(double x, double y, double depth) {
	}

	private WorldProjection() {
	}

	/** Вектор «вперёд» {x, y, z}; углы в градусах. */
	public static double[] forward(double pitchDeg, double yawDeg) {
		double yaw = Math.toRadians(yawDeg);
		double pitch = Math.toRadians(pitchDeg);
		double cosPitch = Math.cos(pitch);
		return new double[] {-Math.sin(yaw) * cosPitch, -Math.sin(pitch), Math.cos(yaw) * cosPitch};
	}

	/** Вектор «вверх» камеры {x, y, z}; углы в градусах. */
	public static double[] up(double pitchDeg, double yawDeg) {
		double yaw = Math.toRadians(yawDeg);
		double pitch = Math.toRadians(pitchDeg);
		return new double[] {-Math.sin(pitch) * Math.sin(yaw), Math.cos(pitch), Math.sin(pitch) * Math.cos(yaw)};
	}

	/** Вектор «вправо» камеры {x, y, z}; от наклона не зависит. */
	public static double[] right(double yawDeg) {
		double yaw = Math.toRadians(yawDeg);
		return new double[] {-Math.cos(yaw), 0, -Math.sin(yaw)};
	}

	/**
	 * Проецирует точку (px, py, pz) на экран width × height. Возвращает {@code null}, если точка не перед камерой.
	 *
	 * @param camX камера: координаты глаза (position())
	 * @param pitchDeg наклон камеры (xRot), градусы; вниз — положительно
	 * @param yawDeg поворот камеры (yRot), градусы
	 * @param fovDeg вертикальный угол обзора, градусы (Camera.getFov())
	 */
	public static Screen project(double px, double py, double pz,
	                             double camX, double camY, double camZ,
	                             double pitchDeg, double yawDeg, double fovDeg,
	                             int width, int height) {
		double[] f = forward(pitchDeg, yawDeg);
		double[] r = right(yawDeg);
		double[] u = up(pitchDeg, yawDeg);
		double dx = px - camX;
		double dy = py - camY;
		double dz = pz - camZ;
		double depth = dx * f[0] + dy * f[1] + dz * f[2];
		if (depth < NEAR) {
			return null;
		}
		double x = dx * r[0] + dy * r[1] + dz * r[2];
		double y = dx * u[0] + dy * u[1] + dz * u[2];
		double fov = Math.max(1.0, Math.min(170.0, fovDeg));
		double focal = (height / 2.0) / Math.tan(Math.toRadians(fov) / 2.0);
		return new Screen(width / 2.0 + x / depth * focal, height / 2.0 - y / depth * focal, depth);
	}

	/**
	 * Масштаб таблички: 1 на расстоянии {@code reference}, крупнее вблизи и мельче вдали, в пределах 0.5–1.6.
	 */
	public static double scaleFor(double depth, double reference) {
		if (depth <= 0 || reference <= 0) {
			return 1.0;
		}
		return Math.max(MIN_SCALE, Math.min(MAX_SCALE, reference / depth));
	}
}
