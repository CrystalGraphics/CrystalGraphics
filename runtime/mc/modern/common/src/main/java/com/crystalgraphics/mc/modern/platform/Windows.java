package com.crystalgraphics.mc.modern.platform;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;

/**
 * Minecraft's window, however this version reaches it.
 *
 * <pre>{@code
 * int width = Windows.of(Minecraft.getInstance()).getWidth();
 * long glfw = Windows.handle(Minecraft.getInstance());
 * }</pre>
 *
 * <p>{@code getWindow()} arrived in 1.15; 1.14 has the field.</p>
 */
public final class Windows {

    private Windows() {}

    public static Window of(Minecraft mc) {
        //? if >=1.15 {
        return mc.getWindow();
        //?} else {
        /*return mc.window;
        *///?}
    }

    /** The GLFW handle. */
    public static long handle(Minecraft mc) {
        //? if >=1.15 {
        return mc.getWindow().getWindow();
        //?} else {
        /*return mc.window.getWindow();
        *///?}
    }
}
