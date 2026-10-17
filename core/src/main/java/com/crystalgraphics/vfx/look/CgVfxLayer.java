package com.crystalgraphics.vfx.look;

import com.crystalgraphics.api.material.CgMaterialState;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshLoader;
import com.crystalgraphics.api.mesh.CgMeshLods;
import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.render.world.CgSortLayer;
import com.crystalgraphics.shape.bounds.CgAABB;
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
import java.util.function.Supplier;

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
 *
 * CgVfxLayer slash = CgVfxLayer.of("mymod:shaders/vfx/slash.shader")
 *         .on(SLASH)                                       // a mesh slot: this mesh, not the unit sphere
 *         .mesh("mymod:models/slash.obj")                  // loaded at its first draw
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
 *   <li>Order within an effect is by {@link Role}, then {@link Builder#order} (Niagara's sort order hint, 0 unless
 *       given), then the look's list order.</li>
 *   <li>{@link Builder#state} draws its shader under another blend, cull or depth write, as a kit's material
 *       authors it: one shader for every state a kit asks of it.</li>
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
    final int order;
    final CgSortLayer sortLayer;
    final CgMaterialState state;
    final CgQuality from;
    final boolean volume, closed;
    final Strip strip;
    final int subdivisions;
    final Map<String, float[]> constants;
    final List<Binding> bindings;
    final Map<String, Supplier<? extends CgTexture>> textures;
    final Map<String, CgSampler> samplers;
    private volatile Map<String, CgTexture> madeTextures;
    final Set<String> keywords, unbound;
    /** What it draws on a mesh slot or per particle of a MESHES emitter instead of the sphere; a path loads at first draw. */
    private final CgMeshLods meshLods;
    private final String meshPath;
    private volatile CgMeshLods loaded;
    private volatile float meshRadius = Float.NaN;
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
        this.order = b.order;
        this.sortLayer = b.sortLayer;
        this.state = b.state;
        this.from = b.from;
        this.volume = b.volume;
        this.closed = b.closed;
        this.strip = b.strip;
        this.subdivisions = b.subdivisions;
        this.constants = Collections.unmodifiableMap(new LinkedHashMap<>(b.constants));
        this.bindings = Collections.unmodifiableList(new ArrayList<>(b.bindings));
        this.textures = Collections.unmodifiableMap(new LinkedHashMap<>(b.textures));
        this.samplers = Collections.unmodifiableMap(new LinkedHashMap<>(b.samplers));
        this.keywords = Collections.unmodifiableSet(new LinkedHashSet<>(b.keywords));
        this.unbound = Collections.unmodifiableSet(new LinkedHashSet<>(b.unbound));
        this.meshLods = b.meshLods;
        this.meshPath = b.meshPath;
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
        b.order = order;
        b.sortLayer = sortLayer;
        b.state = state;
        b.from = from;
        b.volume = volume;
        b.closed = closed;
        b.strip = strip;
        b.subdivisions = subdivisions;
        b.constants.putAll(constants);
        b.bindings.addAll(bindings);
        b.textures.putAll(textures);
        b.samplers.putAll(samplers);
        b.keywords.addAll(keywords);
        b.unbound.addAll(unbound);
        b.meshLods = meshLods;
        b.meshPath = meshPath;
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

    /** Its sort order hint within its role ({@link Builder#order}): lowest draws first; 0 unless given. */
    public int order() {
        return order;
    }

    /** The sort layer it draws in among every translucent draw ({@link Builder#sortLayer}), or null for the engine's. */
    public CgSortLayer sortLayer() {
        return sortLayer;
    }

    /** The render state its materials draw under where it is not its shader's ({@link Builder#state}). */
    public CgMaterialState state() {
        return state;
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

    /** How it draws an emitter's particles: each as its renderer says, a trail behind each, or one ribbon through them. */
    public Strip strip() {
        return strip;
    }

    /** Quads between two points of a trail or a {@link Strip} ({@link Builder#subdivisions}); 0 for the slot's own. */
    public int subdivisions() {
        return subdivisions;
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

    /** Its textures by property, each made at the first call: what its materials bind, at its first draw. */
    public Map<String, CgTexture> textures() {
        Map<String, CgTexture> made = madeTextures;
        if (made == null) {
            Map<String, CgTexture> m = new LinkedHashMap<>();
            for (Map.Entry<String, Supplier<? extends CgTexture>> t : textures.entrySet()) {
                m.put(t.getKey(), Objects.requireNonNull(t.getValue().get(), t.getKey()));
            }
            madeTextures = made = Collections.unmodifiableMap(m);
        }
        return made;
    }

    /** The sampler each texture is sampled by in place of its own, by property; a texture absent samples as its own. */
    public Map<String, CgSampler> samplers() {
        return samplers;
    }

    /** The {@code cg_feature} keywords set on its materials, beside its slot kind's {@code FX_ON_*}. */
    public Set<String> keywords() {
        return keywords;
    }

    /** Properties kept the shader's own, never bound by name. */
    public Set<String> unbound() {
        return unbound;
    }

    /** Whether it draws a mesh of its own ({@link Builder#mesh}). */
    public boolean hasMesh() {
        return meshLods != null || meshPath != null;
    }

    /** Its mesh, a path loaded the first time it is asked (on the render thread, at its first draw); null for none. */
    public CgMeshLods mesh() {
        if (meshLods != null) return meshLods;
        if (meshPath == null) return null;
        CgMeshLods lods = loaded;
        if (lods == null) loaded = lods = single(CgMeshLoader.load(meshPath));
        return lods;
    }

    /** The farthest its mesh reaches from its origin, at size 1: what a particle drawn with it is culled by; 1 for none. */
    public float meshRadius() {
        float r = meshRadius;
        if (r == r) return r;
        CgMeshLods lods = mesh();
        if (lods == null) {
            r = 1f;
        } else {
            CgAABB box = lods.finest().bounds(new CgAABB());
            double x = Math.max(Math.abs(box.minX()), Math.abs(box.maxX()));
            double y = Math.max(Math.abs(box.minY()), Math.abs(box.maxY()));
            double z = Math.max(Math.abs(box.minZ()), Math.abs(box.maxZ()));
            r = (float) Math.sqrt(x * x + y * y + z * z);
        }
        meshRadius = r;
        return r;
    }

    private static CgMeshLods single(CgMesh mesh) {
        return CgMeshLods.builder().level(mesh, 0f).build();
    }

    @Override
    public String toString() {
        return "layer " + name + " (" + shader + ")";
    }

    /** How a layer on an emitter draws its particles ({@link Builder#trails}, {@link Builder#ribbon}). */
    public enum Strip {
        /** Each particle as its emitter's renderer says. */
        NONE,
        /** A trail behind each particle, through the positions its emitter keeps. */
        TRAILS,
        /** One ribbon through an instance's particles in spawn order. */
        RIBBON
    }

    public static final class Builder {

        private final String shader;
        private String name;
        private final List<CgVfxSlot> slots = new ArrayList<>();
        private float size = 1f;
        private CgVfxParam.Scalar sizeParam;
        private Role role = Role.SURFACE;
        private int order;
        private CgSortLayer sortLayer;
        private CgMaterialState state = CgMaterialState.SHADER;
        private CgQuality from = CgQuality.LOW;
        private boolean volume, closed;
        private Strip strip = Strip.NONE;
        private int subdivisions;
        private final Map<String, float[]> constants = new LinkedHashMap<>();
        private final List<Binding> bindings = new ArrayList<>();
        private final Map<String, Supplier<? extends CgTexture>> textures = new LinkedHashMap<>();
        private final Map<String, CgSampler> samplers = new LinkedHashMap<>();
        private final Set<String> keywords = new LinkedHashSet<>(), unbound = new LinkedHashSet<>();
        private CgMeshLods meshLods;
        private String meshPath;

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

        /**
         * Where it draws among its role's layers, lowest first, ties in the look's order: Niagara's renderer sort order
         * hint. An imported kit's render layers are this.
         *
         * <pre>{@code
         * CgVfxLayer.of(GLOW).on(BALL).order(12).build()      // over the layers of order 0 to 11
         * }</pre>
         */
        public Builder order(int hint) {
            if (hint < 0 || hint > 255) throw new IllegalArgumentException("order " + hint + ", 0 to 255");
            this.order = hint;
            return this;
        }

        /**
         * The sort layer its draws go in among every translucent draw in the world, Unreal's translucency sort
         * priority: drawn after every draw of an earlier layer, whatever their distances. The effects' own unless given.
         *
         * <pre>{@code
         * CgVfxLayer.of(FLASH).on(BALL).sortLayer(CgSortLayer.OVERLAY).build()
         * }</pre>
         */
        public Builder sortLayer(CgSortLayer layer) {
            this.sortLayer = Objects.requireNonNull(layer, "layer");
            return this;
        }

        /**
         * Draws its shader under {@code state} where it is not the shader's: another blend, two-sided, a depth write,
         * a clip. One shader serves every state a kit asks of it; each blend compiles once.
         *
         * <pre>{@code
         * CgVfxLayer.of(SOFT).on(BALL).state(CgMaterialState.builder().blend(CgBlendMode.ADDITIVE).twoSided().build())
         * }</pre>
         */
        public Builder state(CgMaterialState state) {
            this.state = Objects.requireNonNull(state, "state");
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

        /**
         * Draws a trail behind each of its emitter's particles, through the last positions the emitter keeps
         * ({@code CgVfxEmitter.Builder.trail}), with a strip shader ({@code shaders/vfx/particle/streaks.shader}). Its size
         * is the trail's half-width at a particle of size 1.
         *
         * <pre>{@code
         * CgVfxLayer.of("crystalgraphics:shaders/vfx/particle/streaks.shader").on(EMBERS).trails().size(0.6f)
         * }</pre>
         */
        public Builder trails() {
            this.strip = Strip.TRAILS;
            return this;
        }

        /**
         * Draws one ribbon through its emitter's particles in each instance, joined in the order they spawned, newest
         * first ({@code shaders/vfx/particle/ribbon.shader}): a stream whose particles each move as their modules push
         * them. Its size is the ribbon's half-width at a particle of size 1; the size curve shapes it along its life.
         *
         * <pre>{@code
         * CgVfxLayer.of("crystalgraphics:shaders/vfx/particle/ribbon.shader").on(STREAM).ribbon().size(0.3f)
         * }</pre>
         */
        public Builder ribbon() {
            this.strip = Strip.RIBBON;
            return this;
        }

        /**
         * Quads it draws between two points of a trail slot or of its particles' trails or ribbon: 4 on a trail slot
         * and 2 on particles unless given. More where points are far apart (a fast swing), fewer for a haze under a glow.
         *
         * <pre>{@code
         * CgVfxLayer.of("crystalgraphics:shaders/vfx/trail/swoosh.shader").on(SWING).subdivisions(8)
         * }</pre>
         */
        public Builder subdivisions(int quads) {
            if (quads < 1 || quads > 16) throw new IllegalArgumentException("subdivisions " + quads + ", 1 to 16");
            this.subdivisions = quads;
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
            Objects.requireNonNull(texture, "texture");
            return texture(property, () -> texture);
        }

        /**
         * A texture made at the layer's first draw, never before: one generated in code (a colour map, a lookup table)
         * named in common code, which a server then never makes.
         *
         * <pre>{@code
         * layer.texture("_Colormap", CgVfxColormap.FIRE::texture)
         * }</pre>
         */
        public Builder texture(String property, Supplier<? extends CgTexture> texture) {
            textures.put(Objects.requireNonNull(property, "property"), Objects.requireNonNull(texture, "texture"));
            samplers.remove(property);
            return this;
        }

        /**
         * A texture sampled by {@code sampler} in place of its own filter and wrap: one image loaded once, clamped by
         * one layer and tiled by another. Unreal's shared sampler source.
         *
         * <pre>{@code
         * static final CgTexture2D NOISE = CgTextures.of("mymod:textures/vfx/noise.png");
         * layer.texture("_Mask", NOISE, CgSampler.BILINEAR_CLAMP)      // a soft edge
         * other.texture("_Scroll", NOISE, CgSampler.BILINEAR_WRAP)     // the same image, tiled
         * }</pre>
         */
        public Builder texture(String property, CgTexture texture, CgSampler sampler) {
            texture(property, texture);
            samplers.put(property, Objects.requireNonNull(sampler, "sampler"));
            return this;
        }

        /**
         * Which frame of its sheet each particle shows: a grid or a rect table, over its life, looping or at random.
         * For a shader reading {@code _SubImage}, {@code _SubUV} and {@code _SubImages} ({@code sprite},
         * {@code sprite_glow}).
         *
         * <pre>{@code
         * layer.texture("_Sheet", FIRE).flipbook(CgVfxFlipbook.grid(4, 4).loop(24f).randomStart())
         * }</pre>
         */
        public Builder flipbook(CgVfxFlipbook flipbook) {
            Objects.requireNonNull(flipbook, "flipbook");
            float[] subImage = flipbook.subImage(), subUv = flipbook.subUv();
            set("_SubImage", subImage[0], subImage[1], subImage[2], subImage[3]);
            set("_SubUV", subUv[0], subUv[1], subUv[2], subUv[3]);
            if (flipbook.isTable()) texture("_SubImages", flipbook::table);
            else textures.remove("_SubImages");
            return this;
        }

        /**
         * Draws {@code mesh} instead of the unit sphere: on a mesh slot placed by the slot's transform, or per particle
         * of a {@code MESHES} emitter, turned and sized as each. On any other slot it throws at its first draw.
         *
         * <pre>{@code
         * .layer(CgVfxLayer.of(SLASH_SHADER).on(SLASH).mesh(CgProceduralMesh.revolve(...).build()).build())
         * .layer(CgVfxLayer.of(ROCK_SHADER).on(DEBRIS).mesh(ROCK_LODS).build())   // a MESHES emitter: one per particle
         * }</pre>
         */
        public Builder mesh(CgMesh mesh) {
            this.meshLods = single(Objects.requireNonNull(mesh, "mesh"));
            this.meshPath = null;
            return this;
        }

        /** As {@link #mesh(CgMesh)}, a level per screen height. */
        public Builder mesh(CgMeshLods lods) {
            this.meshLods = Objects.requireNonNull(lods, "lods");
            this.meshPath = null;
            return this;
        }

        /** As {@link #mesh(CgMesh)}, an OBJ or glTF file, loaded at the layer's first draw: a server naming it loads nothing. */
        public Builder mesh(String path) {
            this.meshPath = Objects.requireNonNull(path, "path");
            this.meshLods = null;
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
