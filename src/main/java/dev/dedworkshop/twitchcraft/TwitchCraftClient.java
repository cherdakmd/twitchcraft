package dev.dedworkshop.twitchcraft;

import dev.dedworkshop.twitchcraft.action.EventProcessor;
import dev.dedworkshop.twitchcraft.action.FundraiserTracker;
import dev.dedworkshop.twitchcraft.action.GoalTracker;
import dev.dedworkshop.twitchcraft.api.AddonManager;
import dev.dedworkshop.twitchcraft.command.TwitchCommands;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.donations.DonationManager;
import dev.dedworkshop.twitchcraft.donations.DonationStore;
import dev.dedworkshop.twitchcraft.game.ChatTimers;
import dev.dedworkshop.twitchcraft.game.GameEvents;
import dev.dedworkshop.twitchcraft.game.GameStats;
import dev.dedworkshop.twitchcraft.game.StreamStatus;
import dev.dedworkshop.twitchcraft.twitch.ClipManager;
import dev.dedworkshop.twitchcraft.config.DonationPresets;
import dev.dedworkshop.twitchcraft.config.TokenStore;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.twitch.ChatSender;
import dev.dedworkshop.twitchcraft.twitch.EventSubClient;
import dev.dedworkshop.twitchcraft.twitch.RewardManager;
import dev.dedworkshop.twitchcraft.twitch.TwitchApi;
import dev.dedworkshop.twitchcraft.twitch.TwitchAuth;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import dev.dedworkshop.twitchcraft.ui.Hotkeys;
import dev.dedworkshop.twitchcraft.ui.OverlayHud;
import dev.dedworkshop.twitchcraft.util.Chat;
import dev.dedworkshop.twitchcraft.vk.VkLive;
import dev.dedworkshop.twitchcraft.vk.VkStore;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Главный класс мода (точка входа). Fabric вызывает onInitializeClient() при запуске игры.
 *
 * Здесь связываются все части:
 *  - ModConfig / TokenStore   — настройки и токены
 *  - TwitchAuth / TwitchApi   — авторизация и запросы к Twitch
 *  - EventSubClient           — получение событий по WebSocket
 *  - EventProcessor           — очередь, кулдауны, выполнение действий
 *  - RewardManager/ChatSender — управление наградами и ответы в чат
 *  - TwitchCommands/Hotkeys/OverlayHud — команды, клавиши, оверлей
 */
public class TwitchCraftClient implements ClientModInitializer {
	public static final String MOD_ID = "twitchcraft";
	public static final Logger LOGGER = LoggerFactory.getLogger("TwitchCraft");

	private static TwitchCraftClient instance;

	private volatile ModConfig config;
	private TokenStore tokens;
	private TwitchApi api;
	private EventSubClient eventSub;
	private EventProcessor events;
	private RewardManager rewards;
	private ChatSender chatSender;
	private GoalTracker goalTracker;
	private FundraiserTracker fundraisers;
	private DonationStore donationStore;
	private DonationManager donations;
	private VkStore vkStore;
	private VkLive vk;
	private GameStats gameStats;
	private GameEvents game;
	private ChatTimers timers;
	private StreamStatus streamStatus;
	private ClipManager clips;

	/** Фоновый поток для сетевых запросов (чтобы не подвешивать игру). */
	private ExecutorService worker;
	/** Планировщик для периодических задач (проверка токена, переподключение). */
	private ScheduledExecutorService scheduler;

	private volatile boolean loginInProgress;
	/** Код устройства текущей авторизации (для экрана настроек). */
	private volatile TwitchAuth.DeviceCode pendingDevice;
	private volatile boolean overlayVisible;
	/** Игрок сам отключился — не переподключаться автоматически при входе в мир. */
	private volatile boolean manuallyDisconnected;
	private boolean hintShown;
	private boolean configWarningsShown;
	private boolean scopeHintShown;
	private int tokenCheckFailures;

	public static TwitchCraftClient get() {
		return instance;
	}

