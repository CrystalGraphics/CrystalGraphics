package io.github.somehussar.crystalgraphics.harness.scene;

import com.crystalgraphics.msdfgen.FreeTypeMSDFIntegration;
import com.crystalgraphics.msdfgen.MSDFException;
import com.crystalgraphics.msdfgen.MSDFShape;
import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.PoseStack;
import io.github.somehussar.crystalgraphics.api.font.CgFont;
import io.github.somehussar.crystalgraphics.api.font.CgFontStyle;
import io.github.somehussar.crystalgraphics.api.font.CgGlyphKey;
import io.github.somehussar.crystalgraphics.api.font.CgTextLayoutBuilder;
import io.github.somehussar.crystalgraphics.text.cache.CgFontRegistry;
import io.github.somehussar.crystalgraphics.text.atlas.CgGlyphAtlas;
import io.github.somehussar.crystalgraphics.text.atlas.CgGlyphAtlasPage;
import io.github.somehussar.crystalgraphics.text.msdf.CgMsdfGenerator;
import io.github.somehussar.crystalgraphics.text.render.CgTextRenderContext;
import io.github.somehussar.crystalgraphics.text.render.CgTextRenderer;
import io.github.somehussar.crystalgraphics.text.msdf.CgMsdfAtlasConfig;
import io.github.somehussar.crystalgraphics.text.msdf.CgMsdfGlyphLayout;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.HarnessSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.AtlasDumpConfig;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.tool.AtlasDumper;
import io.github.somehussar.crystalgraphics.harness.tool.MsdfVerificationTool;
import io.github.somehussar.crystalgraphics.harness.tool.MsdfAtlasSizeEstimator;
import io.github.somehussar.crystalgraphics.harness.util.HarnessFontUtil;
import io.github.somehussar.crystalgraphics.harness.util.HarnessOutputDir;
import io.github.somehussar.crystalgraphics.harness.util.ScreenshotUtil;
import io.github.somehussar.crystalgraphics.api.text.CgTextLayout;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.io.File;
import java.util.*;
import java.util.logging.Logger;

public class AtlasDumpScene implements HarnessSceneLifecycle {

    private static final Logger LOGGER = Logger.getLogger(AtlasDumpScene.class.getName());

    @Override
    public void init(HarnessContext ctx) {
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        // Typed config is resolved before execution in FontDebugHarnessMain
        // and set on the context — no scene-side CLI parsing needed.
        AtlasDumpConfig config = (AtlasDumpConfig) ctx.getSceneConfig();
        run(ctx, ctx.getOutputDir(), config);
    }

    @Override
    public void dispose() {
    }

