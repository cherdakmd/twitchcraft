import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import dev.dedworkshop.twitchcraft.util.Chat;
import dev.dedworkshop.twitchcraft.youtube.YoutubeLive;
import dev.dedworkshop.twitchcraft.youtube.YoutubeStore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end YouTube Live test against mock_youtube.py: OAuth refresh, polling/pageToken/maxResults,
 * нарастающая задержка и Retry-After, зрители эфира и квота Data API, очередь отправки,
 * управление трансляцией (transition/update) и модерация (бан, разбан, удаление сообщения).
 */
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

	/** Включить инъекцию сбоев для опроса чата: mode=500|429|quota|ended. */
	private static void injectFailure(String mode, int times, int retryAfter) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(MOCK + "/__fail?mode=" + mode
				+ "&times=" + times + "&retry_after=" + retryAfter)).GET().build();
		HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
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

	private static int chatCalls() {
		try {
			return state().getAsJsonArray("chat_calls").size();
		} catch (Exception e) {
			return -1;
		}
	}

	private static long chatCallTime(int index) {
		try {
			JsonArray calls = state().getAsJsonArray("chat_calls");
			if (calls.size() <= index) return -1;
			return calls.get(index).getAsJsonObject().get("time").getAsLong();
		} catch (Exception e) {
			return -1;
		}
	}

	private static int count(String key) {
		try {
			return state().getAsJsonArray(key).size();
		} catch (Exception e) {
			return -1;
		}
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
			config.youtube.showModeration = true;
			config.youtube.control = true;
			config.youtube.trackViewers = true;
			config.youtube.viewersIntervalSeconds = 15;
			config.youtube.pollMaxResults = 2000;
			config.youtube.quotaBudget = 0; // без пользовательского порога: проверяем только лимит Google
			config.youtube.defaultTimeoutSeconds = 300;
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
				&& waitFor(() -> chatCalls() >= 4, 12000));
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
		boolean pageSizes = !calls.isEmpty() && calls.stream()
				.allMatch(call -> "2000".equals(call.get("maxResults").getAsString()));
		check("nextPageToken is continued page by page", tokensContinue);
		check("pollingIntervalMillis is respected between list requests", intervalsRespected);
		check("youtube.pollMaxResults is sent as maxResults (fewer requests per quota unit)", pageSizes);
		check("liveBroadcasts query uses exactly one filter (broadcastStatus=active)", recorded.getAsJsonArray("broadcast_calls").size() > 0
				&& recorded.getAsJsonArray("broadcast_calls").get(0).getAsJsonObject().get("broadcastStatus").getAsString().equals("active")
				&& !recorded.getAsJsonArray("broadcast_calls").get(0).getAsJsonObject().has("mine"));

		System.out.println("== YouTube moderation, service messages and participants ==");
		check("userBannedEvent is counted as moderation and never becomes a game event",
				waitFor(() -> youtube.moderationSeen() >= 1, 8000) && mod.received.size() == 1);
		check("moderation is announced to the streamer when youtube.showModeration is on",
				Chat.lastText().contains("тайм-аут") && Chat.lastText().contains("Troll Viewer"));
		check("service messages (placeholderMessageEvent) are skipped without events",
				waitFor(() -> chatCalls() >= 5, 8000) && mod.received.size() == 1);
		check("chat participants are remembered for moderation by nickname", youtube.participantsCount() >= 3);

		System.out.println("== YouTube stream details and quota accounting ==");
		check("videos.list brings viewers, stream start time and title",
				waitFor(() -> count("video_calls") >= 1, 8000) && youtube.viewers() >= 8
				&& youtube.streamStartedAt() > 0 && youtube.broadcastTitle().equals("Тестовый эфир"));
		Map<String, String> vars = youtube.placeholders();
		check("YouTube placeholders are available to texts and timers",
				!vars.get("youtube_viewers").isBlank() && !vars.get("youtube_live_time").isBlank()
				&& vars.get("youtube_broadcast_url").equals("https://youtu.be/broadcast-1")
				&& vars.get("youtube_channel").equals("Channel Owner") && !vars.get("youtube_quota").isBlank());
		int chatCallsNow = chatCalls();
		int used = youtube.quota().used();
		check("quota units are counted per request (chat list 5, reads 1)",
				used >= 5 * chatCallsNow + count("video_calls") + 2
				&& youtube.quota().describe(9000).contains("сброс"));
		check("channel profile is cached: channels.list is not called on every poll",
				state().get("channel_calls").getAsInt() <= 2);

		System.out.println("== YouTube failure backoff and Retry-After ==");
		int before = chatCalls();
		injectFailure("500", 1, 0);
		check("a 5xx failure is retried with a growing delay (>= 2 s)",
				waitFor(() -> chatCalls() > before + 1, 15000)
				&& chatCallTime(before + 1) - chatCallTime(before) >= 2000);
		check("polling recovers after a transient failure",
				waitFor(() -> youtube.consecutiveFailures() == 0 && youtube.isActive(), 15000));
		before = chatCalls();
		injectFailure("429", 1, 3);
		check("Retry-After from Google is respected on 429",
				waitFor(() -> chatCalls() > before + 1, 20000)
				&& chatCallTime(before + 1) - chatCallTime(before) >= 3000);

		System.out.println("== YouTube outgoing chat queue ==");
		youtube.send("Первое сообщение", true);
		youtube.send("Второе сообщение", true);
		check("queued replies are sent to the active liveChatId", waitFor(() -> count("posts") >= 2, 9000));
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

		System.out.println("== YouTube broadcast control (transition / update) ==");
		youtube.transition("live", true);
		check("liveBroadcasts.transition is called for the active broadcast without a request body",
				waitFor(() -> count("transitions") >= 1, 8000)
				&& first(state().getAsJsonArray("transitions")).get("broadcastStatus").getAsString().equals("live")
				&& first(state().getAsJsonArray("transitions")).get("id").getAsString().equals("broadcast-1")
				&& first(state().getAsJsonArray("transitions")).getAsJsonObject("body").size() == 0);
		check("transition success is reported in game chat", Chat.lastText().contains("эфир начат"));
		youtube.transition("testing", true);
		check("redundantTransition is explained instead of failing",
				waitFor(() -> count("transitions") >= 2, 8000) && Chat.lastText().contains("уже в состоянии"));

		youtube.setTitle("Новый заголовок");
		check("title update sends the whole broadcast resource back (PUT liveBroadcasts)",
				waitFor(() -> count("broadcast_updates") >= 1, 8000));
		JsonObject update = first(state().getAsJsonArray("broadcast_updates"));
		JsonObject updateBody = update.getAsJsonObject("body");
		check("updated resource keeps required parts and the new title",
				updateBody.getAsJsonObject("snippet").get("title").getAsString().equals("Новый заголовок")
				&& updateBody.getAsJsonObject("status").get("privacyStatus").getAsString().equals("public")
				&& updateBody.getAsJsonObject("contentDetails").getAsJsonObject("monitorStream").get("enableMonitorStream").getAsBoolean()
				&& update.getAsJsonObject("query").get("part").getAsString().equals("snippet,status,contentDetails")
				&& youtube.broadcastTitle().equals("Новый заголовок"));

		System.out.println("== YouTube chat moderation commands ==");
		youtube.ban("New Viewer", 60);
		check("ban by nickname resolves the participant channelId and sends a temporary ban",
				waitFor(() -> count("bans") >= 1, 8000));
		JsonObject banBody = first(state().getAsJsonArray("bans")).getAsJsonObject("body").getAsJsonObject("snippet");
		check("ban body carries liveChatId, type, duration and the banned channel",
				banBody.get("liveChatId").getAsString().equals("live-chat-1")
				&& banBody.get("type").getAsString().equals("temporary")
				&& banBody.get("banDurationSeconds").getAsInt() == 60
				&& banBody.getAsJsonObject("bannedUserDetails").get("channelId").getAsString().equals("UC-viewer")
				&& Chat.lastText().contains("тайм-аут"));
		youtube.ban("Viewer", 60);
		check("an ambiguous nickname is not banned and the variants are listed",
				Chat.lastText().contains("подходит нескольким зрителям") && count("bans") == 1);
		youtube.deleteMessage("New Viewer");
		check("delete removes the last message of the resolved viewer",
				waitFor(() -> count("deletes") >= 1, 8000)
				&& first(state().getAsJsonArray("deletes")).get("id").getAsString().equals("new-message"));
		youtube.unban("New Viewer");
		check("unban reuses the ban id returned by YouTube",
				waitFor(() -> count("unbans") >= 1, 8000)
				&& first(state().getAsJsonArray("unbans")).get("id").getAsString().equals("ban-1"));

		System.out.println("== YouTube quota guard and upcoming broadcast lookup ==");
		youtube.disconnect();
		check("manual disconnect stops polling", !youtube.isActive());
		// Дать завершиться запросу, который уже был в полёте, прежде чем считать вызовы.
		Thread.sleep(1500);
		mod.config.youtube.quotaBudget = 1;
		int callsBefore = chatCalls();
		int broadcastsBefore = count("broadcast_calls");
		youtube.connect(true);
		Thread.sleep(2500);
		check("the daily quota budget stops polling instead of burning requests",
				chatCalls() == callsBefore && count("broadcast_calls") == broadcastsBefore
				&& youtube.statusText().contains("квота") && Chat.lastText().contains("квота"));
		mod.config.youtube.quotaBudget = 0;
		youtube.connect(true);
		check("polling resumes once the budget allows it", waitFor(() -> chatCalls() > callsBefore, 15000));

		youtube.disconnect();
		youtube.transition("complete", true);
		check("without an active broadcast the mod controls the nearest upcoming one",
				waitFor(() -> count("transitions") >= 3, 8000)
				&& last(state().getAsJsonArray("transitions")).get("id").getAsString().equals("broadcast-upcoming")
				&& last(state().getAsJsonArray("transitions")).get("broadcastStatus").getAsString().equals("complete"));

		mod.close();
		System.out.println(failures == 0 ? "\nALL " + total + " YOUTUBE TESTS PASSED" : "\nFAILURES: " + failures + " of " + total + " YOUTUBE TESTS");
		System.exit(failures == 0 ? 0 : 1);
	}

	private static JsonObject first(JsonArray array) {
		return array.size() > 0 && array.get(0).isJsonObject() ? array.get(0).getAsJsonObject() : new JsonObject();
	}

	private static JsonObject last(JsonArray array) {
		return array.size() > 0 && array.get(array.size() - 1).isJsonObject()
				? array.get(array.size() - 1).getAsJsonObject() : new JsonObject();
	}
}
