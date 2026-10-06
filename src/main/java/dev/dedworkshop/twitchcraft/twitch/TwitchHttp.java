package dev.dedworkshop.twitchcraft.twitch;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Маленькая обёртка над встроенным в Java HTTP-клиентом.
 * Никаких сторонних библиотек: всё, что нужно, уже есть в Java 25.
 */
public final class TwitchHttp {
	public static final HttpClient CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	/** Представляемся сервисам по-человечески, а не «Java-http-client» (некоторые прокси такое режут). */
	public static final String USER_AGENT = "TwitchCraft/" + version() + " (Minecraft Fabric mod)";

	private static String version() {
		try {
			return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("twitchcraft")
					.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
		} catch (Throwable t) {
			return "dev"; // вне игры (тесты) загрузчика нет
		}
	}

	private TwitchHttp() {
	}

	/** Ответ сервера: HTTP-статус и тело. */
	public record Response(int status, String body) {
		public boolean ok() {
			return status >= 200 && status < 300;
		}

		/** Тело ответа как JSON-объект (или пустой объект, если это не JSON). */
		public JsonObject json() {
			try {
				JsonElement element = JsonParser.parseString(body);
				return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
			} catch (Exception e) {
				return new JsonObject();
			}
		}

		/** Текст ошибки из поля "message", либо всё тело ответа. */
		public String errorMessage() {
			JsonObject json = json();
			if (json.has("message") && !json.get("message").isJsonNull()) {
				return json.get("message").getAsString();
			}
			return body == null ? "" : body;
		}
	}

	public static Response get(String url, Map<String, String> headers) throws IOException, InterruptedException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(20))
				.header("User-Agent", USER_AGENT)
				.GET();
		headers.forEach(builder::header);
		return send(builder.build());
	}

	public static Response postForm(String url, Map<String, String> form, Map<String, String> headers) throws IOException, InterruptedException {
		String body = form.entrySet().stream()
				.map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
				.collect(Collectors.joining("&"));
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(20))
				.header("User-Agent", USER_AGENT)
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
		headers.forEach(builder::header);
		return send(builder.build());
	}

	public static Response postJson(String url, JsonElement json, Map<String, String> headers) throws IOException, InterruptedException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(20))
				.header("User-Agent", USER_AGENT)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json.toString(), StandardCharsets.UTF_8));
		headers.forEach(builder::header);
		return send(builder.build());
	}

	public static Response patchJson(String url, JsonElement json, Map<String, String> headers) throws IOException, InterruptedException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(20))
				.header("User-Agent", USER_AGENT)
				.header("Content-Type", "application/json")
				.method("PATCH", HttpRequest.BodyPublishers.ofString(json.toString(), StandardCharsets.UTF_8));
		headers.forEach(builder::header);
		return send(builder.build());
	}

	private static Response send(HttpRequest request) throws IOException, InterruptedException {
		HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		return new Response(response.statusCode(), response.body());
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
