package com.crystalgraphics.vfx.system;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.vfx.look.CgVfxKit;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterAssets;

/**
 * The {@code system} kind's compiler: a system asset to the {@link CgVfxSystemDefinition} a
 * {@link CgVfxSystemInstance} plays, as Niagara compiles a {@code UNiagaraSystem}. Its {@code user} parameters become
 * its schema, each emitter handle an emitter of its look, and {@code effect:} the behaviour registered under that id.
 *
 * <pre>{@code
 * CgVfxSystemAssets.effect("crystalgraphics:energy_wave", Wave::new);   // at init, before a system names it
 * CgVfxSystemAssets.register();                                         // the pipeline compiles systems so
 * CgAssetRef<CgVfxSystemDefinition> NOVA = CgVfxAssets.ref(CgParticleKinds.SYSTEM, "novamod:nova_cannon/charge");
 * VFX.play(new CgVfxSystemInstance(NOVA.get(), x, y, z));
 * }</pre>
 *
 * <ul>
 *   <li>An input the asset leaves out takes the default {@code modules.yaml} declares; with none declared it fails
 *       naming the emitter, as do a module the reader does not know and an {@code effect} nothing registered.</li>
 *   <li>Renderers are not layers yet: a compiled system simulates its emitters and draws none of them.</li>
 *   <li>Runs on a worker: reads only the asset and its dependencies.</li>
 * </ul>
 */
public final class CgVfxSystemAssets {

    private static final Map<String, CgVfxBehaviour.Factory> EFFECTS = new ConcurrentHashMap<>();

    /** The system kind's compiler. */
    public static final CgAssetCompiler<CgVfxSystemDefinition> COMPILER = (asset, deps) -> compile(asset, deps);

    private CgVfxSystemAssets() {
    }

    /** Sets {@link #COMPILER} as the system kind's. */
    public static void register() {
        CgAssetCompiler.register(CgParticleKinds.SYSTEM, COMPILER);
    }

    /** Names {@code factory} as the behaviour a system's {@code effect: id} plays with. At init. */
    public static void effect(String id, CgVfxBehaviour.Factory factory) {
        EFFECTS.put(id, factory);
    }

    /** {@code asset}'s definition; {@code deps} answers its referenced emitters. */
    public static CgVfxSystemDefinition compile(CgAssetData asset, CgAssetCompiler.Dependencies deps) {
        String id = asset.id();
        Map<Object, Object> tree = asset.resolved();
        CgVfxSchema schema = CgVfxSchema.of(id);
        if (tree.get("user") instanceof Map<?, ?> user) {
            for (Map.Entry<?, ?> e : user.entrySet()) param(id, schema, String.valueOf(e.getKey()), (Map<?, ?>) e.getValue());
        }
        CgVfxKit.Builder kit = CgVfxKit.of(id);
        if (tree.get("emitters") instanceof List<?> handles) {
            for (Object o : handles) {
                Map<?, ?> h = (Map<?, ?>) o;
                if (Boolean.FALSE.equals(h.get("enabled"))) continue;
                kit.emitter(emitter(id, h, deps));
            }
        }
        CgVfxLook look = CgVfxLook.builder(schema).with(kit.build()).build();
        CgVfxBehaviour.Factory behaviour = null;
        if (tree.get("effect") instanceof String effect) {
            behaviour = EFFECTS.get(effect);
            if (behaviour == null) throw new IllegalArgumentException(id + ": effect " + effect + " has no behaviour registered");
        }
        return new CgVfxSystemDefinition(id, look, behaviour);
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
            default:
                throw new IllegalArgumentException(system + ": user." + name + " is a " + type + ", which systems cannot hold yet");
        }
    }

    @Nullable
    static CgVfxBehaviour.Factory effect(String id) {
        return EFFECTS.get(id);
    }
}
