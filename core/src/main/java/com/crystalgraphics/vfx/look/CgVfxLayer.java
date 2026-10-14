package com.crystalgraphics.vfx.look;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One draw of a look: a {@code .shader} on one or more slots, its properties set, bound to the look's parameters, or
 * left to bind themselves by name.
 *
 * <pre>{@code
 * CgVfxLayer shell = CgVfxLayer.of("crystalgraphics:shaders/vfx/beam/body_shell.shader")
 *         .on(CgBeamBody.BODY)
 *         .size(1.15f)                        // times the slot's own size
 *         .set("_Flow", 22f)                  // a constant of this layer
 *         .build();                           // _Shell, _ShellHot bind to SHELL, SHELL_HOT by name
 *
 * CgVfxLayer bands = CgVfxLayer.of("mymod:shaders/vfx/bands.shader")
 *         .on(CgBeamBody.BODY, CgBeamBody.HEAD)            // one shader on a tube and a sphere
 *         .bind("_Colors", MyWave.BANDS)                   // a palette into color[3]
 *         .bind("_Reach", CgEnergyWave.RADIUS, 2.6f)       // RADIUS × 2.6, every draw
 *         .texture("_Strip", MyWave.SLEEVE)
 *         .build();
 * }</pre>
 *
 * <ul>
 *   <li><b>By name</b>: a colour or palette property neither set nor bound reads the look's colour or palette of its
 *       name, the leading underscore dropped and the first letter lowered ({@code _ShellHot} reads {@code shellHot});
 *       else the shader's default. A number is bound only by {@link Builder#bind}, so a shader's {@code _Speed} never
 *       takes an effect's {@code speed}. {@link Builder#unbound} keeps a colour the shader's.</li>
 *   <li>Bound values are read per draw from the effect's own values, so effects playing different values still batch;
 *       constants are the layer's material, made once per layer.</li>
 *   <li>A name the shader does not declare, or of the wrong kind, throws when the layer's first material is made.</li>
 *   <li>Order within an effect is the look's list order within a {@link Role}: no numbers.</li>
 *   <li>Immutable, and shared: a look built from kits shares their layers' materials. A {@link CgVfxTuning} may change
 *       its constants for the run, in every look drawing it.</li>
 * </ul>
 */
public final class CgVfxLayer {

    /**
     * What a layer is, for where it draws within its effect: roles draw in this order, and within one the look's
     * order. A {@link #SURFACE} layer draws after its own effect's distortion, so its haze never smears it.
     */
    public enum Role {
        /** Smoke, dust and other soft matter, drawn first. */
        SMOKE,
        /** Light pools on what is round the effect. */
        LIGHT,
        /** Haze and shock fronts: a shader with a Distortion pass, bending what drew before it. */
        DISTORTION,
        /** The effect itself: its body, its glows, its sparks. */
        SURFACE
    }

    /** A property bound to a parameter: read from the effect's values each draw, a number times {@code factor}. */
    public static final class Binding {
        final String property;
        final CgVfxParam param;
        final float factor;

        Binding(String property, CgVfxParam param, float factor) {
            this.property = property;
            this.param = param;
            this.factor = factor;
        }

        public String property() {
            return property;
        }

        public CgVfxParam param() {
            return param;
        }

        public float factor() {
            return factor;
        }
    }

    final String shader, name;
    final List<CgVfxSlot> slots;
    final float size;
    final CgVfxParam.Scalar sizeParam;
    final Role role;
    final CgQuality from;
    final boolean volume, closed;
    final Map<String, float[]> constants;
    final List<Binding> bindings;
    final Map<String, CgTexture> textures;
    final Set<String> keywords, unbound;
    /** Constants a tuning changed: the layer's for as long as the run. */
    private final Map<String, float[]> tuned = new ConcurrentHashMap<>();
    private volatile int tunings;

    private CgVfxLayer(Builder b) {
        this.shader = b.shader;
        this.name = b.name != null ? b.name : defaultName(b.shader);
        this.slots = Collections.unmodifiableList(new ArrayList<>(b.slots));
        this.size = b.size;
        this.sizeParam = b.sizeParam;
        this.role = b.role;
        this.from = b.from;
        this.volume = b.volume;
        this.closed = b.closed;
        this.constants = Collections.unmodifiableMap(new LinkedHashMap<>(b.constants));
        this.bindings = Collections.unmodifiableList(new ArrayList<>(b.bindings));
        this.textures = Collections.unmodifiableMap(new LinkedHashMap<>(b.textures));
        this.keywords = Collections.unmodifiableSet(new LinkedHashSet<>(b.keywords));
        this.unbound = Collections.unmodifiableSet(new LinkedHashSet<>(b.unbound));
    }

