package com.crystalgraphics.text.layout;

import java.nio.charset.StandardCharsets;

/**
 * Maps HarfBuzz cluster ids (UTF-8 byte offsets into a shaped run's own source substring)
 * back to UTF-16 char offsets in that same substring. Shared by justifiable-position
 * tagging ({@link CgTextLayoutEngine#computeJustifiable}) and the unsafe-to-break fast path
 * ({@link CgLineBreaker#findBestFittingBoundary}) — both need to translate a HarfBuzz
 * cluster id back into a position in the original Java string.
 */
final class Utf8ClusterMapper {

    private Utf8ClusterMapper() {
    }

    /**
     * @return an array indexed by UTF-8 byte offset (0..byte length inclusive), giving the
     *         UTF-16 char offset of the codepoint that owns that byte
     */
    static int[] byteOffsetToCharOffset(String text) {
        int byteLength = text.getBytes(StandardCharsets.UTF_8).length;
        int[] map = new int[byteLength + 1];
        int byteOffset = 0;
        int charOffset = 0;
        int len = text.length();
        while (charOffset < len) {
            int codePoint = text.codePointAt(charOffset);
            int charCount = Character.charCount(codePoint);
            int byteCount = utf8ByteCount(codePoint);
            for (int b = 0; b < byteCount && byteOffset + b < byteLength; b++) {
                map[byteOffset + b] = charOffset;
            }
            byteOffset += byteCount;
            charOffset += charCount;
        }
        map[byteLength] = len;
        return map;
    }

    /**
     * How many bytes {@code String.getBytes(UTF_8)} will actually write for this code point.
     *
     * <p><b>An unpaired surrogate is one byte, not three.</b> {@code codePointAt} hands back the lone
     * surrogate itself when a string ends mid-pair, and its value sits below {@code 0x10000} — so the
     * arithmetic below would claim three bytes while the JDK's encoder writes a single {@code '?'} for
     * malformed input. The two disagree, the running total runs past the encoded length, and the write
     * lands outside the map.</p>
     *
     * <p>That is not a hypothetical input. A text layer hands this substrings, and any slice taken on a
     * UTF-16 index — a wrapped line, a highlighted range, a clipped run — can fall between the halves of
     * an emoji. The caller should not do that, and this must not crash when one does: the loop above is
     * also bounds-guarded, because a mapper is the wrong place to discover somebody else's off-by-one.</p>
     */
    private static int utf8ByteCount(int codePoint) {
        if (codePoint < 0x80) return 1;
        if (codePoint < 0x800) return 2;
        if (Character.isSurrogate((char) codePoint)) return 1;
        if (codePoint < 0x10000) return 3;
        return 4;
    }
}
