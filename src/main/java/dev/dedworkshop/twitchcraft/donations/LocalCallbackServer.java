package dev.dedworkshop.twitchcraft.donations;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Крошечный HTTP-сервер на 127.0.0.1 для приёма токена после входа в DonationAlerts (OAuth implicit)
 * или кода авторизации VK Video Live (OAuth code). Браузер открывает http://localhost:PORT/da#access_token=...
 * (страница пересылает хэш на /da/token?...) либо http://localhost:PORT/vk?code=...; сервер отдаёт результат
 * моду и закрывается. Без зависимостей от jdk.httpserver.
 */
public final class LocalCallbackServer implements Closeable {
	private final int port;
	private final String path;
	private final String expectedState;
	private final String service;
	private final String retryHint;
	private final Consumer<Map<String, String>> onToken;
	private volatile ServerSocket server;
	private volatile boolean closed;
	private boolean consumed;

	public LocalCallbackServer(int port, String path, Consumer<Map<String, String>> onToken) {
		this(port, path, null, onToken);
	}

	/**
	 * @param expectedState значение OAuth-параметра {@code state}, которое мы отправили в ссылке входа.
	 *                      Если сервис вернул {@code state} и он не совпал — токен отклоняется (защита от подмены
	 *                      токена чужой страницей, login-CSRF). {@code null} — не проверять.
	 */
	public LocalCallbackServer(int port, String path, String expectedState, Consumer<Map<String, String>> onToken) {
		this(port, path, expectedState, "DonationAlerts", "/twitch donations da login", onToken);
	}

	/**
	 * @param service   название сервиса для страниц в браузере («DonationAlerts», «VK Video Live»)
	 * @param retryHint команда, которой можно начать вход заново
	 */
	public LocalCallbackServer(int port, String path, String expectedState, String service, String retryHint, Consumer<Map<String, String>> onToken) {
		this.port = port;
		this.path = path;
		this.expectedState = expectedState;
		this.service = service;
		this.retryHint = retryHint;
		this.onToken = onToken;
	}

