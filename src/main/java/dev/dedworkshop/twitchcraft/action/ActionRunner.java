package dev.dedworkshop.twitchcraft.action;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.config.DonationPresets;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import dev.dedworkshop.twitchcraft.util.Chat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Выполняет одно действие из конфига: эффекты на экране (сообщение, заголовок,
 * actionbar, тост, звук), ответ в чат Twitch и последовательность команд
 * с паузами и повторами.
 *
 * Все методы вызываются в основном потоке клиента.
 */
public class ActionRunner {
	private final TwitchCraftClient mod;
	private final CommandRunner commands;
	private final List<Sequence> running = new ArrayList<>();
	private final Set<String> warnedSounds = new HashSet<>();
	private final Set<String> warnedCommands = new HashSet<>();
	private final Set<String> warnedPools = new HashSet<>();
	private final Random random = new Random();

	public ActionRunner(TwitchCraftClient mod) {
		this.mod = mod;
		this.commands = new CommandRunner(mod);
	}

	public CommandRunner commands() {
		return commands;
	}

	/**
	 * Запускает действие.
	 *
	 * @param onDone вызывается, когда все команды выполнены (или если команд нет)
	 */
	public void run(TwitchEvent event, ModConfig.Action action, Map<String, String> baseVars, Runnable onDone) {
		Minecraft mc = Minecraft.getInstance();
		Map<String, String> vars = new HashMap<>(baseVars);

		// Таблица лута: выбираем запись заранее, чтобы {loot} работал уже в сообщении основного действия
		ModConfig.Action loot = action.pickLoot(random);
		if (loot != null) {
			vars.put("loot", loot.name == null || loot.name.isBlank() ? "сюрприз" : loot.name);
		}
		// Случайное событие из ценника донатов (pool = bad / good / any) — для наград «Пакость» и «Подарок»
		vars.putIfAbsent("picked", "");
		vars.putIfAbsent("picked_full", "");
		vars.putIfAbsent("picked_text", "");
		if (loot == null && notBlank(action.pool)) {
			ModConfig.Action picked = DonationPresets.pick(mod.config().donationTiers, action.pool, random);
			if (picked != null) {
				String plain = DonationPresets.plainName(picked);
				vars.put("picked", plain);
				vars.put("picked_full", picked.name == null ? plain : picked.name.trim());
				vars.put("picked_text", DonationPresets.flavor(picked));
				vars.put("loot", plain);
				loot = DonationPresets.asPoolPick(picked);
			} else if (warnedPools.add(action.pool)) {
				Chat.warn("В ценнике донатов нет включённых записей для pool=\"" + action.pool + "\" — награда выполнит только своё действие. "
						+ "Вернуть ценник: /twitch donations preset");
			}
		}

		int repeat = computeRepeat(action, vars);
		vars.put("repeat", String.valueOf(repeat));
		vars.put("i", "1");

		showEffects(mc, action, vars);

		if (notBlank(action.reply)) {
			mod.reply(event, Placeholders.apply(action.reply, vars));
		}

		// После основного действия — выпавшая запись лута (её эффекты и команды), затем onDone
		ModConfig.Action finalLoot = loot;
		Runnable afterMain = finalLoot == null ? onDone : () -> runLoot(mc, event, finalLoot, vars, onDone);
		startSequence(action, vars, repeat, afterMain);
	}

	private void runLoot(Minecraft mc, TwitchEvent event, ModConfig.Action loot, Map<String, String> parentVars, Runnable onDone) {
		if (mc.player == null) {
			// игрок вышел из мира, пока выполнялось основное действие — подарок не выдать
			onDone.run();
			return;
		}
		Map<String, String> vars = new HashMap<>(parentVars);
		int repeat = computeRepeat(loot, vars);
		vars.put("repeat", String.valueOf(repeat));
		vars.put("i", "1");
		// Эффекты выпавшей записи (заголовок, звук, тост) не должны мешать командам:
		// иначе ошибка в заголовке/звуке отменяла бы сам подарок (золотое яблоко, наковальня...).
		try {
			showEffects(mc, loot, vars);
			if (notBlank(loot.reply)) {
				mod.reply(event, Placeholders.apply(loot.reply, vars));
			}
		} catch (Exception e) {
			TwitchCraftClient.LOGGER.error("Ошибка эффектов выпавшей записи «{}» — команды всё равно выполняем", loot.name, e);
			warnFailed("эффект награды");
		}
		startSequence(loot, vars, repeat, onDone);
	}

