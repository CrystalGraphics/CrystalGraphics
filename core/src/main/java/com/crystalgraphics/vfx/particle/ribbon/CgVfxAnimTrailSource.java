package com.crystalgraphics.vfx.particle.ribbon;

import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRibbonRendererProperties;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRibbonRendererProperties.AnimTrail;

import java.util.Arrays;

/**
 * An anim trail's spawning: Cascade's {@code FParticleAnimTrailEmitterInstance} ({@code ParticleTrail2EmitterInstance.cpp}),
 * a sample each tick of the edge between two points its effect sets, a sword's hilt and tip, laid as a strip through
 * the samples. Its emitter's books tick it; its rows go to the pool's Step, which spawns each sample.
 *
 * <pre>{@code
 * // the effect moves the edge each tick, as user parameters from its origin
 * effect.values().set(FIRST, hiltX, hiltY, hiltZ);
 * effect.values().set(SECOND, tipX, tipY, tipZ);
 * }</pre>
 *
 * <ul>
 *   <li>One sample a tick, at the edge as the tick ends: Cascade spawns no rate, no burst and no interpolated samples
 *       for an anim trail ({@code Spawn}, :3287-3448).</li>
 *   <li>Each sample keeps its tangent and interpolation parameter from its neighbours as they were when it was laid
 *       ({@code RecalculateTangentAndInterpolationParam}, :2901-2932); the one before is worked out again beside it, a
 *       refit, unless {@code recalculate_previous} is off.</li>
 *   <li>Stopped, the trail is dead: no sample joins it, and a start lays a new chain. With {@code retract} it keeps
 *       sampling while it loses that many of its oldest a tick, its alpha and width fading by {@code fade} a tick.</li>
 *   <li>Allocates nothing once made.</li>
 * </ul>
 */
public final class CgVfxAnimTrailSource implements CgVfxTrailRows {

    /** A row's kind: an anim trail's sample. */
    public static final int SAMPLE = 3;
    /** A refit's mode: a sample's tangent and interpolation parameter set. */
    public static final int REFIT_SAMPLE = 3;
    /** {@code FVector::IsNearlyZero}'s tolerance, in blocks. */
    private static final float NEARLY_ZERO = 1e-6f;

    private final AnimTrail anim;
    private final int[] rows = new int[CgVfxTrailSource.ROW_WORDS], refits = new int[CgVfxTrailSource.REFIT_WORDS];
    private final int[] minIds = new int[1];
    private int rowFirst, rowsUsed, refitsUsed;
    // Every sample alive, oldest first, with when it dies.
    private final int ring;
    private final int[] ringIds;
    private final float[] ringDeath;
    private int ringHead, ringSize;
    // The chain being laid: its first spawn, its samples alive, its newest two.
    private int chain, chainFirst, chainSize, newestId = -1;
    private final float[] newest = new float[3], before = new float[3];
    private float tiled, fadeAlpha = 1f, fadeWidth = 1f;
    private boolean stopped;

    /** The spawning for {@code anim}, keeping at most {@code capacity} samples alive. */
    public CgVfxAnimTrailSource(AnimTrail anim, int capacity) {
        this.anim = anim;
        ring = Math.max(capacity, 1) + 1;
        ringIds = new int[ring];
        ringDeath = new float[ring];
    }

    @Override
    public void reset() {
        rowsUsed = refitsUsed = ringHead = ringSize = chainSize = 0;
        chain = chainFirst = 0;
        newestId = -1;
        minIds[0] = 0;
        tiled = 0f;
        fadeAlpha = fadeWidth = 1f;
        stopped = false;
    }

    /**
     * One tick of {@code dt} seconds from {@code time}: a sample of the edge {@code view}'s user parameters set, while
     * {@code active} or while a retracting trail still has samples; its loop's seconds from {@code loopFrom} to
     * {@code loopTo}.
     */
    public void tick(CgVfxTrailSource.Spawner books, float dt, float time, CgVfxInstanceView view, boolean active,
                     float loopFrom, float loopTo) {
        rowsUsed = refitsUsed = 0;
        retire(time);
        boolean retracting = anim.retract() > 0;
        if (!active) {
            if (!stopped) {
                stopped = true;
                // The trail is dead: nothing joins it again.
                if (!retracting) newestId = -1;
            }
            if (retracting) {
                killOldest(anim.retract());
                fadeAlpha = Math.max(0f, fadeAlpha - anim.fadeAlpha());
                fadeWidth = Math.max(0f, fadeWidth - anim.fadeWidth());
            }
            if (!retracting || ringSize == 0) return;
        } else if (stopped) {
            stopped = false;
            fadeAlpha = fadeWidth = 1f;
            newestId = -1;
        }
        if (anim.life() > 0 && ringSize >= anim.life()) killOldest(ringSize - anim.life() + 1);
        sample(books, dt, time, view, loopFrom, loopTo);
    }

