package io.github.somehussar.crystalgraphics.harness.scene;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.PoseStack;
import io.github.somehussar.crystalgraphics.api.font.CgFont;
import io.github.somehussar.crystalgraphics.api.font.CgFontStyle;
import io.github.somehussar.crystalgraphics.api.font.CgTextLayoutBuilder;
import io.github.somehussar.crystalgraphics.gl.text.CgFontRegistry;
import io.github.somehussar.crystalgraphics.gl.text.CgGlyphAtlas;
import io.github.somehussar.crystalgraphics.gl.text.CgMsdfGenerator;
import io.github.somehussar.crystalgraphics.gl.text.CgTextRenderContext;
import io.github.somehussar.crystalgraphics.gl.text.CgTextRenderer;
import io.github.somehussar.crystalgraphics.harness.*;
import io.github.somehussar.crystalgraphics.harness.config.AtlasDumpConfig;
import io.github.somehussar.crystalgraphics.harness.config.HarnessConfig;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.util.HarnessFontUtil;
import io.github.somehussar.crystalgraphics.harness.util.HarnessOutputDir;
import io.github.somehussar.crystalgraphics.harness.util.ScreenshotUtil;
import io.github.somehussar.crystalgraphics.text.CgTextLayout;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.io.File;
import java.util.logging.Logger;

public class AtlasDumpScene implements HarnessScene {

    private static final Logger LOGGER = Logger.getLogger(AtlasDumpScene.class.getName());

    private static final int FBO_WIDTH = 800;
    private static final int FBO_HEIGHT = 600;

    @Override
    public void run(HarnessContext ctx, String outputDir) {
        AtlasDumpConfig config = AtlasDumpConfig.create(HarnessConfig.getGlobalCliArgs());
        run(ctx, outputDir, config);
    }

    void run(HarnessContext ctx, String outputDir, AtlasDumpConfig config) {
        String fontPath = HarnessFontUtil.resolveFontPath(config.getFontPath());
        int bitmapPxSize = config.getBitmapPxSize();
        int msdfPxSize = config.getMsdfPxSize();
        String text = config.getText();
        AtlasDumpConfig.AtlasType atlasType = config.getAtlasType();

        boolean wantMsdf = atlasType == AtlasDumpConfig.AtlasType.MSDF
                || atlasType == AtlasDumpConfig.AtlasType.BOTH;
        if (wantMsdf && msdfPxSize < AtlasDumpConfig.MIN_MSDF_PX_SIZE) {
            throw new IllegalArgumentException(
                    "MSDF atlas requires --msdf-px-size >= " + AtlasDumpConfig.MIN_MSDF_PX_SIZE
                    + ", got " + msdfPxSize);
        }

        LOGGER.info("[Harness] Atlas dump: font=" + fontPath);
        LOGGER.info("[Harness] Atlas dump: atlasType=" + atlasType
                + ", bitmapPxSize=" + bitmapPxSize + ", msdfPxSize=" + msdfPxSize);
        LOGGER.info("[Harness] Atlas dump: text=\"" + text + "\"");

        String atlasDir = outputDir + File.separator + "atlas";
        HarnessOutputDir.ensureExists(atlasDir);

        CgCapabilities caps = CgCapabilities.detect();
        LOGGER.info("[Harness] Capabilities: coreFbo=" + caps.isCoreFbo()
                + ", coreShaders=" + caps.isCoreShaders()
                + ", vao=" + caps.isVaoSupported()
                + ", mapBufferRange=" + caps.isMapBufferRangeSupported());

        if (!caps.isCoreFbo() || !caps.isCoreShaders()
                || !caps.isVaoSupported() || !caps.isMapBufferRangeSupported()) {
            throw new IllegalStateException(
                    "Atlas dump scene requires modern GL: core FBO, core shaders, VAO, glMapBufferRange");
        }

        int registryAtlasSize = computeAtlasSize(msdfPxSize, text.length());
        CgFontRegistry registry = new CgFontRegistry(registryAtlasSize);
        CgTextRenderer renderer = CgTextRenderer.create(caps, registry);
        CgTextLayoutBuilder layoutBuilder = new CgTextLayoutBuilder();

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
            throw new RuntimeException("Atlas dump FBO incomplete: 0x" + Integer.toHexString(status));
        }