	private void startSequence(ModConfig.Action action, Map<String, String> vars, int repeat, Runnable onDone) {
		List<String> list = new ArrayList<>();
		if (action.commands != null) {
			for (String command : action.commands) {
				if (command != null && !command.isBlank()) {
					list.add(command);
				}
			}
		}
		if (list.isEmpty()) {
			onDone.run();
			return;
		}
		running.add(new Sequence(list, vars, repeat, action.randomOne, Math.max(0, action.repeatDelay), onDone));
	}

	/** Сколько раз повторить команды: поле repeat может быть числом или плейсхолдером ("{amount}"). */
	public static int computeRepeat(ModConfig.Action action, Map<String, String> vars) {
		if (action.repeat == null || action.repeat.isBlank()) {
			return 1;
		}
		String value = Placeholders.apply(action.repeat.trim(), vars);
		int count;
		try {
			count = Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return 1;
		}
		int per = Math.max(1, action.repeatPer);
		count = count / per;
		return Math.max(1, Math.min(count, Math.max(1, action.maxRepeat)));
	}

	private void showEffects(Minecraft mc, ModConfig.Action action, Map<String, String> vars) {
		if (notBlank(action.message)) {
			Chat.send(Component.literal(Placeholders.apply(Chat.colorize(action.message), vars)));
		}
		if (mc.gui == null) {
			return;
		}
		if (notBlank(action.title) || notBlank(action.subtitle)) {
			String title = Placeholders.apply(Chat.colorize(action.title), vars);
			String subtitle = Placeholders.apply(Chat.colorize(action.subtitle), vars);
			mc.gui.hud.setTimes(5, Math.max(10, mod.config().titleTicks), 15);
			mc.gui.hud.setSubtitle(Component.literal(subtitle));
			mc.gui.hud.setTitle(Component.literal(title));
		}
		if (notBlank(action.actionbar)) {
			mc.gui.hud.setOverlayMessage(Component.literal(Placeholders.apply(Chat.colorize(action.actionbar), vars)), false);
		}
		if (notBlank(action.toast)) {
			Component title = Component.literal(Placeholders.apply(Chat.colorize(action.toast), vars));
			Component text = notBlank(action.toastText)
					? Component.literal(Placeholders.apply(Chat.colorize(action.toastText), vars)) : null;
			SystemToast.add(mc.gui.toastManager(), new SystemToast.SystemToastId(6000L), title, text);
		}
		if (notBlank(action.sound)) {
			playSound(mc, action.sound.trim(), action.volume, action.pitch);
		}
	}

	/** Проигрывает клиентский звук (слышно только стримеру). */
	public void playSound(Minecraft mc, String id, float volume, float pitch) {
		Identifier identifier = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
		if (identifier == null) {
			warnSound(id, "некорректный id");
			return;
		}
		Optional<SoundEvent> sound = BuiltInRegistries.SOUND_EVENT.getOptional(identifier);
		if (sound.isEmpty()) {
			warnSound(id, "такого звука нет");
			return;
		}
		float v = Math.max(0.0f, Math.min(volume <= 0 ? 1.0f : volume, 2.0f));
		float p = Math.max(0.5f, Math.min(pitch <= 0 ? 1.0f : pitch, 2.0f));
		mc.getSoundManager().play(SimpleSoundInstance.forUI(sound.get(), p, v));
	}

	private void warnSound(String id, String why) {
		if (warnedSounds.add(id)) {
			Chat.warn("Звук «" + id + "» не проигран: " + why + ". Пример: minecraft:entity.player.levelup");
		}
	}

	private long lastFailureWarnAt;

	/**
	 * Сообщает в чат, что часть действия не выполнилась (не чаще раза в 10 секунд, чтобы не засорять чат на стриме).
	 * Без этого любая ошибка внутри команды выглядела как «награда просто не пришла».
	 */
	private void warnFailed(String what) {
		long now = System.currentTimeMillis();
		if (now - lastFailureWarnAt < 10_000) {
			return;
		}
		lastFailureWarnAt = now;
		Chat.warn("Не выполнено: " + what + " — внутренняя ошибка мода, подробности в logs/latest.log. "
				+ "Остальные команды и выдача награды продолжаются.");
	}

