package dev.dedworkshop.twitchcraft.action;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import dev.dedworkshop.twitchcraft.util.Chat;
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
 * Сборы средств — полоса в стиле боссбара вверху экрана («Сбор на микрофон: 3 500 / 10 000 ₽ (35%)»).
 *
 * Вклады: донаты (сумма в основной валюте) и, если в сборе заданы ставки, битсы, подписки и баллы канала.
 * Прогресс хранится в config/twitchcraft-fundraisers.json и НЕ сбрасывается между стримами —
 * сбор на новый ПК может идти неделями. Сбросить: /twitch fund reset <имя> или кнопка в настройках.
 *
 * Когда собранная сумма впервые достигает цели, создаётся событие FUND — его действие (салют, сообщение,
 * ответ в чат) настраивается у сбора. После сброса сбор можно закрыть снова (times растёт).
 *
 * Все методы вызываются в основном потоке клиента (запись файла уходит в фоновый поток).
 */
public class FundraiserTracker {
	public static final String FILE_NAME = "twitchcraft-fundraisers.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** Прогресс одного сбора. */
	public static class Progress {
		/** Собрано, в основной валюте (дробная часть возможна из-за ставок за битсы). */
		public double current;
		/** Сколько раз сбор закрывался (растёт после каждого сброса и нового закрытия). */
		public int completed;
		/** Сбор сейчас закрыт (цель достигнута и событие уже было). */
		public boolean done;
		public long completedAt;
		/** Количество вкладов. */
		public int contributions;
		public String lastUser = "";
		public double lastAmount;
		public long lastAt;

		Progress copy() {
			Progress copy = new Progress();
			copy.current = current;
			copy.completed = completed;
			copy.done = done;
			copy.completedAt = completedAt;
			copy.contributions = contributions;
			copy.lastUser = lastUser;
			copy.lastAmount = lastAmount;
			copy.lastAt = lastAt;
			return copy;
		}
	}

	/** Сбор + его прогресс: всё, что нужно для отрисовки полосы и строки в чате. */
	public record Line(ModConfig.Fundraiser fund, Progress progress, String currency) {
		public int target() {
			return Math.max(1, fund.target);
		}

		public double current() {
			return Math.max(0, progress.current);
		}

		public double left() {
			return Math.max(0, target() - current());
		}

		/** Процент без потолка (может быть 120 %). */
		public int percent() {
			return (int) Math.floor(current() * 100.0 / target());
		}

		/** Заполнение полосы 0..1. */
		public float fraction() {
			return (float) Math.max(0.0, Math.min(1.0, current() / target()));
		}

		public boolean done() {
			return progress.done;
		}

		public String symbol() {
			return TwitchEvent.currencySymbol(currency);
		}

		/** Текст над полосой: формат сбора с подставленными значениями и &-цветами. */
		public String label() {
			Map<String, String> vars = new LinkedHashMap<>();
			vars.put("title", fund.displayTitle());
			vars.put("name", fund.name == null ? "" : fund.name);
			vars.put("current", formatAmount(current()));
			vars.put("target", formatAmount(target()));
			vars.put("left", formatAmount(left()));
			vars.put("percent", String.valueOf(percent()));
			vars.put("currency", symbol());
			vars.put("last", progress.lastUser == null ? "" : progress.lastUser);
			vars.put("last_amount", formatAmount(progress.lastAmount));
			vars.put("donors", String.valueOf(progress.contributions));
			String format = fund.format == null || fund.format.isBlank() ? ModConfig.DEFAULT_FUND_FORMAT : fund.format;
			return Chat.colorize(Placeholders.apply(format, vars));
		}

		/** Короткая строка без цветов для чата и команд: «Сбор: 1 200 / 5 000 ₽ (24%)». */
		public String plainText() {
			return stripColors(fund.displayTitle()) + ": " + formatAmount(current()) + " / " + formatAmount(target()) + " " + symbol()
					+ " (" + percent() + "%)" + (progress.done ? " ✔" : "");
		}
	}

	private static class State {
		long updatedAt;
		Map<String, Progress> fundraisers = new LinkedHashMap<>();
	}

	private final TwitchCraftClient mod;
	private final Executor io;
	private final Path path;
	private final Map<String, Progress> progress = new LinkedHashMap<>();

