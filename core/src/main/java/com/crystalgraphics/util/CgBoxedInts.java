package com.crystalgraphics.util;

import java.util.Arrays;

/**
 * Boxed ints kept once, for a map keyed or valued by small indices on a hot path: {@code Integer.valueOf} caches only
 * -128 to 127, and a slot past that boxes a new Integer each call.
 *
 * <pre>{@code
 * Integer slot = slots.get(resource);
 * if (slot == null) slots.put(resource, CgBoxedInts.of(resources.size()));
 * }</pre>
 *
 * <ul>
 *   <li>Non-negative values only; the cache grows to the largest asked for and is never trimmed.</li>
 *   <li>Single-threaded per caller is not required: a race only boxes twice.</li>
 * </ul>
 */
public final class CgBoxedInts {

    private static volatile Integer[] boxes = new Integer[0];

    private CgBoxedInts() {
    }

    /** {@code i} boxed, the same Integer every call. */
    public static Integer of(int i) {
        Integer[] b = boxes;
        if (i < b.length) return b[i];
        Integer[] grown = Arrays.copyOf(b, Math.max(i + 1, b.length * 2 + 64));
        for (int k = b.length; k < grown.length; k++) grown[k] = k;
        boxes = grown;
        return grown[i];
    }
}
