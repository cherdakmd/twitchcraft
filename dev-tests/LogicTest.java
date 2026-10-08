import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dedworkshop.twitchcraft.action.ActionRunner;
import dev.dedworkshop.twitchcraft.action.Cooldowns;
import dev.dedworkshop.twitchcraft.action.Placeholders;
import dev.dedworkshop.twitchcraft.action.SessionStats;
import dev.dedworkshop.twitchcraft.action.FundraiserTracker;
import dev.dedworkshop.twitchcraft.action.GoalTracker;
import dev.dedworkshop.twitchcraft.config.DonationPresets;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.donations.DonatePayClient;
import dev.dedworkshop.twitchcraft.donations.DonationParser;
import dev.dedworkshop.twitchcraft.donations.DonationStore;
import dev.dedworkshop.twitchcraft.donations.LocalCallbackServer;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.twitch.ChatSender;
import dev.dedworkshop.twitchcraft.twitch.EventSubClient;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import dev.dedworkshop.twitchcraft.ui.TwitchChatRenderer;
import dev.dedworkshop.twitchcraft.ui.ChatSignLayout;
import dev.dedworkshop.twitchcraft.ui.WorldProjection;
import dev.dedworkshop.twitchcraft.vk.VkEvents;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/** Логические тесты без запуска Minecraft. */
public class LogicTest {
    static int failures = 0;
    static int total = 0;
    static String normalisedPool(String pool) {
        ModConfig c = ModConfig.createDefault();
        ModConfig.Action a = new ModConfig.Action("m", "", "");
        a.pool = pool;
        c.rewards.put("tmp", a);
        c.normalize();
        return c.rewards.get("tmp").pool;
    }

    static void check(String name, boolean cond) {
        total++;
        System.out.println((cond ? "  OK   " : "  FAIL ") + name);
        if (!cond) failures++;
    }
    static JsonObject j(String s) { return JsonParser.parseString(s).getAsJsonObject(); }
    static TwitchEvent ev(TwitchEvent.Type type, int amount, String reward) {
        return TwitchEvent.simple(type, "X", "x", amount, "", reward, "1");
    }

