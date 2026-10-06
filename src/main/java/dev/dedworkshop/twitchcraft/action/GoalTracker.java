package dev.dedworkshop.twitchcraft.action;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Накопительные цели: «каждые 10 фолловеров — награда», «1000 битсов — награда» и т.п.
 *
 * Прогресс хранится в файле config/twitchcraft-goals.json, чтобы переживать перезапуск игры.
 * Если мод не запускался дольше goalsSettings.resetAfterHours — прогресс обнуляется (новый стрим).
 *
 * Все методы вызываются в основном потоке клиента (кроме записи файла — она уходит в фоновый поток).
 */
public class GoalTracker {
	public static final String FILE_NAME = "twitchcraft-goals.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	/**
	 * Сколько раз одна цель может сработать от ОДНОГО события. Защита от «шторма» эффектов: рейд на 1000 зрителей
	 * при цели «каждые 10 зрителей» иначе дал бы 100 наград подряд. Излишек остаётся в прогрессе и «догоняется»
	 * следующими событиями.
	 */
	public static final int MAX_COMPLETIONS_PER_EVENT = 5;

	/** Прогресс одной цели. */
	public static class Progress {
		public int count;
		public int completed;
		public boolean done;
		public String lastUser = "";

		Progress copy() {
			Progress copy = new Progress();
			copy.count = count;
			copy.completed = completed;
			copy.done = done;
			copy.lastUser = lastUser;
			return copy;
		}
	}

	/** Строка для оверлея / команды. */
	public record Line(ModConfig.Goal goal, Progress progress) {
		public int target() {
			return Math.max(1, goal.target);
		}

		public String text() {
			if (progress.done && !goal.repeat) {
				return goal.name + ": ✔ " + target() + "/" + target();
			}
			return goal.name + ": " + progress.count + "/" + target()
					+ (progress.completed > 0 ? " ×" + progress.completed : "");
		}
	}

	private static class State {
		long updatedAt;
		Map<String, Progress> goals = new LinkedHashMap<>();
	}

	private final TwitchCraftClient mod;
	private final Executor io;
	private final Path path;
	private final Map<String, Progress> progress = new LinkedHashMap<>();

