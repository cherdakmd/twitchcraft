package dev.dedworkshop.twitchcraft.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Раскладка таблички чата: перенос текста по словам, поля, стопка и затухание.
 * Без Minecraft: ширину текста отдаёт вызывающий код (в игре это {@code Font.width}), поэтому проверяется в LogicTest.
 */
public final class ChatSignLayout {
	/** Поля вокруг текста, пиксели GUI при масштабе 1. */
	public static final int PAD = 4;
	/** Шаг строки: шрифт 9 px + 1 px. */
	public static final int LINE = 10;
	/** Зазор между табличками в стопке, пиксели GUI. */
	public static final int GAP = 3;
	/** Ширина текста одной строки, пиксели GUI при масштабе 1. */
	public static final int MAX_TEXT_WIDTH = 160;
	/** Сколько строк сообщения показываем; остальное заменяется на «…». */
	public static final int MAX_MESSAGE_LINES = 3;
	/** Табличка появляется за это время, миллисекунды. */
	public static final long FADE_IN_MS = 250;
	/**
	 * Досягаемость для разбивания кликом, блоки: около 6, как дистанция по умолчанию. Ванильная рука достаёт 4.5,
	 * но табличка появляется ровно на дистанции, и её должно быть можно разбить, стоя на месте.
	 */
	public static final double BREAK_REACH = 6.0;
	/** Допуск к досягаемости, блоки: за тик игрок смещается примерно на 0.2, табличка уже поставлена на старом месте. */
	private static final double REACH_TOLERANCE = 0.25;

	/** Кусок строки одного цвета (ARGB). */
	public record Segment(String text, int argb) {
	}

	/** Строка таблички и её ширина, пиксели GUI при масштабе 1. */
	public record Row(List<Segment> segments, int width) {
	}

	/** Табличка целиком при масштабе 1. */
	public record Layout(List<Row> rows, int width, int height) {
	}

	private ChatSignLayout() {
	}

	/**
	 * Собирает табличку: первая строка — шапка (ник, значки, метка платформы), дальше — текст сообщения.
	 */
	public static Layout layout(List<Segment> header, String message, int messageArgb, ToIntFunction<String> measure) {
		List<Row> rows = new ArrayList<>();
		rows.add(row(fit(header, MAX_TEXT_WIDTH, measure), measure));
		for (String line : wrap(message, MAX_TEXT_WIDTH, MAX_MESSAGE_LINES, measure)) {
			rows.add(row(List.of(new Segment(line, messageArgb)), measure));
		}
		int width = 0;
		for (Row r : rows) {
			width = Math.max(width, r.width());
		}
		return new Layout(rows, width + PAD * 2, rows.size() * LINE + PAD * 2 - 1);
	}

	/**
	 * Перенос по словам: не больше {@code maxLines} строк шириной не больше {@code maxWidth}.
	 * Если текст не влез, последняя строка заканчивается на «…». Слово шире строки режется по символам.
	 */
	public static List<String> wrap(String text, int maxWidth, int maxLines, ToIntFunction<String> measure) {
		List<String> lines = new ArrayList<>();
		if (text == null || maxLines <= 0) {
			return lines;
		}
		String clean = text.trim();
		if (clean.isEmpty()) {
			return lines;
		}
		List<String> all = greedy(clean.split("\\s+"), maxWidth, measure);
		if (all.size() <= maxLines) {
			return all;
		}
		lines.addAll(all.subList(0, maxLines));
		lines.set(maxLines - 1, ellipsize(lines.get(maxLines - 1), maxWidth, measure));
		return lines;
	}

	/**
	 * Шапка помещается в {@code maxWidth}: сокращается самый широкий кусок (обычно ник), в конце ставится «…».
	 * Цвет куска сохраняется.
	 */
	public static List<Segment> fit(List<Segment> header, int maxWidth, ToIntFunction<String> measure) {
		List<Segment> segments = new ArrayList<>(header);
		while (!segments.isEmpty() && segmentsWidth(segments, measure) > maxWidth) {
			int widest = 0;
			for (int i = 1; i < segments.size(); i++) {
				if (measure.applyAsInt(segments.get(i).text()) > measure.applyAsInt(segments.get(widest).text())) {
					widest = i;
				}
			}
			Segment segment = segments.get(widest);
			String text = segment.text();
			if (text.length() <= 1) {
				segments.remove(widest);
				continue;
			}
			String base = text.endsWith("…") ? text.substring(0, text.length() - 1) : text;
			base = base.substring(0, Math.max(0, base.length() - 1));
			segments.set(widest, new Segment(base + "…", segment.argb()));
		}
		return segments;
	}