	public FundraiserTracker(TwitchCraftClient mod) {
		this(mod, mod.worker(), FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME));
	}

	public FundraiserTracker(TwitchCraftClient mod, Executor io, Path path) {
		this.mod = mod;
		this.io = io;
		this.path = path;
		load();
	}

	// ---------- Учёт событий ----------

	/**
	 * Учитывает событие во всех включённых сборах.
	 *
	 * @return события «сбор закрыт» (по одному на каждый сбор, который закрылся этим вкладом)
	 */
	public synchronized List<TwitchEvent> onEvent(TwitchEvent event) {
		ModConfig config = mod.config();
		if (config == null || !config.isEnabled(Module.FUNDRAISERS) || config.fundraisers == null || event == null || event.synthetic()) {
			return List.of();
		}
		List<TwitchEvent> fired = new ArrayList<>();
		for (ModConfig.Fundraiser fund : config.fundraisers) {
			if (fund == null || !fund.enabled || fund.name == null || fund.name.isBlank()) {
				continue;
			}
			double add = contribution(fund, event);
			if (add <= 0) {
				continue;
			}
			fired.addAll(apply(fund, add, event.user(), event.userLogin()));
		}
		return fired;
	}

	/** Сколько единиц валюты даёт событие этому сбору (0 — не считается). */
	public static double contribution(ModConfig.Fundraiser fund, TwitchEvent event) {
		if (fund == null || event == null || event.synthetic()) {
			return 0;
		}
		int amount = Math.max(0, event.amount());
		return switch (event.type()) {
			case DONATION -> fund.countDonations ? amount : 0;
			case CHEER -> amount * Math.max(0, fund.bitsRate);
			case SUBSCRIBE, RESUB -> Math.max(0, fund.subValue);
			case GIFT_SUB -> Math.max(0, fund.subValue) * Math.max(1, amount);
			case REWARD -> amount * Math.max(0, fund.pointsRate);
			default -> 0;
		};
	}

	/**
	 * Ручной вклад (/twitch fund add): наличные, перевод на карту, что угодно. Может закрыть сбор.
	 * Отрицательная сумма уменьшает прогресс и, если сумма упала ниже цели, снова «открывает» сбор.
	 */
	public synchronized List<TwitchEvent> add(ModConfig.Fundraiser fund, double amount, String from) {
		if (fund == null || amount == 0 || Double.isNaN(amount) || Double.isInfinite(amount)) {
			return List.of();
		}
		return apply(fund, amount, from == null ? "" : from.trim(), "");
	}

	/**
	 * Установить собранную сумму (правка руками в настройках или /twitch fund set).
	 * Событие «сбор закрыт» при этом НЕ создаётся — только пересчитывается флаг закрытия.
	 */
	public synchronized void set(ModConfig.Fundraiser fund, double value) {
		if (fund == null || Double.isNaN(value) || Double.isInfinite(value)) {
			return;
		}
		Progress p = progressFor(fund.name);
		p.current = Math.max(0, value);
		int target = Math.max(1, fund.target);
		if (p.current < target) {
			p.done = false;
		} else if (!p.done) {
			p.done = true;
			p.completedAt = System.currentTimeMillis();
		}
		saveAsync();
	}

	private List<TwitchEvent> apply(ModConfig.Fundraiser fund, double add, String user, String login) {
		Progress p = progressFor(fund.name);
		long now = System.currentTimeMillis();
		p.current = Math.max(0, p.current + add);
		if (add > 0) {
			p.contributions++;
			p.lastUser = user == null ? "" : user;
			p.lastAmount = add;
			p.lastAt = now;
		}
		List<TwitchEvent> fired = new ArrayList<>();
		int target = Math.max(1, fund.target);
		if (!p.done && p.current >= target) {
			p.done = true;
			p.completed++;
			p.completedAt = now;
			fired.add(TwitchEvent.fund(fund.name, target, p.completed, currency(), user, login, false));
		} else if (p.done && p.current < target) {
			p.done = false; // сумму уменьшили — сбор снова открыт и сможет закрыться ещё раз
		}
		saveAsync();
		return fired;
	}

	// ---------- Чтение ----------

	public synchronized Progress progressFor(String fundName) {
		return progress.computeIfAbsent(key(fundName), k -> new Progress());
	}

	public synchronized Progress peek(String fundName) {
		Progress p = progress.get(key(fundName));
		return p == null ? new Progress() : p.copy();
	}

	public synchronized Line line(ModConfig.Fundraiser fund) {
		return new Line(fund, peek(fund.name), currency());
	}

	/** Все включённые сборы в порядке конфига. */
	public synchronized List<Line> lines() {
		List<Line> lines = new ArrayList<>();
		ModConfig config = mod.config();
		if (config == null || config.fundraisers == null) {
			return lines;
		}
		String currency = currency();
		for (ModConfig.Fundraiser fund : config.fundraisers) {
			if (fund != null && fund.enabled && fund.name != null && !fund.name.isBlank()) {
				lines.add(new Line(fund, peek(fund.name), currency));
			}
		}
		return lines;
	}

	/** Сборы, которые нужно рисовать на экране: включённые, видимые и не спрятанные после закрытия. */
	public synchronized List<Line> visibleLines() {
		List<Line> visible = new ArrayList<>();
		long now = System.currentTimeMillis();
		for (Line line : lines()) {
			ModConfig.Fundraiser fund = line.fund();
			if (!fund.visible) {
				continue;
			}
			if (line.done() && fund.hideWhenCompleteSeconds > 0
					&& now - line.progress().completedAt > fund.hideWhenCompleteSeconds * 1000L) {
				continue;
			}
			visible.add(line);
		}
		return visible;
	}

	/**
	 * Плейсхолдеры {fund} {fund_name} {fund_current} {fund_target} {fund_left} {fund_percent} {fund_currency}
	 * {fund_donors} — для «главного» сбора (первый видимый, иначе первый включённый).
	 */
	public synchronized Map<String, String> placeholders() {
		Map<String, String> vars = new LinkedHashMap<>();
		Line main = null;
		for (Line line : lines()) {
			if (line.fund().visible) {
				main = line;
				break;
			}
			if (main == null) {
				main = line;
			}
		}
		if (main == null) {
			vars.put("fund", "—");
			vars.put("fund_name", "");
			vars.put("fund_current", "0");
			vars.put("fund_target", "0");
			vars.put("fund_left", "0");
			vars.put("fund_percent", "0");
			vars.put("fund_currency", TwitchEvent.currencySymbol(currency()));
			vars.put("fund_donors", "0");
			return vars;
		}
		vars.put("fund", stripColors(main.fund().displayTitle()));
		vars.put("fund_name", main.fund().name);
		vars.put("fund_current", formatAmount(main.current()));
		vars.put("fund_target", formatAmount(main.target()));
		vars.put("fund_left", formatAmount(main.left()));
		vars.put("fund_percent", String.valueOf(main.percent()));
		vars.put("fund_currency", main.symbol());
		vars.put("fund_donors", String.valueOf(main.progress().contributions));
		return vars;
	}

	// ---------- Управление ----------

	/** Сбросить прогресс одного сбора (null — всех). */
	public synchronized void reset(String fundName) {
		if (fundName == null) {
			progress.clear();
		} else {
			progress.remove(key(fundName));
		}
		saveAsync();
	}

	/** Перенести прогресс при переименовании сбора в настройках. */
	public synchronized void rename(String oldName, String newName) {
		if (oldName == null || newName == null || key(oldName).equals(key(newName))) {
			return;
		}
		Progress p = progress.remove(key(oldName));
		if (p != null) {
			progress.put(key(newName), p);
			saveAsync();
		}
	}

	private String currency() {
		ModConfig config = mod.config();
		return config == null || config.donations == null || config.donations.currency == null ? "RUB" : config.donations.currency;
	}

	private static String key(String name) {
		return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
	}

	// ---------- Форматирование ----------

	/** 5000 → «5 000», 1234.5 → «1 234.50», 0 → «0». */
	public static String formatAmount(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return "0";
		}
		double rounded = Math.rint(value * 100.0) / 100.0;
		long whole = (long) Math.abs(rounded);
		long cents = Math.round((Math.abs(rounded) - whole) * 100.0);
		if (cents >= 100) {
			whole++;
			cents = 0;
		}
		String digits = Long.toString(whole);
		StringBuilder sb = new StringBuilder();
		int count = 0;
		for (int i = digits.length() - 1; i >= 0; i--) {
			sb.append(digits.charAt(i));
			if (++count % 3 == 0 && i > 0) {
				sb.append(' ');
			}
		}
		if (rounded < 0) {
			sb.append('-');
		}
		String result = sb.reverse().toString();
		if (cents > 0) {
			result += String.format(Locale.ROOT, ".%02d", cents);
		}
		return result;
	}

	/** Убирает &-коды цветов из текста (для чата Twitch и команд). */
	public static String stripColors(String text) {
		return text == null ? "" : text.replaceAll("[&§][0-9a-fk-orA-FK-OR]", "");
	}

	// ---------- Файл ----------

	private void load() {
		if (!Files.exists(path)) {
			return;
		}
		try {
			State state = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), State.class);
			if (state == null || state.fundraisers == null) {
				return;
			}
			for (Map.Entry<String, Progress> entry : state.fundraisers.entrySet()) {
				if (entry.getValue() != null) {
					if (entry.getValue().lastUser == null) {
						entry.getValue().lastUser = "";
					}
					progress.put(key(entry.getKey()), entry.getValue());
				}
			}
		} catch (Exception e) {
			TwitchCraftClient.LOGGER.warn("Не удалось прочитать {}: {}", path, e.toString());
		}
	}

	private void saveAsync() {
		State state = new State();
		state.updatedAt = System.currentTimeMillis();
		for (Map.Entry<String, Progress> entry : progress.entrySet()) {
			state.fundraisers.put(entry.getKey(), entry.getValue().copy());
		}
		String json = GSON.toJson(state);
		Runnable write = () -> {
			try {
				dev.dedworkshop.twitchcraft.util.SafeFiles.writeAtomic(path, json);
			} catch (IOException e) {
				TwitchCraftClient.LOGGER.warn("Не удалось сохранить прогресс сборов: {}", e.toString());
			}
		};
		try {
			io.execute(write);
		} catch (Exception e) {
			write.run();
		}
	}
}
