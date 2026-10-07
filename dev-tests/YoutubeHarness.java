import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import dev.dedworkshop.twitchcraft.youtube.YoutubeLive;
import dev.dedworkshop.twitchcraft.youtube.YoutubeStore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** End-to-end polling, page-token, token-refresh, and outgoing queue test against mock_youtube.py. */
public class YoutubeHarness {
	private static final String MOCK = "http://127.0.0.1:8087";
	private static int failures;
	private static int total;

	private static void check(String label, boolean condition) {
		total++;
		System.out.println((condition ? "  OK   " : "  FAIL ") + label);
		if (!condition) failures++;
	}

	private static JsonObject state() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(MOCK + "/__state")).GET().build();
		String body = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body();
		return JsonParser.parseString(body).getAsJsonObject();
	}

	private static boolean waitFor(java.util.function.BooleanSupplier predicate, long timeoutMillis) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (System.currentTimeMillis() < deadline) {
			if (predicate.getAsBoolean()) return true;
			Thread.sleep(50);
		}
		return predicate.getAsBoolean();
	}

	private static List<JsonObject> objects(JsonArray array) {
		List<JsonObject> values = new ArrayList<>();
		for (var element : array) if (element.isJsonObject()) values.add(element.getAsJsonObject());
		return values;
	}

	private static class TestMod extends TwitchCraftClient {
		private final ModConfig config = ModConfig.createDefault();
		private final YoutubeStore store = new YoutubeStore();
		private final ExecutorService worker = Executors.newSingleThreadExecutor();
		private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
		private final List<TwitchEvent> received = Collections.synchronizedList(new ArrayList<>());
		private final CountDownLatch newChat = new CountDownLatch(1);
		private YoutubeLive youtube;

		TestMod() {
			config.youtube.clientId = "desktop-client";
			config.youtube.paidMessages = true;
			config.youtube.memberships = true;
			store.accessToken = "yt-access-1-expired";
			store.refreshToken = "yt-refresh-1";
			store.expiresAt = System.currentTimeMillis() - 1000;
			store.scopes = dev.dedworkshop.twitchcraft.youtube.YoutubeApi.SCOPES;
		}

		@Override public ModConfig config() { return config; }
		@Override public YoutubeStore youtubeStore() { return store; }
		@Override public ExecutorService worker() { return worker; }
		@Override public ScheduledExecutorService scheduler() { return scheduler; }
		@Override public boolean isModuleEnabled(Module module) { return config.isEnabled(module); }
		@Override public YoutubeLive youtube() { return youtube; }
		@Override public void onTwitchEvent(TwitchEvent event) {
			received.add(event);
			if (event.type() == TwitchEvent.Type.CHAT) newChat.countDown();
		}

		void close() {
			if (youtube != null) youtube.shutdown();
			worker.shutdownNow();
			scheduler.shutdownNow();
		}
	}

	public static void main(String[] args) throws Exception {
		TestMod mod = new TestMod();
		YoutubeLive youtube = new YoutubeLive(mod);
		mod.youtube = youtube;

		System.out.println("== YouTube API token refresh and live-chat polling ==");
		check("configured module and OAuth read/write scopes", youtube.isConfigured() && mod.isModuleEnabled(Module.YOUTUBE_LIVE)
				&& mod.youtubeStore().canReadChat() && mod.youtubeStore().canWriteChat());
		youtube.connect(true);
		check("discovers the authenticated channel and active broadcast", waitFor(youtube::isActive, 5000)
				&& waitFor(() -> {
					try { return state().getAsJsonArray("chat_calls").size() >= 3; }
					catch (Exception e) { return false; }
				}, 8000));
		check("first poll is skipped; only new chat is dispatched with YouTube platform", mod.newChat.await(2, TimeUnit.SECONDS)
				&& mod.received.size() == 1 && mod.received.get(0).type() == TwitchEvent.Type.CHAT
				&& mod.received.get(0).isYoutube() && mod.received.get(0).userId().equals("UC-viewer")
				&& mod.received.get(0).message().equals("!new hello"));
		check("expired access token refreshed once using stored refresh token", mod.store.accessToken.equals("yt-access-2")
				&& mod.store.refreshToken.equals("yt-refresh-2") && mod.store.expiresAt > System.currentTimeMillis());

		JsonObject recorded = state();
		List<JsonObject> calls = objects(recorded.getAsJsonArray("chat_calls"));
		boolean tokensContinue = calls.size() >= 3 && calls.get(0).get("pageToken").getAsString().isBlank()
				&& calls.get(1).get("pageToken").getAsString().equals("PAGE-1")
				&& calls.get(2).get("pageToken").getAsString().equals("PAGE-2");
		boolean intervalsRespected = calls.size() >= 3
				&& calls.get(1).get("time").getAsLong() - calls.get(0).get("time").getAsLong() >= 1000
				&& calls.get(2).get("time").getAsLong() - calls.get(1).get("time").getAsLong() >= 1000;
		check("nextPageToken is continued page by page", tokensContinue);
		check("pollingIntervalMillis is respected between list requests", intervalsRespected);
		check("liveBroadcasts query uses exactly one filter (broadcastStatus=active)", recorded.getAsJsonArray("broadcast_calls").size() > 0
				&& recorded.getAsJsonArray("broadcast_calls").get(0).getAsJsonObject().get("broadcastStatus").getAsString().equals("active")
				&& !recorded.getAsJsonArray("broadcast_calls").get(0).getAsJsonObject().has("mine"));

		System.out.println("== YouTube outgoing chat queue ==");
		youtube.send("Первое сообщение", true);
		youtube.send("Второе сообщение", true);
		check("queued replies are sent to the active liveChatId", waitFor(() -> {
			try { return state().getAsJsonArray("posts").size() >= 2; }
			catch (Exception e) { return false; }
		}, 9000));
		recorded = state();
		JsonArray posts = recorded.getAsJsonArray("posts");
		boolean postBody = posts.size() >= 2
				&& posts.get(0).getAsJsonObject().getAsJsonObject("body").getAsJsonObject("snippet").get("liveChatId").getAsString().equals("live-chat-1")
				&& posts.get(0).getAsJsonObject().getAsJsonObject("body").getAsJsonObject("snippet").getAsJsonObject("textMessageDetails").get("messageText").getAsString().equals("Первое сообщение")
				&& posts.get(1).getAsJsonObject().getAsJsonObject("body").getAsJsonObject("snippet").getAsJsonObject("textMessageDetails").get("messageText").getAsString().equals("Второе сообщение");
		boolean sendIntervals = posts.size() >= 2
				&& posts.get(1).getAsJsonObject().get("time").getAsLong() - posts.get(0).getAsJsonObject().get("time").getAsLong() >= 1800;
		check("outgoing JSON body contains liveChatId and text", postBody);
		check("outgoing chat queue spaces messages to respect rate limits", sendIntervals);
		check("active connection status reports YouTube chat", youtube.statusText().contains("подключено к live chat"));

		youtube.disconnect();
		check("manual disconnect stops polling", !youtube.isActive());
		mod.close();
		System.out.println(failures == 0 ? "\nALL " + total + " YOUTUBE TESTS PASSED" : "\nFAILURES: " + failures + " of " + total + " YOUTUBE TESTS");
		System.exit(failures == 0 ? 0 : 1);
	}
}
