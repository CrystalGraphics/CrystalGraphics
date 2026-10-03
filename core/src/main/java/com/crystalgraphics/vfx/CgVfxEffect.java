package com.crystalgraphics.vfx;

import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxValues;
import com.crystalgraphics.vfx.particle.CgVfxAir;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.camera.CgCameraShake;
import java.util.Arrays;

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
 *   <li>It shakes the camera through shakes declared on its schema, so a look can change them:
 *       {@link #playShake} for a hit, {@link #holdShake} every tick for a tremor that lasts. What it holds stops when
 *       it dies.</li>
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
    /** The air an effect's particles move through before it is played: still. */
    private static final CgVfxAir STILL = new CgVfxAir().wind(0f, 0f, 0f);
    private double groundY = Double.NaN;
    /** The shakes it holds, by parameter; made when first held. */
    private CgVfxParam[] heldParams;
    private CgCameraShake.Held[] held;
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
        die();
    }

    protected final void die() {
        state = State.DEAD;
        if (held != null) {
            for (CgCameraShake.Held h : held) {
                if (h != null) h.close();
            }
        }
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

    public final CgCameraShake shake(CgVfxParam param) {
        return values.shake(param);
    }

    public final CgVfxEffect set(CgVfxParam param, CgCameraShake shake) {
        values.set(param, shake);
        return this;
    }

    /** Plays the shake {@code param} at a point relative to its origin, its radii in units of {@code scale} blocks. */
    protected final void playShake(CgVfxParam param, float x, float y, float z, float scale) {
        values.shake(param).play(originX + x, originY + y, originZ + z, scale);
    }

    /** As {@link #playShake(CgVfxParam, float, float, float, float)}, its punch shoving along {@code (dx, dy, dz)}. */
    protected final void playShake(CgVfxParam param, float x, float y, float z, float dx, float dy, float dz, float scale) {
        values.shake(param).play(originX + x, originY + y, originZ + z, dx, dy, dz, scale);
    }

    /**
     * Holds the shake {@code param} at a point relative to its origin, its trauma times {@code level}: call it every tick
     * while it lasts. Left unset for a quarter second, it lapses.
     */
    protected final void holdShake(CgVfxParam param, float x, float y, float z, float scale, float level) {
        CgCameraShake shake = values.shake(param);
        int slot = 0;
        if (heldParams == null) {
            heldParams = new CgVfxParam[4];
            held = new CgCameraShake.Held[4];
        }
        while (slot < heldParams.length && heldParams[slot] != null && heldParams[slot] != param) slot++;
        if (slot == heldParams.length) {
            heldParams = Arrays.copyOf(heldParams, slot * 2);
            held = Arrays.copyOf(held, slot * 2);
        }
        if (held[slot] == null || held[slot].shake() != shake) {
            if (held[slot] != null) held[slot].close();
            heldParams[slot] = param;
            held[slot] = shake.hold();
        }
        held[slot].at(originX + x, originY + y, originZ + z).scale(scale).level(level);
    }

    /**
     * Where its particles land where the host has no world (the harness), as a world height; NaN, the default, for no
     * ground. In a world they land on its own surfaces ({@code CgVfxGround}).
     */
    public final CgVfxEffect ground(double worldY) {
        groundY = worldY;
        return this;
    }

    /** The ground's height relative to its origin, or NaN: what an emitter instance takes. */
    protected final float groundHeight() {
        return (float) (groundY - originY);
    }

    /** The air its particles move through: its system's, or still air before it is played. */
    protected final CgVfxAir air() {
        return system != null ? system.air() : STILL;
    }

    /** Advances one of its emitters by a tick, in its system's air and at its spawn share, sampling turbulence at its origin. */
    protected final void tick(CgVfxEmitterInstance emitter, float dt) {
        if (system != null) emitter.share(system.spawnShare(emitter.emitter()));
        emitter.tick(dt, air(), originX, originY, originZ);
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
