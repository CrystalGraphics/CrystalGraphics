package com.crystalgraphics.render.scene;

import com.crystalgraphics.render.CgFrameClock;

/**
 * A scene's time, Unreal's world time: what its views' {@code CG_TIME} reads and its effects run on. The client scene's
 * is the game's frame clock; a scene of your own runs as its owner advances it, paused or scaled on its own.
 *
 * <pre>{@code
 * CgScene lab = new CgScene();
 * lab.tick(dt);                            // each frame: its clock advances by dt, then its primitives are placed
 * lab.clock().paused(true);                // its views hold their time; the game's goes on
 * lab.clock().timeScale(0.25f);            // a quarter speed: slow motion in this scene alone
 * double t = lab.clock().seconds();
 * }</pre>
 *
 * <ul>
 *   <li>The client scene's clock is the game's: pausing or scaling it throws.</li>
 *   <li>Render thread, as its scene.</li>
 * </ul>
 */
public final class CgSceneClock {

    private final boolean game;
    private double seconds, delta;
    private boolean paused;
    private float timeScale = 1f;

    CgSceneClock(boolean game) {
        this.game = game;
    }

    /** Seconds since it started, scaled and less what it spent paused. */
    public double seconds() {
        return game ? CgFrameClock.seconds() : seconds;
    }

    /** What its last advance added: 0 while paused. Always 0 on the client scene's, whose delta is its world's. */
    public double delta() {
        return delta;
    }

    public boolean paused() {
        return paused;
    }

    /** Holds its time where it is. */
    public CgSceneClock paused(boolean paused) {
        ownersOnly();
        this.paused = paused;
        return this;
    }

    public float timeScale() {
        return timeScale;
    }

    /** How fast it runs against the time its owner advances it by: 1 as given, 0.5 half speed. */
    public CgSceneClock timeScale(float scale) {
        ownersOnly();
        if (!(scale >= 0f)) throw new IllegalArgumentException("time scale " + scale);
        this.timeScale = scale;
        return this;
    }

    /** {@code realSeconds} passed: it advances by them scaled, or not at all while paused. */
    void advance(double realSeconds) {
        ownersOnly();
        delta = paused ? 0 : realSeconds * timeScale;
        seconds += delta;
    }

    private void ownersOnly() {
        if (game) throw new IllegalStateException("the client scene's clock is the game's");
    }
}
