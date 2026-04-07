package io.github.somehussar.crystalgraphics.harness.scene;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.PoseStack;
import io.github.somehussar.crystalgraphics.api.font.CgFont;
import io.github.somehussar.crystalgraphics.api.font.CgFontStyle;
import io.github.somehussar.crystalgraphics.api.font.CgTextLayoutBuilder;
import io.github.somehussar.crystalgraphics.text.cache.CgFontRegistry;
import io.github.somehussar.crystalgraphics.text.atlas.CgGlyphAtlas;
import io.github.somehussar.crystalgraphics.text.render.CgTextRenderContext;
import io.github.somehussar.crystalgraphics.text.render.CgTextRenderer;
import io.github.somehussar.crystalgraphics.text.msdf.CgMsdfAtlasConfig;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.HarnessSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.config.TextSceneConfig;
import io.github.somehussar.crystalgraphics.harness.util.HarnessFontUtil;
import io.github.somehussar.crystalgraphics.harness.util.HarnessOutputDir;
import io.github.somehussar.crystalgraphics.harness.util.ScreenshotUtil;
import io.github.somehussar.crystalgraphics.api.text.CgShapedRun;
import io.github.somehussar.crystalgraphics.api.text.CgTextLayout;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.io.File;
import java.util.List;
import java.util.logging.Logger;

public class TextScene2D implements HarnessSceneLifecycle {

    private static final Logger LOGGER = Logger.getLogger(TextScene2D.class.getName());

    // Matches CrystalGraphicsFontDemo.DEMO_TEXT_2D_LABEL exactly
    private static final String TOP_LABEL_TEXT = "2D UI text: logical size stable, raster scales with pose";
    // Matches CrystalGraphicsFontDemo top-label color (green, 0xAAFFAAFF packed RGBA)
    private static final int TOP_LABEL_COLOR = 0xAAFFAAFF;

    @Override
    public void init(HarnessContext ctx) {
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        // Typed config is resolved before execution in FontDebugHarnessMain
        // and set on the context — no scene-side CLI parsing needed.
        TextSceneConfig config = (TextSceneConfig) ctx.getSceneConfig();
        run(ctx, ctx.getOutputDir(), config);
    }

    @Override
    public void dispose() {
    }

