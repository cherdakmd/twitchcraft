package dev.dedworkshop.twitchcraft.api;

/**
 * Действие (флоу) аддона: триггер + элементы. Пара «триггер → элементы» — это ровно то,
 * что в схеме TikFinity называется объектом награды/действия
 * ({@code { "trigger": { "trigger": "redemption", "params": {…} }, "actions": […] }}).
 *
 * <pre>
 * context.registerAction(AddonAction.of("artifact_give", "Выдать артефакт", AddonTrigger.redemption("f0a1-…"))
 *         .elements(AddonElements.builder().message("§d{user}§r выбил артефакт!").build()));
 * </pre>
 *
 * <p>Мод сам решает, когда сработал триггер, и сам выполняет элементы — в основном потоке игры,
 * с подстановкой плейсхолдеров. Аддон при этом не обязан ничего делать в {@code onEvent}.</p>
 */
public final class AddonAction {
	private final String id;
	private final String title;
	private final AddonTrigger trigger;
	private final AddonElements elements;

	private AddonAction(String id, String title, AddonTrigger trigger, AddonElements elements) {
		this.id = id;
		this.title = title == null || title.isBlank() ? id : title;
		this.trigger = trigger;
		this.elements = elements;
	}

	/**
	 * @param id      идентификатор действия (латиницей, уникальный у аддона) — по нему его видно в {@code /twitch addons}
	 * @param title   название для людей
	 * @param trigger когда выполнять
	 */
	public static Builder of(String id, String title, AddonTrigger trigger) {
		return new Builder(id, title, trigger);
	}

	public String id() {
		return id;
	}

	public String title() {
		return title;
	}

	public AddonTrigger trigger() {
		return trigger;
	}

	public AddonElements elements() {
		return elements;
	}

	public static final class Builder {
		private final String id;
		private final String title;
		private final AddonTrigger trigger;
		private AddonElements elements = AddonElements.none();

		private Builder(String id, String title, AddonTrigger trigger) {
			this.id = id;
			this.title = title;
			this.trigger = trigger;
		}

		public Builder elements(AddonElements elements) {
			if (elements != null) {
				this.elements = elements;
			}
			return this;
		}

		public AddonAction build() {
			return new AddonAction(id, title, trigger, elements);
		}
	}
}
