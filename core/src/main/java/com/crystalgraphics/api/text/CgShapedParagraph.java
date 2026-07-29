package com.crystalgraphics.api.text;

import com.crystalgraphics.util.profiling.CgProfiler;
import com.crystalgraphics.api.font.CgFontMetrics;
import com.crystalgraphics.text.layout.CgReshapeContext;
import com.crystalgraphics.text.layout.CgTextLayoutEngine;
import com.crystalgraphics.text.layout.RunReshaper;

import java.util.List;

/**
 * The "built, not yet wrapped" checkpoint — analogous to Skia's {@code Paragraph} before
 * {@code layout(width)} is called. Retains exactly what {@link CgTextLayoutEngine}'s
 * shape step already computes: BiDi/style-span-split runs (grouped per linguistic
 * paragraph), the reshape context each paragraph's runs were shaped from, the resolved
 * metrics/reshaper, and the {@link CgParagraphKnobs} the originating {@link CgTextLayout.Request}
 * was configured with.
 *
 * <h3>Why this exists</h3>
 * <p>{@code Draw.text(String)} has no reason to re-wrap the same content at a different
 * width — for that caller, {@link CgTextLayout.Request#build()} (which calls
 * {@link #shape()} then immediately {@link #layout}) is all that's ever needed. But an
 * eventual CrystalGUI block-tag translator does need this: every Taffy reflow of a
 * text-containing {@code UIElement} (window resize, flex recompute) has to re-wrap the
 * <em>same</em> already-parsed-and-shaped content at a new width, without re-running
 * markup parsing, BiDi segmentation, style-span splitting, or HarfBuzz shaping again.
 * {@link #layout(float, float)} re-runs only line-breaking and the bake step.</p>
 *
 * <p>Unconsumed today — nothing currently holds onto a {@code CgShapedParagraph} across
 * multiple {@link #layout} calls — but the type exists now so retrofitting it later
 * doesn't mean finding every {@code .build()} call site and deciding whether it should
 * have held one all along.</p>
 */
public final class CgShapedParagraph {

    /** One linguistic paragraph's shaped runs plus the source text they were shaped from. */
    public record Slice(String text, List<CgShapedRun> runs, CgReshapeContext context) {
    }

    private final List<Slice> slices;
    private final CgFontMetrics metrics;
    private final RunReshaper reshaper;
    private final List<CgShapedRun> ellipsisRuns;
    private final CgParagraphKnobs knobs;

    // Last-call memo (mutable, unlike everything else on this otherwise-immutable holder) --
    // see layout()'s javadoc. Deliberately not thread-safe: one CgShapedParagraph is drawn from
    // one draw call site, never shared across threads.
    private float lastMaxWidth = Float.NaN;
    private float lastMaxHeight = Float.NaN;
    private CgTextLayout lastLayout;

    public CgShapedParagraph(List<Slice> slices, CgFontMetrics metrics, RunReshaper reshaper,
                              List<CgShapedRun> ellipsisRuns, CgParagraphKnobs knobs) {
        this.slices = slices;
        this.metrics = metrics;
        this.reshaper = reshaper;
        this.ellipsisRuns = ellipsisRuns;
        this.knobs = knobs;
    }

    /**
     * Wraps this already-shaped content at {@code maxWidth}/{@code maxHeight} — re-runs only
     * line-breaking and the bake step (no re-parse, no re-shape, no re-split).
     *
     * <p>Memoizes the last {@code (maxWidth, maxHeight)} pair and returns the cached
     * {@link CgTextLayout} unchanged when called again with the same values (exact float
     * equality — callers driving this from a quantized/hysteresis-smoothed value, e.g. a
     * draw-time scale resolver, will naturally get the same bits frame-to-frame whenever the
     * effective scale hasn't stepped). This is what makes it safe to call {@code layout(...)}
     * every draw instead of only when the caller knows the width changed — e.g. a text
     * renderer re-wrapping every frame in response to the current PoseStack scale skips the
     * actual line-breaking work on every frame except the one where the scale genuinely
     * changed, the same way a browser only reflows on an actual zoom step.</p>
     */
    public CgTextLayout layout(float maxWidth, float maxHeight) {
        if (lastLayout != null && maxWidth == lastMaxWidth && maxHeight == lastMaxHeight) {
            CgProfiler.count("paragraphLayout.hit");
            return lastLayout;
        }
        CgProfiler.count("paragraphLayout.miss");
        CgTextLayout result = CgTextLayoutEngine.wrap(slices, metrics, reshaper, ellipsisRuns, maxWidth, maxHeight, knobs);
        lastMaxWidth = maxWidth;
        lastMaxHeight = maxHeight;
        lastLayout = result;
        return result;
    }
}
