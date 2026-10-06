package dev.dedworkshop.twitchcraft.action;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;

import java.util.UUID;

/**
 * Выполняет команды Minecraft от имени игрока.
 *
 * В одиночной игре у нас есть доступ к встроенному серверу — выполняем команду
 * напрямую с правами оператора (работает даже если читы выключены).
 * На чужом сервере отправляем команду как обычный игрок — нужны права OP.
 */
public class CommandRunner {
	private final TwitchCraftClient mod;

	public CommandRunner(TwitchCraftClient mod) {
		this.mod = mod;
	}

	/**
	 * @return false, если команда заблокирована настройкой blockedCommands или игрок не в мире.
	 */
	public boolean run(String command) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null) {
			return false;
		}
		String cmd = command.trim();
		if (cmd.startsWith("/")) {
			cmd = cmd.substring(1);
		}
		if (cmd.isEmpty()) {
			return true;
		}
		if (mod.config().isCommandBlocked(cmd)) {
			TwitchCraftClient.LOGGER.warn("Команда заблокирована настройкой blockedCommands: /{}", cmd);
			return false;
		}

		boolean showOutput = mod.config().showCommandOutput;
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			UUID uuid = player.getUUID();
			String finalCmd = cmd;
			server.execute(() -> {
				ServerPlayer serverPlayer = server.getPlayerList().getPlayer(uuid);
				if (serverPlayer == null) {
					return;
				}
				CommandSourceStack source = serverPlayer.createCommandSourceStack()
						.withMaximumPermission(LevelBasedPermissionSet.OWNER);
				if (!showOutput) {
					source = source.withSuppressedOutput();
				}
				try {
					server.getCommands().performPrefixedCommand(source, finalCmd);
				} catch (Exception e) {
					TwitchCraftClient.LOGGER.error("Ошибка выполнения команды '{}'", finalCmd, e);
				}
			});
		} else {
			player.connection.sendCommand(cmd);
		}
		TwitchCraftClient.LOGGER.info("Выполнена команда: /{}", cmd);
		return true;
	}
}
