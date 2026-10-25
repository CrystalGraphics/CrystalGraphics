package com.crystalgraphics.vfx.system;

import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.renderer.CgVfxMacroUv;

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
 * @param kills         emitters whose particles end when another's event fires
 * @param fixedBounds   its bounds about its origin, or null where they follow its particles: what a material reading
 *                      the system's bounds ({@code CG_OBJECT_POSITION}, {@code CG_OBJECT_RADIUS}) reads
 * @param macroUv       where its renderers' particle macro UVs centre and how far they reach ({@code CG_PARTICLE_MACRO_UV});
 *                      null is Cascade's default
 */
public record CgVfxSystemDefinition(String name, CgVfxLook look, @Nullable CgVfxBehaviour.Factory behaviour,
                                    List<CgVfxEmitter> emitters, int poolPrimeSize, int maxPoolSize, List<Kill> kills,
                                    @Nullable FixedBounds fixedBounds, CgVfxMacroUv macroUv) {

    /**
     * A system's bounds fixed about its origin, in blocks: Unreal's fixed bounds (Niagara's {@code FixedBounds},
     * Cascade's {@code FixedRelativeBoundingBox}), whose centre and sphere a material reads as the primitive's
     * ({@code ObjectWorldPositionAndRadius}).
     *
     * <pre>{@code
     * CgVfxSystemDefinition ORB = CgVfxSystemDefinition.of("mymod:orb", ORB_LOOK)
     *         .withFixedBounds(new CgVfxSystemDefinition.FixedBounds(-4, -4, -4, 4, 4, 4));
     * }</pre>
     */
    public record FixedBounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {

        public FixedBounds {
            if (!(minX <= maxX && minY <= maxY && minZ <= maxZ)) {
                throw new IllegalArgumentException("fixed bounds' min passes their max: " + this);
            }
        }

        /** Its centre's offset from the system's origin, on {@code axis} 0 to 2. */
        public float centre(int axis) {
            return axis == 0 ? (minX + maxX) * 0.5f : axis == 1 ? (minY + maxY) * 0.5f : (minZ + maxZ) * 0.5f;
        }

        /** The radius of the sphere round it: half its diagonal, as Unreal's box-sphere bounds take. */
        public float radius() {
            float x = maxX - minX, y = maxY - minY, z = maxZ - minZ;
            return 0.5f * (float) Math.sqrt(x * x + y * y + z * z);
        }
    }

    /**
     * Emitter {@code receiver}'s particles all end when emitter {@code source}'s event {@code event} (its place in that
     * emitter's {@code events()}, read back) fires in the same instance: a file's {@code kill: true} handler, Cascade's
     * {@code EventReceiverKillParticles}.
     *
     * <pre>{@code
     * new CgVfxSystemDefinition("mymod:blast", look, null, List.of(dummy, light), 0, 32,
     *         List.of(new CgVfxSystemDefinition.Kill(0, 0, 1)));         // the light goes when the dummy's death fires
     * new CgVfxSystemDefinition.Kill(0, 0, 1, true);                       // and spawns no more: Cascade's bStopSpawning
     * }</pre>
     *
     * <ul>
     *   <li>The source's event must read its rows back ({@code CgVfxEvent.readback}); the kill lands a frame or two after
     *       the step that raised it.</li>
     * </ul>
     */
    public record Kill(int source, int event, int receiver, boolean stopSpawning) {

        /** Its particles killed, its spawning left running. */
        public Kill(int source, int event, int receiver) {
            this(source, event, receiver, false);
        }
    }

    /** Unreal's {@code UNiagaraSystem} defaults: none made ahead, 32 kept. */
    public static final int POOL_PRIME_SIZE = 0, MAX_POOL_SIZE = 32;

    /** A system with no parameters of its own reads none: its kits' layers read theirs. */
    public static final CgVfxSchema SCHEMA = CgVfxSchema.of("crystalgraphics:system");

    public CgVfxSystemDefinition {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("a system needs a name");
        if (look == null) throw new IllegalArgumentException(name + " needs a look");
        if (macroUv == null) macroUv = CgVfxMacroUv.DEFAULT;
        emitters = List.copyOf(emitters);
        for (int i = 0; i < emitters.size(); i++) {
            if (emitters.get(i).spawnFrom() != null) sourceOf(name, emitters, i);
        }
        if (poolPrimeSize < 0 || maxPoolSize < 0) throw new IllegalArgumentException(name + ": a pool size is negative");
        kills = List.copyOf(kills);
        for (Kill k : kills) {
            if (k.source() < 0 || k.source() >= emitters.size() || k.receiver() < 0 || k.receiver() >= emitters.size()
                    || k.event() < 0 || k.event() >= emitters.get(k.source()).events().size()
                    || emitters.get(k.source()).events().get(k.event()).readback() <= 0) {
                throw new IllegalArgumentException(name + ": " + k + " names no emitter, or an event that reads no rows back");
            }
        }
    }

    /** Its bounds following its particles. */
    public CgVfxSystemDefinition(String name, CgVfxLook look, @Nullable CgVfxBehaviour.Factory behaviour,
                                 List<CgVfxEmitter> emitters, int poolPrimeSize, int maxPoolSize, List<Kill> kills) {
        this(name, look, behaviour, emitters, poolPrimeSize, maxPoolSize, kills, null, CgVfxMacroUv.DEFAULT);
    }

    /** Killing nothing. */
    public CgVfxSystemDefinition(String name, CgVfxLook look, @Nullable CgVfxBehaviour.Factory behaviour,
                                 List<CgVfxEmitter> emitters, int poolPrimeSize, int maxPoolSize) {
        this(name, look, behaviour, emitters, poolPrimeSize, maxPoolSize, List.of());
    }

    /**
     * Where emitter {@code reader}'s source is among {@code emitters}: the place its file resolved, else the emitter of
     * its source's name; itself where it is born on its own particles. Fails naming a source it lacks, the reader
     * itself otherwise, or one that does not publish.
     */
    static int sourceOf(String system, List<CgVfxEmitter> emitters, int reader) {
        CgVfxEmitter e = emitters.get(reader);
        if (e.spawnFrom().itself()) {
            if (!e.publishes()) throw new IllegalArgumentException(system + ": " + e.name() + " is born on its own particles,"
                    + " which it does not publish: build it with publishes(true)");
            return reader;
        }
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
        return new CgVfxSystemDefinition(name, look, behaviour, emitters, poolPrimeSize, maxPoolSize, kills, fixedBounds, macroUv);
    }

    /** This definition with its instance pool's sizes: {@code prime} made ahead when it loads, at most {@code max} kept. */
    public CgVfxSystemDefinition withPool(int prime, int max) {
        return new CgVfxSystemDefinition(name, look, behaviour, emitters, prime, max, kills, fixedBounds, macroUv);
    }

    /** This definition with its bounds fixed about its origin; null for bounds following its particles. */
    public CgVfxSystemDefinition withFixedBounds(@Nullable FixedBounds bounds) {
        return new CgVfxSystemDefinition(name, look, behaviour, emitters, poolPrimeSize, maxPoolSize, kills, bounds, macroUv);
    }

    /** This definition with its particle macro UVs centred and sized by {@code macroUv}. */
    public CgVfxSystemDefinition withMacroUv(CgVfxMacroUv macroUv) {
        return new CgVfxSystemDefinition(name, look, behaviour, emitters, poolPrimeSize, maxPoolSize, kills, fixedBounds, macroUv);
    }
}
