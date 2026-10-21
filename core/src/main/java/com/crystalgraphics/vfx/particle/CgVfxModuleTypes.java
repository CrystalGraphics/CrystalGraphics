package com.crystalgraphics.vfx.particle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import com.crystalgraphics.object.CgPropertyFlag;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgTypeKind;
import com.crystalgraphics.object.CgTypes;
import com.crystalgraphics.object.CgValueType;
import com.crystalgraphics.object.layout.CgLayout;
import com.crystalgraphics.object.layout.CgLayoutValues;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.primitive.CgBox;
import com.crystalgraphics.shape.primitive.CgPlane;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.vfx.particle.gpu.CgVfxModuleWords;

/**
 * Each {@link CgVfxModule} kind declared as data: its inputs in GPU word order, the {@link CgLayout} and
 * {@link CgVfxModuleWords} compiled from them, and the two directions between a module and a module entry's
 * {@code inputs}: {@link Kind#inputs} writes an asset's, {@link Kind#compile} builds the module from them.
 *
 * <pre>{@code
 * CgVfxModuleTypes.Kind<?> drag = CgVfxModuleTypes.kind("drag");
 * Map<String, Object> inputs = CgVfxModuleTypes.inputs(new CgVfxModule.Drag(4f, 0f));   // {linear: 4.0, quadratic: 0.0}
 * CgVfxModule back = drag.compile(inputs);                                              // Drag[linear=4.0, quadratic=0.0]
 * drag.words().write(drag.values(inputs), out);                                         // its row, from the declaration
 * }</pre>
 *
 * <ul>
 *   <li>A shape is {@code volume} (a sphere's radius three times, a box's half extents or a plane's normal) then
 *       {@code shape}, one vec4 as {@code fx_contact.glsl} reads it; its centre ({@code offset}) and a plane's radius
 *       are {@code PER_INSTANCE}, written to the instance lane.</li>
 *   <li>A field ({@link CgVfxModule.VectorField}) is an object input: a texture, outside the words.</li>
 *   <li>Declared in a registry of their own, so the bible gate's global types are untouched. Names follow
 *       {@code modules.yaml} where it declares the input; the engine's own kinds use their Java names, snake-cased.</li>
 * </ul>
 */
public final class CgVfxModuleTypes {

    private static final CgTypes REGISTRY = CgTypes.create();
    private static final CgValueType SHAPE = CgValueType.enumOf("sphere", "box", "plane");
    private static final Map<String, Kind<?>> BY_ID = new LinkedHashMap<>();
    private static final Map<Class<?>, Kind<?>> BY_CLASS = new LinkedHashMap<>();

    /**
     * One kind's declaration.
     *
     * @param id      its GPU kind, as {@link CgVfxModule#gpuKind()}
     * @param type    the record it compiles to
     * @param inputs  its inputs, in GPU word order
     * @param layout  the layout of {@code inputs}
     * @param words   its row, laid out from {@code layout}
     */
    public record Kind<M extends CgVfxModule>(String id, Class<M> type, CgType inputs, CgLayout layout,
                                              CgVfxModuleWords words, BiConsumer<M, Inputs> writer,
                                              Function<CgLayoutValues, M> reader) {

        /** {@code module}'s inputs by name, in declaration order. */
        public Map<String, Object> inputs(M module) {
            Inputs in = new Inputs();
            writer.accept(module, in);
            return Collections.unmodifiableMap(in.map);
        }

        /** {@code inputs} over the declaration's defaults. */
        public CgLayoutValues values(Map<?, ?> inputs) {
            return layout.values().setAll(inputs);
        }

        /** The module {@code inputs} declare. */
        public M compile(Map<?, ?> inputs) {
            return reader.apply(values(inputs));
        }

        /** The module {@code values} hold. */
        public M compile(CgLayoutValues values) {
            return reader.apply(values);
        }
    }

    /** A module's inputs as it writes them: numbers as doubles, vectors as lists, enums by name. */
    public static final class Inputs {
        private final Map<String, Object> map = new LinkedHashMap<>();

        Inputs f(String name, float v) {
            map.put(name, (double) v);
            return this;
        }

        Inputs i(String name, int v) {
            map.put(name, v);
            return this;
        }

        Inputs b(String name, boolean v) {
            map.put(name, v);
            return this;
        }

