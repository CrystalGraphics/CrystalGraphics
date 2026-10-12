package com.crystalgraphics.vfx;

import com.crystalgraphics.platform.service.CgEntityQuery;
import com.crystalgraphics.platform.service.CgWorldQuery;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.net.CgVfxParams;
import com.crystalgraphics.world.CgEntityAttachments;

import java.util.Arrays;

/**
 * Runs an effect on a server: the half gameplay depends on (where a beam goes, what it strikes, when it bursts) on the
 * server's tick, against one level, while each client draws the effect. The effect is the same class the clients play;
 * {@link CgVfxEffect#authoritative} turns its shakes, post volumes, particles and moments off, and its collisions reach
 * only its own listeners, on the server thread.
 *
 * <pre>{@code
 * CgLevelQueries levels = CgPlatform.get(CgLevelQueries.SERVICE);
 * CgEnergyWave wave = new CgEnergyWave(CgEnergyWave.kamehameha(), x, y, z).collide();
 * CgVfxAuthority run = new CgVfxAuthority(wave, levels.world(level), levels.entities(level));
 * wave.onCollide(c -> damage(c.entity()));
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

    final CgWorldQuery world;
    final CgEntityQuery entities;
    private final CgVfxEffect effect;
    private final double[] pose = new double[CgEntityQuery.POSE_LENGTH], point = new double[3];
    private float pending;
    private boolean started;
    /** The numbers and colours set on the effect since {@link #publishParams} last ran. */
    private CgVfxParam[] set = new CgVfxParam[4];
    private int setCount;

    /** Takes {@code effect}, unplayed, to run against {@code world} and {@code entities}: one level's. */
    public CgVfxAuthority(CgVfxEffect effect, CgWorldQuery world, CgEntityQuery entities) {
        if (effect.system != null || effect.authority != null) throw new IllegalStateException("an effect plays once");
        this.effect = effect;
        this.world = world;
        this.entities = entities;
        effect.authority = this;
    }

    public CgVfxEffect effect() {
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
     * What a cue carrying {@code said} must carry once the numbers and colours set on the effect since the last call are
     * in it: a copy with each that differs, or null when none does. A lasting cue publishes it after each advance.
     */
    public CgVfxParams publishParams(CgVfxParams said) {
        CgVfxParams out = null;
        for (int i = 0; i < setCount; i++) {
            CgVfxParam param = set[i];
            set[i] = null;
            if (says(said, param)) continue;
            if (out == null) out = said == null ? CgVfxParams.of() : said.copy();
            if (param.size() == 1) {
                out.set(param.name(), effect.get(param));
            } else {
                out.set(param.name(), effect.get(param, 0), effect.get(param, 1), effect.get(param, 2), effect.get(param, 3));
            }
        }
        setCount = 0;
        return out;
    }

    private boolean says(CgVfxParams said, CgVfxParam param) {
        float[] v = said == null ? null : said.components(param.name());
        if (v == null || v.length < param.size()) return false;
        for (int k = 0; k < param.size(); k++) if (v[k] != effect.get(param, k)) return false;
        return true;
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
        if (!entities.pose(effect.followEntity, 1f, pose)) {
            effect.followEntity = -1;
            effect.stop();
            return;
        }
        CgEntityAttachments.point(effect.followPoint, pose, point);
        effect.moveToFollowed(point[0], point[1], point[2]);
    }
}
