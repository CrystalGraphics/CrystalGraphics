package io.github.somehussar.crystalgraphics.harness;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.font.CgAtlasRegion;
import io.github.somehussar.crystalgraphics.api.font.CgFont;
import io.github.somehussar.crystalgraphics.api.font.CgFontKey;
import io.github.somehussar.crystalgraphics.api.font.CgFontMetrics;
import io.github.somehussar.crystalgraphics.api.font.CgFontStyle;
import io.github.somehussar.crystalgraphics.api.font.CgGlyphKey;
import io.github.somehussar.crystalgraphics.api.font.CgTextLayoutBuilder;
import io.github.somehussar.crystalgraphics.api.shader.CgShaderProgram;
import io.github.somehussar.crystalgraphics.gl.shader.CgShaderFactory;
import io.github.somehussar.crystalgraphics.gl.text.CgFontRegistry;
import io.github.somehussar.crystalgraphics.gl.text.CgGlyphAtlas;
import io.github.somehussar.crystalgraphics.gl.text.CgGlyphVbo;
import io.github.somehussar.crystalgraphics.gl.text.CgMsdfGenerator;
import io.github.somehussar.crystalgraphics.text.CgShapedRun;
import io.github.somehussar.crystalgraphics.text.CgTextLayout;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.logging.Logger;

/**
 * Parity scene that exercises the <strong>same rendering pipeline</strong>
 * as {@code CrystalGraphicsFontDemo} in Minecraft, but running inside the
 * standalone harness with its known-good orthographic matrix.
 *
 * <h3>What This Tests</h3>
 * <p>This scene uses the real shared components from the CrystalGraphics
 * font pipeline:</p>
 * <ul>
 *   <li>{@link CgFont#load} — font loading with FreeType + HarfBuzz</li>
 *   <li>{@link CgTextLayoutBuilder#layout} — BiDi, shaping, line breaking</li>
 *   <li>{@link CgFontRegistry#ensureGlyph} — glyph atlas allocation</li>
 *   <li>{@link CgGlyphVbo} — batched quad rendering with VAO/VBO/IBO</li>
 *   <li>{@link CgShaderFactory#compile} — shader compilation (bitmap + MSDF)</li>
 *   <li>Bitmap and MSDF atlas shaders from resource classpath</li>
 * </ul>
 *
 * <h3>How It Differs from CgTextRenderer</h3>
 * <p>The only difference is that this scene does NOT call
 * {@code CgStateBoundary.save()/restore()}, which has Minecraft-specific
 * imports ({@code OpenGlHelper}, {@code CrystalGraphicsCoremod}).  The GL
 * state management is done directly in this class. All other rendering
 * logic is identical to {@code CgTextRenderer.drawInternal()}.</p>
 *
 * <h3>Comparison with TextSceneScene</h3>
 * <p>{@code TextSceneScene} uses a simplified custom draw path (manual
 * FreeType/HarfBuzz → custom quads → inline shader).
 * This scene uses the production {@code CgFontRegistry} + {@code CgGlyphVbo}
 * pipeline, which is what actually runs in-game.</p>
 */
final class CgRendererParityScene implements HarnessScene {

    private static final Logger LOGGER = Logger.getLogger(CgRendererParityScene.class.getName());

    private static final String TEST_STRING = "CrystalGraphics font demo - mouse wheel zoom";
    private static final int FONT_SIZE_PX = 24;
    private static final int FBO_WIDTH = 800;
    private static final int FBO_HEIGHT = 600;
    private static final int INITIAL_VBO_CAPACITY = 256;

    private static final String BITMAP_VERT = "/assets/crystalgraphics/shader/bitmap_text.vert";
    private static final String BITMAP_FRAG = "/assets/crystalgraphics/shader/bitmap_text.frag";
    private static final String MSDF_VERT = "/assets/crystalgraphics/shader/msdf_text.vert";
    private static final String MSDF_FRAG = "/assets/crystalgraphics/shader/msdf_text.frag";