	/** Случайный одноразовый {@code state} для ссылки входа. */
	public static String newState() {
		byte[] bytes = new byte[18];
		new SecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	public void start() throws IOException {
		server = new ServerSocket(port, 8, InetAddress.getLoopbackAddress());
		Thread thread = new Thread(this::loop, "TwitchCraft-Callback");
		thread.setDaemon(true);
		thread.start();
	}

	public boolean isRunning() {
		return !closed && server != null && !server.isClosed();
	}

	private void loop() {
		while (!closed) {
			try (Socket socket = server.accept()) {
				socket.setSoTimeout(5000);
				handle(socket);
			} catch (IOException e) {
				if (!closed) {
					TwitchCraftClient.LOGGER.debug("Callback-сервер: {}", e.toString());
				}
			} catch (Exception e) {
				TwitchCraftClient.LOGGER.warn("Callback-сервер: ошибка обработки запроса", e);
			}
		}
	}

	private void handle(Socket socket) throws IOException {
		BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
		String requestLine = reader.readLine();
		if (requestLine == null) {
			return;
		}
		String line;
		Map<String, String> headers = new HashMap<>();
		while ((line = reader.readLine()) != null && !line.isEmpty()) {
			int colon = line.indexOf(':');
			if (colon > 0) {
				headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
			}
		}
		String[] parts = requestLine.split(" ");
		String target = parts.length >= 2 ? parts[1] : "/";
		String query = "";
		int q = target.indexOf('?');
		if (q >= 0) {
			query = target.substring(q + 1);
			target = target.substring(0, q);
		}
		OutputStream out = socket.getOutputStream();
		if (target.equals(path + "/token")) {
			Map<String, String> params = parseQuery(query);
			// Запрос с чужого сайта (картинка/fetch со стороннего origin): браузер сам помечает его как cross-site,
			// подделать этот заголовок страница не может. Наша страница делает same-origin запрос.
			String fetchSite = headers.getOrDefault("sec-fetch-site", "");
			if (fetchSite.equalsIgnoreCase("cross-site")) {
				TwitchCraftClient.LOGGER.warn("Callback-сервер: отклонён запрос токена с чужого сайта (Sec-Fetch-Site=cross-site)");
				respond(out, 403, page("Отклонено", "Запрос пришёл не со страницы " + service + ". Открой ссылку входа из игры заново."));
				return;
			}
			if (expectedState != null && params.containsKey("state") && !expectedState.equals(params.get("state"))) {
				TwitchCraftClient.LOGGER.warn("Callback-сервер: параметр state не совпал — токен отклонён");
				respond(out, 400, page("Отклонено", "Параметр state не совпал. Начни вход заново: " + retryHint));
				return;
			}
			if (params.containsKey("access_token")) {
				synchronized (this) {
					if (consumed) {
						respond(out, 200, page("Готово!", "Токен уже получен. Вернись в игру."));
						return;
					}
					consumed = true;
				}
				if (expectedState != null && !params.containsKey("state")) {
					TwitchCraftClient.LOGGER.warn("Callback-сервер: {} не вернул параметр state — принимаю токен без сверки", service);
				}
				respond(out, 200, page("Готово!", "Вход в " + service + " выполнен. Эту вкладку можно закрыть и вернуться в игру."));
				try {
					onToken.accept(params);
				} finally {
					close();
				}
			} else {
				String error = params.getOrDefault("error_description", params.getOrDefault("error", "токен не получен"));
				respond(out, 400, page("Ошибка", service + " не выдал токен: " + escape(error) + ". Попробуй ещё раз: " + retryHint));
			}
		} else if (target.equals(path) || target.equals(path + "/")) {
			Map<String, String> params = parseQuery(query);
			if (params.containsKey("error")) {
				respond(out, 200, page("Доступ не выдан", "Ты отклонил(а) запрос или произошла ошибка: "
						+ escape(params.getOrDefault("error_description", params.get("error"))) + ". Вернись в игру и попробуй снова."));
			} else if (params.containsKey("code")) {
				// OAuth Authorization Code: код приходит прямо в адресе (?code=...&state=...), без хэша
				if (expectedState != null && params.containsKey("state") && !expectedState.equals(params.get("state"))) {
					TwitchCraftClient.LOGGER.warn("Callback-сервер: параметр state не совпал — код отклонён");
					respond(out, 400, page("Отклонено", "Параметр state не совпал. Начни вход заново: " + retryHint));
					return;
				}
				synchronized (this) {
					if (consumed) {
						respond(out, 200, page("Готово!", "Код уже получен. Вернись в игру."));
						return;
					}
					consumed = true;
				}
				if (expectedState != null && !params.containsKey("state")) {
					TwitchCraftClient.LOGGER.warn("Callback-сервер: {} не вернул параметр state — принимаю код без сверки", service);
				}
				respond(out, 200, page("Готово!", "Вход в " + service + " выполнен. Эту вкладку можно закрыть и вернуться в игру."));
				try {
					onToken.accept(params);
				} finally {
					close();
				}
			} else {
				respond(out, 200, landing());
			}
		} else {
			respond(out, 404, page("Не найдено", "Эта страница служебная. Вернись в игру."));
		}
	}

	/** Страница, которая пересылает токен из хэша адреса (#access_token=...) на сервер. */
	private String landing() {
		return "<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\"><title>TwitchCraft</title></head>"
				+ "<body style=\"font-family:sans-serif;background:#1b1b1f;color:#eee;text-align:center;padding-top:15vh\">"
				+ "<h2 id=\"t\">Завершаю вход в " + service + "…</h2><p id=\"m\">Подожди секунду.</p>"
				+ "<script>(function(){var h=location.hash?location.hash.substring(1):'';"
				+ "if(!h){document.getElementById('t').textContent='Токен не найден';document.getElementById('m').textContent='Открой эту страницу по ссылке из игры.';return;}"
				+ "fetch('" + path + "/token?'+h).then(function(r){return r.text();}).then(function(x){document.open();document.write(x);document.close();})"
				+ ".catch(function(e){document.getElementById('t').textContent='Ошибка';document.getElementById('m').textContent=String(e);});})();</script>"
				+ "</body></html>";
	}

	private static String page(String title, String text) {
		return "<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\"><title>TwitchCraft</title></head>"
				+ "<body style=\"font-family:sans-serif;background:#1b1b1f;color:#eee;text-align:center;padding-top:15vh\">"
				+ "<h2>" + title + "</h2><p>" + text + "</p></body></html>";
	}

	private static void respond(OutputStream out, int status, String html) throws IOException {
		byte[] body = html.getBytes(StandardCharsets.UTF_8);
		String reason = status == 200 ? "OK" : status == 400 ? "Bad Request" : status == 403 ? "Forbidden" : "Not Found";
		String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
				+ "Content-Type: text/html; charset=utf-8\r\n"
				+ "Content-Length: " + body.length + "\r\n"
				+ "Cache-Control: no-store\r\n"
				+ "Connection: close\r\n\r\n";
		out.write(head.getBytes(StandardCharsets.US_ASCII));
		out.write(body);
		out.flush();
	}

	static Map<String, String> parseQuery(String query) {
		Map<String, String> result = new LinkedHashMap<>();
		if (query == null || query.isEmpty()) {
			return result;
		}
		for (String pair : query.split("&")) {
			int eq = pair.indexOf('=');
			String key = eq >= 0 ? pair.substring(0, eq) : pair;
			String value = eq >= 0 ? pair.substring(eq + 1) : "";
			try {
				result.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
			} catch (IllegalArgumentException e) {
				result.put(key, value);
			}
		}
		return result;
	}

	private static String escape(String text) {
		return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	@Override
	public void close() {
		closed = true;
		ServerSocket s = server;
		if (s != null) {
			try {
				s.close();
			} catch (IOException ignored) {
			}
		}
	}
}
