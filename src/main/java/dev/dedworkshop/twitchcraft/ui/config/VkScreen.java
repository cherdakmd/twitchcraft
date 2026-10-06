package dev.dedworkshop.twitchcraft.ui.config;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.twitch.TwitchEvent;
import dev.dedworkshop.twitchcraft.util.Chat;
import dev.dedworkshop.twitchcraft.vk.VkLive;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.net.URI;

/**
 * VK Video Live: приложение, вход, канал, подключение и флаги (чат, команды, награды, фолловы, ответы).
 * Настройки правятся в черновике и применяются кнопкой «Сохранить»; вход/подключение действуют сразу.
 */
class VkScreen extends BaseScreen {
	private static final String APPS_URL = "https://dev.live.vkvideo.ru/apps";

	private final ModConfig.Vk draft = new ModConfig.Vk();
	private String secret = "";
	private String signature = "";
	private int ticks;

	VkScreen(TwitchCraftClient mod, Screen parent) {
		super(mod, parent, "VK Video Live");
		ModConfig.Vk current = mod.config().vk;
		draft.channelUrl = current.channelUrl;
		draft.clientId = current.clientId;
		draft.callbackPort = current.callbackPort;
		draft.showChat = current.showChat;
		draft.chatPrefix = current.chatPrefix;
		draft.chatCommands = current.chatCommands;
		draft.rewards = current.rewards;
		draft.follows = current.follows;
		draft.replies = current.replies;
		draft.manageDemands = current.manageDemands;
		draft.debugEvents = current.debugEvents;
	}

	@Override
	protected void buildContent(LinearLayout content) {
		signature = signature();
		VkLive vk = mod.vk();

		String last = Chat.lastText();
		if (!last.isEmpty() && System.currentTimeMillis() - Chat.lastTime() < 120_000) {
			content.addChild(Widgets.clipped(font, Component.literal("» " + last).withStyle(ChatFormatting.YELLOW), Widgets.FULL));
			content.addChild(SpacerElement.height(2));
		}

		content.addChild(Widgets.header(font, "Подключение" + (mod.isModuleEnabled(Module.VK_VIDEO_LIVE) ? "" : " (модуль выключен)")));
		content.addChild(Widgets.clipped(font, Component.literal("Статус: ").withStyle(ChatFormatting.GRAY).append(Component.literal(vk.statusText())), Widgets.FULL));
		content.addChild(Widgets.gray(font, "Чат, команды, награды за баллы и фолловы с live.vkvideo.ru работают через те же действия, что и Twitch"));

		// ---------- Приложение и вход
		section(content, "Приложение (dev.live.vkvideo.ru → Приложения)");
		GridLayout app = Widgets.form();
		int r = 0;
		row(app, r++, "ID приложения", Widgets.textField(font, Widgets.FIELD, draft.clientId, v -> draft.clientId = v.trim(), "client_id из карточки приложения"));
		row(app, r++, "Секрет", Widgets.textField(font, Widgets.FIELD, secret, v -> secret = v.trim(),
				mod.vkStore().hasSecret() ? "секрет сохранён — введи новый, чтобы заменить" : "секретный ключ приложения"));
		row(app, r++, "Порт входа", Widgets.intField(font, Widgets.FIELD, draft.callbackPort, 1024, 65535, v -> draft.callbackPort = v));
		row(app, r++, "Канал", Widgets.textField(font, Widgets.FIELD, draft.channelUrl, v -> draft.channelUrl = v.trim(),
				mod.vkStore().ownChannelUrl.isBlank() ? "ссылка или имя; пусто — свой канал после входа" : "пусто — свой канал: " + mod.vkStore().ownChannelUrl));
		content.addChild(app);
		content.addChild(Widgets.gray(font, "Redirect URI приложения: " + vk.redirectUri() + "  (сохрани настройки перед входом)"));
		GridLayout appButtons = new GridLayout().columnSpacing(10).rowSpacing(4);
		GridLayout.RowHelper appRows = appButtons.createRowHelper(2);
		appRows.addChild(Widgets.button("Открыть страницу приложений", Widgets.HALF,
				() -> ConfirmLinkScreen.confirmLinkNow(this, URI.create(APPS_URL)),
				"Создай приложение (вход через VK ID) и укажи Redirect URI"));
		appRows.addChild(Widgets.button("Скопировать Redirect URI", Widgets.HALF, () -> {
			minecraft.keyboardHandler.setClipboard(vk.redirectUri());
			Chat.info("Скопировано: " + vk.redirectUri());
		}));
		appRows.addChild(Widgets.button(loginLabel(), Widgets.HALF, this::loginOrLogout,
				"Вход через браузер: разреши доступ, код придёт в игру сам"));
		appRows.addChild(Widgets.button(vk.isActive() || vk.isConnecting() ? "Отключить VK" : "Подключить VK", Widgets.HALF, () -> {
			if (vk.isActive() || vk.isConnecting()) {
				vk.disconnect();
			} else {
				applyDraft();
				vk.connect(true);
			}
			refresh();
		}));
		appRows.addChild(Widgets.button("Создать награды на VK", Widgets.HALF, () -> {
			applyDraft();
			vk.syncRewards();
		}, "Создаёт на VK награды из раздела «Награды за баллы» (например, «Пакость» и «Подарок»), которых там ещё нет"));
		appRows.addChild(Widgets.button("Тест награды «Пакость»", Widgets.HALF, () -> {
			if (!inWorld()) {
				Chat.warn("Тест работает только в мире.");
				return;
			}
			mod.onTwitchEvent(VkLive.testEvent(TwitchEvent.Type.REWARD, "VkViewer", 250, "Привет из VK",
					dev.dedworkshop.twitchcraft.config.DonationPresets.REWARD_BAD));
		}, "Как /twitch vk test reward Пакость"));
		content.addChild(appButtons);

		// ---------- Что включено
		section(content, "Что брать с VK");
		GridLayout flags = Widgets.form();
		r = 0;
		row(flags, r++, "Чат в игре", Widgets.toggle("Показывать", draft.showChat, v -> draft.showChat = v, Widgets.FIELD,
				"Сообщения чата VK показываются в чате Minecraft с префиксом"));
		row(flags, r++, "Префикс чата", Widgets.textField(font, Widgets.FIELD, draft.chatPrefix, v -> draft.chatPrefix = v, "&9[VK]&r "));
		row(flags, r++, "Чат-команды", Widgets.toggle("Выполнять", draft.chatCommands, v -> draft.chatCommands = v, Widgets.FIELD,
				"Зрители VK могут писать !команды из раздела «Чат-команды»"));
		row(flags, r++, "Награды за баллы", Widgets.toggle("Обрабатывать", draft.rewards, v -> draft.rewards = v, Widgets.FIELD,
				"Запросы наград VK запускают действия из раздела «Награды за баллы» (совпадение по названию)"));
		row(flags, r++, "Фолловы", Widgets.toggle("Обрабатывать", draft.follows, v -> draft.follows = v, Widgets.FIELD,
				"Новые подписчики канала VK → действие follow"));
		row(flags, r++, "Ответы в чат VK", Widgets.toggle("Отправлять", draft.replies, v -> draft.replies = v, Widgets.FIELD,
				"reply, ответы про кулдаун и права — в чат VK (также нужен модуль «Ответы в чат»)"));
		row(flags, r++, "Статус наград", Widgets.toggle("Управлять", draft.manageDemands, v -> draft.manageDemands = v, Widgets.FIELD,
				"Подтверждать выполненные запросы наград и отклонять невыполненные (баллы вернутся зрителю)"));
		row(flags, r++, "Лог событий", Widgets.toggle("Подробный", draft.debugEvents, v -> draft.debugEvents = v, Widgets.FIELD,
				"Писать все события WebSocket VK в logs/latest.log (для диагностики)"));
		content.addChild(flags);
		content.addChild(SpacerElement.height(4));
		content.addChild(Widgets.gray(font, "Секрет и токены хранятся в config/twitchcraft-vk.json — не показывай его на стриме"));
		content.addChild(SpacerElement.height(6));
	}