    void run(HarnessContext ctx, String outputDir, AtlasDumpConfig config) {
        int fboWidth = ctx.getScreenWidth();
        int fboHeight = ctx.getScreenHeight();
        String fontPath = HarnessFontUtil.resolveFontPath(config.getFontPath());
        int bitmapPxSize = config.getBitmapPxSize();
        int msdfPxSize = config.getMsdfPxSize();
        int msdfAtlasScale = config.getMsdfAtlasScale() > 0
                ? config.getMsdfAtlasScale()
                : CgMsdfAtlasConfig.DEFAULT_ATLAS_SCALE_PX;
        String text = config.getText();
        AtlasDumpConfig.AtlasType atlasType = config.getAtlasType();
        boolean dumpAllPages = config.isDumpAllPages();
        boolean parityPrewarm = config.isParityPrewarm();
        boolean prewarmBitmap = config.isPrewarmBitmap();
        int atlasPageSize = config.getAtlasPageSize();

        boolean wantMsdf = atlasType == AtlasDumpConfig.AtlasType.MSDF
                || atlasType == AtlasDumpConfig.AtlasType.MTSDF
                || atlasType == AtlasDumpConfig.AtlasType.BOTH;
        if (wantMsdf && msdfPxSize < AtlasDumpConfig.MIN_MSDF_PX_SIZE) {
            throw new IllegalArgumentException(
                    "MSDF atlas requires --msdf-px-size >= " + AtlasDumpConfig.MIN_MSDF_PX_SIZE
                    + ", got " + msdfPxSize);
        }

        LOGGER.info("[Harness] Atlas dump: font=" + fontPath);
        LOGGER.info("[Harness] Atlas dump: atlasType=" + atlasType
                + ", bitmapPxSize=" + bitmapPxSize + ", msdfPxSize=" + msdfPxSize
                + ", msdfAtlasScale=" + msdfAtlasScale);
        LOGGER.info("[Harness] Atlas dump: text=\"" + text + "\"");
        LOGGER.info("[Harness] Atlas dump: dumpAllPages=" + dumpAllPages
                + ", parityPrewarm=" + parityPrewarm + ", prewarmBitmap=" + prewarmBitmap
                + ", atlasPageSize=" + (atlasPageSize == AtlasDumpConfig.ATLAS_PAGE_SIZE_AUTO
                        ? "auto" : atlasPageSize));

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

        // Resolve effective atlas size: CLI override takes precedence, then parity estimator,
        // then legacy auto-compute for non-parity smoke runs.
        int registryAtlasSize;
        if (atlasPageSize != AtlasDumpConfig.ATLAS_PAGE_SIZE_AUTO) {
            registryAtlasSize = atlasPageSize;
            LOGGER.info("[Harness] Using explicit atlas page size: " + registryAtlasSize);
        } else if (wantMsdf && parityPrewarm) {
            CgFont estimatorCgFont = CgFont.load(fontPath, CgFontStyle.REGULAR, msdfPxSize);
            try {
                FreeTypeMSDFIntegration.Font estimatorFont = estimatorCgFont.getMsdfFont();
                if (estimatorFont != null) {
                    CgMsdfAtlasConfig estimatorConfig = CgMsdfAtlasConfig.forHarnessParity(msdfAtlasScale, null);
                    registryAtlasSize = MsdfAtlasSizeEstimator.estimate(estimatorFont, text, estimatorConfig);
                    LOGGER.info("[Harness] Estimated parity MSDF atlas size: " + registryAtlasSize);
                } else {
                    registryAtlasSize = computeAtlasSize(msdfPxSize, text.length());
                    LOGGER.info("[Harness] MSDF estimator unavailable, falling back to coarse auto-size: " + registryAtlasSize);
                }
            } finally {
                estimatorCgFont.dispose();
            }
        } else {
            registryAtlasSize = computeAtlasSize(msdfPxSize, text.length());
            LOGGER.info("[Harness] Auto-computed atlas size: " + registryAtlasSize);
        }

        CgMsdfAtlasConfig registryMsdfConfig = config.buildMsdfAtlasConfig(registryAtlasSize);
        CgFontRegistry registry = new CgFontRegistry(registryAtlasSize, registryMsdfConfig);
        CgTextRenderer renderer = CgTextRenderer.create(caps, registry);
        CgTextLayoutBuilder layoutBuilder = new CgTextLayoutBuilder();

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
            throw new RuntimeException("Atlas dump FBO incomplete: 0x" + Integer.toHexString(status));
        }

