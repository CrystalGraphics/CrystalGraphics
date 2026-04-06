package io.github.somehussar.crystalgraphics.harness.util;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.PoseStack;
import io.github.somehussar.crystalgraphics.api.font.CgFont;
import io.github.somehussar.crystalgraphics.api.font.CgFontStyle;
import io.github.somehussar.crystalgraphics.api.font.CgTextLayoutBuilder;
import io.github.somehussar.crystalgraphics.gl.text.CgFontRegistry;
import io.github.somehussar.crystalgraphics.gl.text.CgTextRenderContext;
import io.github.somehussar.crystalgraphics.gl.text.CgTextRenderer;
import io.github.somehussar.crystalgraphics.gl.text.CgWorldTextRenderContext;
import io.github.somehussar.crystalgraphics.gl.text.msdf.CgMsdfAtlasConfig;
import io.github.somehussar.crystalgraphics.harness.scene.TextScene3D;
import io.github.somehussar.crystalgraphics.text.CgTextLayout;

import org.joml.Matrix4f;

import java.util.logging.Logger;

/**
 * Shared rendering logic for world-space text scenes.
 *
 * <p>Encapsulates the GL resources (font, registry, renderer, layouts) and
 * rendering operations that are common to ({@link TextScene3D})
 * world text scenes. This eliminates duplication without forcing both scene
 * types into a single dual-interface class.</p>
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>GL capability validation (core FBO, core shaders, VAO, glMapBufferRange)</li>
 *   <li>Font loading and CgFontRegistry/CgTextRenderer creation</li>
 *   <li>Layout construction for world text and 2D ortho reference text</li>
 *   <li>World-space text rendering via {@link CgTextRenderer#drawWorld}</li>
 *   <li>2D ortho reference text rendering via {@link CgTextRenderer#draw}</li>
 *   <li>Resource cleanup (renderer, registry, font disposal)</li>
 * </ul>
 *
 * <h3>Usage pattern</h3>
 * <pre>
 * WorldTextRenderHelper helper = new WorldTextRenderHelper(fontPath, fontSizePx, text, width, height);
 * helper.init();       // validates GL caps, loads font, builds layouts
 * helper.renderWorld(viewMatrix, screenWidth, screenHeight, frameNumber);
 * helper.dispose();    // releases all GL resources
 * </pre>
 *
 * <p>This class is <em>not</em> a scene itself — it has no lifecycle awareness.
 * The owning scene controls when init/render/dispose are called.</p>
 *
 * @see TextScene3D
 */
public final class WorldTextRenderHelper {

    private static final Logger LOGGER = Logger.getLogger(WorldTextRenderHelper.class.getName());

    // ── Configuration (set at construction, immutable) ──
    private final String fontPath;
    private final int fontSizePx;
    private final String text;
    private final int layoutWidth;
    private final int layoutHeight;
    private final int atlasSize;
    private final boolean mtsdf;
    // ── GL resources (created in init(), destroyed in dispose()) ──
    private CgCapabilities caps;
    private CgFont font;
    private CgFontRegistry registry;
    private CgTextRenderer renderer;
    private CgTextLayout worldLayout;
    private CgTextLayout refLayout;

    /**
     * Creates a new helper with the given configuration.
     *
     * @param fontPath   resolved font file path (use {@link HarnessFontUtil#resolveFontPath})
     * @param fontSizePx font size in pixels
     * @param text       the text content to render
     * @param layoutWidth  layout width in pixels (for text wrapping)
     * @param layoutHeight layout height in pixels (used for ortho context)
     */
    public WorldTextRenderHelper(String fontPath, int fontSizePx, String text,
                                  int layoutWidth, int layoutHeight,
                                  int atlasSize, boolean mtsdf) {
        this.fontPath = fontPath;
        this.fontSizePx = fontSizePx;
        this.text = text;
        this.layoutWidth = layoutWidth;
        this.layoutHeight = layoutHeight;
        this.atlasSize = atlasSize;
        this.mtsdf = mtsdf;
    }

