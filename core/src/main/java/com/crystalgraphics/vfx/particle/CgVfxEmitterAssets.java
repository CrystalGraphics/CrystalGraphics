package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.vfx.particle.renderer.CgVfxRenderers;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRendererProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.curve.CgRichCurve;
import com.crystalgraphics.asset.kind.CgFieldKinds;
import com.crystalgraphics.asset.kind.CgModuleTypes;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.easing.CgEasing;
import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.object.CgProperty;
import com.crystalgraphics.object.CgPropertyFlag;
import com.crystalgraphics.object.value.CgQuantity;
import com.crystalgraphics.vfx.look.CgVfxFlipbook;
import com.crystalgraphics.vfx.particle.gpu.CgVfxDrawnChannel;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.shape.primitive.CgPlane;
import com.crystalgraphics.shape.primitive.CgBox;
import com.crystalgraphics.vfx.particle.form.CgVfxCurveBlock;
import com.crystalgraphics.vfx.particle.form.CgVfxSpawnForm;
import com.crystalgraphics.vfx.particle.form.CgVfxSpawnInput;
import com.crystalgraphics.vfx.particle.form.CgVfxFormCompiler;
import com.crystalgraphics.vfx.particle.form.CgVfxFormInput;
import com.crystalgraphics.vfx.particle.form.CgVfxModuleForm;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;
import com.crystalgraphics.vfx.particle.spawn.CgVfxInactiveResponse;
import com.crystalgraphics.vfx.particle.spawn.CgVfxLoopBehavior;
import com.crystalgraphics.vfx.particle.spawn.CgVfxSpawnBurst;
import com.crystalgraphics.vfx.particle.spawn.CgVfxSpawnRate;

/**
 * An emitter as an {@code emitter} asset's tree, both ways: {@link #write} is what the Java builder writes, and
 * {@link #compile} builds the emitter its stacks declare, as Niagara compiles an emitter's module stacks.
 *
 * <pre>{@code
 * Map<String, Object> tree = CgVfxEmitterAssets.write(CgVfxExplosion.SPECKS);   // properties and the three stacks
 * CgVfxEmitter back = CgVfxEmitterAssets.compile("specks", tree, CgVfxExplosion.SPECKS);
 * CgAssetCompiler.register(CgParticleKinds.EMITTER, CgVfxEmitterAssets.compiler(id -> TEMPLATES.get(id)));
 * }</pre>
 *
 * <p>Every field of the builder is the file's, through {@code modules.yaml}'s modules: {@code shape_location} (the
 * shape, its placement, turn and offset), {@code add_velocity}, {@code initialize_particle}, {@code scale_color},
 * {@code scale_sprite_size} and {@code scale_mesh_size} over age, {@code sprite_rotation_rate},
 * {@code spawn_rate.scale} by loop, {@code grid_rasterize}, and every physics module by its file id.</p>
 *
 * <ul>
 *   <li>The template gives what an emitter file does not carry yet: the renderer, trail and clip, events and
 *       bindings. A {@code null} template leaves them the builder's defaults.</li>
 *   <li>Curves are {@code FRichCurve}'s keys: linear segments linear, quad and cubic easings as cubic keys with their
 *       tangents, any other easing as linear keys sampled along it.</li>
 *   <li>A lifetime, sprite size, speed or radius in any form but a constant or a range is a spawn form
 *       ({@link CgVfxSpawnForm}), written back as it was read: the builder's skewed size and sweep are two
 *       (aaa-design 2.60).</li>
 *   <li>An input {@code modules.yaml} declares that the engine does not take yet reads at its default alone, and fails
 *       by name at any other value.</li>
 * </ul>
 */
public final class CgVfxEmitterAssets {

    private static final String NS = "crystalgraphics:";
    /** Hermite pieces an eased segment no single cubic reproduces is written as, each key at the easing's own slope. */
    private static final int EASE_STEPS = 32;
    private static final float HALF_SQRT2 = (float) Math.sqrt(0.5);
    /** Linear pieces an extrapolated curve is baked into over a life. */
    private static final int EXTRAPOLATED_STEPS = 128;

    private CgVfxEmitterAssets() {
    }

    // ── Write ────────────────────────────────────────────────────────────────────────────

    /** {@code e}'s asset tree: {@code _v}, {@code properties} and its stacks. */
    public static Map<String, Object> write(CgVfxEmitter e) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("_v", 1);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("capacity", e.capacity);
        props.put("local_space", e.local);
        // Unreal's emitter scalability override: the Low tier spawns half.
        if (e.optional) props.put("scalability", List.of(map("tiers", List.of("low"), "scale_spawn_count", true, "spawn_count_scale", 0.5)));
        tree.put("properties", props);

        List<Object> update = new ArrayList<>();
        update.add(entry(e.id("state"), "emitter_state", state(e.state)));
        if (e.rate.most() > 0f) {
            Map<String, Object> rate = map("rate", range(e.rate.perSecond(), e.rate.most()));
            if (!isOne(e.rate.scale())) rate.put("scale", curve(e.rate.scale(), "loop"));
            update.add(entry(e.id("rate"), "spawn_rate", rate));
        }
        for (int i = 0; i < e.bursts.length; i++) {
            CgVfxSpawnBurst b = e.bursts[i];
            update.add(entry(e.burstIds[i], "spawn_burst", map("time", (double) b.time(), "count", range(b.countMin(), b.countMax()),
                    "probability", (double) b.probability())));
        }
        if (e.perUnit.perBlock() > 0f) {
            update.add(entry("per_unit", "spawn_per_unit", map("per_unit", (double) e.perUnit.perBlock(), "max_step", (double) e.maxStep)));
        }
        tree.put("emitter_update", update);

        List<Object> spawn = new ArrayList<>();
        Map<String, Object> init = map("lifetime", written(e, CgVfxSpawnInput.LIFETIME, range(e.lifeMin, e.lifeMax)),
                "heat", (double) e.heat, "sprite_size", written(e, CgVfxSpawnInput.SPRITE_SIZE, spriteSize(e.sizeMin, e.sizeMax)));
        // Unreal starts a sprite unturned; ours turns it at random unless a file says otherwise.
        init.put("sprite_rotation", written(e, CgVfxSpawnInput.SPRITE_ROTATION, range(0f, (float) (2 * Math.PI))));
        if (!same(e.colorFrom, WHITE) || !same(e.colorTo, WHITE)) init.put("color", vector(e.colorFrom, e.colorTo));
        if (!same(e.scaleFrom, UNIT) || !same(e.scaleTo, UNIT)) init.put("mesh_scale", vector(e.scaleFrom, e.scaleTo));
        for (CgVfxSpawnInput stored : new CgVfxSpawnInput[]{CgVfxSpawnInput.COLOR, CgVfxSpawnInput.MESH_SCALE}) {
            for (CgVfxSpawnForm form : e.spawnForms()) if (form.target() == stored) init.put(stored.input(), form.value());
        }
        spawn.add(entry(e.id("init"), "initialize_particle", init));
        Map<String, Object> shape = shape(e);
        if (shape != null) spawn.add(entry(e.id("shape"), "shape_location", shape));
        if (e.meshRotation != null) {
            CgVfxMeshRotation r = e.meshRotation;
            Map<String, Object> turn = map("mode", "none", "rotation", written(e, CgVfxSpawnInput.MESH_ORIENTATION,
                    rotatorValue(r.from(), r.to(), r.rolls())));
            if (r.inheritParent()) turn.put("inherit_parent", true);
            spawn.add(entry(e.id("orientation"), "initial_mesh_orientation", turn));
        }
        if (e.sockets != null) {
            List<Object> points = new ArrayList<>();
            for (int i = 0; i < e.sockets.names().size(); i++) {
                float[] o = e.sockets.offsets()[i];
                points.add(map("name", e.sockets.names().get(i), "offset", list(o[0], o[1], o[2])));
            }
            Map<String, Object> socket = map("points", points, "selection", e.sockets.sequential() ? "sequential" : "random");
            if (e.sockets.follow()) socket.put("update_each_frame", true);
            if (e.sockets.inherit() != 0f) socket.put("inherit_velocity", (double) e.sockets.inherit());
            spawn.add(entry(e.id("socket"), "socket_location", socket));
        }
        spawn.add(entry(e.id("velocity"), "add_velocity", velocity(e)));
        tree.put("particle_spawn", spawn);

