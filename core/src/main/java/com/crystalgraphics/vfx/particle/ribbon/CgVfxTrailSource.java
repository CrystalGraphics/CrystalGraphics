package com.crystalgraphics.vfx.particle.ribbon;

import java.util.Arrays;

import com.crystalgraphics.vfx.particle.renderer.CgVfxRibbonRendererProperties;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRibbonRendererProperties.RenderAxis;
import com.crystalgraphics.vfx.particle.spawn.CgVfxSpawnPerUnit;

/**
 * Where a Cascade ribbon trail's particles are born: {@code FParticleRibbonEmitterInstance}'s source update, its
 * movement spawning and its rate spawning ({@code ParticleTrail2EmitterInstance.cpp}: {@code UpdateSourceData}
 * 2104-2179, {@code Spawn_Source} 1521-1963, {@code GetSpawnPerUnitAmount} 935-1046, {@code Spawn} 1188-1512), run a
 * tick at a time on the CPU for each of its trails. Each tick it answers spawn rows, which the Step places exactly as
 * Cascade does (Hermite points between the last and current source, their tangents, ups and tiled distance), each
 * trail's kills from its old end, and the refits Cascade's previous-tangent recalculation makes to the last chain.
 *
 * <pre>{@code
 * CgVfxTrailSource trail = new CgVfxTrailSource(renderer.trail(), perUnit, maxStep, capacity, offset);
 * // each tick, from the instance's books:
 * trail.tick(books, dt, time, x, y, z, qx, qy, qz, qw, ratePerSecond, perUnitAt);
 * for (int r = 0; r < trail.rows(); r++) pool.spawnTrail(slot, trail.rowFirst(r), trail.rowCount(r), trail.row(r), ...);
 * }</pre>
 *
 * <ul>
 *   <li>Where Cascade reads a particle's location after its birth (the previous-tangent recalculation, the tiled
 *       distance, a zero tangent's repair), the source keeps each anchor at its birth point plus its emitter's constant
 *       spawn offset: exact for a particle that does not move after birth.</li>
 *   <li>Bursts never spawn a trail: Cascade's ribbon spawning reads its spawn module's rate alone.</li>
 * </ul>
 */
public final class CgVfxTrailSource implements CgVfxTrailRows {

    /** Words of a spawn row after its four of slot and spawn indices: {@code CgVfxParticlePool.SPAWN_ROW_WORDS - 4}. */
    public static final int ROW_WORDS = 32;
    /** Words of a refit: trail, first id, count, mode, then four vec3s. */
    public static final int REFIT_WORDS = 16;
    /** A row's kind: Cascade's movement spawning (Hermite) or its rate spawning (at the source). */
    public static final int MOVEMENT = 1, RATE = 2;
    /** A refit's mode: a chain moved onto a new curve, or one particle's tangent set. */
    public static final int REFIT_CHAIN = 1, REFIT_TANGENT = 2;
    /** Cascade's HALF_WORLD_MAX, in blocks: a source past it has teleported. */
    private static final float HALF_WORLD_MAX = 1.0485760e4f;
    private static final float KINDA_SMALL = 1.0e-4f, SMALL = 1.0e-8f;

    /** How a trail spawns: its books, which hand out spawn indices and book their deaths. */
    public interface Spawner {
        /**
         * The next spawn index, its particle born {@code born} seconds into the tick, living the life spawn {@code key}
         * rolls (-1 its own); -1 where its emitter's capacity has no room.
         */
        int spawn(float born, int key);

        /** Whether spawn {@code k} is thinned out by the density share: no particle is born for it. */
        boolean thinned(int k);

        /** The life spawn {@code key} rolls, in seconds. */
        float life(int key);

        /**
         * The first of {@code n} spawn indices handed out for the GPU to spawn on as it decides (a trail's head): its
         * books count none of them alive, and keep the emitter until its longest life has passed.
         */
        default int reserve(int n) {
            throw new UnsupportedOperationException("these books spawn one at a time");
        }
    }

