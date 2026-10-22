package com.crystalgraphics.vfx.system;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.vfx.effect.beam.CgEnergyWave;
import com.crystalgraphics.vfx.look.CgVfxKit;
import com.crystalgraphics.vfx.look.CgVfxLayout;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.look.CgVfxUnit;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterAssets;
import com.crystalgraphics.vfx.particle.CgVfxEventHandler;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.render.CgVfxSlotRenderer;

/**
 * The {@code system} kind's compiler and writer: a system asset to the {@link CgVfxSystemDefinition} a
 * {@link CgVfxSystemInstance} plays, as Niagara compiles a {@code UNiagaraSystem}, and a definition back to its file.
 * Its {@code user} parameters become its schema, each emitter handle an emitter its instances play, and {@code effect:}
 * the behaviour registered under that id. A behaviour registered with a contract ({@link CgVfxBehaviour.Contract})
 * draws with its contract's look: the file's {@code user} values are set on it by name, and a name the contract
 * requires that the file lacks fails.
 *
 * <pre>{@code
 * CgVfxSystemAssets.effect("mymod:pulse", Pulse::new);                    // at init, before a system names it
 * CgVfxSystemAssets.effect("mymod:wave", Wave::new, Wave.CONTRACT);      // drawn with its own look, set by the file
 * CgVfxSystemAssets.register();                                          // the pipeline compiles systems so
 * CgAssetRef<CgVfxSystemDefinition> NOVA = CgVfxAssets.ref(CgParticleKinds.SYSTEM, "novamod:nova_cannon/charge");
 * VFX.play(new CgVfxSystemInstance<>(NOVA.get(), x, y, z));
 * }</pre>
 *
 * <p>A definition made in code, written as a file a tool can open:</p>
 * <pre>{@code
 * Map<String, Object> tree = CgVfxSystemAssets.write(PUFF);     // effect, user, emitters inline
 * Files.write(path, CgAssetYaml.write(tree, CgParticleKinds.SYSTEM).getBytes(StandardCharsets.UTF_8));
 * }</pre>
 *
 * <ul>
 *   <li>The engine's own behaviour, {@code crystalgraphics:energy_wave} ({@link CgEnergyWave}), is registered here.</li>
 *   <li>An input the asset leaves out takes the default {@code modules.yaml} declares; with none declared it fails
 *       naming the emitter, as do a module the reader does not know and an {@code effect} nothing registered.</li>
 *   <li>Renderers are not layers yet: a compiled system simulates its emitters and draws none of them.</li>
 *   <li>Runs on a worker: reads only the asset and its dependencies.</li>
 *   <li>{@link #write} writes a contracted system's values only where they differ from its contract's look, and its
 *       contract's required names always: the file is the delta. Emitters are written inline. Curves, palettes and
 *       shakes are not user parameters yet, and are not written.</li>
 * </ul>
 */
public final class CgVfxSystemAssets {

    /** A registered behaviour: its factory, and its contract when files play it with its look. */
    private record Effect(CgVfxBehaviour.Factory factory, @Nullable CgVfxBehaviour.Contract contract) {
    }

    private static final Map<String, Effect> EFFECTS = new ConcurrentHashMap<>();

    static {
        effect("crystalgraphics:energy_wave", CgEnergyWave::new, CgEnergyWave.CONTRACT);
    }

    /** The system kind's compiler. */
    public static final CgAssetCompiler<CgVfxSystemDefinition> COMPILER = (asset, deps) -> compile(asset, deps);

    private CgVfxSystemAssets() {
    }

    /**
     * Sets {@link #COMPILER} as the system kind's, and the emitter kind's: an emitter file compiles against its own
     * hard references (its renderers' materials), which a system referencing it does not hold.
     */
    public static void register() {
        CgAssetCompiler.register(CgParticleKinds.SYSTEM, COMPILER);
        CgAssetCompiler.register(CgParticleKinds.EMITTER, CgVfxEmitterAssets.compiler(id -> null));
    }

