package com.crystalgraphics.vfx;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.vfx.look.CgVfxLook;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A mod's hand on a VFX system: what it plays, registers and clears, under its namespace. {@link CgVfx#owner} gives the
 * one for the client world's system, which the engine updates and draws; a mod never does.
 *
 * <pre>{@code
 * static final CgVfxOwner VFX = CgVfx.owner("mymod");
 *
 * // once, at startup: an effect by id, for the spawn command, the network and tools
 * VFX.register("hit_spark", "Hit spark", "Impacts", HitSpark.LOOK,
 *         (look, x, y, z, tx, ty, tz) -> new HitSpark(look, x, y, z).facing(tx - x, ty - y, tz - z));
 *
 * // in play
 * VFX.play(new HitSpark(HitSpark.LOOK, x, y, z));
 * VFX.play("hit_spark", x, y, z, tx, ty, tz);               // by id: "mymod:hit_spark"
 * VFX.play(new Aura(look, x, y, z).follow(entityId));       // bound to an entity
 *
 * // ending this mod's effects, and no other's
 * VFX.stop();                                               // gracefully: emission ends, what is in flight plays out
 * VFX.clear();                                              // at once: effects and particles gone
 * }</pre>
 *
 * <ul>
 *   <li>Play from any thread: an effect played off the render thread, or into the world system, starts at the next
 *       world frame. On a dedicated server, and on a client before its first world, nothing plays.</li>
 *   <li>An effect that throws is killed and logged under its owner; the others keep playing.</li>
 *   <li>Its effects' ticks and draws are timed under its namespace ({@code vfx.effect.tick/<namespace>},
 *       {@code vfx.effect.submit/<namespace>}), and counted ({@code vfx.effects/<namespace>}).</li>
 *   <li>Leaving the world clears every owner's effects, as {@link #clear} does.</li>
 * </ul>
 */
public final class CgVfxOwner {

    /** Plays one owner may have waiting for a world frame: past this, a client that never draws a world drops its oldest. */
    static final int MAX_PENDING = 4096;
    private static final Logger LOGGER = LogManager.getLogger("CgVfx");

    private final String namespace;
    /** The system it plays into; null for the world's, which is made again with each context. */
    private final CgVfxSystem fixed;
    final int tickZone, submitZone, effectsCounter;
    /** Into the world system: plays waiting for its next frame, from any thread. */
    private final ConcurrentLinkedQueue<CgVfxEffect> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingCount = new AtomicInteger();
    private volatile boolean warnedFull;
    /** Its effects in its system, each at its {@code ownerSlot}, until the system removes it. Render thread. */
    private final List<CgVfxEffect> effects = new ArrayList<>();
    /** The system whose owner list holds it. */
    CgVfxSystem listedIn;

    CgVfxOwner(String namespace, CgVfxSystem fixed) {
        if (!namespace.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("A VFX owner is a namespace, [a-z0-9_.-]+: '" + namespace + "'");
        }
        this.namespace = namespace;
        this.fixed = fixed;
        tickZone = CgTrace.name("vfx.effect.tick/" + namespace);
        submitZone = CgTrace.name("vfx.effect.submit/" + namespace);
        effectsCounter = CgTrace.name("vfx.effects/" + namespace);
    }

    public String namespace() {
        return namespace;
    }

    /** The system it plays into: the client world's, or the one it was made over. Render thread. */
    public CgVfxSystem system() {
        return fixed != null ? fixed : CgVfx.world();
    }

    /** Plays {@code effect} as this owner's. Into the world system it starts at the next world frame. */
    public <E extends CgVfxEffect> E play(E effect) {
        if (effect.system != null || effect.owner != null) throw new IllegalStateException("already played: " + effect);
        effect.owner = this;
        if (fixed != null) {
            fixed.play(effect);
        } else if (CgVfx.mayPlay()) {
            if (pendingCount.incrementAndGet() > MAX_PENDING && pending.poll() != null) {
                pendingCount.decrementAndGet();
                if (!warnedFull) LOGGER.warn("[CgVfx] {}: {} effects wait for a world frame: dropping the oldest", namespace, MAX_PENDING);
                warnedFull = true;
            }
            pending.add(effect);
        }
        return effect;
    }

    /** Plays what waits into {@code system}. */
    void drain(CgVfxSystem system) {
        for (CgVfxEffect effect; (effect = pending.poll()) != null; ) {
            pendingCount.decrementAndGet();
            system.play(effect);
        }
    }

    /** Drops what waits to play. */
    void dropPending() {
        while (pending.poll() != null) pendingCount.decrementAndGet();
    }

    /** {@code effect}, just played into its system, joins its list. */
    void adopt(CgVfxEffect effect) {
        effect.ownerSlot = effects.size();
        effects.add(effect);
    }

    /** {@code effect}, removed from its system, leaves its list. */
    void release(CgVfxEffect effect) {
        int slot = effect.ownerSlot, last = effects.size() - 1;
        CgVfxEffect moved = effects.remove(last);
        if (slot != last) {
            effects.set(slot, moved);
            moved.ownerSlot = slot;
        }
        effect.ownerSlot = -1;
    }

    /** Its system dropped every effect at once. */
    void releaseAll() {
        for (int i = 0; i < effects.size(); i++) effects.get(i).ownerSlot = -1;
        effects.clear();
    }

    /**
     * Plays the registered effect {@code id} at a point, aimed at a target: {@code "path"} under this namespace, or a full
     * {@code "namespace:path"} for another's. Null, playing nothing, when no such effect is registered.
     */
    public CgVfxEffect play(String id, double x, double y, double z, double tx, double ty, double tz) {
        CgVfxRegistry.Entry entry = CgVfxRegistry.get(id(id));
        return entry == null ? null : play(entry.spawn(entry.look(), x, y, z, tx, ty, tz));
    }

    /**
     * Registers an effect as {@code namespace:path}, for tools, the spawn command and the network to play by id. Once
     * per path; a second throws.
     */
    public CgVfxRegistry.Entry register(String path, String name, String category, CgVfxLook look,
                                        CgVfxRegistry.Factory factory) {
        if (path.indexOf(':') >= 0) throw new IllegalArgumentException("a path, not an id: '" + path + "'");
        return CgVfxRegistry.register(namespace + ":" + path, name, category, look, factory);
    }

    /** {@code id} as a full id: a bare path is this namespace's. */
    public String id(String id) {
        return id.indexOf(':') >= 0 ? id : namespace + ":" + id;
    }

    /** Warms what {@code look} draws and simulates before it first plays (as {@link CgVfxSystem#prepare}). Render thread. */
    public void prepare(CgVfxLook look) {
        system().prepare(look);
    }

    /** Whether every look prepared or played has built (as {@link CgVfxSystem#warmed}). Render thread. */
    public boolean warmed() {
        return system().warmed();
    }

    /**
     * Ends every effect it played at once, particles and all (gone by the next particle step), and drops those waiting to
     * start. Another owner's effects play on. Render thread.
     */
    public void clear() {
        dropPending();
        for (int i = 0; i < effects.size(); i++) effects.get(i).kill();
    }

    /**
     * Stops every effect it played: none emits again, and what is in flight plays out before each dies. Those waiting to
     * start never do. Render thread.
     */
    public void stop() {
        dropPending();
        for (int i = 0; i < effects.size(); i++) effects.get(i).stop();
    }

    /** How many of its effects its system holds: playing, or ended since its last update. Render thread. */
    public int playing() {
        return effects.size();
    }

    @Override
    public String toString() {
        return namespace;
    }
}
