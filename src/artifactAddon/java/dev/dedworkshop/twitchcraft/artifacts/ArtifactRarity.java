package dev.dedworkshop.twitchcraft.artifacts;

import java.util.Random;

/**
 * Редкости артефактов — перенесены из серверного аддона CHRDK REBORN (VKChat Artifacts).
 *
 * Шансы по умолчанию: обычный 40 %, необычный 25 %, редкий 20 %, эпический 10 %,
 * легендарный 4 %, мифический 0,1 % (как в документации к серверному аддону).
 * Мифический артефакт идёт без проклятия и может оказаться особым (Перо Феникса и др.).
 */
public enum ArtifactRarity {
	COMMON("common", "Обычный", "§7", 1, 40.0),
	UNCOMMON("uncommon", "Необычный", "§a", 1, 25.0),
	RARE("rare", "Редкий", "§9", 2, 20.0),
	EPIC("epic", "Эпический", "§5", 3, 10.0),
	LEGENDARY("legendary", "Легендарный", "§6", 4, 4.0),
	MYTHIC("mythic", "Мифический", "§d", 5, 0.1);

	public final String id;
	public final String title;
	/** Цвет для чата и названия предмета (§-код). */
	public final String color;
	public final int modelData;
	/** Шанс по умолчанию, проценты. */
	public final double defaultChance;

	ArtifactRarity(String id, String title, String color, int modelData, double defaultChance) {
		this.id = id;
		this.title = title;
		this.color = color;
		this.modelData = modelData;
		this.defaultChance = defaultChance;
	}

	public boolean isMythic() {
		return this == MYTHIC;
	}

	public static ArtifactRarity byId(String id) {
		if (id == null) {
			return COMMON;
		}
		String wanted = id.trim().toLowerCase(java.util.Locale.ROOT);
		for (ArtifactRarity rarity : values()) {
			if (rarity.id.equals(wanted) || rarity.name().equalsIgnoreCase(wanted)) {
				return rarity;
			}
		}
		return COMMON;
	}

	/**
	 * Бросок редкости.
	 *
	 * @param random  источник случайности
	 * @param chances шансы в процентах, порядок — как у {@link #values()}; значения ≤ 0 не выпадают
	 * @return выпавшая редкость (COMMON, если все шансы нулевые)
	 */
	public static ArtifactRarity roll(Random random, double[] chances) {
		double total = 0;
		if (chances != null) {
			for (double chance : chances) {
				if (chance > 0) {
					total += chance;
				}
			}
		}
		if (total <= 0) {
			return COMMON;
		}
		double roll = random.nextDouble() * total;
		double cumulative = 0;
		for (int i = 0; i < values().length; i++) {
			double chance = chances != null && i < chances.length ? chances[i] : 0;
			if (chance <= 0) {
				continue;
			}
			cumulative += chance;
			if (roll < cumulative) {
				return values()[i];
			}
		}
		return COMMON;
	}

	/** Шансы по умолчанию (в порядке values()) — для конфига и тестов. */
	public static double[] defaultChances() {
		ArtifactRarity[] rarities = values();
		double[] chances = new double[rarities.length];
		for (int i = 0; i < rarities.length; i++) {
			chances[i] = rarities[i].defaultChance;
		}
		return chances;
	}
}
