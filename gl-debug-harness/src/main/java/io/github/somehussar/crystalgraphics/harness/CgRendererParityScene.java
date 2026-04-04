package io.github.somehussar.crystalgraphics.harness;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.PoseStack;
import io.github.somehussar.crystalgraphics.api.font.CgFont;
import io.github.somehussar.crystalgraphics.api.font.CgFontStyle;
import io.github.somehussar.crystalgraphics.api.font.CgTextLayoutBuilder;
import io.github.somehussar.crystalgraphics.gl.text.CgFontRegistry;
import io.github.somehussar.crystalgraphics.gl.text.CgGlyphAtlas;
import io.github.somehussar.crystalgraphics.gl.text.CgTextRenderContext;
import io.github.somehussar.crystalgraphics.gl.text.CgTextRenderer;
import io.github.somehussar.crystalgraphics.text.CgTextLayout;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.logging.Logger;

/**
 * Parity scene that exercises the <strong>same rendering pipeline</strong>
 * as {@code CrystalGraphicsFontDemo} in Minecraft, but running inside the
 * standalone harness with its known-good orthographic matrix.
 *
 * <h3>What This Tests</h3>
 * <p>This scene uses the production {@link CgTextRenderer} directly, ensuring
 * that harness rendering is bit-for-bit aligned with the in-game path. The
 * ONLY difference from the in-game path is the absence of
 * {@code CgStateBoundary.save()/restore()} for the outer GL state management
 * (the renderer still calls it internally during draw).</p>
 *
 * <h3>PoseStack Integration</h3>
 * <p>The scene uses a {@link PoseStack} and {@link CgTextRenderContext} to
 * drive text rendering through the same PoseStack-aware path used by the
 * Minecraft demo. This eliminates the previous duplication of
 * {@code drawInternal()} logic and stale threshold/backend rules.</p>
 */
final class CgRendererParityScene implements HarnessScene {

    private static final Logger LOGGER = Logger.getLogger(CgRendererParityScene.class.getName());

    private static final String TEST_STRING = "CrystalGraphics font demo - mouse wheel zoom";
    private static final int FONT_SIZE_PX = 24;
    private static final int FBO_WIDTH = 800;
    private static final int FBO_HEIGHT = 600;

    @Override
    public void run(HarnessContext ctx, String outputDir) {
        LOGGER.info("[Harness] Renderer parity scene: text=\"" + TEST_STRING + "\"");
        LOGGER.info("[Harness] Renderer parity scene: size=" + FONT_SIZE_PX + "px");

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

        String fontPath = findFontPath();
        LOGGER.info("[Harness] Font path: " + fontPath);

        CgFont font = CgFont.load(fontPath, CgFontStyle.REGULAR, FONT_SIZE_PX);
        LOGGER.info("[Harness] Font loaded: " + font.getKey());
        LOGGER.info("[Harness] Font metrics: ascender=" + font.getMetrics().getAscender()
                + ", descender=" + font.getMetrics().getDescender()
                + ", lineHeight=" + font.getMetrics().getLineHeight());

        CgFontRegistry registry = new CgFontRegistry();
        CgTextRenderer renderer = CgTextRenderer.create(caps, registry);

        CgTextLayoutBuilder layoutBuilder = new CgTextLayoutBuilder();
        CgTextLayout layout = layoutBuilder.layout(
                TEST_STRING + " [" + FONT_SIZE_PX + "px]",
                font,
                FBO_WIDTH,
                0
        );
        LOGGER.info("[Harness] Layout: " + layout.getLines().size() + " lines, "
                + "width=" + layout.getTotalWidth() + ", height=" + layout.getTotalHeight());

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

        CgTextRenderContext renderContext = CgTextRenderContext.orthographic(FBO_WIDTH, FBO_HEIGHT);
        PoseStack poseStack = new PoseStack();
        long frame = 1;

        renderer.draw(layout, font, 20.0f, 40.0f, 0xFFFFFF, frame,
                renderContext, poseStack);

        GL11.glFinish();

        ScreenshotUtil.captureFboColorTexture(fbo, colorTex,
                FBO_WIDTH, FBO_HEIGHT, outputDir, "renderer-parity.png");

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

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        renderer.delete();
        registry.releaseAll();
        font.dispose();
        GL30.glDeleteFramebuffers(fbo);
        GL11.glDeleteTextures(colorTex);

        LOGGER.info("[Harness] Renderer parity scene complete.");
    }

    private String findFontPath() {
        String testFont = "src/main/resources/assets/crystalgraphics/test-font.ttf";
        java.io.File f = new java.io.File(testFont);
        if (f.exists()) {
            return f.getAbsolutePath();
        }
        return AtlasDumpScene.findSystemFont();
    }
}