        List<Object> modules = new ArrayList<>();
        // First: reading it drops a size over life before it, as Cascade's does.
        if (e.sizeBySpeed[0] != 0f || e.sizeBySpeed[1] != 0f || e.sizeBySpeed[2] != 1f || e.sizeBySpeed[3] != 1f) {
            modules.add(entry("size_by_speed", "scale_size_by_speed", map("speed_scale", list(e.sizeBySpeed[0], e.sizeBySpeed[1]),
                    "max_scale", list(e.sizeBySpeed[2], e.sizeBySpeed[3]))));
        }
        if (!isOne(e.sizeOverLife)) modules.add(entry("size", "scale_sprite_size", map("scale", curve(e.sizeOverLife, "age"))));
        if (!isOne(e.scaleX) || !isOne(e.scaleY) || !isOne(e.scaleZ)) {
            modules.add(entry("scale", "scale_mesh_size", map("scale", curves("age", e.scaleX, e.scaleY, e.scaleZ))));
        }
        for (int i = 0; i < e.lifeRolls.size(); i++) {
            CgVfxLifeRoll roll = e.lifeRolls.get(i);
            String module = roll.target() == CgVfxLifeRoll.Target.MESH_SCALE ? "scale_mesh_size" : "scale_color";
            String input = roll.target() == CgVfxLifeRoll.Target.ALPHA ? "alpha" : "scale";
            Object value;
            if (!roll.curved()) {
                value = roll.form();
            } else if (roll.target() == CgVfxLifeRoll.Target.ALPHA) {
                value = map("fn", NS + "random_range", "inputs", map("min", curve(roll.fromCurves()[0], "age"),
                        "max", curve(roll.toCurves()[0], "age")));
            } else {
                CgKeyframes[] lo = roll.fromCurves(), hi = roll.toCurves();
                value = map("fn", NS + "random_range", "inputs", map("min", curves("age", lo[0], lo[1], lo[2]),
                        "max", curves("age", hi[0], hi[1], hi[2])));
            }
            modules.add(entry(e.id("life_roll_" + i), module, map(input, value)));
        }
        for (int i = 0; i < e.lifeBinds.size(); i++) {
            CgVfxLifeBind u = e.lifeBinds.get(i);
            modules.add(entry(e.id("life_bind_" + i), switch (u.target()) {
                case MESH_SCALE -> "scale_mesh_size";
                case SPRITE_SIZE -> "scale_sprite_size";
                case COLOR, ALPHA -> "scale_color";
            }, map(u.target() == CgVfxLifeBind.Target.ALPHA ? "alpha" : "scale", u.source())));
        }
        Map<String, Object> color = new LinkedHashMap<>();
        if (!isOne(e.red) || !isOne(e.green) || !isOne(e.blue)) color.put("scale", curves("age", e.red, e.green, e.blue));
        if (!isOne(e.opacityOverLife)) color.put("alpha", curve(e.opacityOverLife, "age"));
        if (!isOne(e.intensity)) color.put("intensity", curve(e.intensity, "age"));
        if (!color.isEmpty()) modules.add(entry("color", "scale_color", color));
        if (e.meshRotation != null && !(Arrays.equals(e.meshRotation.rateFrom(), new float[3]) && Arrays.equals(e.meshRotation.rateTo(), new float[3]))) {
            CgVfxMeshRotation r = e.meshRotation;
            modules.add(entry(e.id("rotation_rate"), "mesh_rotation_rate", map("rate", rotatorValue(r.rateFrom(), r.rateTo(), r.rateRolls()))));
        } else if (e.spawnForm(CgVfxSpawnInput.MESH_ROTATION_RATE) != null) {
            modules.add(entry(e.id("rotation_rate"), "mesh_rotation_rate", map("rate", e.spawnForm(CgVfxSpawnInput.MESH_ROTATION_RATE).value())));
        }
        if (e.meshRotation != null) {
            List<CgVfxMeshRotation.Step> steps = e.meshRotation.steps();
            for (int i = 0; i < steps.size(); i++) {
                CgKeyframes[] a = steps.get(i).axes();
                modules.add(entry(e.id("rotation_life_" + i), "mesh_rotation_rate", steps.get(i).scale()
                        ? map("mode", "scale", "rate_scale", curves("age", a[0], a[1], a[2]))
                        : map("mode", "add", "rate", curves("age", a[0], a[1], a[2]))));
            }
        }
        if (e.spinMin != 0f || e.spinMax != 0f) {
            // Ours rolls the sense too: Unreal's rate is signed.
            modules.add(entry(e.id("spin_rate"), "sprite_rotation_rate", map("rate",
                    map("fn", NS + "random_sign", "inputs", map("value", range(e.spinMin, e.spinMax))))));
        }
        if (e.rasterized != null) {
            float[] r = e.rasterized;
            modules.add(entry("rasterize", "grid_rasterize", map("density", list(r[0], r[1], r[2], r[3]))));
        }
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (int i = 0; i < e.modules.size(); i++) {
            CgVfxModule m = e.modules.get(i);
            CgVfxModuleTypes.Kind<CgVfxModule> kind = CgVfxModuleTypes.of(m);
            Mapped f = toFile(e.name, kind.id(), kind.inputs(m));
            for (CgVfxModuleForm form : e.forms) if (form.module() == i) f.inputs.put(f.fileName(form.input()), form.value());
            int n = seen.merge(f.module, 1, Integer::sum);
            String id = e.moduleIds[i] != null ? e.moduleIds[i] : n == 1 ? f.module : f.module + "_" + n;
            modules.add(entry(id, f.module, f.inputs));
        }
        tree.put("particle_update", modules);
        if (!e.renderers.isEmpty()) {
            List<Object> renderers = new ArrayList<>();
            for (CgVfxRendererProperties r : e.renderers) renderers.add(r.tree());
            tree.put("renderers", renderers);
        }
        if (!e.handlers.isEmpty()) {
            List<Object> events = new ArrayList<>();
            for (CgVfxEventHandler h : e.handlers) events.add(h.tree());
            tree.put("events", events);
        }
        return tree;
    }

    private static final float[] WHITE = {1f, 1f, 1f, 1f}, UNIT = {1f, 1f, 1f};

    /** {@code shape_location}'s inputs; null for a point at the source, unturned. */
    @Nullable
    private static Map<String, Object> shape(CgVfxEmitter e) {
        CgVfxSpawnShape.Builder s = e.spawn.given();
        Map<String, Object> in = new LinkedHashMap<>();
        Object radius = written(e, CgVfxSpawnInput.RADIUS, (double) s.b);
        switch (s.kind) {
            case CgVfxSpawnShape.POINT -> in.put("shape", "point");
            case CgVfxSpawnShape.SPHERE -> {
                in.put("shape", "sphere");
                in.put("sphere_radius", radius);
                band(e.name, s, in);
            }
            case CgVfxSpawnShape.BOX -> {
                in.put("shape", "box");
                in.put("box_size", list(2f * s.a, 2f * s.b, 2f * s.c));
                writePlacement(s, in);
            }
            case CgVfxSpawnShape.CYLINDER -> {
                in.put("shape", "cylinder");
                in.put("cylinder_radius", radius);
                in.put("cylinder_height", written(e, CgVfxSpawnInput.CYLINDER_HEIGHT, (double) (2f * s.c)));
                band(e.name, s, in);
            }
            case CgVfxSpawnShape.CONE -> {
                in.put("shape", "cone");
                in.put("cone_length", (double) s.a);
                in.put("cone_angle", (double) s.b);
                writePlacement(s, in);
            }
            case CgVfxSpawnShape.CAPSULE -> {
                in.put("shape", "capsule");
                in.put("capsule_radius", (double) s.b);
                in.put("capsule_height", (double) (2f * s.a));
                writePlacement(s, in);
            }
            case CgVfxSpawnShape.MESH -> {
                if (s.meshPath == null) throw new IllegalArgumentException(e.name + ": a mesh made in code has no file; name it by path");
                in.put("shape", "mesh");
                in.put("mesh", map("mesh", s.meshPath));
                if (s.placement == CgVfxSpawnShape.VERTICES) in.put("vertices", true);
                if (s.a != 1f) in.put("scale", list(s.a, s.a, s.a));
            }
            default -> {
                in.put("shape", "entity");
                writePlacement(s, in);
            }
        }
        if (s.hemisphere) in.put("hemisphere", true);
        if (s.arc < CgVfxSpawnShape.TAU) in.put("arc", (double) s.arc);
        if (s.ordered > 0) in.put("ordered", s.ordered);
        float[] q = e.spawn.turn(), o = e.spawn.place();
        if (q[0] != 0f || q[1] != 0f || q[2] != 0f || q[3] != 1f) in.put("rotation", list(q[0], q[1], q[2], q[3]));
        if (e.spawnForm(CgVfxSpawnInput.OFFSET) != null) in.put("offset", e.spawnForm(CgVfxSpawnInput.OFFSET).value());
        else if (o[0] != 0f || o[1] != 0f || o[2] != 0f) in.put("offset", list(o[0], o[1], o[2]));
        return in.size() == 1 && "point".equals(in.get("shape")) ? null : in;
    }

    /** A sphere's or cylinder's hollow (a shell, a ring) as Unreal's surface band, and its surface or edge. */
    private static void band(String name, CgVfxSpawnShape.Builder s, Map<String, Object> in) {
        if (s.placement == CgVfxSpawnShape.VOLUME && s.a > 0f) {
            in.put("surface_only", true);
            in.put("surface_thickness", (double) (s.b - s.a));
        } else {
            if (s.a > 0f) throw new IllegalArgumentException(name + ": a hollow shape's surface or edge has no file form");
            writePlacement(s, in);
        }
    }

    private static void writePlacement(CgVfxSpawnShape.Builder s, Map<String, Object> in) {
        if (s.placement == CgVfxSpawnShape.SURFACE) in.put("surface_only", true);
        else if (s.placement == CgVfxSpawnShape.EDGE) in.put("edge_only", true);
    }

    /** {@code target}'s spawn form's value as it was read, else {@code otherwise}. */
    private static Object written(CgVfxEmitter e, CgVfxSpawnInput target, Object otherwise) {
        CgVfxSpawnForm f = e.spawnForm(target);
        return f == null ? otherwise : f.value();
    }

    private static Map<String, Object> velocity(CgVfxEmitter e) {
        CgVfxSpawnShape.Builder s = e.spawn.given();
        Map<String, Object> in = new LinkedHashMap<>();
        CgVfxSpawnForm linear = e.spawnForm(CgVfxSpawnInput.VELOCITY);
        if (linear != null) {
            in.put("mode", "linear");
            in.put("linear", linear.value());
            in.put("inherit", (double) e.inherit);
            return in;
        }
        switch (s.mode) {
            case CgVfxSpawnShape.INWARD -> in.put("mode", "inward");
            case CgVfxSpawnShape.DIRECTION -> {
                in.put("mode", "in_cone");
                in.put("cone_axis", list(s.dirX, s.dirY, s.dirZ));
                // Unreal's cone angles are apex angles: twice the half angles drawn between.
                in.put("cone_angle", written(e, CgVfxSpawnInput.CONE_ANGLE, 2.0 * s.spread));
                if (s.innerSpread != 0f) in.put("inner_cone", 2.0 * s.innerSpread);
            }
            default -> in.put("mode", "outward");
        }
        in.put("speed", written(e, CgVfxSpawnInput.SPEED, range(e.speedMin, e.speedMax)));
        in.put("inherit", (double) e.inherit);
        return in;
    }

    // ── Compile ──────────────────────────────────────────────────────────────────────────

    /** The emitter {@code tree} declares, named {@code name}; {@code template} gives what a file does not carry yet. */
    public static CgVfxEmitter compile(String name, Map<?, ?> tree, @Nullable CgVfxEmitter template) {
        return compile(name, tree, template, null);
    }

    /** As {@link #compile(String, Map, CgVfxEmitter)}, {@code deps} answering its renderers' materials. */
    public static CgVfxEmitter compile(String name, Map<?, ?> tree, @Nullable CgVfxEmitter template,
                                       @Nullable CgAssetCompiler.Dependencies deps) {
        tree = toHost(tree, deps == null ? CgUnits.HOST : deps.units());
        CgVfxEmitter.Builder b = CgVfxEmitter.builder(name);
        if (template != null) b.carry(template);
        // Unreal's defaults for what a file leaves out: opaque throughout.
        b.opacity(CgKeyframes.constant(1f));
        Map<?, ?> props = tree.get("properties") instanceof Map<?, ?> p ? p : Map.of();
        if (props.get("capacity") instanceof Number c) b.capacity(c.intValue());
        b.local(Boolean.TRUE.equals(props.get("local_space")));
        if (props.get("scalability") != null) b.optional(halvedAtLow(name, props.get("scalability")));

        int bursts = 0;
        for (Map<?, ?> entry : entries(tree, "emitter_update")) {
            Map<?, ?> in = inputs(entry);
            switch (moduleId(entry)) {
                case "emitter_state":
                    b.state(state(in)).id("state", idOf(entry, "state"));
                    break;
                case "spawn_rate": {
                    b.rate(spawnRate(name, in.get("rate"), in.get("scale"))).id("rate", idOf(entry, "rate"));
                    break;
                }
                case "spawn_burst": {
                    float[] count = rangeOf(in.get("count"));
                    float p = in.get("probability") instanceof Number n ? n.floatValue() : 1f;
                    b.burst(CgVfxSpawnBurst.at(num(in.get("time")), (int) count[0], (int) count[1]).probability(p),
                            idOf(entry, "burst_" + bursts++));
                    break;
                }
                case "spawn_per_unit":
                    b.ratePerBlock(num(in.get("per_unit")));
                    if (in.get("max_step") instanceof Number s) b.maxStep(s.floatValue());
                    break;
                default:
                    throw new IllegalArgumentException(name + ": emitter_update has no " + entry.get("module"));
            }
        }
        for (Map<?, ?> entry : entries(tree, "particle_spawn")) {
            if (Boolean.FALSE.equals(entry.get("enabled"))) continue;
            Map<?, ?> in = inputs(entry);
            switch (moduleId(entry)) {
                case "initialize_particle" -> initialize(name, b, in, spawnId(b, entry, "init"));
                case "add_velocity" -> velocity(name, b, in, spawnId(b, entry, "velocity"));
                case "shape_location" -> shape(name, b, in, spawnId(b, entry, "shape"));
                case "socket_location" -> sockets(name, b, in, spawnId(b, entry, "socket"));
                case "initial_mesh_orientation" -> meshOrientation(name, b, in, spawnId(b, entry, "orientation"));
                default -> throw new IllegalArgumentException(name + ": particle_spawn has no " + entry.get("module"));
            }
        }
        // A file's mesh particles are unturned without an orientation module, as Unreal's are.
        if (b.meshRotation() == null && drawsMeshes(tree)) b.meshRotation(CgVfxMeshRotation.NONE);
        CgVfxFormCompiler forms = new CgVfxFormCompiler(new CgVfxCurveBlock(), id -> null);
        int stacked = 0;
        List<Map<?, ?>> drawn = new ArrayList<>();
        for (Map<?, ?> entry : entries(tree, "particle_update")) {
            if (Boolean.FALSE.equals(entry.get("enabled"))) continue;
            String id = moduleId(entry);
            switch (id) {   // over life by age, and the grid's density: the builder's fields, not modules
                case "scale_sprite_size" -> {
                    Object scale = inputs(entry).get("scale");
                    CgVfxLifeRoll between = curveRoll(CgVfxLifeRoll.Target.MESH_SCALE, name, scale, 2);
                    if (lifeBind(name, entry, "scale", CgVfxLifeBind.Target.SPRITE_SIZE, forms, b)) {
                        continue;
                    } else if (between != null) {
                        b.lifeRoll(between);
                    } else if (scale instanceof Map<?, ?> m && m.get("range") != null) {
                        b.lifeRoll(CgVfxLifeRoll.of(CgVfxLifeRoll.Target.MESH_SCALE, m, 2));
                    } else if (scale instanceof Map<?, ?> m && m.get("curves") != null) {
                        CgKeyframes[] xy = axes(name, scale, 2);
                        b.scaleOverLife(xy[0], xy[1], CgKeyframes.constant(1f));
                    } else {
                        b.size(keyframes(name, uniform(scale), "age"));
                    }
                    continue;
                }
                case "scale_mesh_size" -> {
                    Object scale = inputs(entry).get("scale");
                    if (lifeBind(name, entry, "scale", CgVfxLifeBind.Target.MESH_SCALE, forms, b)) continue;
                    CgVfxLifeRoll between = curveRoll(CgVfxLifeRoll.Target.MESH_SCALE, name, scale, 3);
                    if (between != null) {
                        b.lifeRoll(between);
                        continue;
                    }
                    if (scale instanceof Map<?, ?> m && m.get("range") != null) {
                        b.lifeRoll(CgVfxLifeRoll.of(CgVfxLifeRoll.Target.MESH_SCALE, m, 3));
                        continue;
                    }
                    CgKeyframes[] xyz = axes(name, scale, 3);
                    b.scaleOverLife(xyz[0], xyz[1], xyz[2]);
                    continue;
                }
                case "scale_color" -> {
                    Map<?, ?> in = inputs(entry);
                    if (lifeBind(name, entry, "scale", CgVfxLifeBind.Target.COLOR, forms, b)) {
                        // bound: the same every particle, multiplying the curves
                    } else if (in.get("scale") instanceof Map<?, ?> m && m.get("range") != null) {
                        b.lifeRoll(CgVfxLifeRoll.of(CgVfxLifeRoll.Target.COLOR, m, 3));
                    } else if (curveRoll(CgVfxLifeRoll.Target.COLOR, name, in.get("scale"), 3) instanceof CgVfxLifeRoll between) {
                        b.lifeRoll(between);
                    } else if (in.get("scale") != null) {
                        CgKeyframes[] rgb = axes(name, in.get("scale"), 3);
                        b.colorOverLife(rgb[0], rgb[1], rgb[2]);
                    }
                    if (lifeBind(name, entry, "alpha", CgVfxLifeBind.Target.ALPHA, forms, b)) {
                        // bound, as the scale
                    } else if (curveRoll(CgVfxLifeRoll.Target.ALPHA, name, in.get("alpha"), 1) instanceof CgVfxLifeRoll between) {
                        b.lifeRoll(between);
                    } else if (in.get("alpha") != null) {
                        b.opacity(keyframes(name, in.get("alpha"), "age"));
                    }
                    if (in.get("intensity") != null) b.intensityOverLife(keyframes(name, in.get("intensity"), "age"));
                    continue;
                }
                case "sprite_rotation_rate" -> {
                    b.id("spin_rate", idOf(entry, "spin_rate"));
                    Object rate = inputs(entry).get("rate");
                    if (rate instanceof Map<?, ?> fn && (NS + "random_sign").equals(fn.get("fn")) && fn.get("inputs") instanceof Map<?, ?> fin) {
                        rate = fin.get("value");
                    }
                    float[] spin = rangeOf(rate);
                    b.spin(spin[0], spin[1]);
                    continue;
                }
                case "grid_rasterize" -> {
                    float[] d = vec(inputs(entry).get("density"), 4);
                    b.rasterize(d[0], d[1], d[2], d[3]);
                    continue;
                }
                case "camera_offset" -> {
                    Map<?, ?> in = inputs(entry);
                    if (in.get("offset") instanceof Map<?, ?> form && form.get("bind") == null) {
                        // Per particle: its own channel, which the factories read in place of the renderer's.
                        b.drawn(CgVfxDrawnChannel.SPRITE, CgVfxDrawnChannel.CAMERA_OFFSET,
                                drawnChannel(name, "camera_offset's offset", form, Boolean.TRUE.equals(in.get("spawn_only"))));
                    }
                    drawn.add(entry);
                    continue;
                }
                case "sprite_facing_and_alignment" -> {
                    drawn.add(entry);
                    continue;
                }
                case "sub_uv_animation" -> {
                    subUv(name, b, inputs(entry));
                    continue;
                }
                case "dynamic_material_parameters" -> {
                    dynamicParameters(name, b, inputs(entry), forms, idOf(entry, id));
                    continue;
                }
                case "vector_field" -> {
                    // Without a grid of its own it moves by the scene's global fields, of which a host has none.
                    if (entry.get("inputs") instanceof Map<?, ?> raw && raw.get("field") == null) continue;
                    b.module(vectorField(name, inputs(entry), deps), idOf(entry, "vector_field"));
                    stacked++;
                    continue;
                }
                case "scale_size_by_speed" -> {
                    sizeBySpeed(name, b, inputs(entry), deps == null ? CgUnits.HOST : deps.units());
                    continue;
                }
                default -> {
                }
            }
            Mapped m = toEngine(name, entry);
            CgVfxModuleTypes.Kind<?> kind = CgVfxModuleTypes.kind(m.module);
            if (kind == null) throw new IllegalArgumentException(name + ": no module " + entry.get("module"));
            formed(name, entry, m, kind, stacked, forms, b);
            stacked++;
            if (!(kind.compile(m.inputs) instanceof CgVfxModule module)) {
                throw new IllegalArgumentException(name + ": module " + entry.get("module") + " is a mod's kind; an asset names the engine's");
            }
            b.module(module, idOf(entry, m.module));
        }
        Object renderers = drawnBy(name, tree.get("renderers"), drawn, deps == null ? CgUnits.HOST : deps.units());
        for (CgVfxRendererProperties r : CgVfxRenderers.compileAll(name, renderers, deps)) b.renderer(r);
        // Its handlers name its system's handles: the system resolves them (CgVfxSystemAssets).
        for (Map<?, ?> entry : entries(tree, "events")) b.handler(CgVfxEventHandler.of(name, entry));
        return b.build();
    }

    /** The emitter kind's compiler: each asset over the code-built emitter {@code templates} answers for its id. */
    public static CgAssetCompiler<CgVfxEmitter> compiler(Function<String, CgVfxEmitter> templates) {
        return (asset, deps) -> {
            String id = asset.id();
            return compile(id.substring(id.lastIndexOf('/') + 1), asset.resolved(), templates.apply(id), deps);
        };
    }

    private static boolean halvedAtLow(String name, Object scalability) {
        if (scalability instanceof List<?> list && list.size() == 1 && list.get(0) instanceof Map<?, ?> m
                && List.of("low").equals(m.get("tiers")) && Boolean.TRUE.equals(m.get("scale_spawn_count"))
                && m.get("spawn_count_scale") instanceof Number n && n.doubleValue() == 0.5) {
            return true;
        }
        throw new IllegalArgumentException(name + ": scalability " + scalability + " is not read yet: the Low tier's half alone");
    }

    /**
     * {@code renderers} with what {@code modules} ({@code camera_offset}, {@code sprite_facing_and_alignment}) write for
     * every particle set on them. Niagara's modules write Particles.CameraOffset, SpriteFacing and SpriteAlignment, which
     * its renderers read; a value the same for every particle is the renderer's own here ({@code _cg_CameraOffset},
     * {@code _cg_FacingVector}, {@code _cg_AlignmentVector}).
     */
    private static Object drawnBy(String name, Object renderers, List<Map<?, ?>> modules, CgUnits units) {
        if (modules.isEmpty() || !(renderers instanceof List<?> list)) return renderers;
        List<Object> out = new ArrayList<>(list.size());
        for (Object r : list) {
            if (!(r instanceof Map<?, ?> m)) {
                out.add(r);
                continue;
            }
            Map<String, Object> renderer = keyed(m);
            Map<String, Object> values = renderer.get("values") instanceof Map<?, ?> v ? keyed(v) : new LinkedHashMap<>();
            Map<String, Object> binds = renderer.get("bind") instanceof Map<?, ?> v ? keyed(v) : new LinkedHashMap<>();
            for (Map<?, ?> module : modules) {
                switch (moduleId(module)) {
                    case "camera_offset" -> cameraOffset(name, renderer, inputs(module), values, binds, units);
                    default -> axisLock(name, renderer, inputs(module), values, units);
                }
            }
            if (!values.isEmpty()) renderer.put("values", values);
            if (!binds.isEmpty()) renderer.put("bind", binds);
            out.add(renderer);
        }
        return out;
    }

    private static Map<String, Object> keyed(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        return out;
    }

    /**
     * {@code camera_offset}: Cascade's CameraOffset, a particle drawn {@code offset} blocks toward the eye
     * (ParticleModules_Camera.cpp:71-130; ParticleSystemRender.cpp:150-193). Sprites and meshes move by it (:473,
     * :1434-1445); Cascade's trails and beams never read it, nor do ours.
     */
    private static void cameraOffset(String name, Map<String, Object> renderer, Map<?, ?> in, Map<String, Object> values,
                                     Map<String, Object> binds, CgUnits units) {
        Object kind = renderer.get("kind");
        if (!"sprite".equals(kind) && !"mesh".equals(kind)) return;
        if (in.get("update") != null && !"direct".equals(in.get("update"))) {
            throw new IllegalArgumentException(name + ": camera_offset's update " + in.get("update") + " is not read yet: direct alone");
        }
        Object offset = in.get("offset");
        if (offset == null || offset instanceof Number) {
            values.put("_cg_CameraOffset", offset == null ? 0.0 : ((Number) offset).doubleValue());
        } else if (offset instanceof Map<?, ?> form && form.get("bind") != null) {
            if (!isIdentity(form.get("remap"))) {
                throw new IllegalArgumentException(name + ": camera_offset's bind remaps " + form.get("remap") + ", which is not read yet");
            }
            // A binding converts nothing: the user parameter is in the asset's lengths.
            binds.put("_cg_CameraOffset", Map.of("bind", form.get("bind"), "scale", units.toHost(1.0, CgQuantity.LENGTH)));
        }
        // Any other form is each particle's own: a drawn channel, set on the emitter.
    }

    /**
     * A per-particle value's form as a drawn channel: a number or a curve by age over life; a range rolled each update,
     * as Cascade's distributions roll on every {@code GetValue}, or once at birth where {@code spawnOnly}, which also
     * holds a curve at its value at birth (ParticleModules_Parameter.cpp:138-167).
     */
    private static CgVfxDrawnChannel drawnChannel(String name, String what, Object value, boolean spawnOnly) {
        if (value instanceof Map<?, ?> form && form.get("range") instanceof List<?> ends && ends.size() == 2
                && ends.get(0) instanceof Number lo && ends.get(1) instanceof Number hi) {
            return new CgVfxDrawnChannel(CgKeyframes.constant(lo.floatValue()), CgKeyframes.constant(hi.floatValue()),
                    spawnOnly ? CgVfxDrawnChannel.Roll.SPAWN : CgVfxDrawnChannel.Roll.UPDATE);
        }
        if (value instanceof Number || value instanceof Map<?, ?> form && form.get("curve") != null) {
            CgKeyframes over = keyframes(name, value, "age");
            return CgVfxDrawnChannel.over(spawnOnly ? CgKeyframes.constant(over.at(0f)) : over);
        }
        // random_range of two values over life: Cascade's uniform curve distribution, a value between them rolled on
        // every GetValue, so each update unless spawnOnly.
        if (value instanceof Map<?, ?> form && (NS + "random_range").equals(form.get("fn")) && form.get("inputs") instanceof Map<?, ?> in
                && in.get("min") != null && in.get("max") != null) {
            CgKeyframes lo = keyframes(name, in.get("min"), "age"), hi = keyframes(name, in.get("max"), "age");
            if (spawnOnly) {
                lo = CgKeyframes.constant(lo.at(0f));
                hi = CgKeyframes.constant(hi.at(0f));
            }
            return new CgVfxDrawnChannel(lo, hi, spawnOnly ? CgVfxDrawnChannel.Roll.SPAWN : CgVfxDrawnChannel.Roll.UPDATE);
        }
        throw new IllegalArgumentException(name + ": " + what + " " + value + " is not read yet: a number, a range or a curve by age");
    }

    /**
     * {@code dynamic_material_parameters}: Cascade's ParameterDynamic, each of a vector's four channels a value the
     * particle carries for its material (ParticleModules_Parameter.cpp:138-167), read by {@code cg_dynamic_parameter}.
     */
    private static void dynamicParameters(String name, CgVfxEmitter.Builder b, Map<?, ?> in, CgVfxFormCompiler forms,
                                          String element) {
        if (Boolean.TRUE.equals(in.get("use_emitter_time"))) {
            throw new IllegalArgumentException(name + ": dynamic_material_parameters' use_emitter_time is not read yet");
        }
        for (int slot = 0; slot < 4; slot++) {
            if (!(in.get("dynamic_" + slot) instanceof List<?> channels)) continue;
            for (int c = 0; c < Math.min(4, channels.size()); c++) {
                if (!(channels.get(c) instanceof Map<?, ?> ch)) continue;
                Object source = ch.get("source");
                if (source != null && !"user".equals(source)) {
                    throw new IllegalArgumentException(name + ": a dynamic parameter from " + source + " is not read yet: user values alone");
                }
                String what = "dynamic_" + slot + "[" + c + "]";
                if (ch.get("value") instanceof Map<?, ?> form && form.get("bind") != null) {
                    // Cascade's particle parameter distribution (ParticleModules_Parameter.cpp:138-167): its instance's
                    // user parameter, remapped, read again each step.
                    if (!(forms.compile(element, channelProperty(), form) instanceof CgVfxFormInput.Binding bound)) {
                        throw new IllegalArgumentException(name + ": " + what + " " + form + " is not a binding");
                    }
                    b.drawn(slot, c, CgVfxDrawnChannel.bound(bound));
                    continue;
                }
                b.drawn(slot, c, drawnChannel(name, what, ch.get("value") == null ? 0.0 : ch.get("value"),
                        Boolean.TRUE.equals(ch.get("spawn_only"))));
            }
        }
    }

    /** The input a drawn channel's binding is read as: a float over life with no unit, as SubUV's frame is. */
    private static CgProperty channelProperty() {
        return property(CgModuleTypes.builtIn().get("sub_uv_animation").inputs().properties(), "frame");
    }

    /**
     * {@code sub_uv_animation}: how the particles play their renderers' frames (Niagara's Sub UV Animation). Direct is
     * Cascade's SubUV, each particle's frame its curve over life (ParticleModules.cpp:2209-2230); loop is SubUVMovie at a
     * rate (:2393-2421), each particle starting at a frame of its own from {@code start_frame} 0 (:2373-2376).
     */
    private static void subUv(String name, CgVfxEmitter.Builder b, Map<?, ?> in) {
        if (Boolean.TRUE.equals(in.get("use_emitter_time"))) {
            throw new IllegalArgumentException(name + ": sub_uv_animation's use_emitter_time is not read yet");
        }
        boolean realTime = Boolean.TRUE.equals(in.get("use_real_time"));
        if (realTime && !"direct".equals(in.get("mode"))) {
            throw new IllegalArgumentException(name + ": sub_uv_animation's use_real_time in a " + in.get("mode")
                    + " is not read yet: direct alone");
        }
        Object interpolation = in.get("interpolation");
        if (interpolation != null && !"none".equals(interpolation)) {
            throw new IllegalArgumentException(name + ": sub_uv_animation's interpolation " + interpolation + " is not read yet");
        }
        String mode = String.valueOf(in.get("mode") == null ? "linear" : in.get("mode"));
        Object rateIn = in.get("frame_rate") == null ? 30.0 : in.get("frame_rate");
        if ((mode.equals("loop") || mode.equals("random")) && !(rateIn instanceof Number)) {
            throw new IllegalArgumentException(name + ": sub_uv_animation's frame_rate " + rateIn + " is not read yet: a number");
        }
        float rate = rateIn instanceof Number n ? n.floatValue() : 0f;
        switch (mode) {
            case "linear" -> b.subUv(CgVfxSubUvAnimation.linear());
            case "random" -> b.subUv(CgVfxSubUvAnimation.random(rate));
            case "direct" -> {
                b.subUv(CgVfxSubUvAnimation.direct());
                CgVfxDrawnChannel frame = drawnChannel(name, "sub_uv_animation's frame",
                        in.get("frame") == null ? 0.0 : in.get("frame"), false);
                b.drawn(CgVfxDrawnChannel.SPRITE, CgVfxDrawnChannel.SUB_IMAGE, realTime ? frame.inRealTime() : frame);
            }
            case "loop" -> {
                int startFrame = in.get("start_frame") instanceof Number n ? n.intValue() : 0;
                if (startFrame > 1) {
                    throw new IllegalArgumentException(name + ": sub_uv_animation's start_frame " + startFrame + " is not read yet: 0 or 1");
                }
                CgVfxSubUvAnimation loop = CgVfxSubUvAnimation.loop(rate);
                b.subUv(startFrame == 0 ? loop.randomStart() : loop);
            }
            default -> throw new IllegalArgumentException(name + ": sub_uv_animation's mode " + mode + " is not read yet");
        }
    }

    /**
     * {@code scale_size_by_speed}: Cascade's SizeScaleBySpeed, which sets size to the base size times
     * {@code clamp(speed * speed_scale, 1, max_scale)} per axis every update (ParticleModules_Size.cpp:419-430), so a size
     * over life before it is lost and one after multiplies it. {@code speed_scale} is a scale a unit of the asset's
     * speed, so it converts by the inverse of a length.
     */
    private static void sizeBySpeed(String name, CgVfxEmitter.Builder b, Map<?, ?> in, CgUnits units) {
        if (in.get("curve") != null) throw new IllegalArgumentException(name + ": scale_size_by_speed's curve is not read yet");
        float[] scale = xy(name, in.get("speed_scale"), 0f), most = xy(name, in.get("max_scale"), 1f);
        float perBlock = (float) (1.0 / ((Number) units.toHost(1.0, CgQuantity.LENGTH)).doubleValue());
        b.size(CgKeyframes.constant(1f));
        b.scaleOverLife(CgKeyframes.constant(1f), CgKeyframes.constant(1f), CgKeyframes.constant(1f));
        b.sizeBySpeed(scale[0] * perBlock, scale[1] * perBlock, most[0], most[1]);
    }

    /** Two numbers, as a list or Unreal's {@code {X, Y}}; {@code fallback} each when absent. */
    private static float[] xy(String name, @Nullable Object v, float fallback) {
        if (v == null) return new float[] {fallback, fallback};
        if (v instanceof List<?> l && l.size() == 2) return new float[] {((Number) l.get(0)).floatValue(), ((Number) l.get(1)).floatValue()};
        if (v instanceof Map<?, ?> m && m.get("X") instanceof Number x && m.get("Y") instanceof Number y) {
            return new float[] {x.floatValue(), y.floatValue()};
        }
        throw new IllegalArgumentException(name + ": two numbers, not " + v);
    }

    /** A remap that changes nothing: none, or its {@code in} range the same as its {@code out}. */
    private static boolean isIdentity(@Nullable Object remap) {
        return remap == null || remap instanceof Map<?, ?> m && sameValue(m.get("in"), m.get("out"));
    }

    /**
     * {@code sprite_facing_and_alignment}'s {@code lock_axis}: Cascade's OrientationAxisLock, which runs nothing itself
     * (ParticleModules.cpp:1005-1012); its sprites take its axes (ParticleSystemRender.cpp:106-146, :882-922). A lock on
     * an axis lays the sprite in that axis's plane, its right and up fixed; a rotation lock stands it along the axis,
     * turning to the eye, a quarter turn about its normal unless the axis is Z. A sprite aligned to its velocity ignores
     * the lock (:884). Axes are the asset's, so they go through its units.
     */
    private static void axisLock(String name, Map<String, Object> renderer, Map<?, ?> in, Map<String, Object> values,
                                 CgUnits units) {
        for (String k : List.of("facing", "alignment")) {
            if (in.get(k) != null && !sameValue(in.get(k), List.of(1.0, 0.0, 0.0))) {
                throw new IllegalArgumentException(name + ": sprite_facing_and_alignment's " + k + " is not read yet: lock_axis alone");
            }
        }
        String lock = String.valueOf(in.get("lock_axis") == null ? "none" : in.get("lock_axis"));
        if (lock.equals("none")) return;
        Object kind = renderer.get("kind");
        if ("mesh".equals(kind)) {
            throw new IllegalArgumentException(name + ": a mesh renderer's locked axis (ParticleSystemRender.cpp:1575) is not built yet");
        }
        if (!"sprite".equals(kind) || "velocity".equals(renderer.get("alignment"))) return;
        if (lock.startsWith("rotate_")) {
            double[] axis = axis(lock.substring("rotate_".length()));
            renderer.put("facing", "camera");
            renderer.put("alignment", "custom");
            values.put("_cg_AlignmentVector", vec4(direction(axis, units)));
            if (!lock.equals("rotate_z")) values.put("_cg_RotationBias", Math.PI / 2);
            return;
        }
        // Cascade's right and up; its up runs down the texture, so the sprite's up is its negation.
        double[] right, up;
        switch (lock) {
            case "x" -> { right = axis("-y"); up = axis("z"); }
            case "y" -> { right = axis("x"); up = axis("z"); }
            case "z" -> { right = axis("y"); up = axis("x"); }
            case "-x" -> { right = axis("y"); up = axis("z"); }
            case "-y" -> { right = axis("-x"); up = axis("z"); }
            case "-z" -> { right = axis("-y"); up = axis("x"); }
            default -> throw new IllegalArgumentException(name + ": no lock_axis " + lock);
        }
        double[] r = direction(right, units), u = direction(up, units);
        double[] facing = {r[1] * u[2] - r[2] * u[1], r[2] * u[0] - r[0] * u[2], r[0] * u[1] - r[1] * u[0]};
        renderer.put("facing", "custom");
        renderer.put("alignment", "custom");
        values.put("_cg_FacingVector", vec4(facing));
        values.put("_cg_AlignmentVector", vec4(u));
    }

    /** A unit axis by name: x, y, z, each optionally negated. */
    private static double[] axis(String name) {
        double sign = name.startsWith("-") ? -1 : 1;
        int i = "xyz".indexOf(name.charAt(name.length() - 1));
        double[] a = new double[3];
        a[i] = sign;
        return a;
    }

    private static double[] direction(double[] v, CgUnits units) {
        List<?> host = (List<?>) units.toHost(List.of(v[0], v[1], v[2]), CgQuantity.DIRECTION);
        return new double[] {((Number) host.get(0)).doubleValue(), ((Number) host.get(1)).doubleValue(), ((Number) host.get(2)).doubleValue()};
    }

    private static List<Double> vec4(double[] v) {
        return List.of(v[0], v[1], v[2], 0.0);
    }

    /**
     * {@code color}: Cascade's ColorOverLife, which sets each particle's colour and alpha from their curves at spawn and
     * every update, the start colour overwritten (ParticleModules_Color.cpp:368-423). So the start colour goes white and
     * the curves are its colour over life. {@code clamp_alpha} is the curve editor's range only
     * (ParticleModules_Color.cpp:136-138): an alpha past 1 stays. A gradient's alpha is the alpha unless {@code alpha}
     * is given.
     */
    private static void color(String name, CgVfxEmitter.Builder b, Map<?, ?> in, boolean colorBound, boolean alphaBound) {
        b.colors(WHITE.clone(), WHITE.clone());
        Object c = colorBound ? null : in.get("color");
        if (c instanceof Map<?, ?> m && m.get("range") != null) {
            // ColorOverLife on a uniform: rolled every tick.
            b.lifeRoll(CgVfxLifeRoll.of(CgVfxLifeRoll.Target.COLOR, m, 3));
            c = null;
        }
        CgKeyframes gradientAlpha = null;
        if (c instanceof Map<?, ?> m && m.get("gradient") instanceof Map<?, ?> g) {
            if (m.get("by") != null && !"age".equals(m.get("by"))) {
                throw new IllegalArgumentException(name + ": a gradient by " + m.get("by") + " is not read yet here, only by age");
            }
            CgKeyframes[] rgba = gradient(name, g);
            b.colorOverLife(rgba[0], rgba[1], rgba[2]);
            gradientAlpha = rgba[3];
        } else if (c != null) {
            CgKeyframes[] rgb = axes(name, c, 3);
            b.colorOverLife(rgb[0], rgb[1], rgb[2]);
        }
        if (alphaBound) return;
        if (curveRoll(CgVfxLifeRoll.Target.ALPHA, name, in.get("alpha"), 1) instanceof CgVfxLifeRoll between) b.lifeRoll(between);
        else if (in.get("alpha") != null) b.opacity(keyframes(name, in.get("alpha"), "age"));
        else if (gradientAlpha != null) b.opacity(gradientAlpha);
    }

    /**
     * {@code entry}'s {@code input} where it binds a user parameter: Cascade's particle parameter distribution in a size
     * or colour module, given to {@code b} on {@code target} ({@link CgVfxLifeBind}). False where it binds nothing.
     */
    private static boolean lifeBind(String name, Map<?, ?> entry, String input, CgVfxLifeBind.Target target,
                                    CgVfxFormCompiler forms, CgVfxEmitter.Builder b) {
        if (!(inputs(entry).get(input) instanceof Map<?, ?> m) || m.get("bind") == null) return false;
        CgModuleTypes.Module declared = CgModuleTypes.builtIn().get(moduleId(entry));
        CgProperty property = property(declared != null ? declared.inputs().properties() : null, input);
        if (!(forms.compile(idOf(entry, moduleId(entry)), property, m) instanceof CgVfxFormInput.Binding bound)) {
            throw new IllegalArgumentException(name + ": " + entry.get("module") + "'s " + input + " " + m + " is not a binding");
        }
        if (target == CgVfxLifeBind.Target.SPRITE_SIZE && bound.components() > 1 && !uniformBind(bound)) {
            throw new IllegalArgumentException(name + ": scale_sprite_size's " + m + " binds x and y apart, which is not read yet");
        }
        b.lifeBind(CgVfxLifeBind.of(target, bound, m));
        return true;
    }

    /**
     * {@code random_range} between two curves by age, as Cascade's {@code UniformCurve} in a size or colour module:
     * rolled each tick between the two at the particle's life ({@link CgVfxLifeRoll#between}); null for another form.
     */
    @Nullable
    private static CgVfxLifeRoll curveRoll(CgVfxLifeRoll.Target target, String name, @Nullable Object value, int n) {
        if (!(value instanceof Map<?, ?> m) || !(NS + "random_range").equals(m.get("fn")) || !(m.get("inputs") instanceof Map<?, ?> in)) {
            return null;
        }
        return CgVfxLifeRoll.between(target, threeChannels(axes(name, in.get("min"), n)), threeChannels(axes(name, in.get("max"), n)));
    }

    /** {@code c}'s channels, the missing ones 1. */
    private static CgKeyframes[] threeChannels(CgKeyframes[] c) {
        CgKeyframes[] out = new CgKeyframes[3];
        for (int i = 0; i < 3; i++) out[i] = i < c.length ? c[i] : CgKeyframes.constant(1f);
        return out;
    }

    /** Whether every channel of {@code bound} is the same: its default, and its remap where it has one. */
    private static boolean uniformBind(CgVfxFormInput.Binding bound) {
        for (int c = 1; c < bound.components(); c++) {
            if (bound.fallback()[c] != bound.fallback()[0]) return false;
            if (bound.inLo() != null && (bound.inLo()[c] != bound.inLo()[0] || bound.inHi()[c] != bound.inHi()[0]
                    || bound.outLo()[c] != bound.outLo()[0] || bound.outHi()[c] != bound.outHi()[0])) {
                return false;
            }
        }
        return true;
    }

    /** A gradient's keys, {@code [time, [r, g, b, a]]}, as four keyframes linear between keys; a missing alpha 1. */
    private static CgKeyframes[] gradient(String name, Map<?, ?> g) {
        if (!(g.get("keys") instanceof List<?> keys) || keys.isEmpty()) {
            throw new IllegalArgumentException(name + ": a gradient of keys [time, colour], not " + g);
        }
        CgKeyframes.Builder[] out = new CgKeyframes.Builder[4];
        for (Object k : keys) {
            if (!(k instanceof List<?> key) || key.size() < 2 || !(key.get(1) instanceof List<?> v) || v.size() < 3) {
                throw new IllegalArgumentException(name + ": a gradient key [time, [r, g, b, a]], not " + k);
            }
            float t = num(key.get(0));
            for (int ch = 0; ch < 4; ch++) {
                float value = ch < v.size() ? num(v.get(ch)) : 1f;
                out[ch] = out[ch] == null ? CgKeyframes.start(t, value) : out[ch].to(t, value, CgEasings.LINEAR);
            }
        }
        CgKeyframes[] built = new CgKeyframes[4];
        for (int ch = 0; ch < 4; ch++) built[ch] = out[ch].build();
        return built;
    }

    private static void initialize(String name, CgVfxEmitter.Builder b, Map<?, ?> in, String element) {
        CgVfxSpawnForm life = spawnForm(CgVfxSpawnInput.LIFETIME, element, "lifetime", in.get("lifetime"));
        float[] lives = life != null ? life.bounds() : rangeOf(in.get("lifetime"));
        b.life(lives[0], lives[1]);
        if (life != null) b.spawnForm(life);
        if (in.get("heat") instanceof Number h) b.heat(h.floatValue());
        if (in.get("sprite_rotation") != null) b.spawnForm(valueForm(CgVfxSpawnInput.SPRITE_ROTATION, element, in.get("sprite_rotation")));
        Object size = in.get("sprite_size");
        CgVfxSpawnForm sized = spawnForm(CgVfxSpawnInput.SPRITE_SIZE, element, "sprite_size", size);
        if (sized != null) {
            float[] width = sized.bounds();
            b.size(width[0], width[1]);
            b.spawnForm(sized);
        } else if (size != null) {
            float[] width = spriteWidth(size);
            b.size(width[0], width[1]);
        }
        float alpha = in.get("alpha") instanceof Number a ? a.floatValue() : 1f;
        CgVfxSpawnForm colored = spawnForm(CgVfxSpawnInput.COLOR, element, "color", in.get("color"));
        if (colored != null) {
            // Stored at birth; the row's starts keep the alpha an rgb form leaves to them.
            b.spawnForm(colored);
            b.colors(new float[]{1f, 1f, 1f, alpha}, new float[]{1f, 1f, 1f, alpha});
        } else if (in.get("color") != null) {
            float[][] c = colourEnds(in.get("color"), alpha);
            b.colors(c[0], c[1]);
        }
        CgVfxSpawnForm scaled = spawnForm(CgVfxSpawnInput.MESH_SCALE, element, "mesh_scale", in.get("mesh_scale"));
        if (scaled != null) {
            // Stored at birth; the row's starts its widest, which a cull's radius takes.
            float[] lo = new float[3], hi = new float[3];
            for (int k = 0; k < 3; k++) {
                float[] s = scaled.bounds(k);
                lo[k] = s[0];
                hi[k] = s[1];
            }
            b.spawnForm(scaled);
            b.scale(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
        } else if (in.get("mesh_scale") != null) {
            float[][] s = ends(in.get("mesh_scale"), 3);
            b.scale(s[0][0], s[0][1], s[0][2], s[1][0], s[1][1], s[1][2]);
        }
    }

    private static void velocity(String name, CgVfxEmitter.Builder b, Map<?, ?> in, String element) {
        CgVfxSpawnShape.Builder s = b.spawn();
        String mode = String.valueOf(in.get("mode"));
        switch (mode) {
            case "outward" -> s.mode = CgVfxSpawnShape.OUTWARD;
            case "inward" -> s.mode = CgVfxSpawnShape.INWARD;
            case "in_cone" -> {
                float[] axis = vec(in.get("cone_axis"), 3);
                s.mode = CgVfxSpawnShape.DIRECTION;
                s.dirX = axis[0];
                s.dirY = axis[1];
                s.dirZ = axis[2];
                Object angle = in.get("cone_angle");
                if (angle instanceof Number a) {
                    s.spread = a.floatValue() / 2f;
                } else {
                    CgVfxSpawnForm cone = valueForm(CgVfxSpawnInput.CONE_ANGLE, element, angle);
                    s.spread = cone.bounds()[1] / 2f;
                    b.spawnForm(cone);
                }
                s.innerSpread = in.get("inner_cone") instanceof Number n ? n.floatValue() / 2f : 0f;
            }
            case "linear" -> {
                if (!isVector(in.get("linear"), 3)) {
                    // Any other form adds to a launch of no speed of its own (Niagara's AddVelocity, Linear).
                    b.spawnForm(valueForm(CgVfxSpawnInput.VELOCITY, element, in.get("linear")));
                    b.speed(0f, 0f);
                    if (in.get("inherit") instanceof Number i) b.inherit(i.floatValue());
                    return;
                }
                float[] v = vec(in.get("linear"), 3);
                float speed = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
                if (speed > 0f) b.direction(v[0], v[1], v[2], 0f);
                b.speed(speed, speed);
                if (in.get("inherit") instanceof Number i) b.inherit(i.floatValue());
                return;
            }
            default -> throw new IllegalArgumentException(name + ": add_velocity's mode " + mode + " is not read yet");
        }
        CgVfxSpawnForm formed = spawnForm(CgVfxSpawnInput.SPEED, element, "speed", in.get("speed"));
        float[] speed = formed != null ? formed.bounds() : rangeOf(in.get("speed"));
        b.speed(speed[0], speed[1]);
        if (formed != null) b.spawnForm(formed);
        if (in.get("inherit") instanceof Number i) b.inherit(i.floatValue());
    }

    /**
     * {@code vector_field}: Cascade's {@code VectorFieldLocal}, its grid a field asset ({@link CgVfxGridField}) placed at
     * its translation and scaled by its intensity and the grid's, its velocity drawn toward the field's by tightness.
     * {@code global} adds the scene's global fields, of which the host has none; {@code use_fix_dt} is the GPU's step,
     * which ours is.
     */
    private static CgVfxModule vectorField(String name, Map<?, ?> in, @Nullable CgAssetCompiler.Dependencies deps) {
        if (!(in.get("field") instanceof Map<?, ?> ref) || !(ref.get("field") instanceof String id)) {
            throw new IllegalArgumentException(name + ": vector_field's field is { field: <id> }, not " + in.get("field"));
        }
        if (!(deps != null && deps.definition(CgFieldKinds.FIELD, id) instanceof CgVfxGridField grid)) {
            throw new IllegalArgumentException(name + ": vector_field's field " + id + " is no grid that loaded");
        }
        float[] place = vectorFieldPlacement(name, in.get("rotation"), deps == null ? CgUnits.HOST : deps.units());
        Rotators start = rotators(name, "vector_field's initial_rotation", in.get("initial_rotation") != null ? in.get("initial_rotation") : List.of(0, 0, 0));
        if (start.rolls()[1] != 0 || start.rolls()[2] != 0) {
            throw new IllegalArgumentException(name + ": vector_field's initial_rotation rolls once for every axis, as Cascade's: no per_channel");
        }
        float[] rate = in.get("rotation_rate") != null ? vec(in.get("rotation_rate"), 3) : new float[3];
        CgVfxFieldTurn turn = new CgVfxFieldTurn(grid.centre().clone(), place, start.from(), start.to(), rate);
        String tile = in.get("tile") == null ? "" : String.valueOf(in.get("tile"));
        if (!tile.matches("[xyz]*")) throw new IllegalArgumentException(name + ": vector_field's tile " + tile + ": axis letters");
        // Cascade's bTileX/Y/Z, its axes the asset's, named in the host's.
        String hostTile = (deps == null ? CgUnits.HOST : deps.units()).hostAxes(tile);
        int tileAxes = (hostTile.contains("x") ? 1 : 0) | (hostTile.contains("y") ? 2 : 0) | (hostTile.contains("z") ? 4 : 0);
        if (in.get("volume") != null) throw new IllegalArgumentException(name + ": vector_field's volume is not read yet: its grid's");
        if (in.get("scale") != null && !(in.get("scale") instanceof Number)) {
            throw new IllegalArgumentException(name + ": vector_field's scale " + in.get("scale") + " is not read yet: a number");
        }
        float scale = in.get("scale") instanceof Number s ? s.floatValue() : 1f;
        float intensity = in.get("intensity") instanceof Number n ? n.floatValue() : 1f;
        float tightness = in.get("tightness") instanceof Number t ? t.floatValue() : 0f;
        float[] at = in.get("translation") != null ? vec(in.get("translation"), 3) : new float[3];
        return new CgVfxModule.VectorField(grid.field().tiledOn(tileAxes), CgBox.of(grid.half()[0], grid.half()[1], grid.half()[2]),
                at[0], at[1], at[2], intensity * grid.intensity() * scale, 1f, tightness, turn);
    }

    /**
     * A vector field's placement rotation as a host quaternion: a rotator (roll, pitch, yaw), or Unreal's {@code FRotator}
     * written by name, {@code { pitch, yaw, roll }}, in the asset's angle units.
     */
    private static float[] vectorFieldPlacement(String name, @Nullable Object rotation, CgUnits units) {
        if (rotation == null) return new float[]{0f, 0f, 0f, 1f};
        if (rotation instanceof Map<?, ?> m && m.keySet().containsAll(List.of("pitch", "yaw", "roll"))) {
            return CgUnits.rotatorToHost(num(m.get("roll")) * units.radians(), num(m.get("pitch")) * units.radians(),
                    num(m.get("yaw")) * units.radians());
        }
        if (!isVector(rotation, 3)) throw new IllegalArgumentException(name + ": vector_field's rotation " + rotation + " is not a rotator");
        float[] r = vec(rotation, 3);
        return CgUnits.rotatorToHost(r[0], r[1], r[2]);
    }

    /** Whether {@code tree} draws a mesh a particle. */
    private static boolean drawsMeshes(Map<?, ?> tree) {
        if (!(tree.get("renderers") instanceof List<?> renderers)) return false;
        for (Object r : renderers) if (r instanceof Map<?, ?> m && "mesh".equals(m.get("kind"))) return true;
        return false;
    }

    /** {@code value} for a spawn input with no spawn-row word: a form whatever its form, a constant too. */
    private static CgVfxSpawnForm valueForm(CgVfxSpawnInput target, String element, Object value) {
        return CgVfxSpawnForm.of(target, element, target.input(), value);
    }

    /** The quaternion {@code a b}: {@code b}'s turn, then {@code a}'s. */
    private static float[] multiply(float[] a, float[] b) {
        return new float[]{
                a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
                a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
                a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
                a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]};
    }

    /** Whether {@code value} is {@code n} numbers as written: a constant vector. */
    private static boolean isVector(Object value, int n) {
        return value instanceof List<?> l && l.size() == n && l.stream().allMatch(x -> x instanceof Number);
    }

    /** {@code value} as a spawn form: null for a constant or a range, which the spawn row's two ends give. */
    @Nullable
    private static CgVfxSpawnForm spawnForm(CgVfxSpawnInput target, String element, String input, Object value) {
        if (!isForm(value) || ((Map<?, ?>) value).containsKey("range")) return null;
        return CgVfxSpawnForm.of(target, element, input, value);
    }

    /** An entry's element id, or {@code otherwise} where it has none. */
    private static String idOf(Map<?, ?> entry, String otherwise) {
        return entry.get("id") instanceof String id ? id : otherwise;
    }

    /** A spawn entry's element id, given to {@code b} under the writer's name {@code element}. */
    private static String spawnId(CgVfxEmitter.Builder b, Map<?, ?> entry, String element) {
        String id = idOf(entry, element);
        b.id(element, id);
        return id;
    }

    private static void shape(String name, CgVfxEmitter.Builder b, Map<?, ?> in, String element) {
        CgVfxSpawnShape.Builder s = b.spawn();
        float[] axisTurn = null;
        String shape = String.valueOf(in.get("shape"));
        s.a = s.b = s.c = 0f;
        s.placement = CgVfxSpawnShape.VOLUME;
        switch (shape) {
            case "point" -> s.kind = CgVfxSpawnShape.POINT;
            case "sphere" -> {
                s.kind = CgVfxSpawnShape.SPHERE;
                s.b = radius(b, element, "sphere_radius", in.get("sphere_radius"));
                band(s, in);
            }
            case "box" -> {
                float[] size = vec(in.get("box_size"), 3);
                s.kind = CgVfxSpawnShape.BOX;
                s.a = size[0] / 2f;
                s.b = size[1] / 2f;
                s.c = size[2] / 2f;
                placement(s, in);
            }
            case "cylinder" -> {
                // Unreal's cylinder stands on its z, the host's y and ours; along its x or y it is ours turned there.
                Object axis = in.get("cylinder_axis");
                if ("x".equals(axis)) axisTurn = new float[]{0f, 0f, -HALF_SQRT2, HALF_SQRT2};
                else if ("y".equals(axis)) axisTurn = new float[]{HALF_SQRT2, 0f, 0f, HALF_SQRT2};
                else if (axis != null && !"z".equals(axis)) throw new IllegalArgumentException(name + ": no cylinder axis " + axis);
                s.kind = CgVfxSpawnShape.CYLINDER;
                s.b = radius(b, element, "cylinder_radius", in.get("cylinder_radius"));
                CgVfxSpawnForm height = spawnForm(CgVfxSpawnInput.CYLINDER_HEIGHT, element, "cylinder_height", in.get("cylinder_height"));
                if (height == null) {
                    s.c = rangeOf(in.get("cylinder_height"))[1] / 2f;
                    if (in.get("cylinder_height") instanceof Map<?, ?>) {
                        b.spawnForm(valueForm(CgVfxSpawnInput.CYLINDER_HEIGHT, element, in.get("cylinder_height")));
                    }
                } else {
                    // Drawn per particle; the shape's own half height its tallest, which its reach takes.
                    s.c = Math.max(Math.abs(height.bounds()[0]), Math.abs(height.bounds()[1])) / 2f;
                    b.spawnForm(height);
                }
                band(s, in);
            }
            case "ring" -> {
                // Unreal's ring at its default coverage: its edge.
                s.kind = CgVfxSpawnShape.CYLINDER;
                s.b = radius(b, element, "ring_radius", in.get("ring_radius"));
                s.placement = CgVfxSpawnShape.EDGE;
            }
            case "cone" -> {
                s.kind = CgVfxSpawnShape.CONE;
                s.a = num(in.get("cone_length"));
                s.b = num(in.get("cone_angle"));
                placement(s, in);
            }
            case "capsule" -> {
                s.kind = CgVfxSpawnShape.CAPSULE;
                s.b = num(in.get("capsule_radius"));
                s.a = num(in.get("capsule_height")) / 2f;
                placement(s, in);
            }
            case "mesh" -> {
                if (!(in.get("mesh") instanceof Map<?, ?> m) || !(m.get("mesh") instanceof String path)) {
                    throw new IllegalArgumentException(name + ": shape_location's mesh is { mesh: <path> }, not " + in.get("mesh"));
                }
                b.mesh(path);
                if (Boolean.TRUE.equals(in.get("vertices"))) s.placement = CgVfxSpawnShape.VERTICES;
                if (in.get("scale") != null) s.a = vec(in.get("scale"), 3)[0];
            }
            case "entity" -> {
                b.entity();
                placement(s, in);
            }
            default -> throw new IllegalArgumentException(name + ": shape_location's shape " + shape + " is not read yet");
        }
        s.hemisphere = Boolean.TRUE.equals(in.get("hemisphere"));
        s.arc = in.get("arc") instanceof Number a ? a.floatValue() : CgVfxSpawnShape.TAU;
        s.ordered = in.get("ordered") instanceof Number n ? n.intValue() : 0;
        Object r = in.get("rotation");
        float[] turn;
        if (r == null) {
            turn = new float[]{0f, 0f, 0f, 1f};
        } else if (isVector(r, 3)) {
            float[] rotator = vec(r, 3);
            turn = CgUnits.rotatorToHost(rotator[0], rotator[1], rotator[2]);
        } else if (isVector(r, 4)) {
            turn = vec(r, 4);
        } else {
            throw new IllegalArgumentException(name + ": shape_location's rotation " + r + " is not read yet: a rotator or a quaternion");
        }
        s.turn = axisTurn == null ? turn : multiply(turn, axisTurn);
        Object offset = in.get("offset");
        if (offset == null || isVector(offset, 3)) {
            s.place = offset != null ? vec(offset, 3) : new float[3];
        } else {
            s.place = new float[3];
            b.spawnForm(valueForm(CgVfxSpawnInput.OFFSET, element, offset));
        }
    }

    /**
     * {@code socket_location}: Cascade's {@code LocationBoneSocket} ({@link CgVfxSockets}). Sockets and bones are both
     * points by name; its mesh is the entity the effect follows, whatever it binds. Its rotation is the identity, as
     * Cascade's is ({@code ParticleModules_Location.cpp:1577-1580}), so {@code orient_meshes} changes nothing.
     */
    private static void sockets(String name, CgVfxEmitter.Builder b, Map<?, ?> in, String element) {
        if (Boolean.TRUE.equals(in.get("segments"))) throw new IllegalArgumentException(name + ": socket_location's segments is not read yet");
        List<?> points = in.get("points") instanceof List<?> l ? l : List.of();
        float[] universal = in.get("universal_offset") != null ? vec(in.get("universal_offset"), 3) : new float[3];
        List<String> names = new ArrayList<>(points.size());
        float[][] offsets = new float[points.size()][];
        for (int i = 0; i < points.size(); i++) {
            if (!(points.get(i) instanceof Map<?, ?> p) || !(p.get("name") instanceof String point)) {
                throw new IllegalArgumentException(name + ": socket_location's point " + points.get(i) + " has no name");
            }
            names.add(point);
            float[] o = p.get("offset") instanceof List<?> offset && !offset.isEmpty() ? vec(offset, 3) : new float[3];
            offsets[i] = new float[]{o[0] + universal[0], o[1] + universal[1], o[2] + universal[2]};
        }
        if (names.isEmpty()) throw new IllegalArgumentException(name + ": socket_location names no points");
        float inherit = in.get("inherit_velocity") instanceof Number v ? v.floatValue() : 0f;
        b.sockets(new CgVfxSockets(element, names, offsets, !"random".equals(in.get("selection")),
                Boolean.TRUE.equals(in.get("update_each_frame")), inherit));
    }

    /** A rotator's two ends, and whether each axis rolls its own. */
    private record Rotators(float[] from, float[] to, int[] rolls) {
    }

    /** {@code value}, a rotator, as its two ends: a constant, or a range rolled once or per channel. */
    private static Rotators rotators(String name, String what, Object value) {
        if (isVector(value, 3)) {
            float[] v = vec(value, 3);
            return new Rotators(v, v, new int[3]);
        }
        if (value instanceof Map<?, ?> m && m.get("range") instanceof List<?> ends && ends.size() == 2
                && m.get("by") == null) {
            return new Rotators(vec(ends.get(0), 3), vec(ends.get(1), 3), CgVfxLifeRoll.rolls(m));
        }
        throw new IllegalArgumentException(name + ": " + what + " " + value + " is not read yet: a constant or a range");
    }

    /**
     * {@code initial_mesh_orientation}: Cascade's {@code MeshRotation}, Niagara's {@code InitialMeshOrientation}, its
     * rotator rolled from the particle's seed ({@link CgVfxMeshRotation}).
     */
    private static void meshOrientation(String name, CgVfxEmitter.Builder b, Map<?, ?> in, String element) {
        if (!"none".equals(String.valueOf(in.get("mode")))) {
            throw new IllegalArgumentException(name + ": initial_mesh_orientation's mode " + in.get("mode") + " is not read yet: none alone");
        }
        CgVfxMeshRotation was = b.meshRotation() != null ? b.meshRotation() : CgVfxMeshRotation.NONE;
        Object rotation = in.get("rotation");
        Rotators r;
        CgVfxSpawnForm stored = spawnForm(CgVfxSpawnInput.MESH_ORIENTATION, element, "rotation", rotation);
        if (stored != null) {
            // Stored at birth: the row's ends unread.
            b.spawnForm(stored);
            r = new Rotators(new float[3], new float[3], new int[3]);
        } else {
            r = rotators(name, "initial_mesh_orientation's rotation", rotation);
        }
        b.meshRotation(new CgVfxMeshRotation(r.from(), r.to(), r.rolls(), was.rateFrom(), was.rateTo(), was.rateRolls(),
                Boolean.TRUE.equals(in.get("inherit_parent")), was.steps()));
    }

    /**
     * {@code mesh_rotation_rate}: Cascade's {@code MeshRotationRate} (a rate rolled from the seed), {@code OverLife} (a
     * rate by age, added) and {@code MultiplyLife} (mode {@code scale}: the rate times {@code rate_scale} by age), in
     * the stack's order ({@link CgVfxMeshRotation#steps()}). A constant before any scale joins the rolled rate.
     */
    private static void meshRotationRate(String name, CgVfxEmitter.Builder b, Map<?, ?> in, String element) {
        CgVfxMeshRotation was = b.meshRotation() != null ? b.meshRotation() : CgVfxMeshRotation.NONE;
        if ("scale".equals(in.get("mode"))) {
            if (in.get("rate") != null && !isZero(in.get("rate"))) {
                throw new IllegalArgumentException(name + ": mesh_rotation_rate's mode scale takes rate_scale, not a rate");
            }
            b.meshRotation(was.over(new CgVfxMeshRotation.Step(true, byAge(name, "rate_scale", in.get("rate_scale")))));
            return;
        }
        Object scale = in.get("rate_scale");
        if (scale != null && !(isVector(scale, 3) && Arrays.equals(vec(scale, 3), new float[]{1f, 1f, 1f}))) {
            throw new IllegalArgumentException(name + ": mesh_rotation_rate's rate_scale " + scale + " is read in mode scale alone");
        }
        Object rate = in.get("rate");
        if (rate instanceof Map<?, ?> m && m.get("curves") != null && !"loop".equals(m.get("by"))) {
            b.meshRotation(was.over(new CgVfxMeshRotation.Step(false, byAge(name, "rate", rate))));
            return;
        }
        if (rate instanceof Map<?, ?> m && "tick".equals(m.get("rolls"))) {
            // OverLife on a uniform rolls each tick (ParticleModules.cpp:1732): integrated, its rate is its mean.
            Map<Object, Object> ends = new LinkedHashMap<>(m);
            ends.remove("rolls");
            Rotators r = rotators(name, "mesh_rotation_rate's rate", ends);
            float[] mean = new float[3];
            for (int c = 0; c < 3; c++) mean[c] = 0.5f * (r.from()[c] + r.to()[c]);
            b.meshRotation(was.over(new CgVfxMeshRotation.Step(false, axes(name, list(mean[0], mean[1], mean[2]), 3))));
            return;
        }
        if (!(rate instanceof Map<?, ?> rm && rm.get("range") != null) && spawnForm(CgVfxSpawnInput.MESH_ROTATION_RATE, element, "rate", rate) != null) {
            // A pick or a curve by the loop, drawn at birth as Cascade's MeshRotationRate is: the particle's rolled rate.
            if (was.scaled() || !Arrays.equals(was.rateFrom(), was.rateTo()) || !Arrays.equals(was.rateFrom(), new float[3])) {
                throw new IllegalArgumentException(name + ": mesh_rotation_rate's stored rate is read first and alone, before any other");
            }
            b.spawnForm(spawnForm(CgVfxSpawnInput.MESH_ROTATION_RATE, element, "rate", rate));
            return;
        }
        Rotators r = rotators(name, "mesh_rotation_rate's rate", rate);
        boolean constant = Arrays.equals(r.from(), r.to());
        if (constant && was.scaled()) {
            b.meshRotation(was.over(new CgVfxMeshRotation.Step(false, axes(name, list(r.from()[0], r.from()[1], r.from()[2]), 3))));
            return;
        }
        if (!constant && was.scaled()) {
            throw new IllegalArgumentException(name + ": mesh_rotation_rate's range after a scale is not read yet: before it");
        }
        boolean rolled = !Arrays.equals(was.rateFrom(), was.rateTo());
        if (!constant && rolled) {
            throw new IllegalArgumentException(name + ": a second mesh_rotation_rate range is not read yet: one rolled rate");
        }
        float[] from = new float[3], to = new float[3];
        for (int c = 0; c < 3; c++) {
            from[c] = was.rateFrom()[c] + r.from()[c];
            to[c] = was.rateTo()[c] + r.to()[c];
        }
        b.meshRotation(was.rate(from, to, rolled ? was.rateRolls() : r.rolls()));
    }

    /** Three curves by age, or a constant: a rotation rate's step over life. */
    private static CgKeyframes[] byAge(String name, String input, Object value) {
        if (value instanceof Map<?, ?> m && m.get("by") != null && !"age".equals(m.get("by"))) {
            throw new IllegalArgumentException(name + ": mesh_rotation_rate's " + input + " by " + m.get("by") + " is not read yet: by age");
        }
        if (value instanceof Map<?, ?> m && m.get("curves") == null) {
            throw new IllegalArgumentException(name + ": mesh_rotation_rate's " + input + " " + value + " is not read yet: curves or a constant");
        }
        return axes(name, value, 3);
    }

    private static boolean isZero(Object value) {
        return isVector(value, 3) && Arrays.equals(vec(value, 3), new float[3]);
    }

    /** A rotator's two ends as a file writes them: one value, or a range. */
    private static Object rotatorValue(float[] from, float[] to, int[] rolls) {
        if (Arrays.equals(from, to)) return list(from[0], from[1], from[2]);
        Map<String, Object> m = map("range", List.of(list(from[0], from[1], from[2]), list(to[0], to[1], to[2])));
        CgVfxLifeRoll.putRolls(m, rolls);
        return m;
    }

    /** A radius, or one in a form (a sweep's is a curve by the loop's seconds, aaa-design 2.60): its first end. */
    private static float radius(CgVfxEmitter.Builder b, String element, String input, Object value) {
        // The row holds one radius, not two ends: a range is a form too.
        CgVfxSpawnForm formed = value instanceof Map<?, ?> ? CgVfxSpawnForm.of(CgVfxSpawnInput.RADIUS, element, input, value) : null;
        if (formed == null) return num(value);
        b.spawnForm(formed);
        return formed.firstEnd();
    }

    private static void band(CgVfxSpawnShape.Builder s, Map<?, ?> in) {
        if (Boolean.TRUE.equals(in.get("surface_only")) && in.get("surface_thickness") instanceof Number t && t.floatValue() > 0f) {
            s.a = s.b - t.floatValue();
            return;
        }
        placement(s, in);
    }

    private static void placement(CgVfxSpawnShape.Builder s, Map<?, ?> in) {
        if (Boolean.TRUE.equals(in.get("surface_only"))) s.placement = CgVfxSpawnShape.SURFACE;
        else if (Boolean.TRUE.equals(in.get("edge_only"))) s.placement = CgVfxSpawnShape.EDGE;
    }

    // ── Physics modules by their file ids ────────────────────────────────────────────────

    /**
     * A module by one side's id and inputs; {@code consumed}, the file inputs it read; {@code names}, each input renamed
     * one to one, by its engine name, to its file name (none where they agree).
     */
    private record Mapped(String module, Map<String, Object> inputs, Set<String> consumed, Map<String, String> names) {

        String fileName(String engine) {
            return names.getOrDefault(engine, engine);
        }
    }

    /** Kind {@code kind}'s inputs as the file names them (aaa-design 2.60, 2.61). */
    private static Mapped toFile(String name, String kind, Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, String> names = new HashMap<>();
        switch (kind) {
            case "force" -> {
                give(out, names, "acceleration", in, "force");
                return new Mapped("acceleration", out, Set.of(), names);
            }
            case "collide", "collide_world", "collide_depth", "collide_entities" -> {
                out.put("against", switch (kind) {
                    case "collide" -> "shape";
                    case "collide_world" -> "world";
                    case "collide_depth" -> "depth";
                    default -> "entities";
                });
                if (kind.equals("collide")) {
                    shapeToFile(in, out);
                    give(out, names, "shape_offset", in, "offset");
                    give(out, names, "keep_inside", in, "keeps_inside");
                    give(out, names, "particle_radius_scale", in, "radius");
                }
                double bounce = ((Number) in.get("bounce")).doubleValue();
                out.put("restitution", List.of(bounce, bounce, bounce));
                give(out, names, "friction", in, "friction");
                give(out, names, "static_friction_speed", in, "rest");
                if (Boolean.TRUE.equals(in.get("kill"))) {
                    out.put("max_collisions", 1);
                    out.put("on_complete", "kill");
                }
                // Ours: the depth a screen collider reaches behind what it sees, an entity's box grown by.
                if (in.containsKey("thickness")) give(out, names, "depth_thickness", in, "thickness");
                if (in.containsKey("size")) give(out, names, "entity_size", in, "size");
                return new Mapped("collision", out, Set.of(), names);
            }
            case "kill" -> {
                shapeToFile(in, out);
                give(out, names, "inside", in, "inside");
                give(out, names, "offset", in, "offset");
                return new Mapped("kill_volume", out, Set.of(), names);
            }
            case "attract" -> {
                if (!"sphere".equals(in.get("shape"))) {
                    throw new IllegalArgumentException(name + ": an attractor over a box has no file form (aaa-design 2.61)");
                }
                give(out, names, "position", in, "offset");
                give(out, names, "strength", in, "strength");
                out.put("radius", ((List<?>) in.get("volume")).get(0));
                give(out, names, "falloff", in, "attenuation");
                give(out, names, "directionality", in, "directionality");
                give(out, names, "axis", in, "axis");
                return new Mapped("point_attractor", out, Set.of(), names);
            }
            case "vector_field" -> {
                give(out, names, "field", in, "field");
                give(out, names, "intensity", in, "strength");
                give(out, names, "translation", in, "offset");
                out.put("tile", Boolean.TRUE.equals(in.get("tiles")) ? "xyz" : "");
                give(out, names, "volume", in, "volume");
                give(out, names, "attenuation", in, "attenuation");
                return new Mapped("vector_field", out, Set.of(), names);
            }
            case "conform" -> {
                out.putAll(in);
                out.put("origin", out.remove("offset"));
                return new Mapped("conform", out, Set.of(), names);
            }
            case "orbit" -> {
                // Ours, beside Unreal's Orbit of another shape (aaa-design 2.61).
                return new Mapped("orbit_axis", new LinkedHashMap<>(in), Set.of(), names);
            }
            default -> {
                return new Mapped(kind, new LinkedHashMap<>(in), Set.of(), names);
            }
        }
    }

    /** A particle_update entry as the engine kind it names and that kind's inputs. */
    private static Mapped toEngine(String name, Map<?, ?> entry) {
        Map<?, ?> in = inputs(entry);
        String id = moduleId(entry);
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, String> names = new HashMap<>();
        Set<String> read = new HashSet<>();
        String kind;
        switch (id) {
            case "acceleration" -> {
                kind = "force";
                take(in, read, out, names, "acceleration", "force");
            }
            case "collision" -> {
                String against = String.valueOf(in.get("against"));
                read.add("against");
                kind = switch (against) {
                    case "shape" -> "collide";
                    case "world" -> "collide_world";
                    case "depth" -> "collide_depth";
                    case "entities" -> "collide_entities";
                    default -> throw new IllegalArgumentException(name + ": collision against " + against);
                };
                if (kind.equals("collide")) {
                    shapeToEngine(in, read, out, names);
                    take(in, read, out, names, "shape_offset", "offset");
                    take(in, read, out, names, "keep_inside", "keeps_inside");
                    take(in, read, out, names, "particle_radius_scale", "radius");
                }
                List<?> r = (List<?>) in.get("restitution");
                if (!(r.get(0).equals(r.get(1)) && r.get(1).equals(r.get(2)))) {
                    throw new IllegalArgumentException(name + ": collision's restitution " + r + " is not read yet: one value on every axis");
                }
                read.add("restitution");
                out.put("bounce", r.get(0));
                take(in, read, out, names, "friction", "friction");
                take(in, read, out, names, "static_friction_speed", "rest");
                Object most = in.get("max_collisions");
                if (most != null && !(most instanceof Number n && n.intValue() == 1 && "kill".equals(in.get("on_complete")))) {
                    throw new IllegalArgumentException(name + ": collision's max_collisions " + most + " is not read yet: 1 and kill alone");
                }
                read.add("max_collisions");
                read.add("on_complete");
                out.put("kill", most != null);
                if (kind.equals("collide_depth")) take(in, read, out, names, "depth_thickness", "thickness");
                if (kind.equals("collide_entities")) take(in, read, out, names, "entity_size", "size");
            }
            case "set_position" -> {
                kind = "set_position";
                take(in, read, out, names, "position", "position");
                take(in, read, out, names, "offset", "offset");
                take(in, read, out, names, "scale", "scale");
                // Cascade's LocationDirect never reads its direction (ParticleModules_Location.cpp:330-385).
                read.add("direction");
            }
            case "scale_velocity" -> {
                kind = "scale_velocity";
                take(in, read, out, names, "scale", "scale");
                take(in, read, out, names, "absolute", "absolute");
                // The engine's frame is the world's: Cascade's bInWorldSpace.
                if ("world".equals(in.get("space"))) read.add("space");
            }
            case "kill_volume" -> {
                kind = "kill";
                shapeToEngine(in, read, out, names);
                take(in, read, out, names, "inside", "inside");
                take(in, read, out, names, "offset", "offset");
            }
            case "point_attractor" -> {
                kind = "attract";
                out.put("shape", "sphere");
                double r = ((Number) in.get("radius")).doubleValue();
                out.put("volume", List.of(r, r, r));
                read.add("radius");
                take(in, read, out, names, "position", "offset");
                take(in, read, out, names, "strength", "strength");
                take(in, read, out, names, "falloff", "attenuation");
                take(in, read, out, names, "directionality", "directionality");
                take(in, read, out, names, "axis", "axis");
            }
            case "vector_field" -> {
                kind = "vector_field";
                take(in, read, out, names, "field", "field");
                take(in, read, out, names, "intensity", "strength");
                take(in, read, out, names, "translation", "offset");
                out.put("tiles", !String.valueOf(in.get("tile")).isEmpty() && in.get("tile") != null);
                read.add("tile");
                take(in, read, out, names, "volume", "volume");
                take(in, read, out, names, "attenuation", "attenuation");
                take(in, read, out, names, "tightness", "tightness");
            }
            case "conform" -> {
                kind = "conform";
                for (Map.Entry<?, ?> e : in.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
                read.addAll(out.keySet());
                out.put("offset", out.remove("origin"));
            }
            case "orbit_axis" -> {
                kind = "orbit";
                for (Map.Entry<?, ?> e : in.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
                read.addAll(out.keySet());
            }
            default -> {
                kind = id;
                CgVfxModuleTypes.Kind<?> k = CgVfxModuleTypes.kind(id);
                if (k == null) throw new IllegalArgumentException(name + ": no module " + entry.get("module"));
                for (CgProperty p : k.inputs().properties()) {
                    if (in.containsKey(p.name())) {
                        out.put(p.name(), in.get(p.name()));
                        read.add(p.name());
                    }
                }
            }
        }
        unread(name, entry, in, read);
        return new Mapped(kind, out, read, names);
    }

    /**
     * Each input of {@code m} written in a form other than a constant, compiled ({@link CgVfxFormCompiler}) and given to
     * {@code b} as module {@code index}'s form; its value in {@code m} becomes the form's first end, which the module's
     * own words hold.
     */
    private static void formed(String name, Map<?, ?> entry, Mapped m, CgVfxModuleTypes.Kind<?> kind, int index,
                               CgVfxFormCompiler forms, CgVfxEmitter.Builder b) {
        CgModuleTypes.Module declared = CgModuleTypes.builtIn().get(moduleId(entry));
        for (Map.Entry<String, Object> in : m.inputs.entrySet()) {
            if (!isForm(in.getValue())) continue;
            String file = m.fileName(in.getKey());
            if (!m.module.equals(moduleId(entry)) && file.equals(in.getKey()) && !m.consumed.contains(file)) {
                throw new IllegalArgumentException(name + ": " + entry.get("module") + "'s " + in.getKey()
                        + " is written in a form, which an input the engine derives otherwise does not read yet");
            }
            CgProperty property = property(declared != null ? declared.inputs().properties() : null, file);
            if (property == null) property = property(kind.inputs().properties(), in.getKey());
            CgVfxFormInput compiled = forms.compile(idOf(entry, m.module), property, in.getValue());
            b.form(new CgVfxModuleForm(index, in.getKey(), in.getValue(), compiled));
            in.setValue(firstEnd(compiled));
        }
    }

    @Nullable
    private static CgProperty property(@Nullable List<CgProperty> properties, String name) {
        if (properties != null) for (CgProperty p : properties) if (p.name().equals(name)) return p;
        return null;
    }

    /** A form: a map with a range, pick, curve, gradient, array, function or binding. */
    private static boolean isForm(Object value) {
        if (!(value instanceof Map<?, ?> m)) return false;
        for (String k : List.of("range", "pick", "curve", "curves", "gradient", "array", "fn", "bind")) if (m.containsKey(k)) return true;
        return false;
    }

    /** What a form's module words hold: a range's or a pick's first end, else zero. */
    private static Object firstEnd(CgVfxFormInput c) {
        float[] v = c instanceof CgVfxFormInput.Range r ? r.min() : c instanceof CgVfxFormInput.Pick p ? p.a() : new float[c.components()];
        return v.length == 1 ? (Object) (double) v[0] : list(v);
    }

    private static void take(Map<?, ?> in, Set<String> read, Map<String, Object> out, Map<String, String> names, String file,
                             String engine) {
        read.add(file);
        if (in.containsKey(file)) out.put(engine, in.get(file));
        if (!file.equals(engine)) names.put(engine, file);
    }

    /** {@code take}'s other way: engine input {@code engine} written as {@code file}. */
    private static void give(Map<String, Object> out, Map<String, String> names, String file, Map<String, Object> in, String engine) {
        out.put(file, in.get(engine));
        if (!file.equals(engine)) names.put(engine, file);
    }

    /** A shape's {@code volume} and {@code shape} as Unreal's shape inputs; a plane's radius ours. */
    private static void shapeToFile(Map<String, Object> in, Map<String, Object> out) {
        String shape = String.valueOf(in.get("shape"));
        List<?> v = (List<?>) in.get("volume");
        out.put("shape", shape);
        switch (shape) {
            case "sphere" -> out.put("sphere_radius", v.get(0));
            case "box" -> out.put("box_size", List.of(2.0 * ((Number) v.get(0)).doubleValue(), 2.0 * ((Number) v.get(1)).doubleValue(),
                    2.0 * ((Number) v.get(2)).doubleValue()));
            default -> {
                out.put("plane_normal", v);
                out.put("plane_radius", in.get("plane_radius"));
            }
        }
    }

    private static void shapeToEngine(Map<?, ?> in, Set<String> read, Map<String, Object> out, Map<String, String> names) {
        String shape = String.valueOf(in.get("shape"));
        read.add("shape");
        out.put("shape", shape);
        switch (shape) {
            case "sphere" -> {
                Object r = in.get("sphere_radius");
                out.put("volume", List.of(r, r, r));
                read.add("sphere_radius");
            }
            case "box" -> {
                float[] s = vec(in.get("box_size"), 3);
                out.put("volume", List.of(s[0] / 2.0, s[1] / 2.0, s[2] / 2.0));
                read.add("box_size");
            }
            default -> {
                out.put("volume", in.get("plane_normal"));
                read.add("plane_normal");
                read.add("plane_radius");
                // Unreal's planes have no edge; ours may (a ramp), its radius an input of our own.
                out.put("plane_radius", in.containsKey("plane_radius") ? in.get("plane_radius") : (double) CgPlane.of(0, 1, 0).radius());
            }
        }
    }

    /**
     * Fails for an input {@code entry} gives that nothing read and that is not {@code modules.yaml}'s default: one the
     * spec declares and the engine does not take yet (Unreal's {@code curl_noise.bias}) reads at its default alone.
     */
    private static void unread(String name, Map<?, ?> entry, Map<?, ?> in, Set<String> read) {
        CgModuleTypes.Module declared = CgModuleTypes.builtIn().get(moduleId(entry));
        for (Map.Entry<?, ?> e : in.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (read.contains(key)) continue;
            Object fallback = null;
            if (declared != null) {
                for (CgProperty p : declared.inputs().properties()) {
                    if (p.name().equals(key) && p.defaultValue() != null) fallback = CgUnits.UNREAL.toHost(p.defaultValue(), p);
                }
            }
            if (!sameValue(e.getValue(), fallback)) {
                throw new IllegalArgumentException(name + ": " + entry.get("module") + ".inputs." + key
                        + " is not taken by the engine yet, only at its default " + fallback);
            }
        }
    }

    /** Equal as values: numbers by their double, lists element by element. */
    private static boolean sameValue(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) return x.doubleValue() == y.doubleValue();
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!sameValue(x.get(i), y.get(i))) return false;
            return true;
        }
        return Objects.equals(a, b);
    }

    // ── emitter_state ────────────────────────────────────────────────────────────────────

    private static Map<String, Object> state(CgVfxEmitterState s) {
        return map("loop", s.loopBehavior().name().toLowerCase(Locale.ROOT), "loop_count", s.loopCount(),
                "duration", range(s.durationMin(), s.durationMax()),
                "recalculate_each_loop", s.recalculatesDurationEachLoop(),
                "delay", range(s.delayMin(), s.delayMax()),
                "delay_first_loop_only", s.delaysFirstLoopOnly(),
                "recalculate_delay_each_loop", s.recalculatesDelayEachLoop(),
                "inactive", s.inactiveResponse().name().toLowerCase(Locale.ROOT));
    }

    /**
     * {@code spawn_rate}'s rate times its scale: each a number, a range drawn each step, or a curve by the loop's seconds,
     * as Cascade evaluates its rate's distribution at the emitter's time each tick. Two ranges at once are not read.
     */
    private static CgVfxSpawnRate spawnRate(String name, Object rate, Object scale) {
        boolean rateCurve = rate instanceof Map<?, ?> r && r.get("curve") != null;
        boolean scaleCurve = scale instanceof Map<?, ?> s && s.get("curve") != null;
        float[] ends = rateCurve ? new float[]{1f, 1f} : rangeOf(rate);
        float[] by = scale == null ? new float[]{1f, 1f} : scaleCurve ? new float[]{1f, 1f} : rangeOf(scale);
        if (ends[1] > ends[0] && by[1] > by[0]) {
            throw new IllegalArgumentException(name + ": spawn_rate's rate and scale both drawn between two ends is not read yet");
        }
        CgKeyframes curve = rateCurve ? keyframes(name, rate, "loop") : null;
        if (scaleCurve) {
            CgKeyframes s = keyframes(name, scale, "loop");
            curve = curve == null ? s : product(curve, s);
        }
        return new CgVfxSpawnRate(ends[0] * by[0], ends[1] * by[1], curve != null ? curve : CgKeyframes.constant(1f));
    }

    /** {@code a} times {@code b}, sampled at each one's keys and between them. */
    private static CgKeyframes product(CgKeyframes a, CgKeyframes b) {
        TreeSet<Float> times = new TreeSet<>();
        for (CgKeyframes k : List.of(a, b)) {
            for (int i = 0; i < k.size(); i++) {
                times.add(k.time(i));
                if (i > 0) for (int j = 1; j < EASE_STEPS; j++) times.add(k.time(i - 1) + (k.time(i) - k.time(i - 1)) * j / EASE_STEPS);
            }
        }
        CgKeyframes.Builder out = null;
        for (float t : times) {
            float v = a.at(t) * b.at(t);
            out = out == null ? CgKeyframes.start(t, v) : out.to(t, v, CgEasings.LINEAR);
        }
        return out.build();
    }

    private static CgVfxEmitterState state(Map<?, ?> in) {
        CgVfxLoopBehavior loop = CgVfxLoopBehavior.valueOf(String.valueOf(in.get("loop")).toUpperCase(Locale.ROOT));
        float[] d = rangeOf(in.get("duration"));
        int count = in.get("loop_count") instanceof Number n ? n.intValue() : 1;
        CgVfxEmitterState s = switch (loop) {
            case ONCE -> CgVfxEmitterState.once(d[0], d[1]);
            case MULTIPLE -> CgVfxEmitterState.loops(count, d[0], d[1]);
            case INFINITE -> CgVfxEmitterState.infinite(d[0], d[1]);
        };
        if (Boolean.TRUE.equals(in.get("recalculate_each_loop"))) s = s.recalculateDurationEachLoop();
        float[] delay = in.get("delay") != null ? rangeOf(in.get("delay")) : new float[2];
        if (delay[1] > 0f) s = s.delay(delay[0], delay[1]);
        if (Boolean.TRUE.equals(in.get("delay_first_loop_only"))) s = s.delayFirstLoopOnly();
        if (Boolean.TRUE.equals(in.get("recalculate_delay_each_loop"))) s = s.recalculateDelayEachLoop();
        if ("kill".equals(in.get("inactive"))) s = s.inactiveResponse(CgVfxInactiveResponse.KILL);
        return s;
    }

    // ── Curves ───────────────────────────────────────────────────────────────────────────

    private static boolean isOne(CgKeyframes k) {
        for (int i = 0; i < k.size(); i++) if (k.value(i) != 1f) return false;
        return true;
    }

    /** {@code k} by {@code by}: a number where it holds one value. */
    /** {@code e}'s drawn channels as the modules that set them: dynamic parameters, a sub-image index, a camera offset. */
    private static void writeDrawn(CgVfxEmitter e, List<Object> modules) {
        Map<String, Object> dynamics = new LinkedHashMap<>();
        for (int slot = 0; slot < 4; slot++) {
            List<Object> channels = new ArrayList<>();
            boolean any = false;
            for (int c = 0; c < 4; c++) {
                CgVfxDrawnChannel ch = e.drawn[slot][c];
                any |= ch != null;
                Map<String, Object> channel = new LinkedHashMap<>();
                channel.put("value", ch == null ? 0.0 : drawnForm(e.name, ch));
                if (ch != null && ch.roll() == CgVfxDrawnChannel.Roll.SPAWN) channel.put("spawn_only", true);
                channel.put("source", "user");
                channels.add(channel);
            }
            if (any) dynamics.put("dynamic_" + slot, channels);
        }
        if (!dynamics.isEmpty()) modules.add(entry("dynamic_parameters", "dynamic_material_parameters", dynamics));
        CgVfxDrawnChannel frame = e.drawn[CgVfxDrawnChannel.SPRITE][CgVfxDrawnChannel.SUB_IMAGE];
        if (frame != null && (e.subUv == null || e.subUv.mode() != CgVfxFlipbook.Mode.DIRECT)) {
            throw new IllegalArgumentException(e.name + " carries a sub-image index its sub-UV animation does not play: subUv(direct())");
        }
        if (e.subUv != null) {
            Map<String, Object> in = new LinkedHashMap<>();
            in.put("mode", e.subUv.mode().name().toLowerCase(Locale.ROOT));
            switch (e.subUv.mode()) {
                case LOOP -> {
                    in.put("frame_rate", (double) e.subUv.rate());
                    in.put("start_frame", e.subUv.startsRandom() ? 0 : 1);
                }
                case RANDOM -> in.put("frame_rate", (double) e.subUv.rate());
                case DIRECT -> {
                    if (frame != null && frame.roll() == CgVfxDrawnChannel.Roll.SPAWN) {
                        throw new IllegalArgumentException(e.name + "'s sub-image index is rolled at birth, which no file writes");
                    }
                    in.put("frame", frame == null ? 0.0 : drawnForm(e.name, frame));
                    if (frame != null && frame.realTime()) in.put("use_real_time", true);
                }
                default -> { }
            }
            modules.add(entry("sub_uv", "sub_uv_animation", in));
        }
        CgVfxDrawnChannel offset = e.drawn[CgVfxDrawnChannel.SPRITE][CgVfxDrawnChannel.CAMERA_OFFSET];
        if (offset != null) {
            Map<String, Object> in = map("offset", drawnForm(e.name, offset), "update", "direct");
            if (offset.roll() == CgVfxDrawnChannel.Roll.SPAWN) in.put("spawn_only", true);
            modules.add(entry("camera_offset", "camera_offset", in));
        }
    }

    /** A drawn channel as the form that reads back to it: its curve over life, or the range its roll spans. */
    private static Object drawnForm(String name, CgVfxDrawnChannel ch) {
        if (ch.roll() == CgVfxDrawnChannel.Roll.BIND) {
            CgVfxFormInput.Binding u = ch.bind();
            Map<String, Object> form = new LinkedHashMap<>();
            form.put("bind", u.path());
            form.put("default", (double) u.fallback()[0]);
            if (u.inLo() != null) {
                form.put("remap", map("in", list(u.inLo()[0], u.inHi()[0]), "out", list(u.outLo()[0], u.outHi()[0])));
                form.put("mode", u.modes()[0]);
            }
            if (u.scale() != 1f) form.put("scale", (double) u.scale());
            return form;
        }
        if (ch.roll() == CgVfxDrawnChannel.Roll.NONE) return curve(ch.from(), "age");
        if (ch.from().size() != 1 || ch.to().size() != 1) {
            throw new IllegalArgumentException(name + ": a drawn channel rolled between curves, which no file writes");
        }
        return map("range", list(ch.from().value(0), ch.to().value(0)));
    }

    private static Object curve(CgKeyframes k, String by) {
        if (k.size() == 1) return (double) k.value(0);
        return map("curve", richCurve(k), "by", by);
    }

    private static Map<String, Object> curves(String by, CgKeyframes... k) {
        List<Object> list = new ArrayList<>();
        for (CgKeyframes c : k) list.add(richCurve(c));
        return map("curves", list, "by", by);
    }

    /**
     * {@code k} as {@code FRichCurve}'s keys. All linear, its keys linear. Otherwise every key cubic with its tangents:
     * a linear segment's both its slope, a quad or cubic easing's its polynomial's end slopes (exact), and any other
     * easing {@link #EASE_STEPS} Hermite pieces along it.
     */
    private static Map<String, Object> richCurve(CgKeyframes k) {
        boolean linear = true;
        for (int i = 1; i < k.size(); i++) linear &= k.easing(i) == CgEasings.LINEAR;
        List<Object> keys = new ArrayList<>();
        if (linear) {
            for (int i = 0; i < k.size(); i++) keys.add(List.of((double) k.time(i), (double) k.value(i), "linear"));
            return map("keys", keys);
        }
        // t, v, arrive, leave
        List<double[]> points = new ArrayList<>();
        points.add(new double[]{k.time(0), k.value(0), 0, 0});
        for (int i = 1; i < k.size(); i++) {
            double t0 = k.time(i - 1), v0 = k.value(i - 1), t1 = k.time(i), v1 = k.value(i), dt = t1 - t0, dv = v1 - v0;
            double[] prev = points.get(points.size() - 1);
            if (!(dt > 0)) {
                points.add(new double[]{t1, v1, 0, 0});
                continue;
            }
            if (k.easing(i) instanceof Cubic c && !Double.isNaN(c.leave())) {
                // A file's cubic: its own slopes back as read, so a curve round-trips to the bit.
                prev[3] = c.leave();
                points.add(new double[]{t1, v1, c.arrive(), 0});
                continue;
            }
            double[] ends = endSlopes(k.easing(i));
            if (ends != null) {
                prev[3] = dv * ends[0] / dt;
                points.add(new double[]{t1, v1, dv * ends[1] / dt, 0});
                continue;
            }
            CgEasing easing = k.easing(i);
            prev[3] = dv * slope(easing, 0) / dt;
            for (int j = 1; j <= EASE_STEPS; j++) {
                double u = (double) j / EASE_STEPS, t = t0 + u * dt, v = j == EASE_STEPS ? v1 : v0 + dv * easing.ease(u);
                double s = dv * slope(easing, u) / dt;
                points.add(new double[]{t, v, s, s});
            }
        }
        for (double[] p : points) keys.add(List.of(p[0], p[1], "cubic", "break", p[2], p[3]));
        return map("keys", keys);
    }

    /** {@code e}'s slope at {@code u}, by a central difference (one-sided at the ends). */
    private static double slope(CgEasing e, double u) {
        double h = 1e-6, lo = Math.max(0, u - h), hi = Math.min(1, u + h);
        return (e.ease(hi) - e.ease(lo)) / (hi - lo);
    }

    /** An easing's slope at its start and end, in its own units: those Hermite reproduces exactly; null for any other. */
    @Nullable
    private static double[] endSlopes(CgEasing e) {
        if (e == CgEasings.LINEAR) return new double[]{1, 1};
        if (e == CgEasings.IN_QUAD) return new double[]{0, 2};
        if (e == CgEasings.OUT_QUAD) return new double[]{2, 0};
        if (e == CgEasings.IN_CUBIC) return new double[]{0, 3};
        if (e == CgEasings.OUT_CUBIC) return new double[]{3, 0};
        return null;
    }

    /**
     * A curve value read as keyframes: a number constant; {@code { curve, by }} its keys, a linear segment linear, a
     * constant one a step and a cubic one eased along the curve itself, so it evaluates as the file's curve.
     */
    private static CgKeyframes keyframes(String name, Object value, String by) {
        if (value instanceof Number n) return CgKeyframes.constant(n.floatValue());
        if (!(value instanceof Map<?, ?> form) || form.get("curve") == null) {
            throw new IllegalArgumentException(name + ": a number or a curve by " + by + ", not " + value);
        }
        if (form.get("by") != null && !by.equals(form.get("by"))) {
            throw new IllegalArgumentException(name + ": a curve by " + form.get("by") + " is not read yet here, only by " + by);
        }
        if (!by.equals("age") && form.get("curve") instanceof Map<?, ?> m && (isExtrapolated(m.get("pre")) || isExtrapolated(m.get("post")))) {
            throw new IllegalArgumentException(name + ": an extrapolated curve by " + by + " is not read yet: by age alone");
        }
        return keyframes(name, CgRichCurve.decode(form.get("curve")), form.get("curve"));
    }

    private static CgKeyframes keyframes(String name, CgRichCurve c, Object tree) {
        if (tree instanceof Map<?, ?> m && m.get("samples") instanceof Map<?, ?> samples) {
            // Linear between samples and held outside them, as CgRichCurve evaluates them.
            float[] range = vec(samples.get("range"), 2);
            int n = c.size();
            if (n < 2) return CgKeyframes.constant(c.eval(range[0]));
            CgKeyframes.Builder k = CgKeyframes.start(range[0], c.value(0));
            for (int i = 1; i < n; i++) k.to(range[0] + (range[1] - range[0]) * i / (n - 1), c.value(i), CgEasings.LINEAR);
            return k.build();
        }
        if (tree instanceof Map<?, ?> m && (isExtrapolated(m.get("pre")) || isExtrapolated(m.get("post")))) {
            // Over a life, a curve's extrapolation is what it gives between 0 and 1 outside its keys.
            if (c.size() == 0) return CgKeyframes.constant(c.eval(0f));
            float from = Math.min(0f, c.time(0)), to = Math.max(1f, c.time(c.size() - 1));
            CgKeyframes.Builder k = CgKeyframes.start(from, c.eval(from));
            for (int j = 1; j <= EXTRAPOLATED_STEPS; j++) {
                float t = from + (to - from) * j / EXTRAPOLATED_STEPS;
                k.to(t, c.eval(t), CgEasings.LINEAR);
            }
            return k.build();
        }
        if (c.size() == 0) return CgKeyframes.constant(c.eval(0f));
        CgKeyframes.Builder k = CgKeyframes.start(c.time(0), c.value(0));
        for (int i = 1; i < c.size(); i++) {
            float t0 = c.time(i - 1), v0 = c.value(i - 1), t1 = c.time(i), v1 = c.value(i);
            int interp = c.interp(i - 1);
            if (interp == CgRichCurve.LINEAR || !(t1 > t0)) {
                k.to(t1, v1, CgEasings.LINEAR);
            } else if (interp == CgRichCurve.CONSTANT) {
                k.to(t1, v0, CgEasings.LINEAR).to(t1, v1, CgEasings.LINEAR);
            } else if (v1 != v0) {
                float span = t1 - t0, rise = v1 - v0;
                boolean hermite = !c.weighted(i - 1) && !c.weighted(i);
                k.to(t1, v1, new Cubic(c, t0, span, v0, rise, hermite ? c.leave(i - 1) : Double.NaN, hermite ? c.arrive(i) : Double.NaN));
            } else {
                // A cubic between equal values bulges: eased pieces would divide by nothing, so straight ones.
                for (int j = 1; j <= EASE_STEPS; j++) {
                    float t = t0 + (t1 - t0) * j / EASE_STEPS;
                    k.to(t, c.eval(t), CgEasings.LINEAR);
                }
            }
        }
        return k.build();
    }

    /**
     * A cubic segment of a file's curve as an easing, evaluated as the curve itself. Unweighted, it keeps its keys'
     * slopes as the file gave them, so {@link #richCurve} writes it back as the same cubic; weighted, they are NaN.
     */
    private record Cubic(CgRichCurve curve, float t0, float span, float v0, float rise, double leave, double arrive)
            implements CgEasing {
        @Override
        public double ease(double u) {
            return (curve.eval(t0 + (float) u * span) - v0) / rise;
        }
    }

    private static boolean isExtrapolated(Object e) {
        return e != null && !"constant".equals(e);
    }

    /** {@code { curves: [...] }} as one keyframes an axis; {@code { curve }} the same on every axis. */
    private static CgKeyframes[] axes(String name, Object value, int n) {
        CgKeyframes[] out = new CgKeyframes[n];
        if (value instanceof Map<?, ?> form && form.get("curves") instanceof List<?> list) {
            if (list.size() < n) throw new IllegalArgumentException(name + ": " + n + " curves, not " + list.size());
            for (int i = 0; i < n; i++) out[i] = keyframes(name, CgRichCurve.decode(list.get(i)), list.get(i));
            return out;
        }
        if (value instanceof List<?> constant) {
            for (int i = 0; i < n; i++) out[i] = CgKeyframes.constant(num(constant.get(i)));
            return out;
        }
        if (value instanceof Map<?, ?> m && m.get("argb") instanceof Number) {
            float[] c = rgba(m);
            for (int i = 0; i < n; i++) out[i] = CgKeyframes.constant(c[i]);
            return out;
        }
        CgKeyframes one = keyframes(name, uniform(value), "age");
        Arrays.fill(out, one);
        return out;
    }

    /** A vec2's uniform form, {@code { curves: [c, c] }}, as {@code { curve: c }}. */
    private static Object uniform(Object value) {
        if (value instanceof Map<?, ?> form && form.get("curves") instanceof List<?> list && !list.isEmpty()) {
            Map<Object, Object> one = new LinkedHashMap<>(form);
            one.remove("curves");
            one.put("curve", list.get(0));
            return one;
        }
        if (value instanceof List<?> constant && !constant.isEmpty()) return constant.get(0);
        return value;
    }

    // ── The tree ─────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> entry(String id, String module, Map<String, Object> inputs) {
        return map("id", id, "module", NS + module, "inputs", inputs);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static List<Object> list(float... v) {
        List<Object> out = new ArrayList<>(v.length);
        for (float f : v) out.add((double) f);
        return out;
    }

    private static boolean same(float[] a, float[] b) {
        return Arrays.equals(a, b);
    }

    private static Object range(float min, float max) {
        return min == max ? (Object) (double) min : map("range", Arrays.asList((double) min, (double) max));
    }

    /** Two ends of a vector, rolled per channel: one where both agree. */
    private static Object vector(float[] from, float[] to) {
        return same(from, to) ? list(from) : map("range", List.of(list(from), list(to)), "per_channel", true);
    }

    /** A sprite size, Niagara's vec2 of width and height: a square, or a range of two squares. */
    private static Object spriteSize(float min, float max) {
        List<Double> lo = Arrays.asList((double) min, (double) min);
        return min == max ? lo : map("range", Arrays.asList(lo, Arrays.asList((double) max, (double) max)));
    }

    /**
     * A sprite size's width, as a range: the vec2's x, or each end's of a range of vec2s; a number or a range of numbers
     * is a square.
     */
    private static float[] spriteWidth(Object value) {
        if (value instanceof List<?> v) return new float[]{num(v.get(0)), num(v.get(0))};
        if (value instanceof Map<?, ?> m && m.get("range") instanceof List<?> r
                && r.get(0) instanceof List<?> lo && r.get(1) instanceof List<?> hi) {
            return new float[]{num(lo.get(0)), num(hi.get(0))};
        }
        return rangeOf(value);
    }

    /** A vector's two ends: a constant both, a range its two; a colour in the engine's form decoded. */
    private static float[][] ends(Object value, int n) {
        if (value instanceof Map<?, ?> m && m.get("range") instanceof List<?> r) return new float[][]{endOf(r.get(0), n), endOf(r.get(1), n)};
        float[] one = endOf(value, n);
        return new float[][]{one, one.clone()};
    }

    /**
     * A colour's two ends, each four floats: a colour is 2 to 4 floats ({@code core.md} § Colours, the schema's
     * {@code colour}), the channels it leaves out 0 and its alpha {@code alpha} (the module's own input, else 1).
     */
    private static float[][] colourEnds(Object value, float alpha) {
        if (value instanceof Map<?, ?> m && m.get("range") instanceof List<?> r) {
            return new float[][]{colourOf(r.get(0), alpha), colourOf(r.get(1), alpha)};
        }
        float[] one = colourOf(value, alpha);
        return new float[][]{one, one.clone()};
    }

    private static float[] colourOf(Object value, float alpha) {
        if (value instanceof Map<?, ?> m && m.get("argb") instanceof Number) return rgba(m);
        if (!(value instanceof List<?> v) || v.size() < 2 || v.size() > 4) {
            throw new IllegalArgumentException("a colour of 2 to 4 floats, not " + value);
        }
        float[] c = {0f, 0f, 0f, alpha};
        for (int i = 0; i < v.size(); i++) c[i] = num(v.get(i));
        return c;
    }

    private static float[] endOf(Object value, int n) {
        if (value instanceof Map<?, ?> m && m.get("argb") instanceof Number) return rgba(m);
        return vec(value, n);
    }

    /** {@code { argb, intensity }}: its rgb times the intensity, and its alpha, as the builder's {@code color} makes it. */
    private static float[] rgba(Map<?, ?> m) {
        int argb = (int) ((Number) m.get("argb")).longValue();
        float i = m.get("intensity") instanceof Number n ? n.floatValue() : 1f;
        return new float[]{(argb >> 16 & 0xFF) / 255f * i, (argb >> 8 & 0xFF) / 255f * i, (argb & 0xFF) / 255f * i, (argb >>> 24) / 255f};
    }

    private static float[] vec(Object value, int n) {
        if (!(value instanceof List<?> v) || v.size() < n) throw new IllegalArgumentException("a vector of " + n + ", not " + value);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) out[i] = num(v.get(i));
        return out;
    }

    private static float[] rangeOf(Object value) {
        if (value instanceof Number n) return new float[]{n.floatValue(), n.floatValue()};
        if (value instanceof Map<?, ?> m && m.get("range") instanceof List<?> r) return new float[]{num(r.get(0)), num(r.get(1))};
        throw new IllegalArgumentException("a number or a range, not " + value);
    }

    private static float num(Object o) {
        if (!(o instanceof Number n)) throw new IllegalArgumentException("a number, not " + o);
        return n.floatValue();
    }

    /**
     * {@code tree} with each module entry's written inputs in the host's units, each by its declared quantity; a value's
     * own {@code unit:} converted and dropped. What a stack's modules do not declare stays as written, for the compile to
     * name.
     */
    private static Map<?, ?> toHost(Map<?, ?> tree, CgUnits units) {
        Map<Object, Object> out = new LinkedHashMap<>(tree);
        for (String stack : STACKS) {
            if (!(tree.get(stack) instanceof List<?> list)) continue;
            List<Object> converted = new ArrayList<>(list.size());
            for (Object e : list) {
                CgModuleTypes.Module declared = e instanceof Map<?, ?> entry && entry.get("inputs") instanceof Map<?, ?>
                        ? CgModuleTypes.builtIn().get(moduleId(entry)) : null;
                if (declared == null) {
                    converted.add(e);
                    continue;
                }
                Map<?, ?> entry = (Map<?, ?>) e;
                Map<Object, Object> in = new LinkedHashMap<>((Map<?, ?>) entry.get("inputs"));
                for (CgProperty p : declared.inputs().properties()) {
                    if (in.containsKey(p.name())) in.put(p.name(), units.toHost(in.get(p.name()), p));
                }
                // socket_location's points carry their own offsets, which the declaration's loose list does not type.
                if (in.get("points") instanceof List<?> points && moduleId(entry).equals("socket_location")) {
                    List<Object> host = new ArrayList<>(points.size());
                    for (Object point : points) {
                        if (point instanceof Map<?, ?> m && m.get("offset") instanceof List<?> offset) {
                            Map<Object, Object> moved = new LinkedHashMap<>(m);
                            moved.put("offset", units.toHost(offset, CgQuantity.OFFSET));
                            point = moved;
                        }
                        host.add(point);
                    }
                    in.put("points", host);
                }
                Map<Object, Object> copy = new LinkedHashMap<>(entry);
                copy.put("inputs", in);
                converted.add(copy);
            }
            out.put(stack, converted);
        }
        return out;
    }

    private static final List<String> STACKS = List.of("emitter_update", "particle_spawn", "particle_update");

    private static List<Map<?, ?>> entries(Map<?, ?> tree, String stack) {
        List<Map<?, ?>> out = new ArrayList<>();
        if (tree.get(stack) instanceof List<?> list) for (Object o : list) out.add((Map<?, ?>) o);
        return out;
    }

    /**
     * Its inputs: each one absent taking the default {@code modules.yaml} declares, which is in Unreal's units
     * ({@code modules.md} § Declaration), and each one its {@code visible_when} gate leaves unread dropped. An input
     * declared optional ({@code default: null}) stays absent; one declared with no default fails here, by name, where
     * its gate holds and the asset leaves it out.
     */
    private static Map<?, ?> inputs(Map<?, ?> entry) {
        Map<Object, Object> in = new LinkedHashMap<>(entry.get("inputs") instanceof Map<?, ?> m ? m : Map.of());
        CgModuleTypes.Module declared = CgModuleTypes.builtIn().get(moduleId(entry));
        if (declared == null) return in;
        for (CgProperty p : declared.inputs().properties()) {
            if (!in.containsKey(p.name()) && p.defaultValue() != null) {
                in.put(p.name(), CgUnits.UNREAL.toHost(p.defaultValue(), p));
            }
        }
        for (CgProperty p : declared.inputs().properties()) {
            if (!gateHolds(p, in)) {
                in.remove(p.name());
            } else if (!in.containsKey(p.name()) && p.flags().contains(CgPropertyFlag.NO_DEFAULT)) {
                throw new IllegalArgumentException(entry.get("module") + ".inputs." + p.name()
                        + ": required, and modules.yaml declares no default");
            }
        }
        return in;
    }

    /** Whether every input {@code p}'s gate names holds the value it asks for; a list is any of its values. */
    private static boolean gateHolds(CgProperty p, Map<?, ?> in) {
        for (Map.Entry<String, Object> gate : p.meta().visibleWhen().entrySet()) {
            Object actual = in.get(gate.getKey());
            Object want = gate.getValue();
            boolean held = want instanceof List<?> any ? any.contains(actual) : want.equals(actual);
            if (!held) return false;
        }
        return true;
    }

    private static String moduleId(Map<?, ?> entry) {
        String id = String.valueOf(entry.get("module"));
        return id.startsWith(NS) ? id.substring(NS.length()) : id;
    }
}