	/**
	 * Смещения стопки в пикселях экрана. {@code heights} — высоты табличек от самой новой к самой старой:
	 * новая стоит у базы, каждая следующая — над предыдущими.
	 */
	public static double[] stackOffsets(double[] heights) {
		double[] offsets = new double[heights.length];
		double acc = 0;
		for (int i = 0; i < heights.length; i++) {
			offsets[i] = acc;
			acc += heights[i] + GAP;
		}
		return offsets;
	}

	/**
	 * Прозрачность 0–255: за {@link #FADE_IN_MS} табличка появляется, а за {@code fadeOutMs} до конца жизни гаснет.
	 */
	public static int alpha(long ageMs, long remainingMs, long fadeOutMs) {
		double in = Math.min(1.0, Math.max(0, ageMs) / (double) FADE_IN_MS);
		double out = fadeOutMs <= 0 ? 1.0 : Math.min(1.0, Math.max(0, remainingMs) / (double) fadeOutMs);
		return (int) Math.round(255 * in * out);
	}

	/** Попадает ли точка (px, py) в прямоугольник [x0, x1] × [y0, y1], края включены. */
	public static boolean contains(double x0, double y0, double x1, double y1, double px, double py) {
		return px >= x0 && px <= x1 && py >= y0 && py <= y1;
	}

	/**
	 * Табличку под прицелом можно разбить кликом, если она в досягаемости ({@link #BREAK_REACH}) и ближе блока или моба
	 * за ней. Если прицел ни во что не попал ({@link Double#POSITIVE_INFINITY}), важна только досягаемость.
	 */
	public static boolean breakable(double plaqueDepth, double solidDistance) {
		return plaqueDepth <= BREAK_REACH + REACH_TOLERANCE && plaqueDepth < solidDistance;
	}

	/** Текст без §-кодов цвета (например, метка платформы из префикса чата). */
	public static String plain(String text) {
		return text == null ? "" : text.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
	}

	private static Row row(List<Segment> segments, ToIntFunction<String> measure) {
		return new Row(segments, segmentsWidth(segments, measure));
	}

	private static int segmentsWidth(List<Segment> segments, ToIntFunction<String> measure) {
		int width = 0;
		for (Segment segment : segments) {
			width += measure.applyAsInt(segment.text());
		}
		return width;
	}

	/** Жадный перенос без ограничения по числу строк. */
	private static List<String> greedy(String[] words, int maxWidth, ToIntFunction<String> measure) {
		List<String> lines = new ArrayList<>();
		String current = "";
		for (String word : words) {
			if (word.isEmpty()) {
				continue;
			}
			if (measure.applyAsInt(word) > maxWidth) {
				// Слово шире строки: режем по символам, чтобы не вылезти за табличку
				if (!current.isEmpty()) {
					lines.add(current);
					current = "";
				}
				String rest = word;
				while (!rest.isEmpty()) {
					int cut = fittingPrefix(rest, maxWidth, measure);
					String piece = rest.substring(0, cut);
					rest = rest.substring(cut);
					if (rest.isEmpty()) {
						current = piece;
					} else {
						lines.add(piece);
					}
				}
				continue;
			}
			String candidate = current.isEmpty() ? word : current + " " + word;
			if (measure.applyAsInt(candidate) <= maxWidth) {
				current = candidate;
			} else {
				lines.add(current);
				current = word;
			}
		}
		if (!current.isEmpty()) {
			lines.add(current);
		}
		return lines;
	}

	/** Самая длинная начальная часть строки, которая влезает в ширину; минимум один символ. */
	private static int fittingPrefix(String s, int maxWidth, ToIntFunction<String> measure) {
		int lo = 1;
		int hi = s.length();
		int best = 1;
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			if (measure.applyAsInt(s.substring(0, mid)) <= maxWidth) {
				best = mid;
				lo = mid + 1;
			} else {
				hi = mid - 1;
			}
		}
		// не разрываем суррогатную пару (эмодзи)
		if (best > 1 && best < s.length() && Character.isHighSurrogate(s.charAt(best - 1))) {
			best--;
		}
		return best;
	}

	private static String ellipsize(String line, int maxWidth, ToIntFunction<String> measure) {
		String base = line.stripTrailing();
		while (!base.isEmpty() && measure.applyAsInt(base + "…") > maxWidth) {
			base = base.substring(0, base.length() - 1).stripTrailing();
		}
		return base + "…";
	}
}
