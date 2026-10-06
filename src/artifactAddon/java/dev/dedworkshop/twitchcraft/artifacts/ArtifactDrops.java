package dev.dedworkshop.twitchcraft.artifacts;

import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Правила: за какие события зрителей выдаётся артефакт.
 *
 * Перенос идеи серверного аддона (там артефакты падали с мобов и из алхимических
 * тайников) в Twitch-контекст: артефакт «выбивает» зритель — за битсы, донат,
 * подписку, подаренные подписки, рейд, награду за баллы канала, а также за
 * победу над боссом в самой игре.
 *
 * Остаток «неиспользованных» битсов и рублей переносится на следующие события,
 * но за одно событие выдаётся не больше {@link #MAX_PER_EVENT} артефактов.
 */
public final class ArtifactDrops {
	/** Не больше стольких артефактов за одно событие (защита от «кита» на 10 000 ₽). */
	public static final int MAX_PER_EVENT = 5;

	private long bits;
	private long donation;
	private long giftSubs;

	/** Сколько артефактов выдавать за это событие. */
	public int countFor(TwitchEvent event, ArtifactConfig config, Random random) {
		if (event == null || config == null || config.drops == null) {
			return 0;
		}
		ArtifactConfig.Drops rules = config.drops;
		int count = switch (event.type()) {
			case CHEER -> rules.bits ? take(event.amount(), rules.bitsPerArtifact, true) : 0;
			case DONATION -> rules.donations ? take(event.amount(), rules.donationPerArtifact, false) : 0;
			case SUBSCRIBE, RESUB -> rules.subs ? Math.max(0, event.amount()) / Math.max(1, rules.subsPerArtifact) : 0;
			case GIFT_SUB -> rules.giftSubs ? takeGiftSubs(event.amount(), rules.giftSubsPerArtifact) : 0;
			case RAID -> rules.raids ? Math.max(0, event.amount()) / Math.max(1, rules.raidViewersPerArtifact) : 0;
			case REWARD -> rules.rewards && matchesReward(event.reward(), rules.rewardNames) ? 1 : 0;
			case GAME -> rules.bossKills && TwitchEvent.GAME_BOSS.equals(event.gameKind())
					? (random.nextDouble() * 100.0 < rules.bossKillChance ? 1 : 0) : 0;
			default -> 0;
		};
		return Math.min(count, MAX_PER_EVENT);
	}

	/** Источник артефакта для статистики и текстов. */
	public static String sourceOf(TwitchEvent event) {
		if (event == null) {
			return "manual";
		}
		return switch (event.type()) {
			case CHEER -> "bits";
			case DONATION -> "donation";
			case SUBSCRIBE, RESUB -> "sub";
			case GIFT_SUB -> "gift";
			case RAID -> "raid";
			case REWARD -> "reward";
			case GAME -> "boss";
			default -> "manual";
		};
	}

	/** Совпадает ли название награды за баллы канала с одним из «артефактных» слов. */
	public static boolean matchesReward(String reward, List<String> names) {
		if (reward == null || reward.isBlank() || names == null || names.isEmpty()) {
			return false;
		}
		String value = reward.toLowerCase(Locale.ROOT);
		for (String name : names) {
			if (name == null || name.isBlank()) {
				continue;
			}
			if (value.contains(name.trim().toLowerCase(Locale.ROOT))) {
				return true;
			}
		}
		return false;
	}

	/** Накопленные «остатки» (для тестов и команды статуса). */
	public long bitsRemainder() {
		return bits;
	}

	public long donationRemainder() {
		return donation;
	}

	public long giftSubsRemainder() {
		return giftSubs;
	}

	public void reset() {
		bits = 0;
		donation = 0;
		giftSubs = 0;
	}

	private int take(int amount, int perArtifact, boolean isBits) {
		int step = Math.max(1, perArtifact);
		long total = (isBits ? bits : donation) + Math.max(0, amount);
		int count = (int) (total / step);
		long remainder = total - (long) count * step;
		if (isBits) {
			bits = remainder;
		} else {
			donation = remainder;
		}
		return count;
	}

	private int takeGiftSubs(int amount, int perArtifact) {
		int step = Math.max(1, perArtifact);
		long total = giftSubs + Math.max(0, amount);
		int count = (int) (total / step);
		giftSubs = total - (long) count * step;
		return count;
	}
}
