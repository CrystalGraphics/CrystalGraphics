package com.crystalgraphics.vfx;

import com.crystalgraphics.collision.CgCollisionWorld;
import com.crystalgraphics.easing.CgEasing;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.vfx.look.CgVfxEnding;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.world.CgEntityAttachments;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Runs an effect on a server: the half gameplay depends on (where a beam goes, what it strikes, when it bursts) on the
 * server's tick, against one level, while each client draws the effect. The effect is the same class the clients play;
 * {@link CgVfxEffect#authoritative} turns its shakes, post volumes, particles and moments off, and its hits reach its
 * listeners on the server thread.
 *
 * <pre>{@code
 * CgEnergyWave wave = new CgEnergyWave(CgEnergyWave.kamehameha(), x, y, z).collide();
 * CgVfxAuthority run = new CgVfxAuthority(wave, CgCollisionWorld.of(level));
 * wave.onHit(CgEnergyWave.PART_BEAM, h -> damage(h.entity()));
 * run.advance(0.05f);                 // each server tick, after wave.aim(...)
 * if (run.done()) ...
 * }</pre>
 *
 * A lasting cue declared {@code authoritative()} does all of this for its effect.
 *
 * <ul>
 *   <li>One thread: the server's. The effect is never also played into a system.</li>
 *   <li>A followed effect is placed each advance at its attachment point's proportions
 *       ({@link CgEntityAttachments#point(int, double[], double[])}): a server has no model to mark the point.</li>
 * </ul>
 */
public final class CgVfxAuthority {

    /** Ticks one advance may step: a server that stalls longer drops the rest rather than catch up in a burst. */
    static final int MAX_STEPS = 60;

    /** What its effect's hitboxes and queries test ({@link CgVfxEffect#world}). */
    final CgCollisionWorld world;
    private final CgVfxEffect<?> effect;
    private final double[] pose = new double[CgEntityQuery.POSE_LENGTH], point = new double[3];
    private float pending;
    private boolean started;
    /** The numbers and colours set on the effect since {@link #drainSet} last ran. */
    private CgVfxParam[] set = new CgVfxParam[4];
    private int setCount;
    /** Tweens begun on the effect since {@link #drainMotion} last ran, and the ending it began, if any. */
    private final List<Tween> tweens = new ArrayList<>();
    private CgVfxEnding ending;

    /** A tween begun on the server's copy, for every viewer to play: a number or a colour, eased to {@code to}. */
    public static final class Tween {
        public final CgVfxParam param;
        public final float[] to;
        public final float seconds;
        public final CgEasing easing;

        Tween(CgVfxParam param, float[] to, float seconds, CgEasing easing) {
            this.param = param;
            this.to = to;
            this.seconds = seconds;
            this.easing = easing;
        }
    }

    /** Takes {@code effect}, unplayed, to run against {@code world}: one level's. */
    public CgVfxAuthority(CgVfxEffect<?> effect, CgCollisionWorld world) {
        if (effect.system != null || effect.authority != null) throw new IllegalStateException("an effect plays once");
        this.effect = effect;
        this.world = world;
        effect.authority = this;
    }

    public CgVfxEffect<?> effect() {
        return effect;
    }

    /** Whether the effect has died. */
    public boolean done() {
        return effect.state() == CgVfxEffect.State.DEAD;
    }

    /**
     * Steps the effect through {@code seconds} on {@link CgVfxSystem#TICK}, first placing it on the entity it follows
     * (stopping it when that is gone). The first advance fast-forwards it to its {@code startAt} age, silently.
     */
    public void advance(float seconds) {
        if (done()) return;
        if (!started) {
            started = true;
            if (effect.startAge > 0f) seek();
        }
        follow();
        pending += seconds;
        int steps = 0;
        while (pending >= CgVfxSystem.TICK && !done()) {
            effect.step(CgVfxSystem.TICK);
            pending -= CgVfxSystem.TICK;
            if (++steps == MAX_STEPS) {
                pending = 0f;
                break;
            }
        }
    }

    /**
     * Hands {@code each} the numbers and colours set on the effect since the last call, once each, and forgets them: a
     * lasting cue publishes them after each advance.
     */
    public void drainSet(Consumer<CgVfxParam> each) {
        for (int i = 0; i < setCount; i++) {
            CgVfxParam param = set[i];
            set[i] = null;
            each.accept(param);
        }
        setCount = 0;
    }

    /**
     * Hands {@code tweens} each tween begun since the last call and {@code endings} the ending begun, if one was: a
     * lasting cue sends them as events, which every viewer plays itself.
     */
    public void drainMotion(Consumer<Tween> tweens, Consumer<CgVfxEnding> endings) {
        for (int i = 0; i < this.tweens.size(); i++) tweens.accept(this.tweens.get(i));
        this.tweens.clear();
        if (ending != null) {
            endings.accept(ending);
            ending = null;
        }
    }

    void animated(CgVfxParam param, float[] to, float seconds, CgEasing easing) {
        tweens.add(new Tween(param, to, seconds, easing));
    }

    void ended(CgVfxEnding ending) {
        this.ending = ending;
    }

    /** {@code param} was set on the effect. */
    void set(CgVfxParam param) {
        for (int i = 0; i < setCount; i++) if (set[i] == param) return;
        if (setCount == set.length) set = Arrays.copyOf(set, setCount * 2);
        set[setCount++] = param;
    }

    private void seek() {
        float target = effect.startAge;
        effect.startAge = 0f;
        int ticks = Math.round(Math.min(target, CgVfxSystem.MAX_START_SECONDS) / CgVfxSystem.TICK);
        effect.seeking = true;
        try {
            for (int t = 0; t < ticks && !done(); t++) effect.step(CgVfxSystem.TICK);
        } finally {
            effect.seeking = false;
        }
        if (target > CgVfxSystem.MAX_START_SECONDS) effect.age = target;
    }

    private void follow() {
        if (effect.followEntity < 0) return;
        if (!world.entities().pose(effect.followEntity, 1f, pose)) {
            effect.followEntity = -1;
            effect.stop();
            return;
        }
        CgEntityAttachments.point(effect.followPoint, pose, point);
        effect.moveToFollowed(point[0], point[1], point[2]);
    }
}
