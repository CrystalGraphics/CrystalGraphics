package io.github.somehussar.crystalgraphics.harness.scene;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.PoseStack;
import io.github.somehussar.crystalgraphics.api.font.CgFont;
import io.github.somehussar.crystalgraphics.api.font.CgFontStyle;
import io.github.somehussar.crystalgraphics.api.font.CgTextLayoutBuilder;
import io.github.somehussar.crystalgraphics.gl.text.CgFontRegistry;
import io.github.somehussar.crystalgraphics.gl.text.CgGlyphAtlas;
import io.github.somehussar.crystalgraphics.gl.text.CgTextRenderContext;
import io.github.somehussar.crystalgraphics.gl.text.CgTextRenderer;
import io.github.somehussar.crystalgraphics.harness.*;
import io.github.somehussar.crystalgraphics.harness.config.HarnessConfig;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.config.TextSceneConfig;
import io.github.somehussar.crystalgraphics.harness.util.HarnessFontUtil;
import io.github.somehussar.crystalgraphics.harness.util.HarnessOutputDir;
import io.github.somehussar.crystalgraphics.harness.util.ScreenshotUtil;
import io.github.somehussar.crystalgraphics.text.CgTextLayout;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.io.File;
import java.util.logging.Logger;

public class TextScene implements HarnessScene {

    private static final Logger LOGGER = Logger.getLogger(TextScene.class.getName());

    @Override
    public void run(HarnessContext ctx, String outputDir) {
        TextSceneConfig config = TextSceneConfig.create(HarnessConfig.getGlobalCliArgs());
        run(ctx, outputDir, config);
    }

    void run(HarnessContext ctx, String outputDir, TextSceneConfig config) {
        String fontPath = HarnessFontUtil.resolveFontPath(config.getFontPath());
        int fontSizePx = config.getFontSizePx();
        String text = config.getText();
        int fboWidth = config.getWidth();
        int fboHeight = config.getHeight();

        LOGGER.info("[Harness] Text scene: font=" + fontPath);
        LOGGER.info("[Harness] Text scene: size=" + fontSizePx + "px, text=\"" + text + "\"");

        CgCapabilities caps = CgCapabilities.detect();
        if (!caps.isCoreFbo() || !caps.isCoreShaders()
                || !caps.isVaoSupported() || !caps.isMapBufferRangeSupported()) {
            throw new IllegalStateException(
                    "Text scene requires modern GL: core FBO, core shaders, VAO, glMapBufferRange");
        }

        CgFont font = CgFont.load(fontPath, CgFontStyle.REGULAR, fontSizePx);
        LOGGER.info("[Harness] Font loaded: " + font.getKey());
        LOGGER.info("[Harness] Font metrics: ascender=" + font.getMetrics().getAscender()
                + ", descender=" + font.getMetrics().getDescender()
                + ", lineHeight=" + font.getMetrics().getLineHeight());

        CgFontRegistry registry = new CgFontRegistry();
        CgTextRenderer renderer = CgTextRenderer.create(caps, registry);

        CgTextLayoutBuilder layoutBuilder = new CgTextLayoutBuilder();
        CgTextLayout layout = layoutBuilder.layout(
                text + " [" + fontSizePx + "px]",
                font,
                fboWidth,
                0
        );
        LOGGER.info("[Harness] Layout: " + layout.getLines().size() + " lines, "
                + "width=" + layout.getTotalWidth() + ", height=" + layout.getTotalHeight());

        int fbo = GL30.glGenFramebuffers();
        int colorTex = GL11.glGenTextures();

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
                fboWidth, fboHeight, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
                GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, colorTex, 0);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("Text scene FBO incomplete: 0x" + Integer.toHexString(status));
        }

        GL11.glViewport(0, 0, fboWidth, fboHeight);
        GL11.glClearColor(0.15f, 0.15f, 0.2f, 1.0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

        CgTextRenderContext renderContext = CgTextRenderContext.orthographic(fboWidth, fboHeight);
        PoseStack poseStack = new PoseStack();
        long frame = 1;

        renderer.draw(layout, font, 20.0f, 40.0f, 0xFFFFFF, frame,
                renderContext, poseStack);

        GL11.glFinish();

        ScreenshotUtil.captureFboColorTexture(fbo, colorTex,
                fboWidth, fboHeight, outputDir, "text-scene.png");

        if (config.isDumpBitmapAtlas()) {
            String atlasDir = outputDir + File.separator + "atlas";
            HarnessOutputDir.ensureExists(atlasDir);
            String filename = "atlas-dump-" + fontSizePx + "px.png";
            CgGlyphAtlas bitmapAtlas = registry.findPopulatedBitmapAtlas(font.getKey());
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

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        renderer.delete();
        registry.releaseAll();
        font.dispose();
        GL30.glDeleteFramebuffers(fbo);
        GL11.glDeleteTextures(colorTex);

        LOGGER.info("[Harness] Text scene complete.");
    }
}
