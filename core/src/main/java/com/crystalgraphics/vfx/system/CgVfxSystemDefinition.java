package com.crystalgraphics.vfx.system;

import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * What a system plays: its name, its look (layers and parameters), the emitters its instances play, and its native
 * behaviour, if any. Unreal's {@code UNiagaraSystem} as it compiles; a system with no behaviour plays its emitters once
 * and completes.
 *
 * <pre>{@code
 * static final CgVfxSystemDefinition PUFF = CgVfxSystemDefinition.of("mymod:puff",
 *         CgVfxLook.builder(CgVfxSystemDefinition.SCHEMA).with(PUFF_KIT).build());
 * VFX.play(new CgVfxSystemInstance<>(PUFF, x, y, z));
 *
 * static final CgVfxSystemDefinition BEAM = CgVfxSystemDefinition.of("mymod:beam", BEAM_LOOK).withBehaviour(Beam::new);
 * }</pre>
 *
 * <p>A behaviour that plays emitters of its own (through the elements it builds) names none for its instance to play,
 * so they are not played twice:</p>
 *
 * <pre>{@code
 * static final CgVfxSystemDefinition WAVE = new CgVfxSystemDefinition("mymod:wave", WAVE_LOOK, Wave::new, List.of());
 * }</pre>
 *
 * <p>Its instances are pooled per definition by the system that plays them, as Unreal's {@code UNiagaraComponentPool}
 * pools a system's components: {@code poolPrimeSize} made ahead when it loads, at most {@code maxPoolSize} kept once
 * finished (Unreal's {@code PoolPrimeSize} and {@code MaxPoolSize}):</p>
 *
 * <pre>{@code
 * static final CgVfxSystemDefinition HIT = CgVfxSystemDefinition.of("mymod:hit", HIT_LOOK).withPool(4, 16);
 * }</pre>
 *
 * <ul>
 *   <li>Held by identity: it is the simulation's key, so make each once, as a constant.</li>
 *   <li>{@link #of(String, CgVfxLook)} plays every emitter of the look; {@code withBehaviour} keeps them.</li>
 * </ul>
 *
 * @param name      what tools and logs call it
 * @param look      its layers and parameters, and the emitters they draw
 * @param behaviour its native behaviour's factory, or null for none
 * @param emitters  what each instance plays from its origin
 * @param poolPrimeSize instances made ahead when it loads; its system primes at least one
 * @param maxPoolSize   finished instances kept for reuse; one past it is freed
 */
public record CgVfxSystemDefinition(String name, CgVfxLook look, @Nullable CgVfxBehaviour.Factory behaviour,
                                    List<CgVfxEmitter> emitters, int poolPrimeSize, int maxPoolSize) {

    /** Unreal's {@code UNiagaraSystem} defaults: none made ahead, 32 kept. */
    public static final int POOL_PRIME_SIZE = 0, MAX_POOL_SIZE = 32;

    /** A system with no parameters of its own reads none: its kits' layers read theirs. */
    public static final CgVfxSchema SCHEMA = CgVfxSchema.of("crystalgraphics:system");

    public CgVfxSystemDefinition {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("a system needs a name");
        if (look == null) throw new IllegalArgumentException(name + " needs a look");
        emitters = List.copyOf(emitters);
        for (int i = 0; i < emitters.size(); i++) {
            if (emitters.get(i).spawnFrom() != null) sourceOf(name, emitters, i);
        }
        if (poolPrimeSize < 0 || maxPoolSize < 0) throw new IllegalArgumentException(name + ": a pool size is negative");
    }

    /**
     * Where emitter {@code reader}'s source is among {@code emitters}: the place its file resolved, else the emitter of
     * its source's name. Fails naming a source it lacks, the reader itself, or one that does not publish.
     */
    static int sourceOf(String system, List<CgVfxEmitter> emitters, int reader) {
        CgVfxEmitter e = emitters.get(reader);
        String source = e.spawnFrom().source();
        int at = e.spawnFrom().sourceIndex();
        for (int i = 0; at < 0 && i < emitters.size(); i++) if (emitters.get(i).name().equals(source)) at = i;
        String why = at < 0 || at >= emitters.size() ? "no emitter of the system" : at == reader ? "itself"
                : !emitters.get(at).publishes() ? "which does not publish: build it with publishes(true)" : null;
        if (why != null) throw new IllegalArgumentException(system + ": " + e.name() + " is born on " + source + ", " + why);
        return at;
    }

    /** With Unreal's pool sizes ({@link #POOL_PRIME_SIZE}, {@link #MAX_POOL_SIZE}). */
    public CgVfxSystemDefinition(String name, CgVfxLook look, @Nullable CgVfxBehaviour.Factory behaviour,
                                 List<CgVfxEmitter> emitters) {
        this(name, look, behaviour, emitters, POOL_PRIME_SIZE, MAX_POOL_SIZE);
    }

    /** {@code look}'s emitters played by every instance. */
    public CgVfxSystemDefinition(String name, CgVfxLook look, @Nullable CgVfxBehaviour.Factory behaviour) {
        this(name, look, behaviour, look.emitters());
    }

    /** {@code look}'s emitters played once, with no behaviour. */
    public static CgVfxSystemDefinition of(String name, CgVfxLook look) {
        return new CgVfxSystemDefinition(name, look, null);
    }

    /** Each look's burst definition, made once: a definition is its simulation's key. Held weakly, as it holds the look. */
    private static final Map<CgVfxLook, WeakReference<CgVfxSystemDefinition>> BURSTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** {@code look}'s emitters played once, with no behaviour: the same definition for the same look. */
    public static CgVfxSystemDefinition of(CgVfxLook look) {
        synchronized (BURSTS) {
            WeakReference<CgVfxSystemDefinition> ref = BURSTS.get(look);
            CgVfxSystemDefinition known = ref != null ? ref.get() : null;
            if (known != null) return known;
            CgVfxSystemDefinition made = new CgVfxSystemDefinition("crystalgraphics:burst", look, null);
            BURSTS.put(look, new WeakReference<>(made));
            return made;
        }
    }

    /** This definition carrying {@code behaviour}, its emitters kept. */
    public CgVfxSystemDefinition withBehaviour(CgVfxBehaviour.Factory behaviour) {
        return new CgVfxSystemDefinition(name, look, behaviour, emitters, poolPrimeSize, maxPoolSize);
    }

    /** This definition with its instance pool's sizes: {@code prime} made ahead when it loads, at most {@code max} kept. */
    public CgVfxSystemDefinition withPool(int prime, int max) {
        return new CgVfxSystemDefinition(name, look, behaviour, emitters, prime, max);
    }
}