    public static Builder of(String shader) {
        return new Builder(shader);
    }

    private static String defaultName(String shader) {
        int slash = Math.max(shader.lastIndexOf('/'), shader.lastIndexOf(':'));
        String file = shader.substring(slash + 1);
        return file.endsWith(".shader") ? file.substring(0, file.length() - ".shader".length()) : file;
    }

    /** A copy to change: another size, another slot, one more constant. */
    public Builder derive() {
        Builder b = new Builder(shader);
        b.name = name;
        b.slots.addAll(slots);
        b.size = size;
        b.sizeParam = sizeParam;
        b.role = role;
        b.from = from;
        b.volume = volume;
        b.closed = closed;
        b.constants.putAll(constants);
        b.bindings.addAll(bindings);
        b.textures.putAll(textures);
        b.keywords.addAll(keywords);
        b.unbound.addAll(unbound);
        return b;
    }

    public String shader() {
        return shader;
    }

    /** What tools and kits call it: its shader's file name unless {@link Builder#named}. */
    public String name() {
        return name;
    }

    /** The slots it draws on. */
    public List<CgVfxSlot> slots() {
        return slots;
    }

    /** Its size, times its slot's own; times {@link #sizeParam} too when bound. */
    public float size() {
        return size;
    }

    /** A parameter its size follows, times {@link #size}, or null. */
    public CgVfxParam.Scalar sizeParam() {
        return sizeParam;
    }

    public Role role() {
        return role;
    }

    /** The lowest quality tier that draws it. */
    public CgQuality from() {
        return from;
    }

    /** Whether each chunk of a tube draws on a sphere round it ({@link Builder#volume}). */
    public boolean isVolume() {
        return volume;
    }

    /** Whether each chunk of a tube is closed at both ends ({@link Builder#closed}). */
    public boolean isClosed() {
        return closed;
    }

    /** Its constants by property as built: one float a number, four a colour, four an element an array. */
    public Map<String, float[]> constants() {
        return constants;
    }

    /** A constant as it stands: as a {@link CgVfxTuning} set it, else as built; null for one it does not set. */
    public float[] constant(String property) {
        float[] tuned = this.tuned.get(property);
        return tuned != null ? tuned : constants.get(property);
    }

    /** How many times a tuning has changed its constants: a material re-applies them when this moves. */
    public int tunings() {
        return tunings;
    }

    /** Sets one float of a constant it sets: a tuning's, on any thread. */
    synchronized void tune(String property, int component, float value) {
        float[] v = constant(property).clone();
        v[component] = value;
        tuned.put(property, v);
        tunings++;
    }

    /** Its explicit bindings. */
    public List<Binding> bindings() {
        return bindings;
    }

    public Map<String, CgTexture> textures() {
        return textures;
    }

    /** The {@code cg_feature} keywords set on its materials, beside its slot kind's {@code FX_ON_*}. */
    public Set<String> keywords() {
        return keywords;
    }

    /** Properties kept the shader's own, never bound by name. */
    public Set<String> unbound() {
        return unbound;
    }

    @Override
    public String toString() {
        return "layer " + name + " (" + shader + ")";
    }

    public static final class Builder {

        private final String shader;
        private String name;
        private final List<CgVfxSlot> slots = new ArrayList<>();
        private float size = 1f;
        private CgVfxParam.Scalar sizeParam;
        private Role role = Role.SURFACE;
        private CgQuality from = CgQuality.LOW;
        private boolean volume, closed;
        private final Map<String, float[]> constants = new LinkedHashMap<>();
        private final List<Binding> bindings = new ArrayList<>();
        private final Map<String, CgTexture> textures = new LinkedHashMap<>();
        private final Set<String> keywords = new LinkedHashSet<>(), unbound = new LinkedHashSet<>();

        private Builder(String shader) {
            this.shader = Objects.requireNonNull(shader, "shader");
        }

        /** What kits and tools call it. */
        public Builder named(String name) {
            this.name = Objects.requireNonNull(name, "name");
            return this;
        }

        /** The slots it draws on; replaces any set before. */
        public Builder on(CgVfxSlot... slots) {
            this.slots.clear();
            for (CgVfxSlot slot : slots) this.slots.add(Objects.requireNonNull(slot, "slot"));
            return this;
        }