    /**
     * Validates GL capabilities and creates all rendering resources.
     *
     * <p>Must be called exactly once before any render methods. Throws
     * {@link IllegalStateException} if the GPU lacks required features.</p>
     *
     * @throws IllegalStateException if required GL capabilities are missing
     */
    public void init() {
        caps = CgCapabilities.detect();
        if (!caps.isCoreFbo() || !caps.isCoreShaders()
                || !caps.isVaoSupported() || !caps.isMapBufferRangeSupported()) {
            throw new IllegalStateException(
                    "World text scene requires modern GL: core FBO, core shaders, VAO, glMapBufferRange");
        }

        font = CgFont.load(fontPath, CgFontStyle.REGULAR, fontSizePx);
        CgMsdfAtlasConfig atlasConfig = CgMsdfAtlasConfig.defaultConfig()
                .withPageSize(atlasSize)
                .withMtsdf(mtsdf);
        registry = new CgFontRegistry(atlasSize, atlasConfig);
        renderer = CgTextRenderer.create(caps, registry);

        CgTextLayoutBuilder layoutBuilder = new CgTextLayoutBuilder();
        worldLayout = layoutBuilder.layout(
                text + " [world-3D, " + fontSizePx + "px, " + (mtsdf ? "MTSDF" : "MSDF") + "]",
                font, (float) layoutWidth, 0);
        refLayout = layoutBuilder.layout(
                "2D reference [" + fontSizePx + "px, ortho]",
                font, (float) layoutWidth, 0);

        LOGGER.info("[WorldTextRenderHelper] Initialized: font=" + fontPath
                + ", size=" + fontSizePx + "px"
                + ", atlasSize=" + atlasSize
                + ", mtsdf=" + mtsdf);
    }

    public void renderWorld(int screenWidth, int screenHeight, long frameNumber,
                            PoseStack poseStack) {
        float aspect = (float) screenWidth / (float) screenHeight;
        Matrix4f perspProjection = HarnessProjectionUtil.perspective(aspect);

        // Create model-view for world text positioned above the floor.
        // The text layout uses screen-space Y-down coordinates, but world space
        // uses Y-up. We apply three transforms:
        //   1. Camera view matrix (world → view space)
        //   2. Translate to text origin above the floor (Y=0.5 to stay above Y=0)
        //   3. Scale with Y-flip: positive scale on X/Z, negative on Y to flip
        //      text right-side-up, and scale down from pixel units to world units
        Matrix4f modelView = poseStack.last().pose();
        CgWorldTextRenderContext worldContext = CgWorldTextRenderContext.create(perspProjection, screenWidth,
                screenHeight);
        worldContext.updateProjectedSize(modelView, perspProjection, fontSizePx);

        registry.tickFrame(frameNumber);

        renderer.drawWorld(worldLayout, font, 0.0f, 0.0f, 0xFFFFFFFF, frameNumber, worldContext, poseStack);
    }
    

    /**
     * Renders world-space text for the interactive 3D scene path.
     *
     * <p>Builds a model-view matrix from the given camera view matrix,
     * positions the text above the floor (Y=0.5), scales from pixel units
     * to world units with a Y-flip for correct orientation, then draws
     * via {@link CgTextRenderer#drawWorld}.</p>
     *
     * @param viewMatrix   the camera's view matrix (world → view space)
     * @param screenWidth  current viewport width in pixels
     * @param screenHeight current viewport height in pixels
     * @param frameNumber  current frame number for animation/MSDF scheduling
     */
    public void renderWorld(Matrix4f viewMatrix, int screenWidth, int screenHeight,
                            long frameNumber) {
        float aspect = (float) screenWidth / (float) screenHeight;
        Matrix4f perspProjection = HarnessProjectionUtil.perspective(aspect);

        // Create model-view for world text positioned above the floor.
        // The text layout uses screen-space Y-down coordinates, but world space
        // uses Y-up. We apply three transforms:
        //   1. Camera view matrix (world → view space)
        //   2. Translate to text origin above the floor (Y=0.5 to stay above Y=0)
        //   3. Scale with Y-flip: positive scale on X/Z, negative on Y to flip
        //      text right-side-up, and scale down from pixel units to world units
        PoseStack poseStack = new PoseStack();
        Matrix4f modelView = poseStack.last().pose();
        modelView.set(viewMatrix);
        float worldScale = 0.01f;
        float textWorldWidth = worldLayout.getTotalWidth() * worldScale;
        modelView.translate(-textWorldWidth * 0.5f, 0.5f, -5f);
        modelView.scale(worldScale, -worldScale, worldScale);

        CgWorldTextRenderContext worldContext = CgWorldTextRenderContext.create(
                perspProjection, screenWidth, screenHeight);
        worldContext.updateProjectedSize(modelView, perspProjection, fontSizePx);

        registry.tickFrame(frameNumber);

        renderer.drawWorld(worldLayout, font, 0.0f, 0.0f, 0xFFFFFFFF, frameNumber,
                worldContext, poseStack);
    }

