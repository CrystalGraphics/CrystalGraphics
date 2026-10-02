package com.crystalgraphics.vfx;

import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxValues;

/**
 * An effect playing in a world: placed at an origin in doubles, simulated in floats relative to it on the
 * {@link CgVfxSystem}'s fixed tick, and drawn each frame through a {@link CgVfxFrame}.
 *
 * <pre>{@code
 * public final class MyEffect extends CgVfxEffect {
 *     public MyEffect(CgVfxLook look, double x, double y, double z) { super(look, x, y, z); }
 *     @Override protected void tick(float dt) { ... }          // CgVfxSystem.TICK seconds of simulation
 *     @Override protected void submit(CgVfxFrame frame) { ... } // draw, interpolated by frame.alpha()
 * }
 * CgVfxEffect effect = vfx.play(new MyEffect(look, x, y, z));
 * effect.stop();                                                // finishes what is in flight, then dies
 * }</pre>
 *
 * <ul>
 *   <li>Its values start as its look's; {@link #set} changes this effect alone.</li>
 *   <li>An effect is done when it calls {@link #die()}; the system then drops it before the next frame.</li>
 *   <li>It announces the moments of its life with {@link #moment}, which a {@link CgVfxMomentListener} hears: name
 *       them as constants on the effect.</li>
 * </ul>
 */
public abstract class CgVfxEffect {

    public enum State { PLAYING, STOPPING, DEAD }

    protected final double originX, originY, originZ;
    /** The system playing it, set by {@link CgVfxSystem#play}. */
    CgVfxSystem system;
    /** Its look's materials have been handed to the system to compile ahead of use. */
    boolean warmed;
    private final CgVfxLook look;
    private final CgVfxValues values;
    private State state = State.PLAYING;
    /** Seconds simulated since it started. */
    protected float age;
    /** A stable random number for this effect, 0..1, which shaders read to tell two effects apart. */
    protected final float seed;

    protected CgVfxEffect(CgVfxLook look, double x, double y, double z) {
        this.look = look;
        this.values = look.copyValues();
        this.originX = x;
        this.originY = y;
        this.originZ = z;
        this.seed = (float) Math.random();
    }

    protected abstract void tick(float dt);

    protected abstract void submit(CgVfxFrame frame);

    /** Ends emission; what is in flight plays out. */
    public void stop() {
        if (state == State.PLAYING) state = State.STOPPING;
    }

    /** Removes it at once. */
    public void kill() {
        state = State.DEAD;
    }

    protected final void die() {
        state = State.DEAD;
    }

    public final State state() {
        return state;
    }

    public final CgVfxLook look() {
        return look;
    }

    public final float age() {
        return age;
    }

    public final float get(CgVfxParam param) {
        return values.get(param);
    }

    /** Component {@code index} of a colour. */
    public final float get(CgVfxParam param, int index) {
        return values.get(param, index);
    }

    public final CgVfxEffect set(CgVfxParam param, float value) {
        values.set(param, value);
        return this;
    }

    public final CgVfxEffect set(CgVfxParam param, float r, float g, float b, float a) {
        values.set(param, r, g, b, a);
        return this;
    }

    public final CgKeyframes curve(CgVfxParam param) {
        return values.curve(param);
    }

    public final CgVfxEffect set(CgVfxParam param, CgKeyframes curve) {
        values.set(param, curve);
        return this;
    }

    /** Whether anything hears its moments: skip working out a moment's framing when nothing does. */
    protected final boolean momentsHeard() {
        return system != null && system.hasMomentListeners();
    }

    /**
     * Announces that it has reached the moment {@code name}, framed by a point relative to its origin and a radius that
     * holds what matters: what a capture tool photographs. Call it once, as the moment is crossed in {@link #tick}.
     */
    protected final void moment(String name, float x, float y, float z, float radius) {
        if (momentsHeard()) system.moment(this, name, originX + x, originY + y, originZ + z, radius);
    }

    final void step(float dt) {
        age += dt;
        tick(dt);
    }

    /** Its own values, live: what its layers read each draw. */
    public final CgVfxValues values() {
        return values;
    }
}
