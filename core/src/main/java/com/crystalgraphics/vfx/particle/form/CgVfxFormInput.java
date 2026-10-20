package com.crystalgraphics.vfx.particle.form;

import java.util.Locale;

import com.crystalgraphics.object.CgPropertyMeta;
import com.crystalgraphics.object.layout.CgFormClass;
import com.crystalgraphics.object.value.CgValueForm;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;

/**
 * One module input compiled from its form ({@code core.md} § Values): what the kernel reads and where
 * ({@link #formClass()}), and its Java evaluation, the reference a kernel is checked against. Built by
 * {@link CgVfxFormCompiler}.
 *
 * <pre>{@code
 * CgVfxFormInput speed = compiler.compile("mymod:sparks/add_velocity", property, Map.of("range", List.of(2, 4)));
 * speed.formClass();               // ROW: two ends, rolled per particle
 * speed.channel(ctx, 0);           // this particle's roll, keyed by the module and the input's name
 * }</pre>
 *
 * <ul>
 *   <li>Every roll is keyed by the module's id and the input's name, never its place in a list.</li>
 *   <li>{@link #channel} allocates nothing; call it per channel, or {@link #eval} for all of them.</li>
 * </ul>
 */
public sealed interface CgVfxFormInput {

    /** The input's name in its module. */
    String name();

    /** The form it was written in. */
    CgValueForm form();

    /** Where the kernel reads it. */
    CgFormClass formClass();

    /** Its numbers: 1 to 4. */
    int components();

    /** Channel {@code c} for the particle {@code ctx} describes. */
    float channel(CgVfxFormContext ctx, int c);

    /** Every channel into {@code out}. */
    default void eval(CgVfxFormContext ctx, float[] out) {
        for (int c = 0; c < components(); c++) out[c] = channel(ctx, c);
    }

    /** A roll in 0..1: per particle, loop or instance as {@code rolls} says, keyed by {@code key} and {@code k}. */
    static float roll(CgVfxFormContext ctx, int key, CgPropertyMeta.Rolls rolls, int k) {
        int n = rolls == CgPropertyMeta.Rolls.LOOP ? ctx.loopIndex : rolls == CgPropertyMeta.Rolls.INSTANCE ? 0 : ctx.particle;
        return CgVfxEmitterInstance.rand(ctx.seed ^ key, n, k);
    }

    /** A lookup's value: {@code age}, {@code particles.age}, {@code loop}, {@code speed}, {@code length}, {@code id}, {@code random}. */
    enum Lookup {
        AGE, PARTICLES_AGE, LOOP, SPEED, LENGTH, ID, RANDOM;

        static Lookup of(Object name) {
            if (name == null) return AGE;
            return valueOf(String.valueOf(name).replace('.', '_').toUpperCase(Locale.ROOT));
        }

        float read(CgVfxFormContext ctx, int key) {
            switch (this) {
                case PARTICLES_AGE: return ctx.particleAge;
                case LOOP: return ctx.loop;
                case SPEED: return ctx.speed;
                case LENGTH: return ctx.length;
                case ID: return ctx.particle;
                case RANDOM: return CgVfxEmitterInstance.rand(ctx.seed ^ key, ctx.particle, 31);
                default: return ctx.age;
            }
        }
    }

    /** {@code 0.5}, {@code [0, 1, 0]}: a word a channel in the row. */
    record Constant(String name, float[] values) implements CgVfxFormInput {
        public CgValueForm form() {
            return CgValueForm.CONSTANT;
        }

        public CgFormClass formClass() {
            return CgFormClass.ROW;
        }

        public int components() {
            return values.length;
        }

        public float channel(CgVfxFormContext ctx, int c) {
            return values[c];
        }
    }

    /**
     * {@code { range: [a, b], per_channel, locked }}: two ends, rolled per particle in the kernel, or per loop or
     * instance by the books ({@code rolls}), then a lane.
     */
    record Range(String name, int key, float[] min, float[] max, boolean perChannel, String locked,
                 CgPropertyMeta.Rolls rolls) implements CgVfxFormInput {
        public CgValueForm form() {
            return CgValueForm.RANGE;
        }

        public CgFormClass formClass() {
            return rolls == CgPropertyMeta.Rolls.PARTICLE || rolls == null ? CgFormClass.ROW : CgFormClass.INSTANCE_LANE;
        }

        public int components() {
            return min.length;
        }

        public float channel(CgVfxFormContext ctx, int c) {
            float u = roll(ctx, key, rolls, rollOf(c));
            return min[c] + (max[c] - min[c]) * u;
        }

        /** Which roll channel {@code c} takes: its own per channel, else the first; a locked axis its source's. */
        private int rollOf(int c) {
            if (!perChannel) return 0;
            if (locked != null) {
                if (locked.equals("xyz") && c > 0) return 0;
                if (locked.equals("xy") && c == 1) return 0;
                if (locked.equals("xz") && c == 2) return 0;
                if (locked.equals("yz") && c == 2) return 1;
            }
            return c;
        }
    }

    /** {@code { pick: [a, b] }}: one end or the other, both ends and a rolled bit in the row. */
    record Pick(String name, int key, float[] a, float[] b, CgPropertyMeta.Rolls rolls) implements CgVfxFormInput {
        public CgValueForm form() {
            return CgValueForm.PICK;
        }

