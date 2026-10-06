package dev.dedworkshop.twitchcraft.artifacts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Создание артефактов и команд Minecraft для них. Только «чистая» логика —
 * её проверяют автотесты без запуска игры.
 *
 * Предмет помечается в названии: {@code … §8⟦tc:a1b2⟧}. По этой метке аддон
 * находит свои артефакты в инвентаре игрока (без миксинов и чтения NBT).
 */
public final class ArtifactFactory {
	/** Начало метки артефакта в названии предмета. */
	public static final String MARK_PREFIX = "⟦tc:";
	public static final String MARK_SUFFIX = "⟧";

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");
	/** Резервный список id, чтобы не повторяться в рамках одного захода. */
	private static int counter;

	private ArtifactFactory() {
	}

	/** Новый артефакт по настройкам: редкость, бафф, проклятие, материал. */
	public static Artifact create(Random random, ArtifactConfig config, String source, String viewer) {
		ArtifactRarity rarity = ArtifactRarity.roll(random, config.rarityChances());
		boolean mythic = rarity.isMythic();

		Artifact artifact = new Artifact();
		artifact.id = newId(random);
		artifact.rarity = rarity.id;
		artifact.mythic = mythic;
		artifact.source = source == null ? "" : source;
		artifact.foundBy = viewer == null ? "" : viewer;
		artifact.createdAt = System.currentTimeMillis();
		artifact.curse = ArtifactCurse.NONE;

		ArtifactCatalog.Template template = null;
		if (random.nextDouble() * 100.0 < config.namedArtifactChance) {
			template = ArtifactCatalog.randomTemplate(random, rarity);
		}
		if (template != null) {
			artifact.name = template.name();
			artifact.material = template.material();
			artifact.buff = template.buff();
			artifact.buffLevel = template.level();
		} else {
			applyGeneric(random, artifact, rarity);
		}

		if (!mythic) {
			ArtifactCurse curse = ArtifactCatalog.randomCurse(random);
			artifact.curse = curse.id();
		}
		return artifact;
	}

	/** Артефакт заданной редкости (для команд и тестов). */
	public static Artifact create(Random random, ArtifactConfig config, ArtifactRarity rarity, String source, String viewer) {
		ArtifactConfig copy = config;
		// Повторяем бросок, пока не выпадет нужная редкость (или 200 попыток) — только для ручной выдачи.
		for (int i = 0; i < 200; i++) {
			Artifact candidate = create(random, copy, source, viewer);
			if (candidate.rarity().equals(rarity)) {
				return candidate;
			}
		}
		Artifact fallback = create(random, copy, source, viewer);
		fallback.rarity = rarity.id;
		fallback.mythic = rarity.isMythic();
		if (fallback.mythic) {
			fallback.curse = ArtifactCurse.NONE;
		} else if (ArtifactCurse.NONE.equals(fallback.curse)) {
			fallback.curse = ArtifactCatalog.randomCurse(random).id();
		}
		return fallback;
	}

	/** «Обычный» артефакт: случайный бафф, материал и уровень по редкости. */
	private static void applyGeneric(Random random, Artifact artifact, ArtifactRarity rarity) {
		artifact.material = ArtifactCatalog.materials().get(random.nextInt(ArtifactCatalog.materials().size()));
		if (rarity.isMythic()) {
			// Особые мифические предметы (как в серверном аддоне) — с шансом внутри мифического дропа.
			double roll = random.nextDouble() * 100.0;
			for (ArtifactCatalog.Special special : ArtifactCatalog.specials()) {
				if (roll < special.rollLimit()) {
					artifact.name = special.name();
					artifact.material = special.material();
					artifact.buff = special.buff();
					artifact.buffLevel = 5;
					return;
				}
			}
			artifact.name = "✨ МИФИЧЕСКАЯ РЕЛИКВИЯ";
			artifact.buff = ArtifactCatalog.randomBuff(random).id();
			artifact.buffLevel = 5;
			return;
		}
		artifact.name = switch (rarity) {
			case RARE -> "Редкий Артефакт";
			case EPIC -> "Эпический Артефакт";
			case LEGENDARY -> "Легендарный Артефакт";
			case UNCOMMON -> "Необычный Артефакт";
			default -> "Обычный Артефакт";
		};
		artifact.buff = ArtifactCatalog.randomBuff(random).id();
		artifact.buffLevel = switch (rarity) {
			case UNCOMMON -> 1 + random.nextInt(2); // 1–2
			case RARE -> 2;
			case EPIC -> 3;
			case LEGENDARY -> 4;
			default -> 1;
		};
	}

	/** Уникальный (в рамках аддона) короткий id. */
	public static String newId(Random random) {
		counter = (counter + 1) & 0xFFFF;
		return String.format(Locale.ROOT, "%04x", (random.nextInt(0x10000) + counter) & 0xFFFF);
	}

	/** Название предмета с цветом и меткой. */
	public static String displayName(Artifact artifact) {
		return artifact.coloredName();
	}

	/** Идентификатор артефакта из названия предмета; null — если это не артефакт аддона. */
	public static String extractId(String hoverName) {
		if (hoverName == null) {
			return null;
		}
		int start = hoverName.indexOf(MARK_PREFIX);
		if (start < 0) {
			return null;
		}
		start += MARK_PREFIX.length();
		int end = hoverName.indexOf(MARK_SUFFIX, start);
		if (end < 0) {
			return null;
		}
		String id = hoverName.substring(start, end).trim();
		return id.isEmpty() ? null : id;
	}

