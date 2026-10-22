package com.crystalgraphics.vfx.particle;

import org.joml.Vector3fc;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuModule;
import com.crystalgraphics.vfx.particle.gpu.CgVfxModuleWords;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWords;

/**
 * Every module kind declared as data: its inputs in GPU word order, the {@link CgLayout} and {@link CgVfxModuleWords}
 * compiled from them, and the two directions between a module and a module entry's {@code inputs}: {@link Kind#inputs}
 * writes an asset's, {@link Kind#compile} builds the module from them. A kind's GPU numbers are its declaration's words:
 * nothing writes them by hand.
 *
 * <pre>{@code
 * CgVfxModuleTypes.Kind<?> drag = CgVfxModuleTypes.kind("drag");
 * Map<String, Object> inputs = CgVfxModuleTypes.inputs(new CgVfxModule.Drag(4f, 0f));   // {linear: 4.0, quadratic: 0.0}
 * CgVfxModule back = drag.compile(inputs);                                              // Drag[linear=4.0, quadratic=0.0]
 * int[] row = CgVfxModuleTypes.words(new CgVfxModule.Drag(4f, 0f));                     // its vec4s, from the declaration
 * }</pre>
 *
 * <p>A kind of a mod's own declares its inputs once, in its class's static initialiser, in the order its
 * {@code fx_<kind>} reads them:</p>
 * <pre>{@code
 * record Swirl(float strength, float height) implements CgVfxGpuModule {
 *     static {
 *         CgVfxModuleTypes.declare("mymod_swirl", Swirl.class,
 *                 t -> t.property("strength", CgValueType.FLOAT).property("height", CgValueType.FLOAT),
 *                 (m, in) -> in.f("strength", m.strength()).f("height", m.height()));
 *     }
 *     public String gpuKind() { return "mymod_swirl"; }
 *     public boolean afterSolve() { return false; }
 * }
 * // void fx_mymod_swirl(inout FxParticle p, inout FxForces f, FxStep s, vec4 m)   // m.x strength, m.y height
 * }</pre>
 *
 * <p>Read back from an asset's inputs too, by adding the reader:</p>
 * <pre>{@code
 * CgVfxModuleTypes.declare("mymod_swirl", Swirl.class, t -> ..., (m, in) -> ...,
 *         r -> new Swirl(r.f("strength"), r.f("height")));
 * }</pre>
 *
 * <ul>
 *   <li>Packing is {@link CgVfxModuleWords}': a scalar takes the next lane, a {@code vec3} or {@code vec4} a new
 *       vec4.</li>
 *   <li>Declare before the first definition holding the kind opens in a pool: a kind with no declaration throws there,
 *       naming it. A second declaration of an id by another class throws.</li>
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
    private static final CgVfxModule.BeamSource.Tangent[] TANGENTS = CgVfxModule.BeamSource.Tangent.values();
    private static final CgValueType TANGENT = CgValueType.enumOf("direct", "user_set", "distribution");
    private static final String[] MODIFIER_FLAGS = {"modify_position", "scale_position", "modify_tangent", "scale_tangent",
            "modify_strength", "scale_strength"};
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
    public record Kind<M extends CgVfxGpuModule>(String id, Class<M> type, CgType inputs, CgLayout layout,
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

        /** The module {@code inputs} declare: throws for a kind declared without a reader. */
        public M compile(Map<?, ?> inputs) {
            return compile(values(inputs));
        }

        /** The module {@code values} hold: throws for a kind declared without a reader. */
        public M compile(CgLayoutValues values) {
            if (reader == null) throw new IllegalStateException(id + " is declared for code alone: it reads no inputs");
            return reader.apply(values);
        }

        /** {@code module}'s {@link #words} vec4s, in a new array. */
        public int[] words(M module) {
            int[] out = new int[words.vectors() * 4];
            CgVfxWords to = CgVfxWords.into(out, 0, words.vectors(), id);
            words.write(values(inputs(module)), to);
            to.finish("its declaration");
            return out;
        }
    }

    /** A module's inputs as its kind's writer gives them: numbers as doubles, vectors as lists, enums by name. */
    public static final class Inputs {
        private final Map<String, Object> map = new LinkedHashMap<>();

        /** A {@code float} input. */
        public Inputs f(String name, float v) {
            map.put(name, (double) v);
            return this;
        }

        /** An {@code int} input. */
        public Inputs i(String name, int v) {
            map.put(name, v);
            return this;
        }

        /** A {@code bool} input: 1 or 0 in its word. */
        public Inputs b(String name, boolean v) {
            map.put(name, v);
            return this;
        }

        /** A {@code vec3} input. */
        public Inputs v3(String name, float x, float y, float z) {
            map.put(name, Arrays.asList((double) x, (double) y, (double) z));
            return this;
        }

        /** A {@code vec4} input. */
        public Inputs v4(String name, float x, float y, float z, float w) {
            map.put(name, Arrays.asList((double) x, (double) y, (double) z, (double) w));
            return this;
        }

        /** An object input (a field, a texture): outside the words. */
        public Inputs o(String name, Object v) {
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

    /** A kind's compiled values read by name, for its reader: compile time only. */
    public record Read(CgLayoutValues v) {
        public float f(String name) {
            return v.getFloat(slot(name));
        }

        /** Component {@code lane} of a vector input. */
        public float f(String name, int lane) {
            return v.getFloat(slot(name), lane);
        }

        public int i(String name) {
            return v.getInt(slot(name));
        }

        public boolean b(String name) {
            return v.getBool(slot(name));
        }

        public Object o(String name) {
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
        kind("curl_noise", CgVfxModule.Turbulence.class, t -> fl(t, "strength", "frequency", "evolve"),
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
        kind("vortex", CgVfxModule.Vortex.class, t -> { v3(t, "axis"); fl(t, "tangential", "radial"); offset(t, "origin"); },
                (m, in) -> in.v3("axis", m.axisX(), m.axisY(), m.axisZ()).f("tangential", m.tangential()).f("radial", m.radial())
                        .v3("origin", m.x(), m.y(), m.z()),
                r -> new CgVfxModule.Vortex(r.f("axis", 0), r.f("axis", 1), r.f("axis", 2), r.f("tangential"), r.f("radial"),
                        r.f("origin", 0), r.f("origin", 1), r.f("origin", 2)));
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
        kind("grid_splat", CgVfxModule.Splat.class, t -> t.property("value", CgValueType.VEC4),
                (m, in) -> in.v4("value", m.x(), m.y(), m.z(), m.w()),
                r -> new CgVfxModule.Splat(r.f("value", 0), r.f("value", 1), r.f("value", 2), r.f("value", 3)));
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
                t -> { t.property("field", CgValueType.FIELD); v3(t, "volume"); t.property("tiles", CgValueType.INT);
                    fl(t, "strength", "attenuation", "tightness"); v3(t, "pivot"); offset(t); },
                (m, in) -> in.o("field", m.field()).v3("volume", (float) m.box().halfX(), (float) m.box().halfY(), (float) m.box().halfZ())
                        .i("tiles", m.field().tileAxes()).f("strength", m.strength()).f("attenuation", m.attenuation())
                        .f("tightness", m.tightness()).v3("pivot", m.turn().pivot()[0], m.turn().pivot()[1], m.turn().pivot()[2])
                        .v3("offset", m.x(), m.y(), m.z()),
                r -> new CgVfxModule.VectorField((CgVfxField) r.o("field"), CgBox.of(r.f("volume", 0), r.f("volume", 1), r.f("volume", 2)),
                        r.f("offset", 0), r.f("offset", 1), r.f("offset", 2), r.f("strength"), r.f("attenuation"), r.f("tightness"),
                        new CgVfxFieldTurn(new float[]{r.f("pivot", 0), r.f("pivot", 1), r.f("pivot", 2)}, new float[]{0f, 0f, 0f, 1f},
                                new float[3], new float[3], new float[3])));
        kind("set_position", CgVfxModule.SetPosition.class,
                t -> { v3(t, "position"); v3(t, "offset"); v3(t, "scale"); },
                (m, in) -> in.v3("position", m.x(), m.y(), m.z()).v3("offset", m.ox(), m.oy(), m.oz()).v3("scale", m.share(), 1f, 1f),
                r -> new CgVfxModule.SetPosition(r.f("position", 0), r.f("position", 1), r.f("position", 2),
                        r.f("offset", 0), r.f("offset", 1), r.f("offset", 2), r.f("scale", 0)));
        kind("scale_velocity", CgVfxModule.ScaleVelocity.class,
                t -> { v3(t, "scale"); t.property("absolute", CgValueType.BOOL); },
                (m, in) -> in.v3("scale", m.x(), m.y(), m.z()).b("absolute", m.absolute()),
                r -> new CgVfxModule.ScaleVelocity(r.f("scale", 0), r.f("scale", 1), r.f("scale", 2), r.b("absolute")));
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
                t -> { shape(t); fl(t, "bounce", "friction", "rest", "radius"); t.property("keeps_inside", CgValueType.BOOL);
                    response(t); offset(t); planeRadius(t); },
                (m, in) -> response(in.shape(m.shape()).f("bounce", m.bounce()).f("friction", m.friction()).f("rest", m.rest())
                        .f("radius", m.radius()).b("keeps_inside", m.keepsInside()), m.response())
                        .v3("offset", m.x(), m.y(), m.z()).f("plane_radius", planeRadius(m.shape())),
                r -> new CgVfxModule.Collide(r.shape(), r.f("offset", 0), r.f("offset", 1), r.f("offset", 2), r.f("bounce"),
                        r.f("friction"), r.f("rest"), r.f("radius"), r.b("keeps_inside"), response(r)));
        kind("collide_world", CgVfxModule.CollideWorld.class,
                t -> { fl(t, "bounce", "friction", "rest", "reach"); response(t); },
                (m, in) -> response(in.f("bounce", m.bounce()).f("friction", m.friction()).f("rest", m.rest())
                        .f("reach", m.reach()), m.response()),
                r -> new CgVfxModule.CollideWorld(r.f("bounce"), r.f("friction"), r.f("rest"), response(r), r.f("reach")));
        kind("collide_depth", CgVfxModule.CollideDepth.class,
                t -> { fl(t, "bounce", "friction", "rest", "thickness", "reach"); response(t); },
                (m, in) -> response(in.f("bounce", m.bounce()).f("friction", m.friction()).f("rest", m.rest())
                        .f("thickness", m.thickness()).f("reach", m.reach()), m.response()),
                r -> new CgVfxModule.CollideDepth(r.f("bounce"), r.f("friction"), r.f("rest"), r.f("thickness"), response(r),
                        r.f("reach")));
        kind("collide_entities", CgVfxModule.CollideEntities.class,
                t -> { fl(t, "bounce", "friction", "rest", "size"); response(t); },
                (m, in) -> response(in.f("bounce", m.bounce()).f("friction", m.friction()).f("rest", m.rest())
                        .f("size", m.size()), m.response()),
                r -> new CgVfxModule.CollideEntities(r.f("bounce"), r.f("friction"), r.f("rest"), r.f("size"), response(r)));
        kind("kill", CgVfxModule.Kill.class,
                t -> { shape(t); t.property("inside", CgValueType.BOOL); offset(t); planeRadius(t); },
                (m, in) -> in.shape(m.shape()).b("inside", m.inside()).v3("offset", m.x(), m.y(), m.z())
                        .f("plane_radius", planeRadius(m.shape())),
                r -> new CgVfxModule.Kill(r.shape(), r.f("offset", 0), r.f("offset", 1), r.f("offset", 2), r.b("inside")));
        kind("beam_source", CgVfxModule.BeamSource.class, t -> beamEnd(t, false),
                (m, in) -> beamEnd(in, m.x(), m.y(), m.z(), m.absolute(), m.tx(), m.ty(), m.tz(), m.method(), m.strength(),
                        m.lock(), m.lockTangent(), m.lockStrength()),
                r -> new CgVfxModule.BeamSource(r.f("point", 0), r.f("point", 1), r.f("point", 2), r.b("absolute"),
                        r.f("tangent", 0), r.f("tangent", 1), r.f("tangent", 2), TANGENTS[r.i("tangent_method")],
                        r.f("strength"), r.b("lock"), r.b("lock_tangent"), r.b("lock_strength")));
        kind("beam_target", CgVfxModule.BeamTarget.class, t -> beamEnd(t, true),
                (m, in) -> beamEnd(in, m.x(), m.y(), m.z(), m.absolute(), m.tx(), m.ty(), m.tz(), m.method(), m.strength(),
                        m.lock(), m.lockTangent(), m.lockStrength()).f("lock_radius", m.lockRadius()).f("speed", m.speed()),
                r -> new CgVfxModule.BeamTarget(r.f("point", 0), r.f("point", 1), r.f("point", 2), r.b("absolute"),
                        r.f("tangent", 0), r.f("tangent", 1), r.f("tangent", 2), TANGENTS[r.i("tangent_method")],
                        r.f("strength"), r.b("lock"), r.b("lock_tangent"), r.b("lock_strength"), r.f("lock_radius"), r.f("speed")));
        // Words as beam.compute's Noise reads them from the parameter row: keep the order.
        kind("beam_noise", CgVfxModule.BeamNoise.class,
                t -> { t.property("frequency", CgValueType.INT); t.property("frequency_low", CgValueType.INT);
                    fl(t, "lock_time"); t.property("smooth", CgValueType.BOOL);
                    v3(t, "range_min"); t.property("oscillate", CgValueType.BOOL);
                    v3(t, "range_max"); t.property("uniform", CgValueType.BOOL);
                    v3(t, "speed_min"); fl(t, "lock_radius");
                    v3(t, "speed_max"); t.property("tessellation", CgValueType.INT);
                    fl(t, "range_scale_min", "range_scale_max", "tangent_strength_min", "tangent_strength_max",
                            "frequency_distance", "scale");
                    t.property("target_noise", CgValueType.BOOL); t.property("sheets", CgValueType.INT).defaultValue(1); },
                (m, in) -> in.i("frequency", m.frequency()).i("frequency_low", m.frequencyLow()).f("lock_time", m.lockTime())
                        .b("smooth", m.smooth()).v3("range_min", m.minX(), m.minY(), m.minZ()).b("oscillate", m.oscillate())
                        .v3("range_max", m.maxX(), m.maxY(), m.maxZ()).b("uniform", m.uniform())
                        .v3("speed_min", m.speedMinX(), m.speedMinY(), m.speedMinZ()).f("lock_radius", m.lockRadius())
                        .v3("speed_max", m.speedMaxX(), m.speedMaxY(), m.speedMaxZ()).i("tessellation", m.tessellation())
                        .f("range_scale_min", m.rangeScaleMin()).f("range_scale_max", m.rangeScaleMax())
                        .f("tangent_strength_min", m.tangentStrengthMin()).f("tangent_strength_max", m.tangentStrengthMax())
                        .f("frequency_distance", m.frequencyDistance()).f("scale", m.scale())
                        .b("target_noise", m.targetNoise()).i("sheets", m.sheets()),
                r -> new CgVfxModule.BeamNoise(r.i("frequency"), r.i("frequency_low"), r.f("lock_time"), r.b("smooth"),
                        r.f("range_min", 0), r.f("range_min", 1), r.f("range_min", 2),
                        r.f("range_max", 0), r.f("range_max", 1), r.f("range_max", 2), r.b("uniform"), r.b("oscillate"),
                        r.f("speed_min", 0), r.f("speed_min", 1), r.f("speed_min", 2),
                        r.f("speed_max", 0), r.f("speed_max", 1), r.f("speed_max", 2), r.f("lock_radius"),
                        r.i("tessellation"), r.f("range_scale_min"), r.f("range_scale_max"), r.f("tangent_strength_min"),
                        r.f("tangent_strength_max"), r.f("frequency_distance"), r.f("scale"), r.b("target_noise"),
                        r.i("sheets")));
        kind("beam_modifier", CgVfxModule.BeamModifier.class,
                t -> { v3(t, "position"); t.property("target", CgValueType.BOOL); v3(t, "tangent");
                    t.property("absolute_tangent", CgValueType.BOOL); fl(t, "strength");
                    for (String b : MODIFIER_FLAGS) t.property(b, CgValueType.BOOL); },
                (m, in) -> in.v3("position", m.x(), m.y(), m.z()).b("target", m.target()).v3("tangent", m.tx(), m.ty(), m.tz())
                        .b("absolute_tangent", m.absoluteTangent()).f("strength", m.strength())
                        .b("modify_position", m.modifyPosition()).b("scale_position", m.scalePosition())
                        .b("modify_tangent", m.modifyTangent()).b("scale_tangent", m.scaleTangent())
                        .b("modify_strength", m.modifyStrength()).b("scale_strength", m.scaleStrength()),
                r -> new CgVfxModule.BeamModifier(r.b("target"), r.f("position", 0), r.f("position", 1), r.f("position", 2),
                        r.b("modify_position"), r.b("scale_position"), r.f("tangent", 0), r.f("tangent", 1), r.f("tangent", 2),
                        r.b("modify_tangent"), r.b("scale_tangent"), r.b("absolute_tangent"), r.f("strength"),
                        r.b("modify_strength"), r.b("scale_strength")));
    }

    /** A beam end's inputs, as {@code fx_beam_source}/{@code fx_beam_target} read them; a target's two more. */
    private static void beamEnd(CgType.Builder t, boolean target) {
        v3(t, "point");
        t.property("absolute", CgValueType.BOOL);
        v3(t, "tangent");
        t.property("tangent_method", TANGENT).defaultValue("direct");
        fl(t, "strength");
        t.property("lock", CgValueType.BOOL);
        t.property("lock_tangent", CgValueType.BOOL);
        t.property("lock_strength", CgValueType.BOOL);
        if (target) fl(t, "lock_radius", "speed");
    }

    private static Inputs beamEnd(Inputs in, float x, float y, float z, boolean absolute, float tx, float ty, float tz,
                                  CgVfxModule.BeamSource.Tangent method, float strength, boolean lock, boolean lockTangent,
                                  boolean lockStrength) {
        in.v3("point", x, y, z).b("absolute", absolute).v3("tangent", tx, ty, tz);
        in.map.put("tangent_method", method.name().toLowerCase(Locale.ROOT));
        return in.f("strength", strength).b("lock", lock).b("lock_tangent", lockTangent).b("lock_strength", lockStrength);
    }

    private CgVfxModuleTypes() {
    }

    private static <M extends CgVfxGpuModule> void kind(String id, Class<M> type, Consumer<CgType.Builder> declare,
                                                        BiConsumer<M, Inputs> writer, Function<Read, M> reader) {
        declare(id, type, declare, writer, reader);
    }

    /**
     * Declares a kind of {@code type}: {@code declare} lists its inputs in the order its {@code fx_<kind>} reads them,
     * {@code writer} gives a module's values for them, {@code reader} builds one back from an asset's. {@code id} is its
     * {@code gpuKind()}, which an asset names it by; a class answering several GPU kinds of one layout declares once,
     * under an id of its own.
     */
    public static synchronized <M extends CgVfxGpuModule> Kind<M> declare(String id, Class<M> type,
            Consumer<CgType.Builder> declare, BiConsumer<M, Inputs> writer, Function<Read, M> reader) {
        Kind<?> known = BY_ID.get(id);
        if (known != null) {
            if (known.type() != type) throw new IllegalArgumentException(id + " is declared already, by " + known.type().getName());
            @SuppressWarnings("unchecked") Kind<M> same = (Kind<M>) known;
            return same;
        }
        CgType.Builder b = REGISTRY.declare("crystalgraphics:gpu_module." + id, CgTypeKind.STRUCT);
        declare.accept(b);
        CgType t = b.register();
        CgLayout layout = CgLayout.of(t);
        Kind<M> kind = new Kind<>(id, type, t, layout, CgVfxModuleWords.of(layout), writer,
                reader == null ? null : v -> reader.apply(new Read(v)));
        BY_ID.put(id, kind);
        BY_CLASS.put(type, kind);
        return kind;
    }

    /** {@link #declare(String, Class, Consumer, BiConsumer, Function) declare} for a kind made in code alone. */
    public static <M extends CgVfxGpuModule> Kind<M> declare(String id, Class<M> type, Consumer<CgType.Builder> declare,
                                                             BiConsumer<M, Inputs> writer) {
        return declare(id, type, declare, writer, null);
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
        offset(t, "offset");
    }

    /** A place from the source, written per instance: {@code name} as the format names it. */
    private static void offset(CgType.Builder t, String name) {
        t.property(name, CgValueType.VEC3).defaultValue(Arrays.asList(0.0, 0.0, 0.0)).flags(CgPropertyFlag.PER_INSTANCE);
    }

    /**
     * A collider's Cascade half ({@link CgVfxModule.CollisionResponse}), after its own inputs: four vec4s, the GLSL's
     * {@code FxCollision}. The damping's mode is 0 for none, 1 a roll per axis, 2 one roll; the completion 0 for no count,
     * else its place plus 1; the lead beside the count's range.
     */
    private static void response(CgType.Builder t) {
        t.property("damping_min", CgValueType.VEC3).defaultValue(Arrays.asList(0.0, 0.0, 0.0));
        t.property("damping_mode", CgValueType.FLOAT).defaultValue(0.0);
        t.property("damping_max", CgValueType.VEC3).defaultValue(Arrays.asList(0.0, 0.0, 0.0));
        t.property("delay", CgValueType.FLOAT).defaultValue(0.0);
        t.property("rotation", CgValueType.VEC3).defaultValue(Arrays.asList(1.0, 1.0, 1.0));
        t.property("complete", CgValueType.FLOAT).defaultValue(0.0);
        t.property("most_min", CgValueType.FLOAT).defaultValue(0.0);
        t.property("most_max", CgValueType.FLOAT).defaultValue(0.0);
        t.property("lead", CgValueType.FLOAT).defaultValue(0.0);
    }

    private static Inputs response(Inputs in, CgVfxModule.CollisionResponse r) {
        Vector3fc lo = r.dampingMin(), hi = r.dampingMax();
        if (lo != null && hi != null) {
            in.v3("damping_min", lo.x(), lo.y(), lo.z()).f("damping_mode", r.dampingLocked() ? 2f : 1f)
                    .v3("damping_max", hi.x(), hi.y(), hi.z());
        } else {
            in.v3("damping_min", 0f, 0f, 0f).f("damping_mode", 0f).v3("damping_max", 0f, 0f, 0f);
        }
        Vector3fc turn = r.rotation();
        return in.f("delay", r.delay()).v3("rotation", turn.x(), turn.y(), turn.z())
                .f("complete", r.completion() == null ? 0f : r.completion().ordinal() + 1f)
                .f("most_min", r.mostMin()).f("most_max", r.mostMax()).f("lead", r.lead());
    }

    private static CgVfxModule.CollisionResponse response(Read r) {
        CgVfxModule.CollisionResponse out = CgVfxModule.CollisionResponse.NONE.delay(r.f("delay"))
                .rotation(r.f("rotation", 0), r.f("rotation", 1), r.f("rotation", 2)).lead(r.f("lead"));
        float mode = r.f("damping_mode");
        if (mode > 0f) {
            out = out.damping(r.f("damping_min", 0), r.f("damping_min", 1), r.f("damping_min", 2), r.f("damping_max", 0),
                    r.f("damping_max", 1), r.f("damping_max", 2), mode > 1.5f);
        }
        int complete = Math.round(r.f("complete"));
        if (complete > 0) {
            out = out.limit(r.f("most_min"), r.f("most_max"), CgVfxModule.CollisionCompletion.values()[complete - 1]);
        }
        return out;
    }

    private static void planeRadius(CgType.Builder t) {
        t.property("plane_radius", CgValueType.FLOAT).defaultValue(1.0).flags(CgPropertyFlag.PER_INSTANCE);
    }

    private static float planeRadius(CgShape s) {
        return s instanceof CgPlane plane ? (float) plane.radius() : 1f;
    }

    /** The kind whose {@link CgVfxGpuModule#gpuKind()} is {@code id}, or null. */
    public static synchronized Kind<?> kind(String id) {
        return BY_ID.get(id);
    }

    /** Every kind, in declaration order. */
    public static synchronized List<Kind<?>> all() {
        return new ArrayList<>(BY_ID.values());
    }

    /** {@code module}'s kind: throws, naming it, for a kind never declared. */
    @SuppressWarnings("unchecked")
    public static synchronized <M extends CgVfxGpuModule> Kind<M> of(M module) {
        Kind<M> k = (Kind<M>) BY_CLASS.get(module.getClass());
        if (k == null) {
            throw new IllegalArgumentException("no declaration for the module kind " + module.gpuKind() + " ("
                    + module.getClass().getName() + "): CgVfxModuleTypes.declare it");
        }
        return k;
    }

    /** {@code module}'s inputs, as its asset entry writes them. */
    public static <M extends CgVfxGpuModule> Map<String, Object> inputs(M module) {
        return of(module).inputs(module);
    }

    /** {@code module}'s vec4s of numbers, laid out from its kind's declaration. */
    public static <M extends CgVfxGpuModule> int[] words(M module) {
        return of(module).words(module);
    }

    /** Every module's words end to end in stack order: what an emitter definition's {@code moduleWords()} holds. */
    public static int[] words(List<? extends CgVfxGpuModule> modules) {
        int[][] each = new int[modules.size()][];
        int total = 0;
        for (int i = 0; i < each.length; i++) total += (each[i] = words(modules.get(i))).length;
        int[] out = new int[total];
        for (int i = 0, at = 0; i < each.length; at += each[i].length, i++) System.arraycopy(each[i], 0, out, at, each[i].length);
        return out;
    }

    /** How many vec4s {@code module}'s numbers take. */
    public static int vectors(CgVfxGpuModule module) {
        return of(module).words().vectors();
    }
}
