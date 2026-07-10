package com.crystalgraphics.mc.fabric;

import com.crystalgraphics.demo.CgFontDemo;
import com.crystalgraphics.demo.CgRenderDemo;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWScrollCallback;

/**
 * Temporary demo event registrations for the Fabric loader.
 * All hooks here drive {@link CgFontDemo} and will be removed when the demo is replaced.
 */
final class CgDemoFabricEvents {
    private CgDemoFabricEvents() {}

    static void register() {
        registerHudRender();
        registerMouseScroll();
    }

    // ── HUD render ─────────────────────────────────────────────────────────────

    private static void registerHudRender() {
        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            Minecraft mc = Minecraft.getInstance();
            CgFontDemo.INSTANCE.render(mc.getWindow().getWidth(), mc.getWindow().getHeight());
        });
    }

    // ── Mouse scroll ───────────────────────────────────────────────────────────

    private static void registerMouseScroll() {
        // Fabric API has no global mouse-scroll event. Chain a GLFW scroll callback so we
        // receive the delta without replacing MC's own handler (MouseHandler.onScroll).
        // CLIENT_STARTED fires after the window is created, so getWindow() is safe here.
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            long window = client.getWindow().getWindow();
            final GLFWScrollCallback[] prev = { GLFW.glfwSetScrollCallback(window, null) };
            GLFW.glfwSetScrollCallback(window, (win, dx, dy) -> {
                CgFontDemo.INSTANCE.onMouseWheel((int) (dy * 120));
                CgRenderDemo.INSTANCE.onMouseWheel((int) (dy * 120));
                if (prev[0] != null) prev[0].invoke(win, dx, dy);
            });
        });
    }
}