        GL11.glViewport(0, 0, fboWidth, fboHeight);
        GL11.glClearColor(0.15f, 0.15f, 0.2f, 1.0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

        CgTextRenderContext renderContext = CgTextRenderContext.orthographic(fboWidth, fboHeight);
        PoseStack poseStack = new PoseStack();
        long frame = 1;

        boolean wantBitmap = atlasType == AtlasDumpConfig.AtlasType.BITMAP
                || atlasType == AtlasDumpConfig.AtlasType.BOTH;

        CgFont bitmapFont = null;
        if (wantBitmap) {
            bitmapFont = CgFont.load(fontPath, CgFontStyle.REGULAR, bitmapPxSize);
            CgTextLayout bitmapLayout = layoutBuilder.layout(text, bitmapFont, fboWidth, 0);

            if (prewarmBitmap) {
                // Deterministic prewarm: render enough frames so every unique glyph
                // is rasterized and allocated before the dump capture. This produces
                // denser packing because all glyphs are present simultaneously.
                frame = prewarmAllGlyphs(registry, renderer, bitmapLayout, bitmapFont,
                        text, 20.0f, 40.0f, frame, renderContext, poseStack);
                LOGGER.info("[Harness] Bitmap prewarm complete at frame " + frame);
            } else {
                renderer.draw(bitmapLayout, bitmapFont, 20.0f, 40.0f, 0xFFFFFF, frame,
                        renderContext, poseStack);
                LOGGER.info("[Harness] Bitmap pass: drew at " + bitmapPxSize + "px");
            }
        }

        CgFont msdfFont = null;
        if (wantMsdf) {
            msdfFont = CgFont.load(fontPath, CgFontStyle.REGULAR, msdfPxSize);
            CgTextLayout msdfLayout = layoutBuilder.layout(text, msdfFont, fboWidth, 0);

            if (parityPrewarm) {
                // Deterministic parity prewarm: render many frames with the full text
                // so that every unique MSDF glyph is generated, bypassing the per-frame
                // budget limit. This is the static build mode described in plan §7.9:
                // it loads the full glyph set up front and generates all MSDF glyph
                // placements before capturing, producing dense atlas packing comparable
                // to msdf-atlas-gen's static output.
                frame = prewarmMsdfGlyphsLargestFirst(registry, msdfFont, text, frame);
                LOGGER.info("[Harness] MSDF parity prewarm complete at frame " + frame);
            } else {
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
        }

        GL11.glFinish();

        // ── Atlas capture and dump ─────────────────────────────────────
        if (wantBitmap && bitmapFont != null) {
            dumpBitmapAtlases(registry, bitmapFont, bitmapPxSize, dumpAllPages, atlasDir);
        }

        if (wantMsdf && msdfFont != null) {
            registry.awaitAsyncGlyphs(5000L);
            registry.tickFrame(frame + 1);
            String dfPrefix = (atlasType == AtlasDumpConfig.AtlasType.MTSDF) ? "mtsdf" : "msdf";
            int dfGlFormat = (atlasType == AtlasDumpConfig.AtlasType.MTSDF)
                    ? 0x881A /* GL_RGBA16F */
                    : GL30.GL_RGB16F;
            dumpMsdfAtlases(registry, msdfFont, msdfPxSize, dumpAllPages, atlasDir, dfPrefix, dfGlFormat);
            if (config.isVerifyMsdf()) {
                MsdfVerificationTool verifier = new MsdfVerificationTool();
                MsdfVerificationTool.VerificationSummary summary = verifier.verifyText(
                        msdfFont,
                        text,
                        registryMsdfConfig,
                        config.buildMsdfVerificationConfig(),
                        atlasDir,
                        dfPrefix + "-verify-" + msdfPxSize + "px");
                LOGGER.info("[Harness] " + verifier.getClass().getSimpleName()
                        + " verification complete: glyphs=" + summary.getGlyphCount()
                        + ", failing=" + summary.getFailingGlyphCount()
                        + ", worstMismatch=" + summary.getWorstMismatchRatio());
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

    // ── Prewarm logic ──────────────────────────────────────────────────

    /**
     * Runs the renderer repeatedly until all unique glyphs in the layout
     * are fully generated and allocated. Returns the frame counter after
     * prewarm completes.
     *
     * <p>The loop renders the full text each frame, ticking the registry
     * between frames to reset the per-frame MSDF generation budget
     * ({@code CgMsdfGenerator.MAX_PER_FRAME}). It terminates when two
     * consecutive frames produce no new atlas allocations, meaning all
     * glyphs have been generated.</p>
     *
     * <p>Safety cap: stops after {@code maxFrames} iterations to prevent
     * infinite loops if glyph generation stalls.</p>
     */
    private long prewarmAllGlyphs(CgFontRegistry registry,
                                   CgTextRenderer renderer,
                                   CgTextLayout layout,
                                   CgFont font,
                                   String text,
                                   float x, float y,
                                   long startFrame,
                                   CgTextRenderContext renderContext,
                                   PoseStack poseStack) {
        // Upper bound: each unique char needs at most 1 frame per MAX_PER_FRAME slot.
        // Add generous headroom for multi-pass convergence and edge cases.
        int uniqueChars = countUniqueChars(text);
        int maxFrames = (uniqueChars / CgMsdfGenerator.MAX_PER_FRAME) + 20;

        long frame = startFrame;
        int stableCount = 0;
        int prevTotalSlots = countTotalAtlasSlots(registry, font);

        for (int i = 0; i < maxFrames; i++) {
            frame++;
            registry.tickFrame(frame);
            renderer.draw(layout, font, x, y, 0xFFFFFF, frame, renderContext, poseStack);

            int currentSlots = countTotalAtlasSlots(registry, font);
            if (currentSlots == prevTotalSlots) {
                stableCount++;
                // Two consecutive frames with no new allocations = prewarm converged
                if (stableCount >= 2) {
                    LOGGER.info("[Harness] Prewarm converged after " + (i + 1)
                            + " frames, total slots=" + currentSlots);
                    break;
                }
            } else {
                stableCount = 0;
                prevTotalSlots = currentSlots;
            }
        }

        return frame;
    }

    private int countTotalAtlasSlots(CgFontRegistry registry, CgFont font) {
        int total = 0;

        List<CgGlyphAtlasPage> pagedBitmapPages = registry.findAllPopulatedPagedBitmapPages(font.getKey());
        if (!pagedBitmapPages.isEmpty()) {
            for (CgGlyphAtlasPage page : pagedBitmapPages) {
                total += page.getSlotCount();
            }
        } else {
            List<CgGlyphAtlas> bitmapPages = registry.findAllPopulatedBitmapAtlases(font.getKey());
            for (CgGlyphAtlas page : bitmapPages) {
                total += page.getSlotCount();
            }
        }

        List<CgGlyphAtlasPage> pagedMsdfPages = registry.findAllPopulatedPagedMsdfPages(font.getKey());
        if (!pagedMsdfPages.isEmpty()) {
            for (CgGlyphAtlasPage page : pagedMsdfPages) {
                total += page.getSlotCount();
            }
        } else {
            List<CgGlyphAtlas> msdfPages = registry.findAllPopulatedMsdfAtlases(font.getKey());
            for (CgGlyphAtlas page : msdfPages) {
                total += page.getSlotCount();
            }
        }

        return total;
    }

    private long prewarmMsdfGlyphsLargestFirst(CgFontRegistry registry,
                                               CgFont font,
                                               String text,
                                               long startFrame) {
        FreeTypeMSDFIntegration.Font msdfFont = font.getMsdfFont();
        CgMsdfAtlasConfig config = registry.getResolvedMsdfConfig(font.getKey());
        List<GlyphPrewarmEntry> glyphs = collectSortedMsdfGlyphs(msdfFont, text, config);
        long frame = startFrame;
        int queued = 0;
        for (int i = 0; i < glyphs.size(); i++) {
            if (queued == CgMsdfGenerator.MAX_PER_FRAME) {
                frame++;
                registry.tickFrame(frame);
                queued = 0;
            }
            GlyphPrewarmEntry entry = glyphs.get(i);
            CgGlyphKey glyphKey = new CgGlyphKey(font.getKey(), entry.glyphId, true, 0);
            registry.queueGlyphPagedPublic(font, glyphKey, font.getKey().getTargetPx(), 0, frame);
            queued++;
        }
        registry.awaitAsyncGlyphs(5000L);
        registry.tickFrame(frame + 1);
        return frame + 1;
    }

    private List<GlyphPrewarmEntry> collectSortedMsdfGlyphs(FreeTypeMSDFIntegration.Font msdfFont,
                                                            String text,
                                                            CgMsdfAtlasConfig config) {
        Map<Integer, GlyphPrewarmEntry> unique = new HashMap<>();
        for (int i = 0; i < text.length(); i++) {
            int glyphId = msdfFont.getGlyphIndex(text.charAt(i));
            if (glyphId <= 0 || unique.containsKey(Integer.valueOf(glyphId))) {
                continue;
            }
            GlyphPrewarmEntry entry = computeGlyphPrewarmEntry(msdfFont, glyphId, config);
            if (entry != null) {
                unique.put(Integer.valueOf(glyphId), entry);
            }
        }
        List<GlyphPrewarmEntry> sorted = new ArrayList<>(unique.values());
        Collections.sort(sorted, (a, b) -> {
            if (a.area != b.area) {
                return Integer.compare(b.area, a.area);
            }
            if (a.height != b.height) {
                return Integer.compare(b.height, a.height);
            }
            return Integer.compare(b.width, a.width);
        });
        return sorted;
    }

    private GlyphPrewarmEntry computeGlyphPrewarmEntry(FreeTypeMSDFIntegration.Font msdfFont,
                                                       int glyphId,
                                                       CgMsdfAtlasConfig config) {
        try {
            FreeTypeMSDFIntegration.GlyphData glyphData = msdfFont.loadGlyphByIndex(
                    glyphId, FreeTypeMSDFIntegration.FONT_SCALING_EM_NORMALIZED);
            MSDFShape shape = glyphData.getShape();
            if (shape.getEdgeCount() == 0) {
                return null;
            }
            shape.normalize();
            double[] bounds = shape.getBounds();
            CgMsdfGlyphLayout layout = CgMsdfGlyphLayout.compute(
                    bounds[0], bounds[1], bounds[2], bounds[3],
                    config.getAtlasScalePx(),
                    config.getPxRange(),
                    config.getMiterLimit(),
                    config.isAlignOriginX(),
                    config.isAlignOriginY());
            if (layout.isEmpty()) {
                return null;
            }
            return new GlyphPrewarmEntry(glyphId, layout.getBoxWidth(), layout.getBoxHeight());
        } catch (MSDFException e) {
            LOGGER.warning("[Harness] Failed to inspect glyph " + glyphId + " for parity prewarm: " + e.getMessage());
            return null;
        }
    }

    private static final class GlyphPrewarmEntry {
        private final int glyphId;
        private final int width;
        private final int height;
        private final int area;

        private GlyphPrewarmEntry(int glyphId, int width, int height) {
            this.glyphId = glyphId;
            this.width = width;
            this.height = height;
            this.area = width * height;
        }
    }

    private static int countUniqueChars(String text) {
        boolean[] seen = new boolean[65536];
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!seen[c]) {
                seen[c] = true;
                count++;
            }
        }
        return count;
    }

    // ── Atlas dump helpers ──────────────────────────────────────────────

    private void dumpBitmapAtlases(CgFontRegistry registry, CgFont font,
                                     int pxSize, boolean dumpAllPages, String atlasDir) {
        int glR8 = 0x8229;

        if (dumpAllPages) {
            List<CgGlyphAtlasPage> pagedPages = registry.findAllPopulatedPagedBitmapPages(font.getKey());
            if (!pagedPages.isEmpty()) {
                AtlasDumper.dumpAllPagedPages(pagedPages,
                        "bitmap-atlas-dump", pxSize + "px", glR8, atlasDir);
                LOGGER.info("[Harness] Dumped " + pagedPages.size() + " bitmap atlas page(s) from paged path");
                return;
            }
            List<CgGlyphAtlas> pages = registry.findAllPopulatedBitmapAtlases(font.getKey());
            if (!pages.isEmpty()) {
                AtlasDumper.dumpAllPages(pages,
                        "bitmap-atlas-dump", pxSize + "px", glR8, atlasDir);
                LOGGER.info("[Harness] Dumped " + pages.size() + " bitmap atlas page(s)");
            } else {
                LOGGER.warning("[Harness] No bitmap atlas pages found after rendering");
            }
        } else {
            List<CgGlyphAtlasPage> pagedPages = registry.findAllPopulatedPagedBitmapPages(font.getKey());
            if (!pagedPages.isEmpty()) {
                CgGlyphAtlasPage page = pagedPages.get(0);
                String filename = "bitmap-atlas-dump-" + pxSize + "px.png";
                LOGGER.info("[Harness] Bitmap atlas captured from paged path: texture=" + page.getTextureId()
                        + ", size=" + page.getPageWidth() + "x" + page.getPageHeight());
                ScreenshotUtil.captureTexture(page.getTextureId(),
                        page.getPageWidth(), page.getPageHeight(),
                        glR8, atlasDir, filename);
                return;
            }
            // Legacy single-page path
            CgGlyphAtlas bitmapAtlas = registry.findPopulatedBitmapAtlas(font.getKey());
            if (bitmapAtlas != null) {
                String filename = "bitmap-atlas-dump-" + pxSize + "px.png";
                LOGGER.info("[Harness] Bitmap atlas captured: texture=" + bitmapAtlas.getTextureId()
                        + ", size=" + bitmapAtlas.getPageWidth() + "x" + bitmapAtlas.getPageHeight());
                ScreenshotUtil.captureTexture(bitmapAtlas.getTextureId(),
                        bitmapAtlas.getPageWidth(), bitmapAtlas.getPageHeight(),
                        glR8, atlasDir, filename);
            } else {
                LOGGER.warning("[Harness] Bitmap atlas not available after rendering");
            }
        }
    }

    private void dumpMsdfAtlases(CgFontRegistry registry, CgFont font,
                                    int pxSize, boolean dumpAllPages, String atlasDir,
                                    String typePrefix, int glFormat) {

        if (dumpAllPages) {
            List<CgGlyphAtlasPage> pagedPages = registry.findAllPopulatedPagedMsdfPages(font.getKey());
            if (!pagedPages.isEmpty()) {
                AtlasDumper.dumpAllPagedPages(pagedPages,
                        typePrefix + "-atlas-dump", pxSize + "px", glFormat, atlasDir);
                LOGGER.info("[Harness] Dumped " + pagedPages.size() + " " + typePrefix.toUpperCase()
                        + " atlas page(s) from paged path");
                return;
            }
            List<CgGlyphAtlas> pages = registry.findAllPopulatedMsdfAtlases(font.getKey());
            if (!pages.isEmpty()) {
                AtlasDumper.dumpAllPages(pages,
                        typePrefix + "-atlas-dump", pxSize + "px", glFormat, atlasDir);
                LOGGER.info("[Harness] Dumped " + pages.size() + " " + typePrefix.toUpperCase() + " atlas page(s)");
            } else {
                LOGGER.warning("[Harness] No " + typePrefix.toUpperCase() + " atlas pages found after rendering");
            }
        } else {
            List<CgGlyphAtlasPage> pagedPages = registry.findAllPopulatedPagedMsdfPages(font.getKey());
            if (!pagedPages.isEmpty()) {
                CgGlyphAtlasPage page = pagedPages.get(0);
                String filename = typePrefix + "-atlas-dump-" + pxSize + "px.png";
                LOGGER.info("[Harness] " + typePrefix.toUpperCase() + " atlas captured from paged path: texture="
                        + page.getTextureId() + ", size=" + page.getPageWidth() + "x" + page.getPageHeight());
                ScreenshotUtil.captureTexture(page.getTextureId(),
                        page.getPageWidth(), page.getPageHeight(),
                        glFormat, atlasDir, filename);
                return;
            }
            // Legacy single-page path
            CgGlyphAtlas msdfAtlas = registry.findPopulatedMsdfAtlas(font.getKey());
            if (msdfAtlas != null) {
                String filename = typePrefix + "-atlas-dump-" + pxSize + "px.png";
                LOGGER.info("[Harness] " + typePrefix.toUpperCase() + " atlas captured: texture="
                        + msdfAtlas.getTextureId() + ", size=" + msdfAtlas.getPageWidth() + "x"
                        + msdfAtlas.getPageHeight());
                ScreenshotUtil.captureTexture(msdfAtlas.getTextureId(),
                        msdfAtlas.getPageWidth(), msdfAtlas.getPageHeight(),
                        glFormat, atlasDir, filename);
            } else {
                LOGGER.warning("[Harness] " + typePrefix.toUpperCase() + " atlas not available after rendering");
            }
        }
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