    private final CgVfxRibbonRendererProperties.Trail trail;
    private final CgVfxSpawnPerUnit perUnit;
    private final float maxStep, offsetX, offsetY, offsetZ;
    private final int trails;
    // Per trail: Cascade's source arrays.
    private final float[] lastPos, lastTan, lastUp, curPos, curTan, curUp;
    private final float[] trailSpawnTime, distanceLeft, tiled;
    private final boolean[] begun;
    // Per trail: its newest two anchors (spawned, not interpolated), N and NN: id, position, tangent, time, chain.
    private final int[] anchorId, anchorFirst, anchorPoints;
    private final float[] anchorPos, anchorTan, anchorTime;
    private final boolean[] anchored;
    // Per trail: its alive particles oldest first, a ring of ids and deaths; its first particle's life key.
    private final int[] ringIds, ringHead, ringSize, lifeKey;
    private final float[] ringDeath;
    private final int ring;
    private float rateLeft, loopFrom, loopTo;
    /** movement's answers: how many, and whether the rate is processed. */
    private int moving;
    private boolean ratePasses;
    // This tick's answers.
    private int[] rows = new int[ROW_WORDS * 4], rowFirst = new int[4], rowCount = new int[4];
    private int rowsUsed;
    private int[] refits = new int[REFIT_WORDS * 4];
    private int refitsUsed;
    private final int[] minIds;

    /**
     * A trail source for an emitter drawing {@code trail}: {@code perUnit} its spawn per unit (Cascade's movement
     * spawning, NONE for none), {@code maxStep} its farthest move in a tick (0 none), {@code capacity} the most it has
     * alive, {@code offset} its constant spawn offset in its source's frame (zeros for none).
     */
    public CgVfxTrailSource(CgVfxRibbonRendererProperties.Trail trail, CgVfxSpawnPerUnit perUnit, float maxStep, int capacity,
                            float[] offset) {
        this.trail = trail;
        this.perUnit = perUnit;
        this.maxStep = maxStep;
        this.trails = Math.max(1, trail.maxTrails());
        offsetX = offset[0];
        offsetY = offset[1];
        offsetZ = offset[2];
        int t = trails;
        lastPos = new float[t * 3];
        lastTan = new float[t * 3];
        lastUp = new float[t * 3];
        curPos = new float[t * 3];
        curTan = new float[t * 3];
        curUp = new float[t * 3];
        trailSpawnTime = new float[t];
        distanceLeft = new float[t];
        tiled = new float[t];
        begun = new boolean[t];
        anchorId = new int[t * 2];
        anchorFirst = new int[t * 2];
        anchorPoints = new int[t * 2];
        anchorPos = new float[t * 6];
        anchorTan = new float[t * 6];
        anchorTime = new float[t * 2];
        anchored = new boolean[t * 2];
        ring = Math.max(capacity, 1) + 1;
        ringIds = new int[t * ring];
        ringDeath = new float[t * ring];
        ringHead = new int[t];
        ringSize = new int[t];
        lifeKey = new int[t];
        minIds = new int[t];
    }

    /** Forgets every trail: a fresh slot. */
    public void reset() {
        Arrays.fill(begun, false);
        Arrays.fill(anchored, false);
        Arrays.fill(ringSize, 0);
        Arrays.fill(ringHead, 0);
        Arrays.fill(distanceLeft, 0f);
        Arrays.fill(tiled, 0f);
        Arrays.fill(minIds, 0);
        rateLeft = 0f;
        rowsUsed = refitsUsed = 0;
    }

    /** How many trails it keeps. */
    public int trails() {
        return trails;
    }

