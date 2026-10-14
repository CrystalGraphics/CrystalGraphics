package com.crystalgraphics.net.object;

/**
 * What a networked object is on one side: an effect, a HUD element, a hitbox, the server's authority over it. The
 * layer replicates; a behaviour reacts. A type makes one per object on each client that sees it
 * ({@link CgNetType#onClient}), and one on the server ({@link CgNetType#onServer}).
 *
 * <pre>{@code
 * TURRET.onClient(obj -> new CgNetBehaviour() {
 *     TurretModel model;
 *     public void spawned(CgNetObject obj, float age) { model = TurretModel.place(obj.x(), obj.y(), obj.z()); }
 *     public void changed(CgNetObject obj, CgNetField<?> field) { if (field == HEAT) model.glow(obj.getFloat(HEAT)); }
 *     public void despawned(CgNetObject obj, boolean killed) { model.remove(); }
 * });
 * }</pre>
 *
 * <p>The server's behaviour writes fields and emits events. After a graceful despawn it finishes in front of its
 * viewers, who are told it is {@code stopping} and still hear what it emits:</p>
 *
 * <pre>{@code
 * TURRET.onServer(obj -> new CgNetBehaviour() {
 *     int cooling = -1;
 *     public void tick(CgNetObject obj) {
 *         obj.setFloat(HEAT, Math.max(0, obj.getFloat(HEAT) - 0.01f));
 *         if (cooling == 0) obj.emit(VENTED, null);           // reaches every viewer: it is still theirs
 *     }
 *     public void despawned(CgNetObject obj, boolean killed) { cooling = 20; }
 *     public boolean done() { return cooling-- <= 0; }
 * });
 *
 * TURRET.onClient(obj -> new CgNetBehaviour() {
 *     public void stopping(CgNetObject obj) { model.powerDown(); }   // the server is finishing it
 *     public void despawned(CgNetObject obj, boolean killed) { model.remove(); }
 * });
 * }</pre>
 *
 * <ul>
 *   <li>Called on the side's own thread, at its tick: the client tick, or the server tick.</li>
 *   <li>{@code spawned}'s age is in server ticks; above 0 for a viewer who arrives late, or a persisted object loading:
 *       catch up silently.</li>
 *   <li>On a client, every field a frame changed is in the object before the first {@code changed} call, and the calls
 *       come in the type's declaration order. A prediction's behaviour is the one that continues once the server makes
 *       it real: {@code changed} reports only the fields the server's values changed.</li>
 *   <li>A graceful despawn of an object with a server behaviour: the server's hears {@code despawned} and ticks on
 *       until {@link #done()}, for at most its type's linger, the viewers who saw it keeping it and hearing what it
 *       sets and emits; then each viewer's hears {@code despawned}. A client's ticks on after that until its own
 *       {@code done()}. A kill, or the object leaving a viewer's view, ends it there at once.</li>
 * </ul>
 */
public interface CgNetBehaviour {

    default void spawned(CgNetObject obj, float age) {
    }

    /** On a client: a field changed, by the server or by this client predicting it. */
    default void changed(CgNetObject obj, CgNetField<?> field) {
    }

    /**
     * On a client: once after a frame's changes to it, after their {@code changed} calls: where a reaction needs several
     * fields at once (Unreal's {@code PostNetReceive}). Not at spawn, where {@code spawned} sees them all.
     */
    default void received(CgNetObject obj) {
    }

    /** On a client: a point-anchored object moved, or a player anchor's player changed entity or dimension. */
    default void moved(CgNetObject obj) {
    }

    /**
     * On a client: an event the server emitted on it. Guaranteed after that tick's changes and their {@code changed}
     * calls, set before or after the emit, the tick it ends included.
     */
    default void event(CgNetObject obj, CgNetEvent<?> event, Object value) {
    }

    /** Each tick of its side, from spawn until it ends; on the server, not while it is dormant. */
    default void tick(CgNetObject obj) {
    }

    /**
     * On a client: the server despawned it gracefully and its server behaviour is finishing. Wind down what it shows;
     * changes and events still come, then {@code despawned}.
     */
    default void stopping(CgNetObject obj) {
    }

    /** {@code killed}: ended at once, or left view, rather than despawned gracefully. */
    default void despawned(CgNetObject obj, boolean killed) {
    }

    /** Whether a graceful end has finished. A transient object is dropped once this says so. */
    default boolean done() {
        return true;
    }
}
