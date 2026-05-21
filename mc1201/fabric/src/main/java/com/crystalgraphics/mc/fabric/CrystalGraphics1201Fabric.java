package com.crystalgraphics.mc.fabric;

import com.crystalgraphics.demo.CgFontDemo;
import com.crystalgraphics.mc.platform.PlatformService1201;
import com.crystalgraphics.platform.CgPlatform;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWScrollCallback;

import static com.crystalgraphics.mc.platform.CrystalGraphics1201.*;

public final class CrystalGraphics1201Fabric implements ClientModInitializer {
    private static final Logger LOGGER = LogManager.getLogger(NAME);

    @Override
    public void onInitializeClient() {
        CgPlatform.register(PlatformService1201.getInstance());
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                new SimpleSynchronousResourceReloadListener() {
                    public ResourceLocation getFabricId() {return new ResourceLocation(MODID, "asset_reload");}
                    public void onResourceManagerReload(ResourceManager manager) {CgPlatform.reload().onReload();}
                });

        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            Minecraft mc = Minecraft.getInstance();
            CgFontDemo.INSTANCE.render(mc.getWindow().getWidth(), mc.getWindow().getHeight());
        });

        // Fabric API has no global mouse-scroll event. Chain a GLFW scroll callback so we
        // receive the delta without replacing MC's own handler (MouseHandler.onScroll).
        // CLIENT_STARTED fires after the window is created, so getWindow() is safe here.
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            long window = client.getWindow().getWindow();
            final GLFWScrollCallback[] prev = { GLFW.glfwSetScrollCallback(window, null) };
            GLFW.glfwSetScrollCallback(window, (win, dx, dy) -> {
                CgFontDemo.INSTANCE.onMouseWheel((int) (dy * 120));
                if (prev[0] != null) prev[0].invoke(win, dx, dy);
            });
        });

        LOGGER.info("[CrystalGraphics] Fabric 1.20.1 platform registered");
    }
}
