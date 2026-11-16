package com.crystalgraphics.platform.world;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;

/**
 * Moving one of the host's entities: what its root motion, a dash, a knockback or a teleport does to where it is and
 * how it faces. Reading where an entity is, its velocity, its turn and whether it stands on the ground is
 * {@link CgEntityQuery}'s; this changes them, as the host's own code would, on the side that owns the entity (the
 * server for a mob, the client for its own player) and on its level's thread.
 *
 * <pre>{@code
 * Entity mob = CgEntityQueries.of(level).entity(id);
 * CgEntityMovement.move(mob, 0.4, 0, 1.1);          // blocks, as its own movement: swept, stopped at a wall
 * CgEntityMovement.turn(mob, 35f);                   // degrees (0 faces +z), body and head with it
 * CgEntityMovement.rotate(mob, 35f, -20f);           // and its pitch: up is negative, as Minecraft's
 * }</pre>
 *
 * <pre>{@code
 * CgEntityMovement.push(target, 0.0, 0.6, 1.2);      // a knockback: added to its velocity, blocks a tick, sent to clients
 * CgEntityMovement.velocity(fighter, 0, 0, 0);       // held in the air ...
 * CgEntityMovement.gravity(fighter, false);          // ... while a flight move plays; true again after it
 * CgEntityMovement.resetFall(fighter);               // landed from a dive: no fall damage
 * CgEntityMovement.teleport(fighter, x, y, z);       // a vanish strike: placed, unswept, its clients told
 * }</pre>
 *
 * <ul>
 *   <li>Entities cross as {@code Object}; anything else answers false (NaN for a read) and changes nothing.</li>
 *   <li>A change a client makes to an entity it does not own is undone by the next update from its server.</li>
 *   <li>Velocities are Minecraft's, blocks a tick, as {@link CgEntityQuery} reads them.</li>
 *   <li>Where a version has no such member it answers false: {@code move} places the entity unswept on Forge 1.13.2,
 *       which has no velocity setter either; Forge 1.8.9 and 1.7.10 have no gravity switch.</li>
 *   <li>Without a host (the harness, a test) nothing changes.</li>
 * </ul>
 */
public final class CgEntityMovement {

    /** What a host does, both sides. One class per era: {@code EntityMovementModern}, {@code EntityMovementLegacy}, {@code EntityMovement1710}. */
    public interface Host {

        /** Moves it by ({@code dx}, {@code dy}, {@code dz}) blocks as its own movement: swept against blocks, stepping up. */
        boolean move(Object entity, double dx, double dy, double dz);

        /** Places it at ({@code x}, {@code y}, {@code z}), unswept, telling its clients (a player's included). */
        boolean teleport(Object entity, double x, double y, double z);

        /** Turns it to {@code yaw} and {@code pitch} degrees, Minecraft's, its body and head with it. */
        boolean rotate(Object entity, float yaw, float pitch);

        /** Its yaw, degrees (0 faces +z); NaN for a non-entity. */
        float yaw(Object entity);

        /** Its pitch, degrees (up negative); NaN for a non-entity. */
        float pitch(Object entity);

        /** Sets its velocity, blocks a tick, sent to its clients. */
        boolean velocity(Object entity, double vx, double vy, double vz);

        /** Adds to its velocity, blocks a tick, sent to its clients: a knockback, a launch. */
        boolean push(Object entity, double vx, double vy, double vz);

        /** Whether gravity pulls it; false where the version has no switch. */
        boolean gravity(Object entity, boolean on);

        /** Clears the distance it has fallen, so landing does it no harm. */
        boolean resetFall(Object entity);

        /** Changes nothing. */
        Host NONE = new None();
    }

    // Not a lambda over the owner: NONE initialising the owner would read HOST while it is null
    private static final class None implements Host {
        @Override public boolean move(Object entity, double dx, double dy, double dz) { return false; }
        @Override public boolean teleport(Object entity, double x, double y, double z) { return false; }
        @Override public boolean rotate(Object entity, float yaw, float pitch) { return false; }
        @Override public float yaw(Object entity) { return Float.NaN; }
        @Override public float pitch(Object entity) { return Float.NaN; }
        @Override public boolean velocity(Object entity, double vx, double vy, double vz) { return false; }
        @Override public boolean push(Object entity, double vx, double vy, double vz) { return false; }
        @Override public boolean gravity(Object entity, boolean on) { return false; }
        @Override public boolean resetFall(Object entity) { return false; }
    }

    /** The slot a host fills. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:entity_movement", Host.NONE);

    private CgEntityMovement() {
    }

    /** Moves {@code entity} by ({@code dx}, {@code dy}, {@code dz}) blocks as its own movement does: swept, stopped at a wall. */
    public static boolean move(Object entity, double dx, double dy, double dz) {
        return host().move(entity, dx, dy, dz);
    }

    /** Places {@code entity} at ({@code x}, {@code y}, {@code z}), unswept, telling its clients. */
    public static boolean teleport(Object entity, double x, double y, double z) {
        return host().teleport(entity, x, y, z);
    }

    /** Turns {@code entity} to {@code yaw} degrees (0 faces +z), its pitch kept, its body and head with it. */
    public static boolean turn(Object entity, float yaw) {
        float pitch = host().pitch(entity);
        return host().rotate(entity, yaw, Float.isNaN(pitch) ? 0f : pitch);
    }

    /** Turns {@code entity} to {@code yaw} and {@code pitch} degrees (up negative), its body and head with it. */
    public static boolean rotate(Object entity, float yaw, float pitch) {
        return host().rotate(entity, yaw, pitch);
    }

    /** {@code entity}'s yaw, degrees; NaN for a non-entity or without a host. */
    public static float yaw(Object entity) {
        return host().yaw(entity);
    }

    /** {@code entity}'s pitch, degrees; NaN for a non-entity or without a host. */
    public static float pitch(Object entity) {
        return host().pitch(entity);
    }

    /** Sets {@code entity}'s velocity, blocks a tick; its clients told. */
    public static boolean velocity(Object entity, double vx, double vy, double vz) {
        return host().velocity(entity, vx, vy, vz);
    }

    /** Adds to {@code entity}'s velocity, blocks a tick; its clients told. */
    public static boolean push(Object entity, double vx, double vy, double vz) {
        return host().push(entity, vx, vy, vz);
    }

    /** Whether gravity pulls {@code entity}; false where the version has no switch (1.7.10, Forge 1.8.9). */
    public static boolean gravity(Object entity, boolean on) {
        return host().gravity(entity, on);
    }

    /** Clears the distance {@code entity} has fallen, so landing does it no harm. */
    public static boolean resetFall(Object entity) {
        return host().resetFall(entity);
    }

    private static Host host() {
        return CgPlatform.get(HOST);
    }
}
