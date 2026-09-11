package com.crystalgraphics.text.font;

import java.util.Arrays;

/**
 * The code points one font face maps to a glyph, as sorted, merged ranges — what its
 * {@code cmap} table says, readable without opening the font natively.
 *
 * <pre>{@code
 * CodePointCoverage coverage = Sfnt.readFaces(path).get(0).coverage();
 * if (coverage.contains(0x65E5)) { ... }   // 日
 * }</pre>
 *
 * <p>Agrees with FreeType's {@code FT_Get_Char_Index(cp) != 0} for the charmap FreeType selects;
 * {@code SfntTest} checks every code point of the test fonts against it.</p>
 */
public final class CodePointCoverage {

    public static final CodePointCoverage EMPTY = new CodePointCoverage(new int[0], new int[0], 0);

    private final int[] starts;
    private final int[] ends;
    private final int size;

    private CodePointCoverage(int[] starts, int[] ends, int size) {
        this.starts = starts;
        this.ends = ends;
        this.size = size;
    }

    public boolean contains(int codePoint) {
        int i = Arrays.binarySearch(starts, codePoint);
        if (i >= 0) {
            return true;
        }
        i = -i - 2;
        return i >= 0 && codePoint <= ends[i];
    }

    /** How many code points are covered. */
    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public String toString() {
        return "CodePointCoverage{" + size + " code points in " + starts.length + " ranges}";
    }

    /** Collects ranges in any order, overlapping or not. */
    public static final class Builder {

        private int[] starts = new int[64];
        private int[] ends = new int[64];
        private int count;

        public Builder add(int start, int end) {
            if (start > end) {
                return this;
            }
            if (count == starts.length) {
                starts = Arrays.copyOf(starts, count * 2);
                ends = Arrays.copyOf(ends, count * 2);
            }
            starts[count] = start;
            ends[count] = end;
            count++;
            return this;
        }

        public CodePointCoverage build() {
            if (count == 0) {
                return EMPTY;
            }
            long[] packed = new long[count];
            for (int i = 0; i < count; i++) {
                packed[i] = ((long) starts[i] << 32) | (ends[i] & 0xFFFFFFFFL);
            }
            Arrays.sort(packed);

            int[] mergedStarts = new int[count];
            int[] mergedEnds = new int[count];
            int merged = 0;
            for (long range : packed) {
                int start = (int) (range >>> 32);
                int end = (int) range;
                if (merged > 0 && start <= mergedEnds[merged - 1] + 1) {
                    mergedEnds[merged - 1] = Math.max(mergedEnds[merged - 1], end);
                } else {
                    mergedStarts[merged] = start;
                    mergedEnds[merged] = end;
                    merged++;
                }
            }
            int size = 0;
            for (int i = 0; i < merged; i++) {
                size += mergedEnds[i] - mergedStarts[i] + 1;
            }
            return new CodePointCoverage(Arrays.copyOf(mergedStarts, merged), Arrays.copyOf(mergedEnds, merged), size);
        }
    }
}
