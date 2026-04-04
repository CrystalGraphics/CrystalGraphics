package io.github.somehussar.crystalgraphics.gl.text;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.PoseStack;
import io.github.somehussar.crystalgraphics.api.font.CgAtlasRegion;
import io.github.somehussar.crystalgraphics.api.font.CgFont;
import io.github.somehussar.crystalgraphics.api.font.CgFontKey;
import io.github.somehussar.crystalgraphics.api.font.CgFontMetrics;
import io.github.somehussar.crystalgraphics.api.font.CgGlyphKey;
import io.github.somehussar.crystalgraphics.api.shader.CgShaderProgram;
import io.github.somehussar.crystalgraphics.gl.shader.CgShaderFactory;
import io.github.somehussar.crystalgraphics.gl.state.CgStateBoundary;
import io.github.somehussar.crystalgraphics.gl.state.CgStateSnapshot;
import io.github.somehussar.crystalgraphics.text.CgShapedRun;
import io.github.somehussar.crystalgraphics.text.CgTextLayout;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.logging.Logger;

/**
 * Two-pass text renderer for bitmap and MSDF glyph atlases.
 *
 * <p>The renderer consumes a pre-built {@link CgTextLayout}, resolves glyphs
 * through {@link CgFontRegistry}, writes quads into {@link CgGlyphVbo}, then
 * draws bitmap quads first and MSDF quads second. It uses the modern GL path
 * required by the current font implementation and restores GL state through
 * {@link CgStateBoundary} after each draw.</p>
 *
 * <h3>Three-Unit Size Model</h3>
 * <p>The text rendering pipeline distinguishes three pixel-size concepts:</p>
 * <ol>
 *   <li><strong>Logical layout pixels</strong> — coordinates used by {@link CgTextLayout}
 *       for width, height, line breaking, and glyph advances. These never change based
 *       on draw-time transforms.</li>
 *   <li><strong>Base target pixels</strong> ({@code CgFontKey.targetPx}) — the font size
 *       requested at load time. Part of the font/layout cache identity.</li>
 *   <li><strong>Effective target pixels</strong> — the actual raster size used for glyph
 *       rendering at draw time, derived from {@code baseTargetPx × poseScale} via
 *       {@link CgTextScaleResolver}. Determines which atlas/cache bucket serves glyphs.</li>
 * </ol>
 *
 * <h3>Projection and Context Model</h3>
 * <p>Rather than requiring callers to pass a raw {@code FloatBuffer projectionMatrix}
 * on every draw call, the renderer consumes a {@link CgTextRenderContext} that holds
 * the projection matrix and scale resolver. The context is set once (or updated on
 * resize) and reused across frames. The {@link PoseStack} — which changes per draw —
 * is passed directly to the draw method.</p>
 *
 * <h3>World-Space Extension</h3>
 * <p>Future perspective/world-space text requires a different {@link CgTextScaleResolver}
 * that estimates on-screen pixel coverage from view distance, FOV, and viewport dimensions.
 * The current shipped resolver ({@link CgTextScaleResolver#ORTHOGRAPHIC}) handles only
 * 2D screen-space text. A future {@code PerspectiveScaleResolver} would be injected
 * via the {@link CgTextRenderContext}.</p>
 */
public class CgTextRenderer {

    private static final Logger LOGGER = Logger.getLogger(CgTextRenderer.class.getName());
    private static final int INITIAL_VBO_CAPACITY = 256;

    private static final String BITMAP_VERT = "/assets/crystalgraphics/shader/bitmap_text.vert";
    private static final String BITMAP_FRAG = "/assets/crystalgraphics/shader/bitmap_text.frag";
    private static final String MSDF_VERT = "/assets/crystalgraphics/shader/msdf_text.vert";
    private static final String MSDF_FRAG = "/assets/crystalgraphics/shader/msdf_text.frag";

    private final CgShaderProgram bitmapShader;
    private final CgShaderProgram msdfShader;
    private final CgGlyphVbo vbo;
    private final CgFontRegistry registry;
    private final int bitmapLocProjection;
    private final int bitmapLocModelview;
    private final int bitmapLocAtlas;
    private final int msdfLocProjection;
    private final int msdfLocModelview;
    private final int msdfLocAtlas;
    private final int msdfLocPxRange;
    private final FloatBuffer matrixBuf;

    private boolean deleted;

