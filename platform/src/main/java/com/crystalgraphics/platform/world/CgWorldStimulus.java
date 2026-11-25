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
 * Input, as the player's mouse and keyboard would give it, for a probe of a camera hold's lock:
 *
 * <pre>{@code
 * stimulus.turnLook(20f);             // the player turned as the mouse turns it
 * stimulus.pressJump();               // the jump key pressed, never released
 * boolean down = stimulus.jumpDown(); // read at a later tick
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

    /**
     * The local player turned by {@code yaw} and {@code pitch} degrees (pitch positive down, clamped at 90), as the mouse
     * turns it; false where there is no player.
     */
    default boolean turnLook(float yaw, float pitch) {
        return false;
    }

    /** The jump key pressed, as the keyboard presses it, and left down; false where this version presses none. */
    default boolean pressJump() {
        return false;
    }

    /** Whether the jump key reads down now. */
    default boolean jumpDown() {
        return false;
    }

    /**
     * A furnace or a chest set at the block by the server, with eight cobblestone in its first slot, for a probe of
     * {@code CgContainers}. {@code block} is {@code "furnace"} or {@code "chest"}, or {@code "anvil"}, which holds nothing; false where this version cannot.
     */
    default boolean container(int x, int y, int z, String block) {
        return false;
    }

    /** A villager with one trade (an emerald for a cobblestone) standing at the block, for a probe of a merchant's menu; false where this version cannot. */
    default boolean merchant(int x, int y, int z) {
        return false;
    }

    /** The local player's use-entity click on the entity with that network id, as the use key sends it; false where this version has no such call. */
    default boolean useEntity(int id) {
        return false;
    }

    /** The local player's right-click on the block's top face, as the use key sends it; false where this version has no such call. */
    default boolean use(int x, int y, int z) {
        return false;
    }

    /**
     * The local player put in creative and moved to the point: a far move for a probe (a chunk left to unload, then
     * entered again) that no fall can kill. False where there is no player.
     */
    default boolean teleport(double x, double y, double z) {
        return false;
    }

    /** The local player put in survival, so the inventory key opens the survival inventory; false where there is no player. */
    default boolean survival() {
        return false;
    }

    /**
     * The local player's inventory emptied, then filled slot by slot, for a probe of an inventory screen: slots 0 to 8
     * are the hotbar, 9 to 35 the rest, each {@code "minecraft:diamond 5"} or empty for none. False where this
     * version cannot.
     *
     * <pre>{@code
     * stimulus.inventory("minecraft:cobblestone 64", "", "minecraft:ender_pearl 16");
     * }</pre>
     */
    default boolean inventory(String... stacks) {
        return false;
    }

    /**
     * The world's time of day set, as {@code /time set}: {@code 6000} noon, {@code 18000} midnight. What a probe of a
     * clock's face moves; false where there is no single-player server.
     */
    default boolean time(long ticks) {
        return false;
    }

    /**
     * The pointer moved to {@code (x, y)}, the window's screen units from its top-left, through the host's own cursor
     * callback, as the system delivers a move: everything listening to the game's input sees it, and the system's
     * cursor stays where it is. False where this version cannot. Render thread.
     *
     * <pre>{@code
     * stimulus.hostPointer(640, 360);           // over the middle of a 1280x720 window
     * stimulus.hostKey(CgKeyCodes.KEY_R);       // R, as the player presses it there
     * }</pre>
     */
    default boolean hostPointer(double x, double y) {
        return false;
    }

    /** A key ({@code CgKeyCodes}) pressed and released through the host's own key callback. False where it cannot. */
    default boolean hostKey(int key) {
        return false;
    }

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