    /**
     * Renders a single-shot managed frame to an already-bound FBO.
     *
     * <p>Sets up a fixed perspective projection and model-view that places
     * the text at z=-200, runs multiple frames to allow MSDF generation
     * budget, then renders a 2D ortho reference line at the bottom.</p>
     *
     * <p>The caller is responsible for FBO creation, binding, viewport setup,
     * clear, and teardown. This method only performs the text rendering.</p>
     *
     * @param fboWidth  FBO width in pixels
     * @param fboHeight FBO height in pixels
     */
    public void renderManagedFrame(int fboWidth, int fboHeight) {
        // Perspective projection using shared harness constants
        float aspect = (float) fboWidth / (float) fboHeight;
        Matrix4f perspProjection = HarnessProjectionUtil.perspective(aspect);

        CgWorldTextRenderContext worldContext = CgWorldTextRenderContext.create(
                perspProjection, fboWidth, fboHeight);

        // Model-view: position text at z=-200 (moderate viewing distance)
        PoseStack poseStack = new PoseStack();
        Matrix4f modelView = poseStack.last().pose();
        modelView.translate(0.0f, 0.0f, -200.0f);

        // Update projected-size hint for quality/LOD tier selection
        worldContext.updateProjectedSize(modelView, perspProjection, fontSizePx);

        long frame = 1;

        // Multi-frame to allow MSDF generation budget
        int framesNeeded = (text.length() / 4) + 5;
        for (long f = 1; f <= framesNeeded; f++) {
            registry.tickFrame(frame + f);
            renderer.drawWorld(worldLayout, font, 20.0f, 40.0f, 0xFFFFFFFF, frame + f,
                    worldContext, poseStack);
        }

        // Also render a 2D reference for comparison
        CgTextRenderContext orthoContext = CgTextRenderContext.orthographic(fboWidth, fboHeight);
        PoseStack orthoPose = new PoseStack();
        renderer.draw(refLayout, font, 20.0f, (float)(fboHeight - 40), 0xAAFFAAFF,
                frame + framesNeeded + 1, orthoContext, orthoPose);
    }

    /**
     * Ticks the font registry for the given frame number.
     *
     * <p>Called by the interactive scene each frame before rendering to
     * advance MSDF generation scheduling.</p>
     *
     * @param frameNumber the current frame number
     */
    public void tickFrame(long frameNumber) {
        registry.tickFrame(frameNumber);
    }

    /**
     * Returns the world-space text layout for external dimension queries.
     *
     * @return the world text layout, or null if not yet initialized
     */
    public CgTextLayout getWorldLayout() {
        return worldLayout;
    }

    /**
     * Returns the font size in pixels.
     *
     * @return the configured font size
     */
    public int getFontSizePx() {
        return fontSizePx;
    }
    
    /**
     * Releases all GL resources held by this helper.
     *
     * <p>Safe to call multiple times; guards against null references
     * from partial initialization.</p>
     */
    public void dispose() {
        if (renderer != null) {
            renderer.delete();
            renderer = null;
        }
        if (registry != null) {
            registry.releaseAll();
            registry = null;
        }
        if (font != null) {
            font.dispose();
            font = null;
        }
        LOGGER.info("[WorldTextRenderHelper] Disposed.");
    }
}
