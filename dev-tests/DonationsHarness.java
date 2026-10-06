import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.donations.DonatePayClient;
import dev.dedworkshop.twitchcraft.donations.DonationAlertsClient;
import dev.dedworkshop.twitchcraft.donations.DonationManager;
import dev.dedworkshop.twitchcraft.donations.DonationStore;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import dev.dedworkshop.twitchcraft.util.Chat;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Интеграционный тест DonationAlerts (OAuth + Centrifugo) и DonatePay (опрос) против mock_donations.py (без Minecraft).
 * Запуск: см. run_tests.sh — нужны системные свойства twitchcraft.daApiUrl/daOauthUrl/daWsUrl/donatePayUrl/donatePayMinIntervalMs.
 */
public class DonationsHarness {
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
        final ModConfig config = ModConfig.createDefault();
        final DonationStore store = new DonationStore();
        final List<TwitchEvent> received = Collections.synchronizedList(new ArrayList<>());
        DonationManager manager;

        TestMod() {
            config.donations.minAmount = 1;
            config.donations.donationAlertsClientId = "12345";
            config.donations.callbackPort = 8635;
            config.donations.donatePayPollSeconds = 2; // в тесте опрашиваем каждые 2 с (лимит мока — 0.9 с; свойство twitchcraft.donatePayMinIntervalMs=1000)
        }

