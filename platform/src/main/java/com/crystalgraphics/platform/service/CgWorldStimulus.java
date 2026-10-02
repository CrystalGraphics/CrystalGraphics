package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgService;

/**
 * Causes world events on the single-player server, as its console would, for a probe checking that
 * {@link CgWorldEvents} hears them. Each call is queued onto the server's thread; the event reaches this client a
 * tick or more later.
 *
 * <pre>{@code
 * CgWorldStimulus stimulus = CgPlatform.get(CgWorldStimulus.SERVICE);
 * if (stimulus.lightning(x, y, z)) expectLightningNear(x, y, z);
 * stimulus.breakBlock(bx, by, bz);    // a stone set there, then destroyed
 * stimulus.explode(ex, ey, ez);       // a pig there, and a TNT with no fuse
 * }</pre>
 *
 * <ul>
 *   <li>False, and nothing done, where there is no single-player server: connected to a server, or a version whose
 *       server takes no queued task.</li>
 *   <li>It changes the world: {@link #breakBlock} leaves air and drops a stone, {@link #explode} breaks blocks within
 *       its reach. A probe aims them at the air above the player.</li>
 *   <li>Client side, render thread.</li>
 * </ul>
 */
public interface CgWorldStimulus {

    /** No single-player server: the harness, a dedicated server. */
    CgWorldStimulus NONE = new CgWorldStimulus() {
        @Override public boolean lightning(double x, double y, double z) { return false; }
        @Override public boolean breakBlock(int x, int y, int z) { return false; }
        @Override public boolean explode(double x, double y, double z) { return false; }
    };

    CgService<CgWorldStimulus> SERVICE = CgService.of("crystalgraphics:world_stimulus", NONE);

    /** A lightning bolt at the point. */
    boolean lightning(double x, double y, double z);

    /** A stone set at the block, then destroyed as a player breaking it would. */
    boolean breakBlock(int x, int y, int z);

    /** A pig at the point and a TNT with no fuse on it: an explosion, and an entity hurt and killed. */
    boolean explode(double x, double y, double z);
}