	public static boolean isArtifactName(String hoverName) {
		return extractId(hoverName) != null;
	}

	/** Строки лора предмета. */
	public static List<String> lore(Artifact artifact) {
		List<String> lines = new ArrayList<>();
		lines.add("§8Древняя вещь, источающая магию.");
		lines.add("");
		lines.add("§a➕ " + artifact.buffInfo().description() + " §7(ур. " + artifact.buffLevel + ")");
		ArtifactCurse curse = artifact.curseInfo();
		if (curse == null) {
			lines.add("§b✨ Проклятий нет — реликвия чиста.");
		} else {
			lines.add("§c☠ " + curse.description() + " §7(" + artifact.cursePercentText() + ")");
		}
		lines.add("");
		lines.add("§8Работает, пока лежит в инвентаре. Чем дольше держишь — тем сильнее проклятие.");
		lines.add("§8Редкость: " + artifact.rarity().color + artifact.rarity().title
				+ " §8· " + (artifact.foundBy.isEmpty() ? "найден" : "выбит для " + artifact.foundBy));
		return lines;
	}

	/**
	 * Команда выдачи предмета. Компоненты: {@code custom_name} (название с меткой) и {@code lore}.
	 * По умолчанию ничего больше — так команда работает на любой версии игры.
	 */
	public static String giveCommand(Artifact artifact, ArtifactConfig config) {
		StringBuilder command = new StringBuilder("give @s ");
		command.append(artifact.material);
		command.append("[minecraft:custom_name=\"").append(escape(displayName(artifact))).append('"');
		command.append(",minecraft:lore=[");
		List<String> lines = lore(artifact);
		for (int i = 0; i < lines.size(); i++) {
			if (i > 0) {
				command.append(',');
			}
			command.append('"').append(escape(lines.get(i))).append('"');
		}
		command.append(']');
		if (config != null && config.writeCustomData) {
			command.append(",minecraft:custom_data={twitchcraft_artifact:{id:\"").append(escape(artifact.id))
					.append("\",rarity:\"").append(escape(artifact.rarity))
					.append("\",buff:\"").append(escape(artifact.buff))
					.append("\",curse:\"").append(escape(artifact.curse))
					.append("\",level:").append(artifact.buffLevel).append("}}");
		}
		command.append("] 1");
		return command.toString();
	}

	/**
	 * Способы убрать артефакт из инвентаря (по порядку): точный слот, затем
	 * удаление одного предмета такого материала. Аддон пробует их по очереди
	 * и проверяет результат сканированием инвентаря.
	 */
	public static List<String> destroyCommands(Artifact artifact, int slot) {
		List<String> commands = new ArrayList<>();
		String slotName = slotName(slot);
		if (slotName != null) {
			commands.add("item replace entity @s " + slotName + " with air");
		}
		commands.add("clear @s " + artifact.material + " 1");
		return commands;
	}

	/** Имя слота для команды {@code /item replace entity @s <слот> …} по индексу инвентаря игрока. */
	public static String slotName(int index) {
		if (index < 0) {
			return null;
		}
		if (index <= 8) {
			return "hotbar." + index;
		}
		if (index <= 35) {
			return "inventory." + (index - 9);
		}
		return switch (index) {
			case 36 -> "armor.feet";
			case 37 -> "armor.legs";
			case 38 -> "armor.chest";
			case 39 -> "armor.head";
			case 40 -> "weapon.offhand";
			default -> null;
		};
	}

	/** Плейсхолдеры для текстов сообщений. */
	public static Map<String, String> placeholders(Artifact artifact, int maxArtifacts) {
		Map<String, String> values = new LinkedHashMap<>();
		values.put("name", artifact.name);
		values.put("id", artifact.id);
		values.put("rarity", artifact.rarity().title);
		values.put("buff", artifact.buffInfo().description());
		values.put("level", String.valueOf(artifact.buffLevel));
		ArtifactCurse curse = artifact.curseInfo();
		values.put("curse", curse == null ? "нет" : curse.description());
		values.put("percent", artifact.cursePercentText());
		values.put("user", artifact.foundBy);
		values.put("max", String.valueOf(maxArtifacts));
		values.put("source", artifact.source);
		return values;
	}

	/** Подстановка {ключей} за один проход: значение не может «раскрыться» в другой плейсхолдер. */
	public static String text(String template, Map<String, String> values) {
		if (template == null || template.isEmpty()) {
			return "";
		}
		StringBuilder result = new StringBuilder();
		Matcher matcher = PLACEHOLDER.matcher(template);
		int last = 0;
		while (matcher.find()) {
			String key = matcher.group(1);
			String value = values == null ? null : values.get(key);
			result.append(template, last, matcher.start());
			if (value != null) {
				result.append(value);
			} else {
				result.append(matcher.group(0));
			}
			last = matcher.end();
		}
		result.append(template.substring(last));
		return result.toString();
	}

	/** Экранирование строки для SNBT (внутри двойных кавычек). */
	static String escape(String value) {
		if (value == null) {
			return "";
		}
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
