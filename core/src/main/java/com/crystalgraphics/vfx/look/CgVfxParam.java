package com.crystalgraphics.vfx.look;

import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.vfx.camera.CgCameraShake;

/**
 * One value a look carries, declared on a {@link CgVfxSchema}: a number, a colour, a palette of colours, a curve or a
 * camera shake. Its kind is its type, so a colour handed where a number belongs does not compile.
 *
 * <pre>{@code
 * public static final CgVfxSchema SCHEMA = CgVfxSchema.of("mymod:bolt");
 * public static final CgVfxParam.Scalar RADIUS = SCHEMA.length("radius", 0.4f);
 * public static final CgVfxParam.Scalar REACH = SCHEMA.length("reach", RADIUS, 6f);   // follows RADIUS × 6
 * public static final CgVfxParam.Color CORE = SCHEMA.color("core", 0xFFFFFFFF);
 *
 * bolt.set(RADIUS, 0.6f);          // this bolt alone; REACH follows to 3.6
 * float reach = bolt.get(REACH);
 * }</pre>
 *
 * <ul>
 *   <li>A colour's or a palette's name is a shader property's: a layer's {@code _Core} reads {@code core} with no
 *       binding. A number binds only by {@link CgVfxLayer.Builder#bind}.</li>
 *   <li>Its name is a network field's and a tuning variable's too: renaming one is a network change.</li>
 * </ul>
 */
public abstract class CgVfxParam {

    final CgVfxSchema schema;
    final String name;
    /** Where it starts among its schema's floats, or its place among its schema's objects. */
    final int local;

    CgVfxParam(CgVfxSchema schema, String name, int local) {
        this.schema = schema;
        this.name = name;
        this.local = local;
    }

    public final String name() {
        return name;
    }

    public final CgVfxSchema schema() {
        return schema;
    }

    /** Floats it takes in a set of values: 1 a number, 4 a colour, 4 an entry a palette; 0 a curve or a shake. */
    public abstract int floats();

    /** Whether a shader property can read it: a number, a colour or a palette. */
    public final boolean numeric() {
        return floats() > 0;
    }

    @Override
    public final String toString() {
        return schema.name() + "." + name;
    }

    /** A number in a unit, optionally following another number. */
    public static final class Scalar extends CgVfxParam {
        final CgVfxUnit unit;
        final float value;
        /** What it follows by default (its value is {@code source × factor}), or null. */
        final Scalar source;
        final float factor;

        Scalar(CgVfxSchema schema, String name, int local, CgVfxUnit unit, float value, Scalar source, float factor) {
            super(schema, name, local);
            this.unit = unit;
            this.value = value;
            this.source = source;
            this.factor = factor;
        }

        public CgVfxUnit unit() {
            return unit;
        }

        /** Its default: a number, or for a linked one its source's default times its factor. */
        public float defaultValue() {
            return source == null ? value : source.defaultValue() * factor;
        }

        /** The number it follows by default, or null. */
        public Scalar source() {
            return source;
        }

        public float factor() {
            return factor;
        }

        @Override
        public int floats() {
            return 1;
        }
    }

    /**
     * A colour, in the space shaders author colour in (sRGB), its rgb above 1 allowed, and a fourth value the reader
     * decides (its alpha, or a strength). Declared and set as {@code 0xAARRGGBB}, with an intensity for one past white;
     * an effect's and its values' runtime setters also take four floats, for colours worked out in code.
     */
    public static final class Color extends CgVfxParam {
        final float r, g, b, a;

        Color(CgVfxSchema schema, String name, int local, float r, float g, float b, float a) {
            super(schema, name, local);
            this.r = r;
            this.g = g;
            this.b = b;
            this.a = a;
        }

        @Override
        public int floats() {
            return 4;
        }

        /**
         * A channel of {@code 0xAARRGGBB} as a colour's float, 0 to 255 as 0 to 1: how every {@code argb} overload
         * reads one. A colour past white is the int and an intensity times its rgb.
         *
         * <pre>{@code
         * look.set(CgEnergyWave.SHELL, 0xFF2659FF, 1.6f);   // (0.15, 0.35, 1, 1) × 1.6 in rgb
         * }</pre>
         */
        public static float red(int argb) {
            return (argb >>> 16 & 0xFF) / 255f;
        }

        public static float green(int argb) {
            return (argb >>> 8 & 0xFF) / 255f;
        }

        public static float blue(int argb) {
            return (argb & 0xFF) / 255f;
        }

        public static float alpha(int argb) {
            return (argb >>> 24) / 255f;
        }
    }

    /**
     * Colours in a row: a toon effect's bands, a dash's choices. A shader reads it through an array property,
     * {@code _Bands ("Bands", color[3])}, as {@code _Bands(i)}.
     */
    public static final class Palette extends CgVfxParam {
        final int count;
        final float[] values;

        Palette(CgVfxSchema schema, String name, int local, int count, float[] values) {
            super(schema, name, local);
            this.count = count;
            this.values = values;
        }

        /** How many colours it holds. */
        public int count() {
            return count;
        }

        @Override
        public int floats() {
            return 4 * count;
        }
    }

    /** A value over time, {@link CgKeyframes}, read by the effect: a size over the charge, a flash over the blast. */
    public static final class Curve extends CgVfxParam {
        final CgKeyframes value;

        Curve(CgVfxSchema schema, String name, int local, CgKeyframes value) {
            super(schema, name, local);
            this.value = value;
        }

        @Override
        public int floats() {
            return 0;
        }
    }

    /** A camera shake the effect plays, so a look changes what its moments feel like. */
    public static final class Shake extends CgVfxParam {
        final CgCameraShake value;

        Shake(CgVfxSchema schema, String name, int local, CgCameraShake value) {
            super(schema, name, local);
            this.value = value;
        }

        @Override
        public int floats() {
            return 0;
        }
    }
}