    /** Names {@code factory} as the behaviour a system's {@code effect: id} plays with, drawing the file's look. At init. */
    public static void effect(String id, CgVfxBehaviour.Factory factory) {
        EFFECTS.put(id, new Effect(factory, null));
    }

    /**
     * Names {@code factory} as the behaviour a system's {@code effect: id} plays with, drawing {@code contract}'s look with
     * the file's values set on it by name. At init.
     */
    public static void effect(String id, CgVfxBehaviour.Factory factory, CgVfxBehaviour.Contract contract) {
        EFFECTS.put(id, new Effect(factory, contract));
    }

    /**
     * {@code definition} as a system file's tree: its {@code effect}, its {@code user} parameters and its emitters inline.
     * Compiling it gives a definition that writes the same tree.
     */
    public static Map<String, Object> write(CgVfxSystemDefinition definition) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("_v", 1);
        String effectId = null;
        Effect effect = null;
        if (definition.behaviour() != null) {
            for (Map.Entry<String, Effect> e : EFFECTS.entrySet()) {
                if (e.getValue().factory() == definition.behaviour()) {
                    effectId = e.getKey();
                    effect = e.getValue();
                }
            }
            if (effectId == null) throw new IllegalArgumentException(definition.name() + "'s behaviour has no effect id registered");
        }
        CgVfxLook look = definition.look();
        CgVfxLayout layout = look.layout();
        CgVfxBehaviour.Contract contract = effect == null ? null : effect.contract();
        Map<String, Object> user = new LinkedHashMap<>();
        for (CgVfxParam p : layout.params()) {
            boolean own = p.schema().name().equals(definition.name());
            if (!(p instanceof CgVfxParam.Scalar || p instanceof CgVfxParam.Vector || p instanceof CgVfxParam.Color)) continue;
            String name = layout.qualifiedName(p);
            if (!own) {
                if (contract == null) continue;
                if (!contract.required().contains(name) && same(look, contract.look(), p)) continue;
            }
            user.put(name, userEntry(look, p));
        }
        if (!user.isEmpty()) tree.put("user", user);
        List<Object> handles = new ArrayList<>();
        Map<CgVfxEmitter, Map<String, Object>> trees = new IdentityHashMap<>();
        Map<CgVfxEmitter, String> ids = new IdentityHashMap<>();
        for (CgVfxEmitter e : definition.emitters()) {
            Map<String, Object> handle = new LinkedHashMap<>();
            String id = e.name().replaceAll("[^a-z0-9_]", "_");
            handle.put("id", id);
            if (!id.equals(e.name())) handle.put("name", e.name());
            Map<String, Object> written = CgVfxEmitterAssets.write(e);
            written.remove("events");
            handle.put("emitter", written);
            handles.add(handle);
            trees.put(e, written);
            ids.put(e, id);
        }
        // Each event as a handler on the emitter it spawns (FNiagaraEventScriptProperties), its source by handle.
        int handlers = 0;
        for (CgVfxEmitter source : definition.emitters()) {
            for (CgVfxEvent event : source.ownEvents()) {
                if (event.child() == null) {
                    throw new IllegalArgumentException(definition.name() + ": " + source.name() + "'s " + event.trigger()
                            + " event spawns nothing: rows to the CPU are Java's alone");
                }
                Map<String, Object> child = trees.get((CgVfxEmitter) event.child());
                if (child == null) {
                    throw new IllegalArgumentException(definition.name() + ": " + source.name() + " spawns "
                            + event.child().name() + ", which is not one of its emitters");
                }
                CgVfxEventHandler h = CgVfxEventHandler.of("e" + ++handlers, ids.get(source), ids.get((CgVfxEmitter) event.child()), event);
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) child.computeIfAbsent("events", k -> new ArrayList<>());
                list.add(h.tree());
            }
        }
        if (!handles.isEmpty() || effectId == null) tree.put("emitters", handles);
        if (effectId != null) tree.put("effect", effectId);
        // Its own slot renderers: what it adds over its behaviour's look, which a file names by its effect alone.
        List<Object> slots = new ArrayList<>();
        List<CgVfxLook.Placed> inherited = contract == null ? List.of() : contract.look().slotted();
        for (CgVfxLook.Placed placed : look.slotted()) {
            boolean theirs = false;
            for (CgVfxLook.Placed c : inherited) theirs |= c.slotRenderer() == placed.slotRenderer();
            if (!theirs) slots.add(placed.slotRenderer().tree());
        }
        if (!slots.isEmpty()) tree.put("slots", slots);
        return tree;
    }

    /** Whether {@code look} holds {@code p} as {@code base} does. */
    private static boolean same(CgVfxLook look, CgVfxLook base, CgVfxParam p) {
        if (p instanceof CgVfxParam.Scalar s) return Float.compare(look.get(s), base.get(s)) == 0;
        int n = p instanceof CgVfxParam.Vector ? 3 : 4;
        for (int i = 0; i < n; i++) {
            float a = p instanceof CgVfxParam.Vector v ? look.get(v, i) : look.get((CgVfxParam.Color) p, i);
            float b = p instanceof CgVfxParam.Vector v ? base.get(v, i) : base.get((CgVfxParam.Color) p, i);
            if (Float.compare(a, b) != 0) return false;
        }
        return true;
    }

    /** One {@code user} entry: its type, quantity where it is a length, and its value in {@code look}. */
    private static Map<String, Object> userEntry(CgVfxLook look, CgVfxParam p) {
        Map<String, Object> entry = new LinkedHashMap<>();
        if (p instanceof CgVfxParam.Scalar s) {
            entry.put("type", "float");
            if (s.unit() == CgVfxUnit.LENGTH) entry.put("quantity", "length");
            entry.put("default", (double) look.get(s));
        } else if (p instanceof CgVfxParam.Vector v) {
            entry.put("type", "vec3");
            if (!Float.isNaN(look.get(v, 0))) {
                entry.put("default", Arrays.asList((double) look.get(v, 0), (double) look.get(v, 1), (double) look.get(v, 2)));
            }
        } else {
            CgVfxParam.Color c = (CgVfxParam.Color) p;
            float r = look.get(c, 0), g = look.get(c, 1), b = look.get(c, 2), a = look.get(c, 3);
            float intensity = Math.max(1f, Math.max(r, Math.max(g, b)));
            long argb = (long) Math.round(Math.min(1f, a) * 255f) << 24 | Math.round(r / intensity * 255f) << 16
                    | Math.round(g / intensity * 255f) << 8 | Math.round(b / intensity * 255f);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("argb", argb);
            if (intensity != 1f) value.put("intensity", (double) intensity);
            entry.put("type", "colour");
            entry.put("default", value);
        }
        return entry;
    }

    /** {@code asset}'s definition; {@code deps} answers its referenced emitters. */
    public static CgVfxSystemDefinition compile(CgAssetData asset, CgAssetCompiler.Dependencies deps) {
        String id = asset.id();
        Map<Object, Object> tree = asset.resolved();
        Map<?, ?> user = tree.get("user") instanceof Map<?, ?> u ? u : Map.of();
        List<CgVfxEmitter> emitters = new ArrayList<>();
        CgVfxKit.Builder kit = CgVfxKit.of(id);
        boolean slotted = false;
        if (tree.get("slots") instanceof List<?> entries) {
            for (Object o : entries) {
                kit.slot(CgVfxSlotRenderer.compile(id, (Map<?, ?>) o, deps));
                slotted = true;
            }
        }
        if (tree.get("emitters") instanceof List<?> handles) {
            Map<String, CgVfxEmitter> base = new LinkedHashMap<>();
            for (Object o : handles) {
                Map<?, ?> h = (Map<?, ?>) o;
                if (Boolean.FALSE.equals(h.get("enabled"))) continue;
                base.put(String.valueOf(h.get("id")), emitter(id, h, deps));
            }
            Map<String, CgVfxEmitter> done = new HashMap<>();
            for (String handle : base.keySet()) {
                CgVfxEmitter e = resolve(id, handle, base, done, new ArrayList<>());
                kit.emitter(e);
                emitters.add(e);
            }
        }
        Effect effect = null;
        if (tree.get("effect") instanceof String name) {
            effect = EFFECTS.get(name);
            if (effect == null) throw new IllegalArgumentException(id + ": effect " + name + " has no behaviour registered");
            if (effect.contract() != null) {
                CgVfxLook look = contracted(id, name, effect.contract(), user, emitters.isEmpty() && !slotted ? null : kit.build());
                return new CgVfxSystemDefinition(id, look, effect.factory(), emitters);
            }
        }
        CgVfxSchema schema = CgVfxSchema.of(id);
        for (Map.Entry<?, ?> e : user.entrySet()) param(id, schema, String.valueOf(e.getKey()), (Map<?, ?>) e.getValue());
        CgVfxLook look = CgVfxLook.builder(schema).with(kit.build()).build();
        return new CgVfxSystemDefinition(id, look, effect == null ? null : effect.factory(), emitters);
    }

    /**
     * {@code contract}'s look with the file's values set on its parameters of the same names; a value it has no parameter
     * for joins a schema of the system's own. A required name the file lacks fails, naming it.
     */
    private static CgVfxLook contracted(String id, String effect, CgVfxBehaviour.Contract contract, Map<?, ?> user,
                                        @Nullable CgVfxKit emitters) {
        for (String name : contract.required()) {
            if (!user.containsKey(name)) {
                throw new IllegalArgumentException(id + ": user." + name + " is missing: " + effect + " reads it");
            }
        }
        CgVfxLook.Builder look = contract.look().derive();
        CgVfxSchema own = CgVfxSchema.of(id);
        boolean owns = false;
        for (Map.Entry<?, ?> e : user.entrySet()) {
            String name = String.valueOf(e.getKey());
            Map<?, ?> p = (Map<?, ?>) e.getValue();
            CgVfxParam target = contract.look().layout().param(name);
            if (target == null) {
                param(id, own, name, p);
                owns = true;
            } else {
                set(id, effect, look, target, name, p);
            }
        }
        if (owns) look.params(own);
        if (emitters != null) look.with(emitters);
        return look.build();
    }

    /** The file's default for {@code name} set on {@code target}, of the same kind. */
    private static void set(String id, String effect, CgVfxLook.Builder look, CgVfxParam target, String name, Map<?, ?> p) {
        String type = String.valueOf(p.get("type"));
        Object v = p.get("default");
        if (target instanceof CgVfxParam.Scalar scalar && ("float".equals(type) || "int".equals(type))) {
            look.set(scalar, v instanceof Number n ? n.floatValue() : 0f);
        } else if (target instanceof CgVfxParam.Vector vector && "vec3".equals(type)) {
            float[] xyz = vec3(id, name, v);
            look.set(vector, xyz[0], xyz[1], xyz[2]);
        } else if (target instanceof CgVfxParam.Color color && "colour".equals(type)) {
            Map<?, ?> c = v instanceof Map<?, ?> m ? m : Map.of();
            look.set(color, c.get("argb") instanceof Number n ? (int) n.longValue() : 0xFFFFFFFF,
                    c.get("intensity") instanceof Number n ? n.floatValue() : 1f);
        } else {
            throw new IllegalArgumentException(id + ": user." + name + " is a " + type + ", where " + effect + " reads "
                    + target);
        }
    }

    /**
     * Handle {@code handle}'s emitter with the events its system's handlers put on it, each spawning its handle resolved
     * the same way first: an emitter is final once its children are. A loop of spawns fails, naming it.
     */
    private static CgVfxEmitter resolve(String system, String handle, Map<String, CgVfxEmitter> base,
                                        Map<String, CgVfxEmitter> done, List<String> path) {
        CgVfxEmitter ready = done.get(handle);
        if (ready != null) return ready;
        if (path.contains(handle)) {
            throw new IllegalArgumentException(system + ": events spawn in a loop: " + String.join(" -> ", path) + " -> " + handle);
        }
        path.add(handle);
        CgVfxEmitter.Builder b = null;
        for (Map.Entry<String, CgVfxEmitter> receiver : base.entrySet()) {
            for (CgVfxEventHandler h : receiver.getValue().handlers()) {
                String at = system + ": emitters." + receiver.getKey() + " events." + h.id();
                if (!base.containsKey(h.source())) throw new IllegalArgumentException(at + ": its source " + h.source() + " is no enabled handle");
                if (!h.source().equals(handle)) continue;
                if (!base.containsKey(h.spawn())) throw new IllegalArgumentException(at + ": it spawns " + h.spawn() + ", no enabled handle");
                CgVfxEmitter child = resolve(system, h.spawn(), base, done, path);
                if (b == null) b = base.get(handle).toBuilder();
                try {
                    b.event(h.compile(child));
                } catch (RuntimeException failed) {
                    throw new IllegalArgumentException(at + ": " + failed.getMessage(), failed);
                }
            }
        }
        path.remove(path.size() - 1);
        CgVfxEmitter out = b == null ? base.get(handle) : b.build();
        done.put(handle, out);
        return out;
    }

    private static CgVfxEmitter emitter(String system, Map<?, ?> handle, CgAssetCompiler.Dependencies deps) {
        String name = String.valueOf(handle.get("name") != null ? handle.get("name") : handle.get("id"));
        String at = system + ": emitters." + handle.get("id") + " (" + name + ")";
        Object e = handle.get("emitter");
        Map<?, ?> tree;
        if (e instanceof String ref) {
            Object d = deps.definition(CgParticleKinds.EMITTER, ref);
            if (d instanceof CgVfxEmitter compiled) return compiled.toBuilder().name(name).build();
            if (!(d instanceof CgAssetData data)) throw new IllegalArgumentException(at + ": emitter " + ref + " did not compile");
            tree = data.resolved();
        } else if (e instanceof Map<?, ?> inline) {
            tree = inline;
        } else {
            throw new IllegalArgumentException(at + ": no emitter");
        }
        try {
            return CgVfxEmitterAssets.compile(name, tree, null, deps);
        } catch (RuntimeException failed) {
            throw new IllegalArgumentException(at + ": " + failed.getMessage(), failed);
        }
    }

    private static void param(String system, CgVfxSchema schema, String name, Map<?, ?> p) {
        Object type = p.get("type"), v = p.get("default");
        switch (String.valueOf(type)) {
            case "float":
            case "int": {
                float f = v instanceof Number n ? n.floatValue() : 0f;
                if ("length".equals(p.get("quantity"))) schema.length(name, f);
                else schema.scalar(name, f);
                break;
            }
            case "colour": {
                Map<?, ?> c = v instanceof Map<?, ?> m ? m : Map.of();
                int argb = c.get("argb") instanceof Number n ? (int) n.longValue() : 0xFFFFFFFF;
                float intensity = c.get("intensity") instanceof Number n ? n.floatValue() : 1f;
                schema.color(name, argb, intensity);
                break;
            }
            case "vec3": {
                float[] xyz = vec3(system, name, v);
                schema.vector(name, xyz[0], xyz[1], xyz[2]);
                break;
            }
            default:
                throw new IllegalArgumentException(system + ": user." + name + " is a " + type + ", which systems cannot hold yet");
        }
    }

    /** A {@code vec3}'s default, {@code [x, y, z]}; absent, all three NaN (none). */
    private static float[] vec3(String system, String name, @Nullable Object v) {
        if (v == null) return new float[] {Float.NaN, Float.NaN, Float.NaN};
        if (!(v instanceof List<?> list) || list.size() != 3) {
            throw new IllegalArgumentException(system + ": user." + name + "'s default is " + v + ", where a vec3 is [x, y, z]");
        }
        float[] xyz = new float[3];
        for (int i = 0; i < 3; i++) xyz[i] = list.get(i) instanceof Number n ? n.floatValue() : Float.NaN;
        return xyz;
    }

    @Nullable
    static CgVfxBehaviour.Factory effect(String id) {
        Effect effect = EFFECTS.get(id);
        return effect == null ? null : effect.factory();
    }
}
