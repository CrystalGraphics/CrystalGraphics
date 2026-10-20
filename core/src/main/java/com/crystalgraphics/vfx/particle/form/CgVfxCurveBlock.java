package com.crystalgraphics.vfx.particle.form;

import java.util.Arrays;

import com.crystalgraphics.asset.curve.CgRichCurve;

/**
 * A definition's curve block: every curve, curves and gradient input baked to {@link #TEXELS} texels a channel over
 * its keys' span, a slot an input. What the kernel samples, linearly between texels, clamped at the ends; the Java
 * side samples it the same way.
 *
 * <pre>{@code
 * CgVfxCurveBlock block = new CgVfxCurveBlock();
 * int slot = block.add(new CgRichCurve[]{fade});      // one channel, baked now
 * block.sample(slot, 0.4f, out);                       // out[0]: the fade at 0.4 of its span
 * block.texels();                                      // the upload: slot by slot, channel-interleaved vec4 texels
 * }</pre>
 *
 * <ul>
 *   <li>A slot holds four channels a texel; an input of fewer leaves the rest 0.</li>
 *   <li>Extrapolation outside the keys clamps: a curve that cycles past its last key bakes its first span only.</li>
 * </ul>
 */
public final class CgVfxCurveBlock {

    /** Texels a slot. */
    public static final int TEXELS = 256;

    private float[] data = new float[0];
    private float[] start = new float[0], span = new float[0];
    private int slots;

    /** Bakes {@code curves} (one a channel, up to four) into a new slot; answers it. */
    public int add(CgRichCurve[] curves) {
        if (curves.length < 1 || curves.length > 4) throw new IllegalArgumentException(curves.length + " channels");
        float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
        for (CgRichCurve c : curves) {
            if (c.size() == 0) continue;
            lo = Math.min(lo, c.time(0));
            hi = Math.max(hi, c.time(c.size() - 1));
        }
        if (!(lo < hi)) {
            lo = 0f;
            hi = 1f;
        }
        int slot = slots++;
        data = Arrays.copyOf(data, slots * TEXELS * 4);
        start = Arrays.copyOf(start, slots);
        span = Arrays.copyOf(span, slots);
        start[slot] = lo;
        span[slot] = hi - lo;
        for (int t = 0; t < TEXELS; t++) {
            float time = lo + (hi - lo) * t / (TEXELS - 1);
            for (int c = 0; c < curves.length; c++) data[(slot * TEXELS + t) * 4 + c] = curves[c].eval(time);
        }
        return slot;
    }

    /** Slot {@code slot} at time {@code time}, its channels into {@code out}. */
    public void sample(int slot, float time, float[] out) {
        float u = span[slot] > 0f ? (time - start[slot]) / span[slot] * (TEXELS - 1) : 0f;
        u = Math.max(0f, Math.min(TEXELS - 1, u));
        int i = Math.min(TEXELS - 2, (int) u);
        float f = u - i;
        int a = (slot * TEXELS + i) * 4, b = a + 4;
        for (int c = 0; c < out.length && c < 4; c++) out[c] = data[a + c] + (data[b + c] - data[a + c]) * f;
    }

    /** Channel {@code channel} of slot {@code slot} at time {@code time}. */
    public float sample(int slot, float time, int channel) {
        float u = span[slot] > 0f ? (time - start[slot]) / span[slot] * (TEXELS - 1) : 0f;
        u = Math.max(0f, Math.min(TEXELS - 1, u));
        int i = Math.min(TEXELS - 2, (int) u);
        float f = u - i;
        int a = (slot * TEXELS + i) * 4 + channel;
        return data[a] + (data[a + 4] - data[a]) * f;
    }

    public int slots() {
        return slots;
    }

    /** The baked texels, {@link #TEXELS} vec4s a slot. */
    public float[] texels() {
        return data;
    }

    /** Where slot {@code slot}'s keys start, and their span: the domain the kernel maps its lookup onto. */
    public float start(int slot) {
        return start[slot];
    }

    public float span(int slot) {
        return span[slot];
    }
}