	/** Вызывается каждый игровой тик: продвигает последовательности команд. */
	public void tick(Minecraft mc) {
		if (running.isEmpty()) {
			return;
		}
		if (mc.player == null) {
			// вышли из мира — всё отменяем, но считаем действия завершёнными
			List<Sequence> cancelled = new ArrayList<>(running);
			running.clear();
			for (Sequence sequence : cancelled) {
				sequence.finish();
			}
			return;
		}
		// Обходим КОПИЮ списка: завершение последовательности (onDone) может запустить новую —
		// например, выпавшую запись таблицы лута. Обход оригинала здесь приводил к ConcurrentModificationException.
		for (Sequence sequence : new ArrayList<>(running)) {
			try {
				sequence.tick();
			} catch (Exception e) {
				TwitchCraftClient.LOGGER.error("Ошибка выполнения действия — незавершённые команды этой последовательности пропущены", e);
				warnFailed("команда");
				// ВАЖНО: именно finish(), а не done = true. Иначе onDone не вызывался, и вместе с
				// ошибочной командой молча пропадали подарок за фоллов/саб/рейд, выпавшее событие
				// награды за баллы и подтверждение активации (баллы зрителя «зависали» на Twitch).
				sequence.finish();
			}
		}
		running.removeIf(sequence -> sequence.done);
	}

	public int runningCount() {
		return running.size();
	}

	/** Точка выполнения одной команды (вынесена отдельно, чтобы логику очереди можно было тестировать без игры). */
	protected boolean runCommand(String command) {
		return commands.run(command);
	}

	/** Последовательность команд с поддержкой пауз ("delay N") и повторов. */
	private class Sequence {
		private final List<String> all;
		private final Map<String, String> vars;
		private final int repeat;
		private final boolean randomOne;
		private final int repeatDelay;
		private final Runnable onDone;

		private List<String> current;
		private int index = 0;
		private int iteration = 0;
		private int waitTicks = 0;
		private boolean done;

		Sequence(List<String> all, Map<String, String> vars, int repeat, boolean randomOne, int repeatDelay, Runnable onDone) {
			this.all = all;
			this.vars = vars;
			this.repeat = repeat;
			this.randomOne = randomOne;
			this.repeatDelay = repeatDelay;
			this.onDone = onDone;
			this.current = pickCommands();
		}

		private List<String> pickCommands() {
			if (!randomOne) {
				return all;
			}
			List<String> real = new ArrayList<>();
			for (String command : all) {
				if (!isDelay(command)) {
					real.add(command);
				}
			}
			if (real.isEmpty()) {
				return all;
			}
			return List.of(real.get(random.nextInt(real.size())));
		}

		void finish() {
			if (!done) {
				done = true;
				try {
					onDone.run();
				} catch (Exception e) {
					TwitchCraftClient.LOGGER.warn("Ошибка завершения действия", e);
				}
			}
		}

		void tick() {
			if (done) {
				return;
			}
			if (waitTicks > 0) {
				waitTicks--;
				return;
			}
			while (true) {
				if (index >= current.size()) {
					iteration++;
					if (iteration >= repeat) {
						finish();
						return;
					}
					index = 0;
					current = pickCommands();
					vars.put("i", String.valueOf(iteration + 1));
					if (repeatDelay > 0) {
						waitTicks = repeatDelay;
						return;
					}
					continue;
				}
				String raw = current.get(index++);
				String command = Placeholders.apply(raw, vars).trim();
				if (command.isEmpty()) {
					continue;
				}
				if (isDelay(command)) {
					waitTicks = parseDelay(command);
					return; // продолжим после паузы
				}
				try {
					if (!runCommand(command)) {
						String root = command.split("\\s+", 2)[0];
						if (warnedCommands.add(root)) {
							Chat.warn("Команда /" + root + " не выполнена: она в blockedCommands или ты вне мира.");
						}
					}
				} catch (Exception e) {
					// Одна «сломанная» команда не должна обрывать ни остальные команды действия,
					// ни выпавший подарок/событие награды, ни подтверждение активации.
					TwitchCraftClient.LOGGER.error("Ошибка выполнения команды '{}' — команда пропущена", command, e);
					warnFailed("команда /" + command.split("\\s+", 2)[0]);
				}
			}
		}
	}

	private static boolean isDelay(String command) {
		String lower = command.trim().toLowerCase(Locale.ROOT);
		return lower.equals("delay") || lower.startsWith("delay ");
	}

	private static int parseDelay(String command) {
		String[] parts = command.trim().split("\\s+");
		if (parts.length >= 2) {
			try {
				return Math.max(0, Integer.parseInt(parts[1]));
			} catch (NumberFormatException ignored) {
			}
		}
		return 20;
	}

	private static boolean notBlank(String s) {
		return s != null && !s.isBlank();
	}
}