    private CgTextRenderer(CgShaderProgram bitmapShader,
                           CgShaderProgram msdfShader,
                           CgGlyphVbo vbo,
                           CgFontRegistry registry) {
        this.bitmapShader = bitmapShader;
        this.msdfShader = msdfShader;
        this.vbo = vbo;
        this.registry = registry;
        this.bitmapLocProjection = bitmapShader.getUniformLocation("u_projection");
        this.bitmapLocModelview = bitmapShader.getUniformLocation("u_modelview");
        this.bitmapLocAtlas = bitmapShader.getUniformLocation("u_atlas");
        this.msdfLocProjection = msdfShader.getUniformLocation("u_projection");
        this.msdfLocModelview = msdfShader.getUniformLocation("u_modelview");
        this.msdfLocAtlas = msdfShader.getUniformLocation("u_atlas");
        this.msdfLocPxRange = msdfShader.getUniformLocation("u_pxRange");
        this.matrixBuf = BufferUtils.createFloatBuffer(16);
    }

    /**
     * Creates the renderer and compiles the text shaders.
     *
     * <p>This renderer requires the modern GL feature set used by the current
     * implementation: core FBO support, core shaders, VAOs, and
     * glMapBufferRange.</p>
     */
    public static CgTextRenderer create(CgCapabilities caps, CgFontRegistry registry) {
        if (!caps.isCoreFbo() || !caps.isCoreShaders() || !caps.isVaoSupported() || !caps.isMapBufferRangeSupported()) {
            throw new IllegalStateException("CgTextRenderer requires modern GL support: core FBO, core shaders, VAO, and glMapBufferRange");
        }

        CgShaderProgram bitmapShader = CgShaderFactory.compile(caps, readShaderSource(BITMAP_VERT), readShaderSource(BITMAP_FRAG));
        CgShaderProgram msdfShader = CgShaderFactory.compile(caps, readShaderSource(MSDF_VERT), readShaderSource(MSDF_FRAG));

        CgGlyphVbo vbo = CgGlyphVbo.create(INITIAL_VBO_CAPACITY);
        vbo.setupAttributes(bitmapShader);
        vbo.setupAttributes(msdfShader);

        LOGGER.info("[CrystalGraphics] CgTextRenderer created: bitmap=" + bitmapShader.getId() + ", msdf=" + msdfShader.getId());
        return new CgTextRenderer(bitmapShader, msdfShader, vbo, registry);
    }

    /**
     * Primary PoseStack-aware draw entry point.
     *
     * <p>Draws a layout at the given local logical origin ({@code x}, {@code y})
     * inside the current top pose. The pose's cumulative transform is used to:
     * <ul>
     *   <li>Derive the effective physical glyph raster size via the context's
     *       {@link CgTextScaleResolver}</li>
     *   <li>Upload the model-view matrix to the text shader as {@code u_modelview}</li>
     * </ul>
     * The {@code x} and {@code y} offsets are applied in local pose space before
     * the shader model-view transformation.</p>
     *
     * @param layout  the pre-built text layout (logical coordinates)
     * @param font    the font to render with
     * @param x       local logical X origin inside the current pose
     * @param y       local logical Y origin inside the current pose
     * @param rgba    packed RGBA color (0xRRGGBBAA)
     * @param frame   current frame number for atlas LRU
     * @param context the render context providing projection and scale resolver
     * @param pose    the current PoseStack providing model-view transform
     */
    public void draw(CgTextLayout layout,
                     CgFont font,
                     float x,
                     float y,
                     int rgba,
                     long frame,
                     CgTextRenderContext context,
                     PoseStack pose) {
        if (deleted) {
            throw new IllegalStateException("CgTextRenderer has been deleted");
        }
        if (layout == null || layout.getLines().isEmpty()) {
            return;
        }

        CgStateSnapshot snapshot = CgStateBoundary.save();
        try {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            drawInternal(layout, font, x, y, rgba, frame,
                    context,
                    pose.last(),
                    context.getScaleResolver());
        } finally {
            CgStateBoundary.restore(snapshot);
        }
    }



    public void delete() {
        if (deleted) {
            return;
        }
        deleted = true;
        vbo.delete();
        bitmapShader.delete();
        msdfShader.delete();
    }

    public boolean isDeleted() {
        return deleted;
    }