        Inputs v3(String name, float x, float y, float z) {
            map.put(name, Arrays.asList((double) x, (double) y, (double) z));
            return this;
        }

        Inputs v4(String name, float x, float y, float z, float w) {
            map.put(name, Arrays.asList((double) x, (double) y, (double) z, (double) w));
            return this;
        }

        Inputs o(String name, Object v) {
            map.put(name, v);
            return this;
        }

        Inputs shape(CgShape s) {
            if (s instanceof CgSphere sphere) {
                float r = (float) sphere.radius();
                v3("volume", r, r, r);
                map.put("shape", "sphere");
            } else if (s instanceof CgBox box) {
                v3("volume", (float) box.halfX(), (float) box.halfY(), (float) box.halfZ());
                map.put("shape", "box");
            } else {
                CgPlane plane = (CgPlane) s;
                v3("volume", (float) plane.nx(), (float) plane.ny(), (float) plane.nz());
                map.put("shape", "plane");
            }
            return this;
        }
    }

    /** Reads compiled values by name: compile time only. */
    private record Read(CgLayoutValues v) {
        float f(String name) {
            return v.getFloat(slot(name));
        }

        float f(String name, int lane) {
            return v.getFloat(slot(name), lane);
        }

        int i(String name) {
            return v.getInt(slot(name));
        }

        boolean b(String name) {
            return v.getBool(slot(name));
        }

        Object o(String name) {
            return v.getObject(slot(name));
        }

        CgShape shape() {
            float x = f("volume", 0), y = f("volume", 1), z = f("volume", 2);
            switch (i("shape")) {
                case 0: return CgSphere.of(x);
                case 1: return CgBox.of(x, y, z);
                default: return CgPlane.of(x, y, z, f("plane_radius"));
            }
        }

        private CgLayout.Slot slot(String name) {
            CgLayout.Slot s = v.layout().slot(name);
            if (s == null) throw new IllegalStateException(v.layout().id() + " has no input " + name);
            return s;
        }
    }

