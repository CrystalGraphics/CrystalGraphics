package com.crystalgraphics.api.material;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.gl.buffer.shader.CgEngineBufferRegistry;
import com.crystalgraphics.gl.buffer.shader.CgParameterCollectionBuffer;
import com.crystalgraphics.mc.CgAssetReloader;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Named values every material can read, set once and seen by all of them: Unreal's material parameter collection. A
 * sequence darkening the stage, a skill's tint, the weather's wetness.
 *
 * <pre>{@code
 * public static final CgParameterCollection SKILL = CgParameterCollection.of("skill");
 * public static final CgParameterCollection.Scalar DARKEN = SKILL.scalar("darken", 0f);
 * public static final CgParameterCollection.Vector TINT = SKILL.vector("tint", 0xFFFFFFFF);
 *
 * DARKEN.set(0.6f);                       // every material reading it, from this frame's world draws
 * TINT.set(0xFFFF8040, 1.5f);             // 0xAARRGGBB, its rgb times 1.5
 * }</pre>
 *
 * <p>A shader reads them by namespace and name, declaring the buffer:</p>
 *
 * <pre>{@code
 * #pragma cg_use globals
 * ...
 * fragColor.rgb *= 1.0 - CG_GLOBAL_skill_darken;     // a float
 * fragColor.rgb *= CG_GLOBAL_skill_tint.rgb;         // a vec4
 * }</pre>
 *
 * <ul>
 *   <li><b>Declare every parameter before the collection's first use</b>: a set, a read, the first frame or the first
 *       shader compiled against it places it, and a declaration after that throws. Declare them as constants.</li>
 *   <li>A collection defined after shaders compiled against the buffer recompiles them at the next frame.</li>
 *   <li>Set and read on the render thread. Values go up once a frame, after the client world's schedule:
 *       a draw outside the world reads them only in a frame the world drew.</li>
 *   <li>Every collection together holds at most {@link #MAX_VEC4S} vec4s: four scalars or one vector each.</li>
 *   <li>Names are GLSL identifiers without {@code __}: a letter, then letters, digits and underscores.</li>
 * </ul>
 */
public final class CgParameterCollection {

    /** The vec4s every collection together may hold: 16 KB. */
    public static final int MAX_VEC4S = 1024;

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final List<CgParameterCollection> COLLECTIONS = new ArrayList<>();

    private static float[] values = new float[4];
    private static int vec4s;
    private static boolean served;

    private final String namespace;
    private final List<Scalar> scalars = new ArrayList<>();
    private final List<Vector> vectors = new ArrayList<>();
    private int base = -1;

    private CgParameterCollection(String namespace) {
        this.namespace = namespace;
    }

    /** A new collection read as {@code CG_GLOBAL_<namespace>_<name>}. A namespace is defined once. */
    public static synchronized CgParameterCollection of(String namespace) {
        checkName(namespace, "namespace");
        for (CgParameterCollection c : COLLECTIONS) {
            if (c.namespace.equals(namespace)) throw new IllegalStateException("collection '" + namespace + "' is defined");
        }
        CgParameterCollection collection = new CgParameterCollection(namespace);
        COLLECTIONS.add(collection);
        return collection;
    }

    /** A number, {@code defaultValue} until set. */
    public Scalar scalar(String name, float defaultValue) {
        synchronized (CgParameterCollection.class) {
            declare(name);
            Scalar s = new Scalar(this, name, scalars.size(), defaultValue);
            scalars.add(s);
            return s;
        }
    }

    /** Four numbers, a colour or a direction, these until set. */
    public Vector vector(String name, float x, float y, float z, float w) {
        synchronized (CgParameterCollection.class) {
            declare(name);
            Vector v = new Vector(this, name, vectors.size(), new float[] {x, y, z, w});
            vectors.add(v);
            return v;
        }
    }

    /** A colour, {@code 0xAARRGGBB}, until set. */
    public Vector vector(String name, int argb) {
        return vector(name, argb, 1f);
    }

    /** A colour, {@code 0xAARRGGBB} with its rgb times {@code intensity}, until set. */
    public Vector vector(String name, int argb, float intensity) {
        float[] c = argb(argb, intensity);
        return vector(name, c[0], c[1], c[2], c[3]);
    }

    public String namespace() {
        return namespace;
    }

    /** Its scalar of that name, or null: Unreal's {@code GetScalarParameterByName}, for a value named in data. */
    @Nullable
    public Scalar scalarNamed(String name) {
        for (Scalar s : scalars) if (s.name.equals(name)) return s;
        return null;
    }

    /** Its vector of that name, or null: Unreal's {@code GetVectorParameterByName}. */
    @Nullable
    public Vector vectorNamed(String name) {
        for (Vector v : vectors) if (v.name.equals(name)) return v;
        return null;
    }

    /** Whether it has its place in the buffer: no parameter can be added once it has. */
    public boolean placed() {
        synchronized (CgParameterCollection.class) {
            return base >= 0;
        }
    }

    /** Puts every value of the collection back to its default. Render thread. */
    public void reset() {
        place();
        for (Scalar s : scalars) s.set(s.defaultValue);
        for (Vector v : vectors) v.set(v.defaults[0], v.defaults[1], v.defaults[2], v.defaults[3]);
    }

    /**
     * Each parameter's {@code #define}, for {@code #pragma cg_use globals}: what the material compiler appends after
     * the buffer's env file. Places every collection defined so far.
     */
    public static synchronized String glsl() {
        placeAll();
        served = true;
        StringBuilder out = new StringBuilder();
        for (CgParameterCollection c : COLLECTIONS) {
            for (Scalar s : c.scalars) {
                out.append("#define CG_GLOBAL_").append(c.namespace).append('_').append(s.name)
                        .append(" (CG_GLOBAL_VEC4(").append(s.at / 4).append(").").append("xyzw".charAt(s.at % 4))
                        .append(")\n");
            }
            for (Vector v : c.vectors) {
                out.append("#define CG_GLOBAL_").append(c.namespace).append('_').append(v.name)
                        .append(" (CG_GLOBAL_VEC4(").append(v.at / 4).append("))\n");
            }
        }
        return out.toString();
    }

    /** This frame's values to the GPU. Render thread, once a frame: the world renderer's, after its listeners. */
    public static void upload() {
        int count;
        float[] data;
        boolean read;
        synchronized (CgParameterCollection.class) {
            placeAll();
            count = vec4s;
            data = values;
            read = served;
        }
        // Bound whenever a shader can read it: one compiled against it, or one compiling this frame against a value
        // already placed. Untouched where nothing defines one.
        if ((read || count > 0) && CgBindingPoints.isInitialized()) CgParameterCollectionBuffer.upload(data, count);
    }

    /** The vec4s every placed collection takes. */
    public static synchronized int vec4s() {
        return vec4s;
    }

    private void declare(String name) {
        checkName(name, "parameter");
        if (base >= 0) {
            throw new IllegalStateException("collection '" + namespace + "' is in use: declare '" + name
                    + "' with its other parameters, before its first use");
        }
        for (Scalar s : scalars) if (s.name.equals(name)) throw duplicate(name);
        for (Vector v : vectors) if (v.name.equals(name)) throw duplicate(name);
    }

    private IllegalStateException duplicate(String name) {
        return new IllegalStateException("collection '" + namespace + "' already has '" + name + "'");
    }

    private static void checkName(String name, String what) {
        if (name == null || !NAME.matcher(name).matches() || name.contains("__")) {
            throw new IllegalArgumentException(what + " '" + name + "': a letter, then letters, digits and single underscores");
        }
    }

    private void place() {
        placeAll();
    }

    /** Every collection not yet placed takes its range, in the order they were defined. */
    private static synchronized void placeAll() {
        boolean grew = false;
        for (int i = 0; i < COLLECTIONS.size(); i++) {
            CgParameterCollection c = COLLECTIONS.get(i);
            if (c.base >= 0) continue;
            int need = (c.scalars.size() + 3) / 4 + c.vectors.size();
            if (vec4s + need > MAX_VEC4S) {
                throw new IllegalStateException("collection '" + c.namespace + "' needs " + need + " vec4s; "
                        + vec4s + " of " + MAX_VEC4S + " are taken");
            }
            c.base = vec4s;
            vec4s += need;
            if (values.length < vec4s * 4) values = Arrays.copyOf(values, Math.max(vec4s * 4, values.length * 2));
            for (Scalar s : c.scalars) {
                s.at = c.base * 4 + s.index;
                values[s.at] = s.defaultValue;
            }
            int vectorBase = c.base + (c.scalars.size() + 3) / 4;
            for (Vector v : c.vectors) {
                v.at = (vectorBase + v.index) * 4;
                System.arraycopy(v.defaults, 0, values, v.at, 4);
            }
            grew = true;
        }
        if (grew && served) CgAssetReloader.changed(CgEngineBufferRegistry.get("globals").generatedPath());
    }

    private static float[] argb(int argb, float intensity) {
        return new float[] {(argb >> 16 & 0xFF) / 255f * intensity, (argb >> 8 & 0xFF) / 255f * intensity,
                (argb & 0xFF) / 255f * intensity, (argb >>> 24) / 255f};
    }

    /** A number of a collection: {@code CG_GLOBAL_<namespace>_<name>}, a {@code float}. */
    public static final class Scalar {
        private final CgParameterCollection collection;
        private final String name;
        private final int index;
        private final float defaultValue;
        private int at = -1;

        private Scalar(CgParameterCollection collection, String name, int index, float defaultValue) {
            this.collection = collection;
            this.name = name;
            this.index = index;
            this.defaultValue = defaultValue;
        }

        /** Its value from now on. Render thread; allocates nothing. */
        public void set(float value) {
            if (at < 0) collection.place();
            values[at] = value;
        }

        public float get() {
            if (at < 0) collection.place();
            return values[at];
        }

        public String name() {
            return name;
        }

        public float defaultValue() {
            return defaultValue;
        }

        /** Where it sits among every collection's floats: its vec4 is this over 4, its component the rest. */
        public int offset() {
            if (at < 0) collection.place();
            return at;
        }
    }

    /** Four numbers of a collection: {@code CG_GLOBAL_<namespace>_<name>}, a {@code vec4}. */
    public static final class Vector {
        private final CgParameterCollection collection;
        private final String name;
        private final int index;
        private final float[] defaults;
        private int at = -1;

        private Vector(CgParameterCollection collection, String name, int index, float[] defaults) {
            this.collection = collection;
            this.name = name;
            this.index = index;
            this.defaults = defaults;
        }

        /** Its value from now on. Render thread; allocates nothing. */
        public void set(float x, float y, float z, float w) {
            if (at < 0) collection.place();
            float[] v = values;
            v[at] = x;
            v[at + 1] = y;
            v[at + 2] = z;
            v[at + 3] = w;
        }

        /** A colour, {@code 0xAARRGGBB}. */
        public void set(int argb) {
            set(argb, 1f);
        }

        /** A colour, {@code 0xAARRGGBB} with its rgb times {@code intensity}. */
        public void set(int argb, float intensity) {
            set((argb >> 16 & 0xFF) / 255f * intensity, (argb >> 8 & 0xFF) / 255f * intensity,
                    (argb & 0xFF) / 255f * intensity, (argb >>> 24) / 255f);
        }

        /** Its value into {@code out}, four floats; answers {@code out}. */
        public float[] get(float[] out) {
            if (at < 0) collection.place();
            System.arraycopy(values, at, out, 0, 4);
            return out;
        }

        public String name() {
            return name;
        }

        /** Where its first float sits among every collection's floats, a multiple of 4. */
        public int offset() {
            if (at < 0) collection.place();
            return at;
        }
    }
}
