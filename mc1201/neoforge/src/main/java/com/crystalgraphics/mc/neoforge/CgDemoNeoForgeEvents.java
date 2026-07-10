package com.crystalgraphics.mc.neoforge;

import com.crystalgraphics.demo.CgFontDemo;
import com.crystalgraphics.demo.CgRenderDemo;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Temporary demo event subscriptions for the NeoForge loader.
 * All hooks here drive {@link CgFontDemo} and will be removed when the demo is replaced.
 */
public final class CgDemoNeoForgeEvents {
    private CgDemoNeoForgeEvents() {}

    /** Called once from {@link CrystalGraphics1201NeoForge} constructor. */
    static void register() {
        NeoForge.EVENT_BUS.addListener(CgDemoNeoForgeEvents::onRenderGui);
        NeoForge.EVENT_BUS.addListener(CgDemoNeoForgeEvents::onMouseScroll);
    }

    private static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        CgFontDemo.INSTANCE.render(mc.getWindow().getWidth(), mc.getWindow().getHeight());
    }

    private static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        CgFontDemo.INSTANCE.onMouseWheel((int) (event.getScrollDeltaY() * 120));
        CgRenderDemo.INSTANCE.onMouseWheel((int) (event.getScrollDeltaY() * 120));
    }
}
