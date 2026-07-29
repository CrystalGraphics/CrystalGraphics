package com.crystalgraphics.text.layout;

import com.crystalgraphics.harfbuzz.HBBuffer;
import com.crystalgraphics.harfbuzz.HBDirection;
import com.crystalgraphics.harfbuzz.HBFont;
import com.crystalgraphics.harfbuzz.HBGlyphInfo;
import com.crystalgraphics.harfbuzz.HBGlyphPosition;
import com.crystalgraphics.harfbuzz.HBShape;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontKey;
import com.crystalgraphics.api.text.CgFontFeature;
import com.crystalgraphics.api.text.CgShapedRun;
import com.crystalgraphics.util.profiling.CgProfiler;

import java.util.List;

/**
 * Stateless text shaper that delegates to HarfBuzz for a single directional run.
 *
 * <p>This class is <strong>pure Java + HarfBuzz JNI</strong> — no GL, no FreeType,
 * no mutable state. Each call to {@link #shape} creates a temporary {@link HBBuffer},
 * shapes it, extracts results, and destroys the buffer.</p>
 *
 * <h3>Usage</h3>
 * <pre>
 * CgTextShaper shaper = new CgTextShaper();
 * CgShapedRun run = shaper.shape("Hello", 0, 5, fontKey, resolvedFont, false, hbFont);
 * // run.getGlyphIds() contains shaped glyph indices
 * </pre>
 *
 * <h3>Thread safety</h3>
 * <p>This class is stateless and therefore thread-safe, but the {@link HBFont}
 * parameter is NOT thread-safe — callers must not share an HBFont across threads
 * without synchronization.</p>
 *
 * @see CgShapedRun
 * @see CgLineBreaker
 */
public class CgTextShaper {

    /**
     * Shape a single directional run of text.
     *
     * <p>Extracts the substring {@code text[start..end)}, feeds it to HarfBuzz
     * with the given direction, and returns shaped glyph data.</p>
     *
     * @param text         the full input string (Java UTF-16)
     * @param start        logical start index into {@code text} (inclusive)
     * @param end          logical end index into {@code text} (exclusive)
     * @param fontKey      font key for the resulting {@link CgShapedRun}
     * @param resolvedFont the concrete font {@code fontKey} resolved to; carried on the
     *                     resulting run so later stages don't need a second lookup by key
     * @param rtl          {@code true} for right-to-left direction
     * @param hbFont       caller-managed HarfBuzz font (already set to correct pixel size)
     * @return shaped run with glyph IDs, advances, and offsets in pixels
     * @throws IllegalArgumentException if parameters are invalid
     */
    /**
     * Reusable readback buffers for {@link HBBuffer#getGlyphData}.
     *
     * <p>Thread-local rather than instance state because {@code CgTextLayoutEngine} holds one
     * shared {@code CgTextShaper}, and shaping happens both on the render thread and on the
     * background glyph-generation executor. Grow-only, so a steady stream of shaping settles at
     * zero allocation.
     */
    private static final class Scratch {
        int[] info = new int[64 * HBBuffer.INFO_STRIDE];
        int[] pos = new int[64 * HBBuffer.POSITION_STRIDE];

        /**
         * One reused HarfBuzz buffer per thread, reset between runs rather than created and
         * destroyed each time.
         *
         * <p>{@code hb_buffer_create}/{@code hb_buffer_destroy} cost ~0.52us per shaped run --
         * small next to what readback used to cost, but pure overhead once that was fixed.
         * {@code hb_buffer_reset} is exactly the API HarfBuzz provides for this.
         *
         * <p>Never destroyed: it lives as long as the owning thread. That is deliberate but worth
         * knowing -- a thread that shapes text and then dies leaks one hb_buffer. The threads that
         * shape here (the render thread and the glyph-generation executor) are long-lived, so this
         * is bounded by pool size rather than by work done.
         */
        HBBuffer buffer;

        HBBuffer buffer() {
            if (buffer == null || buffer.isDestroyed()) {
                buffer = HBBuffer.create();
            } else {
                buffer.reset();
            }
            return buffer;
        }

        void grow(int glyphCount) {
            if (info.length < glyphCount * HBBuffer.INFO_STRIDE) {
                info = new int[glyphCount * HBBuffer.INFO_STRIDE];
            }
            if (pos.length < glyphCount * HBBuffer.POSITION_STRIDE) {
                pos = new int[glyphCount * HBBuffer.POSITION_STRIDE];
            }
        }
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    public CgShapedRun shape(String text, int start, int end,
                             CgFontKey fontKey, CgFont resolvedFont, boolean rtl, HBFont hbFont) {
        return shape(text, start, end, fontKey, resolvedFont, rtl, hbFont, null);
    }

    /**
     * Converts {@code features} to HarfBuzz's own feature-string syntax, or returns {@code null}
     * for none.
     *
     * <p>{@code "tag=value"} is what {@code hb_feature_from_string} parses — the same syntax
     * {@code hb-shape --features} takes. The JNI layer runs every string through that parser and
     * silently skips any it rejects, so a malformed tag degrades to "that feature is off" rather
     * than failing the shape. {@link CgFontFeature} already enforces the 4-character tag length at
     * construction, so rejection here would take real effort.</p>
     */
    private static String[] toHarfBuzzFeatures(List<CgFontFeature> features) {
        if (features == null || features.isEmpty()) return null;
        String[] out = new String[features.size()];
        for (int i = 0; i < out.length; i++) {
            CgFontFeature f = features.get(i);
            out[i] = f.tag() + "=" + f.value();
        }
        return out;
    }

