package dev.dedworkshop.twitchcraft.api;

import dev.dedworkshop.twitchcraft.config.ModConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Что именно делает действие аддона (в терминах TikFinity — {@code actions} у триггера):
 * сообщение в чат игры, заголовок на экране, тост, звук, команды Minecraft и т.п.
 * Выполняет это <b>мод</b> — тем же кодом, что и действия из конфига TwitchCraft,
 * поэтому работают плейсхолдеры ({@code {user}}, {@code {amount}}, свои переменные аддона…)
 * и ограничения вроде {@code blockedCommands}.
 *
 * <p>Собирается по шагам:</p>
 * <pre>
 * AddonElements elements = AddonElements.builder()
 *         .message("§d{user}§r получает артефакт!")
 *         .sound("minecraft:entity.player.levelup")
 *         .command("say {user} выбил артефакт")
 *         .build();
 * </pre>
 */
public final class AddonElements {
	private String message = "";
	private String title = "";
	private String subtitle = "";
	private String actionbar = "";
	private String toast = "";
	private String toastText = "";
	private String sound = "";
	private float volume = 1.0f;
	private float pitch = 1.0f;
	private final List<String> commands = new ArrayList<>();
	private boolean randomOne;
	private int chance = 100;
	private String failMessage = "";
	private String repeat = "";
	private int repeatPer = 1;
	private int maxRepeat = 10;
	private int repeatDelay = 5;

	private AddonElements() {
	}

	public static AddonElements builder() {
		return new AddonElements();
	}

	/** Пустые элементы — ничего не делают (для проверок). */
	public static AddonElements none() {
		return new AddonElements();
	}

	/** Сообщение в чат игры (с плейсхолдерами). */
	public AddonElements message(String text) {
		this.message = text == null ? "" : text;
		return this;
	}

	/** Заголовок на экране ({@code title}) и подзаголовок. */
	public AddonElements title(String text, String subtitle) {
		this.title = text == null ? "" : text;
		this.subtitle = subtitle == null ? "" : subtitle;
		return this;
	}

	/** Текст над хотбаром. */
	public AddonElements actionbar(String text) {
		this.actionbar = text == null ? "" : text;
		return this;
	}

	/** Всплывающее уведомление (тост): заголовок и текст. */
	public AddonElements toast(String text, String subtitle) {
		this.toast = text == null ? "" : text;
		this.toastText = subtitle == null ? "" : subtitle;
		return this;
	}

	/** Клиентский звук: id, громкость, высота (слышно только стримеру). */
	public AddonElements sound(String soundId, float volume, float pitch) {
		this.sound = soundId == null ? "" : soundId;
		this.volume = volume;
		this.pitch = pitch;
		return this;
	}

	public AddonElements sound(String soundId) {
		return sound(soundId, 1.0f, 1.0f);
	}

	/** Команды Minecraft по очереди ({@code delay N} — пауза в тиках). */
	public AddonElements commands(String... commands) {
		if (commands != null) {
			for (String command : commands) {
				if (command != null && !command.isBlank()) {
					this.commands.add(command);
				}
			}
		}
		return this;
	}

	public AddonElements command(String command) {
		return commands(command);
	}

	/** Выполнить только одну случайную команду из списка. */
	public AddonElements randomOne() {
		this.randomOne = true;
		return this;
	}

	/** Шанс срабатывания 0–100 и сообщение при неудаче. */
	public AddonElements chance(int percent, String failMessage) {
		this.chance = Math.max(0, Math.min(100, percent));
		this.failMessage = failMessage == null ? "" : failMessage;
		return this;
	}

	/** Повтор команд: сколько раз (число или плейсхолдер, например {@code {amount}}). */
	public AddonElements repeat(String repeat, int per, int max, int delayTicks) {
		this.repeat = repeat == null ? "" : repeat;
		this.repeatPer = Math.max(1, per);
		this.maxRepeat = Math.max(1, max);
		this.repeatDelay = Math.max(0, delayTicks);
		return this;
	}

	/** Готово (шаги можно продолжать и после вызова — {@code build()} ничего не копирует). */
	public AddonElements build() {
		return this;
	}

	public boolean isEmpty() {
		return message.isBlank() && title.isBlank() && subtitle.isBlank() && actionbar.isBlank()
				&& toast.isBlank() && sound.isBlank() && commands.isEmpty();
	}

	public int chance() {
		return chance;
	}

	public String failMessage() {
		return failMessage;
	}

	/** Внутреннее: те же поля, что у действия из конфига, — чтобы выполнял мод. */
	public ModConfig.Action toConfigAction() {
		ModConfig.Action action = new ModConfig.Action();
		action.message = message;
		action.title = title;
		action.subtitle = subtitle;
		action.actionbar = actionbar;
		action.toast = toast;
		action.toastText = toastText;
		action.sound = sound;
		action.volume = volume;
		action.pitch = pitch;
		action.commands = new ArrayList<>(commands);
		action.randomOne = randomOne;
		action.chance = chance;
		action.failMessage = failMessage;
		action.repeat = repeat;
		action.repeatPer = repeatPer;
		action.maxRepeat = maxRepeat;
		action.repeatDelay = repeatDelay;
		return action;
	}
}
