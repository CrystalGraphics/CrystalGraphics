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
 * </ul>
 */
public abstract class CgVfxEffect {

    public enum State { PLAYING, STOPPING, DEAD }

    protected final double originX, originY, originZ;
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

    final void step(float dt) {
        age += dt;
        tick(dt);
    }

    /** Its own values, live: what its layers read each draw. */
    public final CgVfxValues values() {
        return values;
    }
}