    /**
     * @param features OpenType features to enable for this run, or {@code null} for none.
     *                 Passed to HarfBuzz at shape time, which is the only point they can take
     *                 effect — they decide which glyphs are produced.
     */
    public CgShapedRun shape(String text, int start, int end,
                             CgFontKey fontKey, CgFont resolvedFont, boolean rtl, HBFont hbFont,
                             List<CgFontFeature> features) {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        if (fontKey == null) {
            throw new IllegalArgumentException("fontKey must not be null");
        }
        if (hbFont == null || hbFont.isDestroyed()) {
            throw new IllegalArgumentException("hbFont must not be null or destroyed");
        }
        if (start < 0 || end > text.length() || start > end) {
            throw new IllegalArgumentException(
                    "Invalid range: start=" + start + ", end=" + end + ", text.length=" + text.length());
        }

        String substring = text.substring(start, end);
        if (substring.isEmpty()) {
            return new CgShapedRun()
                    .fontKey(fontKey).resolvedFont(resolvedFont).rtl(rtl)
                    .glyphIds(new int[0]).clusterIds(new int[0])
                    .advancesX(new float[0]).offsetsX(new float[0]).offsetsY(new float[0])
                    .totalAdvance(0.0f).sourceStart(start).sourceEnd(end);
        }

        Scratch scratch = SCRATCH.get();
        HBBuffer buf;
        try (CgProfiler.Scope ignored = CgProfiler.scope("hb.bufferCreate")) {
            buf = scratch.buffer();
        }

        try (CgProfiler.Scope ignored = CgProfiler.scope("hb.bufferFill")) {
            buf.addUTF8(substring);
            buf.setDirection(rtl ? HBDirection.HB_DIRECTION_RTL : HBDirection.HB_DIRECTION_LTR);
            buf.guessSegmentProperties();
        }

        try (CgProfiler.Scope ignored = CgProfiler.scope("hb.shape")) {
            HBShape.shape(hbFont, buf, toHarfBuzzFeatures(features));
        }

        // Primitive readback into reusable scratch. getGlyphInfos()/getGlyphPositions() would
        // allocate one wrapper object per glyph and do a FindClass + GetMethodID natively on
        // every call -- measured at 63% of all HarfBuzz time, and fixed per call rather than
        // per glyph, so it dominated short strings.
        int glyphCount;
        int[] info;
        int[] pos;
        try (CgProfiler.Scope ignored = CgProfiler.scope("hb.readBack")) {
            glyphCount = buf.getGlyphData(scratch.info, scratch.pos);
            if (glyphCount < 0) {
                // Negative means the arrays were too small and nothing was written; grow to
                // exactly what this run needs and retry once.
                scratch.grow(-glyphCount);
                glyphCount = buf.getGlyphData(scratch.info, scratch.pos);
            }
            info = scratch.info;
            pos = scratch.pos;
        }

        // DELIBERATELY UNSCOPED — measured, then the scopes were removed again.
        //
        // This block (six per-run array allocations plus the O(glyphs) unpack below) is what the
        // "~1.06 ms unattributed shaping gap" in PERFORMANCE_TODO turned out to contain. Scoping it
        // measured it at 0.196 ms, with the substring and run construction adding 0.030 and
        // 0.033 ms -- 0.259 ms total, against a ~1.06 ms gap.
        //
        // The rest of the gap is the profiler itself. text-stress issues ~1000 shape() calls per
        // frame and the four scopes already inside this method cost ~1.00 ms at ~250 ns each; three
        // more added ~0.75 ms. So instrumenting the gap costs more than the gap contains, and the
        // measurement stopped being able to see past its own cost -- a control run without the new
        // scopes came out *higher* than one with them, because run-to-run variance exceeds the
        // effect.
        //
        // Do not re-add scopes here without a workload that shapes far less often than text-stress
        // does. In a real UI, layout caching means shape() barely runs and none of this matters.
        int[] glyphIds = new int[glyphCount];
        int[] clusterIds = new int[glyphCount];
        float[] advancesX = new float[glyphCount];
        float[] offsetsX = new float[glyphCount];
        float[] offsetsY = new float[glyphCount];
        boolean[] safeToBreakBefore = new boolean[glyphCount];
        float totalAdvance = 0.0f;

        for (int i = 0; i < glyphCount; i++) {
            int ib = i * HBBuffer.INFO_STRIDE;
            int pb = i * HBBuffer.POSITION_STRIDE;
            glyphIds[i] = info[ib];
            clusterIds[i] = info[ib + 1];
            safeToBreakBefore[i] = (info[ib + 2] & HBGlyphInfo.HB_GLYPH_FLAG_UNSAFE_TO_BREAK) == 0;
            advancesX[i] = pos[pb] / 64.0f;
            offsetsX[i] = pos[pb + 2] / 64.0f;
            offsetsY[i] = pos[pb + 3] / 64.0f;
            totalAdvance += advancesX[i];
        }

        return new CgShapedRun()
                .fontKey(fontKey).resolvedFont(resolvedFont).rtl(rtl)
                .glyphIds(glyphIds).clusterIds(clusterIds)
                .advancesX(advancesX).offsetsX(offsetsX).offsetsY(offsetsY)
                .totalAdvance(totalAdvance).sourceStart(start).sourceEnd(end)
                .safeToBreakBefore(safeToBreakBefore);
        // No try/finally + destroy any more: the buffer belongs to the thread-local Scratch and is
        // reset on next use. Destroying it per call is what made this a native allocation per run.
    }
}