    private void drawInternal(CgTextLayout layout,
                              CgFont font,
                              float x,
                              float y,
                              int rgba,
                              long frame,
                              CgTextRenderContext context,
                              PoseStack.Pose pose,
                              CgTextScaleResolver scaleResolver) {
        CgFontKey fontKey = font.getKey();
        CgFontMetrics metrics = layout.getMetrics();

        // Resolve effective physical raster size from pose scale
        int previousEffectiveTargetPx = context.getPreviousEffectiveTargetPx(fontKey);
        int effectiveTargetPx = scaleResolver.resolveEffectiveTargetPx(
                fontKey.getTargetPx(), pose, previousEffectiveTargetPx);
        context.setPreviousEffectiveTargetPx(fontKey, effectiveTargetPx);
        boolean previousMsdf = previousEffectiveTargetPx > 0 ? context.wasMsdf(fontKey) : effectiveTargetPx >= 32;
        boolean wantMsdf = scaleResolver.shouldUseMsdf(effectiveTargetPx, previousMsdf);
        context.setWasMsdf(fontKey, wantMsdf);

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
                    int subPixelBucket = selectSubPixelBucket(effectiveTargetPx, offsetsX[i]);
                    CgGlyphKey glyphKey = new CgGlyphKey(fontKey, glyphIds[i], wantMsdf);
                    regions[index] = registry.ensureGlyphAtEffectiveSize(
                            font, glyphKey, effectiveTargetPx, subPixelBucket, frame);
                    glyphX[index] = penX + offsetsX[i];
                    glyphY[index] = penY + offsetsY[i];
                    penX += advancesX[i];
                    index++;
                }
            }
            penY += metrics.getLineHeight();
        }

        vbo.begin();
        int bitmapQuadCount = appendQuads(regions, glyphX, glyphY, rgba, false);
        int msdfQuadCount = appendQuads(regions, glyphX, glyphY, rgba, true);
        if (bitmapQuadCount == 0 && msdfQuadCount == 0) {
            return;
        }

        boolean blendWasEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean depthWasEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);

        vbo.uploadAndBind();
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_DEPTH_TEST);

        // Prepare model-view matrix from pose
        Matrix4f mv = pose.pose();
        FloatBuffer mvBuf = prepareMatrixBuffer(mv);

        CgRasterFontKey rasterFontKey = new CgRasterFontKey(fontKey, effectiveTargetPx);

        if (bitmapQuadCount > 0) {
            bitmapShader.bind();
            uploadProjectionMatrix(bitmapShader, bitmapLocProjection, context.getProjectionBuffer());
            uploadMatrix(bitmapShader, bitmapLocModelview, mvBuf);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, registry.getBitmapAtlas(rasterFontKey).getTextureId());
            bitmapShader.setUniform1i(bitmapLocAtlas, 0);
            GL11.glDrawElements(GL11.GL_TRIANGLES, bitmapQuadCount * 6, GL11.GL_UNSIGNED_SHORT, 0);
            bitmapShader.unbind();
        }

        if (msdfQuadCount > 0) {
            msdfShader.bind();
            uploadProjectionMatrix(msdfShader, msdfLocProjection, context.getProjectionBuffer());
            uploadMatrix(msdfShader, msdfLocModelview, mvBuf);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, registry.getMsdfAtlas(rasterFontKey).getTextureId());
            msdfShader.setUniform1i(msdfLocAtlas, 0);
            msdfShader.setUniform1f(msdfLocPxRange, CgMsdfGenerator.PX_RANGE);
            long offsetBytes = (long) bitmapQuadCount * 6L * 2L;
            GL11.glDrawElements(GL11.GL_TRIANGLES, msdfQuadCount * 6, GL11.GL_UNSIGNED_SHORT, offsetBytes);
            msdfShader.unbind();
        }

        vbo.unbind();

        if (!blendWasEnabled) {
            GL11.glDisable(GL11.GL_BLEND);
        }
        if (depthWasEnabled) {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
        }
    }

    private int countGlyphs(List<List<CgShapedRun>> lines) {
        int total = 0;
        for (List<CgShapedRun> line : lines) {
            for (CgShapedRun run : line) {
                total += run.getGlyphIds().length;
            }
        }
        return total;
    }

    private int appendQuads(CgAtlasRegion[] regions,
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
            vbo.addGlyph(qx, qy, region.getWidth(), region.getHeight(), region.getU0(), region.getV0(), region.getU1(), region.getV1(), rgba);
            quadCount++;
        }
        return quadCount;
    }

    private void uploadProjectionMatrix(CgShaderProgram shader, int uniformLocation, FloatBuffer projectionMatrix) {
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

    private FloatBuffer prepareMatrixBuffer(Matrix4f matrix) {
        FloatBuffer buf = BufferUtils.createFloatBuffer(16);
        matrix.get(buf);
        buf.rewind();
        return buf;
    }

    private void uploadMatrix(CgShaderProgram shader, int uniformLocation, FloatBuffer matrixData) {
        if (uniformLocation < 0) {
            return;
        }
        matrixData.rewind();
        shader.setUniformMatrix4f(uniformLocation, matrixData);
    }

    /**
     * Selects the sub-pixel bucket based on the effective target pixel size.
     * Uses the effective size (not base targetPx) because the effective size
     * determines whether sub-pixel positioning is perceptible.
     */
    static int selectSubPixelBucket(int effectiveTargetPx, float xOffset) {
        if (effectiveTargetPx >= CgGlyphKey.SUB_PIXEL_BUCKET_MAX_PX) {
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

    static String readShaderSource(String resourcePath) {
        InputStream in = CgTextRenderer.class.getResourceAsStream(resourcePath);
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
