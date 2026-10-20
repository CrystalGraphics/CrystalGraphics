package com.crystalgraphics.vfx.particle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.shape.primitive.CgSphere;
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
 * <p>What the asset carries: {@code properties} ({@code capacity}, {@code local_space}); {@code emitter_update}'s
 * {@code emitter_state}, {@code spawn_rate}, {@code spawn_burst}s and {@code spawn_per_unit}; {@code particle_spawn}'s
 * {@code initialize_particle} (lifetime, heat) and {@code add_velocity} (speed, inherit); and every
 * {@code particle_update} module, by {@link CgVfxModuleTypes}.</p>
 *
 * <ul>
 *   <li>Everything else (the spawn shape, launch, size, spin, the curves and colours over life, events, the renderer,
 *       trails, a clip, bindings, a spawn rate's scale curve) still comes from {@code template}, the code-built emitter
 *       of the same id. A {@code null} template starts from the builder's defaults.</li>
 *   <li>Physics modules write their engine ids ({@code crystalgraphics:<gpu kind>}) and the inputs
 *       {@link CgVfxModuleTypes} declares; where {@code modules.yaml} declares none the bible gate reports them.</li>
 *   <li>A range is {@code { range: [min, max] }}, a constant where both ends agree.</li>
 * </ul>
 */
public final class CgVfxEmitterAssets {

    private static final String NS = "crystalgraphics:";

    private CgVfxEmitterAssets() {
    }

    /** {@code e}'s asset tree: {@code _v}, {@code properties} and its stacks. */
    public static Map<String, Object> write(CgVfxEmitter e) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("_v", 1);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("capacity", e.capacity);
        props.put("local_space", e.local);
        tree.put("properties", props);

        List<Object> update = new ArrayList<>();
        update.add(entry("state", "emitter_state", state(e.state)));
        if (e.rate.perSecond() > 0f) update.add(entry("rate", "spawn_rate", map("rate", (double) e.rate.perSecond())));
        for (int i = 0; i < e.bursts.length; i++) {
            CgVfxSpawnBurst b = e.bursts[i];
            update.add(entry("burst_" + i, "spawn_burst", map("time", (double) b.time(), "count", range(b.countMin(), b.countMax()),
                    "probability", (double) b.probability())));
        }
        if (e.perUnit.perBlock() > 0f) {
            update.add(entry("per_unit", "spawn_per_unit", map("per_unit", (double) e.perUnit.perBlock(), "max_step", (double) e.maxStep)));
        }
        tree.put("emitter_update", update);

        List<Object> spawn = new ArrayList<>();
        spawn.add(entry("init", "initialize_particle", map("lifetime", range(e.lifeMin, e.lifeMax), "heat", (double) e.heat)));
        spawn.add(entry("velocity", "add_velocity", map("speed", range(e.speedMin, e.speedMax), "inherit", (double) e.inherit)));
        tree.put("particle_spawn", spawn);

        List<Object> modules = new ArrayList<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (CgVfxModule m : e.modules) {
            CgVfxModuleTypes.Kind<CgVfxModule> kind = CgVfxModuleTypes.of(m);
            int n = seen.merge(kind.id(), 1, Integer::sum);
            modules.add(entry(n == 1 ? kind.id() : kind.id() + "_" + n, kind.id(), kind.inputs(m)));
        }
        tree.put("particle_update", modules);
        return tree;
    }

    /** The emitter {@code tree} declares, named {@code name}, over {@code template}'s fields the asset does not carry. */
    public static CgVfxEmitter compile(String name, Map<?, ?> tree, @Nullable CgVfxEmitter template) {
        CgVfxEmitter.Builder b = template != null ? template.toBuilder().name(name) : CgVfxEmitter.builder(name);
        b.clearBursts().clearModules().rate(CgVfxSpawnRate.NONE).ratePerBlock(0f);
        Map<?, ?> props = tree.get("properties") instanceof Map<?, ?> p ? p : Map.of();
        if (props.get("capacity") instanceof Number c) b.capacity(c.intValue());
        b.local(Boolean.TRUE.equals(props.get("local_space")));

        for (Map<?, ?> entry : entries(tree, "emitter_update")) {
            Map<?, ?> in = inputs(entry);
            switch (moduleId(entry)) {
                case "emitter_state":
                    b.state(state(in));
                    break;
                case "spawn_rate": {
                    float rate = num(in.get("rate"));
                    b.rate(template != null && template.rate.perSecond() == rate ? template.rate : CgVfxSpawnRate.of(rate));
                    break;
                }
                case "spawn_burst": {
                    float[] count = rangeOf(in.get("count"));
                    float p = in.get("probability") instanceof Number n ? n.floatValue() : 1f;
                    b.burst(CgVfxSpawnBurst.at(num(in.get("time")), (int) count[0], (int) count[1]).probability(p));
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
                case "initialize_particle": {
                    float[] life = rangeOf(in.get("lifetime"));
                    b.life(life[0], life[1]);
                    if (in.get("heat") instanceof Number h) b.heat(h.floatValue());
                    if (in.get("sprite_size") != null) {
                        float[] size = rangeOf(in.get("sprite_size"));
                        b.size(size[0], size[1]);
                    }
                    break;
                }
                case "add_velocity": {
                    float[] speed = rangeOf(in.get("speed"));
                    b.speed(speed[0], speed[1]);
                    if (in.get("inherit") instanceof Number i) b.inherit(i.floatValue());
                    if ("inward".equals(in.get("mode"))) b.inward();
                    break;
                }
                case "shape_location": {
                    if (!"sphere".equals(in.get("shape"))) throw new IllegalArgumentException(name + ": shape_location's shape " + in.get("shape") + " is not read yet");
                    b.shape(CgSphere.of(num(in.get("sphere_radius"))));
                    if (Boolean.TRUE.equals(in.get("surface_only"))) b.surface();
                    break;
                }
                default:
                    throw new IllegalArgumentException(name + ": particle_spawn has no " + entry.get("module"));
            }
        }
        for (Map<?, ?> entry : entries(tree, "particle_update")) {
            if (Boolean.FALSE.equals(entry.get("enabled"))) continue;
            switch (moduleId(entry)) {   // over life by age: the builder's curves, not modules
                case "scale_sprite_size":
                    b.size(ageCurve(name, inputs(entry).get("scale")));
                    continue;
                case "scale_color":
                    b.opacity(ageCurve(name, inputs(entry).get("alpha")));
                    continue;
                default:
            }
            CgVfxModuleTypes.Kind<?> kind = CgVfxModuleTypes.kind(moduleId(entry));
            if (kind == null) throw new IllegalArgumentException(name + ": no module " + entry.get("module"));
            b.module(kind.compile(inputs(entry)));
        }
        return b.build();
    }

    /** The emitter kind's compiler: each asset over the code-built emitter {@code templates} answers for its id. */
    public static CgAssetCompiler<CgVfxEmitter> compiler(Function<String, CgVfxEmitter> templates) {
        return (asset, deps) -> {
            String id = asset.id();
            return compile(id.substring(id.lastIndexOf('/') + 1), asset.resolved(), templates.apply(id));
        };
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

    // ── The tree ─────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> entry(String id, String module, Map<String, Object> inputs) {
        return map("id", id, "module", NS + module, "inputs", inputs);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Object range(float min, float max) {
        return min == max ? (Object) (double) min : map("range", Arrays.asList((double) min, (double) max));
    }

    private static float[] rangeOf(Object value) {
        if (value instanceof Number n) return new float[]{n.floatValue(), n.floatValue()};
        if (value instanceof Map<?, ?> m && m.get("range") instanceof List<?> r) return new float[]{num(r.get(0)), num(r.get(1))};
        throw new IllegalArgumentException("a number or a range, not " + value);
    }

    /** {@code { curve: { keys: [[t, v], ...] }, by: age }}, linear between keys. */
    private static CgKeyframes ageCurve(String name, Object value) {
        if (!(value instanceof Map<?, ?> form) || !(form.get("curve") instanceof Map<?, ?> curve)
                || !(curve.get("keys") instanceof List<?> keys) || keys.isEmpty()) {
            throw new IllegalArgumentException(name + ": a curve over age, not " + value);
        }
        if (form.get("by") != null && !"age".equals(form.get("by"))) throw new IllegalArgumentException(name + ": a curve by " + form.get("by") + " is not read yet");
        List<?> first = (List<?>) keys.get(0);
        CgKeyframes.Builder k = CgKeyframes.start(num(first.get(0)), num(first.get(1)));
        for (int i = 1; i < keys.size(); i++) {
            List<?> key = (List<?>) keys.get(i);
            k.to(num(key.get(0)), num(key.get(1)), CgEasings.LINEAR);
        }
        return k.build();
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

    private static Map<?, ?> inputs(Map<?, ?> entry) {
        return entry.get("inputs") instanceof Map<?, ?> m ? m : Map.of();
    }

    private static String moduleId(Map<?, ?> entry) {
        String id = String.valueOf(entry.get("module"));
        return id.startsWith(NS) ? id.substring(NS.length()) : id;
    }
}
