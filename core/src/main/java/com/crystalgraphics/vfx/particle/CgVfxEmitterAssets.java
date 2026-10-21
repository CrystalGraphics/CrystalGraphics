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
import java.util.function.Function;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.curve.CgRichCurve;
import com.crystalgraphics.asset.kind.CgModuleTypes;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.easing.CgEasing;
import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.object.CgProperty;
import com.crystalgraphics.object.CgPropertyFlag;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.shape.primitive.CgPlane;
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
        if (e.rate.perSecond() > 0f) {
            Map<String, Object> rate = map("rate", (double) e.rate.perSecond());
            if (!isOne(e.rate.scale())) rate.put("scale", curve(e.rate.scale(), "loop"));
            update.add(entry("rate", "spawn_rate", rate));
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
        if (!same(e.colorFrom, WHITE) || !same(e.colorTo, WHITE)) init.put("color", vector(e.colorFrom, e.colorTo));
        if (!same(e.scaleFrom, UNIT) || !same(e.scaleTo, UNIT)) init.put("mesh_scale", vector(e.scaleFrom, e.scaleTo));
        spawn.add(entry(e.id("init"), "initialize_particle", init));
        Map<String, Object> shape = shape(e);
        if (shape != null) spawn.add(entry(e.id("shape"), "shape_location", shape));
        spawn.add(entry(e.id("velocity"), "add_velocity", velocity(e)));
        tree.put("particle_spawn", spawn);

        List<Object> modules = new ArrayList<>();
        if (!isOne(e.sizeOverLife)) modules.add(entry("size", "scale_sprite_size", map("scale", curve(e.sizeOverLife, "age"))));
        if (!isOne(e.scaleX) || !isOne(e.scaleY) || !isOne(e.scaleZ)) {
            modules.add(entry("scale", "scale_mesh_size", map("scale", curves("age", e.scaleX, e.scaleY, e.scaleZ))));
        }
        Map<String, Object> color = new LinkedHashMap<>();
        if (!isOne(e.red) || !isOne(e.green) || !isOne(e.blue)) color.put("scale", curves("age", e.red, e.green, e.blue));
        if (!isOne(e.opacityOverLife)) color.put("alpha", curve(e.opacityOverLife, "age"));
        if (!isOne(e.intensity)) color.put("intensity", curve(e.intensity, "age"));
        if (!color.isEmpty()) modules.add(entry("color", "scale_color", color));
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
                in.put("cylinder_height", (double) (2f * s.c));
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
        if (o[0] != 0f || o[1] != 0f || o[2] != 0f) in.put("offset", list(o[0], o[1], o[2]));
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
        switch (s.mode) {
            case CgVfxSpawnShape.INWARD -> in.put("mode", "inward");
            case CgVfxSpawnShape.DIRECTION -> {
                in.put("mode", "in_cone");
                in.put("cone_axis", list(s.dirX, s.dirY, s.dirZ));
                // Unreal's cone angles are apex angles: twice the half angles drawn between.
                in.put("cone_angle", 2.0 * s.spread);
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
                    Object scale = in.get("scale");
                    b.rate(scale == null || scale instanceof Number ? new CgVfxSpawnRate(num(in.get("rate")), CgKeyframes.constant(scale == null ? 1f : num(scale)))
                            : new CgVfxSpawnRate(num(in.get("rate")), keyframes(name, scale, "loop")));
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
            Map<?, ?> in = inputs(entry);
            switch (moduleId(entry)) {
                case "initialize_particle" -> initialize(name, b, in, spawnId(b, entry, "init"));
                case "add_velocity" -> velocity(name, b, in, spawnId(b, entry, "velocity"));
                case "shape_location" -> shape(name, b, in, spawnId(b, entry, "shape"));
                default -> throw new IllegalArgumentException(name + ": particle_spawn has no " + entry.get("module"));
            }
        }
        CgVfxFormCompiler forms = new CgVfxFormCompiler(new CgVfxCurveBlock(), id -> null);
        int stacked = 0;
        for (Map<?, ?> entry : entries(tree, "particle_update")) {
            if (Boolean.FALSE.equals(entry.get("enabled"))) continue;
            String id = moduleId(entry);
            switch (id) {   // over life by age, and the grid's density: the builder's fields, not modules
                case "scale_sprite_size" -> {
                    Object scale = inputs(entry).get("scale");
                    if (scale instanceof Map<?, ?> m && m.get("curves") != null) {
                        CgKeyframes[] xy = axes(name, scale, 2);
                        b.scaleOverLife(xy[0], xy[1], CgKeyframes.constant(1f));
                    } else {
                        b.size(keyframes(name, uniform(scale), "age"));
                    }
                    continue;
                }
                case "scale_mesh_size" -> {
                    CgKeyframes[] xyz = axes(name, inputs(entry).get("scale"), 3);
                    b.scaleOverLife(xyz[0], xyz[1], xyz[2]);
                    continue;
                }
                case "scale_color" -> {
                    Map<?, ?> in = inputs(entry);
                    if (in.get("scale") != null) {
                        CgKeyframes[] rgb = axes(name, in.get("scale"), 3);
                        b.colorOverLife(rgb[0], rgb[1], rgb[2]);
                    }
                    if (in.get("alpha") != null) b.opacity(keyframes(name, in.get("alpha"), "age"));
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
        for (CgVfxRendererProperties r : CgVfxRenderers.compileAll(name, tree.get("renderers"), deps)) b.renderer(r);
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

    private static void initialize(String name, CgVfxEmitter.Builder b, Map<?, ?> in, String element) {
        CgVfxSpawnForm life = spawnForm(CgVfxSpawnInput.LIFETIME, element, "lifetime", in.get("lifetime"));
        float[] lives = life != null ? life.bounds() : rangeOf(in.get("lifetime"));
        b.life(lives[0], lives[1]);
        if (life != null) b.spawnForm(life);
        if (in.get("heat") instanceof Number h) b.heat(h.floatValue());
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
        if (in.get("color") != null) {
            float[][] c = ends(in.get("color"), 4);
            b.colors(c[0], c[1]);
        }
        if (in.get("mesh_scale") != null) {
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
                s.spread = num(in.get("cone_angle")) / 2f;
                s.innerSpread = in.get("inner_cone") instanceof Number n ? n.floatValue() / 2f : 0f;
            }
            case "linear" -> {
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
                if (in.get("cylinder_axis") != null && !"z".equals(in.get("cylinder_axis"))) {
                    throw new IllegalArgumentException(name + ": a cylinder along " + in.get("cylinder_axis") + " is not read yet: turn it");
                }
                s.kind = CgVfxSpawnShape.CYLINDER;
                s.b = radius(b, element, "cylinder_radius", in.get("cylinder_radius"));
                s.c = num(in.get("cylinder_height")) / 2f;
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
        if (r instanceof List<?> q && q.size() == 3 && q.stream().allMatch(v -> v instanceof Number n && n.doubleValue() == 0.0)) r = null;
        if (r != null && !(r instanceof List<?> q4 && q4.size() == 4)) {
            throw new IllegalArgumentException(name + ": shape_location's rotation " + r + " is not read yet: a quaternion (x, y, z, w)");
        }
        s.turn = r == null ? new float[]{0f, 0f, 0f, 1f} : vec(r, 4);
        s.place = in.get("offset") != null ? vec(in.get("offset"), 3) : new float[3];
    }

    /** A radius, or one in a form (a sweep's is a curve by the loop's seconds, aaa-design 2.60): its first end. */
    private static float radius(CgVfxEmitter.Builder b, String element, String input, Object value) {
        CgVfxSpawnForm formed = spawnForm(CgVfxSpawnInput.RADIUS, element, input, value);
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
                    if (p.name().equals(key) && p.defaultValue() != null) fallback = CgUnits.UNREAL.toHost(p.defaultValue(), p.quantity());
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
        return keyframes(name, CgRichCurve.decode(form.get("curve")), form.get("curve"));
    }

    private static CgKeyframes keyframes(String name, CgRichCurve c, Object tree) {
        if (tree instanceof Map<?, ?> m && (m.get("samples") != null || isExtrapolated(m.get("pre")) || isExtrapolated(m.get("post")))) {
            throw new IllegalArgumentException(name + ": a sampled or extrapolated curve is not read yet here");
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
                in.put(p.name(), CgUnits.UNREAL.toHost(p.defaultValue(), p.quantity()));
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
