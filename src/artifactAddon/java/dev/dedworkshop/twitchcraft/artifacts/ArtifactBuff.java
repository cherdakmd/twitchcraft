package dev.dedworkshop.twitchcraft.artifacts;

/**
 * Положительный эффект артефакта (перенесён из серверного {@code ArtifactFactory.BUFFS}).
 *
 * @param id          идентификатор (латиницей, как в серверном аддоне)
 * @param description описание для лора предмета и команд
 * @param effect      ванильный эффект Minecraft (например {@code minecraft:speed}) или пустая строка,
 *                    если эффект требует доработок на стороне сервера (миксины) и пока работает «идейно»
 */
public record ArtifactBuff(String id, String description, String effect) {

	/** Есть ли у баффа настоящий ванильный эффект. */
	public boolean hasEffect() {
		return effect != null && !effect.isBlank();
	}
}
