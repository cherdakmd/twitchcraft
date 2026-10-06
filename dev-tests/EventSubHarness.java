import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.config.TokenStore;
import dev.dedworkshop.twitchcraft.twitch.ChatSender;
import dev.dedworkshop.twitchcraft.twitch.EventSubClient;
import dev.dedworkshop.twitchcraft.twitch.RewardManager;
import dev.dedworkshop.twitchcraft.twitch.TwitchApi;
import dev.dedworkshop.twitchcraft.twitch.TwitchAuth;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

/** Интеграционный тест EventSubClient / RewardManager / ChatSender против mock_twitch.py (без Minecraft). */
public class EventSubHarness {
    static int failures = 0;
    static int total = 0;
    static void check(String name, boolean cond) {
        total++;
        System.out.println((cond ? "  OK   " : "  FAIL ") + name);
        if (!cond) failures++;
    }

    static class TestMod extends TwitchCraftClient {
        final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final TokenStore tokens = new TokenStore();
        final ModConfig config = ModConfig.createDefault();
        final TwitchApi api = new TwitchApi(this);
        final List<TwitchEvent> received = Collections.synchronizedList(new ArrayList<>());

        TestMod() {
            tokens.accessToken = "test-token";
            tokens.userId = "42";
            tokens.displayName = "DedWorkshop";
            tokens.scopes = new ArrayList<>(TwitchAuth.ALL_SCOPES);
            config.clientId = "test-client";
        }
        @Override public ScheduledExecutorService scheduler() { return scheduler; }
        @Override public ExecutorService worker() { return worker; }
        @Override public TokenStore tokens() { return tokens; }
        @Override public ModConfig config() { return config; }
        @Override public TwitchApi api() { return api; }
        @Override public void onTwitchEvent(TwitchEvent event) {
            System.out.println("  >> EVENT " + event.type() + " " + event.user() + " " + event.amount() + " " + event.message());
            received.add(event);
        }
    }

