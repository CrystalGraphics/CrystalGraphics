package com.crystalgraphics.demo;

import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.text.cache.CgFontRegistry;
import com.crystalgraphics.text.render.CgTextRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * A font demo: a line of text in the world's pose, zoomed with the mouse wheel, and a 2D label, drawn through
 * {@link CgTextRenderer}.
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

    private CgFontDemo() {}

    public void render(int displayWidth, int displayHeight) {
        if (!demoEnabled) return;

        try {
            ensureDemoFontSystem();

            demoTextRenderer.beginBatch();

            PoseStack poseStack = new PoseStack();
            poseStack.scale(demoPoseScale, demoPoseScale, 1.0f);
            float logicalViewportWidth = (float) displayWidth / demoPoseScale;
            demoTextRenderer.context().clearHistory();

            CgTextLayout demoLayout = CgTextLayout.of(
                            DEMO_TEXT + " [base " + demoFontSize + "px, pose "
                                    + String.format("%.1f", demoPoseScale) + "x]",
                            demoFont)
                    .maxWidth(logicalViewportWidth)
                    .build();
            demoTextRenderer.draw().layout(demoLayout).font(demoFont).at(20.0f, 40.0f + demoFontSize)
                    .color(0xFFFFFFFF).pose(poseStack).submit();

            demoTextRenderer.context().clearHistory();

            PoseStack identityPose = new PoseStack();
            CgTextLayout labelLayout = CgTextLayout.of(DEMO_TEXT_2D_LABEL, demoFont)
                    .maxWidth((float) displayWidth)
                    .build();
            demoTextRenderer.draw().layout(labelLayout).font(demoFont).at(20.0f, 20.0f)
                    .color(0xAAFFAAFF).pose(identityPose).submit();

            demoTextRenderer.endBatch();

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
            // Screen-sized: the owned context's orthographic projection auto-tracks the
            // display window resolution via CgTextRendererRegistry (see CgGraphicsLifecycle.onResize).
            demoTextRenderer = CgTextRenderer.create();
        }
    }
}
