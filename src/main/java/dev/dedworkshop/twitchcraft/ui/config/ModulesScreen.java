package dev.dedworkshop.twitchcraft.ui.config;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;
import dev.dedworkshop.twitchcraft.module.Module;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.Screen;

/**
 * Включение и выключение модулей. Изменения применяются сразу
 * (сохраняются в конфиг; при необходимости мод переподключается к Twitch).
 */
class ModulesScreen extends BaseScreen {
	ModulesScreen(TwitchCraftClient mod, Screen parent) {
		super(mod, parent, "Модули");
	}

	@Override
	protected void buildContent(LinearLayout content) {
		content.addChild(Widgets.gray(font, "Выключенный модуль не подписывается на события и ничего не делает"));
		Module.Kind kind = null;
		for (Module module : Module.values()) {
			if (module.kind != kind) {
				kind = module.kind;
				content.addChild(SpacerElement.height(4));
				content.addChild(Widgets.header(font, kind.title));
			}
			String tooltip = module.description;
			if (module.scope != null) {
				boolean has = !mod.tokens().hasTokens() || module.missingScope(mod.tokens()) == null;
				tooltip += "\nПраво Twitch: " + module.scope + (has ? "" : " — НЕ ВЫДАНО (выйди и войди заново)");
			}
			if (module.affectsSubscriptions()) {
				tooltip += "\nПри переключении мод переподключится к Twitch";
			}
			content.addChild(Widgets.toggle(module.title, mod.isModuleEnabled(module),
					value -> mod.setModuleEnabled(module, value), Widgets.FULL, tooltip));
		}
	}
}
