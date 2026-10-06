package dev.dedworkshop.twitchcraft.artifacts;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Поиск артефактов аддона в инвентаре игрока.
 *
 * Миксины и чтение NBT не нужны: артефакт помечен меткой {@code ⟦tc:<id>⟧}
 * прямо в названии предмета (компонент {@code custom_name}), поэтому достаточно
 * прочитать название.
 */
public final class ArtifactInventory {
	private ArtifactInventory() {
	}

	/** {@code id артефакта → индекс слота} для всех найденных артефактов. */
	public static Map<String, Integer> scan(LocalPlayer player) {
		Map<String, Integer> found = new LinkedHashMap<>();
		if (player == null) {
			return found;
		}
		Inventory inventory = player.getInventory();
		if (inventory == null) {
			return found;
		}
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			String id = ArtifactFactory.extractId(stack.getHoverName().getString());
			if (id != null) {
				found.putIfAbsent(id, slot);
			}
		}
		return found;
	}

	/** Сколько артефактов лежит в инвентаре. */
	public static int count(LocalPlayer player) {
		return scan(player).size();
	}
}
