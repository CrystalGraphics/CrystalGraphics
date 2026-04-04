package io.github.somehussar.crystalgraphics.mc.integration;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.font.CgFont;
import io.github.somehussar.crystalgraphics.api.font.CgFontStyle;
import io.github.somehussar.crystalgraphics.api.font.CgTextLayoutBuilder;
import io.github.somehussar.crystalgraphics.gl.text.CgFontRegistry;
import io.github.somehussar.crystalgraphics.gl.text.CgTextRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Mouse;

import java.io.File;
import java.nio.FloatBuffer;

public class CrystalGraphicsFontDemo {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphicsFontDemo");
    private static final String DEMO_TEXT = "CrystalGraphics font demo - mouse wheel zoom";
    private static final String DEMO_FONT_PATH = "X:\\projects\\CrystalGraphics\\src\\main\\resources\\assets\\crystalgraphics\\test-font.ttf";

    private boolean demoEnabled = true;
    private int demoFontSize = 24;
    private long demoFrame = 0L;
    private CgFont demoFont;
    private CgFontRegistry demoFontRegistry;
    private CgTextRenderer demoTextRenderer;
    private final CgTextLayoutBuilder demoLayoutBuilder = new CgTextLayoutBuilder();
    private final FloatBuffer demoProjectionMatrix = BufferUtils.createFloatBuffer(16);

    public void register() {
        FMLCommonHandler.instance().bus().register(this);
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("CrystalGraphicsFontDemo: Registered");
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (!demoEnabled || event.phase != TickEvent.Phase.END) return;


        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;


        int wheel = Mouse.getDWheel();
        if (wheel > 0) setDemoFontSize(demoFontSize + 2);
        else if (wheel < 0) setDemoFontSize(demoFontSize - 2);

        demoFrame++;
        if (demoFontRegistry != null) demoFontRegistry.tickFrame(demoFrame);
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Text event) {
        if (!demoEnabled) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return;
        }

        try {
            ensureDemoFontSystem();
            ScaledResolution resolution = event.resolution;
            populateOrthoMatrix(demoProjectionMatrix, resolution.getScaledWidth(), resolution.getScaledHeight());

            demoTextRenderer.draw(
                    demoLayoutBuilder.layout(DEMO_TEXT + " [" + demoFontSize + "px]", demoFont, resolution.getScaledWidth(), 0),
                    demoFont,
                    20.0f,
                    40.0f,
                    0xFFFFFFFF,
                    demoFrame,
                    demoProjectionMatrix);
            int x = 10;
        } catch (Exception e) {
            LOGGER.error("CrystalGraphics font demo failed", e);
            demoEnabled = false;
        }
    }

    private void ensureDemoFontSystem() {
        if (demoFontRegistry == null) {
            demoFontRegistry = new CgFontRegistry();
        }
        if (demoFont == null || demoFont.isDisposed() || demoFont.getKey().getTargetPx() != demoFontSize) {
            if (demoFont != null && !demoFont.isDisposed()) {
                demoFont.dispose();
            }
            
            demoFont = CgFont.load(DEMO_FONT_PATH, CgFontStyle.REGULAR, demoFontSize);
        }
        if (demoTextRenderer == null || demoTextRenderer.isDeleted()) {
            demoTextRenderer = CgTextRenderer.create(CgCapabilities.detect(), demoFontRegistry);
        }
    }

    private void setDemoFontSize(int newSize) {
        int clamped = Math.max(8, Math.min(96, newSize));
        if (clamped == demoFontSize) {
            return;
        }
        demoFontSize = clamped;
        if (demoFont != null && !demoFont.isDisposed()) {
            demoFont.dispose();
            demoFont = null;
        }
    }

    private void populateOrthoMatrix(FloatBuffer buffer, int width, int height) {
        buffer.clear();
        float left = 0.0f;
        float right = width;
        float bottom = height;
        float top = 0.0f;
        float near = -1.0f;
        float far = 1.0f;

        float sx = 2.0f / (right - left);
        float sy = 2.0f / (top - bottom);
        float sz = -2.0f / (far - near);
        float tx = -(right + left) / (right - left);
        float ty = -(top + bottom) / (top - bottom);
        float tz = -(far + near) / (far - near);

        buffer.put(sx).put(0.0f).put(0.0f).put(tx);
        buffer.put(0.0f).put(sy).put(0.0f).put(ty);
        buffer.put(0.0f).put(0.0f).put(sz).put(tz);
        buffer.put(0.0f).put(0.0f).put(0.0f).put(1.0f);
        buffer.flip();
    }
}
