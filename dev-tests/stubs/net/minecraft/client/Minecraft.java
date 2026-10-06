package net.minecraft.client;

import net.minecraft.client.player.LocalPlayer;

/**
 * Заглушка для тестов: игра не запущена. По умолчанию getInstance() возвращает null;
 * тесты могут подставить свой экземпляр через INSTANCE (например, с «игроком в мире»).
 */
public class Minecraft {
    public static Minecraft INSTANCE;
    public final net.minecraft.client.gui.Gui gui = null;
    public LocalPlayer player;
    public static Minecraft getInstance() { return INSTANCE; }
    public void execute(Runnable r) { r.run(); }
}