	public GoalTracker(TwitchCraftClient mod) {
		this(mod, mod.worker(), FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME));
	}

	public GoalTracker(TwitchCraftClient mod, Executor io, Path path) {
		this.mod = mod;
		this.io = io;
		this.path = path;
		load();
	}

	// ---------- Учёт событий ----------

	/**
	 * Учитывает событие во всех подходящих целях.
	 *
	 * @return события «цель достигнута» (может быть несколько, если один вклад закрыл цель несколько раз)
	 */
	public synchronized List<TwitchEvent> onEvent(TwitchEvent event) {
		ModConfig config = mod.config();
		if (config == null || !config.isEnabled(Module.GOALS) || config.goals == null || event == null) {
			return List.of();
		}
		List<TwitchEvent> fired = new ArrayList<>();
		List<String> announce = new ArrayList<>();
		boolean changed = false;
		for (ModConfig.Goal goal : config.goals) {
			if (goal == null || !goal.enabled || goal.name == null || goal.name.isBlank()) {
				continue;
			}
			int add = goal.goalType().contribution(event);
			if (add <= 0) {
				continue;
			}
			Progress p = progressFor(goal.name);
			if (p.done && !goal.repeat) {
				continue;
			}
			p.count += add;
			p.lastUser = event.user();
			changed = true;
			int target = Math.max(1, goal.target);
			boolean reached = false;
			int burst = 0;
			while (p.count >= target && burst < MAX_COMPLETIONS_PER_EVENT) {
				p.completed++;
				reached = true;
				burst++;
				fired.add(TwitchEvent.goal(goal.name, target, p.completed, event.user(), event.userLogin(), event.synthetic()));
				if (goal.repeat) {
					p.count -= target;
				} else {
					p.done = true;
					p.count = target;
					break;
				}
			}
			if (burst >= MAX_COMPLETIONS_PER_EVENT && p.count >= target) {
				TwitchCraftClient.LOGGER.info("Цель «{}»: одно событие дало больше {} срабатываний — остаток {} оставлен в прогрессе",
						goal.name, MAX_COMPLETIONS_PER_EVENT, p.count);
			}
			if (!reached) {
				announce.add("§7Цель «§f" + goal.name + "§7»: §a" + p.count + "§7/§f" + target);
			}
		}
		if (changed) {
			saveAsync();
			if (config.goalsSettings != null && config.goalsSettings.announceProgress && config.showEventsInChat) {
				for (String line : announce) {
					dev.dedworkshop.twitchcraft.util.Chat.info(line);
				}
			}
		}
		return fired;
	}

	/** Ручная правка прогресса: /twitch goals add <цель> <число>. */
	public synchronized List<TwitchEvent> add(ModConfig.Goal goal, int amount) {
		Progress p = progressFor(goal.name);
		p.count = Math.max(0, p.count + amount);
		List<TwitchEvent> fired = new ArrayList<>();
		int target = Math.max(1, goal.target);
		int burst = 0;
		while (p.count >= target && !(p.done && !goal.repeat) && burst++ < MAX_COMPLETIONS_PER_EVENT) {
			p.completed++;
			fired.add(TwitchEvent.goal(goal.name, target, p.completed, "", "", false));
			if (goal.repeat) {
				p.count -= target;
			} else {
				p.done = true;
				p.count = target;
			}
		}
		saveAsync();
		return fired;
	}

	public synchronized Progress progressFor(String goalName) {
		return progress.computeIfAbsent(key(goalName), k -> new Progress());
	}

	public synchronized Progress peek(String goalName) {
		Progress p = progress.get(key(goalName));
		return p == null ? new Progress() : p.copy();
	}

	/** Строки прогресса для оверлея и команды — только включённые цели, в порядке конфига. */
	public synchronized List<Line> lines() {
		List<Line> lines = new ArrayList<>();
		ModConfig config = mod.config();
		if (config == null || config.goals == null) {
			return lines;
		}
		for (ModConfig.Goal goal : config.goals) {
			if (goal != null && goal.enabled && goal.name != null && !goal.name.isBlank()) {
				lines.add(new Line(goal, peek(goal.name)));
			}
		}
		return lines;
	}

	public synchronized void reset(String goalName) {
		if (goalName == null) {
			progress.clear();
		} else {
			progress.remove(key(goalName));
		}
		saveAsync();
	}

	private static String key(String goalName) {
		return goalName == null ? "" : goalName.trim().toLowerCase(Locale.ROOT);
	}

	// ---------- Файл ----------

	private void load() {
		if (!Files.exists(path)) {
			return;
		}
		try {
			State state = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), State.class);
			if (state == null || state.goals == null) {
				return;
			}
			ModConfig config = mod.config();
			int resetHours = config == null || config.goalsSettings == null ? 0 : config.goalsSettings.resetAfterHours;
			if (resetHours > 0 && state.updatedAt > 0
					&& System.currentTimeMillis() - state.updatedAt > resetHours * 3_600_000L) {
				TwitchCraftClient.LOGGER.info("Прогресс целей сброшен: прошло больше {} ч с прошлого стрима", resetHours);
				return;
			}
			progress.putAll(state.goals);
		} catch (Exception e) {
			TwitchCraftClient.LOGGER.warn("Не удалось прочитать {}: {}", path, e.toString());
		}
	}

	private void saveAsync() {
		State state = new State();
		state.updatedAt = System.currentTimeMillis();
		for (Map.Entry<String, Progress> entry : progress.entrySet()) {
			state.goals.put(entry.getKey(), entry.getValue().copy());
		}
		String json = GSON.toJson(state);
		Runnable write = () -> {
			try {
				dev.dedworkshop.twitchcraft.util.SafeFiles.writeAtomic(path, json);
			} catch (IOException e) {
				TwitchCraftClient.LOGGER.warn("Не удалось сохранить прогресс целей: {}", e.toString());
			}
		};
		try {
			io.execute(write);
		} catch (Exception e) {
			write.run();
		}
	}
}