    public static void main(String[] args) {
        System.out.println("== TwitchEvent.fromEventSub ==");
        TwitchEvent follow = TwitchEvent.fromEventSub("channel.follow", j("""
            {"user_id":"1","user_login":"cool_user","user_name":"Cool_User","broadcaster_user_id":"2","followed_at":"2026-10-05T10:00:00Z"}"""));
        check("follow parsed + userId", follow != null && follow.type() == TwitchEvent.Type.FOLLOW && follow.user().equals("Cool_User") && follow.userId().equals("1"));

        TwitchEvent sub = TwitchEvent.fromEventSub("channel.subscribe", j("""
            {"user_name":"Subber","user_login":"subber","tier":"2000","is_gift":false}"""));
        check("subscribe tier 2", sub != null && sub.tier().equals("2") && sub.type() == TwitchEvent.Type.SUBSCRIBE);

        TwitchEvent giftedSub = TwitchEvent.fromEventSub("channel.subscribe", j("""
            {"user_name":"Recipient","user_login":"recipient","tier":"1000","is_gift":true}"""));
        check("gifted subscribe skipped", giftedSub == null);

        TwitchEvent resub = TwitchEvent.fromEventSub("channel.subscription.message", j("""
            {"user_name":"Loyal","user_login":"loyal","tier":"1000","message":{"text":"12 months pog","emotes":[]},"cumulative_months":12,"streak_months":5,"duration_months":1}"""));
        check("resub months=12, msg", resub != null && resub.amount() == 12 && resub.message().equals("12 months pog"));

        TwitchEvent gift = TwitchEvent.fromEventSub("channel.subscription.gift", j("""
            {"user_name":null,"user_login":null,"total":5,"tier":"1000","is_anonymous":true,"cumulative_total":null}"""));
        check("anon gift total=5", gift != null && gift.user().equals("Аноним") && gift.amount() == 5 && gift.isAnonymous());

        TwitchEvent cheer = TwitchEvent.fromEventSub("channel.cheer", j("""
            {"is_anonymous":false,"user_name":"Rich","user_login":"rich","message":"Cheer1000 take it","bits":1000}"""));
        check("cheer 1000", cheer != null && cheer.amount() == 1000 && cheer.user().equals("Rich"));

        TwitchEvent raid = TwitchEvent.fromEventSub("channel.raid", j("""
            {"from_broadcaster_user_name":"BigStreamer","from_broadcaster_user_login":"bigstreamer","to_broadcaster_user_id":"2","viewers":321}"""));
        check("raid 321 viewers", raid != null && raid.amount() == 321 && raid.user().equals("BigStreamer"));

        TwitchEvent reward = TwitchEvent.fromEventSub("channel.channel_points_custom_reward_redemption.add", j("""
            {"id":"red-1","user_name":"Viewer","user_login":"viewer","user_input":"hi \\"there\\"\\nnewline §c","status":"unfulfilled",
             "reward":{"id":"abc","title":"Зомби","cost":500,"prompt":""}}"""));
        check("reward title/cost/input/ids", reward != null && reward.reward().equals("Зомби") && reward.amount() == 500
                && reward.rewardId().equals("abc") && reward.redemptionId().equals("red-1") && !reward.message().contains("§"));

        TwitchEvent chat = TwitchEvent.fromEventSub("channel.chat.message", j("""
            {"broadcaster_user_id":"2","chatter_user_id":"7","chatter_user_login":"modguy","chatter_user_name":"ModGuy",
             "message_id":"m1","message":{"text":"!zombie now please","fragments":[]},"message_type":"text",
             "badges":[{"set_id":"moderator","id":"1","info":""},{"set_id":"subscriber","id":"12","info":"13"}],
             "color":"#1E90FF","cheer":null,"reply":null,"channel_points_custom_reward_id":null}"""));
        check("chat parsed: badges, color, permission", chat != null && chat.type() == TwitchEvent.Type.CHAT
                && chat.badges().contains("moderator") && chat.color().equals("#1E90FF")
                && chat.permission() == TwitchEvent.Permission.MODERATOR && chat.message().equals("!zombie now please"));
        check("unknown type -> null", TwitchEvent.fromEventSub("channel.ban", j("{}")) == null);
        TwitchEvent cmd = chat.asCommand("zombie", "now please");
        check("asCommand keeps user, sets args", cmd.type() == TwitchEvent.Type.CHAT_COMMAND && cmd.command().equals("zombie")
                && cmd.message().equals("now please") && cmd.user().equals("ModGuy") && cmd.actionKey().equals("chat:zombie"));
        check("test events are synthetic", TwitchEvent.test(TwitchEvent.Type.FOLLOW, "T", 0, "", "", "").synthetic()
                && !follow.synthetic() && follow.asSynthetic().synthetic());

        System.out.println("== ModConfig.findAction (defaults) ==");
        ModConfig cfg = ModConfig.createDefault();
        {
            // с 1.6.0 наград по умолчанию две («Пакость»/«Подарок»); для старых проверок добавляем примеры из прежних версий
            java.util.LinkedHashMap<String, ModConfig.Action> withLegacy = new java.util.LinkedHashMap<>();
            withLegacy.put("Зомби", new ModConfig.Action("&c{user} &7натравил на тебя зомби!", "&cЗОМБИ!", "&fот {user}", "summon minecraft:zombie ~2 ~ ~2"));
            withLegacy.put("Лотерея", new ModConfig.Action("&6{user} &7испытал удачу...", "", "", "give @s minecraft:diamond 3").with(a -> a.chance = 10));
            withLegacy.putAll(cfg.rewards);
            cfg.rewards = withLegacy;
        }
        check("follow action", cfg.findAction(follow).action() == cfg.follow && cfg.findAction(follow).key().equals("follow"));
        check("sub action", cfg.findAction(sub).action() == cfg.subscribe);
        check("resub 12 -> tier 12", cfg.findAction(resub).action() == cfg.resubTiers.get("12") && cfg.findAction(resub).key().equals("resub:12"));
        check("resub 3 -> base", cfg.findAction(ev(TwitchEvent.Type.RESUB, 3, "")).action() == cfg.resub);
        check("gift 5 -> base, gift 10 -> tier", cfg.findAction(gift).action() == cfg.giftSub
                && cfg.findAction(ev(TwitchEvent.Type.GIFT_SUB, 10, "")).action() == cfg.giftSubTiers.get("10"));
        check("raid 321 -> tier 50", cfg.findAction(raid).action() == cfg.raidTiers.get("50"));
        check("cheer 1000 -> threshold 1000", cfg.findAction(cheer).action() == cfg.cheer.get("1000"));
        check("cheer 50 -> threshold 1", cfg.findAction(ev(TwitchEvent.Type.CHEER, 50, "")).action() == cfg.cheer.get("1"));
        check("cheer 999 -> threshold 100", cfg.findAction(ev(TwitchEvent.Type.CHEER, 999, "")).action() == cfg.cheer.get("100"));
        check("reward exact", cfg.findAction(reward).action() == cfg.rewards.get("Зомби"));
        check("reward case-insensitive + trim", cfg.findAction(ev(TwitchEvent.Type.REWARD, 1, "  зомби ")).action() == cfg.rewards.get("Зомби"));
        check("reward fallback *", cfg.findAction(ev(TwitchEvent.Type.REWARD, 1, "Что-то новое")).action() == cfg.rewards.get("*"));
        check("plain chat -> null", cfg.findAction(chat) == null);
        check("chat command by name", cfg.findAction(cmd).action() == cfg.chatCommands.get("zombie"));
        check("chat command by alias", cfg.findChatCommand("зомби").action() == cfg.chatCommands.get("zombie"));
        check("chat command unknown -> null", cfg.findChatCommand("nope") == null);
        check("chat command permission", cfg.chatCommands.get("heal").permissionLevel() == TwitchEvent.Permission.SUBSCRIBER);
        ModConfig empty = new ModConfig();
        check("empty config -> null", empty.findAction(follow) == null && empty.findAction(reward) == null);
        cfg.rewards.get("Зомби").enabled = false;
        check("disabled action still resolved (processor decides)", cfg.findAction(reward) != null && !cfg.findAction(reward).action().enabled);
        cfg.rewards.get("Зомби").enabled = true;

        System.out.println("== Safety: blocked commands / ignored users ==");
        check("blocked: stop, /op, minecraft:ban", cfg.isCommandBlocked("stop") && cfg.isCommandBlocked("/op Steve") && cfg.isCommandBlocked("minecraft:ban x"));
        check("not blocked: summon, stopsound", !cfg.isCommandBlocked("summon zombie") && !cfg.isCommandBlocked("stopsound @s"));
        check("ignored users", cfg.isIgnoredUser("Nightbot") && !cfg.isIgnoredUser("dedworkshop"));
        check("needsChat default", cfg.needsChat());

        System.out.println("== Placeholders ==");
        SessionStats stats = new SessionStats();
        stats.record(cheer, "ok");
        stats.record(follow, "ok");
        stats.record(chat, "chat");
        Map<String, String> vars = Placeholders.of(reward, "Steve", stats);
        check("safe(): quotes/newline/backslash removed", !vars.get("message").contains("\"") && !vars.get("message").contains("\n"));
        String out = Placeholders.apply("tellraw @s \"{user}: {message} ({reward}, {amount}) for {player}\"", vars);
        check("apply()", out.equals("tellraw @s \"Viewer: hi 'there' newline c (Зомби, 500) for Steve\""));
        System.out.println("     -> " + out);
        check("session placeholders", vars.get("session_bits").equals("1000") && vars.get("session_follows").equals("1") && vars.get("session_events").equals("2"));
        check("unknown placeholder kept", Placeholders.apply("{nope} {user}", vars).equals("{nope} Viewer"));
        TwitchEvent sneaky = TwitchEvent.simple(TwitchEvent.Type.CHEER, "{player}", "x", 1, "{reward}", "", "");
        Map<String, String> sv = Placeholders.of(sneaky, "Steve");
        check("single pass: no re-expansion", Placeholders.apply("{user} {message}", sv).equals("{player} {reward}"));
        check("describe()", reward.describe().contains("Зомби") && cheer.describe().contains("1000"));
        check("stats: history + last", stats.lastEvent() == follow && stats.recent(10).size() == 2 && stats.chatMessages == 1);

        System.out.println("== Repeat ==");
        ModConfig.Action rep = new ModConfig.Action();
        rep.repeat = "{amount}"; rep.repeatPer = 100; rep.maxRepeat = 5;
        check("repeat 1000/100 capped 5", ActionRunner.computeRepeat(rep, Map.of("amount", "1000")) == 5);
        check("repeat 250/100 = 2", ActionRunner.computeRepeat(rep, Map.of("amount", "250")) == 2);
        check("repeat 50/100 -> min 1", ActionRunner.computeRepeat(rep, Map.of("amount", "50")) == 1);
        rep.repeat = "3"; rep.repeatPer = 1;
        check("repeat literal 3", ActionRunner.computeRepeat(rep, Map.of()) == 3);
        rep.repeat = "";
        check("repeat empty -> 1", ActionRunner.computeRepeat(rep, Map.of()) == 1);

        System.out.println("== Cooldowns ==");
        Cooldowns cd = new Cooldowns();
        check("no cooldown initially", cd.remaining("reward:зомби", "viewer", 30, 60) == 0);
        cd.mark("reward:зомби", "viewer", 30, 60);
        check("global cooldown active", cd.remaining("reward:зомби", "other", 30, 0) > 0);
        check("other key free", cd.remaining("reward:крипер", "viewer", 30, 0) == 0);
        cd.mark("chat:heal", "viewer", 0, 300);
        check("user cooldown: same user blocked, other free", cd.remaining("chat:heal", "VIEWER", 0, 300) > 0 && cd.remaining("chat:heal", "other", 0, 300) == 0);
        check("zero cooldown never blocks", cd.remaining("chat:heal", "viewer", 0, 0) == 0);

        System.out.println("== Config JSON roundtrip + warnings ==");
        com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        String json = gson.toJson(cfg);
        ModConfig back = gson.fromJson(json, ModConfig.class);
        check("rewards preserved + ordered", back.rewards.size() == cfg.rewards.size() && back.rewards.keySet().iterator().next().equals("Зомби"));
        check("cheer thresholds preserved", back.cheer.get("1000").commands.size() == 5);
        check("new fields preserved", back.rewards.get("Лотерея").chance == 10 && back.chatCommands.get("heal").permission.equals("subscriber")
                && back.overlay.corner.equals("top-left") && back.blockedCommands.contains("stop"));
        check("no html escaping of quotes/&", json.contains("&d{user}") && !json.contains("\\u0026"));
        check("transient fields not saved", !json.contains("\"warnings\"") && !json.contains("loadError"));
        check("no warnings for own output", ModConfig.findWarnings(json).isEmpty());
        ModConfig partial = gson.fromJson("{\"clientId\":\"abc\",\"rewards\":{\"Тест\":{\"commands\":[\"say hi\"]}}}", ModConfig.class);
        check("partial config: defaults for missing", partial.autoConnect && partial.follow != null && partial.follow.isEmpty() && partial.cheer.isEmpty()
                && partial.isEnabled(Module.TWITCH_CHAT) && partial.maxQueuedEvents == 20);
        check("partial config: reward parsed", partial.rewards.get("Тест").commands.get(0).equals("say hi") && "".equals(partial.rewards.get("Тест").message)
                && partial.rewards.get("Тест").chance == 100 && partial.rewards.get("Тест").enabled);
        List<String> warnings = ModConfig.findWarnings("{\"clientID\":\"x\",\"rewards\":{\"A\":{\"comands\":[],\"message\":\"\"}},\"cheer\":{\"abc\":{}}}");
        String joined = String.join(" | ", warnings);
        check("warnings: typo top-level, typo in action, non-numeric threshold", warnings.size() == 3
                && joined.contains("clientID") && joined.contains("comands") && joined.contains("\"abc\""));
        System.out.println("     -> " + warnings);

        // addonTriggers: действия из конфига, привязанные к кастомным триггерам аддонов (слоты v0…v3)
        String atJson = "{\"addonTriggers\":{\"v2\":{\"message\":\"Босс {trigger} появился!\",\"cooldown\":30},"
                + "\"V0\":{\"commands\":[\"say hi\"]},\"1\":{\"title\":\"Слот без буквы\"},\"v9\":{},\"oops\":{}}}";
        ModConfig at = ModConfig.fromJson(atJson);
        check("addonTriggers: слот v2 находится", at.findAddonTriggerAction("v2") != null
                && "Босс {trigger} появился!".equals(at.findAddonTriggerAction("v2").action().message)
                && "addonTrigger:v2".equals(at.findAddonTriggerAction("v2").key())
                && at.findAddonTriggerAction("v2").action().cooldown == 30);
        check("addonTriggers: поиск не смотрит на регистр, пробелы и букву v", at.findAddonTriggerAction(" V2 ") != null
                && at.findAddonTriggerAction("0") != null && at.findAddonTriggerAction("1") != null);
        check("addonTriggers: слот вне диапазона — null", at.findAddonTriggerAction("v9") == null
                && at.findAddonTriggerAction("v4") == null && at.findAddonTriggerAction("-1") == null);
        check("addonTriggers: мусор и пустой конфиг — null", at.findAddonTriggerAction(null) == null
                && at.findAddonTriggerAction("абв") == null && ModConfig.createDefault().findAddonTriggerAction("v0") == null);
        check("normalizeTriggerSlot приводит ключи", "v1".equals(ModConfig.normalizeTriggerSlot("V1"))
                && "v0".equals(ModConfig.normalizeTriggerSlot(" 0 ")) && ModConfig.normalizeTriggerSlot("v4") == null
                && ModConfig.normalizeTriggerSlot(null) == null && ModConfig.normalizeTriggerSlot("x") == null);
        List<String> atWarnings = ModConfig.findWarnings(atJson);
        check("addonTriggers: предупреждения на неверные слоты (v9, oops)", atWarnings.size() == 2
                && String.join(" | ", atWarnings).contains("\"v9\"") && String.join(" | ", atWarnings).contains("\"oops\""));
        check("addonTriggers: верные ключи без предупреждений",
                ModConfig.findWarnings("{\"addonTriggers\":{\"v0\":{\"message\":\"hi\"},\"3\":{}}}").isEmpty());
        check("addonTriggers: переживает JSON-раундтрип", ModConfig.fromJson(at.toJson()).findAddonTriggerAction("v2") != null);
        ModConfig atClean = ModConfig.createDefault();
        atClean.addonTriggers.put("v2", new ModConfig.Action("Босс появился!", "", ""));
        check("addonTriggers: свой вывод без предупреждений", ModConfig.findWarnings(atClean.toJson()).isEmpty()
                && "Босс появился!".equals(ModConfig.fromJson(atClean.toJson()).findAddonTriggerAction("v2").action().message));

        // byTier: разные действия по уровню подписки (1/2/3/prime) — ветвление по {tier}
        ModConfig bt = ModConfig.createDefault();
        bt.subscribe = new ModConfig.Action("базовая подписка", "", "");
        bt.resub = new ModConfig.Action("базовый ресаб", "", "");
        bt.giftSub = new ModConfig.Action("базовые подарки", "", "");
        bt.subscribeByTier.put("3", new ModConfig.Action("подписка Tier 3", "", ""));
        bt.subscribeByTier.put("prime", new ModConfig.Action("подписка Prime", "", ""));
        bt.resubByTier.put("2", new ModConfig.Action("ресабы Tier 2", "", ""));
        bt.giftSubByTier.put("3", new ModConfig.Action("подарки Tier 3", "", ""));
        check("byTier: подписка Tier 3 заменяет базовую", "подписка Tier 3".equals(bt.findAction(TwitchEvent.test(TwitchEvent.Type.SUBSCRIBE, "V", 1, "", "", "3")).action().message)
                && "subscribe:tier:3".equals(bt.findAction(TwitchEvent.test(TwitchEvent.Type.SUBSCRIBE, "V", 1, "", "", "3")).key()));
        check("byTier: Tier 1 без переопределения — базовая", "базовая подписка".equals(bt.findAction(TwitchEvent.test(TwitchEvent.Type.SUBSCRIBE, "V", 1, "", "", "1")).action().message)
                && "subscribe".equals(bt.findAction(TwitchEvent.test(TwitchEvent.Type.SUBSCRIBE, "V", 1, "", "", "1")).key()));
        check("byTier: Prime не различает регистр", "подписка Prime".equals(bt.findAction(TwitchEvent.test(TwitchEvent.Type.SUBSCRIBE, "V", 1, "", "", "Prime")).action().message));
        check("byTier: ресаб своего уровня, когда пороги не подошли", "ресабы Tier 2".equals(bt.findAction(TwitchEvent.test(TwitchEvent.Type.RESUB, "V", 6, "привет", "", "2")).action().message));
        bt.resubTiers.put("12", new ModConfig.Action("12 месяцев", "", ""));
        check("byTier: порог месяцев важнее уровня ресаба", "12 месяцев".equals(bt.findAction(TwitchEvent.test(TwitchEvent.Type.RESUB, "V", 12, "привет", "", "2")).action().message));
        check("byTier: подарки своего уровня, когда пороги не подошли", "подарки Tier 3".equals(bt.findAction(TwitchEvent.test(TwitchEvent.Type.GIFT_SUB, "V", 5, "", "", "3")).action().message));
        bt.giftSubTiers.put("5", new ModConfig.Action("5 подарков", "", ""));
        check("byTier: порог количества подарков важнее уровня", "5 подарков".equals(bt.findAction(TwitchEvent.test(TwitchEvent.Type.GIFT_SUB, "V", 5, "", "", "3")).action().message));
        check("normalizeSubTier приводит варианты", "1".equals(ModConfig.normalizeSubTier("1000")) && "2".equals(ModConfig.normalizeSubTier("Tier 2"))
                && "prime".equals(ModConfig.normalizeSubTier(" PRIME ")) && ModConfig.normalizeSubTier("4") == null
                && ModConfig.normalizeSubTier(null) == null && ModConfig.normalizeSubTier("") == null);
        bt.subscribeByTier.put("1000", new ModConfig.Action("подписка уровня 1 (ключ 1000)", "", ""));
        check("byTier: ключи конфига тоже нормализуются (1000 = 1)", "подписка уровня 1 (ключ 1000)".equals(
                bt.findAction(TwitchEvent.test(TwitchEvent.Type.SUBSCRIBE, "V", 1, "", "", "1")).action().message));
        List<String> btWarnings = ModConfig.findWarnings("{\"subscribeByTier\":{\"2\":{\"message\":\"hi\"},\"5\":{}},\"resubByTier\":{\"x\":{}}}");
        check("byTier: предупреждения на неверные уровни", btWarnings.size() == 2
                && String.join(" | ", btWarnings).contains("\"5\"") && String.join(" | ", btWarnings).contains("\"x\""));
        check("byTier: верные уровни без предупреждений",
                ModConfig.findWarnings("{\"subscribeByTier\":{\"1\":{},\"prime\":{}},\"giftSubByTier\":{\"3\":{}}}").isEmpty());
        check("byTier: переживает JSON-раундтрип", "подписка Tier 3".equals(ModConfig.fromJson(bt.toJson())
                .findAction(TwitchEvent.test(TwitchEvent.Type.SUBSCRIBE, "V", 1, "", "", "3")).action().message));

        System.out.println("== Chat helpers ==");
        check("ChatSender.clean strips colors/newlines", ChatSender.clean("§aПривет &c{user}\nмир").equals("Привет {user} мир"));
        check("ChatSender.clean neutralizes /commands", ChatSender.clean("/ban x").startsWith(" /"));
        check("parseColor", TwitchChatRenderer.parseColor("#1E90FF") == 0x1E90FF && TwitchChatRenderer.parseColor("") == -1
                && TwitchChatRenderer.parseColor("#000000") > 0);
        check("badges glyphs", TwitchChatRenderer.badges(Set.of("moderator", "subscriber")).equals("⚔★")
                && TwitchChatRenderer.badges(Set.of("broadcaster")).equals("♛"));
        check("close codes", EventSubClient.describeClose(4003).contains("10") && EventSubClient.describeClose(1234).contains("1234"));
        check("permission parse", TwitchEvent.Permission.parse("mods") == TwitchEvent.Permission.MODERATOR
                && TwitchEvent.Permission.parse(null) == TwitchEvent.Permission.EVERYONE);

        System.out.println("== v1.2: Modules ==");
        ModConfig m = ModConfig.createDefault();
        check("all modules enabled by default", java.util.Arrays.stream(Module.values()).allMatch(m::isEnabled));
        m.setEnabled(Module.FOLLOWS, false);
        check("module toggle", !m.isEnabled(Module.FOLLOWS) && m.isEnabled(Module.SUBSCRIPTIONS));
        check("forEvent mapping", Module.forEvent(TwitchEvent.Type.FOLLOW) == Module.FOLLOWS && Module.forEvent(TwitchEvent.Type.CHEER) == Module.BITS
                && Module.forEvent(TwitchEvent.Type.REWARD) == Module.CHANNEL_POINTS && Module.forEvent(TwitchEvent.Type.GOAL) == Module.GOALS);
        check("byId", Module.byId("goals") == Module.GOALS && Module.byId("GOALS") == Module.GOALS && Module.byId("nope") == null);
        check("affectsSubscriptions", Module.FOLLOWS.affectsSubscriptions() && Module.TWITCH_CHAT.affectsSubscriptions() && !Module.OVERLAY.affectsSubscriptions());
        m.setEnabled(Module.TWITCH_CHAT, false);
        check("needsChat: chat off but commands on", m.needsChat());
        m.setEnabled(Module.CHAT_COMMANDS, false);
        check("needsChat: both off", !m.needsChat());
        String mj = m.toJson();
        ModConfig mb = ModConfig.fromJson(mj);
        check("modules roundtrip", !mb.isEnabled(Module.FOLLOWS) && !mb.isEnabled(Module.TWITCH_CHAT) && mb.isEnabled(Module.RAIDS) && mj.contains("\"modules\""));

        System.out.println("== v1.2: Loot tables ==");
        ModConfig d = ModConfig.createDefault();
        check("default gifts for follow/sub/raid", d.follow.loot != null && d.follow.loot.size() >= 3 && d.subscribe.loot != null && !d.subscribe.loot.isEmpty()
                && d.raid.loot != null && !d.raid.loot.isEmpty() && d.follow.message.contains("{loot}"));
        check("default follow loot has give commands", d.follow.loot.stream().allMatch(e -> !e.commands.isEmpty() && e.commands.get(0).startsWith("give")));
        ModConfig.Action lootOwner = new ModConfig.Action();
        check("pickLoot empty -> null", lootOwner.pickLoot(new java.util.Random(1)) == null);
        lootOwner.loot = new java.util.ArrayList<>(List.of(
                ModConfig.Action.lootEntry("common", 90, "give @s cookie"),
                ModConfig.Action.lootEntry("rare", 10, "give @s diamond"),
                ModConfig.Action.lootEntry("never", 0, "give @s nothing"),
                ModConfig.Action.lootEntry("off", 1000, "give @s off")));
        lootOwner.loot.get(3).enabled = false;
        java.util.Random rnd = new java.util.Random(42);
        int common = 0, rare = 0, bad = 0;
        for (int i = 0; i < 10000; i++) {
            ModConfig.Action picked = lootOwner.pickLoot(rnd);
            if (picked == null) { bad++; continue; }
            switch (picked.name) { case "common" -> common++; case "rare" -> rare++; default -> bad++; }
        }
        check("pickLoot weights ~90/10, zero-weight & disabled never", bad == 0 && common > 8700 && common < 9300 && rare > 700 && rare < 1300);
        System.out.println("     -> common=" + common + " rare=" + rare);
        lootOwner.loot.forEach(e -> e.enabled = false);
        check("pickLoot all disabled -> null", lootOwner.pickLoot(rnd) == null);
        ModConfig.Action lootCopy = d.follow.copy();
        lootCopy.loot.get(0).name = "changed";
        check("Action.copy deep-copies loot", !d.follow.loot.get(0).name.equals("changed") && lootCopy.loot.size() == d.follow.loot.size());
        check("loot survives json", ModConfig.fromJson(d.toJson()).follow.loot.size() == d.follow.loot.size());

        System.out.println("== v1.2: Goals ==");
        TwitchEvent f1 = TwitchEvent.simple(TwitchEvent.Type.FOLLOW, "A", "a", 0, "", "", "");
        check("contribution follows", ModConfig.GoalType.FOLLOWS.contribution(f1) == 1 && ModConfig.GoalType.SUBS.contribution(f1) == 0);
        check("contribution synthetic = 0", ModConfig.GoalType.FOLLOWS.contribution(TwitchEvent.test(TwitchEvent.Type.FOLLOW, "T", 0, "", "", "")) == 0);
        check("contribution bits/gifts/raidViewers/points", ModConfig.GoalType.BITS.contribution(ev(TwitchEvent.Type.CHEER, 1000, "")) == 1000
                && ModConfig.GoalType.SUBS.contribution(ev(TwitchEvent.Type.GIFT_SUB, 5, "")) == 5
                && ModConfig.GoalType.GIFTS.contribution(ev(TwitchEvent.Type.GIFT_SUB, 5, "")) == 5
                && ModConfig.GoalType.RAID_VIEWERS.contribution(ev(TwitchEvent.Type.RAID, 321, "")) == 321
                && ModConfig.GoalType.RAIDS.contribution(ev(TwitchEvent.Type.RAID, 321, "")) == 1
                && ModConfig.GoalType.POINTS.contribution(ev(TwitchEvent.Type.REWARD, 500, "X")) == 500
                && ModConfig.GoalType.EVENTS.contribution(ev(TwitchEvent.Type.CHAT, 0, "")) == 0
                && ModConfig.GoalType.EVENTS.contribution(f1) == 1);
        check("GoalType.parse", ModConfig.GoalType.parse("raidViewers") == ModConfig.GoalType.RAID_VIEWERS && ModConfig.GoalType.parse("RAID_VIEWERS") == ModConfig.GoalType.RAID_VIEWERS
                && ModConfig.GoalType.parse("garbage") == ModConfig.GoalType.FOLLOWS);
        check("default goals exist & valid", d.goals.size() >= 3 && d.goals.stream().allMatch(g -> g.enabled && !g.name.isBlank() && g.target > 0 && g.action != null)
                && d.findGoal("фолловеры") != null && d.findGoal("nope") == null);

        try {
            dev.dedworkshop.twitchcraft.TwitchCraftClient fakeMod = new dev.dedworkshop.twitchcraft.TwitchCraftClient();
            java.lang.reflect.Field cf = dev.dedworkshop.twitchcraft.TwitchCraftClient.class.getDeclaredField("config");
            cf.setAccessible(true);
            ModConfig gc = ModConfig.createDefault();
            gc.showEventsInChat = false;
            gc.goals.clear();
            gc.goals.add(new ModConfig.Goal("F", "follows", 3, true, new ModConfig.Action()));
            gc.goals.add(new ModConfig.Goal("B", "bits", 1000, false, new ModConfig.Action()));
            gc.goals.add(new ModConfig.Goal("Off", "follows", 1, true, new ModConfig.Action()));
            gc.goals.get(2).enabled = false;
            cf.set(fakeMod, gc);
            java.nio.file.Path goalsPath = java.nio.file.Files.createTempFile("twitchcraft-goals", ".json");
            java.nio.file.Files.delete(goalsPath);
            GoalTracker tracker = new GoalTracker(fakeMod, Runnable::run, goalsPath);

            check("goal: 2 follows -> no fire", tracker.onEvent(f1).isEmpty() && tracker.onEvent(f1).isEmpty() && tracker.peek("F").count == 2);
            List<TwitchEvent> fired = tracker.onEvent(TwitchEvent.simple(TwitchEvent.Type.FOLLOW, "Zed", "zed", 0, "", "", ""));
            check("goal: 3rd follow fires GOAL event", fired.size() == 1 && fired.get(0).type() == TwitchEvent.Type.GOAL && fired.get(0).reward().equals("F")
                    && fired.get(0).amount() == 3 && fired.get(0).message().equals("1") && fired.get(0).user().equals("Zed") && !fired.get(0).synthetic());
            check("goal: repeat resets counter, completed=1", tracker.peek("F").count == 0 && tracker.peek("F").completed == 1 && !tracker.peek("F").done);
            check("goal: disabled goal ignored, case-insensitive peek", tracker.peek("off").count == 0 && tracker.peek("f").completed == 1);
            check("goal: synthetic events don't count", tracker.onEvent(TwitchEvent.test(TwitchEvent.Type.FOLLOW, "T", 0, "", "", "")).isEmpty() && tracker.peek("F").count == 0);
            List<TwitchEvent> big = tracker.onEvent(ev(TwitchEvent.Type.CHEER, 2500, ""));
            check("goal: one-shot fires once even if overshoot", big.size() == 1 && tracker.peek("B").done && tracker.peek("B").count == 1000);
            check("goal: one-shot does not fire again", tracker.onEvent(ev(TwitchEvent.Type.CHEER, 5000, "")).isEmpty());
            List<TwitchEvent> multi = tracker.onEvent(TwitchEvent.simple(TwitchEvent.Type.FOLLOW, "Q", "q", 0, "", "", ""));
            tracker.add(gc.goals.get(0), 7); // 1 + 7 = 8 -> fires twice (6), remainder 2
            check("goal: manual add fires multiple times", multi.isEmpty() && tracker.peek("F").completed == 3 && tracker.peek("F").count == 2);
            check("goal: peek returns copy", (tracker.peek("F").count = 99) == 99 && tracker.peek("F").count == 2);
            check("goal: lines() only enabled goals", tracker.lines().size() == 2 && tracker.lines().get(0).text().startsWith("F: 2/3")
                    && tracker.lines().get(1).text().contains("✔"));
            check("goal: progress persisted to file", java.nio.file.Files.exists(goalsPath) && java.nio.file.Files.readString(goalsPath).contains("\"completed\": 3"));
            GoalTracker reloaded = new GoalTracker(fakeMod, Runnable::run, goalsPath);
            check("goal: progress restored after restart", reloaded.peek("F").count == 2 && reloaded.peek("F").completed == 3 && reloaded.peek("B").done);
            reloaded.reset("F");
            check("goal: reset one", reloaded.peek("F").count == 0 && reloaded.peek("B").done);
            reloaded.reset(null);
            check("goal: reset all", !reloaded.peek("B").done && reloaded.lines().get(1).text().equals("B: 0/1000"));
            gc.setEnabled(Module.GOALS, false);
            check("goal: module off -> nothing counted", reloaded.onEvent(f1).isEmpty() && reloaded.peek("F").count == 0);
            java.nio.file.Files.deleteIfExists(goalsPath);
        } catch (Exception e) {
            e.printStackTrace();
            check("goal tracker tests threw " + e, false);
        }

        System.out.println("== v1.4: Fundraisers (boss bar) ==");
        check("fund: formatAmount", FundraiserTracker.formatAmount(5000).equals("5 000") && FundraiserTracker.formatAmount(1234.5).equals("1 234.50")
                && FundraiserTracker.formatAmount(0).equals("0") && FundraiserTracker.formatAmount(1000000).equals("1 000 000")
                && FundraiserTracker.formatAmount(999).equals("999") && FundraiserTracker.formatAmount(0.7 * 100).equals("70")
                && FundraiserTracker.formatAmount(-5).equals("-5") && FundraiserTracker.formatAmount(Double.NaN).equals("0"));
        ModConfig.Fundraiser rates = new ModConfig.Fundraiser("R", "", 1000, null);
        rates.bitsRate = 0.7;
        rates.subValue = 150;
        rates.pointsRate = 0.1;
        TwitchEvent fundDon500 = TwitchEvent.donation(TwitchEvent.SOURCE_DONATION_ALERTS, "Dona", 500, "RUB", "hi", "d1", false);
        check("fund: contribution rates", FundraiserTracker.contribution(rates, fundDon500) == 500
                && FundraiserTracker.contribution(rates, ev(TwitchEvent.Type.CHEER, 100, "")) == 70.0
                && FundraiserTracker.contribution(rates, ev(TwitchEvent.Type.SUBSCRIBE, 0, "")) == 150
                && FundraiserTracker.contribution(rates, ev(TwitchEvent.Type.RESUB, 7, "")) == 150
                && FundraiserTracker.contribution(rates, ev(TwitchEvent.Type.GIFT_SUB, 5, "")) == 750
                && FundraiserTracker.contribution(rates, ev(TwitchEvent.Type.REWARD, 500, "X")) == 50.0
                && FundraiserTracker.contribution(rates, f1) == 0
                && FundraiserTracker.contribution(rates, TwitchEvent.test(TwitchEvent.Type.CHEER, "T", 100, "", "", "")) == 0);
        rates.countDonations = false;
        check("fund: countDonations=false ignores donations", FundraiserTracker.contribution(rates, fundDon500) == 0);
        check("fund: defaults have example fundraiser + chat command", d.fundraisers.size() == 1 && d.fundraisers.get(0).name.equals("Сбор")
                && d.fundraisers.get(0).target == 5000 && d.fundraisers.get(0).visible && d.chatCommands.containsKey("fund")
                && d.findFundraiser("сбор") != null && d.findFundraiser("nope") == null && d.fundraiserSettings != null && d.fundraiserSettings.y == 12);
        TwitchEvent fundDone = TwitchEvent.fund("Сбор", 5000, 2, "RUB", "Vasya", "vasya", false);
        check("fund: FUND event shape", fundDone.type() == TwitchEvent.Type.FUND && fundDone.reward().equals("Сбор") && fundDone.amount() == 5000
                && fundDone.message().equals("2") && fundDone.tier().equals("RUB") && fundDone.currencySymbol().equals("₽")
                && fundDone.describe().contains("Сбор") && fundDone.shortText().contains("Сбор") && fundDone.actionKey().equals("fund:сбор"));
        ModConfig.Resolved fr = d.findAction(fundDone);
        check("fund: findAction -> fundraiser action, key fund:<name>", fr != null && fr.action() == d.fundraisers.get(0).action && fr.key().equals("fund:сбор")
                && d.findAction(TwitchEvent.fund("Nope", 1, 1, "RUB", "", "", false)) == null);
        Map<String, String> fundVars = Placeholders.of(fundDone, "Steve");
        check("fund: placeholders for FUND event", "Сбор".equals(fundVars.get("goal")) && "5000".equals(fundVars.get("target")) && "2".equals(fundVars.get("times"))
                && "5000 ₽".equals(fundVars.get("sum")) && "RUB".equals(fundVars.get("currency")) && "Vasya".equals(fundVars.get("user")));
        check("fund: Module.forEvent(FUND) = FUNDRAISERS, 17 modules", Module.forEvent(TwitchEvent.Type.FUND) == Module.FUNDRAISERS && Module.values().length == 21
                && Module.byId("fundraisers") == Module.FUNDRAISERS);
        ModConfig.Fundraiser badFund = new ModConfig.Fundraiser("  X  ", null, -5, null);
        badFund.color = "Rainbow";
        badFund.style = "zigzag";
        badFund.format = "";
        badFund.bitsRate = -1;
        badFund.hideWhenCompleteSeconds = -3;
        ModConfig.normalizeFundraiser(badFund);
        check("fund: normalizeFundraiser fixes bad values", badFund.name.equals("X") && badFund.title.isEmpty() && badFund.target == 1 && badFund.color.equals("pink")
                && badFund.style.equals("notched_10") && badFund.format.equals(ModConfig.DEFAULT_FUND_FORMAT) && badFund.bitsRate == 0
                && badFund.hideWhenCompleteSeconds == 0 && badFund.action != null);
        List<String> fundWarnings = ModConfig.findWarnings("{\"configVersion\":5,\"fundraisers\":[{\"name\":\"Y\",\"target\":100,\"color\":\"rainbow\",\"tagret\":5}]}");
        check("fund: findWarnings catches bad color + typo", fundWarnings.size() >= 2 && String.join(" ", fundWarnings).contains("rainbow")
                && String.join(" ", fundWarnings).contains("tagret"));

        try {
            dev.dedworkshop.twitchcraft.TwitchCraftClient fakeMod = new dev.dedworkshop.twitchcraft.TwitchCraftClient();
            java.lang.reflect.Field cf = dev.dedworkshop.twitchcraft.TwitchCraftClient.class.getDeclaredField("config");
            cf.setAccessible(true);
            ModConfig fc = ModConfig.createDefault();
            fc.showEventsInChat = false;
            fc.fundraisers.clear();
            ModConfig.Fundraiser a = new ModConfig.Fundraiser("A", "&6Микрофон", 1000, new ModConfig.Action());
            ModConfig.Fundraiser b = new ModConfig.Fundraiser("B", "", 500, new ModConfig.Action());
            b.countDonations = false;
            b.bitsRate = 1;
            b.visible = false;
            ModConfig.Fundraiser off = new ModConfig.Fundraiser("Off", "", 10, new ModConfig.Action());
            off.enabled = false;
            fc.fundraisers.add(a);
            fc.fundraisers.add(b);
            fc.fundraisers.add(off);
            cf.set(fakeMod, fc);
            java.nio.file.Path fundPath = java.nio.file.Files.createTempFile("twitchcraft-fundraisers", ".json");
            java.nio.file.Files.delete(fundPath);
            FundraiserTracker ft = new FundraiserTracker(fakeMod, Runnable::run, fundPath);

            TwitchEvent don400 = TwitchEvent.donation(TwitchEvent.SOURCE_DONATION_ALERTS, "Dona", 400, "RUB", "", "d2", false);
            check("fund: 400 of 1000 -> no fire, only A counts donations", ft.onEvent(don400).isEmpty() && ft.peek("A").current == 400 && ft.peek("B").current == 0
                    && ft.peek("a").contributions == 1 && ft.peek("A").lastUser.equals("Dona") && ft.peek("A").lastAmount == 400);
            check("fund: cheer 300 -> B only (bitsRate 1), disabled Off ignored", ft.onEvent(ev(TwitchEvent.Type.CHEER, 300, "")).isEmpty()
                    && ft.peek("B").current == 300 && ft.peek("A").current == 400 && ft.peek("Off").current == 0 && ft.peek("Off").contributions == 0);
            check("fund: synthetic donation ignored", ft.onEvent(TwitchEvent.donation(TwitchEvent.SOURCE_DONATION_ALERTS, "T", 900, "RUB", "", "", true)).isEmpty()
                    && ft.peek("A").current == 400);
            FundraiserTracker.Line la = ft.line(a);
            check("fund: line math", la.target() == 1000 && la.current() == 400 && la.left() == 600 && la.percent() == 40 && Math.abs(la.fraction() - 0.4f) < 0.001f
                    && !la.done() && la.symbol().equals("₽") && la.label().startsWith("§6Микрофон: 400 / 1 000 ₽ (40%)") && la.plainText().startsWith("Микрофон: 400 / 1 000 ₽ (40%)"));
            List<TwitchEvent> firedA = ft.onEvent(TwitchEvent.donation(TwitchEvent.SOURCE_DONATE_PAY, "Big", 700, "RUB", "", "d3", false));
            check("fund: 1100 >= 1000 fires FUND once", firedA.size() == 1 && firedA.get(0).type() == TwitchEvent.Type.FUND && firedA.get(0).reward().equals("A")
                    && firedA.get(0).amount() == 1000 && firedA.get(0).message().equals("1") && firedA.get(0).user().equals("Big") && !firedA.get(0).synthetic()
                    && ft.peek("A").done && ft.peek("A").completed == 1 && ft.peek("A").current == 1100 && ft.peek("A").completedAt > 0);
            check("fund: further donations keep adding, no re-fire", ft.onEvent(don400).isEmpty() && ft.peek("A").current == 1500 && ft.peek("A").done
                    && ft.line(a).percent() == 150 && ft.line(a).fraction() == 1f && ft.line(a).left() == 0);
            check("fund: manual negative add reopens", ft.add(a, -1200, "").isEmpty() && ft.peek("A").current == 300 && !ft.peek("A").done && ft.peek("A").contributions == 3);
            List<TwitchEvent> manual = ft.add(a, 700, "Вася");
            check("fund: manual add can close again (completed=2, lastUser)", manual.size() == 1 && manual.get(0).message().equals("2") && manual.get(0).user().equals("Вася")
                    && ft.peek("A").completed == 2 && ft.peek("A").lastUser.equals("Вася") && ft.peek("A").lastAmount == 700);
            check("fund: add(0)/NaN ignored", ft.add(a, 0, "x").isEmpty() && ft.add(a, Double.NaN, "x").isEmpty() && ft.peek("A").current == 1000);
            ft.set(a, 50);
            boolean setOpen = !ft.peek("A").done && ft.peek("A").current == 50 && ft.peek("A").completed == 2;
            ft.set(a, 99999);
            boolean setDone = ft.peek("A").done && ft.peek("A").completed == 2;
            check("fund: set() is silent — flag recalculated, completed unchanged", setOpen && setDone && ft.add(a, 10, "").isEmpty());
            ft.set(a, 250);
            check("fund: peek returns copy", (ft.peek("A").current = 7) == 7 && ft.peek("A").current == 250);
            check("fund: lines() enabled only, visibleLines() hides invisible", ft.lines().size() == 2 && ft.visibleLines().size() == 1
                    && ft.visibleLines().get(0).fund() == a);
            a.hideWhenCompleteSeconds = 5;
            ft.set(a, 5000);
            boolean shownFresh = ft.visibleLines().size() == 1;
            ft.progressFor("A").completedAt = System.currentTimeMillis() - 10_000;
            boolean hiddenLater = ft.visibleLines().isEmpty();
            a.hideWhenCompleteSeconds = 0;
            boolean shownAgain = ft.visibleLines().size() == 1;
            ft.set(a, 250);
            check("fund: hideWhenCompleteSeconds hides finished bar after timeout", shownFresh && hiddenLater && shownAgain);
            Map<String, String> fp = ft.placeholders();
            check("fund: global {fund_*} placeholders from main (visible) fundraiser", "Микрофон".equals(fp.get("fund")) && "A".equals(fp.get("fund_name"))
                    && "250".equals(fp.get("fund_current")) && "1 000".equals(fp.get("fund_target")) && "750".equals(fp.get("fund_left"))
                    && "25".equals(fp.get("fund_percent")) && "₽".equals(fp.get("fund_currency")) && fp.containsKey("fund_donors"));
            check("fund: progress persisted", java.nio.file.Files.exists(fundPath) && java.nio.file.Files.readString(fundPath).contains("\"completed\": 2"));
            FundraiserTracker ft2 = new FundraiserTracker(fakeMod, Runnable::run, fundPath);
            check("fund: restored after restart", ft2.peek("A").current == 250 && ft2.peek("A").completed == 2 && ft2.peek("B").current == 300
                    && ft2.peek("A").lastUser.isEmpty() && ft2.peek("A").lastAmount == 10 && ft2.peek("B").lastUser.equals("X"));
            ft2.rename("A", "Z");
            check("fund: rename keeps progress", ft2.peek("Z").current == 250 && ft2.peek("A").current == 0 && ft2.peek("Z").completed == 2);
            ft2.reset("Z");
            check("fund: reset one", ft2.peek("Z").current == 0 && ft2.peek("Z").completed == 0 && ft2.peek("B").current == 300);
            ft2.reset(null);
            check("fund: reset all", ft2.peek("B").current == 0 && ft2.peek("B").contributions == 0);
            fc.setEnabled(Module.FUNDRAISERS, false);
            check("fund: module off -> nothing counted", ft2.onEvent(don400).isEmpty() && ft2.peek("A").current == 0 && ft2.peek("Z").current == 0);
            fc.setEnabled(Module.FUNDRAISERS, true);
            java.nio.file.Files.writeString(fundPath, "{garbage");
            FundraiserTracker ft3 = new FundraiserTracker(fakeMod, Runnable::run, fundPath);
            check("fund: corrupt state file -> empty progress, no crash", ft3.peek("A").current == 0 && ft3.onEvent(don400).isEmpty() && ft3.peek("A").current == 400);
            java.nio.file.Files.deleteIfExists(fundPath);
        } catch (Exception e) {
            e.printStackTrace();
            check("fundraiser tracker tests threw " + e, false);
        }

        System.out.println("== v1.2: Migration v2 -> v3 ==");
        String v2 = "{\"configVersion\":2,\"clientId\":\"abc\",\"twitchChat\":{\"enabled\":false,\"prefix\":\"[T] \"},\"overlay\":{\"enabled\":true,\"corner\":\"top-right\"},"
                + "\"follow\":{\"message\":\"&d{user} follows\"},\"rewards\":{\"Зомби\":{\"commands\":[\"summon zombie\"]}}}";
        ModConfig mig = ModConfig.fromJson(v2);
        boolean changed = mig.upgradeFrom(v2);
        check("v2 upgraded (to current version)", changed && mig.configVersion == 11 && mig.donationTiers.size() == 51 && !mig.fundraisers.isEmpty());
        check("v2 enabled flags -> modules", !mig.isEnabled(Module.TWITCH_CHAT) && mig.isEnabled(Module.OVERLAY) && mig.isEnabled(Module.FOLLOWS));
        check("v2 follow gets loot + {loot} in message", mig.follow.loot != null && !mig.follow.loot.isEmpty() && mig.follow.message.contains("{loot}") && mig.follow.message.startsWith("&d{user} follows"));
        check("v2 user data preserved", mig.clientId.equals("abc") && mig.twitchChat.prefix.equals("[T] ") && mig.overlay.corner.equals("top-right")
                && mig.rewards.get("Зомби").commands.get(0).equals("summon zombie"));
        check("v2 gets goals + examples", !mig.goals.isEmpty() && mig.goalsSettings != null && !mig.chatCommands.isEmpty());
        String v3 = mig.toJson();
        check("v3 output is stable (no re-upgrade)", !ModConfig.fromJson(v3).upgradeFrom(v3) && ModConfig.findWarnings(v3).isEmpty());
        check("v3 own default output: no warnings, no upgrade", !ModConfig.fromJson(d.toJson()).upgradeFrom(d.toJson()) && ModConfig.findWarnings(d.toJson()).isEmpty());
        List<String> goalWarnings = ModConfig.findWarnings("{\"configVersion\":3,\"goals\":[{\"name\":\"X\",\"type\":\"folows\",\"target\":5}]}");
        check("warning for unknown goal type", goalWarnings.size() == 1 && goalWarnings.get(0).contains("folows"));
        System.out.println("     -> " + goalWarnings);

        System.out.println("== v1.2: Placeholders for goals/loot ==");
        TwitchEvent goalEvent = TwitchEvent.goal("Фолловеры", 10, 2, "Zed", "zed", false);
        Map<String, String> gv = Placeholders.of(goalEvent, "Steve", new SessionStats());
        check("goal placeholders", gv.get("goal").equals("Фолловеры") && gv.get("target").equals("10") && gv.get("times").equals("2") && gv.get("user").equals("Zed")
                && gv.containsKey("loot"));
        check("goal event describe/shortText", goalEvent.describe().contains("Фолловеры") && goalEvent.type() == TwitchEvent.Type.GOAL);

        System.out.println("== v1.2.1: ActionRunner tick — loot chained from inside the tick loop (crash regression) ==");
        {
            net.minecraft.client.Minecraft mc = new net.minecraft.client.Minecraft();
            mc.player = new net.minecraft.client.player.LocalPlayer();
            net.minecraft.client.Minecraft.INSTANCE = mc;
            List<String> executed = new ArrayList<>();
            ActionRunner runner = new ActionRunner(null) {
                @Override
                protected boolean runCommand(String command) {
                    executed.add(command);
                    return true;
                }
            };
            ModConfig.Action tFollow = new ModConfig.Action();
            tFollow.commands = new ArrayList<>(List.of("particle heart ~ ~2 ~"));
            tFollow.loot = new ArrayList<>(List.of(ModConfig.Action.lootEntry("алмаз", 1, "give @s diamond")));
            int[] done = {0};
            TwitchEvent fe = TwitchEvent.test(TwitchEvent.Type.FOLLOW, "Tester", 0, "", "", "");
            Map<String, String> fv = Placeholders.of(fe, "Steve", new SessionStats());
            runner.run(fe, tFollow, fv, () -> done[0]++);
            boolean threw = false;
            try {
                for (int t = 0; t < 10 && (runner.runningCount() > 0 || done[0] == 0); t++) {
                    runner.tick(mc); // раньше здесь падал ConcurrentModificationException
                }
            } catch (Exception e) {
                threw = true;
                System.out.println("     !! " + e);
            }
            check("loot chained in tick: no exception", !threw);
            check("loot chained in tick: main then loot executed", executed.equals(List.of("particle heart ~ ~2 ~", "give @s diamond")));
            check("loot chained in tick: onDone exactly once, nothing left running", done[0] == 1 && runner.runningCount() == 0);

            // Несколько действий одновременно, у каждого лут с паузой — порядок и завершение
            executed.clear();
            done[0] = 0;
            ModConfig.Action tSub = new ModConfig.Action();
            tSub.commands = new ArrayList<>(List.of("say sub", "delay 1", "say sub2"));
            tSub.loot = new ArrayList<>(List.of(ModConfig.Action.lootEntry("тотем", 1, "delay 1", "give @s totem_of_undying")));
            runner.run(fe, tFollow, fv, () -> done[0]++);
            runner.run(fe, tSub, fv, () -> done[0]++);
            runner.run(fe, tSub, fv, () -> done[0]++);
            threw = false;
            try {
                for (int t = 0; t < 40 && runner.runningCount() > 0; t++) {
                    runner.tick(mc);
                }
            } catch (Exception e) {
                threw = true;
                System.out.println("     !! " + e);
            }
            check("parallel sequences with loot: no exception, all finished", !threw && runner.runningCount() == 0 && done[0] == 3);
            check("parallel sequences with loot: every command ran", executed.size() == 2 + 2 * 3 && executed.stream().filter(c -> c.equals("give @s totem_of_undying")).count() == 2);

            // Игрок вышел из мира во время действия: лут не выдаётся, onDone вызывается один раз, очередь пуста
            executed.clear();
            done[0] = 0;
            runner.run(fe, tFollow, fv, () -> done[0]++);
            mc.player = null;
            threw = false;
            try {
                runner.tick(mc);
                runner.tick(mc);
            } catch (Exception e) {
                threw = true;
                System.out.println("     !! " + e);
            }
            check("left world: cancelled cleanly, loot skipped, onDone once", !threw && executed.isEmpty() && done[0] == 1 && runner.runningCount() == 0);

            // Команда, бросившая исключение, не ломает остальные последовательности и не роняет тик
            mc.player = new net.minecraft.client.player.LocalPlayer();
            executed.clear();
            done[0] = 0;
            ActionRunner tBad = new ActionRunner(null) {
                @Override
                protected boolean runCommand(String command) {
                    if (command.startsWith("boom")) throw new IllegalStateException("test failure");
                    executed.add(command);
                    return true;
                }
            };
            ModConfig.Action boom = new ModConfig.Action();
            boom.commands = new ArrayList<>(List.of("boom now"));
            tBad.run(fe, boom, fv, () -> done[0]++);
            tBad.run(fe, tFollow, fv, () -> done[0]++);
            threw = false;
            try {
                for (int t = 0; t < 10 && tBad.runningCount() > 0; t++) {
                    tBad.tick(mc);
                }
            } catch (Exception e) {
                threw = true;
                System.out.println("     !! " + e);
            }
            check("throwing command: tick survives, other sequences complete", !threw && tBad.runningCount() == 0 && executed.contains("give @s diamond"));

            // Регрессия «после фоллова награда не выдалась»: ошибка внутри одной команды отменяла всю
            // последовательность — пропадали и остальные команды, и выпавший подарок, и подтверждение
            // активации (баллы зрителя «зависали» на Twitch). Теперь ошибочная команда пропускается.
            executed.clear();
            done[0] = 0;
            ModConfig.Action tBoomLoot = new ModConfig.Action();
            tBoomLoot.commands = new ArrayList<>(List.of("boom now", "say after boom"));
            tBoomLoot.loot = new ArrayList<>(List.of(ModConfig.Action.lootEntry("алмаз", 1, "give @s diamond")));
            ActionRunner tBoomRunner = new ActionRunner(null) {
                @Override
                protected boolean runCommand(String command) {
                    if (command.startsWith("boom")) throw new IllegalStateException("test failure");
                    executed.add(command);
                    return true;
                }
            };
            tBoomRunner.run(fe, tBoomLoot, fv, () -> done[0]++);
            threw = false;
            try {
                for (int t = 0; t < 20 && tBoomRunner.runningCount() > 0; t++) {
                    tBoomRunner.tick(mc);
                }
            } catch (Exception e) {
                threw = true;
                System.out.println("     !! " + e);
            }
            check("throwing command: the rest of the action still runs", !threw && executed.contains("say after boom"));
            check("throwing command: loot is still issued", executed.contains("give @s diamond"));
            check("throwing command: reward confirmed once (onDone), queue empty", done[0] == 1 && tBoomRunner.runningCount() == 0);
            check("throwing command: player is warned in chat", dev.dedworkshop.twitchcraft.util.Chat.lastText().contains("Не выполнено"));

            // Ошибка при показе выпавшей записи (заголовок/звук/тост/ответ) не отменяет сам подарок.
            // mod == null, поэтому mod.reply внутри runLoot бросает NPE — как любая ошибка эффектов.
            executed.clear();
            done[0] = 0;
            ModConfig.Action tBadEffects = new ModConfig.Action();
            tBadEffects.commands = new ArrayList<>(List.of("say gift"));
            ModConfig.Action badLoot = ModConfig.Action.lootEntry("золотое яблоко", 1, "give @s golden_apple");
            badLoot.reply = "Держи {loot}!";
            tBadEffects.loot = new ArrayList<>(List.of(badLoot));
            ActionRunner tEffectsRunner = new ActionRunner(null) {
                @Override
                protected boolean runCommand(String command) {
                    executed.add(command);
                    return true;
                }
            };
            tEffectsRunner.run(fe, tBadEffects, fv, () -> done[0]++);
            threw = false;
            try {
                for (int t = 0; t < 20 && tEffectsRunner.runningCount() > 0; t++) {
                    tEffectsRunner.tick(mc);
                }
            } catch (Exception e) {
                threw = true;
                System.out.println("     !! " + e);
            }
            check("loot effects failed: main commands ran", !threw && executed.contains("say gift"));
            check("loot effects failed: loot still given, onDone once, queue empty",
                    executed.contains("give @s golden_apple") && done[0] == 1 && tEffectsRunner.runningCount() == 0);
            net.minecraft.client.Minecraft.INSTANCE = null;
        }

        System.out.println("== v1.3: DonationParser — DonationAlerts ==");
        TwitchEvent da1 = DonationParser.donationAlerts(j("""
            {"id":1234567,"name":"donation","username":"Donor","message_type":"text","message":"Hi there","amount":500,"currency":"RUB","is_shown":0,"amount_in_user_currency":500,"created_at":"2026-10-05 12:00:00"}"""), "RUB");
        check("DA: basic donation", da1 != null && da1.type() == TwitchEvent.Type.DONATION && da1.amount() == 500 && da1.currency().equals("RUB")
                && da1.user().equals("Donor") && da1.message().equals("Hi there") && da1.rewardId().equals("1234567") && !da1.synthetic()
                && da1.source().equals(TwitchEvent.SOURCE_DONATION_ALERTS) && da1.sourceTitle().equals("DonationAlerts"));
        TwitchEvent da2 = DonationParser.donationAlerts(j("""
            {"id":2,"username":"Foreign","message":"","amount":10.5,"currency":"usd","amount_in_user_currency":950.75}"""), "RUB");
        check("DA: foreign currency -> amount_in_user_currency in main currency", da2.amount() == 950 && da2.currency().equals("RUB"));
        TwitchEvent da3 = DonationParser.donationAlerts(j("""
            {"id":3,"username":"Foreign","message":null,"amount":"10.99","currency":"USD"}"""), "RUB");
        check("DA: no conversion field -> keep original currency, string amount, null message", da3.amount() == 10 && da3.currency().equals("USD")
                && da3.message().isEmpty() && da3.currencySymbol().equals("$"));
        check("DA: null -> null", DonationParser.donationAlerts(null, "RUB") == null);
        check("currency symbols", TwitchEvent.currencySymbol("RUB").equals("₽") && TwitchEvent.currencySymbol("EUR").equals("€")
                && TwitchEvent.currencySymbol("XYZ").equals("XYZ") && TwitchEvent.currencySymbol("").isEmpty());

        JsonObject pub = j("""
            {"result":{"channel":"$alerts:donation_42","data":{"data":{"id":77,"username":"P","amount":100,"currency":"RUB","message":"m"}}}}""");
        check("Centrifugo: publication extracted", DonationParser.centrifugoPublication(pub) != null && DonationParser.centrifugoPublication(pub).get("id").getAsInt() == 77);
        check("Centrifugo: command reply ignored", DonationParser.centrifugoPublication(j("{\"id\":1,\"result\":{\"client\":\"uuid\",\"version\":\"2.2.1\"}}")) == null);
        check("Centrifugo: join push ignored", DonationParser.centrifugoPublication(j("{\"result\":{\"type\":1,\"channel\":\"$alerts:donation_42\",\"data\":{\"info\":{}}}}")) == null);
        check("Centrifugo: empty/other ignored", DonationParser.centrifugoPublication(j("{}")) == null && DonationParser.centrifugoPublication(j("{\"result\":{\"channel\":\"x\",\"data\":{\"foo\":1}}}")) == null);

        System.out.println("== v1.3: DonationParser — DonatePay ==");
        JsonObject dpResp = j("""
            {"status":"success","message":"","time":1700000000,"sum":"350.00","count":4,"data":[
              {"id":30,"what":"Third","sum":"150.00","commission":"7.5","status":"success","type":"donation","vars":{"name":"Third","comment":"yo"},"comment":"yo","created_at":{"date":"2026-10-05 12:00:00.000000","timezone_type":3,"timezone":"Europe/Moscow"}},
              {"id":10,"what":"","sum":"100.00","status":"success","type":"donation","vars":{"name":"First","comment":"from vars"}},
              {"id":20,"what":"Second","sum":"99.99","status":"wait","type":"donation","vars":{}},
              {"id":25,"what":"Cash","sum":"1000.00","status":"success","type":"cashout"},
              {"id":27,"what":"Tester","sum":"5","status":"user","type":"donation","vars":{"name":"Tester","comment":"test"}}
            ]}""");
        List<DonationParser.DonatePayTx> txs = DonationParser.donatePayTransactions(dpResp);
        check("DP: 4 donations sorted asc, cashout skipped", txs.size() == 4 && txs.get(0).id() == 10 && txs.get(1).id() == 20 && txs.get(2).id() == 27 && txs.get(3).id() == 30);
        check("DP: name/comment fallback to vars", txs.get(0).name().equals("First") && txs.get(0).comment().equals("from vars") && txs.get(3).name().equals("Third"));
        check("DP: sum parsed, statuses", txs.get(0).sum() == 100.0 && txs.get(0).isSuccess() && txs.get(1).isPending() && txs.get(2).isTest() && txs.get(3).sum() == 150.0
                && txs.get(0).currency().equals("RUB"));
        TwitchEvent dpEv = DonationParser.donatePay(txs.get(3), false);
        check("DP: event", dpEv.type() == TwitchEvent.Type.DONATION && dpEv.source().equals(TwitchEvent.SOURCE_DONATE_PAY) && dpEv.amount() == 150
                && dpEv.user().equals("Third") && dpEv.message().equals("yo") && dpEv.rewardId().equals("30") && dpEv.sourceTitle().equals("DonatePay"));
        check("DP: error detection", DonationParser.donatePayError(dpResp) == null
                && DonationParser.donatePayError(j("{\"status\":\"error\",\"message\":\"Wrong access token\"}")).equals("Wrong access token")
                && DonationParser.donatePayError(j("{\"status\":\"error\",\"message\":\"Too Many Attempts.\"}")).contains("20 секунд")
                && DonationParser.donatePayError(null) != null);
        check("DP: empty data", DonationParser.donatePayTransactions(j("{\"status\":\"success\",\"data\":[]}")).isEmpty() && DonationParser.donatePayTransactions(j("{}")).isEmpty());
        check("toInt", DonationParser.toInt(99.999) == 99 && DonationParser.toInt(100.0) == 100 && DonationParser.toInt(-5) == 0 && DonationParser.toInt(Double.NaN) == 0 && DonationParser.toInt(0.5) == 0);

        System.out.println("== v1.3: ModConfig donation tiers / modules / goals ==");
        ModConfig dc = ModConfig.createDefault();
        TwitchEvent don500 = TwitchEvent.donation(TwitchEvent.SOURCE_DONATION_ALERTS, "D", 500, "RUB", "msg", "1", false);
        TwitchEvent don499dp = TwitchEvent.donation(TwitchEvent.SOURCE_DONATE_PAY, "D", 499, "RUB", "msg", "2", false);
        check("default: 51 donation tiers, settings", dc.donationTiers.size() == 51 && dc.donations.currency.equals("RUB") && dc.donations.minAmount == 1
                && dc.donations.donatePayPollSeconds == 20 && dc.donations.callbackPort == 8631 && dc.donationTiers.values().stream().allMatch(a -> a.enabled));
        check("tier 500 -> '500', key donation:500", dc.findAction(don500).action() == dc.donationTiers.get("500") && dc.findAction(don500).key().equals("donation:500"));
        check("tier 499 (DonatePay) -> '450'", dc.findAction(don499dp).action() == dc.donationTiers.get("450"));
        check("tier 1 -> '1'; 0 -> null", dc.findAction(TwitchEvent.donation("test", "D", 1, "RUB", "", "", true)).action() == dc.donationTiers.get("1")
                && dc.findAction(TwitchEvent.donation("test", "D", 0, "RUB", "", "", true)) == null);
        check("tier 999999 -> '5000'", dc.findAction(TwitchEvent.donation("test", "D", 999999, "RUB", "", "", true)).action() == dc.donationTiers.get("5000"));
        ModConfig.Action onlyDa = new ModConfig.Action("DA only", "", "");
        dc.donationAlertsTiers.put("1", onlyDa);
        check("service table wins entirely for its service", dc.findAction(don500).action() == onlyDa && dc.findAction(don500).key().equals("donation:donationalerts:1")
                && dc.findAction(don499dp).action() == dc.donationTiers.get("450"));
        check("effectiveDonationTiers", dc.effectiveDonationTiers(TwitchEvent.SOURCE_DONATION_ALERTS) == dc.donationAlertsTiers
                && dc.effectiveDonationTiers(TwitchEvent.SOURCE_DONATE_PAY) == dc.donationTiers && dc.effectiveDonationTiers("test") == dc.donationTiers);
        dc.donationAlertsTiers.clear();
        check("Module.forEvent(event) by source", Module.forEvent(don500) == Module.DONATION_ALERTS && Module.forEvent(don499dp) == Module.DONATE_PAY
                && Module.forEvent(TwitchEvent.donation("test", "D", 5, "RUB", "", "", true)) == null && Module.forEvent(f1) == Module.FOLLOWS
                && Module.forEvent(TwitchEvent.Type.DONATION) == Module.DONATION_ALERTS);
        check("donation modules: kind, default enabled, ids", Module.DONATION_ALERTS.isDonationService() && Module.DONATE_PAY.isDonationService() && !Module.FOLLOWS.isDonationService()
                && dc.isEnabled(Module.DONATION_ALERTS) && dc.isEnabled(Module.DONATE_PAY) && Module.DONATION_ALERTS.id.equals("donationAlerts") && Module.DONATE_PAY.id.equals("donatePay"));
        dc.setEnabled(Module.DONATE_PAY, false);
        check("module toggle persists in modules map", !dc.isEnabled(Module.DONATE_PAY) && ModConfig.fromJson(dc.toJson()).isEnabled(Module.DONATE_PAY) == false && dc.isEnabled(Module.DONATION_ALERTS));
        check("goal contributions: donations / donationSum / synthetic", ModConfig.GoalType.DONATIONS.contribution(don500) == 1 && ModConfig.GoalType.DONATION_SUM.contribution(don500) == 500
                && ModConfig.GoalType.DONATION_SUM.contribution(TwitchEvent.donation("test", "D", 500, "RUB", "", "", true)) == 0
                && ModConfig.GoalType.EVENTS.contribution(don500) == 1 && ModConfig.GoalType.BITS.contribution(don500) == 0);
        check("GoalType.parse donationSum", ModConfig.GoalType.parse("donationSum") == ModConfig.GoalType.DONATION_SUM && ModConfig.GoalType.parse("donations") == ModConfig.GoalType.DONATIONS);
        check("default goals include donation goal", dc.goals.stream().anyMatch(g -> "donationSum".equals(g.type) && g.target > 0));
        check("event actionKey/describe/shortText", don500.actionKey().equals("donation") && don500.describe().contains("D") && don500.describe().contains("500")
                && don500.describe().contains("₽") && don500.shortText().contains("донат") && don500.withoutMessage().message().isEmpty()
                && don500.withoutMessage().amount() == 500 && don500.withoutMessage().source().equals(TwitchEvent.SOURCE_DONATION_ALERTS));

        System.out.println("== v1.3: Placeholders / SessionStats ==");
        SessionStats ds = new SessionStats();
        ds.record(don500, "ok");
        ds.record(don499dp, "ok");
        Map<String, String> dv = Placeholders.of(don500, "Steve", ds);
        check("placeholders sum/currency/source/amount", dv.get("sum").equals("500 ₽") && dv.get("currency").equals("RUB") && dv.get("source").equals("DonationAlerts")
                && dv.get("amount").equals("500") && dv.get("user").equals("D") && dv.get("message").equals("msg"));
        check("session stats donations", ds.donations == 2 && ds.donationSum == 999 && dv.get("session_donations").equals("2") && dv.get("session_donation_sum").equals("999")
                && ds.summaryLine("₽").contains("₽999") && ds.summaryLine().contains("999"));
        check("placeholder apply with {sum}", Placeholders.apply("&6{user} &7закинул(а) &a{sum}&7!", dv).contains("закинул(а) &a500 ₽&7!"));
        check("non-donation event: empty source/currency", Placeholders.of(f1, "Steve", ds).get("source").isEmpty() && Placeholders.of(f1, "Steve", ds).get("currency").isEmpty());

        System.out.println("== v1.3: Migration v3 -> v4 ==");
        String v3cfg = "{\"configVersion\":3,\"clientId\":\"abc\",\"modules\":{\"overlay\":false},\"follow\":{\"message\":\"hi\"},\"goals\":[{\"name\":\"Мои\",\"type\":\"follows\",\"target\":5}]}";
        ModConfig m4 = ModConfig.fromJson(v3cfg);
        boolean up4 = m4.upgradeFrom(v3cfg);
        check("v3 upgraded to v4 (and on to v7)", up4 && m4.configVersion == 11 && m4.donations != null && m4.donationTiers.size() == 51 && m4.donationAlertsTiers.isEmpty());
        check("v3 user data preserved + donation goal appended", m4.clientId.equals("abc") && !m4.isEnabled(Module.OVERLAY) && m4.follow.message.startsWith("hi")
                && m4.goals.size() == 2 && m4.goals.get(0).name.equals("Мои") && "donationSum".equals(m4.goals.get(1).type));
        check("v4 output stable, no warnings", !ModConfig.fromJson(m4.toJson()).upgradeFrom(m4.toJson()) && ModConfig.findWarnings(m4.toJson()).isEmpty()
                && ModConfig.findWarnings(dc.toJson()).isEmpty());
        System.out.println("== v1.4: Migration v4 -> v5 ==");
        String v4cfg = "{\"configVersion\":4,\"clientId\":\"abc\",\"modules\":{\"goals\":false},\"chatCommands\":{\"mine\":{\"reply\":\"x\"}},\"donations\":{\"currency\":\"USD\"}}";
        ModConfig m5 = ModConfig.fromJson(v4cfg);
        boolean up5 = m5.upgradeFrom(v4cfg);
        check("v4 upgraded to v5: fundraisers + settings + fund command added, user data kept", up5 && m5.configVersion == 11 && m5.fundraisers.size() == 1
                && m5.fundraisers.get(0).name.equals("Сбор") && m5.fundraiserSettings != null && m5.chatCommands.containsKey("fund") && m5.chatCommands.containsKey("mine")
                && m5.clientId.equals("abc") && !m5.isEnabled(Module.GOALS) && m5.isEnabled(Module.FUNDRAISERS) && m5.donations.currency.equals("USD"));
        check("v5 output stable, no warnings", !ModConfig.fromJson(m5.toJson()).upgradeFrom(m5.toJson()) && ModConfig.findWarnings(m5.toJson()).isEmpty());
        ModConfig keepFund = ModConfig.fromJson("{\"configVersion\":4,\"fundraisers\":[{\"name\":\"Own\",\"target\":77}]}");
        keepFund.upgradeFrom("{\"configVersion\":4,\"fundraisers\":[{\"name\":\"Own\",\"target\":77}]}");
        check("v4 with own fundraisers: not overwritten by example", keepFund.fundraisers.size() == 1 && keepFund.fundraisers.get(0).name.equals("Own") && keepFund.fundraisers.get(0).target == 77);
        ModConfig badDon = ModConfig.fromJson("{\"configVersion\":4,\"donations\":{\"currency\":\" usd \",\"donatePayPollSeconds\":3,\"callbackPort\":80,\"minAmount\":-5}}");
        badDon.normalize();
        check("normalize donation settings", badDon.donations.currency.equals("USD") && badDon.donations.donatePayPollSeconds >= 20 && badDon.donations.callbackPort == 8631 && badDon.donations.minAmount == 0);

        System.out.println("== v1.5: Donation presets (25 bad + 25 good) ==");
        Map<String, ModConfig.Action> dp5 = DonationPresets.defaults();
        int dp5bad = 0, dp5good = 0, dp5other = 0;
        boolean dp5keysOk = true, dp5sorted = true, dp5content = true, dp5balanced = true, dp5roots = true, dp5blocked = false, dp5msg = true;
        Set<String> dp5seen = new HashSet<>();
        Set<String> dp5known = Set.of("give", "effect", "summon", "execute", "tp", "time", "weather", "xp", "particle", "spreadplayers", "item", "delay");
        int dp5prev = Integer.MIN_VALUE;
        for (Map.Entry<String, ModConfig.Action> e5 : dp5.entrySet()) {
            int key5;
            try {
                key5 = Integer.parseInt(e5.getKey());
            } catch (NumberFormatException ex) {
                dp5keysOk = false;
                continue;
            }
            if (!dp5seen.add(e5.getKey()) || key5 < 1 || key5 > 5000) dp5keysOk = false;
            if (key5 <= dp5prev) dp5sorted = false;
            dp5prev = key5;
            ModConfig.Action a5 = e5.getValue();
            if (DonationPresets.isBad(a5)) dp5bad++; else if (DonationPresets.isGood(a5)) dp5good++; else dp5other++;
            if (a5.name == null || a5.name.isBlank() || a5.message == null || !a5.message.contains("{user}") || !a5.message.contains("{sum}")
                    || (a5.title == null || a5.title.isBlank()) && (a5.toast == null || a5.toast.isBlank()) || a5.commands == null || a5.commands.isEmpty() || !a5.enabled || a5.cost != 0) dp5content = false;
            if (!DonationPresets.plainName(a5).equals(a5.name.replace(DonationPresets.BAD, "").replace(DonationPresets.GOOD, "").trim())) dp5msg = false;
            for (String c5 : a5.commands) {
                int br5 = 0, sq5 = 0;
                for (char ch5 : c5.toCharArray()) {
                    if (ch5 == '{') br5++;
                    if (ch5 == '}') br5--;
                    if (ch5 == '[') sq5++;
                    if (ch5 == ']') sq5--;
                    if (br5 < 0 || sq5 < 0) dp5balanced = false;
                }
                if (br5 != 0 || sq5 != 0 || c5.chars().filter(ch -> ch == '"').count() % 2 != 0 || c5.startsWith("/") || c5.contains("\n")) dp5balanced = false;
                if (!dp5known.contains(c5.split(" ")[0])) dp5roots = false;
                if (dc.isCommandBlocked(c5)) dp5blocked = true;
            }
        }
        check("preset: 51 tiers = 25 ☠ + 25 ★ + 1 neutral", dp5.size() == 51 && dp5bad == 25 && dp5good == 25 && dp5other == 1);
        check("preset: numeric distinct keys 1..5000, sorted ascending", dp5keysOk && dp5sorted && dp5.containsKey("30") && dp5.containsKey("5000") && dp5.containsKey("1"));
        check("preset: every action has name/title/commands, message with {user}+{sum}, enabled, no cost", dp5content && dp5msg);
        check("preset: commands balanced {}/[]/quotes, no leading slash", dp5balanced);
        check("preset: command roots ⊆ known set, none blocked by default blockedCommands", dp5roots && !dp5blocked);
        check("preset: bad/good interleave at the bottom (30 ☠, 40 ★, 50 ☠)", DonationPresets.isBad(dp5.get("30")) && DonationPresets.isGood(dp5.get("40")) && DonationPresets.isBad(dp5.get("50")));
        TwitchEvent dp5e29 = TwitchEvent.donation("test", "D", 29, "RUB", "", "a", true);
        TwitchEvent dp5e30 = TwitchEvent.donation("test", "D", 30, "RUB", "", "b", true);
        TwitchEvent dp5e45 = TwitchEvent.donation("test", "D", 45, "RUB", "", "c", true);
        TwitchEvent dp5e9999 = TwitchEvent.donation("test", "D", 9999, "RUB", "", "d", true);
        check("preset: resolution 29→1, 30→30, 45→40 (key donation:40), 9999→5000", dc.findAction(dp5e29).action() == dc.donationTiers.get("1")
                && dc.findAction(dp5e30).action() == dc.donationTiers.get("30") && dc.findAction(dp5e45).action() == dc.donationTiers.get("40")
                && dc.findAction(dp5e45).key().equals("donation:40") && dc.findAction(dp5e9999).action() == dc.donationTiers.get("5000"));
        check("preset: default config uses the preset (same keys/names)", dc.donationTiers.keySet().equals(dp5.keySet())
                && dc.donationTiers.get("4000").name.equals(dp5.get("4000").name) && !DonationPresets.isLegacyDefault(dc.donationTiers) && !DonationPresets.isLegacyDefault(Map.of()));
        Map<String, String> dp5ph = DonationPresets.placeholders(dc.donationTiers);
        check("preset: placeholders bad/good/all (fit one chat reply ≤ 500 with header)", dp5ph.get("donation_prices_bad").startsWith("30 Тыква") && dp5ph.get("donation_prices_bad").length() <= 450
                && dp5ph.get("donation_prices_good").startsWith("40 Перекус") && dp5ph.get("donation_prices_good").length() <= 450 && dp5ph.get("donation_prices_good").endsWith("5000 Легенда")
                && dp5ph.get("donation_prices").startsWith("1 Спасибо · 30 ☠ Тыква") && !dp5ph.get("donation_prices_bad").contains("☠"));
        check("preset: priceLines by kind", DonationPresets.priceLines(dc.donationTiers, "₽", 'b').size() == 25 && DonationPresets.priceLines(dc.donationTiers, "₽", 'g').size() == 25
                && DonationPresets.priceLines(dc.donationTiers, "₽", 'o').size() == 1 && DonationPresets.priceLines(dc.donationTiers, "₽", 'a').size() == 51
                && DonationPresets.priceLines(dc.donationTiers, "₽", 'b').get(0).contains("30 ₽") && DonationPresets.priceLines(null, "₽", 'a').isEmpty());
        ModConfig dp5disabled = ModConfig.createDefault();
        dp5disabled.donationTiers.get("30").enabled = false;
        check("preset: disabled tier is dropped from the viewer price list but marked in the in-game list",
                !DonationPresets.placeholders(dp5disabled.donationTiers).get("donation_prices_bad").contains("Тыква")
                && DonationPresets.priceLines(dp5disabled.donationTiers, "₽", 'b').get(0).contains("(выкл)"));
        check("preset: default chat commands ценник/плохое/хорошее with aliases", dc.findChatCommand("ценник") != null && dc.findChatCommand("prices").action() == dc.findChatCommand("ценник").action()
                && dc.findChatCommand("плохое") != null && dc.findChatCommand("bad").action() == dc.findChatCommand("плохое").action() && dc.findChatCommand("плохое").action().reply.contains("{donation_prices_bad}")
                && dc.findChatCommand("хорошее") != null && dc.findChatCommand("good").action() == dc.findChatCommand("хорошее").action() && dc.findChatCommand("хорошее").action().reply.contains("{donation_prices_good}")
                && dc.findChatCommand("ценник").key().equals("chat:ценник"));
        String dp5legacy = "{\"configVersion\":5,\"donationTiers\":{\"1\":{\"name\":\"Спасибо\"},\"50\":{\"name\":\"Перекус\"},\"100\":{\"name\":\"Салют\"},\"150\":{\"name\":\"Бу!\"},"
                + "\"200\":{\"name\":\"Ночь ужасов\"},\"300\":{\"name\":\"Зомби-волна\"},\"500\":{\"name\":\"Супер-сила\"},\"1000\":{\"name\":\"Сокровища\"},"
                + "\"2000\":{\"name\":\"В небо\"},\"5000\":{\"name\":\"Легенда\"}},\"chatCommands\":{\"mine\":{\"reply\":\"x\"}}}";
        ModConfig dp5m6 = ModConfig.fromJson(dp5legacy);
        boolean dp5up6 = dp5m6.upgradeFrom(dp5legacy);
        check("v5 → v6: untouched legacy table replaced by the 51-tier preset, chat commands added, user command kept", dp5up6 && dp5m6.configVersion == 11
                && dp5m6.donationTiers.size() == 51 && DonationPresets.isBad(dp5m6.donationTiers.get("30")) && dp5m6.chatCommands.containsKey("ценник")
                && dp5m6.chatCommands.containsKey("плохое") && dp5m6.chatCommands.containsKey("хорошее") && dp5m6.chatCommands.containsKey("mine"));
        check("v6 output stable, no warnings", !ModConfig.fromJson(dp5m6.toJson()).upgradeFrom(dp5m6.toJson()) && ModConfig.findWarnings(dp5m6.toJson()).isEmpty());
        String dp5custom = "{\"configVersion\":5,\"donationTiers\":{\"1\":{\"name\":\"Спасибо\"},\"100\":{\"name\":\"Моё\",\"commands\":[\"say hi\"]}}}";
        ModConfig dp5keep = ModConfig.fromJson(dp5custom);
        boolean dp5upKeep = dp5keep.upgradeFrom(dp5custom);
        check("v5 → v6: customised table is NOT overwritten (only version + chat commands)", dp5upKeep && dp5keep.configVersion == 11 && dp5keep.donationTiers.size() == 2
                && dp5keep.donationTiers.get("100").name.equals("Моё") && dp5keep.chatCommands.containsKey("ценник"));
        String dp5renamed = dp5legacy.replace("\"Салют\"", "\"Мой салют\"");
        ModConfig dp5keep2 = ModConfig.fromJson(dp5renamed);
        dp5keep2.upgradeFrom(dp5renamed);
        check("v5 → v6: legacy table with one renamed entry counts as customised", dp5keep2.donationTiers.size() == 10 && dp5keep2.donationTiers.get("100").name.equals("Мой салют"));
        ModConfig dp5v6src = ModConfig.createDefault();
        dp5v6src.donationTiers = new java.util.LinkedHashMap<>(Map.of("1", new ModConfig.Action("спасибо", "", "")));
        dp5v6src.chatCommands.remove("ценник");
        String dp5v6json = dp5v6src.toJson();
        ModConfig dp5v6 = ModConfig.fromJson(dp5v6json);
        check("v6 config with a tiny table and without !ценник: no upgrade, nothing added", !dp5v6.upgradeFrom(dp5v6json)
                && dp5v6.donationTiers.size() == 1 && !dp5v6.chatCommands.containsKey("ценник") && dp5v6.configVersion == 11);
        Map<String, String> dp5vars = Placeholders.of(dp5e30, "Steve");
        dp5vars.putAll(DonationPresets.placeholders(dc.donationTiers));
        dp5vars.put("donation_currency", "₽");
        check("preset: chat reply ☠ renders fully within 500 chars", Placeholders.apply(dc.findChatCommand("плохое").action().reply, dp5vars).length() <= 500
                && Placeholders.apply(dc.findChatCommand("плохое").action().reply, dp5vars).contains("4000 Иссушитель")
                && Placeholders.apply(dc.findChatCommand("хорошее").action().reply, dp5vars).length() <= 500
                && Placeholders.apply(dc.findChatCommand("ценник").action().reply, dp5vars).contains("₽") && !Placeholders.apply(dc.findChatCommand("ценник").action().reply, dp5vars).contains("{"));
        check("preset: {user} inside SNBT survives Placeholders.safe (quotes neutralised)",
                Placeholders.apply(dp5.get("1300").commands.get(0), Placeholders.of(TwitchEvent.donation("test", "Ev\"il}", 1300, "RUB", "", "z", true), "Steve")).contains("Босс Ev'il}")
                && !Placeholders.apply(dp5.get("1300").commands.get(0), Placeholders.of(TwitchEvent.donation("test", "Ev\"il}", 1300, "RUB", "", "z", true), "Steve")).contains("{user}"));

        System.out.println("== v1.3: DonationStore ==");
        try {
            DonationStore mem = new DonationStore();
            check("store: empty", !mem.hasDonationAlerts() && !mem.hasDonatePay() && !mem.dpWasSeen(1));
            mem.dpMarkSeen(5);
            mem.dpMarkSeen(5);
            check("store: seen dedup", mem.dpWasSeen(5) && mem.dpSeen.size() == 1);
            for (long i = 100; i < 1000; i++) mem.dpMarkSeen(i);
            check("store: seen capped", mem.dpSeen.size() <= 400 && mem.dpWasSeen(999) && !mem.dpWasSeen(5));
            mem.save(); // in-memory: no path, must not throw
            java.nio.file.Path storePath = java.nio.file.Files.createTempFile("twitchcraft-donations", ".json");
            java.nio.file.Files.delete(storePath);
            DonationStore st = DonationStore.load(storePath);
            st.daAccessToken = "tok"; st.daUserId = "42"; st.daUserName = "Streamer";
            st.dpApiKey = "key"; st.dpUserName = "DP"; st.dpCursor = 30; st.dpMarkSeen(30);
            st.save();
            DonationStore back2 = DonationStore.load(storePath);
            check("store: roundtrip", back2.hasDonationAlerts() && back2.hasDonatePay() && back2.daUserId.equals("42") && back2.dpCursor == 30 && back2.dpWasSeen(30) && back2.dpApiKey.equals("key"));
            back2.clearDonationAlerts();
            back2.clearDonatePay();
            check("store: clear", !back2.hasDonationAlerts() && !back2.hasDonatePay() && back2.dpCursor == 0 && !back2.dpWasSeen(30) && !DonationStore.load(storePath).hasDonatePay());
            check("store: missing file -> empty", !DonationStore.load(storePath.resolveSibling("nope-" + System.nanoTime() + ".json")).hasDonatePay());
            java.nio.file.Files.deleteIfExists(storePath);
        } catch (Exception e) {
            check("donation store tests threw " + e, false);
        }

        System.out.println("== v1.3: DonatePayClient.handle (cursor, dedup, pending, statuses) ==");
        try {
            List<TwitchEvent> received = new ArrayList<>();
            dev.dedworkshop.twitchcraft.TwitchCraftClient dpMod = new dev.dedworkshop.twitchcraft.TwitchCraftClient() {
                @Override
                public void onTwitchEvent(TwitchEvent event) {
                    received.add(event);
                }
            };
            java.lang.reflect.Field cf2 = dev.dedworkshop.twitchcraft.TwitchCraftClient.class.getDeclaredField("config");
            cf2.setAccessible(true);
            ModConfig dpCfg = ModConfig.createDefault();
            dpCfg.donations.minAmount = 10;
            cf2.set(dpMod, dpCfg);
            java.lang.reflect.Field sf = dev.dedworkshop.twitchcraft.TwitchCraftClient.class.getDeclaredField("donationStore");
            sf.setAccessible(true);
            DonationStore dpStore = new DonationStore();
            dpStore.dpApiKey = "k";
            dpStore.dpCursor = 9;
            sf.set(dpMod, dpStore);
            DonatePayClient dpc = new DonatePayClient(dpMod);
            dpc.handle(txs, dpStore);
            check("handle: success + test delivered, wait/cashout skipped, test is synthetic", received.size() == 2 && received.get(0).amount() == 100 && !received.get(0).synthetic()
                    && received.get(1).amount() == 150 && received.get(1).user().equals("Third"));
            check("handle: cursor advanced to max final id, pending not marked seen", dpStore.dpCursor == 30 && dpStore.dpWasSeen(10) && dpStore.dpWasSeen(30) && dpStore.dpWasSeen(27) && !dpStore.dpWasSeen(20));
            check("handle: test donation below minAmount skipped (5 < 10) but counted", dpc.donationsReceived() == 3);
            received.clear();
            dpc.handle(txs, dpStore);
            check("handle: same batch again -> nothing (dedup)", received.isEmpty() && dpStore.dpCursor == 30);
            List<DonationParser.DonatePayTx> later = new ArrayList<>(List.of(
                    new DonationParser.DonatePayTx(20, "success", "Second", "paid now", 99.99, "RUB"),
                    new DonationParser.DonatePayTx(31, "cancel", "Nope", "", 500, "RUB"),
                    new DonationParser.DonatePayTx(32, "success", "Fourth", "", 1000, "RUB")));
            dpc.handle(later, dpStore);
            check("handle: pending resolved later -> delivered once; cancel skipped", received.size() == 2 && received.get(0).rewardId().equals("20") && received.get(0).amount() == 99
                    && received.get(1).amount() == 1000 && dpStore.dpCursor == 32 && dpStore.dpWasSeen(31));
            check("handle: status text mentions key state", dpc.statusText().contains("DonatePay") || dpc.statusText().contains("отключено") || dpc.statusText().contains("ключ"));
        } catch (Exception e) {
            e.printStackTrace();
            check("DonatePayClient tests threw " + e, false);
        }

        System.out.println("== v1.3: LocalCallbackServer ==");
        try {
            int port;
            try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
                port = probe.getLocalPort();
            }
            java.util.concurrent.CompletableFuture<Map<String, String>> got = new java.util.concurrent.CompletableFuture<>();
            LocalCallbackServer cb = new LocalCallbackServer(port, "/da", got::complete);
            cb.start();
            java.net.http.HttpClient hc = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpResponse<String> landing = hc.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/da")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            check("callback: landing page forwards hash", landing.statusCode() == 200 && landing.body().contains("location.hash") && landing.body().contains("/da/token?")
                    && landing.headers().firstValue("content-type").orElse("").contains("utf-8"));
            java.net.http.HttpResponse<String> missing = hc.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/da/token?foo=bar")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            check("callback: token missing -> 400, server still alive", missing.statusCode() == 400 && cb.isRunning() && !got.isDone());
            java.net.http.HttpResponse<String> ok = hc.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/da/token?access_token=abc%20def&token_type=bearer&expires_in=3600")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            Map<String, String> params = got.get(5, java.util.concurrent.TimeUnit.SECONDS);
            check("callback: token delivered (url-decoded) and page says done", ok.statusCode() == 200 && ok.body().contains("Готово") && params.get("access_token").equals("abc def")
                    && params.get("expires_in").equals("3600"));
            Thread.sleep(200);
            check("callback: server closed after token", !cb.isRunning());
            check("parseQuery", LocalCallbackServer.class.getDeclaredMethods().length > 0);
        } catch (Exception e) {
            e.printStackTrace();
            check("LocalCallbackServer tests threw " + e, false);
        }

        System.out.println("== v1.3.1 (аудит): чёрный список и цепочки execute/run ==");
        {
            ModConfig bc = ModConfig.createDefault();
            check("blocked: execute ... run op", bc.isCommandBlocked("execute as @a run op @s") && bc.isCommandBlocked("/execute as @a at @s run minecraft:op @s"));
            check("blocked: nested execute chains and return run", bc.isCommandBlocked("execute run execute run stop") && bc.isCommandBlocked("return run /ban Steve"));
            check("not blocked: execute ... run summon / tellraw with word run in quotes", !bc.isCommandBlocked("execute as @a run summon zombie")
                    && !bc.isCommandBlocked("tellraw @a \"run\"") && !bc.isCommandBlocked("summon zombie"));
            check("blocked list unchanged: stop, op, ban, whitelist still blocked at root", bc.isCommandBlocked("stop") && bc.isCommandBlocked("op x"));
        }

        System.out.println("== v1.3.1 (аудит): {session_*} учитывают текущее событие ==");
        {
            SessionStats st = new SessionStats();
            TwitchEvent c1 = TwitchEvent.simple(TwitchEvent.Type.CHEER, "A", "a", 300, "", "", "");
            TwitchEvent f1x = TwitchEvent.simple(TwitchEvent.Type.FOLLOW, "B", "b", 0, "", "", "");
            st.record(c1, "ok");
            st.record(f1x, "ok");
            TwitchEvent f2 = TwitchEvent.simple(TwitchEvent.Type.FOLLOW, "C", "c", 0, "", "", "");
            Map<String, String> pending = Placeholders.forPending(f2, "Steve", st);
            Map<String, String> plain = Placeholders.of(f2, "Steve", st);
            check("forPending: follows 2, events 3; of(): follows 1, events 2", pending.get("session_follows").equals("2") && pending.get("session_events").equals("3")
                    && plain.get("session_follows").equals("1") && plain.get("session_events").equals("2") && pending.get("session_bits").equals("300"));
            TwitchEvent c2 = TwitchEvent.simple(TwitchEvent.Type.CHEER, "D", "d", 700, "", "", "");
            check("forPending: bits add amount", Placeholders.forPending(c2, "Steve", st).get("session_bits").equals("1000"));
            TwitchEvent synthetic = TwitchEvent.test(TwitchEvent.Type.FOLLOW, "T", 0, "", "", "");
            check("forPending: synthetic events not counted", Placeholders.forPending(synthetic, "Steve", st).get("session_follows").equals("1"));
            TwitchEvent goalEv = TwitchEvent.goal("Цель", 5, 1, "C", "c", false);
            Map<String, String> goalVars = Placeholders.forPending(goalEv, "Steve", st);
            check("forPending: goal counts as goal, not as event", goalVars.get("session_goals").equals("1") && goalVars.get("session_events").equals("2"));
            check("stats untouched by forPending", st.follows == 1 && st.events == 2);
        }

        System.out.println("== v1.3.1 (аудит): атомарная запись файлов ==");
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("twitchcraft-test");
            java.nio.file.Path file = dir.resolve("cfg.json");
            dev.dedworkshop.twitchcraft.util.SafeFiles.writeAtomic(file, "{\"a\":1}");
            dev.dedworkshop.twitchcraft.util.SafeFiles.writeAtomic(file, "{\"a\":2}");
            check("writeAtomic: content replaced, no .tmp left", java.nio.file.Files.readString(file).equals("{\"a\":2}")
                    && !java.nio.file.Files.exists(dir.resolve("cfg.json.tmp")));
            java.nio.file.Path backup = dev.dedworkshop.twitchcraft.util.SafeFiles.backupBroken(file);
            check("backupBroken: copy created next to the file", backup != null && java.nio.file.Files.exists(backup)
                    && backup.getFileName().toString().startsWith("cfg.json.broken-") && java.nio.file.Files.readString(backup).equals("{\"a\":2}"));
            check("backupBroken: missing file -> null", dev.dedworkshop.twitchcraft.util.SafeFiles.backupBroken(dir.resolve("nope.json")) == null);
            java.nio.file.Path nested = dir.resolve("sub/dir/x.json");
            dev.dedworkshop.twitchcraft.util.SafeFiles.writeAtomic(nested, "x");
            check("writeAtomic: creates parent directories", java.nio.file.Files.readString(nested).equals("x"));
        } catch (Exception e) {
            e.printStackTrace();
            check("SafeFiles tests threw " + e, false);
        }

        System.out.println("== v1.3.1 (аудит): Chat.lastText без префикса и цветов ==");
        {
            net.minecraft.client.Minecraft.INSTANCE = null;
            dev.dedworkshop.twitchcraft.util.Chat.info("Привет, &aмир");
            check("lastText stripped of [Twitch] prefix and § codes", dev.dedworkshop.twitchcraft.util.Chat.lastText().equals("Привет, мир"));
            dev.dedworkshop.twitchcraft.util.Chat.error("Ошибка 401");
            check("lastText error", dev.dedworkshop.twitchcraft.util.Chat.lastText().equals("Ошибка 401"));
        }

        System.out.println("== v1.3.2 (аудит 2): Shared Chat — сообщения из чата канала-партнёра ==");
        {
            TwitchEvent shared = TwitchEvent.fromEventSub("channel.chat.message", j("""
                {"broadcaster_user_id":"2","source_broadcaster_user_id":"999","source_broadcaster_user_login":"partner",
                 "chatter_user_id":"7","chatter_user_login":"guest","chatter_user_name":"Guest",
                 "message":{"text":"!zombie"},"badges":[{"set_id":"moderator","id":"1","info":""}],"color":"","source_badges":[]}"""));
            check("shared chat parsed: isShared + sharedFrom=partner", shared != null && shared.isShared() && shared.sharedFrom().equals("partner")
                    && shared.permission() == TwitchEvent.Permission.MODERATOR);
            check("shared flag survives asCommand/asSynthetic/withoutMessage", shared.asCommand("zombie", "").isShared()
                    && shared.asSynthetic().sharedFrom().equals("partner") && shared.withoutMessage().isShared());
            TwitchEvent own = TwitchEvent.fromEventSub("channel.chat.message", j("""
                {"broadcaster_user_id":"2","source_broadcaster_user_id":"2","chatter_user_id":"7","chatter_user_login":"x","chatter_user_name":"X",
                 "message":{"text":"hi"},"badges":[]}"""));
            TwitchEvent plain = TwitchEvent.fromEventSub("channel.chat.message", j("""
                {"broadcaster_user_id":"2","source_broadcaster_user_id":null,"chatter_user_id":"7","chatter_user_login":"x","chatter_user_name":"X",
                 "message":{"text":"hi"},"badges":[]}"""));
            TwitchEvent noId = TwitchEvent.fromEventSub("channel.chat.message", j("""
                {"broadcaster_user_id":"2","source_broadcaster_user_id":"999","chatter_user_id":"7","chatter_user_login":"x","chatter_user_name":"X",
                 "message":{"text":"hi"},"badges":[]}"""));
            check("own channel / null source -> not shared; missing login -> id used", own != null && !own.isShared() && plain != null && !plain.isShared()
                    && noId != null && noId.sharedFrom().equals("999"));
            TwitchEvent legacy = new TwitchEvent(TwitchEvent.Type.CHAT, "A", "a", "1", 0, "", "", "", "", "", Set.of(), "", "", false);
            check("14-arg constructor -> sharedFrom empty, null normalised", legacy.sharedFrom().equals("") && !legacy.isShared()
                    && new TwitchEvent(TwitchEvent.Type.CHAT, "A", "a", "1", 0, "", "", "", "", "", Set.of(), "", "", false, null).sharedFrom().equals(""));
            ModConfig sc = ModConfig.createDefault();
            check("config defaults: show shared chat, shared commands off", sc.twitchChat.showSharedChat && !sc.twitchChat.sharedChatCommands);
            sc.twitchChat.sharedChatCommands = true;
            sc.twitchChat.showSharedChat = false;
            ModConfig scBack = ModConfig.fromJson(sc.toJson());
            check("shared chat settings survive JSON round-trip", scBack.twitchChat.sharedChatCommands && !scBack.twitchChat.showSharedChat
                    && ModConfig.findWarnings(sc.toJson()).isEmpty());
            try {
                String rendered = TwitchChatRenderer.build(ModConfig.createDefault(), shared).getString();
                check("renderer marks shared messages with [partner]", rendered.contains("[partner] ") && rendered.contains("Guest"));
            } catch (Throwable t) {
                check("renderer test threw " + t, false);
            }
        }

        System.out.println("== v1.3.2 (аудит 2): не больше " + GoalTracker.MAX_COMPLETIONS_PER_EVENT + " срабатываний цели от одного события ==");
        try {
            dev.dedworkshop.twitchcraft.TwitchCraftClient fakeMod = new dev.dedworkshop.twitchcraft.TwitchCraftClient();
            java.lang.reflect.Field cf = dev.dedworkshop.twitchcraft.TwitchCraftClient.class.getDeclaredField("config");
            cf.setAccessible(true);
            ModConfig gc = ModConfig.createDefault();
            gc.showEventsInChat = false;
            gc.goals.clear();
            gc.goals.add(new ModConfig.Goal("V", "raidViewers", 10, true, new ModConfig.Action()));
            gc.goals.add(new ModConfig.Goal("Once", "bits", 10, false, new ModConfig.Action()));
            cf.set(fakeMod, gc);
            java.nio.file.Path goalsPath = java.nio.file.Files.createTempFile("twitchcraft-goals2", ".json");
            java.nio.file.Files.delete(goalsPath);
            GoalTracker tracker = new GoalTracker(fakeMod, Runnable::run, goalsPath);
            List<TwitchEvent> storm = tracker.onEvent(ev(TwitchEvent.Type.RAID, 1000, ""));
            check("raid 1000 / every 10 -> capped at 5 fires, remainder kept", storm.size() == GoalTracker.MAX_COMPLETIONS_PER_EVENT
                    && tracker.peek("V").completed == 5 && tracker.peek("V").count == 950);
            List<TwitchEvent> next = tracker.onEvent(ev(TwitchEvent.Type.RAID, 3, ""));
            check("next event keeps catching up (5 more)", next.size() == 5 && tracker.peek("V").completed == 10 && tracker.peek("V").count == 903);
            check("one-shot goal unaffected by cap", tracker.onEvent(ev(TwitchEvent.Type.CHEER, 100000, "")).size() == 1 && tracker.peek("Once").done);
            tracker.reset(null);
            check("manual add capped too", tracker.add(gc.goals.get(0), 1000).size() == 5 && tracker.peek("V").count == 950);
            java.nio.file.Files.deleteIfExists(goalsPath);
        } catch (Exception e) {
            e.printStackTrace();
            check("goal cap tests threw " + e, false);
        }

        System.out.println("== v1.3.2 (аудит 2): ротация журнала событий и права файлов секретов ==");
        try {
            dev.dedworkshop.twitchcraft.action.EventLog log = new dev.dedworkshop.twitchcraft.action.EventLog(Runnable::run);
            java.nio.file.Path logPath = log.path();
            java.nio.file.Path rotated = logPath.resolveSibling(logPath.getFileName() + ".1");
            java.nio.file.Files.createDirectories(logPath.getParent());
            java.nio.file.Files.deleteIfExists(rotated);
            byte[] big = new byte[5 * 1024 * 1024 + 1024];
            java.util.Arrays.fill(big, (byte) 'x');
            java.nio.file.Files.write(logPath, big);
            log.log(TwitchEvent.simple(TwitchEvent.Type.FOLLOW, "Rot", "rot", 0, "", "", ""), "ok");
            check("event log rotated to .1 when > 5 MB", java.nio.file.Files.exists(rotated) && java.nio.file.Files.size(rotated) == big.length
                    && java.nio.file.Files.size(logPath) < 1000 && java.nio.file.Files.readString(logPath).contains("FOLLOW | Rot"));
            log.log(TwitchEvent.simple(TwitchEvent.Type.FOLLOW, "Two", "two", 0, "", "", ""), "ok");
            check("no rotation while small", java.nio.file.Files.readString(logPath).contains("Rot") && java.nio.file.Files.readString(logPath).contains("Two"));
            java.nio.file.Files.deleteIfExists(logPath);
            java.nio.file.Files.deleteIfExists(rotated);
            try { java.nio.file.Files.deleteIfExists(logPath.getParent()); } catch (java.io.IOException ignored) { }

            java.nio.file.Path secret = java.nio.file.Files.createTempFile("twitchcraft-secret", ".json");
            dev.dedworkshop.twitchcraft.util.SafeFiles.writeAtomic(secret, "{\"token\":1}", true);
            boolean posix = secret.getFileSystem().supportedFileAttributeViews().contains("posix");
            check("private file: content written" + (posix ? ", perms rw-------" : ""), java.nio.file.Files.readString(secret).equals("{\"token\":1}")
                    && (!posix || java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(secret)).equals("rw-------")));
            java.nio.file.Files.deleteIfExists(secret);
        } catch (Exception e) {
            e.printStackTrace();
            check("event log / private file tests threw " + e, false);
        }

        System.out.println("== v1.3.2 (аудит 2): LocalCallbackServer — state и Sec-Fetch-Site ==");
        try {
            String st = LocalCallbackServer.newState();
            check("newState: url-safe, ≥20 chars, unique", st.matches("[A-Za-z0-9_-]{20,}") && !st.equals(LocalCallbackServer.newState()));
            List<Map<String, String>> got = new ArrayList<>();
            LocalCallbackServer srv = new LocalCallbackServer(8637, "/da", st, got::add);
            srv.start();
            java.net.http.HttpClient hc = java.net.http.HttpClient.newHttpClient();
            java.util.function.BiFunction<String, String[], Integer> call = (url, hdr) -> {
                try {
                    java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url));
                    for (int i = 0; i + 1 < hdr.length; i += 2) b.header(hdr[i], hdr[i + 1]);
                    return hc.send(b.build(), java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode();
                } catch (Exception e) {
                    return -1;
                }
            };
            int crossSite = call.apply("http://127.0.0.1:8637/da/token?access_token=evil&state=" + st, new String[]{"Sec-Fetch-Site", "cross-site"});
            int badState = call.apply("http://127.0.0.1:8637/da/token?access_token=evil&state=nope", new String[]{});
            int noToken = call.apply("http://127.0.0.1:8637/da/token?error=access_denied", new String[]{});
            check("cross-site -> 403, bad state -> 400, no token -> 400; nothing accepted", crossSite == 403 && badState == 400 && noToken == 400
                    && got.isEmpty() && srv.isRunning());
            int ok = call.apply("http://127.0.0.1:8637/da/token?access_token=good&state=" + st, new String[]{"Sec-Fetch-Site", "same-origin"});
            check("same-origin with right state -> 200, token delivered once, server closed", ok == 200 && got.size() == 1
                    && got.get(0).get("access_token").equals("good") && !srv.isRunning());
            srv.close();
        } catch (Exception e) {
            e.printStackTrace();
            check("callback server tests threw " + e, false);
        }

        System.out.println("== v1.6: две случайные награды (pool) ==");
        {
            ModConfig p6 = ModConfig.createDefault();
            check("defaults: only Пакость + Подарок (+ fallback *), 250 points each",
                    p6.rewards.size() == 3 && p6.rewards.containsKey(DonationPresets.REWARD_BAD) && p6.rewards.containsKey(DonationPresets.REWARD_GOOD)
                    && p6.rewards.containsKey("*") && p6.rewards.get("Пакость").cost == 250 && p6.rewards.get("Подарок").cost == 250
                    && p6.rewards.get("Пакость").pool.equals("bad") && p6.rewards.get("Подарок").pool.equals("good")
                    && p6.rewards.keySet().iterator().next().equals("Пакость"));
            List<ModConfig.Action> p6bad = DonationPresets.poolCandidates(p6.donationTiers, "bad");
            List<ModConfig.Action> p6good = DonationPresets.poolCandidates(p6.donationTiers, "good");
            check("pool candidates: 25 ☠, 25 ★, 50 any, 0 unknown", p6bad.size() == 25 && p6good.size() == 25
                    && DonationPresets.poolCandidates(p6.donationTiers, "any").size() == 50 && DonationPresets.poolCandidates(p6.donationTiers, "wat").isEmpty()
                    && p6bad.stream().allMatch(DonationPresets::isBad) && p6good.stream().allMatch(DonationPresets::isGood));
            p6.donationTiers.get("30").enabled = false;
            check("disabled tier is skipped", DonationPresets.poolCandidates(p6.donationTiers, "bad").size() == 24);
            p6.donationTiers.get("30").enabled = true;
            java.util.Map<String, Integer> p6hist = new java.util.HashMap<>();
            java.util.Random p6rnd = new java.util.Random(42);
            for (int i = 0; i < 25_000; i++) {
                p6hist.merge(DonationPresets.plainName(DonationPresets.pick(p6.donationTiers, "bad", p6rnd)), 1, Integer::sum);
            }
            int p6min = p6hist.values().stream().mapToInt(Integer::intValue).min().orElse(0);
            int p6max = p6hist.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            check("pick is uniform over all 25 (25 000 draws: min " + p6min + ", max " + p6max + ")", p6hist.size() == 25 && p6min > 800 && p6max < 1200);
            check("pick from empty pool -> null", DonationPresets.pick(new java.util.LinkedHashMap<>(), "bad", p6rnd) == null);
            ModConfig.Action p6pumpkin = p6.donationTiers.get("30");
            String p6flavor = DonationPresets.flavor(p6pumpkin);
            check("flavor strips «{user} задонатил(а) {sum} — » and {message}: \"" + p6flavor + "\"", !p6flavor.isEmpty() && !p6flavor.contains("{sum}")
                    && !p6flavor.contains("задонатил") && !p6flavor.contains("{message}"));
            ModConfig.Action p6pick = DonationPresets.asPoolPick(p6pumpkin);
            check("asPoolPick: commands/title/sound kept, texts cleared, chance 100, no cooldown", p6pick != p6pumpkin
                    && p6pick.commands.equals(p6pumpkin.commands) && p6pick.title.equals(p6pumpkin.title) && p6pick.message.isEmpty()
                    && p6pick.reply.isEmpty() && p6pick.chance == 100 && p6pick.cooldown == 0 && p6pumpkin.message.length() > 0);
            check("pool normalised: BAD/junk", normalisedPool(" BAD ").equals("bad") && normalisedPool("junk").isEmpty() && normalisedPool(null).isEmpty());

            // Полный прогон через ActionRunner: награда «Пакость» выполняет команды выпавшей записи, {picked} подставлен в ответ
            net.minecraft.client.Minecraft p6mc = new net.minecraft.client.Minecraft();
            p6mc.player = new net.minecraft.client.player.LocalPlayer();
            net.minecraft.client.Minecraft.INSTANCE = p6mc;
            List<String> p6replies = new ArrayList<>();
            dev.dedworkshop.twitchcraft.TwitchCraftClient p6mod = new dev.dedworkshop.twitchcraft.TwitchCraftClient() {
                @Override public ModConfig config() { return p6; }
                @Override public void reply(TwitchEvent event, String text) { p6replies.add(text); }
            };
            List<String> p6exec = new ArrayList<>();
            ActionRunner p6runner = new ActionRunner(p6mod) {
                @Override
                protected boolean runCommand(String command) {
                    p6exec.add(command);
                    return true;
                }
            };
            TwitchEvent p6ev = TwitchEvent.test(TwitchEvent.Type.REWARD, "Viewer", 250, "", "Пакость", "");
            int[] p6done = {0};
            p6runner.run(p6ev, p6.rewards.get("Пакость"), Placeholders.of(p6ev, "Steve", new SessionStats()), () -> p6done[0]++);
            for (int t = 0; t < 400 && p6done[0] == 0; t++) {
                p6runner.tick(p6mc);
            }
            Set<String> p6allBad = new HashSet<>();
            Map<String, String> p6vars = Placeholders.of(p6ev, "Steve", new SessionStats());
            for (ModConfig.Action a : p6bad) {
                p6allBad.addAll(a.commands);
            }
            // Команды из ценника содержат {user}/{sum}/… — ActionRunner подставляет их перед выполнением,
            // поэтому сравниваем и сырой вид, и с подстановкой, и по началу строки до первого плейсхолдера
            // (иначе тест «мигает» в зависимости от того, какая запись выпала).
            java.util.function.Predicate<String> p6known = c -> p6allBad.stream().anyMatch(raw ->
                    raw.equals(c) || Placeholders.apply(raw, p6vars).equals(c)
                            || (raw.indexOf('{') > 0 && c.startsWith(raw.substring(0, raw.indexOf('{')))));
            List<String> p6unknown = p6exec.stream().filter(c -> !c.startsWith("delay")).filter(c -> !p6known.test(c)).toList();
            check("runner: reward ran the picked ☠ entry's commands (" + p6exec.size() + " cmds"
                    + (p6unknown.isEmpty() ? "" : ", лишние: " + p6unknown) + ")", p6done[0] == 1 && !p6exec.isEmpty()
                    && p6unknown.isEmpty());
            check("runner: reply has the picked name, no raw placeholders: " + p6replies, p6replies.size() == 1 && p6replies.get(0).startsWith("Viewer, выпало: ☠ ")
                    && !p6replies.get(0).contains("{picked}") && p6replies.get(0).length() > "Viewer, выпало: ☠ ".length());
            check("Placeholders: {picked}/{picked_text} default to empty for ordinary events",
                    Placeholders.apply("[{picked}|{picked_text}]", Placeholders.of(p6ev, "Steve", new SessionStats())).equals("[|]")
                    || Placeholders.apply("[{picked}|{picked_text}]", Placeholders.of(p6ev, "Steve", new SessionStats())).equals("[{picked}|{picked_text}]"));
        }

        System.out.println("== v1.6: migration v6 -> v7 (rewards) ==");
        {
            ModConfig m7src = ModConfig.createDefault();
            m7src.configVersion = 6;
            m7src.rewards = new java.util.LinkedHashMap<>();
            for (String t : List.of("Зомби", "Крипер", "Молния", "Лечение", "Голод", "Прыгучесть", "Ночь", "Телепорт", "Алмаз", "Сюрприз", "Лотерея", "Сообщение", "*")) {
                m7src.rewards.put(t, new ModConfig.Action("x", "", "", "say " + t));
            }
            String m7json = m7src.toJson().replace("\"vk\":", "\"vkOld\":"); // как будто секции vk ещё не было
            ModConfig m7 = ModConfig.fromJson(m7json);
            boolean m7up = m7.upgradeFrom(m7json);
            check("v6 → v11: untouched example rewards replaced by Пакость/Подарок/*; vk section added; version 11", m7up && m7.configVersion == 11
                    && m7.rewards.size() == 3 && m7.rewards.containsKey("Пакость") && m7.rewards.containsKey("Подарок") && m7.rewards.containsKey("*")
                    && m7.vk != null && m7.vk.callbackPort > 0);
            ModConfig m7own = ModConfig.createDefault();
            m7own.configVersion = 6;
            m7own.rewards = new java.util.LinkedHashMap<>();
            m7own.rewards.put("Моя", new ModConfig.Action("x", "", "", "say my"));
            m7own.rewards.put("пакость", new ModConfig.Action("y", "", "", "say custom bad"));
            String m7ownJson = m7own.toJson();
            ModConfig m7kept = ModConfig.fromJson(m7ownJson);
            boolean m7keptUp = m7kept.upgradeFrom(m7ownJson);
            check("v6 → v7: user's rewards kept, only missing «Подарок» added (case-insensitive match for «пакость»)", m7keptUp && m7kept.rewards.size() == 3
                    && m7kept.rewards.get("Моя").commands.get(0).equals("say my") && m7kept.rewards.get("пакость").commands.get(0).equals("say custom bad")
                    && m7kept.rewards.containsKey("Подарок") && !m7kept.rewards.containsKey("Пакость"));
            String m7stable = m7kept.toJson();
            check("v7 output stable", !ModConfig.fromJson(m7stable).upgradeFrom(m7stable) && ModConfig.findWarnings(m7stable).isEmpty());
        }

        System.out.println("== v1.6: VK Video Live — slug, platform, events ==");
        {
            check("slugOf: url / trailing slash / plain / query / vkplay", ModConfig.Vk.slugOf("https://live.vkvideo.ru/dedworkshop").equals("dedworkshop")
                    && ModConfig.Vk.slugOf("https://live.vkvideo.ru/dedworkshop/").equals("dedworkshop") && ModConfig.Vk.slugOf("  DedWorkshop ").equals("dedworkshop")
                    && ModConfig.Vk.slugOf("live.vkvideo.ru/dedworkshop?utm=1").equals("dedworkshop") && ModConfig.Vk.slugOf("https://vkplay.live/dedworkshop/records").equals("dedworkshop")
                    && ModConfig.Vk.slugOf("").isEmpty() && ModConfig.Vk.slugOf(null).isEmpty());
            TwitchEvent vkT = TwitchEvent.test(TwitchEvent.Type.FOLLOW, "Вася", 0, "", "", "").withPlatform(TwitchEvent.PLATFORM_VK);
            check("withPlatform: isVk, platformTitle, module routing, synthetic kept", vkT.isVk() && vkT.platformTitle().contains("VK") && vkT.synthetic()
                    && Module.forEvent(vkT) == Module.VK_VIDEO_LIVE && !TwitchEvent.test(TwitchEvent.Type.FOLLOW, "A", 0, "", "", "").isVk()
                    && Module.forEvent(TwitchEvent.test(TwitchEvent.Type.FOLLOW, "A", 0, "", "", "")) == Module.FOLLOWS);
            check("VK module is a platform module, enabled by default", Module.VK_VIDEO_LIVE.isPlatform() && ModConfig.createDefault().isEnabled(Module.VK_VIDEO_LIVE)
                    && Module.VK_VIDEO_LIVE.id.equals("vkVideoLive"));
            check("{platform} placeholder", Placeholders.of(vkT, "Steve").get("platform").equals("VK Video Live")
                    && Placeholders.of(TwitchEvent.test(TwitchEvent.Type.FOLLOW, "A", 0, "", "", ""), "Steve").get("platform").equals("Twitch")
                    && vkT.describe().startsWith("§9[VK]"));

            VkEvents.Parsed vkMsg = VkEvents.parse(j("""
                {"type":"message","data":{"id":101,"createdAt":1700000000,"isPrivate":false,
                 "author":{"id":55,"nick":"vasya","displayName":"Вася","nickColor":3,"isOwner":false,"isChatModerator":true,"roles":[{"name":"Модератор"}],"badges":[]},
                 "data":[{"type":"text","content":"[\\"!zombie сейчас\\",\\"unstyled\\",[]]","modificator":""},{"type":"mention","nick":"dedworkshop","displayName":"DedWorkshop"},
                         {"type":"smile","name":"pepe"},{"type":"link","content":"https://a.b","url":"https://a.b"}]}}"""));
            check("VK chat message: user, draft-json text decoded, mention/smile/link rendered, moderator, platform",
                    vkMsg.event() != null && vkMsg.event().type() == TwitchEvent.Type.CHAT && vkMsg.event().user().equals("Вася") && vkMsg.event().userLogin().equals("vasya")
                    && vkMsg.event().message().startsWith("!zombie сейчас") && vkMsg.event().message().contains("@DedWorkshop") && vkMsg.event().message().contains(":pepe:")
                    && vkMsg.event().message().contains("https://a.b") && vkMsg.event().permission() == TwitchEvent.Permission.MODERATOR && vkMsg.event().isVk()
                    && vkMsg.event().userId().equals("55") && !vkMsg.event().synthetic());
            check("decodeContent: plain text passes through, bad json passes through", VkEvents.decodeContent("просто текст").equals("просто текст")
                    && VkEvents.decodeContent("[\"a\",\"unstyled\",[]]").equals("a") && VkEvents.decodeContent("[broken").equals("[broken"));
            VkEvents.Parsed vkOwner = VkEvents.parse(j("""
                {"type":"message","data":{"id":"102","author":{"id":1,"nick":"dedworkshop","isOwner":true,"roles":[],"badges":[]},"data":[{"type":"text","content":"hi"}]}}"""));
            check("VK owner -> BROADCASTER permission", vkOwner.event() != null && vkOwner.event().permission() == TwitchEvent.Permission.BROADCASTER
                    && vkOwner.event().message().equals("hi"));

            VkEvents.Parsed vkDemand = VkEvents.parse(j("""
                {"type":"cp_reward_demand","data":{"demandId":777,"status":"pending","createdAt":1700000001,
                 "reward":{"id":"8d1c-uuid","name":"Пакость","price":250,"isAutoapproved":false,"isTextRequired":true},
                 "user":{"id":55,"nick":"vasya","displayName":"Вася"},
                 "activationMessage":[{"type":"text","content":"привет §cстример"}]}}"""));
            check("VK reward demand: REWARD event, title/cost/ids/text(clean)/status pending",
                    vkDemand.event() != null && vkDemand.event().type() == TwitchEvent.Type.REWARD && vkDemand.event().reward().equals("Пакость")
                    && vkDemand.event().amount() == 250 && vkDemand.event().rewardId().equals("8d1c-uuid") && vkDemand.event().redemptionId().equals("777")
                    && vkDemand.event().user().equals("Вася") && vkDemand.event().userId().equals("55") && !vkDemand.event().message().contains("§") && vkDemand.event().message().startsWith("привет")
                    && vkDemand.demandStatus().equals("pending") && vkDemand.event().isVk() && vkDemand.event().actionKey().equals("reward:пакость"));
            VkEvents.Parsed vkJournalFollow = VkEvents.parse(j("""
                {"type":"actions_journal_new_event","data":{"type":"following","action_time":1700000002,"follower":{"id":9,"nick":"newbie","displayName":"Новичок"}}}"""));
            check("VK journal following -> FOLLOW", vkJournalFollow.event() != null && vkJournalFollow.event().type() == TwitchEvent.Type.FOLLOW
                    && vkJournalFollow.event().user().equals("Новичок") && vkJournalFollow.event().isVk() && vkJournalFollow.type().contains("following"));
            VkEvents.Parsed vkJournalDemand = VkEvents.parse(j("""
                {"type":"actions_journal_new_event","data":{"type":"reward_demand","reward_demand":{"id":778,"status":"approved","reward":{"id":"u2","name":"Подарок","price":250},
                 "user":{"id":56,"nick":"petya"},"message_parts":[{"text":{"content":"go"}}]}}}"""));
            check("VK journal reward_demand (snake_case, REST-style parts) -> REWARD approved", vkJournalDemand.event() != null
                    && vkJournalDemand.event().type() == TwitchEvent.Type.REWARD && vkJournalDemand.event().reward().equals("Подарок")
                    && vkJournalDemand.event().redemptionId().equals("778") && vkJournalDemand.demandStatus().equals("approved")
                    && vkJournalDemand.event().user().equals("petya") && vkJournalDemand.event().message().equals("go"));
            VkEvents.Parsed vkStatus = VkEvents.parse(j("{\"type\":\"stream_online_status\",\"data\":{\"isOnline\":true,\"viewers\":12,\"streamId\":\"s-1\"}}"));
            VkEvents.Parsed vkEnd = VkEvents.parse(j("{\"type\":\"stream_end\",\"data\":{}}"));
            VkEvents.Parsed vkUnknown = VkEvents.parse(j("{\"type\":\"viewers_count\",\"data\":{\"count\":5}}"));
            check("VK stream status / end / unknown", vkStatus.event() == null && Boolean.TRUE.equals(vkStatus.online()) && vkStatus.streamId().equals("s-1")
                    && Boolean.FALSE.equals(vkEnd.online()) && vkUnknown.event() == null && vkUnknown.type().equals("viewers_count")
                    && VkEvents.parse(null).event() == null);
            check("partsText: REST shapes (text/mention/smile/link) and plain string", VkEvents.partsText(j("{\"p\":[{\"text\":{\"content\":\"a\"}},{\"mention\":{\"nick\":\"b\"}},{\"smile\":{\"name\":\"c\"}},{\"link\":{\"url\":\"http://d\"}}]}").get("p"))
                    .replace("  ", " ").trim().equals("a @b :c: http://d")
                    && VkEvents.partsText(new com.google.gson.JsonPrimitive("plain")).equals("plain") && VkEvents.partsText(null).isEmpty());

            // findAction для VK-награды — тот же ключ reward:<title>, что и у Twitch
            ModConfig vkCfg = ModConfig.createDefault();
            check("VK demand resolves to the same reward action as Twitch", vkCfg.findAction(vkDemand.event()).action() == vkCfg.rewards.get("Пакость")
                    && vkCfg.findAction(vkDemand.event()).key().equals("reward:пакость"));
            TwitchEvent vkTest = dev.dedworkshop.twitchcraft.vk.VkLive.testEvent(TwitchEvent.Type.REWARD, "Tester", 250, "msg", "Подарок");
            check("VkLive.testEvent: synthetic VK reward", vkTest.synthetic() && vkTest.isVk() && vkTest.reward().equals("Подарок") && vkTest.amount() == 250
                    && vkCfg.findAction(vkTest).action() == vkCfg.rewards.get("Подарок"));
        }

        System.out.println("== v1.7: GameStats — counters, totals, placeholders, formatDuration ==");
        try {
            java.nio.file.Path gsPath = java.nio.file.Files.createTempDirectory("tc-stats").resolve("twitchcraft-stats.json");
            dev.dedworkshop.twitchcraft.game.GameStats gs = dev.dedworkshop.twitchcraft.game.GameStats.load(gsPath);
            gs.setPersist(true);
            check("fresh stats: zero counters, session 1", gs.deaths == 0 && gs.deathsTotal() == 0 && gs.sessions() == 1 && gs.lastDeath.isEmpty());
            int d1 = gs.recordDeath("Дед сгорел");
            int d2 = gs.recordDeath("");
            int a1 = gs.recordAdvancement();
            int b1 = gs.recordBoss();
            gs.recordDimensionChange();
            check("recordDeath/Advancement/Boss return running numbers", d1 == 1 && d2 == 2 && a1 == 1 && b1 == 1 && gs.dimensionChanges == 1
                    && gs.deaths == 2 && gs.deathsTotal() == 2 && gs.lastDeath.equals("смерть №2") && gs.lastDeathAt > 0);
            Map<String, String> gsVars = gs.placeholders();
            check("placeholders: deaths/deaths_total/advancements/bosses/last_death/session_time", gsVars.get("deaths").equals("2") && gsVars.get("deaths_total").equals("2")
                    && gsVars.get("advancements").equals("1") && gsVars.get("bosses_total").equals("1") && gsVars.get("last_death").equals("смерть №2")
                    && gsVars.get("session_time").equals("меньше минуты"));
            check("stats file persisted", java.nio.file.Files.exists(gsPath) && java.nio.file.Files.readString(gsPath).contains("\"deathsTotal\": 2"));
            dev.dedworkshop.twitchcraft.game.GameStats gs2 = dev.dedworkshop.twitchcraft.game.GameStats.load(gsPath);
            check("reload: totals survive, session counters start from zero, sessions++", gs2.deathsTotal() == 2 && gs2.advancementsTotal() == 1 && gs2.deaths == 0
                    && gs2.sessions() == 2);
            gs.resetSession();
            check("resetSession keeps totals", gs.deaths == 0 && gs.advancements == 0 && gs.deathsTotal() == 2);
            gs.resetTotals();
            check("resetTotals zeroes totals", gs.deathsTotal() == 0 && gs.bossesTotal() == 0);
            gs.setPersist(false);
            java.nio.file.Files.deleteIfExists(gsPath);
            gs.recordDeath("x");
            check("persist=false: nothing written", !java.nio.file.Files.exists(gsPath) && gs.deathsTotal() == 1);
            check("formatDuration", dev.dedworkshop.twitchcraft.game.GameStats.formatDuration(30_000).equals("меньше минуты")
                    && dev.dedworkshop.twitchcraft.game.GameStats.formatDuration(12 * 60_000L).equals("12 мин")
                    && dev.dedworkshop.twitchcraft.game.GameStats.formatDuration(65 * 60_000L).equals("1 ч 05 мин")
                    && dev.dedworkshop.twitchcraft.game.GameStats.formatDuration(3 * 3600_000L + 30 * 60_000L).equals("3 ч 30 мин")
                    && dev.dedworkshop.twitchcraft.game.GameStats.formatDuration(-5).equals("меньше минуты"));
        } catch (Exception e) {
            e.printStackTrace();
            check("v1.7 block threw: " + e, false);
        }

        System.out.println("== v1.7: TwitchEvent.game + GAME placeholders + findAction ==");
        {
            TwitchEvent death = TwitchEvent.game(TwitchEvent.GAME_DEATH, "Dед", "Dед was slain by Zombie", "", 3, false);
            check("game(): type GAME, kind=reward, message=text, amount, player as user, not synthetic", death.type() == TwitchEvent.Type.GAME && death.isGame()
                    && death.gameKind().equals(TwitchEvent.GAME_DEATH) && death.message().equals("Dед was slain by Zombie") && death.amount() == 3
                    && death.user().equals("Dед") && !death.synthetic());
            ModConfig g8 = ModConfig.createDefault();
            check("default config: 6 game actions, death reply uses {cause}/{deaths}", g8.gameEvents.size() == 6 && g8.gameEvents.get("death").reply.contains("{cause}")
                    && g8.gameEvents.get("death").reply.contains("{deaths}") && ModConfig.GAME_EVENT_KEYS.size() == 6);
            check("findAction GAME death → gameEvents.death", g8.findAction(death).action() == g8.gameEvents.get("death") && g8.findAction(death).key().equals("game:death"));
            TwitchEvent goal = TwitchEvent.game(TwitchEvent.GAME_ADVANCEMENT_GOAL, "Dед", "Освободить Край", "Удачи!", 4, false);
            g8.gameEvents.get("advancementGoal").reply = "";
            g8.gameEvents.get("advancementGoal").commands.clear();
            check("findAction GAME goal falls back to 'advancement' when the goal action is empty", g8.findAction(goal).action() == g8.gameEvents.get("advancement"));
            g8.gameEvents.get("advancementGoal").reply = "цель!";
            check("findAction GAME goal uses its own action when configured", g8.findAction(goal).action() == g8.gameEvents.get("advancementGoal"));
            Map<String, String> gmv = Placeholders.of(death, "Dед", new SessionStats());
            check("GAME placeholders: {cause} {player}", gmv.get("cause").equals("Dед was slain by Zombie") && gmv.get("player").equals("Dед") && gmv.get("game_kind").equals("death"));
            Map<String, String> av = Placeholders.of(goal, "Dед", null);
            check("GAME placeholders: {advancement} {advancement_text} {advancement_kind}", av.get("advancement").equals("Освободить Край")
                    && av.get("advancement_text").equals("Удачи!") && av.get("advancement_kind").equals("цель"));
            TwitchEvent bossEv = TwitchEvent.game(TwitchEvent.GAME_BOSS, "Dед", "Иссушитель", "Скелет", 1, false);
            check("GAME placeholders: {boss} {killer}; describe()", Placeholders.of(bossEv, "Dед", null).get("boss").equals("Иссушитель")
                    && Placeholders.of(bossEv, "Dед", null).get("killer").equals("Скелет") && bossEv.describe().contains("Иссушитель") && death.describe().contains("№3"));
            check("GAME event routes to module GAME_EVENTS", Module.forEvent(death) == Module.GAME_EVENTS && Module.forEvent(goal) == Module.GAME_EVENTS);
            check("Module scopes: CLIPS needs clips:edit + channel:manage:broadcast", Module.CLIPS.scopes().contains("clips:edit") && Module.CLIPS.scopes().contains("channel:manage:broadcast")
                    && Module.GAME_EVENTS.scopes().isEmpty());
        }

        System.out.println("== v1.7: GameEvents.parseAdvancement / stripBrackets / dimensionName / isSameName ==");
        {
            net.minecraft.network.chat.MutableComponent advTitle = net.minecraft.network.chat.Component.literal("Каменный век");
            net.minecraft.network.chat.MutableComponent advHover = net.minecraft.network.chat.Component.literal("Каменный век\nДобудьте свою первую кирку");
            net.minecraft.network.chat.MutableComponent bracketed = net.minecraft.network.chat.Component.translatable("chat.square_brackets", advTitle)
                    .withStyle(st -> st.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(advHover)));
            dev.dedworkshop.twitchcraft.game.GameEvents.Advancement parsedAdv = dev.dedworkshop.twitchcraft.game.GameEvents.parseAdvancement(bracketed);
            check("parseAdvancement: title from [brackets], description from hover (title line dropped)", parsedAdv.title().equals("Каменный век")
                    && parsedAdv.description().equals("Добудьте свою первую кирку"));
            dev.dedworkshop.twitchcraft.game.GameEvents.Advancement plainAdv = dev.dedworkshop.twitchcraft.game.GameEvents.parseAdvancement(
                    net.minecraft.network.chat.Component.literal("[Mod Thing]"));
            check("parseAdvancement: no hover → empty description", plainAdv.title().equals("Mod Thing") && plainAdv.description().isEmpty());
            check("stripBrackets", dev.dedworkshop.twitchcraft.game.GameEvents.stripBrackets(" [X] ").equals("X")
                    && dev.dedworkshop.twitchcraft.game.GameEvents.stripBrackets("[]").isEmpty() && dev.dedworkshop.twitchcraft.game.GameEvents.stripBrackets("Y").equals("Y")
                    && dev.dedworkshop.twitchcraft.game.GameEvents.stripBrackets(null).isEmpty());
            java.util.function.Function<String, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>> dimKey = idText ->
                    net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, net.minecraft.resources.Identifier.parse(idText));
            check("dimensionName: vanilla + modded", dev.dedworkshop.twitchcraft.game.GameEvents.dimensionName(dimKey.apply("minecraft:the_nether")).equals("Нижний мир")
                    && dev.dedworkshop.twitchcraft.game.GameEvents.dimensionName(dimKey.apply("minecraft:the_end")).equals("Край")
                    && dev.dedworkshop.twitchcraft.game.GameEvents.dimensionName(dimKey.apply("minecraft:overworld")).equals("Верхний мир")
                    && dev.dedworkshop.twitchcraft.game.GameEvents.dimensionName(dimKey.apply("aether:the_aether")).equals("The aether"));
            check("isSameName: exact / team prefix / suffix / not pet", dev.dedworkshop.twitchcraft.game.GameEvents.isSameName("Dед", "Dед")
                    && dev.dedworkshop.twitchcraft.game.GameEvents.isSameName("[Red] Dед", "Dед") && dev.dedworkshop.twitchcraft.game.GameEvents.isSameName("Dед ★", "Dед")
                    && !dev.dedworkshop.twitchcraft.game.GameEvents.isSameName("Рекс", "Dед") && !dev.dedworkshop.twitchcraft.game.GameEvents.isSameName("Dедушка", "Dед"));
        }

        System.out.println("== v1.7: ChatTimers — interval + chat-activity gating (fake clock) ==");
        try {
            net.minecraft.client.Minecraft.INSTANCE = null; // заглушка-игрок без getName(): плейсхолдер {player} должен быть пустым, а не падать
            dev.dedworkshop.twitchcraft.TwitchCraftClient tMod = new dev.dedworkshop.twitchcraft.TwitchCraftClient();
            java.lang.reflect.Field tcf = dev.dedworkshop.twitchcraft.TwitchCraftClient.class.getDeclaredField("config");
            tcf.setAccessible(true);
            ModConfig tCfg = ModConfig.createDefault();
            tCfg.timers.clear();
            tCfg.timers.add(new ModConfig.ChatTimer("ценник", 15, 3, "Ценник: {donation_prices_bad} ({donation_currency})"));
            tCfg.timers.add(new ModConfig.ChatTimer("всегда", 1, 0, "тик"));
            tcf.set(tMod, tCfg);
            final long[] clock = {1_000_000L};
            final int[] chatN = {0};
            List<String> posted = new ArrayList<>();
            dev.dedworkshop.twitchcraft.game.ChatTimers ct = new dev.dedworkshop.twitchcraft.game.ChatTimers(tMod, () -> clock[0], () -> chatN[0]) {
                @Override
                protected void send(ModConfig.ChatTimer timer, String text, boolean verbose) {
                    posted.add(timer.name + "|" + text + "|" + timer.twitch + "/" + timer.vk);
                }
            };
            check("first tick arms timers, nothing posted", ct.tick() == 0 && posted.isEmpty() && ct.secondsLeft(tCfg.timers.get(0)) == 15 * 60);
            clock[0] += 500;
            check("ticks closer than 1 s are ignored", ct.tick() == 0);
            clock[0] += 61_000;
            check("1-minute timer with minChat=0 fires after its interval", ct.tick() == 1 && posted.size() == 1 && posted.get(0).startsWith("всегда|тик|true/true"));
            clock[0] += 15 * 60_000L;
            int firedQuiet = ct.tick();
            check("15-min timer with quiet chat does NOT fire; the 1-min one does", firedQuiet == 1 && posted.size() == 2 && posted.stream().noneMatch(x -> x.startsWith("ценник")));
            check("quiet timer rechecks in 60 s", ct.secondsLeft(tCfg.timers.get(0)) == 60);
            chatN[0] = 3;
            clock[0] += 60_000;
            int firedLive = ct.tick();
            check("after 3 chat messages the 15-min timer fires with placeholders applied", firedLive == 2 && posted.size() == 4
                    && posted.stream().anyMatch(x -> x.startsWith("ценник|Ценник: ") && !x.contains("{donation_prices_bad}") && x.contains("(₽)")));
            check("posts() counts and nextAt resets to the full interval", ct.posts(tCfg.timers.get(0)) == 1 && ct.secondsLeft(tCfg.timers.get(0)) == 15 * 60);
            tCfg.timers.get(1).enabled = false;
            clock[0] += 120_000;
            check("disabled timer never fires; secondsLeft -1", ct.tick() == 0 && ct.secondsLeft(tCfg.timers.get(1)) == -1);
            tCfg.setEnabled(Module.CHAT_TIMERS, false);
            clock[0] += 16 * 60_000L;
            chatN[0] = 100;
            check("module off → nothing fires", ct.tick() == 0);
            tCfg.setEnabled(Module.CHAT_TIMERS, true);
            check("post(): manual post ignores gating; empty text → false", ct.post(tCfg.timers.get(0), true) && posted.size() == 5
                    && !ct.post(new ModConfig.ChatTimer("пусто", 5, 0, "  "), true));
            tCfg.timers.remove(1);
            ct.syncWithConfig();
            check("syncWithConfig drops states of removed timers", ct.posts(tCfg.timers.get(0)) == 1 && tCfg.findTimer("ЦЕННИК") == tCfg.timers.get(0) && tCfg.findTimer("нет") == null);
        } catch (Exception e) {
            e.printStackTrace();
            check("v1.7 block threw: " + e, false);
        }

        System.out.println("== v1.7: ClipManager decisions (pure) ==");
        try {
            dev.dedworkshop.twitchcraft.TwitchCraftClient cMod = new dev.dedworkshop.twitchcraft.TwitchCraftClient();
            java.lang.reflect.Field ccf = dev.dedworkshop.twitchcraft.TwitchCraftClient.class.getDeclaredField("config");
            ccf.setAccessible(true);
            ModConfig cCfg = ModConfig.createDefault();
            ccf.set(cMod, cCfg);
            dev.dedworkshop.twitchcraft.twitch.ClipManager cm = new dev.dedworkshop.twitchcraft.twitch.ClipManager(cMod);
            check("defaults: death → marker + clip; boss → marker + clip", cm.decideDeath().marker() && cm.decideDeath().clip() && cm.decideBoss().marker() && cm.decideBoss().clip());
            TwitchEvent don49 = TwitchEvent.simple(TwitchEvent.Type.DONATION, "Вася", "vasya", 49, "", "", "RUB");
            TwitchEvent don50 = TwitchEvent.simple(TwitchEvent.Type.DONATION, "Вася", "vasya", 50, "", "", "RUB");
            TwitchEvent donTest = TwitchEvent.test(TwitchEvent.Type.DONATION, "Тест", 500, "", "", "RUB");
            check("donation threshold 50: 49 → nothing, 50 → marker + clip, synthetic → nothing", !cm.decideDonation(don49).marker() && !cm.decideDonation(don49).clip()
                    && cm.decideDonation(don50).marker() && cm.decideDonation(don50).clip() && !cm.decideDonation(donTest).clip());
            cCfg.clips.clipOnDonation = false;
            cCfg.clips.donationFrom = 0;
            check("donationFrom=0 disables donation triggers", !cm.decideDonation(don50).marker() && !cm.decideDonation(don50).clip());
            cCfg.clips.donationFrom = 100;
            check("per-trigger flags respected (marker only)", cm.decideDonation(TwitchEvent.simple(TwitchEvent.Type.DONATION, "A", "a", 100, "", "", "RUB")).marker()
                    && !cm.decideDonation(TwitchEvent.simple(TwitchEvent.Type.DONATION, "A", "a", 100, "", "", "RUB")).clip());
            check("non-donation events never trigger donation logic", !cm.decideDonation(ev(TwitchEvent.Type.CHEER, 1000, "")).marker());
            cCfg.setEnabled(Module.CLIPS, false);
            check("module CLIPS off → nothing", !cm.decideDeath().marker() && !cm.decideBoss().clip() && !cm.enabled());
            cCfg.setEnabled(Module.CLIPS, true);
            check("no tokens → canClip/canMark false, manual clip refused without network", !cm.canClip() && !cm.canMark() && !cm.clip("тест", false) && !cm.marker("тест", false)
                    && cm.clipsCreated() == 0);
            check("StreamStatus placeholders without data", new dev.dedworkshop.twitchcraft.game.StreamStatus(cMod).placeholders().get("stream_time").equals("неизвестно")
                    && new dev.dedworkshop.twitchcraft.game.StreamStatus(cMod).placeholders().get("live").equals("нет"));
            dev.dedworkshop.twitchcraft.game.StreamStatus ss = new dev.dedworkshop.twitchcraft.game.StreamStatus(cMod);
            ss.set(new dev.dedworkshop.twitchcraft.twitch.TwitchApi.Stream(true, System.currentTimeMillis() - 90 * 60_000L, 17, "Стрим", "Minecraft"));
            check("StreamStatus live placeholders", ss.live() && ss.known() && ss.placeholders().get("stream_time").equals("1 ч 30 мин") && ss.placeholders().get("viewers").equals("17")
                    && ss.placeholders().get("stream_title").equals("Стрим") && ss.describe().contains("в эфире"));
            ss.set(new dev.dedworkshop.twitchcraft.twitch.TwitchApi.Stream(false, 0, 0, "", ""));
            check("StreamStatus offline", !ss.live() && ss.known() && ss.placeholders().get("stream_time").equals("не в эфире") && ss.placeholders().get("viewers").equals("0"));
            ss.forget();
            check("StreamStatus.forget", !ss.known());
        } catch (Exception e) {
            e.printStackTrace();
            check("v1.7 block threw: " + e, false);
        }

        System.out.println("== YouTube Live: event mapping, PKCE, platform routing, and config ==");
        {
            com.google.gson.JsonObject ytChatJson = j("{\"id\":\"chat-1\",\"snippet\":{\"type\":\"textMessageEvent\",\"textMessageDetails\":{\"messageText\":\"!zombie hi\"}},\"authorDetails\":{\"channelId\":\"UC123\",\"displayName\":\"Viewer\",\"isChatModerator\":true}}");
            TwitchEvent ytChat = dev.dedworkshop.twitchcraft.youtube.YoutubeEventMapper.fromMessage(ytChatJson);
            check("YouTube text message keeps explicit platform, channel ID and moderator permission", ytChat != null
                    && ytChat.type() == TwitchEvent.Type.CHAT && ytChat.isYoutube() && ytChat.userId().equals("UC123")
                    && ytChat.message().equals("!zombie hi") && ytChat.permission() == TwitchEvent.Permission.MODERATOR
                    && Module.forEvent(ytChat) == Module.YOUTUBE_LIVE && Placeholders.of(ytChat, "Steve").get("platform").equals("YouTube Live"));

            TwitchEvent ytSuperChat = dev.dedworkshop.twitchcraft.youtube.YoutubeEventMapper.fromMessage(j("{\"id\":\"paid-1\",\"snippet\":{\"type\":\"superChatEvent\",\"superChatDetails\":{\"amountMicros\":\"55000000\",\"currency\":\"RUB\",\"userComment\":\"Спасибо\"}},\"authorDetails\":{\"channelId\":\"UC456\",\"displayName\":\"Sponsor\"}}"));
            check("YouTube Super Chat maps to shared DONATION, preserving amount/currency/source", ytSuperChat != null
                    && ytSuperChat.type() == TwitchEvent.Type.DONATION && ytSuperChat.amount() == 55 && ytSuperChat.currency().equals("RUB")
                    && ytSuperChat.source().equals(TwitchEvent.SOURCE_YOUTUBE_SUPER_CHAT) && ytSuperChat.isYoutube()
                    && ytSuperChat.describe().contains("YouTube Super Chat"));

            TwitchEvent ytMember = dev.dedworkshop.twitchcraft.youtube.YoutubeEventMapper.fromMessage(j("{\"id\":\"member-1\",\"snippet\":{\"type\":\"newSponsorEvent\",\"newSponsorDetails\":{\"memberLevelName\":\"Gold\"}},\"authorDetails\":{\"channelId\":\"UC789\",\"displayName\":\"Member\",\"isChatSponsor\":true}}"));
            TwitchEvent ytGift = dev.dedworkshop.twitchcraft.youtube.YoutubeEventMapper.fromMessage(j("{\"id\":\"gift-1\",\"snippet\":{\"type\":\"membershipGiftingEvent\",\"membershipGiftingDetails\":{\"giftMembershipsCount\":5,\"giftMembershipsLevelName\":\"Gold\",\"gifterIsAnonymous\":true}},\"authorDetails\":{\"channelId\":\"UC111\",\"displayName\":\"Gifter\"}}"));
            check("YouTube paid membership and gift membership map to subscribe/gift actions", ytMember != null
                    && ytMember.type() == TwitchEvent.Type.SUBSCRIBE && ytMember.isYoutube() && ytMember.tier().equals("Gold")
                    && ytGift != null && ytGift.type() == TwitchEvent.Type.GIFT_SUB && ytGift.amount() == 5 && ytGift.user().equals("Аноним"));
            check("YouTube gift-recipient notices and unsupported platform events are not double-counted", dev.dedworkshop.twitchcraft.youtube.YoutubeEventMapper.fromMessage(j("{\"snippet\":{\"type\":\"giftMembershipReceivedEvent\"}}")) == null
                    && dev.dedworkshop.twitchcraft.youtube.YoutubeEventMapper.fromMessage(j("{\"snippet\":{\"type\":\"channelRaidEvent\"}}")) == null);

            String verifier = dev.dedworkshop.twitchcraft.youtube.YoutubeApi.newCodeVerifier();
            String challenge = dev.dedworkshop.twitchcraft.youtube.YoutubeApi.codeChallenge(verifier);
            String authorize = dev.dedworkshop.twitchcraft.youtube.YoutubeApi.authorizeUrl("client-id", "http://localhost:8640", "state-1", challenge);
            check("OAuth Desktop PKCE verifier/challenge use URL-safe 43-character values", verifier.length() == 43 && challenge.length() == 43
                    && verifier.matches("[A-Za-z0-9_-]+") && challenge.matches("[A-Za-z0-9_-]+"));
            check("OAuth URL requests code + S256 PKCE, offline refresh and YouTube read/write scopes", authorize.contains("response_type=code")
                    && authorize.contains("code_challenge_method=S256") && authorize.contains("access_type=offline")
                    && authorize.contains("youtube.readonly") && authorize.contains("youtube.force-ssl")
                    && authorize.contains("redirect_uri=http%3A%2F%2Flocalhost%3A8640"));

            dev.dedworkshop.twitchcraft.youtube.YoutubeStore ytStore = new dev.dedworkshop.twitchcraft.youtube.YoutubeStore();
            ytStore.setTokens("access", "refresh", 3600, dev.dedworkshop.twitchcraft.youtube.YoutubeApi.SCOPES);
            check("YouTube token store checks read/write scopes separately", ytStore.hasTokens() && ytStore.hasRefreshToken()
                    && ytStore.canReadChat() && ytStore.canWriteChat());
            ytStore.scopes = dev.dedworkshop.twitchcraft.youtube.YoutubeApi.SCOPE_READ;
            check("read-only YouTube token cannot send bot messages", ytStore.canReadChat() && !ytStore.canWriteChat());
            ytStore.setTokens("refreshed-access", "", 3600, dev.dedworkshop.twitchcraft.youtube.YoutubeApi.SCOPES);
            boolean sameAccountRefreshPreserved = ytStore.refreshToken.equals("refresh");
            ytStore.replaceTokens("new-account-access", "", 3600, dev.dedworkshop.twitchcraft.youtube.YoutubeApi.SCOPES);
            check("YouTube fresh login never reuses another account's refresh token", sameAccountRefreshPreserved
                    && ytStore.accessToken.equals("new-account-access") && !ytStore.hasRefreshToken());

            ModConfig ytConfig = ModConfig.createDefault();
            ytConfig.youtube.callbackPort = ytConfig.vk.callbackPort;
            ytConfig.normalize();
            check("YouTube config is separate, enabled by default, current version 10, and callback ports stay unique", ytConfig.configVersion == 11
                    && ytConfig.youtube != null && ytConfig.isEnabled(Module.YOUTUBE_LIVE) && ytConfig.youtube.callbackPort != ytConfig.vk.callbackPort
                    && ytConfig.youtube.callbackPort != ytConfig.donations.callbackPort);
            check("YouTube platform prefix and membership wording", ytMember.describe().contains("YouTube")
                    && ytMember.shortText().startsWith("YouTube ") && ytChat.describe().startsWith("§c[YouTube]"));
        }

        System.out.println("== YouTube Live 1.11.0: quota accounting, moderation events, Retry-After, new config ==");
        {
            dev.dedworkshop.twitchcraft.youtube.YoutubeQuota quota = new dev.dedworkshop.twitchcraft.youtube.YoutubeQuota("client-a");
            quota.sync("client-a");
            check("счётчик квоты пуст, привязан к Client ID и считает сутки по тихоокеанскому времени", quota.used() == 0
                    && quota.clientId().equals("client-a") && dev.dedworkshop.twitchcraft.youtube.YoutubeQuota.dayKey().matches("\\d{4}-\\d{2}-\\d{2}")
                    && quota.remaining(9000) == 9000 && !quota.exhausted(9000) && !quota.exhausted(0));
            quota.charge(dev.dedworkshop.twitchcraft.youtube.YoutubeQuota.COST_CHAT_LIST);
            quota.charge(dev.dedworkshop.twitchcraft.youtube.YoutubeQuota.COST_VIDEO_LIST);
            check("стоимость запросов соответствует прайсу Google: чат 5, чтение 1, запись 50", quota.used() == 6
                    && dev.dedworkshop.twitchcraft.youtube.YoutubeQuota.COST_CHAT_LIST == 5
                    && dev.dedworkshop.twitchcraft.youtube.YoutubeQuota.COST_VIDEO_LIST == 1
                    && dev.dedworkshop.twitchcraft.youtube.YoutubeQuota.COST_BROADCAST_LIST == 1
                    && dev.dedworkshop.twitchcraft.youtube.YoutubeQuota.COST_CHAT_INSERT == 50
                    && dev.dedworkshop.twitchcraft.youtube.YoutubeQuota.COST_BAN_INSERT == 50
                    && dev.dedworkshop.twitchcraft.youtube.YoutubeQuota.COST_BROADCAST_TRANSITION == 50);
            quota.restore("client-a", dev.dedworkshop.twitchcraft.youtube.YoutubeQuota.dayKey(), 8995);
            check("счётчик из секретного файла действует до порога дневного бюджета", quota.used() == 8995
                    && quota.remaining(9000) == 5 && !quota.exhausted(9000));
            quota.charge(10);
            check("исчерпанный бюджет виден в статусе и объясняет, когда квота сбросится", quota.exhausted(9000)
                    && quota.remaining(9000) == 0 && quota.describe(9000).contains("исчерпана")
                    && quota.describe(9000).contains("сброс в") && quota.describe(0).contains("/ 10000 ед.")
                    && dev.dedworkshop.twitchcraft.youtube.YoutubeQuota.millisUntilReset() > 0);
            quota.sync("client-b");
            check("смена проекта Google Cloud обнуляет счётчик (квота считается на проект)", quota.used() == 0
                    && quota.clientId().equals("client-b"));
            check("unitsPerHour оценивает расход на час чтения чата", dev.dedworkshop.twitchcraft.youtube.YoutubeQuota.unitsPerHour(5000,
                    dev.dedworkshop.twitchcraft.youtube.YoutubeQuota.COST_CHAT_LIST) == 3600
                    && dev.dedworkshop.twitchcraft.youtube.YoutubeQuota.unitsPerHour(60000,
                    dev.dedworkshop.twitchcraft.youtube.YoutubeQuota.COST_CHAT_LIST) == 300);

            dev.dedworkshop.twitchcraft.youtube.YoutubeEventMapper.Moderation ytBan = dev.dedworkshop.twitchcraft.youtube.YoutubeEventMapper.moderation(j("{\"snippet\":{\"type\":\"userBannedEvent\",\"userBannedEventDetails\":{\"banType\":\"temporary\",\"banDurationSeconds\":300,\"bannedUserDetails\":{\"channelId\":\"UC-troll\",\"displayName\":\"Troll Viewer\"}}}}"));
            dev.dedworkshop.twitchcraft.youtube.YoutubeEventMapper.Moderation ytPermaban = dev.dedworkshop.twitchcraft.youtube.YoutubeEventMapper.moderation(j("{\"snippet\":{\"type\":\"userBannedEvent\",\"userBannedEventDetails\":{\"banType\":\"permanent\",\"bannedUserDetails\":{\"displayName\":\"Banned\"}}}}"));
            dev.dedworkshop.twitchcraft.youtube.YoutubeEventMapper.Moderation ytSpam = dev.dedworkshop.twitchcraft.youtube.YoutubeEventMapper.moderation(j("{\"snippet\":{\"type\":\"markChatItemAsSpamEvent\",\"markChatItemAsSpamDetails\":{\"spammedChannelId\":\"UC-spam\",\"spammedChannelDisplayName\":\"Spammer\"}}}"));
            dev.dedworkshop.twitchcraft.youtube.YoutubeEventMapper.Moderation ytDelete = dev.dedworkshop.twitchcraft.youtube.YoutubeEventMapper.moderation(j("{\"snippet\":{\"type\":\"markChatItemsAsDeletedEvent\",\"markChatItemsAsDeletedDetails\":{\"deletedStateMessage\":{\"simpleText\":\"Сообщение удалено\"}}}}"));
            check("тайм-аут модератора разбирается с ником и длительностью в секундах", ytBan != null
                    && ytBan.kind().equals("ban") && ytBan.user().equals("Troll Viewer") && ytBan.seconds() == 300 && ytBan.detail().equals("temporary"));
            check("постоянный бан не получает длительность, спам и удаление помечаются своим видом", ytPermaban != null
                    && ytPermaban.seconds() == 0 && ytPermaban.user().equals("Banned") && ytSpam != null && ytSpam.kind().equals("spam")
                    && ytSpam.user().equals("Spammer") && ytDelete != null && ytDelete.kind().equals("delete"));
            check("обычные сообщения чата не считаются модерацией", dev.dedworkshop.twitchcraft.youtube.YoutubeEventMapper.moderation(j("{\"snippet\":{\"type\":\"textMessageEvent\"}}")) == null);
            check("служебные типы чата распознаются и не попадают в лог как неизвестные", dev.dedworkshop.twitchcraft.youtube.YoutubeEventMapper.isServiceType("placeholderMessageEvent")
                    && dev.dedworkshop.twitchcraft.youtube.YoutubeEventMapper.isServiceType("giftMembershipReceivedEvent")
                    && dev.dedworkshop.twitchcraft.youtube.YoutubeEventMapper.isServiceType("pollEvent")
                    && !dev.dedworkshop.twitchcraft.youtube.YoutubeEventMapper.isServiceType("textMessageEvent")
                    && !dev.dedworkshop.twitchcraft.youtube.YoutubeEventMapper.isServiceType(null));
            check("события модерации не превращаются в игровые события", dev.dedworkshop.twitchcraft.youtube.YoutubeEventMapper.fromMessage(j("{\"snippet\":{\"type\":\"userBannedEvent\"}}")) == null
                    && dev.dedworkshop.twitchcraft.youtube.YoutubeEventMapper.fromMessage(j("{\"snippet\":{\"type\":\"markChatItemAsSpamEvent\"}}")) == null
                    && dev.dedworkshop.twitchcraft.youtube.YoutubeEventMapper.fromMessage(j("{\"snippet\":{\"type\":\"placeholderMessageEvent\"}}")) == null);

            dev.dedworkshop.twitchcraft.twitch.TwitchHttp.Response limited = new dev.dedworkshop.twitchcraft.twitch.TwitchHttp.Response(429, "{}",
                    java.util.Map.of("retry-after", java.util.List.of("3")));
            check("Retry-After читается из заголовков без учёта регистра и переводится в миллисекунды", limited.header("Retry-After").equals("3")
                    && limited.retryAfterMillis() == 3000 && !limited.ok() && limited.status() == 429
                    && new dev.dedworkshop.twitchcraft.twitch.TwitchHttp.Response(200, "ok").retryAfterMillis() == 0
                    && new dev.dedworkshop.twitchcraft.twitch.TwitchHttp.Response(200, null, null).body().isEmpty());

            ModConfig ytNew = ModConfig.createDefault();
            check("новые настройки YouTube: максимум единиц за опрос, зрители, бюджет квоты, управление и модерация", ytNew.youtube.pollMaxResults == 2000
                    && ytNew.youtube.trackViewers && ytNew.youtube.viewersIntervalSeconds == 60 && ytNew.youtube.quotaBudget == 9000
                    && ytNew.youtube.quotaGuard && ytNew.youtube.control && !ytNew.youtube.showModeration
                    && ytNew.youtube.defaultTimeoutSeconds == 300);
            ModConfig ytClamp = ModConfig.createDefault();
            ytClamp.youtube.pollMaxResults = 5000;
            ytClamp.youtube.viewersIntervalSeconds = 1;
            ytClamp.youtube.quotaBudget = -5;
            ytClamp.youtube.defaultTimeoutSeconds = 99999999;
            ytClamp.normalize();
            check("normalize зажимает опрос чата, интервал зрителей, бюджет квоты и тайм-аут в допустимые границы", ytClamp.youtube.pollMaxResults == 2000
                    && ytClamp.youtube.viewersIntervalSeconds == 15 && ytClamp.youtube.quotaBudget == 0
                    && ytClamp.youtube.defaultTimeoutSeconds == 7 * 24 * 3600);

            ModConfig ytSrc = ModConfig.createDefault();
            ytSrc.configVersion = 9;
            ytSrc.youtube.clientId = "old-client";
            ytSrc.youtube.showChat = false;
            ytSrc.youtube.chatPrefix = "&9[YT]&r ";
            String ytJson = ytSrc.toJson();
            com.google.gson.JsonObject ytObj = j(ytJson);
            com.google.gson.JsonObject ytSection = ytObj.getAsJsonObject("youtube");
            for (String key : new String[]{"pollMaxResults", "trackViewers", "viewersIntervalSeconds", "quotaBudget", "quotaGuard", "control", "showModeration", "defaultTimeoutSeconds"}) {
                ytSection.remove(key);
            }
            String ytOld = ytObj.toString();
            ModConfig ytMig = ModConfig.fromJson(ytOld);
            check("v9 → v10: сохранённые настройки YouTube не трогают, новые появляются значениями по умолчанию", ytMig.upgradeFrom(ytOld)
                    && ytMig.configVersion == 11 && ytMig.youtube.clientId.equals("old-client") && !ytMig.youtube.showChat
                    && ytMig.youtube.chatPrefix.equals("&9[YT]&r ") && ytMig.youtube.pollMaxResults == 2000
                    && ytMig.youtube.quotaBudget == 9000 && ytMig.youtube.quotaGuard && ytMig.youtube.control
                    && !ytMig.youtube.showModeration && ytMig.youtube.defaultTimeoutSeconds == 300
                    && ModConfig.findWarnings(ytMig.toJson()).isEmpty());
        }

        System.out.println("== v1.7: migration v7 -> v9 (game events, timers, clips, VK port 8632 → 8638) ==");
        {
            ModConfig m8src = ModConfig.createDefault();
            m8src.configVersion = 7;
            m8src.vk.callbackPort = 8632;
            m8src.chatCommands.remove("смерти");
            m8src.chatCommands.remove("время");
            String m8json = m8src.toJson();
            // убираем новые секции — как будто конфиг писала версия 1.6.0
            com.google.gson.JsonObject m8obj = j(m8json);
            m8obj.remove("gameEvents");
            m8obj.remove("gameEventsSettings");
            m8obj.remove("youtube");
            m8obj.remove("timers");
            m8obj.remove("clips");
            String m8old = m8obj.toString();
            ModConfig m8 = ModConfig.fromJson(m8old);
            boolean m8up = m8.upgradeFrom(m8old);
            check("v7 → v10: YouTube and v1.7 sections added, version 10", m8up && m8.configVersion == 11 && m8.youtube != null
                    && m8.gameEvents != null && m8.gameEvents.size() == 6 && m8.gameEventsSettings != null
                    && m8.timers != null && m8.timers.size() == 2 && m8.clips != null && m8.clips.donationFrom == 50);
            check("v7 → v10: chat commands !смерти and !время added; VK port 8632 → 8638", m8.findChatCommand("смерти") != null && m8.findChatCommand("время") != null
                    && m8.vk.callbackPort == 8638);
            ModConfig m8custom = ModConfig.createDefault();
            m8custom.configVersion = 7;
            m8custom.vk.callbackPort = 5000;
            String m8customJson = m8custom.toJson();
            ModConfig m8c = ModConfig.fromJson(m8customJson);
            m8c.upgradeFrom(m8customJson);
            check("v7 → v10: custom VK port is kept", m8c.vk.callbackPort == 5000 && m8c.configVersion == 11);
            String m8stable = m8.toJson();
            check("v8 output stable, no warnings", !ModConfig.fromJson(m8stable).upgradeFrom(m8stable) && ModConfig.findWarnings(m8stable).isEmpty()
                    && ModConfig.createDefault().vk.callbackPort == 8638);
            ModConfig m8norm = ModConfig.createDefault();
            m8norm.timers.get(0).intervalMinutes = 0;
            m8norm.timers.get(0).minChatMessages = -4;
            m8norm.clips.clipCooldownSeconds = -1;
            m8norm.clips.donationFrom = -5;
            m8norm.normalize();
            check("normalize clamps timers and clips", m8norm.timers.get(0).intervalMinutes >= 1 && m8norm.timers.get(0).minChatMessages == 0
                    && m8norm.clips.clipCooldownSeconds >= 0 && m8norm.clips.donationFrom == 0);
            check("Module list has 21 entries incl. YouTube Live, chat timers, game events, and clips", Module.values().length == 21
                    && Module.byId("gameEvents") == Module.GAME_EVENTS && Module.byId("chatTimers") == Module.CHAT_TIMERS && Module.byId("clips") == Module.CLIPS);
        }

        testChatSigns();

        System.out.println(failures == 0 ? "\nALL " + total + " TESTS PASSED" : "\nFAILURES: " + failures + " of " + total);
        System.exit(failures == 0 ? 0 : 1);
    }

    /** Таблички чата в воздухе: раскладка текста, проекция, шапка, настройки и миграция v10 → v11. */
    static void testChatSigns() {
        System.out.println("== v1.12: таблички чата в воздухе (чистая логика) ==");
        ToIntFunction<String> m = s -> s.length() * 6; // 6 px на символ
        check("sign: короткий текст — одна строка", ChatSignLayout.wrap("привет мир", 160, 3, m).equals(List.of("привет мир")));
        check("sign: пустой и null текст — без строк", ChatSignLayout.wrap("   ", 60, 3, m).isEmpty() && ChatSignLayout.wrap(null, 60, 3, m).isEmpty());
        List<String> lines = ChatSignLayout.wrap("слово ".repeat(60).trim(), 60, 3, m);
        check("sign: перенос не шире строки, не больше трёх строк, хвост «…»", lines.size() == 3
                && lines.stream().allMatch(l -> m.applyAsInt(l) <= 60) && lines.get(2).endsWith("…"));
        List<String> cut = ChatSignLayout.wrap("abcdefghijklmnopqrstuvwxyzabcdefghijklmn", 60, 3, m);
        check("sign: слово шире строки режется по символам", cut.size() == 3 && cut.get(0).equals("abcdefghij")
                && cut.get(1).equals("klmnopqrst") && cut.get(2).endsWith("…"));
        ChatSignLayout.Layout lay = ChatSignLayout.layout(List.of(new ChatSignLayout.Segment("Ник", 0xFFFFFFFF)), "текст", 0xFFFFFFFF, m);
        check("sign: табличка = строка шапки + строка текста, с полями", lay.rows().size() == 2
                && lay.width() == Math.max(m.applyAsInt("Ник"), m.applyAsInt("текст")) + 2 * ChatSignLayout.PAD
                && lay.height() == 2 * ChatSignLayout.LINE + 2 * ChatSignLayout.PAD - 1);
        List<ChatSignLayout.Segment> head = List.of(new ChatSignLayout.Segment("[T] ", 1),
                new ChatSignLayout.Segment("ОченьДлинныйНикЗрителя", 2));
        List<ChatSignLayout.Segment> fitted = ChatSignLayout.fit(head, 100, m);
        int fittedWidth = 0;
        for (ChatSignLayout.Segment seg : fitted) {
            fittedWidth += m.applyAsInt(seg.text());
        }
        ChatSignLayout.Segment lastSeg = fitted.get(fitted.size() - 1);
        check("sign: шапка сокращается до ширины, ник сохраняет цвет и получает «…»", fittedWidth <= 100
                && lastSeg.argb() == 2 && lastSeg.text().endsWith("…"));
        double[] off = ChatSignLayout.stackOffsets(new double[] {17, 17, 30});
        check("sign: стопка — новая у базы, старые выше", off[0] == 0 && off[1] == 17 + ChatSignLayout.GAP
                && off[2] == 2 * (17 + ChatSignLayout.GAP));
        check("sign: затухание — появление, полная видимость, уход за последнюю секунду", ChatSignLayout.alpha(0, 9000, 1000) == 0
                && ChatSignLayout.alpha(10_000, 9000, 1000) == 255 && ChatSignLayout.alpha(10_000, 500, 1000) == 128
                && ChatSignLayout.alpha(10_000, 0, 1000) == 0);
        check("sign: метка платформы без цветовых кодов", ChatSignLayout.plain("§9[VK]§r ").equals("[VK] "));

        // Проекция: точки в центре, справа/сверху, за камерой, взгляд вверх
        WorldProjection.Screen centre = WorldProjection.project(0, 0, 6, 0, 0, 0, 0, 0, 70, 854, 480);
        check("projection: точка прямо перед камерой — в центре экрана", centre != null && Math.abs(centre.x() - 427) < 1e-6
                && Math.abs(centre.y() - 240) < 1e-6 && Math.abs(centre.depth() - 6) < 1e-9);
        WorldProjection.Screen rightSide = WorldProjection.project(-3, 0, 6, 0, 0, 0, 0, 0, 70, 854, 480);
        WorldProjection.Screen above = WorldProjection.project(0, 1, 6, 0, 0, 0, 0, 0, 70, 854, 480);
        check("projection: при взгляде на юг запад справа, выше в мире — выше на экране", rightSide != null && rightSide.x() > 427
                && above != null && above.y() < 240);
        WorldProjection.Screen westFacing = WorldProjection.project(-3, 0, -3, 0, 0, 0, 0, 90, 70, 854, 480);
        check("projection: при взгляде на запад север справа", westFacing != null && westFacing.x() > 427 && westFacing.depth() > 2.9);
        check("projection: точка за камерой не рисуется", WorldProjection.project(0, 0, -5, 0, 0, 0, 0, 0, 70, 854, 480) == null);
        WorldProjection.Screen sky = WorldProjection.project(0, 5, 0, 0, 0, 0, -90, 0, 70, 854, 480);
        check("projection: взгляд строго вверх — точка над головой в центре, без вырождения", sky != null
                && Math.abs(sky.x() - 427) < 1e-6 && Math.abs(sky.y() - 240) < 1e-6);
        boolean basisMatchesGame = true;
        for (float[] a : new float[][] {{0, 0}, {30, 45}, {-60, 120}, {15, -170}, {89, 10}}) {
            Vec3 g = Vec3.directionFromRotation(a[0], a[1]);
            double[] f = WorldProjection.forward(a[0], a[1]);
            double[] r = WorldProjection.right(a[1]);
            double[] u = WorldProjection.up(a[0], a[1]);
            double dotFR = f[0] * r[0] + f[1] * r[1] + f[2] * r[2];
            double dotFU = f[0] * u[0] + f[1] * u[1] + f[2] * u[2];
            basisMatchesGame &= Math.abs(g.x - f[0]) < 1e-6 && Math.abs(g.y - f[1]) < 1e-6 && Math.abs(g.z - f[2]) < 1e-6
                    && Math.abs(dotFR) < 1e-9 && Math.abs(dotFU) < 1e-9;
        }
        check("projection: «вперёд» совпадает с Vec3.directionFromRotation, базис ортогонален", basisMatchesGame);
        check("projection: масштаб 1 на расстоянии 6, крупнее вблизи, мельче вдали, в пределах 0.5–1.6",
                Math.abs(WorldProjection.scaleFor(6, 6) - 1) < 1e-9 && WorldProjection.scaleFor(3, 6) == 1.6
                        && WorldProjection.scaleFor(12, 6) == 0.5);

        // Шапка таблички: метка платформы, значки, цвет ника, битсы
        ModConfig hdr = ModConfig.createDefault();
        TwitchEvent vkChat = TwitchEvent.simple(TwitchEvent.Type.CHAT, "Зритель", "zritel", 0, "привет", "", "").withPlatform("vk");
        StringBuilder vkHead = new StringBuilder();
        for (ChatSignLayout.Segment seg : TwitchChatRenderer.signHeader(hdr, vkChat)) {
            vkHead.append(seg.text());
        }
        check("sign header: VK — метка из префикса VK без цветовых кодов", vkHead.toString().equals("[VK] Зритель"));
        TwitchEvent twChat = new TwitchEvent(TwitchEvent.Type.CHAT, "Ник", "nik", "1", 100, "hi", "", "", "", "",
                Set.of("subscriber"), "#1E90FF", "", false);
        List<ChatSignLayout.Segment> twHead = TwitchChatRenderer.signHeader(hdr, twChat);
        StringBuilder twText = new StringBuilder();
        for (ChatSignLayout.Segment seg : twHead) {
            twText.append(seg.text());
        }
        check("sign header: Twitch — [T], значок подписчика, битсы, цвет ника зрителя", twText.toString().equals("[T] ★ Ник [100 битс]")
                && twHead.get(2).argb() == 0xFF1E90FF);

        // Настройки: значения по умолчанию, пределы, JSON и миграция v10 → v11
        ModConfig cs = ModConfig.createDefault();
        check("chat signs: по умолчанию включены, строка в чате остаётся, 10 с, 5 сразу, 6 блоков, 100 %, версия 11",
                cs.chatSigns.enabled && cs.chatSigns.keepInChat && cs.chatSigns.seconds == 10 && cs.chatSigns.maxVisible == 5
                        && cs.chatSigns.distance == 6 && cs.chatSigns.scale == 100 && cs.configVersion == 11);
        cs.chatSigns.enabled = false;
        cs.chatSigns.keepInChat = false;
        cs.chatSigns.seconds = 999;
        cs.chatSigns.maxVisible = 0;
        cs.chatSigns.distance = 100;
        cs.chatSigns.scale = 1;
        cs.normalize();
        check("chat signs: normalize зажимает значения в допустимых пределах", cs.chatSigns.seconds == 120 && cs.chatSigns.maxVisible == 1
                && cs.chatSigns.distance == 30 && cs.chatSigns.scale == 25);
        ModConfig csBack = ModConfig.fromJson(cs.toJson());
        check("chat signs: переживают JSON-раундтрип без предупреждений", !csBack.chatSigns.enabled && !csBack.chatSigns.keepInChat
                && csBack.chatSigns.seconds == 120 && ModConfig.findWarnings(cs.toJson()).isEmpty());
        String v10 = "{\"configVersion\":10,\"clientId\":\"abc\",\"twitchChat\":{\"prefix\":\"[T] \"}}";
        ModConfig up11 = ModConfig.fromJson(v10);
        boolean changed11 = up11.upgradeFrom(v10);
        check("v10 → v11: раздел chatSigns появляется по умолчанию, свои поля сохранены", changed11 && up11.configVersion == 11
                && up11.chatSigns.enabled && up11.clientId.equals("abc") && up11.twitchChat.prefix.equals("[T] "));
        String stable11 = up11.toJson();
        check("v11 output stable, no warnings", !ModConfig.fromJson(stable11).upgradeFrom(stable11) && ModConfig.findWarnings(stable11).isEmpty());
        String custom11 = "{\"configVersion\":11,\"chatSigns\":{\"enabled\":false,\"seconds\":3}}";
        ModConfig cust = ModConfig.fromJson(custom11);
        cust.upgradeFrom(custom11);
        check("v11: свои значения chatSigns не перетираются миграцией", !cust.chatSigns.enabled && cust.chatSigns.seconds == 3);
    }
}
