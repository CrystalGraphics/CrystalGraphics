package com.crystalgraphics.vfx;

import com.crystalgraphics.collision.CgPart;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEventListener;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEventRows;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Where every {@link CgVfxCollision} goes: the effect's listeners, its owner's, then the global ones. Particles' rows
 * come in through one pool listener, a collision's and a landing's, tagged with the effect whose slot they came from.
 * An effect running on a server ({@link CgVfxEffect#authoritative}) is heard by its own listeners alone: the others are
 * a client's, and on an integrated server share its JVM.
 */
final class CgVfxCollisions {

    private static final Logger LOGGER = LogManager.getLogger("CgVfx");
    static final CopyOnWriteArrayList<CgVfxCollisionListener> GLOBAL = new CopyOnWriteArrayList<>();
    /** Filled for each call; render thread. */
    private static final CgVfxCollision EVENT = new CgVfxCollision();
    private static boolean listening;

    /** What hears every pool's rows. */
    static final CgVfxEventListener ROWS = CgVfxCollisions::rows;

    private CgVfxCollisions() {
    }

    /** Hears particles' rows from now on. Once; render thread. */
    static void install() {
        if (listening) return;
        listening = true;
        CgVfxParticlePool.listen(ROWS);
    }

    static CgVfxCollision.Registration add(List<CgVfxCollisionListener> to, CgVfxCollisionListener listener) {
        if (listener == null) throw new IllegalArgumentException("listener");
        to.add(listener);
        return () -> to.remove(listener);
    }

    /** Whether a collision of {@code effect}'s would be heard. */
    static boolean heard(CgVfxEffect effect) {
        if (effect != null && effect.authoritative()) return effect.collideListeners() != null;
        if (!GLOBAL.isEmpty()) return true;
        if (effect == null) return false;
        return effect.collideListeners() != null || effect.owner != null && !effect.owner.collideListeners.isEmpty();
    }

    /** An effect's own collision. */
    static void effect(CgVfxEffect effect, CgPart part, CgVfxCollision.Hit hit, int entity, double x, double y, double z,
                       float nx, float ny, float nz, float vx, float vy, float vz, float radius) {
        if (!heard(effect)) return;
        // A server's own event: EVENT is the render thread's.
        CgVfxCollision event = effect.authoritative() ? new CgVfxCollision() : EVENT;
        dispatch(event.effect(effect, part, hit, entity, x, y, z, nx, ny, nz, vx, vy, vz, radius));
    }

    private static void rows(CgVfxGpuEmitter definition, int event, CgVfxEventRows rows) {
        CgVfxEvent.Trigger trigger = definition.events().get(event).trigger();
        if (trigger != CgVfxEvent.Trigger.COLLISION && trigger != CgVfxEvent.Trigger.LANDING) return;
        for (int i = 0; i < rows.count(); i++) {
            CgVfxEffect effect = rows.owner(i) instanceof CgVfxEffect e ? e : null;
            if (!heard(effect)) continue;
            CgVfxCollision.Hit hit = trigger == CgVfxEvent.Trigger.LANDING ? CgVfxCollision.Hit.GROUND
                    : CgVfxCollision.hitOf(rows.hit(i));
            dispatch(EVENT.particle(effect, definition, rows, i, hit));
        }
    }

    private static void dispatch(CgVfxCollision c) {
        CgVfxEffect effect = c.effect();
        if (effect != null) {
            CgVfxCollisionListener[] own = effect.collideListeners();
            if (own != null) {
                for (CgVfxCollisionListener l : own) if (l != null) call(l, c);
            }
            if (effect.authoritative()) return;
            if (effect.owner != null) call(effect.owner.collideListeners, c);
        }
        call(GLOBAL, c);
    }

    private static void call(List<CgVfxCollisionListener> listeners, CgVfxCollision c) {
        for (CgVfxCollisionListener l : listeners) call(l, c);   // a snapshot: a listener may unregister as it hears
    }

    private static void call(CgVfxCollisionListener listener, CgVfxCollision c) {
        try {
            listener.collided(c);
        } catch (RuntimeException e) {
            LOGGER.error("A VFX collision listener threw on {}", c, e);
        }
    }
}