    /**
     * One Cascade tick of {@code dt} seconds from {@code time}, Cascade's {@code RunningTime} as the tick begins: the
     * source at {@code (x, y, z)} from the instance's origin, turned by {@code (qx, qy, qz, qw)}; {@code ratePerSecond}
     * its spawn rate now, {@code perUnitNow} its spawn per unit's count a unit now, {@code spawning} whether its loop
     * spawns this tick, its loop's seconds from {@code loopFrom} to {@code loopTo} across it.
     */
    public void tick(Spawner books, float dt, float time, float x, float y, float z, float qx, float qy, float qz, float qw,
                     float ratePerSecond, float perUnitNow, boolean spawning, float loopFrom, float loopTo) {
        rowsUsed = refitsUsed = 0;
        this.loopFrom = loopFrom;
        this.loopTo = loopTo;
        retire(time);
        float upX = 2f * (qx * qy - qw * qz), upY = 1f - 2f * (qx * qx + qz * qz), upZ = 2f * (qy * qz + qw * qx);
        float ox = 2f * (qy * offsetZ - qz * offsetY), oy = 2f * (qz * offsetX - qx * offsetZ), oz = 2f * (qx * offsetY - qy * offsetX);
        // The offset turned by the source's rotation: v + w t + q x t, t = 2 q x v.
        float offX = offsetX + qw * ox + (qy * oz - qz * oy), offY = offsetY + qw * oy + (qz * ox - qx * oz);
        float offZ = offsetZ + qw * oz + (qx * oy - qy * ox);
        boolean sourceUp = trail.renderAxis() == RenderAxis.SOURCE_UP;
        boolean processRate = true;
        for (int t = 0; t < trails; t++) {
            int v = t * 3;
            // UpdateSourceData: the first tick starts the trail where its source is.
            if (!begun[t]) {
                set(lastPos, v, x, y, z);
                set(lastTan, v, 0f, 0f, 0f);
                set(lastUp, v, upX, upY, upZ);
                trailSpawnTime[t] = time;
                begun[t] = true;
            }
            set(curPos, v, x, y, z);
            float since = time - trailSpawnTime[t];
            if (since != 0f) set(curTan, v, (x - lastPos[v]) / since, (y - lastPos[v + 1]) / since, (z - lastPos[v + 2]) / since);
            else set(curTan, v, 1f, 0f, 0f);
            set(curUp, v, upX, upY, upZ);

            int count = 0;
            if (perUnit.perBlock() > 0f && spawning) {
                movement(t, time, perUnitNow);
                count = moving;
                processRate = ratePasses;
            }
            int max = trail.maxParticles(), active = active();
            if (max > 0) {
                int kill = count + active - max;
                if (kill > 0) killOldest(t, kill);
                if (count + active() > max) count = Math.max(0, max - active());
            }
            if (count > 0) spawnMoving(books, t, dt, time, count, sourceUp, offX, offY, offZ);
        }
        if (processRate && spawning) spawnRate(books, dt, time, ratePerSecond, sourceUp, upX, upY, upZ, x, y, z, offX, offY, offZ);
    }

    /** GetSpawnPerUnitAmount: how many the source's move spawns, and whether the rate is processed, into the fields. */
    private void movement(int t, float time, float perUnitNow) {
        int v = t * 3;
        boolean moved = false;
        int count = 0;
        float perUnit = perUnitNow / this.perUnit.unit();
        if (perUnit >= 0f) {
            float left = distanceLeft[t];
            float dx = curPos[v] - lastPos[v], dy = curPos[v + 1] - lastPos[v + 1], dz = curPos[v + 2] - lastPos[v + 2];
            float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if ((maxStep > 0f && d > maxStep) || d > HALF_WORLD_MAX) {
                d = 0f;
                copy(curPos, v, lastPos, v);
            }
            float check = 0f;
            if (trail.tangentSpawnScale() > 0f) {
                float elapsed = time;
                if (active() == 0) {
                    if (elapsed == 0f) elapsed = KINDA_SMALL;
                    set(lastTan, v, dx / elapsed, dy / elapsed, dz / elapsed);
                }
                float div = time - trailSpawnTime[t];
                if (div == 0f) div = KINDA_SMALL;
                float cx = dx / div, cy = dy / div, cz = dz / div, cl = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
                float px = lastTan[v], py = lastTan[v + 1], pz = lastTan[v + 2], pl = (float) Math.sqrt(px * px + py * py + pz * pz);
                float dot = (cl > SMALL ? 1f / cl : 0f) * (pl > SMALL ? 1f / pl : 0f) * (cx * px + cy * py + cz * pz);
                check = (dot - 1f) * -0.5f;
            }
            if (d > 0f) {
                moved = d > this.perUnit.tolerance() * this.perUnit.unit();
                float total = (d + left) * perUnit + check * trail.tangentSpawnScale();
                count = trail.spawnInitialParticle() && active() == 0 && total < 1f ? 1 : (int) Math.floor(total);
                distanceLeft[t] = Math.max(0f, d + left - count * this.perUnit.unit());
            }
        }
        moving = count;
        ratePasses = !this.perUnit.ignoreRateWhenMoving() || !moved;
    }