        public CgFormClass formClass() {
            return rolls == CgPropertyMeta.Rolls.PARTICLE || rolls == null ? CgFormClass.ROW : CgFormClass.INSTANCE_LANE;
        }

        public int components() {
            return a.length;
        }

        public float channel(CgVfxFormContext ctx, int c) {
            return roll(ctx, key, rolls, 0) < 0.5f ? a[c] : b[c];
        }
    }

    /** {@code curve}, {@code curves} or {@code gradient}: a slot of the curve block, sampled by {@code by}. */
    record Curve(String name, CgValueForm form, int key, CgVfxCurveBlock block, int slot, int components, Lookup by)
            implements CgVfxFormInput {
        public CgFormClass formClass() {
            return CgFormClass.CURVE_BLOCK;
        }

        public float channel(CgVfxFormContext ctx, int c) {
            return block.sample(slot, by.read(ctx, key), c);
        }
    }

    /** {@code { array: [...], by, interpolate, address }}: its values indexed by a lookup. */
    record Array(String name, int key, float[][] values, Lookup by, boolean interpolate, String address)
            implements CgVfxFormInput {
        public CgValueForm form() {
            return CgValueForm.ARRAY;
        }

        public CgFormClass formClass() {
            return by == Lookup.RANDOM || by == Lookup.ID ? CgFormClass.ROW : CgFormClass.CURVE_BLOCK;
        }

        public int components() {
            return values[0].length;
        }

        public float channel(CgVfxFormContext ctx, int c) {
            int n = values.length;
            float t = by.read(ctx, key);
            if (by == Lookup.RANDOM) return values[Math.min(n - 1, (int) (t * n))][c];
            if (by == Lookup.ID) return values[index((int) t, n)][c];
            float u = t * (n - 1);
            int i = (int) Math.floor(u);
            if (!interpolate) return values[index(Math.round(u), n)][c];
            float f = u - i;
            return values[index(i, n)][c] * (1f - f) + values[index(i + 1, n)][c] * f;
        }

        private int index(int i, int n) {
            if ("wrap".equals(address)) return Math.floorMod(i, n);
            if ("mirror".equals(address)) {
                int period = 2 * (n - 1);
                if (period == 0) return 0;
                int m = Math.floorMod(i, period);
                return m < n ? m : period - m;
            }
            return Math.max(0, Math.min(n - 1, i));
        }
    }

    /**
     * {@code { bind: path, scale, remap, mode, default }}: {@code scale × remap(value)}, computed per instance and
     * written to a lane; a particle's own attribute ({@code particles.*}) is read in the kernel.
     */
    record Binding(String name, String path, float scale, float[] inLo, float[] inHi, float[] outLo, float[] outHi,
                   String[] modes, float[] fallback) implements CgVfxFormInput {
        public CgValueForm form() {
            return CgValueForm.BINDING;
        }

        public CgFormClass formClass() {
            return path.startsWith("particles.") ? CgFormClass.ATTRIBUTE : CgFormClass.INSTANCE_LANE;
        }

        public int components() {
            return fallback.length;
        }

        public float channel(CgVfxFormContext ctx, int c) {
            float v;
            if (path.equals("particles.age")) {
                v = ctx.particleAge;
            } else {
                float[] bound = ctx.parameter(path);
                v = bound != null ? bound[Math.min(c, bound.length - 1)] : fallback[c];
            }
            String mode = modes[Math.min(c, modes.length - 1)];
            if (!mode.equals("direct") && inLo != null) {
                if (mode.equals("absolute")) v = Math.abs(v);
                float lo = inLo[c], hi = inHi[c];
                if (!(hi > lo)) {
                    v = outLo[c];
                } else {
                    float u = Math.max(0f, Math.min(1f, (v - lo) / (hi - lo)));
                    v = outLo[c] + (outHi[c] - outLo[c]) * u;
                }
            } else if (mode.equals("absolute")) {
                v = Math.abs(v);
            }
            return scale * v;
        }
    }

    /**
     * {@code { fn: crystalgraphics:random_range, inputs: { min, max } }} or {@code add { a, b }}: a dynamic input,
     * expanded inline; its class is the most general of its arguments'.
     */
    record Function(String name, int key, String fn, CgVfxFormInput first, CgVfxFormInput second,
                    CgPropertyMeta.Rolls rolls) implements CgVfxFormInput {
        public CgValueForm form() {
            return CgValueForm.FUNCTION;
        }

        public CgFormClass formClass() {
            CgFormClass a = first.formClass(), b = second.formClass();
            return a.ordinal() >= b.ordinal() ? a : b;
        }

        public int components() {
            return Math.max(first.components(), second.components());
        }

        public float channel(CgVfxFormContext ctx, int c) {
            float a = first.channel(ctx, Math.min(c, first.components() - 1));
            float b = second.channel(ctx, Math.min(c, second.components() - 1));
            if (fn.endsWith(":add")) return a + b;
            return a + (b - a) * roll(ctx, key, rolls, 0);
        }
    }

    /** {@code { mesh | texture | field: <id> }}: a data interface's, outside the words. */
    record Data(String name, String kind, String id) implements CgVfxFormInput {
        public CgValueForm form() {
            return CgValueForm.DATA;
        }

        public CgFormClass formClass() {
            return CgFormClass.WORLD;
        }

        public int components() {
            return 1;
        }

        public float channel(CgVfxFormContext ctx, int c) {
            return 0f;
        }
    }
}