        @Override public ScheduledExecutorService scheduler() { return scheduler; }
        @Override public ExecutorService worker() { return worker; }
        @Override public ModConfig config() { return config; }
        @Override public DonationStore donationStore() { return store; }
        @Override public DonationManager donations() { return manager; }
        @Override public boolean isModuleEnabled(Module module) { return config.isEnabled(module); }
        @Override public void onTwitchEvent(TwitchEvent event) {
            System.out.println("  >> EVENT " + event.sourceTitle() + " " + event.user() + " " + event.amount() + " " + event.currency() + " id=" + event.rewardId()
                    + (event.synthetic() ? " (synthetic)" : "") + " \"" + event.message() + "\"");
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

    /**
     * Начать вход с повтором: локальный callback-порт 8635 освобождается чуть позже,
     * чем заканчивается предыдущая попытка входа, и на медленном раннере первый
     * beginLogin() может вернуть null («Address already in use»).
     */
    static String beginLoginWithRetry(DonationAlertsClient da) throws Exception {
        for (int attempt = 0; attempt < 30; attempt++) {
            if (!da.isLoginInProgress()) {
                String url = da.beginLogin();
                if (url != null) {
                    return url;
                }
            }
            Thread.sleep(200);
        }
        return null;
    }

    static String queryParam(String url, String name) {
        if (url == null) {
            return null; // вход не начался (например, порт callback ещё занят) — не роняем тест NPE
        }
        String query = URI.create(url).getRawQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            String key = URLDecoder.decode(equals >= 0 ? pair.substring(0, equals) : pair, StandardCharsets.UTF_8);
            if (name.equals(key)) {
                return URLDecoder.decode(equals >= 0 ? pair.substring(equals + 1) : "", StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    static int stateInt(String url, String key) throws Exception {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\": (\\d+)").matcher(http(url));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    static int daState(String key) throws Exception {
        return stateInt("http://127.0.0.1:8082/__state", key);
    }

    static long count(TestMod mod, String id) {
        return mod.received.stream().filter(e -> e.rewardId().equals(id)).count();
    }

    static boolean waitFor(java.util.function.BooleanSupplier cond, long millis) throws InterruptedException {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            if (cond.getAsBoolean()) return true;
            Thread.sleep(50);
        }
        return cond.getAsBoolean();
    }

    public static void main(String[] args) throws Exception {
        TestMod mod = new TestMod();
        mod.manager = new DonationManager(mod);
        DonationAlertsClient da = mod.manager.donationAlerts();
        DonatePayClient dp = mod.manager.donatePay();

        System.out.println("== Этап 1: DonationAlerts — OAuth URL, проверка Client ID и локальный callback ==");
        check("not configured initially", !da.isConfigured() && da.statusText().contains("нет входа"));
        mod.config.donations.donationAlertsClientId = "not-a-client-id";
        check("non-numeric Client ID rejected before opening callback", da.beginLogin() == null && !da.isLoginInProgress());
        mod.config.donations.donationAlertsClientId = "12345";
        String loginUrl = beginLoginWithRetry(da);
        check("login url built", loginUrl != null && loginUrl.contains("client_id=12345") && loginUrl.contains("response_type=token")
                && loginUrl.contains("redirect_uri=http%3A%2F%2Flocalhost%3A8635%2Fda") && loginUrl.contains("oauth-donation-subscribe"));
        URI authUri = URI.create(loginUrl);
        check("authorize URL has one query delimiter and the expected endpoint", authUri.getPath().equals("/oauth/authorize")
                && authUri.getRawQuery() != null && loginUrl.indexOf('?') == loginUrl.lastIndexOf('?'));
        check("OAuth query contains exact app ID, callback, implicit grant and scopes (no secret)",
                "12345".equals(queryParam(loginUrl, "client_id"))
                        && "http://localhost:8635/da".equals(queryParam(loginUrl, "redirect_uri"))
                        && "token".equals(queryParam(loginUrl, "response_type"))
                        && DonationAlertsClient.SCOPES.equals(queryParam(loginUrl, "scope"))
                        && !authUri.getRawQuery().contains("client_secret"));
        String state = queryParam(loginUrl, "state");
        check("login in progress", da.isLoginInProgress() && da.statusText().contains("браузере"));
        String wrongErrorState = http("http://127.0.0.1:8635/da?error=invalid_client&state=wrong-state");
        check("OAuth error with wrong state rejected without ending login", wrongErrorState.startsWith("400") && da.isLoginInProgress());
        String authErrorPage = http("http://127.0.0.1:8635/da?error=invalid_client&error_description=Client+authentication+failed&state=" + state);
        check("invalid_client callback explains cause, notifies game and ends login", authErrorPage.startsWith("200")
                && authErrorPage.contains("invalid_client") && authErrorPage.contains("Client ID")
                && authErrorPage.contains("http://localhost:8635/da") && waitFor(() -> !da.isLoginInProgress(), 2000)
                && Chat.lastText().contains("invalid_client"));
        String retryAfterQueryError = beginLoginWithRetry(da);
        String retryState = queryParam(retryAfterQueryError, "state");
        check("login retry after query error starts with fresh state", retryAfterQueryError != null && da.isLoginInProgress()
                && retryState != null && !retryState.equals(state));
        loginUrl = retryAfterQueryError;
        state = retryState;
        String hashAuthError = http("http://127.0.0.1:8635/da/token?error=invalid_client&error_description=Client+authentication+failed&state=" + state);
        check("implicit-flow invalid_client also notifies game and ends login", hashAuthError.startsWith("400")
                && hashAuthError.contains("invalid_client") && hashAuthError.contains("Client ID")
                && hashAuthError.contains("http://localhost:8635/da") && waitFor(() -> !da.isLoginInProgress(), 2000)
                && Chat.lastText().contains("invalid_client"));
        String retryAfterHashError = beginLoginWithRetry(da);
        check("login retry after fragment error starts", retryAfterHashError != null && da.isLoginInProgress());
        loginUrl = retryAfterHashError;
        String landing = http("http://127.0.0.1:8635/da");
        check("landing page served", landing.startsWith("200") && landing.contains("location.hash"));
        java.util.regex.Matcher sm = java.util.regex.Pattern.compile("[&?]state=([A-Za-z0-9_-]+)").matcher(loginUrl);
        check("login url contains random state", sm.find() && sm.group(1).length() >= 20);
        state = sm.group(1);
        String crossSite = http("http://127.0.0.1:8635/da/token?access_token=evil-token&state=" + state, "Sec-Fetch-Site", "cross-site");
        check("cross-site token request rejected (403)", crossSite.startsWith("403") && !mod.store.hasDonationAlerts());
        String wrongState = http("http://127.0.0.1:8635/da/token?access_token=evil-token&state=wrong-state");
        check("wrong state rejected (400)", wrongState.startsWith("400") && da.isLoginInProgress() && !mod.store.hasDonationAlerts());
        String tokenResp = http("http://127.0.0.1:8635/da/token?access_token=da-token&token_type=bearer&expires_in=631151999&state=" + state,
                "Sec-Fetch-Site", "same-origin");
        check("token accepted by callback", tokenResp.startsWith("200") && tokenResp.contains("Готово"));
        check("profile fetched, store filled", waitFor(() -> mod.store.hasDonationAlerts() && "Streamer".equals(mod.store.daUserName) && "42".equals(mod.store.daUserId), 5000));
        check("callback server closed after login", waitFor(() -> !da.isLoginInProgress(), 2000));

        System.out.println("== Этап 2: DonationAlerts — Centrifugo: connect, subscribe, донаты, дедуп, батч ==");
        check("auto-connected after login", waitFor(da::isActive, 8000));
        check("2 donations arrived (dup skipped)", waitFor(() -> mod.received.size() >= 2, 5000));
        TwitchEvent first = mod.received.get(0);
        TwitchEvent second = mod.received.get(1);
        check("first donation 500 RUB id 77", first.type() == TwitchEvent.Type.DONATION && first.amount() == 500 && first.currency().equals("RUB")
                && first.rewardId().equals("77") && first.user().equals("Donor") && first.source().equals(TwitchEvent.SOURCE_DONATION_ALERTS) && !first.synthetic());
        check("batched donation converted to RUB (950)", second.rewardId().equals("78") && second.amount() == 950 && second.currency().equals("RUB"));
        Thread.sleep(300);
        check("duplicate id 77 not delivered", mod.received.stream().filter(e -> e.rewardId().equals("77")).count() == 1);

        System.out.println("== Этап 3: DonationAlerts — переподключение после закрытия сервером ==");
        check("reconnected and donation 79 arrived", waitFor(() -> mod.received.stream().anyMatch(e -> e.rewardId().equals("79")), 15000));
        check("active again, 2 ws connections, oauth re-fetched", da.isActive() && daState("ws_connections") == 2 && daState("subscribe_calls") >= 2);
        Thread.sleep(600);
        check("donation below minAmount (0.5) skipped", mod.received.stream().noneMatch(e -> e.rewardId().equals("80")) && da.donationsReceived() == 4);
        check("status text: connected with name", da.statusText().contains("подключено") && da.statusText().contains("Streamer"));
        check("quiet reconnect: no second 'подключено' in chat", !Chat.lastText().contains("восстановлено"));
        check("catch-up requested after reconnect, old history (id 10) not replayed", waitFor(() -> {
            try { return daState("donations_calls") == 1; } catch (Exception e) { return false; }
        }, 5000) && count(mod, "10") == 0);

        System.out.println("== Этап 3б: Centrifugo v2 — продление токенов (refresh / sub_refresh) без переподключения ==");
        check("refresh + sub_refresh sent before ttl", waitFor(() -> {
            try { return daState("refresh_calls") >= 2 && daState("sub_refresh_calls") >= 2; } catch (Exception e) { return false; }
        }, 8000));
        check("refresh uses a fresh socket token from /user/oauth", daState("oauth_calls") >= 4);
        check("still the same ws connection, still active", daState("ws_connections") == 2 && da.isActive());

        System.out.println("== Этап 3в: push unsub от сервера -> переподписка на том же соединении ==");
        http("http://127.0.0.1:8082/__unsub");
        check("resubscribed and donation 81 arrived", waitFor(() -> count(mod, "81") == 1, 8000));
        check("no reconnect needed for unsub", daState("ws_connections") == 2 && daState("resubscribes") >= 1 && da.isActive());
        check("catch-up after resubscribe too (nothing new)", waitFor(() -> {
            try { return daState("donations_calls") == 2; } catch (Exception e) { return false; }
        }, 5000) && count(mod, "10") == 0);

        System.out.println("== Этап 3г: обрыв соединения -> переподключение + догонка пропущенного доната через REST ==");
        http("http://127.0.0.1:8082/__close");
        check("missed donation 83 caught up after reconnect", waitFor(() -> count(mod, "83") == 1, 15000));
        check("3rd ws connection, REST list requested once more", daState("ws_connections") == 3 && daState("donations_calls") == 3 && da.isActive());
        Thread.sleep(300);
        check("old history (id 10) and already seen (81) not replayed", count(mod, "10") == 0 && count(mod, "81") == 1 && count(mod, "83") == 1);
        TwitchEvent missed = mod.received.stream().filter(e -> e.rewardId().equals("83")).findFirst().orElseThrow();
        check("caught-up donation is a real DA event (300 RUB, Missed)", missed.amount() == 300 && missed.user().equals("Missed") && !missed.synthetic()
                && missed.source().equals(TwitchEvent.SOURCE_DONATION_ALERTS));

        System.out.println("== Этап 4: DonationAlerts — отзыв токена -> статус ошибки, без бесконечных попыток ==");
        http("http://127.0.0.1:8082/__reject");
        da.disconnect();
        da.connect(false);
        check("token rejected detected", waitFor(() -> da.statusText().contains("недействителен"), 5000));
        check("not active after rejection", !da.isActive() && Chat.lastText().contains("DonationAlerts") && Chat.lastText().contains("401"));
        int oauthCalls = Integer.parseInt(http("http://127.0.0.1:8082/__state").replaceAll("(?s).*\"oauth_calls\": (\\d+).*", "$1"));
        Thread.sleep(2500);
        int oauthCallsLater = Integer.parseInt(http("http://127.0.0.1:8082/__state").replaceAll("(?s).*\"oauth_calls\": (\\d+).*", "$1"));
        check("no retry storm after 401", oauthCallsLater == oauthCalls);
        da.logout();
        check("logout clears store", !mod.store.hasDonationAlerts() && !da.isConfigured());

        System.out.println("== Этап 5: DonatePay — ключ, посев курсора, опрос, ожидание платежа, тестовый донат ==");
        mod.received.clear();
        dp.setKey("wrong");
        check("wrong key rejected", waitFor(() -> Chat.lastText().contains("не принял ключ") && Chat.lastText().contains("Wrong access token"), 5000));
        check("wrong key not stored", !mod.store.hasDonatePay());
        Thread.sleep(1100);
        dp.setKey("dp-key");
        check("key accepted, account name stored", waitFor(() -> mod.store.hasDonatePay() && "DpStreamer".equals(mod.store.dpUserName) && "777".equals(mod.store.dpUserId), 5000));
        check("connected after seeding cursor (no old donations replayed)", waitFor(dp::isActive, 8000) && mod.store.dpCursor == 100 && mod.received.isEmpty());
        check("poll 2: pending-at-start 100 paid + Alice 150 delivered, Bob pending", waitFor(() -> mod.received.size() == 2, 30000)
                && mod.received.get(0).user().equals("Late") && mod.received.get(0).amount() == 70
                && mod.received.get(1).amount() == 150 && mod.received.get(1).user().equals("Alice")
                && mod.received.get(1).source().equals(TwitchEvent.SOURCE_DONATE_PAY) && !mod.received.get(1).synthetic());
        check("cursor after poll 2 = 101 (pending 102 not passed)", mod.store.dpCursor == 101 && !mod.store.dpWasSeen(102));
        check("poll 3: Bob paid + test donation; cancel skipped", waitFor(() -> mod.received.size() == 4, 30000)
                && mod.received.get(2).user().equals("Bob") && mod.received.get(2).amount() == 99 && !mod.received.get(2).synthetic()
                && mod.received.get(3).user().equals("Tester") && mod.received.get(3).amount() == 5 && mod.received.get(3).synthetic());
        check("cursor after poll 3 = 104, cancel marked seen", mod.store.dpCursor == 104 && mod.store.dpWasSeen(104) && mod.store.dpWasSeen(102));
        check("status text ok", dp.statusText().contains("подключено") && dp.statusText().contains("DpStreamer") && dp.donationsReceived() == 4);
        dp.disconnect();
        check("disconnected", !dp.isActive() && dp.statusText().contains("отключено"));

        System.out.println("== Этап 6: DonationManager ==");
        mod.config.setEnabled(Module.DONATE_PAY, false);
        mod.manager.autoConnect();
        Thread.sleep(300);
        check("autoConnect skips disabled module", !dp.isActive());
        mod.config.setEnabled(Module.DONATE_PAY, true);
        mod.manager.onModuleChanged(Module.DONATE_PAY, true);
        check("module enabled -> connects", waitFor(dp::isActive, 8000));
        mod.manager.onModuleChanged(Module.DONATE_PAY, false);
        check("module disabled -> disconnects", !dp.isActive());
        check("overlay mark + status lines", mod.manager.overlayMark().contains("DP") && mod.manager.statusLines().size() == 2
                && mod.manager.statusLines().get(1).contains("DonatePay"));
        mod.received.clear();
        mod.manager.test("test", 250, "привет");
        check("test donation synthetic 250", mod.received.size() == 1 && mod.received.get(0).synthetic() && mod.received.get(0).amount() == 250
                && mod.received.get(0).source().equals("test") && Module.forEvent(mod.received.get(0)) == null);
        mod.manager.shutdown();

        System.out.println(failures == 0 ? "\nALL " + total + " HARNESS CHECKS PASSED" : "\nFAILURES: " + failures + " of " + total);
        System.exit(failures == 0 ? 0 : 1);
    }
}