    /** Spawn_Source's movement spawning for trail {@code t}: {@code count} along the source's Hermite since its last. */
    private void spawnMoving(Spawner books, int t, float dt, float time, int count, boolean sourceUp,
                             float offX, float offY, float offZ) {
        int v = t * 3;
        int n = t * 2, nn = t * 2 + 1;
        if (trail.recalculatePrevious() && !trail.everyFrame() && anchored[n] && anchored[nn]) {
            // The newest two spawned give the middle one's tangent, and its chain is laid again along it.
            float delta = time - anchorTime[nn];
            float tx = 0f, ty = 0f, tz = 0f;
            if (delta > SMALL) {
                tx = (curPos[v] - anchorPos[nn * 3]) / delta;
                ty = (curPos[v + 1] - anchorPos[nn * 3 + 1]) / delta;
                tz = (curPos[v + 2] - anchorPos[nn * 3 + 2]) / delta;
            }
            if (anchorPoints[n] > 0) {
                float diff = anchorTime[n] - anchorTime[nn];
                refit(REFIT_CHAIN, t, anchorFirst[n], anchorPoints[n], anchorPos, nn * 3, anchorTan, nn * 3,
                        anchorPos, n * 3, tx * diff, ty * diff, tz * diff);
                float inv = 1f / anchorPoints[n];
                set(anchorTan, n * 3, tx * diff * inv, ty * diff * inv, tz * diff * inv);
            }
            set(lastTan, v, tx, ty, tz);
        }
        float diff = time - trailSpawnTime[t];
        float inc = dt / count;
        boolean empty = ringSize[t] == 0;
        int first = -1;
        float startX = anchorPos[n * 3], startY = anchorPos[n * 3 + 1], startZ = anchorPos[n * 3 + 2];
        boolean started = anchored[n] && !empty;
        float tiledFrom = 0f;
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            float step = Math.min((i + 1f) / count, 1f);
            int k = books.spawn(i * inc, empty ? -1 : lifeKey[t]);
            if (k < 0) break;
            spawned++;
            if (first < 0) first = k;
            if (empty && !books.thinned(k)) {
                lifeKey[t] = k;
                empty = false;
            }
            if (books.thinned(k)) continue;
            float px = cubic(lastPos[v], lastTan[v] * diff, curPos[v], curTan[v] * diff, step) + offX;
            float py = cubic(lastPos[v + 1], lastTan[v + 1] * diff, curPos[v + 1], curTan[v + 1] * diff, step) + offY;
            float pz = cubic(lastPos[v + 2], lastTan[v + 2] * diff, curPos[v + 2], curTan[v + 2] * diff, step) + offZ;
            if (i == 0) {
                tiled[t] = started ? tiled[t] + distance(px, py, pz, startX, startY, startZ) : 0f;
                tiledFrom = tiled[t];
            } else {
                tiled[t] += distance(px, py, pz, startX, startY, startZ);
            }
            startX = px;
            startY = py;
            startZ = pz;
            started = true;
            enlist(t, k, time + books.life(lifeKey[t]) + i * inc);
            if (i == count - 1) {
                float tx = cubicDerivative(lastPos[v], lastTan[v] * diff, curPos[v], curTan[v] * diff, step) / count;
                float ty = cubicDerivative(lastPos[v + 1], lastTan[v + 1] * diff, curPos[v + 1], curTan[v + 1] * diff, step) / count;
                float tz = cubicDerivative(lastPos[v + 2], lastTan[v + 2] * diff, curPos[v + 2], curTan[v + 2] * diff, step) / count;
                anchor(t, k, first, count, px, py, pz, tx, ty, tz, time - diff * (1f - step));
            }
        }
        if (spawned == 0) return;
        int[] r = row(first, spawned);
        int at = (rowsUsed - 1) * ROW_WORDS;
        r[at] = Float.floatToRawIntBits(0f);
        r[at + 1] = Float.floatToRawIntBits((count - 1) * inc);
        r[at + 2] = Float.floatToRawIntBits(inc);
        r[at + 3] = Float.floatToRawIntBits(dt);
        put(r, at + 4, lastPos, v, loopFrom);
        put(r, at + 8, curPos, v, loopTo);
        r[at + 12] = Float.floatToRawIntBits(lastTan[v] * diff);
        r[at + 13] = Float.floatToRawIntBits(lastTan[v + 1] * diff);
        r[at + 14] = Float.floatToRawIntBits(lastTan[v + 2] * diff);
        r[at + 15] = Float.floatToRawIntBits(t);
        r[at + 16] = Float.floatToRawIntBits(curTan[v] * diff);
        r[at + 17] = Float.floatToRawIntBits(curTan[v + 1] * diff);
        r[at + 18] = Float.floatToRawIntBits(curTan[v + 2] * diff);
        r[at + 19] = Float.floatToRawIntBits(MOVEMENT);
        upAt(r, at + 20, lastUp, v, sourceUp);
        r[at + 23] = Float.floatToRawIntBits(tiledFrom);
        upAt(r, at + 24, curUp, v, sourceUp);
        r[at + 27] = Float.floatToRawIntBits(count);
        r[at + 28] = lifeKey[t];
        // The last source becomes this one: the next move is measured from here.
        copy(curPos, v, lastPos, v);
        copy(curTan, v, lastTan, v);
        copy(curUp, v, lastUp, v);
        trailSpawnTime[t] = time;
    }

    /** Spawn's rate spawning, trail 0's: at the source, each its own row for its tangent's repair. */
    private void spawnRate(Spawner books, float dt, float time, float rate, boolean sourceUp, float upX, float upY, float upZ,
                           float x, float y, float z, float offX, float offY, float offZ) {
        int t = 0;
        float leftover = rateLeft, total = leftover + dt * rate;
        int number = (int) Math.floor(total);
        float inc = rate > 0f ? 1f / rate : 0f;
        float start = dt + leftover * inc - inc;
        rateLeft = total - number;
        int max = trail.maxParticles();
        if (max > 0) {
            int kill = number + active() - max;
            if (kill > 0) killOldest(t, kill);
        }
        if (!(rate > 0f) || number <= 0) return;
        int n = t * 2, nn = t * 2 + 1;
        for (int i = 0; i < number; i++) {
            float age = start - i * inc, stored = dt * Math.min((i + 1f) / number, 1f);
            boolean empty = ringSize[t] == 0;
            int k = books.spawn(dt - age, empty ? -1 : lifeKey[t]);
            if (k < 0) return;
            if (books.thinned(k)) continue;
            if (empty) {
                lifeKey[t] = k;
                tiled[t] = 0f;
            } else if (anchored[n]) {
                tiled[t] += distance(x + offX, y + offY, z + offZ, anchorPos[n * 3], anchorPos[n * 3 + 1], anchorPos[n * 3 + 2]);
            }
            float spawnTime = time - dt + stored;
            // A zero tangent's repair (Spawn :1405-1419): toward the newest spawned, over their times.
            float fx = 0f, fy = 0f, fz = 0f;
            if (!empty && anchored[n]) {
                float d = spawnTime - anchorTime[n];
                if (d != 0f) {
                    fx = (x + offX - anchorPos[n * 3]) / d;
                    fy = (y + offY - anchorPos[n * 3 + 1]) / d;
                    fz = (z + offZ - anchorPos[n * 3 + 2]) / d;
                }
            }
            if (trail.recalculatePrevious() && !trail.everyFrame() && !empty && anchored[n]) {
                // The newest spawned's tangent, from the new one to the one before it (Spawn :1428-1458).
                int from = anchored[nn] ? nn : n;
                float d = spawnTime - anchorTime[from];
                if (d != 0f) {
                    float tx = (x + offX - anchorPos[from * 3]) / d, ty = (y + offY - anchorPos[from * 3 + 1]) / d;
                    float tz = (z + offZ - anchorPos[from * 3 + 2]) / d;
                    refitTangent(t, anchorId[n], tx, ty, tz);
                    set(anchorTan, n * 3, tx, ty, tz);
                }
            }
            enlist(t, k, time + dt + books.life(lifeKey[t]) - age);
            int[] r = row(k, 1);
            int at = (rowsUsed - 1) * ROW_WORDS;
            r[at] = Float.floatToRawIntBits(dt - age);
            r[at + 1] = r[at];
            r[at + 2] = 0;
            r[at + 3] = Float.floatToRawIntBits(dt);
            put(r, at + 4, curPos, 0, loopFrom);
            put(r, at + 8, curPos, 0, loopTo);
            r[at + 12] = Float.floatToRawIntBits(fx);
            r[at + 13] = Float.floatToRawIntBits(fy);
            r[at + 14] = Float.floatToRawIntBits(fz);
            r[at + 15] = Float.floatToRawIntBits(t);
            r[at + 19] = Float.floatToRawIntBits(RATE);
            float ux = sourceUp ? upX : 0f, uy = sourceUp ? upY : 1f, uz = sourceUp ? upZ : 0f;
            r[at + 20] = Float.floatToRawIntBits(ux);
            r[at + 21] = Float.floatToRawIntBits(uy);
            r[at + 22] = Float.floatToRawIntBits(uz);
            r[at + 23] = Float.floatToRawIntBits(tiled[t]);
            r[at + 24] = r[at + 20];
            r[at + 25] = r[at + 21];
            r[at + 26] = r[at + 22];
            r[at + 27] = Float.floatToRawIntBits(1f);
            r[at + 28] = lifeKey[t];
            anchor(t, k, k, 1, x + offX, y + offY, z + offZ, fx, fy, fz, spawnTime);
        }
    }

    /** This tick's spawn rows, in order. */
    public int rows() {
        return rowsUsed;
    }

    /** Row {@code r}'s first spawn index. */
    public int rowFirst(int r) {
        return rowFirst[r];
    }

    /** Row {@code r}'s spawn candidates. */
    public int rowCount(int r) {
        return rowCount[r];
    }

    /** Every row's words, {@link #ROW_WORDS} each from {@code r * ROW_WORDS}: a spawn row's after its first four. */
    public int[] rowWords() {
        return rows;
    }

    /** This tick's refits, {@link #REFIT_WORDS} words each in {@link #refitWords()}. */
    public int refits() {
        return refitsUsed;
    }

    public int[] refitWords() {
        return refits;
    }

    /** Each trail's oldest spawn index still alive: the Step ends older ones of the trail. */
    public int[] minIds() {
        return minIds;
    }

    private int[] row(int first, int count) {
        if (rowFirst.length == rowsUsed) {
            rowFirst = Arrays.copyOf(rowFirst, rowsUsed * 2);
            rowCount = Arrays.copyOf(rowCount, rowsUsed * 2);
            rows = Arrays.copyOf(rows, rowsUsed * 2 * ROW_WORDS);
        }
        rowFirst[rowsUsed] = first;
        rowCount[rowsUsed] = count;
        Arrays.fill(rows, rowsUsed * ROW_WORDS, (rowsUsed + 1) * ROW_WORDS, 0);
        rowsUsed++;
        return rows;
    }

    private void refit(int mode, int t, int firstId, int count, float[] p0, int a0, float[] t0, int b0, float[] p1, int a1,
                       float t1x, float t1y, float t1z) {
        int at = grow();
        refits[at] = t;
        refits[at + 1] = firstId;
        refits[at + 2] = count;
        refits[at + 3] = mode;
        for (int c = 0; c < 3; c++) {
            refits[at + 4 + c] = Float.floatToRawIntBits(p0[a0 + c]);
            refits[at + 7 + c] = Float.floatToRawIntBits(t0[b0 + c]);
            refits[at + 10 + c] = Float.floatToRawIntBits(p1[a1 + c]);
        }
        refits[at + 13] = Float.floatToRawIntBits(t1x);
        refits[at + 14] = Float.floatToRawIntBits(t1y);
        refits[at + 15] = Float.floatToRawIntBits(t1z);
    }

    private void refitTangent(int t, int id, float tx, float ty, float tz) {
        int at = grow();
        Arrays.fill(refits, at, at + REFIT_WORDS, 0);
        refits[at] = t;
        refits[at + 1] = id;
        refits[at + 2] = 1;
        refits[at + 3] = REFIT_TANGENT;
        refits[at + 13] = Float.floatToRawIntBits(tx);
        refits[at + 14] = Float.floatToRawIntBits(ty);
        refits[at + 15] = Float.floatToRawIntBits(tz);
    }

    private int grow() {
        if (refits.length < (refitsUsed + 1) * REFIT_WORDS) refits = Arrays.copyOf(refits, refits.length * 2);
        return refitsUsed++ * REFIT_WORDS;
    }

    /** The newest spawned of trail {@code t} is {@code k}, its chain from {@code first}: the one before becomes NN. */
    private void anchor(int t, int k, int first, int points, float x, float y, float z, float tx, float ty, float tz, float time) {
        int n = t * 2, nn = t * 2 + 1;
        if (anchored[n]) {
            anchored[nn] = true;
            anchorId[nn] = anchorId[n];
            anchorFirst[nn] = anchorFirst[n];
            anchorPoints[nn] = anchorPoints[n];
            anchorTime[nn] = anchorTime[n];
            System.arraycopy(anchorPos, n * 3, anchorPos, nn * 3, 3);
            System.arraycopy(anchorTan, n * 3, anchorTan, nn * 3, 3);
        }
        anchored[n] = true;
        anchorId[n] = k;
        anchorFirst[n] = first;
        anchorPoints[n] = points;
        anchorTime[n] = time;
        set(anchorPos, n * 3, x, y, z);
        set(anchorTan, n * 3, tx, ty, tz);
    }

    private void enlist(int t, int k, float death) {
        if (ringSize[t] == ring) {
            ringHead[t] = (ringHead[t] + 1) % ring;
            ringSize[t]--;
        }
        int at = t * ring + (ringHead[t] + ringSize[t]) % ring;
        ringIds[at] = k;
        ringDeath[at] = death;
        ringSize[t]++;
    }

    /** Every particle whose life is over by {@code time} gone from its trail; an emptied trail forgets its anchors. */
    private void retire(float time) {
        for (int t = 0; t < trails; t++) {
            while (ringSize[t] > 0 && ringDeath[t * ring + ringHead[t]] <= time) {
                ringHead[t] = (ringHead[t] + 1) % ring;
                ringSize[t]--;
            }
            if (ringSize[t] == 0) {
                anchored[t * 2] = anchored[t * 2 + 1] = false;
            }
        }
    }

    /** KillParticles(trail, n): its {@code count} oldest end. */
    private void killOldest(int t, int count) {
        for (int i = 0; i < count && ringSize[t] > 0; i++) {
            ringHead[t] = (ringHead[t] + 1) % ring;
            ringSize[t]--;
        }
        minIds[t] = ringSize[t] > 0 ? ringIds[t * ring + ringHead[t]] : Integer.MAX_VALUE;
        if (ringSize[t] == 0) anchored[t * 2] = anchored[t * 2 + 1] = false;
    }

    /** Every trail's particles alive: Cascade's ActiveParticles. */
    private int active() {
        int sum = 0;
        for (int t = 0; t < trails; t++) sum += ringSize[t];
        return sum;
    }

    private static float cubic(float p0, float t0, float p1, float t1, float a) {
        float a2 = a * a, a3 = a2 * a;
        return (2f * a3 - 3f * a2 + 1f) * p0 + (a3 - 2f * a2 + a) * t0 + (a3 - a2) * t1 + (-2f * a3 + 3f * a2) * p1;
    }

    private static float cubicDerivative(float p0, float t0, float p1, float t1, float a) {
        float x = 6f * p0 + 3f * t0 + 3f * t1 - 6f * p1, y = -6f * p0 - 4f * t0 - 2f * t1 + 6f * p1;
        return x * a * a + y * a + t0;
    }

    private static float distance(float ax, float ay, float az, float bx, float by, float bz) {
        float dx = ax - bx, dy = ay - by, dz = az - bz;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static void set(float[] a, int at, float x, float y, float z) {
        a[at] = x;
        a[at + 1] = y;
        a[at + 2] = z;
    }

    private static void copy(float[] from, int a, float[] to, int b) {
        to[b] = from[a];
        to[b + 1] = from[a + 1];
        to[b + 2] = from[a + 2];
    }

    private static void put(int[] r, int at, float[] v, int from, float w) {
        r[at] = Float.floatToRawIntBits(v[from]);
        r[at + 1] = Float.floatToRawIntBits(v[from + 1]);
        r[at + 2] = Float.floatToRawIntBits(v[from + 2]);
        r[at + 3] = Float.floatToRawIntBits(w);
    }

    /** A source-up trail's up, or Cascade's default (0, 0, 1), the host's +Y. */
    private static void upAt(int[] r, int at, float[] up, int v, boolean sourceUp) {
        r[at] = Float.floatToRawIntBits(sourceUp ? up[v] : 0f);
        r[at + 1] = Float.floatToRawIntBits(sourceUp ? up[v + 1] : 1f);
        r[at + 2] = Float.floatToRawIntBits(sourceUp ? up[v + 2] : 0f);
    }
}