    /** {@code SpawnParticle} (:3029-3286): the edge's sample, its neighbours' tangents worked out again. */
    private void sample(CgVfxTrailSource.Spawner books, float dt, float time, CgVfxInstanceView view, float loopFrom, float loopTo) {
        float fx = view.user(anim.first(), 0, 0f), fy = view.user(anim.first(), 1, 0f), fz = view.user(anim.first(), 2, 0f);
        float sx = view.user(anim.second(), 0, 0f), sy = view.user(anim.second(), 1, 0f), sz = view.user(anim.second(), 2, 0f);
        float dx = sx - fx, dy = sy - fy, dz = sz - fz, length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length > 0f) {
            dx /= length;
            dy /= length;
            dz /= length;
        }
        // Its width modes at the notify's width of 1: each a half edge either side of its middle.
        length *= 0.5f;
        float x, y, z;
        if (anim.widthMode() == CgVfxRibbonRendererProperties.WidthMode.SECOND) {
            x = sx - dx * length;
            y = sy - dy * length;
            z = sz - dz * length;
        } else {
            x = fx + dx * length;
            y = fy + dy * length;
            z = fz + dz * length;
        }
        int k = books.spawn(0f, -1);
        if (k < 0 || books.thinned(k)) return;
        boolean first = newestId < 0 || chainSize == 0;
        float tx = 0f, ty = 0f, tz = 0f, interp = 0f;
        if (first) {
            chain++;
            chainFirst = k;
            chainSize = 0;
            tiled = 0f;
        } else {
            // The new sample, with no newer: toward it from the last.
            float ex = x - newest[0], ey = y - newest[1], ez = z - newest[2], segment = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
            if (segment > 0f) {
                tx = ex / segment;
                ty = ey / segment;
                tz = ez / segment;
            }
            interp = (float) Math.sqrt(segment);
            if (chainSize == 1) {
                // The only one before becomes the trail's end: its tangent the same.
                refit(newestId, tx, ty, tz, interp);
            } else if (anim.recalculatePrevious() && !anim.everyFrame()) {
                // The last, between the new one and the one before it; the new one takes its tangent.
                float px = x - before[0], py = y - before[1], pz = z - before[2];
                if (!(Math.abs(px) <= NEARLY_ZERO && Math.abs(py) <= NEARLY_ZERO && Math.abs(pz) <= NEARLY_ZERO)) {
                    float l = (float) Math.sqrt(px * px + py * py + pz * pz);
                    px /= l;
                    py /= l;
                    pz /= l;
                }
                refit(newestId, px, py, pz, interp);
                tx = px;
                ty = py;
                tz = pz;
            }
            if (anim.tilingDistance() > 0f) tiled += segment;
        }
        enlist(k, time + books.life(k));
        chainSize++;
        rowFirst = k;
        rowsUsed = 1;
        Arrays.fill(rows, 0);
        rows[3] = Float.floatToRawIntBits(dt);
        rows[4] = rows[8] = Float.floatToRawIntBits(x);
        rows[5] = rows[9] = Float.floatToRawIntBits(y);
        rows[6] = rows[10] = Float.floatToRawIntBits(z);
        rows[7] = Float.floatToRawIntBits(loopFrom);
        rows[11] = Float.floatToRawIntBits(loopTo);
        rows[12] = Float.floatToRawIntBits(tx);
        rows[13] = Float.floatToRawIntBits(ty);
        rows[14] = Float.floatToRawIntBits(tz);
        rows[16] = Float.floatToRawIntBits(dx * length);
        rows[17] = Float.floatToRawIntBits(dy * length);
        rows[18] = Float.floatToRawIntBits(dz * length);
        rows[19] = Float.floatToRawIntBits(SAMPLE);
        rows[23] = Float.floatToRawIntBits(tiled);
        rows[27] = Float.floatToRawIntBits(1f);
        rows[28] = k;
        rows[29] = Float.floatToRawIntBits(chain);
        rows[30] = Float.floatToRawIntBits(interp);
        before[0] = newest[0];
        before[1] = newest[1];
        before[2] = newest[2];
        newest[0] = x;
        newest[1] = y;
        newest[2] = z;
        newestId = k;
    }

    private void refit(int id, float tx, float ty, float tz, float interp) {
        Arrays.fill(refits, 0);
        refits[1] = id;
        refits[2] = 1;
        refits[3] = REFIT_SAMPLE;
        refits[4] = Float.floatToRawIntBits(interp);
        refits[13] = Float.floatToRawIntBits(tx);
        refits[14] = Float.floatToRawIntBits(ty);
        refits[15] = Float.floatToRawIntBits(tz);
        refitsUsed = 1;
    }

    private void enlist(int id, float death) {
        if (ringSize == ring) pop();
        int at = (ringHead + ringSize) % ring;
        ringIds[at] = id;
        ringDeath[at] = death;
        ringSize++;
    }

    /** Drops the samples whose lives have ended by {@code time}. */
    private void retire(float time) {
        while (ringSize > 0 && ringDeath[ringHead] <= time) pop();
    }

    /** Ends its {@code n} oldest samples: the Step kills them by id. */
    private void killOldest(int n) {
        for (int i = 0; i < n && ringSize > 0; i++) pop();
        minIds[0] = ringSize > 0 ? ringIds[ringHead] : newestId + 1;
    }

    private void pop() {
        if (ringIds[ringHead] >= chainFirst && chainSize > 0) chainSize--;
        ringHead = (ringHead + 1) % ring;
        ringSize--;
    }

    /** Its alpha this tick: 1, fading by {@code fade.alpha} a tick once a retracting trail has stopped. */
    public float fadeAlpha() {
        return fadeAlpha;
    }

    /** Its width this tick, as {@link #fadeAlpha} by {@code fade.width}. */
    public float fadeWidth() {
        return fadeWidth;
    }

    @Override
    public int trails() {
        return 1;
    }

    @Override
    public int rows() {
        return rowsUsed;
    }

    @Override
    public int rowFirst(int r) {
        return rowFirst;
    }

    @Override
    public int rowCount(int r) {
        return 1;
    }

    @Override
    public int[] rowWords() {
        return rows;
    }

    @Override
    public int refits() {
        return refitsUsed;
    }

    @Override
    public int[] refitWords() {
        return refits;
    }

    @Override
    public int[] minIds() {
        return minIds;
    }
}