    @Override
    public void run(HarnessContext ctx, String outputDir) {
        LOGGER.info("[Harness] Renderer parity scene: text=\"" + TEST_STRING + "\"");
        LOGGER.info("[Harness] Renderer parity scene: size=" + FONT_SIZE_PX + "px");

        // ── 1. Detect capabilities ────────────────────────────────────
        CgCapabilities caps = CgCapabilities.detect();
        LOGGER.info("[Harness] Capabilities: coreFbo=" + caps.isCoreFbo()
                + ", coreShaders=" + caps.isCoreShaders()
                + ", vao=" + caps.isVaoSupported()
                + ", mapBufferRange=" + caps.isMapBufferRangeSupported());

        if (!caps.isCoreFbo() || !caps.isCoreShaders()
                || !caps.isVaoSupported() || !caps.isMapBufferRangeSupported()) {
            throw new IllegalStateException(
                    "Renderer parity scene requires modern GL: core FBO, core shaders, VAO, glMapBufferRange");
        }

        // ── 2. Load font via CgFont (same as CrystalGraphicsFontDemo) ──
        String fontPath = findFontPath();
        LOGGER.info("[Harness] Font path: " + fontPath);

        CgFont font = CgFont.load(fontPath, CgFontStyle.REGULAR, FONT_SIZE_PX);
        LOGGER.info("[Harness] Font loaded: " + font.getKey());
        LOGGER.info("[Harness] Font metrics: ascender=" + font.getMetrics().getAscender()
                + ", descender=" + font.getMetrics().getDescender()
                + ", lineHeight=" + font.getMetrics().getLineHeight());

        // ── 3. Create CgFontRegistry (same as CrystalGraphicsFontDemo) ─
        CgFontRegistry registry = new CgFontRegistry();

        // ── 4. Compile shaders via CgShaderFactory (same as CgTextRenderer.create()) ─
        CgShaderProgram bitmapShader = CgShaderFactory.compile(caps,
                readShaderSource(BITMAP_VERT), readShaderSource(BITMAP_FRAG));
        CgShaderProgram msdfShader = CgShaderFactory.compile(caps,
                readShaderSource(MSDF_VERT), readShaderSource(MSDF_FRAG));
        LOGGER.info("[Harness] Shaders compiled: bitmap=" + bitmapShader.getId()
                + ", msdf=" + msdfShader.getId());

        // ── 5. Create CgGlyphVbo (same as CgTextRenderer.create()) ────
        CgGlyphVbo vbo = CgGlyphVbo.create(INITIAL_VBO_CAPACITY);
        vbo.setupAttributes(bitmapShader);
        vbo.setupAttributes(msdfShader);
        LOGGER.info("[Harness] VBO created: capacity=" + vbo.getCapacity());

        // ── 6. Layout text via CgTextLayoutBuilder (same as CrystalGraphicsFontDemo) ─
        CgTextLayoutBuilder layoutBuilder = new CgTextLayoutBuilder();
        CgTextLayout layout = layoutBuilder.layout(
                TEST_STRING + " [" + FONT_SIZE_PX + "px]",
                font,
                FBO_WIDTH,  // maxWidth
                0            // maxHeight (unbounded)
        );
        LOGGER.info("[Harness] Layout: " + layout.getLines().size() + " lines, "
                + "width=" + layout.getTotalWidth() + ", height=" + layout.getTotalHeight());

        // ── 7. Set up FBO for rendering ───────────────────────────────
        int fbo = GL30.glGenFramebuffers();
        int colorTex = GL11.glGenTextures();

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
                FBO_WIDTH, FBO_HEIGHT, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
                GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, colorTex, 0);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("Parity scene FBO incomplete: 0x" + Integer.toHexString(status));
        }

        GL11.glViewport(0, 0, FBO_WIDTH, FBO_HEIGHT);
        GL11.glClearColor(0.15f, 0.15f, 0.2f, 1.0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

        // ── 8. Build orthographic projection (same as CrystalGraphicsFontDemo.populateOrthoMatrix) ─
        FloatBuffer projectionMatrix = BufferUtils.createFloatBuffer(16);
        populateOrthoMatrix(projectionMatrix, FBO_WIDTH, FBO_HEIGHT);

        // ── 9. Draw using the same logic as CgTextRenderer.drawInternal() ─
        long frame = 1;
        drawInternal(layout, font, 20.0f, 40.0f, 0xFFFFFF, frame,
                projectionMatrix, registry, vbo, bitmapShader, msdfShader);

        GL11.glFinish();

        // ── 10. Capture output ─────────────────────────────────────────
        ScreenshotUtil.captureFboColorTexture(fbo, colorTex,
                FBO_WIDTH, FBO_HEIGHT, outputDir, "renderer-parity.png");

        // Also dump the bitmap atlas for comparison
        CgGlyphAtlas bitmapAtlas = registry.getBitmapAtlas(font.getKey());
        if (bitmapAtlas != null && !bitmapAtlas.isDeleted() && bitmapAtlas.getTextureId() != 0) {
            ScreenshotUtil.captureTexture(bitmapAtlas.getTextureId(), 1024, 1024,
                    0x8229 /* GL_R8 */, outputDir, "renderer-parity-bitmap-atlas.png");
        }

        CgGlyphAtlas msdfAtlas = registry.getMsdfAtlas(font.getKey());
        if (msdfAtlas != null && !msdfAtlas.isDeleted() && msdfAtlas.getTextureId() != 0) {
            ScreenshotUtil.captureTexture(msdfAtlas.getTextureId(), 1024, 1024,
                    GL30.GL_RGB16F, outputDir, "renderer-parity-msdf-atlas.png");
        }

        // ── 11. Cleanup ────────────────────────────────────────────────
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        vbo.delete();
        bitmapShader.delete();
        msdfShader.delete();
        registry.releaseAll();
        font.dispose();
        GL30.glDeleteFramebuffers(fbo);
        GL11.glDeleteTextures(colorTex);

        LOGGER.info("[Harness] Renderer parity scene complete.");
    }

    /**
     * Replicates {@code CgTextRenderer.drawInternal()} exactly, using the same
     * glyph resolution, quad building, VBO upload, and two-pass draw logic.
     *
     * <p>The ONLY difference from the in-game path is the absence of
     * {@code CgStateBoundary.save()/restore()} and the Keyboard debug check.</p>
     */
    private void drawInternal(CgTextLayout layout,
                              CgFont font,
                              float x, float y,
                              int rgba,
                              long frame,
                              FloatBuffer projectionMatrix,
                              CgFontRegistry registry,
                              CgGlyphVbo vbo,
                              CgShaderProgram bitmapShader,
                              CgShaderProgram msdfShader) {
        if (layout == null || layout.getLines().isEmpty()) {
            return;
        }

        CgFontKey fontKey = font.getKey();
        CgFontMetrics metrics = layout.getMetrics();
        boolean wantMsdf = fontKey.getTargetPx() >= 32;
        List<List<CgShapedRun>> lines = layout.getLines();
        int totalGlyphs = countGlyphs(lines);
        float[] glyphX = new float[totalGlyphs];
        float[] glyphY = new float[totalGlyphs];
        CgAtlasRegion[] regions = new CgAtlasRegion[totalGlyphs];

        int index = 0;
        float penY = y;
        for (List<CgShapedRun> line : lines) {
            float penX = x;
            for (CgShapedRun run : line) {
                int[] glyphIds = run.getGlyphIds();
                float[] advancesX = run.getAdvancesX();
                float[] offsetsX = run.getOffsetsX();
                float[] offsetsY = run.getOffsetsY();
                for (int i = 0; i < glyphIds.length; i++) {
                    int subPixelBucket = selectSubPixelBucket(fontKey, offsetsX[i]);
                    CgGlyphKey glyphKey = new CgGlyphKey(fontKey, glyphIds[i], wantMsdf, subPixelBucket);
                    regions[index] = registry.ensureGlyph(font, glyphKey, frame);
                    glyphX[index] = penX + offsetsX[i];
                    glyphY[index] = penY + offsetsY[i];
                    penX += advancesX[i];
                    index++;
                }
            }
            penY += metrics.getLineHeight();
        }

        LOGGER.info("[Harness] Resolved " + totalGlyphs + " glyphs, wantMsdf=" + wantMsdf);

        vbo.begin();
        int bitmapQuadCount = appendQuads(vbo, regions, glyphX, glyphY, rgba, false);
        int msdfQuadCount = appendQuads(vbo, regions, glyphX, glyphY, rgba, true);

        LOGGER.info("[Harness] Quad counts: bitmap=" + bitmapQuadCount + ", msdf=" + msdfQuadCount);

        if (bitmapQuadCount == 0 && msdfQuadCount == 0) {
            LOGGER.warning("[Harness] No quads to render — all glyphs empty?");
            return;
        }

        vbo.uploadAndBind();
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_DEPTH_TEST);

        FloatBuffer matrixBuf = BufferUtils.createFloatBuffer(16);

        if (bitmapQuadCount > 0) {
            bitmapShader.bind();

            int bitmapLocProjection = bitmapShader.getUniformLocation("u_projection");
            uploadProjectionMatrix(bitmapShader, bitmapLocProjection, projectionMatrix, matrixBuf);

            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, registry.getBitmapAtlas(fontKey).getTextureId());

            int bitmapLocAtlas = bitmapShader.getUniformLocation("u_atlas");
            bitmapShader.setUniform1i(bitmapLocAtlas, 0);

            GL11.glDrawElements(GL11.GL_TRIANGLES, bitmapQuadCount * 6, GL11.GL_UNSIGNED_SHORT, 0);
            bitmapShader.unbind();
        }

        if (msdfQuadCount > 0) {
            msdfShader.bind();

            int msdfLocProjection = msdfShader.getUniformLocation("u_projection");
            uploadProjectionMatrix(msdfShader, msdfLocProjection, projectionMatrix, matrixBuf);

            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, registry.getMsdfAtlas(fontKey).getTextureId());

            int msdfLocAtlas = msdfShader.getUniformLocation("u_atlas");
            msdfShader.setUniform1i(msdfLocAtlas, 0);

            int msdfLocPxRange = msdfShader.getUniformLocation("u_pxRange");
            msdfShader.setUniform1f(msdfLocPxRange, CgMsdfGenerator.PX_RANGE);

            long offsetBytes = (long) bitmapQuadCount * 6L * 2L;
            GL11.glDrawElements(GL11.GL_TRIANGLES, msdfQuadCount * 6, GL11.GL_UNSIGNED_SHORT, offsetBytes);
            msdfShader.unbind();
        }

        vbo.unbind();
        GL11.glDisable(GL11.GL_BLEND);
    }

    // ── Helpers copied from CgTextRenderer (no MC dependencies) ───────

    private int countGlyphs(List<List<CgShapedRun>> lines) {
        int total = 0;
        for (List<CgShapedRun> line : lines) {
            for (CgShapedRun run : line) {
                total += run.getGlyphIds().length;
            }
        }
        return total;
    }

    private int appendQuads(CgGlyphVbo vbo,
                            CgAtlasRegion[] regions,
                            float[] glyphX,
                            float[] glyphY,
                            int rgba,
                            boolean msdf) {
        int quadCount = 0;
        for (int i = 0; i < regions.length; i++) {
            CgAtlasRegion region = regions[i];
            if (region == null || region.getWidth() <= 0 || region.getHeight() <= 0) {
                continue;
            }
            if (region.getKey().isMsdf() != msdf) {
                continue;
            }
            float qx = glyphX[i] + region.getBearingX();
            float qy = glyphY[i] - region.getBearingY();
            vbo.addGlyph(qx, qy, region.getWidth(), region.getHeight(),
                    region.getU0(), region.getV0(), region.getU1(), region.getV1(), rgba);
            quadCount++;
        }
        return quadCount;
    }

    private void uploadProjectionMatrix(CgShaderProgram shader, int uniformLocation,
                                        FloatBuffer projectionMatrix, FloatBuffer matrixBuf) {
        if (uniformLocation < 0) {
            return;
        }
        int sourcePosition = projectionMatrix.position();
        matrixBuf.clear();
        for (int i = 0; i < 16; i++) {
            matrixBuf.put(projectionMatrix.get(sourcePosition + i));
        }
        matrixBuf.flip();
        shader.setUniformMatrix4f(uniformLocation, matrixBuf);
    }

    private int selectSubPixelBucket(CgFontKey fontKey, float xOffset) {
        if (fontKey.getTargetPx() > 12) {
            return 0;
        }
        float fractional = xOffset - (float) Math.floor(xOffset);
        if (fractional < 0.125f) {
            return 0;
        }
        if (fractional < 0.375f) {
            return 1;
        }
        if (fractional < 0.625f) {
            return 2;
        }
        if (fractional < 0.875f) {
            return 3;
        }
        return 0;
    }

    /**
     * Orthographic projection matrix matching {@code CrystalGraphicsFontDemo.populateOrthoMatrix()}
     * exactly: left=0, right=width, top=0, bottom=height, near=0, far=1.
     */
    private void populateOrthoMatrix(FloatBuffer buffer, int width, int height) {
        buffer.clear();
        float left = 0.0f;
        float right = width;
        float bottom = height;
        float top = 0.0f;
        float near = 0;
        float far = 1.0f;

        float sx = 2.0f / (right - left);
        float sy = 2.0f / (top - bottom);
        float sz = -2.0f / (far - near);
        float tx = -(right + left) / (right - left);
        float ty = -(top + bottom) / (top - bottom);
        float tz = -(far + near) / (far - near);

        // Column-major order for glUniformMatrix4(loc, false, buf)
        // Column 0
        buffer.put(sx).put(0.0f).put(0.0f).put(0.0f);
        // Column 1
        buffer.put(0.0f).put(sy).put(0.0f).put(0.0f);
        // Column 2
        buffer.put(0.0f).put(0.0f).put(sz).put(0.0f);
        // Column 3
        buffer.put(tx).put(ty).put(tz).put(1.0f);
        buffer.flip();
    }

    // ── Font finding ──────────────────────────────────────────────────

    /**
     * Finds the test font bundled in the resource classpath, or falls back
     * to the system font used by other harness scenes.
     */
    private String findFontPath() {
        // Try to use the same test font as CrystalGraphicsFontDemo
        String testFont = "src/main/resources/assets/crystalgraphics/test-font.ttf";
        java.io.File f = new java.io.File(testFont);
        if (f.exists()) {
            return f.getAbsolutePath();
        }

        // Fallback: system font (same as AtlasDumpScene)
        return AtlasDumpScene.findSystemFont();
    }

    // ── Shader source loading (from classpath) ────────────────────────

    private static String readShaderSource(String resourcePath) {
        InputStream in = CgRendererParityScene.class.getResourceAsStream(resourcePath);
        if (in == null) {
            throw new RuntimeException("Shader resource not found on classpath: " + resourcePath);
        }
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read shader resource: " + resourcePath, e);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }
}
