package io.github.somehussar.crystalgraphics.mc.integration;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import io.github.somehussar.crystalgraphics.CrystalGraphics;
import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.PoseStack;
import io.github.somehussar.crystalgraphics.api.font.CgFont;
import io.github.somehussar.crystalgraphics.api.font.CgFontStyle;
import io.github.somehussar.crystalgraphics.api.font.CgTextLayoutBuilder;
import io.github.somehussar.crystalgraphics.api.shader.CgShader;
import io.github.somehussar.crystalgraphics.api.shader.CgShaderScope;
import io.github.somehussar.crystalgraphics.gl.text.CgFontRegistry;
import io.github.somehussar.crystalgraphics.gl.text.CgGlyphAtlas;
import io.github.somehussar.crystalgraphics.gl.text.CgTextRenderContext;
import io.github.somehussar.crystalgraphics.gl.text.CgTextRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.*;

import java.nio.FloatBuffer;

public class CrystalGraphicsFontDemo {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphicsFontDemo");
    private static final String DEMO_TEXT = "CrystalGraphics font demo - mouse wheel zoom";
    private static final String DEMO_TEXT_2D_LABEL = "2D UI text: logical size stable, raster scales with pose";
    private static final String DEMO_TEXT_3D_LABEL = "3D world text: always MSDF, projection-aware quality";
    private static final String DEMO_FONT_PATH = "X:\\projects\\CrystalGraphics\\src\\main\\resources\\assets\\crystalgraphics\\test-font.ttf";

    private boolean demoEnabled = true;
    private int demoFontSize = 24;
    private float demoPoseScale = 1.0f;
    private long demoFrame = 0L;
    private CgFont demoFont;
    private CgFontRegistry demoFontRegistry;
    private CgTextRenderer demoTextRenderer;
    private final CgTextLayoutBuilder demoLayoutBuilder = new CgTextLayoutBuilder();
    private CgTextRenderContext demoRenderContext;
    private int lastDisplayWidth;
    private int lastDisplayHeight;

    // ── Diagnostic: atlas viewer ──────────────────────────────────────
    private CgShader diagAtlasShader;
    private int diagAtlasVao;
    private int diagAtlasVbo;
    private boolean diagAtlasInitialized;
    private final FloatBuffer diagAtlasProjection = BufferUtils.createFloatBuffer(16);

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
        if (wheel > 0) {
            demoPoseScale = Math.min(4.0f, demoPoseScale + 0.1f);
        } else if (wheel < 0) {
            demoPoseScale = Math.max(0.5f, demoPoseScale - 0.1f);
        }

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
            int scaledW = resolution.getScaledWidth();
            int scaledH = resolution.getScaledHeight();

            // Projection must match Minecraft's overlay coordinate space:
            // setupOverlayRendering() uses glOrtho(0, scaledWidth, scaledHeight, 0, ...)
            // so our shader projection must also span [0, scaledWidth] × [0, scaledHeight].
            if (demoRenderContext == null
                    || mc.displayWidth != lastDisplayWidth
                    || mc.displayHeight != lastDisplayHeight) {
                if (demoRenderContext == null) {
                    demoRenderContext = CgTextRenderContext.orthographic(mc.displayWidth, mc.displayHeight);
                } else {
                    demoRenderContext.updateOrtho(mc.displayWidth, mc.displayHeight);
                }
                lastDisplayWidth = mc.displayWidth;
                lastDisplayHeight = mc.displayHeight;
            }

            PoseStack poseStack = new PoseStack();
            poseStack.scale(demoPoseScale, demoPoseScale, 1.0f);
            float logicalViewportWidth = (float) mc.displayWidth/demoPoseScale;
            demoRenderContext.clearHistory();

            // 2D UI text: logical spacing is stable; PoseStack scale only
            // increases the effective raster size (sharper glyphs) without
            // changing layout metrics.
            demoTextRenderer.draw(
                    demoLayoutBuilder.layout(DEMO_TEXT + " [base " + demoFontSize + "px, pose " + String.format("%.1f", demoPoseScale) + "x]", demoFont, logicalViewportWidth, 0),
                    demoFont,
                    20.0f,
                    40.0f + demoFontSize,
                    0xFFFFFFFF,
                    demoFrame,
                    demoRenderContext,
                    poseStack);

            demoRenderContext.clearHistory();

            float topLabelWrapWidth = (float) mc.displayWidth;
            PoseStack identityPose = new PoseStack();
            demoTextRenderer.draw(
                    demoLayoutBuilder.layout(DEMO_TEXT_2D_LABEL, demoFont, topLabelWrapWidth, 0),
                    demoFont,
                    20.0f,
                    20.0f,
                    0xAAFFAAFF,
                    demoFrame,
                    demoRenderContext,
                    identityPose);
            
//             demoTextRenderer.draw(
//                    demoLayoutBuilder.layout("His is a \nline break [" + demoFontSize + "px]", demoFont, mc.displayWidth, 0),
//                    demoFont,
//                    20.0f,
//                    80.0f + demoFontSize,
//                    0xFFFFFFFF,
//                    demoFrame,
//                    demoProjectionMatrix);

            if (true) 
                drawDiagAtlas(resolution.getScaledWidth(), resolution.getScaledHeight());
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

    // ── Diagnostic: atlas viewer ──────────────────────────────────────

