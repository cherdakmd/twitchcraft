package dev.dedworkshop.twitchcraft.ui.config;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.config.ModConfig;
import dev.dedworkshop.twitchcraft.module.Module;
import dev.dedworkshop.twitchcraft.twitch.ClipManager;
import dev.dedworkshop.twitchcraft.util.Chat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Клипы и метки стрима Twitch: что делать при смерти, донате, боссе; кулдауны; куда писать ссылку; ручные кнопки.
 */
class ClipsScreen extends BaseScreen {
	private boolean changed;

	ClipsScreen(TwitchCraftClient mod, Screen parent) {
		super(mod, parent, "Клипы и метки стрима");
	}

	@Override
	protected void buildContent(LinearLayout content) {
		ModConfig.Clips clips = mod.config().clips;
		ClipManager manager = mod.clips();
		if (!mod.isModuleEnabled(Module.CLIPS)) {
			content.addChild(new StringWidget(Component.literal("Модуль «Клипы и метки» выключен (включи в «Модули»)").withStyle(ChatFormatting.RED), font));
		}
		String last = Chat.lastText();
		if (!last.isEmpty() && System.currentTimeMillis() - Chat.lastTime() < 120_000) {
			content.addChild(Widgets.clipped(font, Component.literal("» " + last).withStyle(ChatFormatting.YELLOW), Widgets.FULL));
		}
		content.addChild(Widgets.clipped(font, Component.literal("Стрим: ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(mod.streamStatus().describe())), Widgets.FULL));
		content.addChild(Widgets.clipped(font, Component.literal("Права: клипы ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(manager.canClip() ? "есть" : "нет (clips:edit)").withStyle(manager.canClip() ? ChatFormatting.GREEN : ChatFormatting.RED))
				.append(Component.literal(", метки ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(manager.canMark() ? "есть" : "нет (channel:manage:broadcast)").withStyle(manager.canMark() ? ChatFormatting.GREEN : ChatFormatting.RED)),
				Widgets.FULL));
		if (!manager.canClip() || !manager.canMark()) {
			content.addChild(Widgets.gray(font, "Чтобы получить права: /twitch logout, затем /twitch login (вход заново)"));
		}
		content.addChild(Widgets.gray(font, "За сеанс: клипов " + manager.clipsCreated() + ", меток " + manager.markersCreated()
				+ (manager.lastError().isBlank() ? "" : " · ошибка: " + manager.lastError())));
		content.addChild(SpacerElement.height(2));

		GridLayout manual = new GridLayout().columnSpacing(10).rowSpacing(4);
		GridLayout.RowHelper rows = manual.createRowHelper(2);
		rows.addChild(Widgets.button("Сделать клип сейчас", Widgets.HALF, () -> {
			if (manager.clip("вручную", true)) {
				Chat.info("§7Запрос на клип отправлен — ссылка появится через ~15 секунд.");
			}
		}, "То же, что F10 или /twitch clip"));
		rows.addChild(Widgets.button("Поставить метку", Widgets.HALF, () -> manager.marker("Метка из настроек", true),
				"Метка видна в редакторе клипов и моментов на Twitch (/twitch marker)"));
		content.addChild(manual);

		content.addChild(SpacerElement.height(6));
		content.addChild(Widgets.header(font, "Когда срабатывать"));
		GridLayout form = Widgets.form();
		int r = 0;
		row(form, r++, "Смерть — метка", toggle("Делать", clips.markerOnDeath, v -> clips.markerOnDeath = v));
		row(form, r++, "Смерть — клип", toggle("Делать", clips.clipOnDeath, v -> clips.clipOnDeath = v));
		row(form, r++, "Донат от, " + currency(), Widgets.intField(font, Widgets.FIELD, clips.donationFrom, 0, 10000000, v -> {
			clips.donationFrom = v;
			changed = true;
		}));
		row(form, r++, "Донат — метка", toggle("Делать", clips.markerOnDonation, v -> clips.markerOnDonation = v));
		row(form, r++, "Донат — клип", toggle("Делать", clips.clipOnDonation, v -> clips.clipOnDonation = v));
		row(form, r++, "Босс — метка", toggle("Делать", clips.markerOnBoss, v -> clips.markerOnBoss = v));
		row(form, r++, "Босс — клип", toggle("Делать", clips.clipOnBoss, v -> clips.clipOnBoss = v));
		content.addChild(form);
		content.addChild(Widgets.gray(font, "Клип Twitch — последние ~30 секунд эфира; метка — точка на таймлайне записи (стрим должен идти)"));

		content.addChild(SpacerElement.height(6));
		content.addChild(Widgets.header(font, "Ссылка на клип и кулдауны"));
		GridLayout post = Widgets.form();
		r = 0;
		row(post, r++, "Ссылку в чат Twitch", toggle("Писать", clips.postClipToTwitch, v -> clips.postClipToTwitch = v));
		row(post, r++, "Ссылку в чат VK", toggle("Писать", clips.postClipToVk, v -> clips.postClipToVk = v));
		row(post, r++, "Ссылку в чат YouTube", toggle("Писать", clips.postClipToYoutube, v -> clips.postClipToYoutube = v));
		row(post, r++, "Текст сообщения", Widgets.textField(font, Widgets.FIELD, clips.clipChatText, v -> {
			clips.clipChatText = v;
			changed = true;
		}, "🎬 Клип: {clip_url}"));
		row(post, r++, "Кулдаун клипов, с", Widgets.intField(font, Widgets.FIELD, clips.clipCooldownSeconds, 0, 3600, v -> {
			clips.clipCooldownSeconds = v;
			changed = true;
		}));
		row(post, r++, "Кулдаун меток, с", Widgets.intField(font, Widgets.FIELD, clips.markerCooldownSeconds, 0, 3600, v -> {
			clips.markerCooldownSeconds = v;
			changed = true;
		}));
		content.addChild(post);
		content.addChild(Widgets.gray(font, "В тексте: {clip_url} — ссылка, {why} — повод (смерть, донат от Имя, босс...)"));
		content.addChild(SpacerElement.height(6));
	}

	@Override
	public void onClose() {
		if (changed) {
			mod.configEdited();
			changed = false;
		}
		super.onClose();
	}

	private net.minecraft.client.gui.components.CycleButton<Boolean> toggle(String label, boolean value, java.util.function.Consumer<Boolean> setter) {
		return Widgets.toggle(label, value, v -> {
			setter.accept(v);
			changed = true;
		}, Widgets.FIELD, null);
	}

	private String currency() {
		return dev.dedworkshop.twitchcraft.twitch.TwitchEvent.currencySymbol(mod.config().donations == null ? "" : mod.config().donations.currency);
	}

	private void row(GridLayout grid, int row, String label, LayoutElement field) {
		grid.addChild(Widgets.label(font, label), row, 0, settings -> settings.alignVerticallyMiddle().alignHorizontallyLeft());
		grid.addChild(field, row, 1);
	}
}