    static {
        // Inputs by the format's names (Niagara's Gravity Force and Drag), so a file's module reads as written.
        kind("gravity", CgVfxModule.Gravity.class, t -> v3(t, "gravity"),
                (m, in) -> in.v3("gravity", m.x(), m.y(), m.z()),
                r -> new CgVfxModule.Gravity(r.f("gravity", 0), r.f("gravity", 1), r.f("gravity", 2)));
        // Drag times Cascade's AccelerationDragScaleOverLife scale; the quadratic term only while its toggle is on.
        kind("drag", CgVfxModule.Drag.class,
                t -> { fl(t, "drag", "scale"); t.property("use_quadratic", CgValueType.BOOL); fl(t, "quadratic"); },
                (m, in) -> in.f("drag", m.linear()).f("scale", 1f).b("use_quadratic", m.quadratic() != 0f)
                        .f("quadratic", m.quadratic()),
                r -> new CgVfxModule.Drag(r.f("drag") * r.f("scale"), r.b("use_quadratic") ? r.f("quadratic") : 0f));
        kind("wind", CgVfxModule.Wind.class, t -> fl(t, "resistance"),
                (m, in) -> in.f("resistance", m.resistance()), r -> new CgVfxModule.Wind(r.f("resistance")));
        kind("turbulence", CgVfxModule.Turbulence.class, t -> fl(t, "strength", "frequency", "evolve"),
                (m, in) -> in.f("strength", m.strength()).f("frequency", m.frequency()).f("evolve", m.evolve()),
                r -> new CgVfxModule.Turbulence(r.f("strength"), r.f("frequency"), r.f("evolve")));
        kind("buoyancy", CgVfxModule.Buoyancy.class, t -> fl(t, "lift", "cooling"),
                (m, in) -> in.f("lift", m.lift()).f("cooling", m.cooling()),
                r -> new CgVfxModule.Buoyancy(r.f("lift"), r.f("cooling")));
        kind("updraft", CgVfxModule.Updraft.class, t -> fl(t, "strength", "radius", "height", "duration"),
                (m, in) -> in.f("strength", m.strength()).f("radius", m.radius()).f("height", m.height()).f("duration", m.duration()),
                r -> new CgVfxModule.Updraft(r.f("strength"), r.f("radius"), r.f("height"), r.f("duration")));
        kind("ground", CgVfxModule.Ground.class, t -> fl(t, "restitution", "friction", "rest", "radius"),
                (m, in) -> in.f("restitution", m.restitution()).f("friction", m.friction()).f("rest", m.rest()).f("radius", m.radius()),
                r -> new CgVfxModule.Ground(r.f("restitution"), r.f("friction"), r.f("rest"), r.f("radius")));
        kind("current", CgVfxModule.Current.class, t -> fl(t, "depth", "roll", "loft", "slow"),
                (m, in) -> in.f("depth", m.depth()).f("roll", m.roll()).f("loft", m.loft()).f("slow", m.slow()),
                r -> new CgVfxModule.Current(r.f("depth"), r.f("roll"), r.f("loft"), r.f("slow")));
        kind("spin", CgVfxModule.Spin.class, t -> fl(t, "drag"),
                (m, in) -> in.f("drag", m.drag()), r -> new CgVfxModule.Spin(r.f("drag")));
        kind("attract", CgVfxModule.Attract.class,
                t -> { shape(t); fl(t, "strength", "attenuation", "directionality"); v3(t, "axis"); offset(t); },
                (m, in) -> in.shape(m.shape()).f("strength", m.strength()).f("attenuation", m.attenuation())
                        .f("directionality", m.directionality()).v3("axis", m.axisX(), m.axisY(), m.axisZ())
                        .v3("offset", m.x(), m.y(), m.z()),
                r -> new CgVfxModule.Attract(r.shape(), r.f("offset", 0), r.f("offset", 1), r.f("offset", 2), r.f("strength"),
                        r.f("attenuation"), r.f("directionality"), r.f("axis", 0), r.f("axis", 1), r.f("axis", 2)));
        kind("orbit", CgVfxModule.Orbit.class, t -> { v3(t, "axis"); fl(t, "turns"); offset(t); },
                (m, in) -> in.v3("axis", m.axisX(), m.axisY(), m.axisZ()).f("turns", m.turns()).v3("offset", m.x(), m.y(), m.z()),
                r -> new CgVfxModule.Orbit(r.f("turns"), r.f("axis", 0), r.f("axis", 1), r.f("axis", 2),
                        r.f("offset", 0), r.f("offset", 1), r.f("offset", 2)));
        kind("vortex", CgVfxModule.Vortex.class, t -> { v3(t, "axis"); fl(t, "tangential", "radial"); offset(t); },
                (m, in) -> in.v3("axis", m.axisX(), m.axisY(), m.axisZ()).f("tangential", m.tangential()).f("radial", m.radial())
                        .v3("offset", m.x(), m.y(), m.z()),
                r -> new CgVfxModule.Vortex(r.f("axis", 0), r.f("axis", 1), r.f("axis", 2), r.f("tangential"), r.f("radial"),
                        r.f("offset", 0), r.f("offset", 1), r.f("offset", 2)));
        kind("flock", CgVfxModule.Flock.class,
                t -> { fl(t, "radius", "separation", "alignment", "cohesion", "apart"); t.property("most", CgValueType.INT).defaultValue(24); },
                (m, in) -> in.f("radius", m.radius()).f("separation", m.separation()).f("alignment", m.alignment())
                        .f("cohesion", m.cohesion()).f("apart", m.apart()).i("most", m.most()),
                r -> new CgVfxModule.Flock(r.f("radius"), r.f("apart"), r.f("separation"), r.f("alignment"), r.f("cohesion"), r.i("most")));
        kind("flow", CgVfxModule.Flow.class, t -> fl(t, "follow"),
                (m, in) -> in.f("follow", m.follow()), r -> new CgVfxModule.Flow(r.f("follow")));
        kind("gas_feed", CgVfxModule.Feed.class, t -> fl(t, "density", "heat", "push"),
                (m, in) -> in.f("density", m.density()).f("heat", m.heat()).f("push", m.push()),
                r -> new CgVfxModule.Feed(r.f("density"), r.f("heat"), r.f("push")));
        kind("grid_splat", CgVfxModule.Splat.class, t -> t.property("weights", CgValueType.VEC4),
                (m, in) -> in.v4("weights", m.x(), m.y(), m.z(), m.w()),
                r -> new CgVfxModule.Splat(r.f("weights", 0), r.f("weights", 1), r.f("weights", 2), r.f("weights", 3)));
        kind("grid_follow", CgVfxModule.Follow.class,
                t -> { t.property("channel", CgValueType.INT); fl(t, "sense", "angle", "turn", "full"); },
                (m, in) -> in.i("channel", m.channel()).f("sense", m.sense()).f("angle", m.angle()).f("turn", m.turn()).f("full", m.full()),
                r -> new CgVfxModule.Follow(r.i("channel"), r.f("sense"), r.f("angle"), r.f("turn"), r.f("full")));
        kind("grid_gradient", CgVfxModule.Gradient.class,
                t -> { t.property("channel", CgValueType.INT); fl(t, "strength"); },
                (m, in) -> in.i("channel", m.channel()).f("strength", m.strength()),
                r -> new CgVfxModule.Gradient(r.i("channel"), r.f("strength")));
        kind("force", CgVfxModule.Force.class, t -> v3(t, "force"),
                (m, in) -> in.v3("force", m.x(), m.y(), m.z()),
                r -> new CgVfxModule.Force(r.f("force", 0), r.f("force", 1), r.f("force", 2)));
        kind("vector_field", CgVfxModule.VectorField.class,
                t -> { t.property("field", CgValueType.FIELD); v3(t, "volume"); t.property("tiles", CgValueType.BOOL);
                    fl(t, "strength", "attenuation"); offset(t); },
                (m, in) -> in.o("field", m.field()).v3("volume", (float) m.box().halfX(), (float) m.box().halfY(), (float) m.box().halfZ())
                        .b("tiles", m.field().tiles()).f("strength", m.strength()).f("attenuation", m.attenuation())
                        .v3("offset", m.x(), m.y(), m.z()),
                r -> new CgVfxModule.VectorField((CgVfxField) r.o("field"), CgBox.of(r.f("volume", 0), r.f("volume", 1), r.f("volume", 2)),
                        r.f("offset", 0), r.f("offset", 1), r.f("offset", 2), r.f("strength"), r.f("attenuation")));
        kind("damping", CgVfxModule.Damping.class, t -> fl(t, "rate"),
                (m, in) -> in.f("rate", m.rate()), r -> new CgVfxModule.Damping(r.f("rate")));
        kind("limit_speed", CgVfxModule.LimitSpeed.class, t -> fl(t, "max_speed"),
                (m, in) -> in.f("max_speed", m.most()), r -> new CgVfxModule.LimitSpeed(r.f("max_speed")));
        kind("conform", CgVfxModule.Conform.class,
                t -> { fl(t, "radius", "influence", "attraction", "speed", "shell", "sticky"); offset(t); },
                (m, in) -> in.f("radius", (float) m.sphere().radius()).f("influence", m.influence()).f("attraction", m.attraction())
                        .f("speed", m.speed()).f("shell", m.shell()).f("sticky", m.sticky()).v3("offset", m.x(), m.y(), m.z()),
                r -> new CgVfxModule.Conform(CgSphere.of(r.f("radius")), r.f("offset", 0), r.f("offset", 1), r.f("offset", 2),
                        r.f("influence"), r.f("attraction"), r.f("speed"), r.f("shell"), r.f("sticky")));
        kind("collide", CgVfxModule.Collide.class,
                t -> { shape(t); fl(t, "bounce", "friction", "rest"); t.property("kill", CgValueType.BOOL);
                    fl(t, "radius"); t.property("keeps_inside", CgValueType.BOOL); offset(t); planeRadius(t); },
                (m, in) -> in.shape(m.shape()).f("bounce", m.bounce()).f("friction", m.friction()).f("rest", m.rest())
                        .b("kill", m.kill()).f("radius", m.radius()).b("keeps_inside", m.keepsInside())
                        .v3("offset", m.x(), m.y(), m.z()).f("plane_radius", planeRadius(m.shape())),
                r -> new CgVfxModule.Collide(r.shape(), r.f("offset", 0), r.f("offset", 1), r.f("offset", 2), r.f("bounce"),
                        r.f("friction"), r.f("rest"), r.f("radius"), r.b("keeps_inside"), r.b("kill")));
        kind("collide_world", CgVfxModule.CollideWorld.class,
                t -> { fl(t, "bounce", "friction", "rest"); t.property("kill", CgValueType.BOOL); },
                (m, in) -> in.f("bounce", m.bounce()).f("friction", m.friction()).f("rest", m.rest()).b("kill", m.kill()),
                r -> new CgVfxModule.CollideWorld(r.f("bounce"), r.f("friction"), r.f("rest"), r.b("kill")));
        kind("collide_depth", CgVfxModule.CollideDepth.class,
                t -> { fl(t, "bounce", "friction", "rest"); t.property("kill", CgValueType.BOOL); fl(t, "thickness"); },
                (m, in) -> in.f("bounce", m.bounce()).f("friction", m.friction()).f("rest", m.rest()).b("kill", m.kill())
                        .f("thickness", m.thickness()),
                r -> new CgVfxModule.CollideDepth(r.f("bounce"), r.f("friction"), r.f("rest"), r.f("thickness"), r.b("kill")));
        kind("collide_entities", CgVfxModule.CollideEntities.class,
                t -> { fl(t, "bounce", "friction", "rest"); t.property("kill", CgValueType.BOOL); fl(t, "size"); },
                (m, in) -> in.f("bounce", m.bounce()).f("friction", m.friction()).f("rest", m.rest()).b("kill", m.kill())
                        .f("size", m.size()),
                r -> new CgVfxModule.CollideEntities(r.f("bounce"), r.f("friction"), r.f("rest"), r.f("size"), r.b("kill")));
        kind("kill", CgVfxModule.Kill.class,
                t -> { shape(t); t.property("inside", CgValueType.BOOL); offset(t); planeRadius(t); },
                (m, in) -> in.shape(m.shape()).b("inside", m.inside()).v3("offset", m.x(), m.y(), m.z())
                        .f("plane_radius", planeRadius(m.shape())),
                r -> new CgVfxModule.Kill(r.shape(), r.f("offset", 0), r.f("offset", 1), r.f("offset", 2), r.b("inside")));
    }