        /** The emitters whose particles it draws. */
        public Builder on(CgVfxEmitter... emitters) {
            this.slots.clear();
            for (CgVfxEmitter emitter : emitters) this.slots.add(emitter.slot());
            return this;
        }

        /** Its size, times its slot's own: a band 1.4 times the beam. */
        public Builder size(float times) {
            this.size = times;
            return this;
        }

        /** Its size following {@code param}, times its slot's own: animatable, tunable, sent. */
        public Builder size(CgVfxParam.Scalar param) {
            this.sizeParam = Objects.requireNonNull(param, "param");
            return this;
        }

        public Builder role(Role role) {
            this.role = Objects.requireNonNull(role, "role");
            return this;
        }

        /** Drawn only at {@code tier} and above: distortion, bloom and other detail a lower tier drops. */
        public Builder from(CgQuality tier) {
            this.from = Objects.requireNonNull(tier, "tier");
            return this;
        }

        /**
         * Draws each chunk of a tube on a sphere round it rather than along it, for a shader summing the light of its
         * own chunk's rings ({@code body_light}): the chunks then add up to the whole with nothing counted twice. Its
         * size is how far the light reaches, in the rings' radii.
         */
        public Builder volume() {
            this.volume = true;
            return this;
        }

        /**
         * Closes each chunk of a tube with a disc at both ends, for a shader tracing the view ray through its own
         * chunk's rings ({@code body_core}). A shader drawing the tube's surface wants it open: the discs would show.
         */
        public Builder closed() {
            this.closed = true;
            return this;
        }

        /** A number. */
        public Builder set(String property, float value) {
            constants.put(property, new float[] {value});
            return this;
        }

        public Builder set(String property, float x, float y) {
            constants.put(property, new float[] {x, y});
            return this;
        }

        /** A vector or a colour. */
        public Builder set(String property, float x, float y, float z, float w) {
            constants.put(property, new float[] {x, y, z, w});
            return this;
        }

        /** An array property's elements, four floats each. */
        public Builder setArray(String property, float... elements) {
            if (elements.length == 0 || elements.length % 4 != 0) {
                throw new IllegalArgumentException("'" + property + "': four floats an element, got " + elements.length);
            }
            constants.put(property, elements.clone());
            return this;
        }

        /** {@code property} read from {@code param} each draw: a number, a colour, or a palette into an array. */
        public Builder bind(String property, CgVfxParam param) {
            return bind(property, param, 1f);
        }

        /** {@code property} read from a number times {@code factor} each draw. */
        public Builder bind(String property, CgVfxParam param, float factor) {
            Objects.requireNonNull(property, "property");
            if (!Objects.requireNonNull(param, "param").numeric()) {
                throw new IllegalArgumentException(param + " is a curve or a shake: a shader cannot read it");
            }
            if (factor != 1f && !(param instanceof CgVfxParam.Scalar)) {
                throw new IllegalArgumentException("A factor scales a number, not " + param);
            }
            bindings.removeIf(b -> b.property.equals(property));
            bindings.add(new Binding(property, param, factor));
            return this;
        }

        /**
         * A texture its shader samples as {@code property}: a {@code sampler2D}, or a {@code sampler2DArray} for a
         * {@code CgTexture2DArray}. One made from files is named in common code and loads at its first draw.
         *
         * <pre>{@code
         * static final CgTexture2D STRIP = CgTextures.of("mymod:textures/vfx/strip.png", CgTextureSpec.RGBA8_LINEAR_REPEAT);
         * static final CgTexture2DArray FLAMES = CgTextures.frames("mymod:textures/vfx/flame_%d.png", 3);
         * layer.texture("_Strip", STRIP).texture("_Flames", FLAMES)
         * }</pre>
         */
        public Builder texture(String property, CgTexture texture) {
            textures.put(Objects.requireNonNull(property, "property"), Objects.requireNonNull(texture, "texture"));
            return this;
        }

        /** Sets a {@code #pragma cg_feature} keyword on its materials. */
        public Builder keyword(String keyword) {
            keywords.add(Objects.requireNonNull(keyword, "keyword"));
            return this;
        }

        /** Keeps {@code property} the shader's own: never bound by name. */
        public Builder unbound(String property) {
            unbound.add(Objects.requireNonNull(property, "property"));
            return this;
        }

        public CgVfxLayer build() {
            if (slots.isEmpty()) throw new IllegalStateException("Layer " + shader + " draws on no slot: give it on(...)");
            return new CgVfxLayer(this);
        }
    }
}
