package com.crystalgraphics.demo;

import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.font.CgTextLayoutBuilder;
import com.crystalgraphics.api.shader.CgShader;
import com.crystalgraphics.api.state.CgGlSlot;
import com.crystalgraphics.gl.shader.CgShaderFactory;
import com.crystalgraphics.gl.state.CgGlScope;
import com.crystalgraphics.gl.state.CgGlState;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.text.atlas.CgGlyphAtlas;
import com.crystalgraphics.text.cache.CgFontRegistry;
import com.crystalgraphics.text.render.CgTextRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Platform-agnostic font benchmark and diagnostic viewer.
 *
 * <h3>GL contract</h3>
 * <p>All GL calls go through {@link CgPlatform#gl()}.  All GL constants come from
 * {@link CgGL}.  No {@code org.lwjgl.*} imports are present in this class.</p>
 *
 * <h3>Attrib location contract for diag_atlas shader</h3>
 * <p>The diagnostic atlas shader is expected to declare {@code a_pos} as its first
 * attribute and {@code a_uv} as its second.  The VAO setup hard-codes locations 0 and 1
 * respectively, which matches the order the driver assigns them when no explicit
 * {@code layout(location=N)} is present.</p>
 */
public final class CgFontDemo {

    public static final CgFontDemo INSTANCE = new CgFontDemo();

    private static final Logger LOGGER = LogManager.getLogger("CgFontDemo");

    private static final String DEMO_TEXT = "CrystalGraphics font demo - mouse wheel zoom";
    private static final String DEMO_TEXT_2D_LABEL = "2D UI text: logical size stable, raster scales with pose";
    private static final String DEMO_FONT_PATH = "C:\\WINDOWS\\Fonts\\arial.ttf";

    private boolean demoEnabled = true;
    private int demoFontSize = 24;
    private float demoPoseScale = 1.0f;
    private CgFont demoFont;
    private CgFontRegistry demoFontRegistry;
    private CgTextRenderer demoTextRenderer;
    private final CgTextLayoutBuilder demoLayoutBuilder = new CgTextLayoutBuilder();
    private int lastDisplayWidth;
    private int lastDisplayHeight;

    // Raw GL handles are acceptable here: this class is a self-contained diagnostic
    // utility that owns its own VAO/VBO pair, analogous to CgDebugBlit.
    private CgShader diagAtlasShader;
    private int diagAtlasVao;
    private int diagAtlasVbo;
    private boolean diagAtlasInitialized;
    private final FloatBuffer diagAtlasProjection =
            ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

    private CgFontDemo() {}

    public void render(int displayWidth, int displayHeight) {
        if (!demoEnabled) return;

        try {
            ensureDemoFontSystem();

            // Update render context when the display size changes.
            if (displayWidth != lastDisplayWidth || displayHeight != lastDisplayHeight) {
                demoTextRenderer.context().updateOrtho(displayWidth, displayHeight);
                lastDisplayWidth = displayWidth;
                lastDisplayHeight = displayHeight;
            }

            demoTextRenderer.beginBatch();

            PoseStack poseStack = new PoseStack();
            poseStack.scale(demoPoseScale, demoPoseScale, 1.0f);
            float logicalViewportWidth = (float) displayWidth / demoPoseScale;
            demoTextRenderer.context().clearHistory();

            demoTextRenderer.draw(
                    demoLayoutBuilder.layout(
                            DEMO_TEXT + " [base " + demoFontSize + "px, pose "
                                    + String.format("%.1f", demoPoseScale) + "x]",
                            demoFont, logicalViewportWidth, 0),
                    demoFont,
                    20.0f,
                    40.0f + demoFontSize,
                    0xFFFFFFFF,
                    poseStack);

            demoTextRenderer.context().clearHistory();

            PoseStack identityPose = new PoseStack();
            demoTextRenderer.draw(
                    demoLayoutBuilder.layout(DEMO_TEXT_2D_LABEL, demoFont, (float) displayWidth, 0),
                    demoFont,
                    20.0f,
                    20.0f,
                    0xAAFFAAFF,
                    identityPose);

            demoTextRenderer.endBatch();

            drawDiagAtlas(displayWidth, displayHeight);
        } catch (Exception e) {
            LOGGER.error("CrystalGraphics font demo failed", e);
            demoEnabled = false;
        }
    }

    /**
     * Adjusts the pose scale in response to a mouse-wheel scroll event.
     *
     * @param delta raw wheel delta ({@code > 0} = scroll up = zoom in)
     */
    public void onMouseWheel(int delta) {
        if (delta > 0) {
            demoPoseScale = Math.min(4.0f, demoPoseScale + 0.1f);
        } else if (delta < 0) {
            demoPoseScale = Math.max(0.5f, demoPoseScale - 0.1f);
        }
    }

    public void dispose() {
        if (demoFont != null && !demoFont.isDisposed()) demoFont.dispose();
        if (demoTextRenderer != null && !demoTextRenderer.isDeleted()) demoTextRenderer.delete();
        if (diagAtlasInitialized) {
            CgGL.glDeleteVertexArrays(diagAtlasVao);
            CgGL.glDeleteBuffers(diagAtlasVbo);
            diagAtlasInitialized = false;
        }
    }

    private void ensureDemoFontSystem() {
        if (demoFontRegistry == null) {
            demoFontRegistry = CgFontRegistry.get();
        }
        if (demoFont == null || demoFont.isDisposed() || demoFont.getKey().getTargetPx() != demoFontSize) {
            if (demoFont != null && !demoFont.isDisposed()) demoFont.dispose();
            demoFont = CgFont.load(DEMO_FONT_PATH, CgFontStyle.REGULAR, demoFontSize);
        }
        if (demoTextRenderer == null || demoTextRenderer.isDeleted()) {
            demoTextRenderer = CgTextRenderer.create();
        }
    }

    private void drawDiagAtlas(int screenW, int screenH) {
        if (demoFontRegistry == null || demoFont == null || demoFont.isDisposed()) return;

        ensureDiagAtlasResources();

        CgGlyphAtlas bitmapAtlas = demoFontRegistry.findPopulatedBitmapAtlas(demoFont.getKey());
        if (bitmapAtlas == null || bitmapAtlas.isDeleted() || bitmapAtlas.getTextureId() == 0) return;

        int atlasDisplaySize = Math.min(256, Math.min(screenW / 2, screenH / 2));
        float x0 = 10.0f;
        float y0 = screenH - atlasDisplaySize - 10.0f;
        float x1 = x0 + atlasDisplaySize;
        float y1 = y0 + atlasDisplaySize;

        updateDiagAtlasQuad(x0, y0, x1, y1);
        populateOrthoMatrix(diagAtlasProjection, screenW, screenH);

        try (CgGlScope scope = CgGlState.save(CgGlSlot.BLEND, CgGlSlot.DEPTH)) {
            CgGL.glDisable(CgGL.GL_BLEND);
            CgGL.glDisable(CgGL.GL_DEPTH_TEST);

            diagAtlasShader.applyBindings(b -> b
                    .mat4("u_projection", diagAtlasProjection)
                    .set1i("u_atlas", 0)
                    .set1i("u_atlasType", 0));
            CgGL.glActiveTexture(CgGL.GL_TEXTURE0);
            CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, bitmapAtlas.getTextureId());

            try (CgGlScope shaderScope = diagAtlasShader.bindScoped()) {
                CgGL.glBindVertexArray(diagAtlasVao);
                CgGL.glDrawArrays(CgGL.GL_TRIANGLE_STRIP, 0, 4);
                CgGL.glBindVertexArray(0);
                CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, 0);
            }

            CgGlyphAtlas msdfAtlas = demoFontRegistry.findPopulatedMsdfAtlas(demoFont.getKey());
            if (msdfAtlas != null && !msdfAtlas.isDeleted() && msdfAtlas.getTextureId() != 0) {
                float mx0 = x1 + 10.0f;
                float my0 = y0;
                float mx1 = mx0 + atlasDisplaySize;
                float my1 = y1;
                updateDiagAtlasQuad(mx0, my0, mx1, my1);

                diagAtlasShader.applyBindings(b -> b.set1i("u_atlasType", 1));
                CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, msdfAtlas.getTextureId());

                try (CgGlScope shaderScope = diagAtlasShader.bindScoped()) {
                    CgGL.glBindVertexArray(diagAtlasVao);
                    CgGL.glDrawArrays(CgGL.GL_TRIANGLE_STRIP, 0, 4);
                    CgGL.glBindVertexArray(0);
                    CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, 0);
                }
            }

            CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, 0);
        }
    }

    private void ensureDiagAtlasResources() {
        if (diagAtlasInitialized) return;

        diagAtlasShader = CgShaderFactory.load(
                "crystalgraphics:shader/diag_atlas.vert",
                "crystalgraphics:shader/diag_atlas.frag");

        // pos(x,y) + uv(u,v) = 4 floats per vertex, 4 vertices (triangle strip)
        float[] quadData = {
            0.0f, 0.0f, 0.0f, 0.0f,
            1.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 1.0f,
            1.0f, 1.0f, 1.0f, 1.0f
        };

        diagAtlasVao = CgGL.glGenVertexArrays();
        diagAtlasVbo = CgGL.glGenBuffers();

        CgGL.glBindVertexArray(diagAtlasVao);
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, diagAtlasVbo);
        CgGL.glBufferData(CgGL.GL_ARRAY_BUFFER, floatsToByteBuffer(quadData), CgGL.GL_DYNAMIC_DRAW);

        // Force compile so the program is live before we configure VAO attrib pointers.
        diagAtlasShader.bind();

        // Attrib locations are hard-coded to 0 (a_pos) and 1 (a_uv).
        // The shader declares these two attributes in this order; the driver assigns
        // them locations 0 and 1 respectively.  glGetAttribLocation is not needed and
        // is not available through CgGLBackend.
        int stride = 4 * 4; // 4 floats × 4 bytes
        CgGL.glVertexAttribPointer(0, 2, CgGL.GL_FLOAT, false, stride, 0);
        CgGL.glEnableVertexAttribArray(0);
        CgGL.glVertexAttribPointer(1, 2, CgGL.GL_FLOAT, false, stride, 8);
        CgGL.glEnableVertexAttribArray(1);

        diagAtlasShader.unbind();

        CgGL.glBindVertexArray(0);
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, 0);

        diagAtlasInitialized = true;
        LOGGER.info("DIAG: atlas viewer initialized (VAO={}, VBO={}, shader={})",
                diagAtlasVao, diagAtlasVbo, diagAtlasShader.getProgram().getId());
    }

    private void updateDiagAtlasQuad(float x0, float y0, float x1, float y1) {
        float[] quadData = {
            x0, y0, 0.0f, 0.0f,
            x1, y0, 1.0f, 0.0f,
            x0, y1, 0.0f, 1.0f,
            x1, y1, 1.0f, 1.0f
        };
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, diagAtlasVbo);
        CgGL.glBufferSubData(CgGL.GL_ARRAY_BUFFER, 0L, floatsToByteBuffer(quadData));
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, 0);
    }

    private static ByteBuffer floatsToByteBuffer(float[] data) {
        ByteBuffer buf = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder());
        buf.asFloatBuffer().put(data);
        return buf;
    }

    /**
     * Fills {@code buffer} with a column-major orthographic projection matrix mapping
     * {@code [0, width] × [0, height]} to clip space with Y pointing down.
     */
    private static void populateOrthoMatrix(FloatBuffer buffer, int width, int height) {
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

        buffer.put(sx).put(0.0f).put(0.0f).put(0.0f);
        buffer.put(0.0f).put(sy).put(0.0f).put(0.0f);
        buffer.put(0.0f).put(0.0f).put(sz).put(0.0f);
        buffer.put(tx).put(ty).put(tz).put(1.0f);
        buffer.flip();
    }
}
