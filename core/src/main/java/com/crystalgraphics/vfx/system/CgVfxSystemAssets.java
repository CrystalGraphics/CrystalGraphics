package com.crystalgraphics.vfx.system;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.vfx.look.CgVfxKit;
import com.crystalgraphics.vfx.effect.beam.CgEnergyWave;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterAssets;

/**
 * The {@code system} kind's compiler: a system asset to the {@link CgVfxSystemDefinition} a
 * {@link CgVfxSystemInstance} plays, as Niagara compiles a {@code UNiagaraSystem}. Its {@code user} parameters become
 * its schema, each emitter handle an emitter its instances play, and {@code effect:} the behaviour registered under
 * that id. A behaviour registered with a contract ({@link CgVfxBehaviour.Contract}) draws with its contract's look: the
 * file's {@code user} values are set on it by name, and a name the contract requires that the file lacks fails.
 *
 * <pre>{@code
 * CgVfxSystemAssets.effect("mymod:pulse", Pulse::new);                    // at init, before a system names it
 * CgVfxSystemAssets.effect("mymod:wave", Wave::new, Wave.CONTRACT);      // drawn with its own look, set by the file
 * CgVfxSystemAssets.register();                                          // the pipeline compiles systems so
 * CgAssetRef<CgVfxSystemDefinition> NOVA = CgVfxAssets.ref(CgParticleKinds.SYSTEM, "novamod:nova_cannon/charge");
 * VFX.play(new CgVfxSystemInstance<>(NOVA.get(), x, y, z));
 * }</pre>
 *
 * <ul>
 *   <li>The engine's own behaviour, {@code crystalgraphics:energy_wave} ({@link CgEnergyWave}), is registered here.</li>
 *   <li>An input the asset leaves out takes the default {@code modules.yaml} declares; with none declared it fails
 *       naming the emitter, as do a module the reader does not know and an {@code effect} nothing registered.</li>
 *   <li>Renderers are not layers yet: a compiled system simulates its emitters and draws none of them.</li>
 *   <li>Runs on a worker: reads only the asset and its dependencies.</li>
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

    /** Sets {@link #COMPILER} as the system kind's. */
    public static void register() {
        CgAssetCompiler.register(CgParticleKinds.SYSTEM, COMPILER);
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

    /** {@code asset}'s definition; {@code deps} answers its referenced emitters. */
    public static CgVfxSystemDefinition compile(CgAssetData asset, CgAssetCompiler.Dependencies deps) {
        String id = asset.id();
        Map<Object, Object> tree = asset.resolved();
        Map<?, ?> user = tree.get("user") instanceof Map<?, ?> u ? u : Map.of();
        List<CgVfxEmitter> emitters = new ArrayList<>();
        CgVfxKit.Builder kit = CgVfxKit.of(id);
        if (tree.get("emitters") instanceof List<?> handles) {
            for (Object o : handles) {
                Map<?, ?> h = (Map<?, ?>) o;
                if (Boolean.FALSE.equals(h.get("enabled"))) continue;
                CgVfxEmitter e = emitter(id, h, deps);
                kit.emitter(e);
                emitters.add(e);
            }
        }
        Effect effect = null;
        if (tree.get("effect") instanceof String name) {
            effect = EFFECTS.get(name);
            if (effect == null) throw new IllegalArgumentException(id + ": effect " + name + " has no behaviour registered");
            if (effect.contract() != null) {
                CgVfxLook look = contracted(id, name, effect.contract(), user, emitters.isEmpty() ? null : kit.build());
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
            return CgVfxEmitterAssets.compile(name, tree, null);
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
