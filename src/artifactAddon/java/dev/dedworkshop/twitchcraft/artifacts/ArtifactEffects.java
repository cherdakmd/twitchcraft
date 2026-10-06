package dev.dedworkshop.twitchcraft.artifacts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Баффы и проклятия как эффекты Minecraft.
 *
 * Пока артефакт лежит в инвентаре, аддон периодически выдаёт эффекты командой
 * {@code /effect give}; чем выше уровень артефакта и процент проклятия, тем
 * сильнее эффект. Баффы без ванильного аналога (вампиризм, молнии и т.п.) пока
 * описаны только в лоре — они ждут серверной части (см. README).
 */
public final class ArtifactEffects {
	/** С какого процента проклятие начинает действовать. */
	public static final double MIN_CURSE_PERCENT = 1;

	private ArtifactEffects() {
	}

	/** Команды эффектов для всех действующих артефактов игрока. */
	public static List<String> commands(List<Artifact> artifacts, ArtifactConfig config, int seconds) {
		Map<String, Integer> amplifiers = new LinkedHashMap<>();
		if (artifacts != null) {
			for (Artifact artifact : artifacts) {
				if (artifact == null || !artifact.active() || !artifact.present) {
					continue;
				}
				ArtifactBuff buff = artifact.buffInfo();
				if (buff.hasEffect()) {
					merge(amplifiers, buff.effect(), buffAmplifier(artifact.buffLevel));
				}
				ArtifactCurse curse = artifact.curseInfo();
				if (curse != null && curse.hasEffect() && artifact.cursePercent >= MIN_CURSE_PERCENT) {
					merge(amplifiers, curse.effect(), curseAmplifier(artifact.cursePercent));
				}
			}
		}
		List<String> commands = new ArrayList<>();
		int duration = Math.max(5, seconds);
		for (Map.Entry<String, Integer> entry : amplifiers.entrySet()) {
			commands.add("effect give @s " + entry.getKey() + " " + duration + " " + entry.getValue() + " true");
		}
		return commands;
	}

	/** Уровень эффекта баффа: 0…4 (в игре это «I…V»). */
	public static int buffAmplifier(int buffLevel) {
		return Math.max(0, Math.min(4, buffLevel - 1));
	}

	/** Уровень эффекта проклятия: растёт с процентом (0…2). */
	public static int curseAmplifier(double percent) {
		if (percent < MIN_CURSE_PERCENT) {
			return -1;
		}
		return Math.max(0, Math.min(2, (int) (percent / 34.0)));
	}

	/** Краткое описание действия артефакта для чата. */
	public static String describe(Artifact artifact) {
		StringBuilder text = new StringBuilder();
		ArtifactBuff buff = artifact.buffInfo();
		text.append(buff.hasEffect() ? "§a" : "§7").append(buff.description());
		if (!buff.hasEffect()) {
			text.append(" §8(пока без эффекта — нужна серверная часть)");
		}
		ArtifactCurse curse = artifact.curseInfo();
		if (curse != null) {
			text.append(" §7· проклятие: §c").append(curse.description());
			if (curse.hasEffect()) {
				int amplifier = curseAmplifier(artifact.cursePercent);
				text.append(amplifier >= 0 ? " §7(уровень " + (amplifier + 1) + ")" : " §8(ещё не действует)");
			}
		} else {
			text.append(" §7· §bпроклятий нет");
		}
		text.append(String.format(Locale.ROOT, " §8[%s]", artifact.rarity().title));
		return text.toString();
	}

	private static void merge(Map<String, Integer> amplifiers, String effect, int amplifier) {
		Integer current = amplifiers.get(effect);
		if (current == null || current < amplifier) {
			amplifiers.put(effect, amplifier);
		}
	}
}
