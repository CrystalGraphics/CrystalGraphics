package com.crystalgraphics.text.render;

import java.util.Arrays;

/**
 * One draw's shadow layers, as {@link CgTextBatch} gathers them from its style, bottom first: local pixels, a Gaussian
 * standard deviation, straight ARGB, and whether each casts the style's outline.
 *
 * <p>Grow-only, so a draw that sets its shadows every frame allocates nothing. What each glyph paints in
 * each shadow is {@link CgTextShadowPlan}'s to decide.</p>
 */
final class CgTextShadowList {

    private int count;
    private float[] x = new float[0];
    private float[] y = new float[0];
    private float[] sigma = new float[0];
    private float[] spread = new float[0];
    private int[] argb = new int[0];
    private boolean[] inset = new boolean[0];
    private boolean[] outlined = new boolean[0];
    /** Per shadow: the glyph scope it applies to, or -1 for every glyph. */
    private int[] scope = new int[0];
    /** Per glyph of the layout: its scope, or null when no shadow is scoped. Held, not copied. */
    private int[] glyphScopes;

    void clear() {
        count = 0;
        glyphScopes = null;
    }

    int count() {
        return count;
    }

    /** Growing keeps the shadows already set, so a second source can append. */
    void count(int count) {
        if (count < 0) throw new IllegalArgumentException("shadow count must be >= 0: " + count);
        if (count > argb.length) {
            int capacity = Math.max(count, argb.length * 2);
            x = Arrays.copyOf(x, capacity);
            y = Arrays.copyOf(y, capacity);
            sigma = Arrays.copyOf(sigma, capacity);
            spread = Arrays.copyOf(spread, capacity);
            argb = Arrays.copyOf(argb, capacity);
            inset = Arrays.copyOf(inset, capacity);
            outlined = Arrays.copyOf(outlined, capacity);
            scope = Arrays.copyOf(scope, capacity);
        }
        this.count = count;
    }

    /** Sets shadow {@code index} for every glyph; a negative sigma or spread reads as 0. */
    void set(int index, float offsetX, float offsetY, float sigma, float spread, int argb, boolean inset,
             boolean outlined) {
        checkIndex(index);
        this.x[index] = offsetX;
        this.y[index] = offsetY;
        this.sigma[index] = Math.max(0f, sigma);
        this.spread[index] = Math.max(0f, spread);
        this.argb[index] = argb;
        this.inset[index] = inset;
        this.outlined[index] = outlined;
        this.scope[index] = -1;
    }

    void scope(int index, int scope) {
        checkIndex(index);
        this.scope[index] = scope;
    }

    void glyphScopes(int[] scopeByGlyph) {
        this.glyphScopes = scopeByGlyph;
    }

    float x(int index) {
        return x[index];
    }

    float y(int index) {
        return y[index];
    }

    float sigma(int index) {
        return sigma[index];
    }

    float spread(int index) {
        return spread[index];
    }

    int argb(int index) {
        return argb[index];
    }

    boolean inset(int index) {
        return inset[index];
    }

    /** Whether shadow {@code index} casts the style's first outline's shape. */
    boolean outlined(int index) {
        return outlined[index];
    }

    /** Whether shadow {@code index} paints anything at all. */
    boolean casts(int index) {
        return (argb[index] >>> 24) != 0;
    }

    /** Whether shadow {@code index} applies to some glyphs only, and so casts nothing for decorations. */
    boolean scoped(int index) {
        return scope[index] >= 0;
    }

    /** The glyph scope shadow {@code index} applies to, or -1 for every glyph. */
    int scopeOf(int index) {
        return scope[index];
    }

    /** Glyph {@code glyph}'s scope, or {@link Integer#MIN_VALUE} where it has none. */
    int glyphScope(int glyph) {
        return glyphScopes != null && glyph < glyphScopes.length ? glyphScopes[glyph] : Integer.MIN_VALUE;
    }

    /** Whether {@code glyph} is one shadow {@code shadow} applies to. */
    boolean appliesTo(int shadow, int glyph) {
        int wanted = scope[shadow];
        if (wanted < 0) return true;
        return glyphScopes != null && glyph < glyphScopes.length && glyphScopes[glyph] == wanted;
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException("shadow " + index + " of " + count);
        }
    }
}
