package net.minecraft.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;

/**
 * Заглушка для тестов: игра не запущена. По умолчанию getInstance() возвращает null;
 * тесты могут подставить свой экземпляр через INSTANCE (например, с «игроком в мире»).
 * Поля мира, шрифта и рендера по умолчанию null: код, который их читает, уходит в ветку «нет мира».
 * Типы полей совпадают с Minecraft 26.3, иначе обращение к полю в тестах дало бы NoSuchFieldError.
 */
public class Minecraft {
    public static Minecraft INSTANCE;
    public final net.minecraft.client.gui.Gui gui = null;
    public LocalPlayer player;
    public ClientLevel level;
    public Font font;
    public GameRenderer gameRenderer;
    public static Minecraft getInstance() { return INSTANCE; }
    public void execute(Runnable r) { r.run(); }
}