    private void drawDiagAtlas(int screenW, int screenH) {
        if (demoFontRegistry == null || demoFont == null || demoFont.isDisposed()) {
            return;
        }

        ensureDiagAtlasResources();

        CgGlyphAtlas bitmapAtlas = demoFontRegistry.findPopulatedBitmapAtlas(demoFont.getKey());
        if (bitmapAtlas == null || bitmapAtlas.isDeleted() || bitmapAtlas.getTextureId() == 0) {
            return;
        }

        int atlasDisplaySize = Math.min(256, Math.min(screenW / 2, screenH / 2));
        float x0 = 10.0f;
        float y0 = screenH - atlasDisplaySize - 10.0f;
        float x1 = x0 + atlasDisplaySize;
        float y1 = y0 + atlasDisplaySize;

        updateDiagAtlasQuad(x0, y0, x1, y1);

        populateOrthoMatrix(diagAtlasProjection, screenW, screenH);

        boolean blendWas = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean depthWas = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);

        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_DEPTH_TEST);

        // First draw: bitmap atlas
        diagAtlasShader.applyBindings(b -> b.mat4("u_projection", diagAtlasProjection)
                                    .set1i("u_atlas", 0)
                                    .set1i("u_atlasType", 0));
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, bitmapAtlas.getTextureId());

        try (CgShaderScope scope = diagAtlasShader.bindScoped()) {
            GL30.glBindVertexArray(diagAtlasVao);
            GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        }

        // Second draw: msdf atlas
        CgGlyphAtlas msdfAtlas = demoFontRegistry.findPopulatedMsdfAtlas(demoFont.getKey());
        if (msdfAtlas != null && !msdfAtlas.isDeleted() && msdfAtlas.getTextureId() != 0) {
            float mx0 = x1 + 10.0f;
            float my0 = y0;
            float mx1 = mx0 + atlasDisplaySize;
            float my1 = y1;
            updateDiagAtlasQuad(mx0, my0, mx1, my1);

            diagAtlasShader.applyBindings(b -> b.set1i("u_atlasType", 1));
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, msdfAtlas.getTextureId());

            try (CgShaderScope scope = diagAtlasShader.bindScoped()) {
                GL30.glBindVertexArray(diagAtlasVao);
                GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
                GL30.glBindVertexArray(0);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            }
        }

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        if (blendWas) GL11.glEnable(GL11.GL_BLEND);
        if (depthWas) GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    private void ensureDiagAtlasResources() {
        if (diagAtlasInitialized) {
            return;
        }

        diagAtlasShader = CrystalGraphics.getShaderManager().load(
                new ResourceLocation("crystalgraphics", "shader/diag_atlas.vert"),
                new ResourceLocation("crystalgraphics", "shader/diag_atlas.frag"));

        // pos(x,y) + uv(u,v) = 4 floats per vertex, 4 vertices (triangle strip)
        float[] quadData = {
            0.0f, 0.0f, 0.0f, 0.0f,
            1.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 1.0f,
            1.0f, 1.0f, 1.0f, 1.0f
        };
        FloatBuffer buf = BufferUtils.createFloatBuffer(quadData.length);
        buf.put(quadData).flip();

        diagAtlasVao = GL30.glGenVertexArrays();
        diagAtlasVbo = GL15.glGenBuffers();

        GL30.glBindVertexArray(diagAtlasVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, diagAtlasVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_DYNAMIC_DRAW);

        // Force compile so getProgram() is available for attrib locations
        diagAtlasShader.bind();

        int stride = 4 * 4;
        int programId = diagAtlasShader.getProgram().getId();
        int posLoc = GL20.glGetAttribLocation(programId, "a_pos");
        int uvLoc = GL20.glGetAttribLocation(programId, "a_uv");
        if (posLoc >= 0) {
            GL20.glVertexAttribPointer(posLoc, 2, GL11.GL_FLOAT, false, stride, 0);
            GL20.glEnableVertexAttribArray(posLoc);
        }
        if (uvLoc >= 0) {
            GL20.glVertexAttribPointer(uvLoc, 2, GL11.GL_FLOAT, false, stride, 8);
            GL20.glEnableVertexAttribArray(uvLoc);
        }

        diagAtlasShader.unbind();

        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        diagAtlasInitialized = true;
        LOGGER.info("DIAG: atlas viewer initialized (VAO=" + diagAtlasVao
                + ", VBO=" + diagAtlasVbo + ", shader=" + diagAtlasShader.getProgram().getId() + ")");
    }

    private void updateDiagAtlasQuad(float x0, float y0, float x1, float y1) {
        float[] quadData = {
            x0, y0, 0.0f, 0.0f,
            x1, y0, 1.0f, 0.0f,
            x0, y1, 0.0f, 1.0f,
            x1, y1, 1.0f, 1.0f
        };
        FloatBuffer buf = BufferUtils.createFloatBuffer(quadData.length);
        buf.put(quadData).flip();

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, diagAtlasVbo);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, buf);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
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

        buffer.put(sx).put(0.0f).put(0.0f).put(0.0f);  // col 0
        buffer.put(0.0f).put(sy).put(0.0f).put(0.0f);  // col 1
        buffer.put(0.0f).put(0.0f).put(sz).put(0.0f);  // col 2
        buffer.put(tx).put(ty).put(tz).put(1.0f);       // col 3
        buffer.flip();
    }
}