	@Override
	protected void buildFooter(LinearLayout footer) {
		footer.addChild(Widgets.button("Сохранить", Widgets.HALF, this::save));
		footer.addChild(Widgets.button("Отмена", Widgets.HALF, this::onClose));
	}

	@Override
	public void tick() {
		super.tick();
		if (++ticks % 10 == 0 && !signature.equals(signature())) {
			refresh();
		}
	}

	private String signature() {
		VkLive vk = mod.vk();
		String last = System.currentTimeMillis() - Chat.lastTime() < 120_000 ? Chat.lastText() : "";
		return vk.isConfigured() + "|" + vk.isLoggedIn() + "|" + vk.isActive() + "|" + vk.isConnecting() + "|" + vk.isLoginInProgress()
				+ "|" + vk.statusText() + "|" + last;
	}

	private String loginLabel() {
		VkLive vk = mod.vk();
		if (vk.isLoginInProgress()) {
			return "Отменить вход";
		}
		return vk.isLoggedIn() ? "Выйти из VK" : "Войти в VK Video Live";
	}

	private void loginOrLogout() {
		VkLive vk = mod.vk();
		if (vk.isLoginInProgress()) {
			vk.cancelLogin();
			refresh();
			return;
		}
		if (vk.isLoggedIn()) {
			confirm("Выйти из VK Video Live?", "Токены будут удалены, подключение закрыто. Секрет приложения останется.", () -> {
				vk.logout();
				refresh();
			});
			return;
		}
		applyDraft(); // чтобы ID, секрет и порт из полей точно использовались для входа
		String url = vk.beginLogin();
		if (url != null) {
			ConfirmLinkScreen.confirmLinkNow(this, URI.create(url));
			refresh();
		}
	}

	private void applyDraft() {
		ModConfig.Vk target = mod.config().vk;
		target.channelUrl = draft.channelUrl;
		target.clientId = draft.clientId;
		target.callbackPort = draft.callbackPort;
		target.showChat = draft.showChat;
		target.chatPrefix = draft.chatPrefix;
		target.chatCommands = draft.chatCommands;
		target.rewards = draft.rewards;
		target.follows = draft.follows;
		target.replies = draft.replies;
		target.manageDemands = draft.manageDemands;
		target.debugEvents = draft.debugEvents;
		mod.config().normalize();
		if (!secret.isEmpty()) {
			mod.vkStore().clientSecret = secret;
			mod.vkStore().save();
			secret = "";
		}
		mod.configEdited();
		mod.vk().syncWithConfig();
	}

	private void save() {
		applyDraft();
		Chat.success("Настройки VK Video Live сохранены.");
		onClose();
	}

	private void section(LinearLayout content, String title) {
		content.addChild(SpacerElement.height(4));
		content.addChild(Widgets.header(font, title));
	}

	private void row(GridLayout grid, int row, String label, LayoutElement field) {
		grid.addChild(Widgets.label(font, label), row, 0, settings -> settings.alignVerticallyMiddle().alignHorizontallyLeft());
		grid.addChild(field, row, 1);
	}
}
