import com.google.gson.JsonParser;
import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import dev.dedworkshop.twitchcraft.util.Chat;
import dev.dedworkshop.twitchcraft.vk.VkEvents;
import dev.dedworkshop.twitchcraft.vk.VkLive;
import dev.dedworkshop.twitchcraft.vk.VkStore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Интеграционный тест VK Video Live (OAuth code + DevAPI + Centrifugo v2) против mock_vk.py (без Minecraft).
 * Запуск: см. run_tests.sh — нужны системные свойства twitchcraft.vkApiUrl/vkAuthUrl/vkTokenUrl/vkRevokeUrl/vkWsUrl.
 */
public class VkHarness {
    static int failures = 0;
    static int total = 0;
    static final String MOCK = "http://127.0.0.1:8085";

    static void check(String name, boolean cond) {
        total++;
        System.out.println((cond ? "  OK   " : "  FAIL ") + name);
        if (!cond) failures++;
    }

    static class TestMod extends TwitchCraftClient {
        final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        final ExecutorService worker = Executors.newFixedThreadPool(2);
        final ModConfig config = ModConfig.createDefault();
        final VkStore store = new VkStore();
        final List<TwitchEvent> received = Collections.synchronizedList(new ArrayList<>());
        VkLive vk;

        TestMod() {
            config.vk.clientId = "vkapp";
            config.vk.callbackPort = 8638;
            config.vk.channelUrl = "https://live.vkvideo.ru/dedworkshop";
            store.clientSecret = "vksecret";
        }

        @Override public ScheduledExecutorService scheduler() { return scheduler; }
        @Override public ExecutorService worker() { return worker; }
        @Override public ModConfig config() { return config; }
        @Override public VkStore vkStore() { return store; }
        @Override public VkLive vk() { return vk; }
        @Override public boolean isModuleEnabled(Module module) { return config.isEnabled(module); }
        @Override public void onTwitchEvent(TwitchEvent event) {
            System.out.println("  >> EVENT " + event.platformTitle() + " " + event.type() + " " + event.user() + " " + event.amount()
                    + " reward=" + event.reward() + " demand=" + event.redemptionId() + (event.synthetic() ? " (synthetic)" : "") + " \"" + event.message() + "\"");
            received.add(event);
        }
    }

    static String http(String url, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        HttpResponse<String> r = HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode() + " " + r.body();
    }

    static String state() throws Exception {
        return http(MOCK + "/__state").substring(4);
    }

    static int stateInt(String key) throws Exception {
        Matcher m = Pattern.compile("\"" + key + "\": (\\d+)").matcher(state());
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    static boolean waitFor(java.util.function.BooleanSupplier cond, long millis) throws InterruptedException {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            if (cond.getAsBoolean()) return true;
            Thread.sleep(100);
        }
        return cond.getAsBoolean();
    }

    interface Probe { boolean test() throws Exception; }