    static String helix(String path) throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:8081" + path)).build(), HttpResponse.BodyHandlers.ofString());
        return r.body();
    }

    static String helixPost(String path, String body) throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:8081" + path))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        return r.body();
    }

    static int count(String json, String token) {
        int c = 0, i = 0;
        while ((i = json.indexOf(token, i)) >= 0) { c++; i += token.length(); }
        return c;
    }

    public static void main(String[] args) throws Exception {
        TestMod mod = new TestMod();
        EventSubClient client = new EventSubClient(mod);

        System.out.println("== Этап 0: RewardManager и ChatSender через Helix ==");
        RewardManager rewards = new RewardManager(mod);
        ChatSender chat = new ChatSender(mod);
        check("rewards.available / chat.available по scope", rewards.available() && chat.available());
        TwitchEvent redemption = new TwitchEvent(TwitchEvent.Type.REWARD, "Viewer", "viewer", "7", 300, "", "Зомби", "",
                "r1", "red-1", Set.of(), "", "", false);
        rewards.fulfill(redemption);
        TwitchEvent foreign = new TwitchEvent(TwitchEvent.Type.REWARD, "Viewer", "viewer", "7", 300, "", "Чужая", "",
                "foreign", "red-2", Set.of(), "", "", false);
        rewards.refund(foreign, "тест 403");
        rewards.refund(foreign, "второй раз — уже не должно быть запроса");
        rewards.fulfill(redemption.asSynthetic()); // тестовое событие — без запроса
        chat.send("Привет из §aтеста", true);
        chat.reply("второе сообщение");
        rewards.sync();
        Thread.sleep(4500);
        String state = helix("/__state");
        System.out.println("     state: " + state);
        check("FULFILLED отправлен для red-1 ровно один раз", count(state, "\"red-1\"") == 1 && state.contains("FULFILLED"));
        check("чужая награда: 403 запомнен, CANCELED не записан", !state.contains("red-2"));
        check("чат: 2 сообщения, цвета убраны", count(state, "\"message\"") == 2 && state.contains("Привет из теста") && state.contains("второе сообщение"));
        int created = count(state, "\"title\"") - 1; // минус уже существующая на канале «Зомби»
        check("rewards sync: созданы награды из конфига по умолчанию («Пакость» и «Подарок» по 250), «*» пропущен, «Зомби» не тронут",
              created == ModConfig.createDefault().rewards.size() - 1 && state.contains("Пакость") && state.contains("Подарок")
              && count(state, "\"cost\": 250") == 2 && count(state, "\"title\": \"Зомби\"") == 1);

        System.out.println("== Этап 0b (1.7.0): стрим, метки и клипы через Helix ==");
        TwitchApi.Stream live = mod.api.getStream("42");
        check("getStream: в эфире, 17 зрителей, started_at и заголовок разобраны", live.live() && live.viewers() == 17 && live.startedAt() > 0
              && live.title().equals("Тестовый стрим") && live.game().equals("Minecraft"));
        dev.dedworkshop.twitchcraft.twitch.ClipManager clips = new dev.dedworkshop.twitchcraft.twitch.ClipManager(mod);
        check("canClip/canMark по scope (clips:edit, channel:manage:broadcast)", clips.canClip() && clips.canMark());
        check("marker() принят к отправке", clips.marker("Тестовая метка", true));
        check("clip() принят к отправке", clips.clip("тест", true));
        check("повторный clip() сразу — кулдаун", !clips.clip("ещё", false));
        Thread.sleep(1500);
        state = helix("/__state");
        check("Helix: метка создана с описанием", state.contains("\"description\": \"Тестовая метка\"") && clips.markersCreated() == 1);
        check("Helix: клип создан (202, id TestClip1)", state.contains("TestClip1") && clips.clipsCreated() == 1);
        check("getClipUrl: первый опрос — ещё не готов (null), второй — ссылка", mod.api.getClipUrl("TestClip1") == null
              && "https://clips.twitch.tv/TestClip1".equals(mod.api.getClipUrl("TestClip1")));
        helixPost("/__stream", "{\"live\": false}");
        check("getStream: не в эфире → OFFLINE", !mod.api.getStream("42").live());
        TwitchApi.Created offlineMarker = mod.api.createStreamMarker("42", "мимо");
        check("createStreamMarker офлайн → 404 с текстом ошибки", !offlineMarker.ok() && offlineMarker.status() == 404 && offlineMarker.error().contains("404"));
        TwitchApi.Created offlineClip = mod.api.createClip("42");
        check("createClip офлайн → 404", !offlineClip.ok() && offlineClip.status() == 404);
        helixPost("/__stream", "{\"live\": true}");

        System.out.println("== Этап 1: подключение, подписки (+чат), уведомления, дубликат, session_reconnect ==");
        client.connect();
        Thread.sleep(3000);
        check("isSubscribed после welcome(S1)", client.isSubscribed());
        check("subscriptionCount == 7 (6 базовых без raid + чат)", client.subscriptionCount() == 7);
        check("hasChat()", client.hasChat());
        check("statusText содержит чат", client.statusText().contains("чат: да"));
        Thread.sleep(3000);
        check("получен ровно 1 cheer (дубликат m1 отброшен)",
              mod.received.stream().filter(e -> e.type() == TwitchEvent.Type.CHEER).count() == 1);
        check("получено сообщение чата с бейджем модератора",
              mod.received.stream().anyMatch(e -> e.type() == TwitchEvent.Type.CHAT && e.badges().contains("moderator") && e.message().equals("!zombie please")));

        System.out.println("== Этап 2: после reconnect — событие на новом сокете, затем аварийный обрыв ==");
        Thread.sleep(4000);
        check("получен reward через соединение #2",
              mod.received.stream().anyMatch(e -> e.type() == TwitchEvent.Type.REWARD && "Зомби".equals(e.reward())));
        String subs = helix("/__subs");
        check("для сессии S2 подписки НЕ создавались (перенос)", count(subs, "\"S2\"") == 0);

        System.out.println("== Этап 3: автопереподключение после обрыва ==");
        Thread.sleep(5000);
        check("после обрыва снова isSubscribed", client.isSubscribed());
        subs = helix("/__subs");
        check("для сессии S3 подписки созданы заново (7 шт.)", count(subs, "\"S3\"") == 7);
        check("получен follow через соединение #3",
              mod.received.stream().anyMatch(e -> e.type() == TwitchEvent.Type.FOLLOW));

        System.out.println("== Этап 4: watchdog при молчании сервера (ждём ~60 c) ==");
        Thread.sleep(62000);
        subs = helix("/__subs");
        check("после тишины создано соединение #4 и подписки для S4", count(subs, "\"S4\"") == 7);
        check("isSubscribed после watchdog-переподключения", client.isSubscribed());
        check("получен raid через соединение #4",
              mod.received.stream().anyMatch(e -> e.type() == TwitchEvent.Type.RAID));
        check("statusText показывает переподключения", client.statusText().contains("переподключений"));

        System.out.println("== Этап 5: отключение ==");
        client.disconnect();
        Thread.sleep(1500);
        check("после disconnect не активно", !client.isActive() && !client.isSubscribed() && !client.hasChat());
        check("всего событий: 5 (cheer, chat, reward, follow, raid)", mod.received.size() == 5);
        check("ClipManager: фоновая проверка готовности нашла ссылку на клип", clips.lastClipUrl().equals("https://clips.twitch.tv/TestClip1"));

        System.out.println(failures == 0 ? "\nALL " + total + " EVENTSUB TESTS PASSED" : "\nFAILURES: " + failures + " of " + total);
        mod.scheduler.shutdownNow();
        mod.worker.shutdownNow();
        System.exit(failures == 0 ? 0 : 1);
    }
}
