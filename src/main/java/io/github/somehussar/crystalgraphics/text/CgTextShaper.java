package io.github.somehussar.crystalgraphics.text;

import com.crystalgraphics.harfbuzz.HBBuffer;
import com.crystalgraphics.harfbuzz.HBDirection;
import com.crystalgraphics.harfbuzz.HBFont;
import com.crystalgraphics.harfbuzz.HBGlyphInfo;
import com.crystalgraphics.harfbuzz.HBGlyphPosition;
import com.crystalgraphics.harfbuzz.HBShape;
import io.github.somehussar.crystalgraphics.api.font.CgFontKey;

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
 * CgShapedRun run = shaper.shape("Hello", 0, 5, fontKey, false, hbFont);
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
     * @param text    the full input string (Java UTF-16)
     * @param start   logical start index into {@code text} (inclusive)
     * @param end     logical end index into {@code text} (exclusive)
     * @param fontKey font key for the resulting {@link CgShapedRun}
     * @param rtl     {@code true} for right-to-left direction
     * @param hbFont  caller-managed HarfBuzz font (already set to correct pixel size)
     * @return shaped run with glyph IDs, advances, and offsets in pixels
     * @throws IllegalArgumentException if parameters are invalid
     */
    public CgShapedRun shape(String text, int start, int end,
                             CgFontKey fontKey, boolean rtl, HBFont hbFont) {
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
            return new CgShapedRun(fontKey, rtl,
                    new int[0], new int[0],
                    new float[0], new float[0], new float[0],
                    0.0f,
                    text, start, end);
        }

        HBBuffer buf = HBBuffer.create();
        try {
            buf.addUTF8(substring);
            buf.setDirection(rtl ? HBDirection.HB_DIRECTION_RTL : HBDirection.HB_DIRECTION_LTR);
            buf.guessSegmentProperties();

            HBShape.shape(hbFont, buf);

            HBGlyphInfo[] infos = buf.getGlyphInfos();
            HBGlyphPosition[] positions = buf.getGlyphPositions();

            int glyphCount = infos.length;
            int[] glyphIds = new int[glyphCount];
            int[] clusterIds = new int[glyphCount];
            float[] advancesX = new float[glyphCount];
            float[] offsetsX = new float[glyphCount];
            float[] offsetsY = new float[glyphCount];
            float totalAdvance = 0.0f;

            for (int i = 0; i < glyphCount; i++) {
                glyphIds[i] = infos[i].getCodepoint();
                clusterIds[i] = infos[i].getCluster();
                advancesX[i] = positions[i].getXAdvance() / 64.0f;
                offsetsX[i] = positions[i].getXOffset() / 64.0f;
                offsetsY[i] = positions[i].getYOffset() / 64.0f;
                totalAdvance += advancesX[i];
            }

            return new CgShapedRun(fontKey, rtl,
                    glyphIds, clusterIds,
                    advancesX, offsetsX, offsetsY,
                    totalAdvance,
                    text, start, end);
        } finally {
            buf.destroy();
        }
    }
}
