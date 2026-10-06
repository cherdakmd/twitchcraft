package dev.dedworkshop.twitchcraft.artifacts;

import java.util.Locale;

/**
 * Один артефакт: лежит в инвентаре игрока предметом с меткой
 * {@code ⟦tc:<id>⟧} в названии, а полное состояние (проклятие, уровень, кто выбил)
 * хранится в файле аддона.
 *
 * Поля без {@code transient} сохраняются в {@code config/twitchcraft-artifacts-state.json}.
 */
public final class Artifact {
	/** Короткий идентификатор (4 hex-цифры) — он же в названии предмета. */
	public String id = "";
	/** Название артефакта без цвета. */
	public String name = "";
	/** Материал предмета, например {@code minecraft:nether_star}. */
	public String material = "minecraft:nether_star";
	/** Редкость (id из {@link ArtifactRarity}). */
	public String rarity = "common";
	/** Бафф (id из каталога). */
	public String buff = "SPEED";
	/** Уровень баффа 1…5. */
	public int buffLevel = 1;
	/** Проклятие (id из каталога) или {@link ArtifactCurse#NONE}. */
	public String curse = ArtifactCurse.NONE;
	/** Текущий процент проклятия (0…breakAt). */
	public double cursePercent = 0;
	/** Сколько миллисекунд артефакт пролежал в инвентаре (для роста проклятия). */
	public long wornMillis = 0;
	public long createdAt = 0;
	/** Откуда пришёл артефакт: bits / donation / sub / gift / raid / reward / boss / manual. */
	public String source = "";
	/** Зритель, который «выбил» артефакт (подпись в названии и в чате). */
	public String foundBy = "";
	/** Мифический артефакт — без проклятия, рассыпаться не может. */
	public boolean mythic = false;
	/** Артефакт разрушен проклятием (запись остаётся для статистики). */
	public boolean destroyed = false;
	public long destroyedAt = 0;
	/** Не удалось убрать предмет из инвентаря — артефакт считается «выгоревшим» и не действует. */
	public boolean inactive = false;

	// --- Только в памяти: где артефакт сейчас ---
	public transient int slot = -1;
	public transient boolean present = false;
	/** Шаг попытки уничтожения (0 — не пробовали). */
	public transient int destroyAttempt = 0;
	public transient long destroyTriedAt = 0;

	public ArtifactRarity rarity() {
		return ArtifactRarity.byId(rarity);
	}

	public ArtifactBuff buffInfo() {
		return ArtifactCatalog.buff(buff);
	}

	/** Описание проклятия или null (мифический артефакт / проклятия нет). */
	public ArtifactCurse curseInfo() {
		return ArtifactCatalog.curse(curse);
	}

	public boolean hasCurse() {
		return !mythic && curseInfo() != null;
	}

	/** Название с цветом редкости и меткой для распознавания предмета. */
	public String coloredName() {
		ArtifactRarity value = rarity();
		return value.color + name + " §8" + ArtifactFactory.MARK_PREFIX + id + ArtifactFactory.MARK_SUFFIX;
	}

	/** Процент проклятия строкой: «37 %». */
	public String cursePercentText() {
		return String.format(Locale.ROOT, "%.0f %%", cursePercent);
	}

	/** Действует ли артефакт сейчас (в инвентаре, не разрушен, не «выгорел»). */
	public boolean active() {
		return !destroyed && !inactive;
	}
}
