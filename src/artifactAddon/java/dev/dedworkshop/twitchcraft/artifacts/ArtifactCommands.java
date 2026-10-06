package dev.dedworkshop.twitchcraft.artifacts;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * Команда {@code /artifact} (и «/артефакт»).
 *
 *   /artifact                     — статус и настройки;
 *   /artifact give [редкость] [ник] — выдать артефакт (проверка без зрителей);
 *   /artifact list [редкость]     — каталог (35 именных артефактов);
 *   /artifact held                — что лежит в инвентаре и сколько процентов проклятия;
 *   /artifact stats               — статистика: выбито, разрушено, топ зрителей;
 *   /artifact curse <id> <процент>— поставить проклятие (для проверки роста и разрушения);
 *   /artifact break <id>          — разрушить артефакт сейчас;
 *   /artifact boss [now|stop|list|<id>] — мировые боссы: статус, вызов, список;
 *   /artifact reload              — перечитать config/twitchcraft-artifacts.json.
 */
public final class ArtifactCommands {
	private static final SuggestionProvider<FabricClientCommandSource> RARITY_SUGGESTIONS = (ctx, builder) -> {
		String remaining = builder.getRemainingLowerCase();
		for (ArtifactRarity rarity : ArtifactRarity.values()) {
			if (rarity.id.startsWith(remaining)) {
				builder.suggest(rarity.id);
			}
		}
		return builder.buildFuture();
	};

	private static final SuggestionProvider<FabricClientCommandSource> BOSS_IDS = (ctx, builder) -> {
		String remaining = builder.getRemainingLowerCase();
		for (BossCatalog.Boss boss : BossCatalog.BOSSES) {
			if (boss.id().startsWith(remaining)) {
				builder.suggest(boss.id());
			}
		}
		return builder.buildFuture();
	};

	private ArtifactCommands() {
	}

	public static void register(ArtifactsAddon addon) {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(tree(addon, "artifact"));
			dispatcher.register(tree(addon, "артефакт"));
		});
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> tree(ArtifactsAddon addon, String name) {
		return literal(name)
				.executes(ctx -> print(addon.statusLines()))
				.then(literal("give")
						.executes(ctx -> give(addon, null, ""))
						.then(argument("rarity", StringArgumentType.word()).suggests(RARITY_SUGGESTIONS)
								.executes(ctx -> give(addon, StringArgumentType.getString(ctx, "rarity"), ""))
								.then(argument("viewer", StringArgumentType.greedyString())
										.executes(ctx -> give(addon, StringArgumentType.getString(ctx, "rarity"),
												StringArgumentType.getString(ctx, "viewer"))))))
				.then(literal("list")
						.executes(ctx -> print(addon.listLines(null)))
						.then(argument("rarity", StringArgumentType.word()).suggests(RARITY_SUGGESTIONS)
								.executes(ctx -> print(addon.listLines(ArtifactRarity.byId(StringArgumentType.getString(ctx, "rarity")))))))
				.then(literal("held").executes(ctx -> print(addon.heldLines())))
				.then(literal("stats").executes(ctx -> print(addon.statsLines())))
				.then(literal("curse")
						.then(argument("id", StringArgumentType.word())
								.then(argument("percent", IntegerArgumentType.integer(0, 1000))
										.executes(ctx -> curse(addon, StringArgumentType.getString(ctx, "id"),
												IntegerArgumentType.getInteger(ctx, "percent"))))))
				.then(literal("break")
						.then(argument("id", StringArgumentType.word())
								.executes(ctx -> breakArtifact(addon, StringArgumentType.getString(ctx, "id")))))
				.then(literal("boss")
						.executes(ctx -> print(addon.bosses().statusLines()))
						.then(literal("now").executes(ctx -> bossNow(addon)))
						.then(literal("stop").executes(ctx -> bossStop(addon)))
						.then(literal("list").executes(ctx -> print(addon.bosses().listLines())))
						.then(argument("id", StringArgumentType.word()).suggests(BOSS_IDS)
								.executes(ctx -> bossSpawn(addon, StringArgumentType.getString(ctx, "id")))))
				.then(literal("reload").executes(ctx -> reload(addon)));
	}

	private static int print(List<String> lines) {
		for (String line : lines) {
			ArtifactChat.info(line);
		}
		return 1;
	}

	private static int give(ArtifactsAddon addon, String rarityId, String viewer) {
		Artifact artifact;
		if (rarityId == null || rarityId.isBlank()) {
			artifact = addon.drop("manual", viewer);
		} else {
			artifact = addon.giveRarity(ArtifactRarity.byId(rarityId), viewer);
		}
		if (artifact == null) {
			return 0;
		}
		ArtifactChat.success("Выдан артефакт " + artifact.rarity().color + artifact.name
				+ " §8[" + artifact.id + "]§r, проклятие: " + (artifact.hasCurse() ? artifact.curseInfo().description() : "нет"));
		return 1;
	}

	private static int curse(ArtifactsAddon addon, String id, int percent) {
		Artifact artifact = addon.store().byId(id);
		if (artifact == null) {
			ArtifactChat.error("Артефакт с id «" + id + "» не найден. Список: /artifact held");
			return 0;
		}
		addon.setCurse(artifact, percent);
		ArtifactChat.info("Проклятие «" + artifact.name + "» теперь " + artifact.cursePercentText());
		return 1;
	}

	private static int breakArtifact(ArtifactsAddon addon, String id) {
		Artifact artifact = addon.store().byId(id);
		if (artifact == null) {
			ArtifactChat.error("Артефакт с id «" + id + "» не найден. Список: /artifact held");
			return 0;
		}
		addon.breakArtifact(artifact);
		return 1;
	}

	private static int bossNow(ArtifactsAddon addon) {
		if (addon.bosses().alive()) {
			ArtifactChat.warn("Босс уже вызван: сначала убери его — §e/artifact boss stop");
			return 0;
		}
		if (addon.bosses().spawnNow(net.minecraft.client.Minecraft.getInstance(), true)) {
			ArtifactChat.success("Босс вызван — следующий по расписанию.");
			return 1;
		}
		ArtifactChat.error("Не удалось вызвать босса: проверь, что боссы включены в настройках аддона.");
		return 0;
	}

	private static int bossSpawn(ArtifactsAddon addon, String id) {
		if (addon.bosses().alive()) {
			ArtifactChat.warn("Босс уже вызван: сначала убери его — §e/artifact boss stop");
			return 0;
		}
		if (addon.bosses().spawn(id, net.minecraft.client.Minecraft.getInstance())) {
			ArtifactChat.success("Босс «" + BossCatalog.nameOf(id) + "» вызван.");
			return 1;
		}
		ArtifactChat.error("Неизвестный босс «" + id + "». Список: §e/artifact boss list");
		return 0;
	}

	private static int bossStop(ArtifactsAddon addon) {
		if (!addon.bosses().stop(false)) {
			ArtifactChat.warn("Сейчас нет вызванного босса.");
			return 0;
		}
		return 1;
	}

	private static int reload(ArtifactsAddon addon) {
		addon.reload();
		ArtifactChat.success("Настройки перечитаны: макс. артефактов " + addon.config().maxArtifacts
				+ ", проклятие +1 % за " + addon.config().curse.intervalMinutes + " мин");
		return 1;
	}
}