    private CgVfxModuleTypes() {
    }

    private static <M extends CgVfxModule> void kind(String id, Class<M> type, Consumer<CgType.Builder> declare,
                                                     BiConsumer<M, Inputs> writer, Function<Read, M> reader) {
        CgType.Builder b = REGISTRY.declare("crystalgraphics:gpu_module." + id, CgTypeKind.STRUCT);
        declare.accept(b);
        CgType t = b.register();
        CgLayout layout = CgLayout.of(t);
        Kind<M> kind = new Kind<>(id, type, t, layout, CgVfxModuleWords.of(layout), writer, v -> reader.apply(new Read(v)));
        BY_ID.put(id, kind);
        BY_CLASS.put(type, kind);
    }

    private static void fl(CgType.Builder t, String... names) {
        for (String n : names) t.property(n, CgValueType.FLOAT);
    }

    private static void v3(CgType.Builder t, String name) {
        t.property(name, CgValueType.VEC3);
    }

    private static void shape(CgType.Builder t) {
        v3(t, "volume");
        t.property("shape", SHAPE).defaultValue("sphere");
    }

    private static void offset(CgType.Builder t) {
        t.property("offset", CgValueType.VEC3).defaultValue(Arrays.asList(0.0, 0.0, 0.0)).flags(CgPropertyFlag.PER_INSTANCE);
    }

    private static void planeRadius(CgType.Builder t) {
        t.property("plane_radius", CgValueType.FLOAT).defaultValue(1.0).flags(CgPropertyFlag.PER_INSTANCE);
    }

    private static float planeRadius(CgShape s) {
        return s instanceof CgPlane plane ? (float) plane.radius() : 1f;
    }

    /** The kind whose {@link CgVfxModule#gpuKind()} is {@code id}, or null. */
    public static Kind<?> kind(String id) {
        return BY_ID.get(id);
    }

    /** Every kind, in declaration order. */
    public static List<Kind<?>> all() {
        return new ArrayList<>(BY_ID.values());
    }

    /** {@code module}'s kind: throws for a kind with no declaration (a mod's own). */
    @SuppressWarnings("unchecked")
    public static <M extends CgVfxModule> Kind<M> of(M module) {
        Kind<M> k = (Kind<M>) BY_CLASS.get(module.getClass());
        if (k == null) throw new IllegalArgumentException("no declaration for the module kind " + module.gpuKind());
        return k;
    }

    /** {@code module}'s inputs, as its asset entry writes them. */
    public static <M extends CgVfxModule> Map<String, Object> inputs(M module) {
        return of(module).inputs(module);
    }
}
