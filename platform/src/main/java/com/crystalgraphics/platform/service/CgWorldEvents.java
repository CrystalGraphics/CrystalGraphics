package com.crystalgraphics.platform.service;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What happens in the host's world, as this client learns of it: an explosion, a block broken, an entity hurt or killed,
 * a lightning strike. Hosts push each where the client already reacts to it; anything may listen, to add an effect to
 * it or replace the vanilla one.
 *
 * <pre>{@code
 * CgWorldEvents.listen(new CgWorldEvents.Listener() {
 *     @Override public void explosion(double x, double y, double z, float power) { blastAt(x, y, z, power); }
 * });
 *
 * // a host, where the client handles the explosion packet
 * CgWorldEvents.explosion(x, y, z, power);
 * }</pre>
 *
 * <ul>
 *   <li>Client side only, delivered on the render thread (the client thread on every host), in the order they happened.</li>
 *   <li>Primitives only: absolute coordinates, the entity ids {@link CgEntityQuery} knows, and the engine's surface
 *       kinds and ARGB colours for a broken block, which is gone by the time a listener hears.</li>
 *   <li>A listener must not throw: one that does stops the event reaching the listeners after it.</li>
 *   <li>Not every host reports every kind: {@link #declared()} says which this one does, as each host
 *       {@linkplain #declare declares} them where it wires the hook.</li>
 * </ul>
 */
public final class CgWorldEvents {

    /** The kinds of event, as bits of {@link #declared()}. */
    public static final int EXPLOSION = 1, BLOCK_BROKEN = 2, ENTITY_HURT = 4, ENTITY_DIED = 8, LIGHTNING = 16;

    private static volatile int declared;

    /** What a listener hears; every method does nothing unless overridden. */
    public interface Listener {
        /** An explosion of {@code power} (TNT is 4) centred at the point. */
        default void explosion(double x, double y, double z, float power) { }

        /** A block broken at the block position: what it was made of ({@code CgWorldQuery.SURFACE_}) and its map colour. */
        default void blockBroken(int x, int y, int z, int surface, int mapColor) { }

        /** Entity {@code id} hurt, at its position. */
        default void entityHurt(int id, double x, double y, double z) { }

        /** Entity {@code id} killed, at its position. */
        default void entityDied(int id, double x, double y, double z) { }

        /** Lightning striking at the point. */
        default void lightning(double x, double y, double z) { }
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private CgWorldEvents() {
    }

    /** Starts telling {@code listener}; until {@link #stopListening}. */
    public static void listen(Listener listener) {
        LISTENERS.addIfAbsent(listener);
    }

    public static void stopListening(Listener listener) {
        LISTENERS.remove(listener);
    }

    /** Host side, where it wires a hook: this host reports {@code kinds} ({@link #EXPLOSION} and the rest). */
    public static synchronized void declare(int kinds) {
        declared |= kinds;
    }

    /** The kinds this host reports; 0 on a host that reports none. */
    public static int declared() {
        return declared;
    }

    /** Host side. */
    public static void explosion(double x, double y, double z, float power) {
        for (Listener l : LISTENERS) l.explosion(x, y, z, power);
    }

    /** Host side. */
    public static void blockBroken(int x, int y, int z, int surface, int mapColor) {
        for (Listener l : LISTENERS) l.blockBroken(x, y, z, surface, mapColor);
    }

    /** Host side. */
    public static void entityHurt(int id, double x, double y, double z) {
        for (Listener l : LISTENERS) l.entityHurt(id, x, y, z);
    }

    /** Host side. */
    public static void entityDied(int id, double x, double y, double z) {
        for (Listener l : LISTENERS) l.entityDied(id, x, y, z);
    }

    /** Host side. */
    public static void lightning(double x, double y, double z) {
        for (Listener l : LISTENERS) l.lightning(x, y, z);
    }
}
