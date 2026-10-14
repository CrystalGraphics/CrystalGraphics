package com.crystalgraphics.platform.world;

import com.crystalgraphics.platform.CgService;

/**
 * Causes world events on the single-player server, as its console would, for a probe checking that
 * {@link CgWorldEvents} hears them. Each call is queued onto the server's thread; the event reaches this client a
 * tick or more later.
 *
 * <pre>{@code
 * CgWorldStimulus stimulus = CgPlatform.get(CgWorldStimulus.SERVICE);
 * stimulus.keepRunning();             // first: a paused game ticks no server and hears nothing
 * if (stimulus.lightning(x, y, z)) expectLightningNear(x, y, z);
 * stimulus.breakBlock(bx, by, bz);    // a stone set there, then destroyed
 * stimulus.explode(ex, ey, ez);       // a pig there, and a TNT with no fuse
 * }</pre>
 *
 * A death and the way back, for a probe of what outlives the player's body:
 *
 * <pre>{@code
 * stimulus.kill();                    // the local player, killed by the server
 * // ...once the death screen is up:
 * stimulus.respawn();                 // its Respawn button; works connected to a server too
 * }</pre>
 *
 * Things to hit, for a probe of collision, and the way to be rid of them:
 *
 * <pre>{@code
 * stimulus.stand(x + 3.5, y, z + 0.5);     // a target that stays put
 * stimulus.dragon(x, y + 40, z);           // an Ender Dragon, alive: its parts move only with its AI
 * stimulus.painting(bx, by + 1, bz + 4);   // hung on a stone set south of that block, facing north
 * stimulus.clear(x, y, z, 48);             // every entity but players within 48 blocks
 * }</pre>
 *
 * <ul>
 *   <li>False, and nothing done, where there is no single-player server: connected to a server, or a version whose
 *       server takes no queued task. {@link #respawn} only needs a dead local player.</li>
 *   <li>It changes the world: {@link #breakBlock} leaves air and drops a stone, {@link #explode} breaks blocks within
 *       its reach, {@link #kill} drops the player's inventory unless the world keeps it, {@link #clear} removes
 *       every mob and item in its reach. A probe aims them at the air above the player.</li>
 *   <li>Client side, render thread.</li>
 * </ul>
 */
public interface CgWorldStimulus {

    /** No single-player server: the harness, a dedicated server. */
    CgWorldStimulus NONE = new CgWorldStimulus() {
        @Override public void keepRunning() { }
        @Override public boolean lightning(double x, double y, double z) { return false; }
        @Override public boolean breakBlock(int x, int y, int z) { return false; }
        @Override public boolean explode(double x, double y, double z) { return false; }
        @Override public boolean kill() { return false; }
        @Override public boolean respawn() { return false; }
        @Override public boolean stand(double x, double y, double z) { return false; }
        @Override public boolean dragon(double x, double y, double z) { return false; }
        @Override public boolean painting(int x, int y, int z) { return false; }
        @Override public boolean clear(double x, double y, double z, double radius) { return false; }
    };

    CgService<CgWorldStimulus> SERVICE = CgService.of("crystalgraphics:world_stimulus", NONE);

    /**
     * Stops the game pausing when its window loses focus, for the rest of the session: a client beside others, as in a
     * smoke run, would otherwise pause and stop its server. This call writes nothing to the options file.
     */
    void keepRunning();

    /** A lightning bolt at the point. */
    boolean lightning(double x, double y, double z);

    /** A stone set at the block, then destroyed as a player breaking it would. */
    boolean breakBlock(int x, int y, int z);

    /** A pig at the point and a TNT with no fuse on it: an explosion, and an entity hurt and killed. */
    boolean explode(double x, double y, double z);

    /** The local player killed on the server, as {@code /kill} would. */
    boolean kill();

    /** The death screen's Respawn: false unless the local player is dead. */
    boolean respawn();

    /** A target that stays put: an armor stand with no gravity, or a pig where there are none (1.7.10). */
    boolean stand(double x, double y, double z);

    /** An Ender Dragon, with its AI: one without never moves its parts from where they were made. */
    boolean dragon(double x, double y, double z);

    /** A 1x1 painting in the block, facing north, on a stone set in the block south of it. */
    boolean painting(int x, int y, int z);

    /** Every entity but players within {@code radius} blocks of the point removed, at once. */
    boolean clear(double x, double y, double z, double radius);
}