        GL11.glViewport(0, 0, FBO_WIDTH, FBO_HEIGHT);
        GL11.glClearColor(0.15f, 0.15f, 0.2f, 1.0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

        CgTextRenderContext renderContext = CgTextRenderContext.orthographic(FBO_WIDTH, FBO_HEIGHT);
        PoseStack poseStack = new PoseStack();
        long frame = 1;

        boolean wantBitmap = atlasType == AtlasDumpConfig.AtlasType.BITMAP
                || atlasType == AtlasDumpConfig.AtlasType.BOTH;

        CgFont bitmapFont = null;
        if (wantBitmap) {
            bitmapFont = CgFont.load(fontPath, CgFontStyle.REGULAR, bitmapPxSize);
            CgTextLayout bitmapLayout = layoutBuilder.layout(
                    text + " [" + bitmapPxSize + "px]", bitmapFont, FBO_WIDTH, 0);
            renderer.draw(bitmapLayout, bitmapFont, 20.0f, 40.0f, 0xFFFFFF, frame,
                    renderContext, poseStack);
            LOGGER.info("[Harness] Bitmap pass: drew at " + bitmapPxSize + "px");
        }

        CgFont msdfFont = null;
        if (wantMsdf) {
            msdfFont = CgFont.load(fontPath, CgFontStyle.REGULAR, msdfPxSize);
            CgTextLayout msdfLayout = layoutBuilder.layout(
                    text + " [" + msdfPxSize + "px]", msdfFont, FBO_WIDTH, 0);

            // CgMsdfGenerator.MAX_PER_FRAME=4, so we need enough frames
            // for all unique glyphs. tickFrame() resets the per-frame budget.
            int framesNeeded = (text.length() / 4) + 5;
            for (long f = 1; f <= framesNeeded; f++) {
                registry.tickFrame(frame + f);
                renderer.draw(msdfLayout, msdfFont, 20.0f, 80.0f, 0xFFFFFF, frame + f,
                        renderContext, poseStack);
            }
            frame += framesNeeded;
            LOGGER.info("[Harness] MSDF pass: drew " + framesNeeded
                    + " frames at " + msdfPxSize + "px");
        }

        GL11.glFinish();

        if (wantBitmap && bitmapFont != null) {
            String filename = "bitmap-atlas-dump-" + bitmapPxSize + "px.png";
            CgGlyphAtlas bitmapAtlas = registry.findPopulatedBitmapAtlas(bitmapFont.getKey());
            if (bitmapAtlas != null) {
                LOGGER.info("[Harness] Bitmap atlas captured: texture=" + bitmapAtlas.getTextureId()
                        + ", size=" + bitmapAtlas.getPageWidth() + "x" + bitmapAtlas.getPageHeight());
                ScreenshotUtil.captureTexture(bitmapAtlas.getTextureId(),
                        bitmapAtlas.getPageWidth(), bitmapAtlas.getPageHeight(),
                        0x8229, atlasDir, filename);
            } else {
                LOGGER.warning("[Harness] Bitmap atlas not available after rendering");
            }
        }

        if (wantMsdf && msdfFont != null) {
            String filename = "msdf-atlas-dump-" + msdfPxSize + "px.png";
            CgGlyphAtlas msdfAtlas = registry.findPopulatedMsdfAtlas(msdfFont.getKey());
            if (msdfAtlas != null) {
                LOGGER.info("[Harness] MSDF atlas captured: texture=" + msdfAtlas.getTextureId()
                        + ", size=" + msdfAtlas.getPageWidth() + "x" + msdfAtlas.getPageHeight());
                ScreenshotUtil.captureTexture(msdfAtlas.getTextureId(),
                        msdfAtlas.getPageWidth(), msdfAtlas.getPageHeight(),
                        GL30.GL_RGB16F, atlasDir, filename);
            } else {
                LOGGER.warning("[Harness] MSDF atlas not available after rendering");
            }
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        renderer.delete();
        registry.releaseAll();
        if (msdfFont != null) {
            msdfFont.dispose();
        }
        if (bitmapFont != null) {
            bitmapFont.dispose();
        }
        GL30.glDeleteFramebuffers(fbo);
        GL11.glDeleteTextures(colorTex);

        LOGGER.info("[Harness] Atlas dump scene complete.");
    }

    private static int computeAtlasSize(int msdfPxSize, int glyphCount) {
        int cellSize = CgMsdfGenerator.cellSizeForFontPx(msdfPxSize);
        int cellsPerRow = 1024 / cellSize;
        int totalSlots = cellsPerRow * cellsPerRow;
        if (totalSlots >= glyphCount) {
            return 1024;
        }
        cellsPerRow = 2048 / cellSize;
        totalSlots = cellsPerRow * cellsPerRow;
        if (totalSlots >= glyphCount) {
            return 2048;
        }
        return 4096;
    }
}