    static boolean waitForX(Probe cond, long millis) throws Exception {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            if (cond.test()) return true;
            Thread.sleep(150);
        }
        return cond.test();
    }

    static List<TwitchEvent> ofType(List<TwitchEvent> events, TwitchEvent.Type type) {
        List<TwitchEvent> out = new ArrayList<>();
        synchronized (events) {
            for (TwitchEvent e : events) if (e.type() == type) out.add(e);
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        TestMod mod = new TestMod();
        mod.vk = new VkLive(mod);
        VkLive vk = mod.vk;

        System.out.println("== Этап 1: вход (OAuth authorization code через локальный callback) ==");
        check("configured, not logged in", vk.isConfigured() && !vk.isLoggedIn() && vk.statusText().contains("вход не выполнен") && vk.overlayMark().isEmpty());
        check("redirect uri", vk.redirectUri().equals("http://localhost:8638/vk"));
        String loginUrl = vk.beginLogin();
        check("login url built", loginUrl != null && loginUrl.startsWith("http://127.0.0.1:8085/app/oauth2/authorize?client_id=vkapp")
                && loginUrl.contains("redirect_uri=http%3A%2F%2Flocalhost%3A8638%2Fvk") && loginUrl.contains("response_type=code")
                && loginUrl.contains("scope=chat%3Amessage%3Asend%2Cchannel%3Apoints%2Cchannel%3Apoints%3Arewards%2Cchannel%3Apoints%3Arewards%3Ademands")
                && loginUrl.contains("&state="));
        check("login in progress", vk.isLoginInProgress() && vk.statusText().contains("браузере"));
        String st = loginUrl.substring(loginUrl.indexOf("state=") + 6);
        String badState = http("http://127.0.0.1:8638/vk?code=good-code&state=nope");
        check("callback: wrong state -> 400, still waiting", badState.startsWith("400") && vk.isLoginInProgress() && !vk.isLoggedIn());
        String denied = http("http://127.0.0.1:8638/vk?error=access_denied&error_description=user+cancelled");
        check("callback: error page 200, still waiting", denied.startsWith("200") && vk.isLoginInProgress());
        String ok = http("http://127.0.0.1:8638/vk?code=good-code&state=" + st, "Sec-Fetch-Site", "cross-site", "Sec-Fetch-Mode", "navigate");
        check("callback: code accepted (cross-site navigation is fine for ?code=)", ok.startsWith("200") && ok.contains("Готово"));
        check("tokens exchanged (Basic auth, code, redirect_uri), profile loaded",
                waitFor(() -> vk.isLoggedIn() && !mod.store.userNick.isEmpty(), 5000)
                && mod.store.accessToken.equals("vk-access-1") && mod.store.refreshToken.equals("vk-refresh-1")
                && mod.store.userNick.equals("DedWorkshop") && mod.store.ownChannelUrl.equals("dedworkshop") && mod.store.userId.equals("1")
                && mod.store.hasScope("chat:message:send") && mod.store.hasScope("channel:points:rewards:demands") && !vk.isLoginInProgress());
        String tokenCalls = state();
        check("token request form: grant_type=authorization_code, code, redirect_uri",
                tokenCalls.contains("\"grant_type\": \"authorization_code\"") && tokenCalls.contains("\"code\": \"good-code\"")
                && tokenCalls.contains("\"redirect_uri\": \"http://localhost:8638/vk\""));

        System.out.println("== Этап 2: подключение к каналу и WebSocket ==");
        check("connected: channel resolved, chat + private channels subscribed",
                waitFor(() -> vk.isActive() && vk.subscribedChannels().size() >= 5, 8000)
                && vk.channelSlug().equals("dedworkshop") && vk.statusText().contains("подключено") && vk.overlayMark().equals("VK●"));
        String s2 = state();
        check("subscription tokens requested only once for all channels; private channels got tokens",
                s2.contains("\"sub_token_calls\": [[") && s2.contains("\"private-channel-info:777\": 1") && s2.contains("\"channel-chat:777\": 1")
                && s2.contains("\"channel-points:777\": 1") && s2.contains("\"private-channel-points:777\": 1"));
        check("stream id from /channel, online", vk.streamId().equals("stream-1") || vk.streamId().equals("stream-2"));

        System.out.println("== Этап 3: события ==");
        boolean got4 = waitFor(() -> mod.received.size() >= 4, 8000);
        Thread.sleep(600); // дубли должны были прийти и быть отброшены
        List<TwitchEvent> chats = ofType(mod.received, TwitchEvent.Type.CHAT);
        List<TwitchEvent> rewards = ofType(mod.received, TwitchEvent.Type.REWARD);
        List<TwitchEvent> follows = ofType(mod.received, TwitchEvent.Type.FOLLOW);
        check("exactly 4 events: 1 chat, 2 rewards (501 pending, 503 approved), 1 follow (dups dropped)",
                got4 && mod.received.size() == 4 && chats.size() == 1 && rewards.size() == 2 && follows.size() == 1);
        TwitchEvent chat = chats.isEmpty() ? null : chats.get(0);
        check("chat: Вася «привет всем :pepeLaugh:», platform vk, not synthetic", chat != null && chat.user().equals("Вася") && chat.userLogin().equals("vasya")
                && chat.message().contains("привет всем") && chat.message().contains(":pepeLaugh:") && chat.isVk() && !chat.synthetic());
        TwitchEvent d501 = rewards.stream().filter(e -> e.redemptionId().equals("501")).findFirst().orElse(null);
        TwitchEvent d503 = rewards.stream().filter(e -> e.redemptionId().equals("503")).findFirst().orElse(null);
        check("demand 501: Пакость 250 by Вася with text, reward id", d501 != null && d501.reward().equals("Пакость") && d501.amount() == 250
                && d501.user().equals("Вася") && d501.message().equals("сделай страшно") && d501.rewardId().equals("rw-bad") && d501.actionKey().equals("reward:пакость"));
        check("demand 503 (auto-approved): Подарок by Петя", d503 != null && d503.reward().equals("Подарок") && d503.user().equals("Петя"));
        check("follow: Новичок", follows.get(0).user().equals("Новичок") && follows.get(0).userLogin().equals("newbie") && follows.get(0).isVk());
        check("stream status: offline then stream_start -> online, stream-2", waitFor(() -> "stream-2".equals(vk.streamId()), 2000) && Boolean.TRUE.equals(vk.online()));
        check("server ping answered with {}", waitForX(() -> stateInt("pings_answered") >= 1, 3000));
        check("unsubscribe push -> private-channel-info re-subscribed with a fresh token",
                waitForX(() -> state().contains("\"private-channel-info:777\": 2"), 5000) && stateInt("sub_token_calls") != 0);
        check("events counters", vk.eventsReceived() >= 9 && vk.chatReceived() == 1);

        System.out.println("== Этап 4: подтверждение/отклонение запросов наград ==");
        vk.acceptDemand(d501);
        vk.acceptDemand(d503); // уже approved на стороне VK — запроса быть не должно
        vk.acceptDemand(VkLive.testEvent(TwitchEvent.Type.REWARD, "T", 1, "", "Пакость")); // synthetic — игнор
        TwitchEvent d502 = VkEvents.parse(JsonParser.parseString(
                "{\"type\":\"cp_reward_demand\",\"data\":{\"demandId\":502,\"status\":\"pending\",\"reward\":{\"id\":\"rw-bad\",\"name\":\"Пакость\",\"price\":250},"
                        + "\"user\":{\"id\":57,\"nick\":\"kolya\"},\"activationMessage\":[]}}").getAsJsonObject()).event();
        vk.rejectDemand(d502, "в тесте нельзя");
        check("accept 501 sent, 503 skipped (approved), synthetic skipped; reject 502 sent",
                waitForX(() -> state().contains("\"accepted\": [501]") && state().contains("\"rejected\": [502]"), 4000));
        mod.config.vk.manageDemands = false;
        vk.rejectDemand(d501, "выключено");
        Thread.sleep(500);
        check("manageDemands=false -> no API calls", state().contains("\"rejected\": [502]") && !state().contains("\"rejected\": [502, 501]"));
        mod.config.vk.manageDemands = true;

        System.out.println("== Этап 5: отправка в чат, продление токенов ==");
        vk.send("Привет из игры!", true);
        check("send: first attempt send_too_fast -> retried after 3 s, delivered with stream_id",
                waitForX(() -> state().contains("\"text\": \"Привет из игры!\""), 7000) && stateInt("send_attempts") == 2
                && state().contains("\"stream_id\": \"stream-2\"") && state().contains("\"channel_url\": \"dedworkshop\"") && vk.wasSentByUs("Привет из игры!"));
        mod.config.vk.replies = false;
        vk.reply("не должно уйти");
        mod.config.vk.replies = true;
        mod.config.setEnabled(Module.CHAT_REPLIES, false);
        vk.reply("и это тоже");
        mod.config.setEnabled(Module.CHAT_REPLIES, true);
        Thread.sleep(400);
        check("reply() respects vk.replies and the CHAT_REPLIES module", stateInt("send_attempts") == 2);
        check("websocket token refreshed (ttl=3 -> refresh with new /websocket/token)",
                waitForX(() -> stateInt("ws_refresh_calls") >= 1, 6000) && stateInt("ws_token_calls") >= 2 && vk.isActive());
        http(MOCK + "/__expire");
        vk.send("после 401", true);
        check("API 401 -> refresh_token grant -> retry succeeded with the new access token",
                waitForX(() -> state().contains("\"text\": \"после 401\""), 6000) && stateInt("refresh_calls") == 1
                && mod.store.accessToken.equals("vk-access-2") && mod.store.refreshToken.equals("vk-refresh-2")
                && state().contains("\"token\": \"vk-access-2\""));

        System.out.println("== Этап 6: обрыв соединения, переподключение и догонка запросов наград ==");
        int before = mod.received.size();
        http(MOCK + "/__close");
        check("reconnected (2nd websocket connection), subscriptions restored",
                waitForX(() -> stateInt("ws_connections") == 2 && vk.isActive() && vk.subscribedChannels().size() >= 5, 15000));
        check("catch-up: only demand 504 delivered (501 seen, 400 too old, 505 approved), title from rewards list",
                waitFor(() -> mod.received.size() == before + 1, 6000) && mod.received.get(mod.received.size() - 1).redemptionId().equals("504")
                && mod.received.get(mod.received.size() - 1).reward().equals("Подарок") && mod.received.get(mod.received.size() - 1).user().equals("late")
                && mod.received.get(mod.received.size() - 1).message().equals("догони") && mod.received.get(mod.received.size() - 1).amount() == 250);
        Thread.sleep(500);
        check("no duplicates after catch-up", mod.received.size() == before + 1 && stateInt("demands_calls") >= 2);

        System.out.println("== Этап 7: создание наград на VK ==");
        vk.syncRewards();
        check("sync: «Подарок» exists -> only «Пакость» created with price 250, description, is_message_required=false",
                waitForX(() -> state().contains("\"name\": \"Пакость\""), 5000) && stateInt("manage_info_calls") == 1
                && state().contains("\"price\": 250") && state().contains("\"description\": \"Случайная пакость") && state().contains("\"is_message_required\": false")
                && !state().replace("\"name\": \"Подарок\", \"price\": 250, \"description\": \"\"", "").contains("\"created_rewards\": [{\"name\": \"Подарок\""));
        check("chat reports the created reward", waitFor(() -> Chat.lastText().contains("Пакость"), 3000));

        System.out.println("== Этап 8: смена канала, выход ==");
        mod.config.vk.channelUrl = "https://live.vkvideo.ru/someone_else";
        vk.syncWithConfig();
        check("channel changed -> reconnect attempt -> 404 -> stopped with an error, not active",
                waitFor(() -> !vk.isActive() && Chat.lastText().contains("не найден"), 8000) && vk.statusText().contains("отключено"));
        mod.config.vk.channelUrl = "dedworkshop";
        vk.connect(true);
        check("connect again works", waitFor(() -> vk.isActive(), 8000) && stateInt("ws_connections") == 3);
        vk.logout();
        check("logout: token revoked, tokens cleared, disconnected, secret kept",
                waitForX(() -> stateInt("revoke_calls") == 1, 3000) && !vk.isLoggedIn() && !vk.isActive() && mod.store.hasSecret()
                && vk.statusText().contains("вход не выполнен") && vk.overlayMark().isEmpty());
        vk.connect(true);
        check("connect without tokens -> warning, nothing happens", !vk.isActive() && Chat.lastText().contains("войди"));

        vk.shutdown();
        mod.scheduler.shutdownNow();
        mod.worker.shutdownNow();
        System.out.println(failures == 0 ? "\nALL " + total + " VK TESTS PASSED" : "\nFAILURES: " + failures + " of " + total);
        System.exit(failures == 0 ? 0 : 1);
    }
}
