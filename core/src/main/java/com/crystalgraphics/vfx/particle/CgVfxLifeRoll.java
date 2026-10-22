package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.easing.CgKeyframes;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A look over life given as a range: Cascade's {@code SizeMultiplyLife}, {@code ColorOverLife} or
 * {@code ColorScaleOverLife} with a uniform distribution, which resets each particle every tick and multiplies it by a
 * value drawn anew each call ({@code ParticleModules_Size.cpp:208-240}, {@code ParticleModules_Color.cpp}): a flicker
 * between the two ends, every step, on top of the curves over life.
 *
 * <pre>{@code
 * // scale_mesh_size's scale: { range: [[1, 0.7, 0.7], [1, 1, 1]], per_channel: true, locked: yz }
 * CgVfxLifeRoll size = CgVfxLifeRoll.of(CgVfxLifeRoll.Target.MESH_SCALE, Map.of("range",
 *         List.of(List.of(1, 0.7, 0.7), List.of(1, 1, 1)), "per_channel", true, "locked", "yz"), 3);
 * // color's color: { range: [[0, 0, 0], [0.08, 0.07, 0.05]], per_channel: true }
 * CgVfxLifeRoll dust = CgVfxLifeRoll.of(CgVfxLifeRoll.Target.COLOR, form, 3);
 * // scale_sprite_size's scale: { fn: random_range, inputs: { min: <curves by age>, max: <curves by age> } }
 * CgVfxLifeRoll between = CgVfxLifeRoll.between(CgVfxLifeRoll.Target.MESH_SCALE, min, max);
 * }</pre>
 *
 * <ul>
 *   <li>Rolled once for every axis, or per channel, a locked pair taking one roll, as a range form's channels do.</li>
 *   <li>A sprite's range has two axes; its third stays 1. A colour's is rgb; its alpha stays.</li>
 *   <li>An emitter's rolls multiply together, each rolled apart.</li>
 *   <li>Its ends may be curves by age, Cascade's {@code UniformCurve}: rolled each tick between the two at the
 *       particle's life.</li>
 * </ul>
 *
 * @param target what it multiplies
 * @param from   its low end, three channels
 * @param to     its high end
 * @param rolls  which of three rolls each channel takes
 * @param fromCurves its low end by age, three channels, or null for {@code from}
 * @param toCurves   its high end by age, or null for {@code to}
 */
public record CgVfxLifeRoll(Target target, float[] from, float[] to, int[] rolls, @Nullable CgKeyframes[] fromCurves,
                            @Nullable CgKeyframes[] toCurves) {

    /** What a roll multiplies: the size's axes, the colour's rgb, or its alpha (the first channel). */
    public enum Target { MESH_SCALE, COLOR, ALPHA }

    public CgVfxLifeRoll(Target target, float[] from, float[] to, int[] rolls) {
        this(target, from, to, rolls, null, null);
    }

    /** Rolled each tick between curves {@code min} and {@code max} by age, every channel apart. */
    public static CgVfxLifeRoll between(Target target, CgKeyframes[] min, CgKeyframes[] max) {
        float[] from = new float[3], to = new float[3];
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i <= 16; i++) {
                float a = min[c].at(i / 16f), b = max[c].at(i / 16f);
                from[c] = i == 0 ? Math.min(a, b) : Math.min(from[c], Math.min(a, b));
                to[c] = i == 0 ? Math.max(a, b) : Math.max(to[c], Math.max(a, b));
            }
        }
        return new CgVfxLifeRoll(target, from, to, new int[]{0, 1, 2}, min.clone(), max.clone());
    }

    /** Whether its ends are curves by age. */
    public boolean curved() {
        return fromCurves != null;
    }

    /** Its vec4s in a parameter row: a header, then its two ends or its two curves of {@code texels} samples. */
    public int vectors(int texels) {
        return 1 + (curved() ? 2 * texels : 2);
    }

    /** A range form, {@code { range, per_channel, locked }}, of {@code n} channels (2 or 3), or of one number for every one. */
    public static CgVfxLifeRoll of(Target target, Map<?, ?> form, int n) {
        List<?> ends = (List<?>) form.get("range");
        float[] from = axes(ends.get(0), n), to = axes(ends.get(1), n);
        return new CgVfxLifeRoll(target, from, to, rolls(form));
    }

    /**
     * Which of three rolls each channel of a range form takes: one for every channel, else its own, locked letters
     * taking the first one's.
     */
    public static int[] rolls(Map<?, ?> form) {
        if (!Boolean.TRUE.equals(form.get("per_channel"))) return new int[]{0, 0, 0};
        int[] rolls = {0, 1, 2};
        String locked = form.get("locked") instanceof String l ? l : "";
        for (int i = 1; i < locked.length(); i++) rolls["xyz".indexOf(locked.charAt(i))] = "xyz".indexOf(locked.charAt(0));
        return rolls;
    }

    /** {@code rolls} into a range form {@code m}: {@code per_channel} and {@code locked} as {@link #rolls} reads them. */
    public static void putRolls(Map<String, Object> m, int[] rolls) {
        if (rolls[0] == 0 && rolls[1] == 0 && rolls[2] == 0) return;
        m.put("per_channel", true);
        StringBuilder locked = new StringBuilder();
        for (int a = 0; a < 3; a++) {
            if (rolls[a] == rolls[(a + 1) % 3] || rolls[a] == rolls[(a + 2) % 3]) locked.append("xyz".charAt(a));
        }
        if (!locked.isEmpty()) m.put("locked", locked.toString());
    }

    /** The most it scales an axis by: what a pool's bounds widen by; for curves, sampled at 17 points of life. */
    public float most() {
        float m = 0f;
        for (int a = 0; a < 3; a++) m = Math.max(m, Math.max(Math.abs(from[a]), Math.abs(to[a])));
        return m;
    }

    /** As a range form again, three axes: what a file writes. */
    public Map<String, Object> form() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("range", List.of(axesList(from), axesList(to)));
        putRolls(m, rolls);
        return m;
    }

    private static List<Object> axesList(float[] v) {
        return List.of((double) v[0], (double) v[1], (double) v[2]);
    }

    private static float[] axes(Object end, int n) {
        float[] v = {1f, 1f, 1f};
        if (end instanceof Number one) {
            for (int a = 0; a < n; a++) v[a] = one.floatValue();
        } else {
            List<?> l = (List<?>) end;
            for (int a = 0; a < n; a++) v[a] = ((Number) l.get(a)).floatValue();
        }
        return v;
    }
}
