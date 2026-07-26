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
            for (int b = 0; b < byteCount; b++) {
                map[byteOffset + b] = charOffset;
            }
            byteOffset += byteCount;
            charOffset += charCount;
        }
        map[byteLength] = len;
        return map;
    }

    private static int utf8ByteCount(int codePoint) {
        if (codePoint < 0x80) return 1;
        if (codePoint < 0x800) return 2;
        if (codePoint < 0x10000) return 3;
        return 4;
    }
}
