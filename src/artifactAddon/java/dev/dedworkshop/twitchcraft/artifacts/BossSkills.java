package dev.dedworkshop.twitchcraft.artifacts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Навыки боссов — 11 штук из серверного аддона CHRDK REBORN
 * ({@code MINIONS, LIGHTNING, PULL, EARTHQUAKE, POISON_CLOUD, WEB_TRAP, TELEPORT, BLINDNESS,
 * WITHER_SKULL, FREEZE, FIRE_RING}).
 *
 * <p>На сервере это был код Bukkit (спавн сущностей, телепорты, зелья). В клиентском аддоне навык —
 * это список <b>команд Minecraft</b>, которые мод выполняет от имени игрока: в одиночной игре —
 * с правами оператора, на сервере — как обычный игрок (нужны права). Поэтому класс чистый:
 * на вход позиции босса и игрока, на выход — команды. Это же позволяет проверять навыки тестами
 * без запуска игры.</p>
 */
public final class BossSkills {

	/** Контекст навыка: где босс, где игрок, чем бросать кубик. */
	public record Context(String bossName, String bossType, double bossX, double bossY, double bossZ,
			String player, double playerX, double playerY, double playerZ, Random random) {

		/** Селектор босса по имени (для команд, которые действуют «от босса»). */
		public String selector() {
			return "@e[type=" + bossType + ",name=\"" + bossName + "\",limit=1]";
		}
	}

	private BossSkills() {
	}

	/** Человеческое описание навыка (для {@code /artifact boss} и логов). */
	public static String description(String skill) {
		return switch (normalize(skill)) {
			case "MINIONS" -> "призывает трёх скелетов рядом с собой";
			case "LIGHTNING" -> "бьёт молнией в игрока";
			case "PULL" -> "притягивает игрока к себе";
			case "EARTHQUAKE" -> "подбрасывает игроков в радиусе 10 блоков";
			case "POISON_CLOUD" -> "отравляет игроков в радиусе 5 блоков";
			case "WEB_TRAP" -> "ставит паутину под ногами игрока";
			case "TELEPORT" -> "телепортируется в случайную точку рядом";
			case "BLINDNESS" -> "ослепляет игрока на 3 секунды";
			case "WITHER_SKULL" -> "стреляет головой иссушителя в игрока";
			case "FREEZE" -> "сильно замедляет игрока на 5 секунд";
			case "FIRE_RING" -> "поджигает круг вокруг себя";
			default -> "неизвестный навык";
		};
	}

	/**
	 * Команды навыка. Пустой список — навык не смог сработать (например, некого подбросить).
	 * Позиции — абсолютные: босс может быть далеко от игрока, а команды выполняются от игрока.
	 */
	public static List<String> commands(String skill, Context ctx) {
		List<String> commands = new ArrayList<>();
		if (skill == null || ctx == null) {
			return commands;
		}
		switch (normalize(skill)) {
			case "MINIONS" -> {
				// Три скелета в паре блоков от босса (как в серверном аддоне: по бокам и спереди)
				commands.add(summon("minecraft:skeleton", ctx.bossX() + 2, ctx.bossY(), ctx.bossZ()));
				commands.add(summon("minecraft:skeleton", ctx.bossX() - 2, ctx.bossY(), ctx.bossZ()));
				commands.add(summon("minecraft:skeleton", ctx.bossX(), ctx.bossY(), ctx.bossZ() + 2));
			}
			case "LIGHTNING" -> commands.add("summon minecraft:lightning_bolt "
					+ coord(ctx.playerX()) + " " + coord(ctx.playerY()) + " " + coord(ctx.playerZ()));
			case "PULL" -> commands.add("tp @s " + ctx.bossX() + " " + ctx.bossY() + " " + ctx.bossZ());
			case "EARTHQUAKE" -> {
				// В ванильных командах нет «подбросить» — ближайший аналог: левитация на секунду
				commands.add("effect give @a[distance=..10] minecraft:levitation 1 2");
				commands.add("effect give @a[distance=..10] minecraft:slow_falling 3 0");
			}
			case "POISON_CLOUD" -> commands.add("effect give @a[distance=..5] minecraft:poison 5 1");
			case "WEB_TRAP" -> commands.add("setblock " + coord(ctx.playerX()) + " " + coord(ctx.playerY())
					+ " " + coord(ctx.playerZ()) + " minecraft:cobweb keep");
			case "TELEPORT" -> {
				Random random = ctx.random() == null ? new Random() : ctx.random();
				int dx = random.nextInt(11) - 5;
				int dz = random.nextInt(11) - 5;
				if (dx == 0 && dz == 0) {
					dx = 3;
				}
				commands.add("tp " + ctx.selector() + " " + (ctx.bossX() + dx) + " " + ctx.bossY() + " " + (ctx.bossZ() + dz));
			}
			case "BLINDNESS" -> commands.add("effect give @s minecraft:blindness 3 1");
			case "WITHER_SKULL" -> {
				double dx = ctx.playerX() - ctx.bossX();
				double dy = (ctx.playerY() + 1) - (ctx.bossY() + 1.5);
				double dz = ctx.playerZ() - ctx.bossZ();
				double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
				if (length < 0.1) {
					length = 1;
				}
				double speed = 1.5;
				commands.add("summon minecraft:wither_skull " + ctx.bossX() + " " + (ctx.bossY() + 1.5) + " " + ctx.bossZ()
						+ " {Motion:[" + round(dx / length * speed) + "d," + round(dy / length * speed) + "d,"
						+ round(dz / length * speed) + "d]}");
			}
			case "FREEZE" -> commands.add("effect give @s minecraft:slowness 5 4");
			case "FIRE_RING" -> {
				// Кольцо огня радиусом 3 блока: 12 точек (как цикл на 360° с шагом 30° на сервере).
				for (int angle = 0; angle < 360; angle += 30) {
					double radians = Math.toRadians(angle);
					commands.add("setblock " + coord(ctx.bossX() + Math.cos(radians) * 3) + " " + coord(ctx.bossY())
							+ " " + coord(ctx.bossZ() + Math.sin(radians) * 3) + " minecraft:fire keep");
				}
			}
			default -> {
			}
		}
		return commands;
	}

	/** Команда появления сущности с постоянным именем (имя нужно для селекторов навыков). */
	public static String summon(String type, double x, double y, double z) {
		return "summon " + type + " " + coord(x) + " " + coord(y) + " " + coord(z);
	}

	private static String normalize(String skill) {
		return skill == null ? "" : skill.trim().toUpperCase(Locale.ROOT);
	}

	/** Координата без лишних нулей: 12.0 → 12. */
	private static String coord(double value) {
		return trim(value);
	}

	private static String round(double value) {
		return trim(Math.round(value * 1000.0) / 1000.0);
	}

	private static String trim(double value) {
		if (value == Math.rint(value) && Math.abs(value) < 1e15) {
			return String.valueOf((long) value);
		}
		return String.valueOf(Math.round(value * 1000.0) / 1000.0);
	}
}