	@Override
	public void onInitializeClient() {
		instance = this;
		worker = Executors.newSingleThreadExecutor(r -> daemon(r, "TwitchCraft-Worker"));
		scheduler = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "TwitchCraft-Scheduler"));

		config = ModConfig.load();
		tokens = TokenStore.load();
		api = new TwitchApi(this);
		eventSub = new EventSubClient(this);
		goalTracker = new GoalTracker(this);
		fundraisers = new FundraiserTracker(this);
		events = new EventProcessor(this);
		rewards = new RewardManager(this);
		chatSender = new ChatSender(this);
		donationStore = DonationStore.load();
		donations = new DonationManager(this);
		vkStore = VkStore.load();
		vk = new VkLive(this);
		gameStats = GameStats.load();
		gameStats.setPersist(config.gameEventsSettings == null || config.gameEventsSettings.persistStats);
		game = new GameEvents(this, gameStats);
		timers = new ChatTimers(this);
		streamStatus = new StreamStatus(this);
		clips = new ClipManager(this);
		overlayVisible = true;

		TwitchCommands.register(this);
		Hotkeys.register(this);
		OverlayHud.register(this);
		dev.dedworkshop.twitchcraft.ui.FundraiserBar.register(this);
		game.register();

		// Каждый тик продвигаем очередь событий и команд (нужно для пауз "delay N")
		ClientTickEvents.END_CLIENT_TICK.register(this::safeTick);
		// При входе в мир — подсказка или автоподключение
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onJoinWorld());
		// При выходе из игры — аккуратно всё закрываем
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> shutdown());

		// Раз в 30 минут проверяем токен (Twitch требует делать это не реже раза в час)
		scheduler.scheduleAtFixedRate(this::periodicTokenCheck, 30, 30, TimeUnit.MINUTES);
		// Раз в минуту — в эфире ли стрим (для {stream_time}, клипов и меток)
		scheduler.scheduleAtFixedRate(() -> {
			try {
				streamStatus.poll(false);
			} catch (Exception e) {
				LOGGER.debug("Проверка стрима: {}", e.toString());
			}
		}, 20, 60, TimeUnit.SECONDS);

		// Аддоны (отдельные моды, например «Артефакты») подключаются последними:
		// к этому моменту готовы конфиг, чат, команды и планировщик.
		AddonManager.loadAll(this);

		LOGGER.info("TwitchCraft загружен. Введи /twitch в игре для настройки.");
	}

	private long lastTickErrorAt;

	/**
	 * Ошибка внутри мода не должна ронять игру: ловим всё, пишем в лог,
	 * а в чат — не чаще раза в 10 секунд.
	 */
	private void safeTick(Minecraft client) {
		try {
			events.tick(client);
			game.tick(client);
			timers.tick();
			// Аддоны (свой try/catch внутри — ошибка аддона не ломает мод и игру)
			AddonManager.tick(client);
		} catch (Exception e) {
			LOGGER.error("Ошибка в тике TwitchCraft (игра продолжает работать)", e);
			long now = System.currentTimeMillis();
			if (now - lastTickErrorAt > 10_000) {
				lastTickErrorAt = now;
				Chat.error("Внутренняя ошибка мода: " + e.getClass().getSimpleName() + " — подробности в logs/latest.log");
			}
		}
	}

	private static Thread daemon(Runnable r, String name) {
		Thread thread = new Thread(r, name);
		thread.setDaemon(true);
		return thread;
	}

	// ---------- Геттеры для других классов ----------

	public ModConfig config() {
		return config;
	}

	public TokenStore tokens() {
		return tokens;
	}

	public TwitchApi api() {
		return api;
	}

	public EventSubClient eventSub() {
		return eventSub;
	}

	public EventProcessor events() {
		return events;
	}

	public RewardManager rewards() {
		return rewards;
	}

	public ChatSender chatSender() {
		return chatSender;
	}

	public GoalTracker goalTracker() {
		return goalTracker;
	}

	public FundraiserTracker fundraisers() {
		return fundraisers;
	}

	public DonationStore donationStore() {
		return donationStore;
	}

	public DonationManager donations() {
		return donations;
	}

	public VkStore vkStore() {
		return vkStore;
	}

	public VkLive vk() {
		return vk;
	}

	public GameEvents game() {
		return game;
	}

	public GameStats gameStats() {
		return gameStats;
	}

	public ChatTimers timers() {
		return timers;
	}

	public StreamStatus streamStatus() {
		return streamStatus;
	}

	public ClipManager clips() {
		return clips;
	}

	/**
	 * Плейсхолдеры, не зависящие от события: {session_*}, {deaths} {deaths_total} {session_time},
	 * {stream_time} {viewers}, {fund_*}, {donation_prices_*} {donation_currency}, {player}.
	 */
	public java.util.Map<String, String> globalPlaceholders() {
		java.util.Map<String, String> vars = new java.util.LinkedHashMap<>();
		Minecraft mc = Minecraft.getInstance();
		vars.put("player", mc != null && mc.player != null ? mc.player.getName().getString() : "");
		if (events != null) {
			vars.putAll(events.stats().placeholders());
		}
		if (fundraisers != null) {
			vars.putAll(fundraisers.placeholders());
		}
		if (gameStats != null) {
			vars.putAll(gameStats.placeholders());
		}
		if (streamStatus != null) {
			vars.putAll(streamStatus.placeholders());
		}
		ModConfig cfg = config();
		if (cfg != null) {
			vars.putAll(DonationPresets.placeholders(cfg.donationTiers));
			vars.put("donation_currency", TwitchEvent.currencySymbol(cfg.donations == null ? "" : cfg.donations.currency));
		}
		return vars;
	}

	/**
	 * Сообщение от мода в чаты площадок (события игры, таймеры, ссылки на клипы) — в обход модуля
	 * «Ответы в чат», но с теми же очередями и проверками прав.
	 */
	public void announce(String text, boolean toTwitch, boolean toVk, boolean verbose) {
		if (text == null || text.isBlank()) {
			return;
		}
		if (toTwitch && chatSender != null) {
			chatSender.send(text, verbose);
		}
		if (toVk && vk != null && isModuleEnabled(Module.VK_VIDEO_LIVE)) {
			vk.send(text, verbose);
		}
	}

	/**
	 * Автоответ зрителю туда, откуда пришло событие: в чат VK Video Live для событий VK, иначе — в чат Twitch.
	 * Учитывает модуль «Ответы в чат» (и флаг replies раздела vk).
	 */
	public void reply(TwitchEvent event, String text) {
		if (event != null && event.isGame()) {
			// События игры объявляются в оба чата (настройки раздела gameEventsSettings)
			ModConfig.GameEventsSettings settings = config.gameEventsSettings == null ? new ModConfig.GameEventsSettings() : config.gameEventsSettings;
			announce(text, settings.toTwitch, settings.toVk, false);
		} else if (event != null && event.isVk()) {
			vk.reply(text);
		} else {
			chatSender.reply(text);
		}
	}

	public ExecutorService worker() {
		return worker;
	}

	public ScheduledExecutorService scheduler() {
		return scheduler;
	}

	public boolean isLoginInProgress() {
		return loginInProgress;
	}

	/** Код, который сейчас нужно ввести на twitch.tv/activate, или null. */
	public TwitchAuth.DeviceCode pendingDeviceCode() {
		return loginInProgress ? pendingDevice : null;
	}

	public boolean isOverlayVisible() {
		return overlayVisible && config.isEnabled(Module.OVERLAY);
	}

	public boolean isModuleEnabled(Module module) {
		ModConfig cfg = config();
		return cfg != null && cfg.isEnabled(module);
	}

	/**
	 * Включает/выключает модуль, сохраняет конфиг и применяет изменение на лету.
	 *
	 * @return true, если состояние изменилось
	 */
	public boolean setModuleEnabled(Module module, boolean enabled) {
		if (config.isEnabled(module) == enabled) {
			return false;
		}
		config.setEnabled(module, enabled);
		config.save();
		applyModuleChange(module, enabled, true);
		return true;
	}

	/** Применяет переключение модуля (переподключение к EventSub, если изменился список подписок). */
	public void applyModuleChange(Module module, boolean enabled, boolean announce) {
		if (announce) {
			Chat.info("Модуль «" + module.title + "» " + (enabled ? "§aвключён" : "§7выключен"));
		}
		if (module == Module.OVERLAY) {
			overlayVisible = true;
		}
		if (module.isDonationService() && donations != null) {
			donations.onModuleChanged(module, enabled);
			return;
		}
		if (module == Module.VK_VIDEO_LIVE && vk != null) {
			vk.onModuleChanged(enabled);
			return;
		}
		resubscribeIfNeeded();
	}

	/**
	 * Заменяет конфиг (после сохранения из экрана настроек): сохраняет файл,
	 * переподключается при необходимости.
	 */
	public void applyConfig(ModConfig updated) {
		updated.normalize();
		config = updated;
		config.save();
		resubscribeIfNeeded();
		if (donations != null) {
			donations.syncWithConfig();
		}
		if (vk != null) {
			vk.syncWithConfig();
		}
		if (timers != null) {
			timers.syncWithConfig();
		}
		if (gameStats != null) {
			gameStats.setPersist(config.gameEventsSettings == null || config.gameEventsSettings.persistStats);
		}
	}

	/** Конфиг изменён из экрана настроек (награды, события, цели): сохранить и применить. */
	public void configEdited() {
		config.save();
		resubscribeIfNeeded();
		if (timers != null) {
			timers.syncWithConfig();
		}
	}

	private void resubscribeIfNeeded() {
		if (eventSub.needsResubscribe()) {
			Chat.info("§7Набор событий изменился — переподключаюсь к Twitch...");
			reconnect();
		}
	}

	// ---------- События игры ----------

	private void onJoinWorld() {
		showConfigWarnings();
		game.onJoinWorld();
		donations.autoConnect();
		vk.autoConnect();
		if (config.clientId == null || config.clientId.isBlank()) {
			if (!hintShown) {
				hintShown = true;
				Chat.info("Мод не настроен. Введи §e/twitch§r, чтобы увидеть инструкцию.");
			}
			return;
		}
		if (!tokens.hasTokens()) {
			if (!hintShown) {
				hintShown = true;
				Chat.info("Ты не авторизован в Twitch. Введи §e/twitch login§r.");
			}
			return;
		}
		if (config.autoConnect && !manuallyDisconnected && !eventSub.isActive()) {
			connect(false);
		}
	}

	/** Показывает проблемы конфига один раз после загрузки. */
	private void showConfigWarnings() {
		if (configWarningsShown) {
			return;
		}
		configWarningsShown = true;
		if (config.loadError != null) {
			Chat.error("Файл config/twitchcraft.json не прочитан: " + config.loadError);
			Chat.error("Работают настройки по умолчанию. Исправь файл и введи §e/twitch reload");
			return;
		}
		List<String> warnings = config.warnings;
		if (warnings == null || warnings.isEmpty()) {
			return;
		}
		Chat.warn("В конфиге найдены возможные ошибки (" + warnings.size() + "):");
		for (int i = 0; i < Math.min(5, warnings.size()); i++) {
			Chat.warn("  • " + warnings.get(i));
		}
		if (warnings.size() > 5) {
			Chat.warn("  ...и ещё " + (warnings.size() - 5) + " (см. лог игры)");
		}
		for (String warning : warnings) {
			LOGGER.warn("Конфиг: {}", warning);
		}
	}

	/** Сюда EventSubClient передаёт каждое событие (из сетевого потока). */
	public void onTwitchEvent(TwitchEvent event) {
		if (event.type() == TwitchEvent.Type.CHAT) {
			LOGGER.debug("Чат {}: {}", event.platformTitle(), event.shortText());
		} else {
			LOGGER.info("Событие {}: {}", event.platformTitle(), event.shortText());
		}
		Minecraft.getInstance().execute(() -> {
			try {
				events.handle(event);
				// Аддоны получают каждое событие (даже то, что мод отбросил: например, «выдача артефакта»
				// зрителю важна и без действия в игре)
				AddonManager.dispatchEvent(event);
			} catch (Exception e) {
				LOGGER.error("Ошибка обработки события {} (игра продолжает работать)", event.shortText(), e);
				Chat.error("Не удалось обработать событие " + event.type() + ": " + e.getClass().getSimpleName() + " — подробности в logs/latest.log");
			}
		});
	}

	private void shutdown() {
		AddonManager.shutdown();
		eventSub.disconnect();
		donations.shutdown();
		vk.shutdown();
		worker.shutdownNow();
		scheduler.shutdownNow();
	}

	// ---------- Действия пользователя (вызываются из команд и клавиш) ----------

	public void setClientId(String clientId) {
		config.clientId = clientId.trim();
		config.save();
		Chat.success("Client ID сохранён. Теперь введи §e/twitch login");
	}

	public void reloadConfig() {
		config = ModConfig.load();
		configWarningsShown = false;
		if (config.loadError != null) {
			showConfigWarnings();
			return;
		}
		Chat.success("Конфиг перезагружен: наград — " + config.rewards.size()
				+ ", порогов битсов — " + config.cheer.size()
				+ ", эффектов донатов — " + config.donationTiers.size()
				+ ", чат-команд — " + config.chatCommands.size()
				+ ", целей — " + config.goals.size()
				+ ", сборов — " + config.fundraisers.size());
		showConfigWarnings();
		resubscribeIfNeeded();
		if (donations != null) {
			donations.syncWithConfig(); // модули донатов могли выключить/включить прямо в файле
		}
		if (vk != null) {
			vk.syncWithConfig();
		}
		AddonManager.configChanged();
	}

	public void setPaused(boolean paused) {
		boolean was = events.isPaused();
		events.setPaused(paused);
		if (paused && !was) {
			Chat.warn("Пауза: события копятся в очереди (F8 или /twitch resume — продолжить).");
		} else if (!paused && was) {
			int queued = events.queueSize();
			Chat.success("Продолжаем." + (queued > 0 ? " В очереди " + queued + " событий — выполняю по одному." : ""));
		}
	}

	public void togglePause() {
		setPaused(!events.isPaused());
	}

	public void toggleOverlay() {
		if (!config.isEnabled(Module.OVERLAY)) {
			Chat.warn("Модуль «Оверлей» выключен. Включить: §e/twitch module overlay on");
			return;
		}
		overlayVisible = !overlayVisible;
		Chat.info("Оверлей " + (overlayVisible ? "§aвключён" : "§7выключен") + " §7(F7 или /twitch overlay)");
	}

	public void replayLast() {
		if (events.lastEvent() == null) {
			Chat.warn("Повторять нечего — событий ещё не было.");
			return;
		}
		if (!events.replayLast()) {
			Chat.warn("Повтор возможен только в мире.");
			return;
		}
		Chat.info("§7Повторяю: " + events.lastEvent().shortText());
	}

	/** Запускает авторизацию через Device Code Flow. */
	public void login() {
		if (config.clientId == null || config.clientId.isBlank()) {
			Chat.error("Сначала укажи Client ID: §e/twitch setup <clientId>§c. Инструкция: §e/twitch");
			return;
		}
		if (loginInProgress) {
			Chat.warn("Авторизация уже идёт. Введи код на twitch.tv/activate.");
			return;
		}
		loginInProgress = true;
		Chat.info("Запрашиваю код у Twitch...");

		// Отдельный поток: ожидание подтверждения может длиться до 30 минут,
		// и оно не должно блокировать остальные сетевые задачи мода.
		Thread loginThread = daemon(() -> {
			try {
				TwitchAuth.DeviceCode device = TwitchAuth.requestDeviceCode(config.clientId);
				pendingDevice = device;
				showDeviceCode(device);

				long deadline = System.currentTimeMillis() + device.expiresInSeconds() * 1000L;
				int interval = Math.max(device.intervalSeconds(), 1);
				int networkErrors = 0;

				while (loginInProgress && System.currentTimeMillis() < deadline) {
					Thread.sleep(interval * 1000L);
					TwitchAuth.PollStatus status;
					try {
						status = TwitchAuth.poll(config.clientId, device.deviceCode(), tokens);
						networkErrors = 0;
					} catch (IOException e) {
						// Кратковременные проблемы с сетью не должны срывать вход
						networkErrors++;
						LOGGER.warn("Ошибка сети при ожидании подтверждения ({}/5): {}", networkErrors, e.toString());
						if (networkErrors >= 5) {
							throw e;
						}
						continue;
					}
					switch (status.kind()) {
						case SUCCESS -> {
							Chat.success("Авторизация прошла успешно!");
							afterLogin();
							return;
						}
						case PENDING -> {
							// ждём дальше
						}
						case SLOW_DOWN -> interval += 5;
						case DENIED -> {
							Chat.error("Ты отклонил доступ. Если передумаешь — /twitch login");
							return;
						}
						case EXPIRED -> {
							Chat.error("Код истёк. Введи /twitch login ещё раз.");
							return;
						}
						case ERROR -> {
							Chat.error("Ошибка авторизации: " + status.message());
							return;
						}
					}
				}
				if (loginInProgress) {
					Chat.error("Время ожидания вышло. Введи /twitch login ещё раз.");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				LOGGER.error("Ошибка авторизации", e);
				Chat.error("Ошибка авторизации: " + e.getMessage());
			} finally {
				loginInProgress = false;
				pendingDevice = null;
			}
		}, "TwitchCraft-Login");
		loginThread.start();
	}

	private void showDeviceCode(TwitchAuth.DeviceCode device) {
		MutableComponent link = Component.literal("[Открыть twitch.tv/activate]")
				.withStyle(style -> style
						.withColor(ChatFormatting.AQUA)
						.withUnderlined(true)
						.withClickEvent(new ClickEvent.OpenUrl(URI.create(device.verificationUri())))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal("Открыть страницу в браузере"))));

		MutableComponent code = Component.literal(device.userCode())
				.withStyle(style -> style
						.withColor(ChatFormatting.GOLD)
						.withBold(true)
						.withClickEvent(new ClickEvent.CopyToClipboard(device.userCode()))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal("Нажми, чтобы скопировать код"))));

		Chat.send(Component.literal(Chat.PREFIX + "Шаг 1: ").append(link));
		Chat.send(Component.literal(Chat.PREFIX + "Шаг 2: введи код ").append(code)
				.append(Component.literal(" §7(клик — скопировать)")));
		Chat.info("Шаг 3: нажми «Активировать» и вернись в игру. Жду подтверждения...");
	}

	/** После успешного входа: узнаём, кто мы, и подключаемся. */
	private void afterLogin() throws Exception {
		TwitchApi.User user = api.fetchCurrentUser();
		tokens.userId = user.id();
		tokens.login = user.login();
		tokens.displayName = user.displayName();
		tokens.save();
		manuallyDisconnected = false;
		Chat.success("Вошли как §d" + user.displayName() + "§a. Подключаюсь к событиям...");
		eventSub.connect();
	}

	/** Прерывает ожидание подтверждения кода. */
	public void cancelLogin() {
		if (loginInProgress) {
			loginInProgress = false;
			pendingDevice = null;
			Chat.info("Авторизация отменена.");
		}
	}

	public void logout() {
		loginInProgress = false;
		eventSub.disconnect();
		tokens.clear();
		Chat.success("Токены удалены, соединение закрыто.");
	}

	/** Подключение к EventSub (с проверкой токена). */
	public void connect(boolean verbose) {
		if (!tokens.hasTokens()) {
			Chat.error("Сначала авторизуйся: §e/twitch login");
			return;
		}
		if (eventSub.isActive()) {
			if (verbose) {
				Chat.warn("Уже подключено. Статус: /twitch status");
			}
			return;
		}
		manuallyDisconnected = false;
		if (verbose) {
			Chat.info("Подключаюсь к Twitch...");
		}
		worker.execute(() -> {
			try {
				if (!ensureValidToken(true)) {
					return;
				}
				if (tokens.userId.isBlank() || tokens.displayName.isBlank()) {
					TwitchApi.User user = api.fetchCurrentUser();
					tokens.userId = user.id();
					tokens.login = user.login();
					tokens.displayName = user.displayName();
					tokens.save();
				}
				eventSub.connect();
				streamStatus.poll(true);
			} catch (Exception e) {
				LOGGER.error("Ошибка подключения", e);
				Chat.error("Ошибка подключения: " + e.getMessage());
			}
		});
	}

	public void disconnect() {
		manuallyDisconnected = true;
		eventSub.disconnect();
		Chat.info("Отключено от Twitch. Автоподключение выключено до §e/twitch connect§r.");
	}

	/** Переподключение (например, после изменения настроек чата). */
	public void reconnect() {
		eventSub.disconnect();
		connect(false);
	}

	/**
	 * Проверяет токен и при необходимости обновляет его.
	 *
	 * @return true, если токен годный.
	 */
	private final Object tokenLock = new Object();

	private boolean ensureValidToken(boolean verbose) throws Exception {
		// Проверка+обновление под замком: иначе таймер (раз в 30 мин), подключение и ответ 401 из API
		// могли обновлять токен одновременно, а refresh-токен Twitch одноразовый.
		synchronized (tokenLock) {
			return ensureValidTokenLocked(verbose);
		}
	}

	private boolean ensureValidTokenLocked(boolean verbose) throws Exception {
		String checked = tokens.accessToken;
		TwitchAuth.Validation validation = TwitchAuth.validate(checked);
		if (validation == null || validation.expiresInSeconds() < 600) {
			LOGGER.info("Токен недействителен или скоро истечёт — обновляю");
			if (!TwitchAuth.refreshIfStale(config.clientId, tokens, checked)) {
				if (verbose) {
					Chat.error("Токен Twitch недействителен. Выполни §e/twitch login");
				}
				return false;
			}
			validation = TwitchAuth.validate(tokens.accessToken);
			if (validation == null) {
				if (verbose) {
					Chat.error("Не удалось обновить токен. Выполни §e/twitch login");
				}
				return false;
			}
		}
		if (!validation.clientId().isEmpty() && !validation.clientId().equals(config.clientId)) {
			if (verbose) {
				Chat.error("Токен выдан другому Client ID. Выполни §e/twitch logout§c, затем §e/twitch login");
			}
			return false;
		}
		if (!validation.hasRequiredScopes()) {
			if (verbose) {
				Chat.error("У токена не хватает прав. Выполни §e/twitch logout§c, затем §e/twitch login");
			}
			return false;
		}
		tokens.userId = validation.userId();
		tokens.login = validation.login();
		tokens.scopes = validation.scopes();
		tokens.expiresAt = System.currentTimeMillis() + validation.expiresInSeconds() * 1000L;
		tokens.save();

		List<String> missing = validation.missingOptionalScopes();
		if (!missing.isEmpty() && !scopeHintShown) {
			scopeHintShown = true;
			StringBuilder names = new StringBuilder();
			for (String scope : missing) {
				names.append(names.length() > 0 ? ", " : "").append(TwitchAuth.describeScope(scope));
			}
			Chat.warn("Токен выдан старой версией мода: недоступны " + names
					+ ". Чтобы включить — §e/twitch logout§e, затем §e/twitch login§e.");
		}
		return true;
	}

	private void periodicTokenCheck() {
		if (!tokens.hasTokens()) {
			return;
		}
		try {
			if (ensureValidToken(false)) {
				tokenCheckFailures = 0;
			} else {
				Chat.error("Токен Twitch больше недействителен — события перестанут приходить. Выполни §e/twitch login");
			}
		} catch (Exception e) {
			tokenCheckFailures++;
			LOGGER.warn("Периодическая проверка токена не удалась ({}): {}", tokenCheckFailures, e.toString());
			if (tokenCheckFailures >= 3) {
				tokenCheckFailures = 0;
				Chat.warn("Не удаётся проверить токен Twitch (нет сети?). Если события не приходят — /twitch connect");
			}
		}
	}
}