    void run(HarnessContext ctx, String outputDir, TextSceneConfig config) {
        String fontPath = HarnessFontUtil.resolveFontPath(config.getFontPath());
        int fontSizePx = config.getFontSizePx();
        String text = config.getText();
        int fboWidth = config.getWidth();
        int guiScale = config.getGuiScale();
        List<Float> scales = config.getEffectiveScales();

        // Logical UI width mirrors Minecraft's ScaledResolution: the overlay
        // coordinate space is [0, scaledWidth] where scaledWidth = displayWidth / guiScale.
        int logicalWidth = fboWidth / guiScale;
        int logicalHeight = config.getHeight() / guiScale;

        String outputFilename = config.getOutputFilename() != null
                ? config.getOutputFilename()
                : "text-scene.png";

        LOGGER.info("[Harness] Text scene: font=" + fontPath);
        LOGGER.info("[Harness] Text scene: size=" + fontSizePx + "px, text=\"" + text + "\"");
        LOGGER.info("[Harness] Text scene: scales=" + scales + ", guiScale=" + guiScale
                + ", logicalWidth=" + logicalWidth + ", output=" + outputFilename);

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

        CgMsdfAtlasConfig msdfConfig = config.buildMsdfAtlasConfig();
        CgFontRegistry registry = new CgFontRegistry(config.getAtlasSize(), msdfConfig);
        CgTextRenderer renderer = CgTextRenderer.create(caps, registry);
        CgTextRenderer.diagnosticLogging = true;

        CgTextLayoutBuilder layoutBuilder = new CgTextLayoutBuilder();

        CgTextLayout topLabelLayout = layoutBuilder.layout(TOP_LABEL_TEXT, font, (float) fboWidth, 0);
        int topLabelBandHeight = (int) Math.ceil(topLabelLayout.getTotalHeight()) + 20;
        LOGGER.info("[Harness] Top label: layout " + topLabelLayout.getLines().size()
                + " lines, width=" + topLabelLayout.getTotalWidth()
                + ", height=" + topLabelLayout.getTotalHeight()
                + ", wrapWidth=" + fboWidth + " (full FBO, identity pose)");

        // Measurement pass: compute per-band pixel height based on actual
        // layout line count and pose scale, with padding for margin.
        int bandPadding = 20; // 10px top + 10px bottom margin
        String[] labels = new String[scales.size()];
        CgTextLayout[] layouts = new CgTextLayout[scales.size()];
        int[] bandHeights = new int[scales.size()];
        int[] bandYOffsets = new int[scales.size()];
        int totalBandHeight = topLabelBandHeight;
        for (int i = 0; i < scales.size(); i++) {
            float scale = scales.get(i);
            labels[i] = text + " [base " + fontSizePx + "px, pose "
                    + String.format("%.1f", scale) + "x]";
            float logicalMaxWidth = fboWidth / scale;
            layouts[i] = layoutBuilder.layout(labels[i], font, logicalMaxWidth, 0);
            // The layout reports logical height; the pose scale magnifies it on screen.
            float scaledHeight = layouts[i].getTotalHeight() * scale;
            bandHeights[i] = (int) Math.ceil(scaledHeight) + bandPadding;
            bandYOffsets[i] = totalBandHeight;
            totalBandHeight += bandHeights[i];
            LOGGER.info("[Harness] Scale " + scale + "x: layout " + layouts[i].getLines().size()
                    + " lines, width=" + layouts[i].getTotalWidth()
                    + ", height=" + layouts[i].getTotalHeight()
                    + ", bandPixelH=" + bandHeights[i]);
        }

        int fboHeight = config.isMultiScaleMode()
                ? totalBandHeight
                : config.getHeight();

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
        long frame = 1;

        if (config.isMtsdf()) {
            frame = prewarmDistanceFieldGlyphs(renderer, registry,
                    font,
                    topLabelLayout, 20.0f, 20.0f, TOP_LABEL_COLOR,
                    labels, layouts, bandYOffsets, scales,
                    renderContext, frame);
        }

        // Draw top label: exact replication of CrystalGraphicsFontDemo's
        // identity-pose green label (position 20,20 — color 0xAAFFAAFF)
        renderContext.clearHistory();
        PoseStack topLabelPose = new PoseStack();
        renderer.draw(topLabelLayout, font, 20.0f, 20.0f, TOP_LABEL_COLOR, frame,
                renderContext, topLabelPose);
        frame++;

        for (int bandIdx = 0; bandIdx < scales.size(); bandIdx++) {
            float scale = scales.get(bandIdx);
            CgTextLayout layout = layouts[bandIdx];

            if (bandIdx == 0) {
                logGlyphDiagnostics(layout, labels[bandIdx]);
            }

            PoseStack poseStack = new PoseStack();
            float xDraw = 20.0f;
            float yDraw = config.isMultiScaleMode()
                    ? bandYOffsets[bandIdx] + 10.0f
                    : 40.0f;

            if (scale != 1.0f) {
                poseStack.last().pose().scale(scale, scale, 1.0f);
                // Compensate draw coords: pose scale multiplies them, so divide
                // to keep the final screen position stable across scales
                xDraw /= scale;
                yDraw /= scale;
            }

            renderContext.clearHistory();
            renderer.draw(layout, font, xDraw, yDraw, 0xFFFFFF, frame,
                    renderContext, poseStack);
            frame++;
        }

        GL11.glFinish();

        ScreenshotUtil.captureFboColorTexture(fbo, colorTex,
                fboWidth, fboHeight, outputDir, outputFilename);

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

    private long prewarmDistanceFieldGlyphs(CgTextRenderer renderer,
                                            CgFontRegistry registry,
                                            CgFont font,
                                            CgTextLayout topLabelLayout,
                                            float topLabelX,
                                            float topLabelY,
                                            int topLabelColor,
                                            String[] labels,
                                            CgTextLayout[] layouts,
                                            int[] bandYOffsets,
                                            List<Float> scales,
                                            CgTextRenderContext renderContext,
                                            long frame) {
        int totalChars = TOP_LABEL_TEXT.length();
        for (String label : labels) {
            totalChars += label.length();
        }
        int warmupFrames = Math.max(8, (totalChars / 4) + 6);

        for (int i = 0; i < warmupFrames; i++) {
            long drawFrame = frame + i;
            registry.tickFrame(drawFrame);

            renderContext.clearHistory();
            PoseStack topLabelPose = new PoseStack();
            renderer.draw(topLabelLayout, font,
                    topLabelX, topLabelY, topLabelColor, drawFrame, renderContext, topLabelPose);

            for (int bandIdx = 0; bandIdx < scales.size(); bandIdx++) {
                float scale = scales.get(bandIdx);
                PoseStack poseStack = new PoseStack();
                float xDraw = 20.0f;
                float yDraw = bandYOffsets[bandIdx] + 10.0f;
                if (scale != 1.0f) {
                    poseStack.last().pose().scale(scale, scale, 1.0f);
                    xDraw /= scale;
                    yDraw /= scale;
                }
                renderContext.clearHistory();
                renderer.draw(layouts[bandIdx], font, xDraw, yDraw,
                        0xFFFFFF, drawFrame, renderContext, poseStack);
            }
        }

        GL11.glClearColor(0.15f, 0.15f, 0.2f, 1.0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        return frame + warmupFrames;
    }

    private void logGlyphDiagnostics(CgTextLayout layout, String text) {
        int charIdx = 0;
        for (int lineIdx = 0; lineIdx < layout.getLines().size(); lineIdx++) {
            List<CgShapedRun> line = layout.getLines().get(lineIdx);
            float penX = 0;
            for (CgShapedRun run : line) {
                float[] advances = run.getAdvancesX();
                float[] offsetsX = run.getOffsetsX();
                int[] glyphIds = run.getGlyphIds();
                for (int i = 0; i < glyphIds.length; i++) {
                    char ch = charIdx < text.length() ? text.charAt(charIdx) : '?';
                    LOGGER.info(String.format("[Diag] glyph[%d] '%c' glyphId=%d penX=%.3f advance=%.3f offsetX=%.3f",
                            charIdx, ch, glyphIds[i], penX, advances[i], offsetsX[i]));
                    penX += advances[i];
                    charIdx++;
                }
            }
        }
    }
}
