package dev.dedworkshop.twitchcraft.artifacts;

/**
 * Проклятие артефакта. Проклятие растёт, пока артефакт лежит в инвентаре:
 * +1 % за каждые {@code intervalMinutes} минут. На 100 % артефакт разрушается навсегда.
 *
 * @param id          идентификатор (как в серверном аддоне)
 * @param description описание для лора предмета
 * @param effect      ванильный негативный эффект или пустая строка
 */
public record ArtifactCurse(String id, String description, String effect) {

	public static final String NONE = "NONE";

	public boolean isNone() {
		return NONE.equals(id);
	}

	public boolean hasEffect() {
		return effect != null && !effect.isBlank();
	}
}
