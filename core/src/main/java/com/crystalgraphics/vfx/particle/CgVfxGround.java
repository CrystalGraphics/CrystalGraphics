package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgWorldQuery;

import java.util.Arrays;

/**
 * The host world's surfaces around one burst, for {@link CgVfxModule.Ground}: a grid of block columns centred on the
 * burst, each scanned once, lazily, the first time a particle is over it. A column keeps every surface between a little
 * above the burst and the first surface at or under it, so debris lands on a hillside, falls off a cliff into the
 * ravine, and stays under an overhang.
 *
 * <pre>{@code
 * // where it bursts: one for all its emitters
 * ground.reset(originX, originY, originZ, impactX, impactY, impactZ);
 * for (CgVfxEmitterInstance e : emitters) e.ground(ground).ground(groundHeight());   // the fixed height: no world
 *
 * // each tick, before the emitters
 * ground.fill(CgVfxGround.FILL_PER_TICK);
 * }</pre>
 *
 * <ul>
 *   <li>With no level (the harness), it answers the emitter's fixed ground height instead.</li>
 *   <li>A column not scanned yet, unloaded, or outside the grid has no floor: a particle there keeps falling. Never the
 *       fixed height, which in a world is not where the ground is.</li>
 *   <li>Render thread, like the slot it reads; it allocates only when made.</li>
 * </ul>
 */
public final class CgVfxGround {

    /** Columns scanned a tick: about a quarter of a millisecond at most. */
    public static final int FILL_PER_TICK = 24;

    private static final byte UNKNOWN = 0, QUEUED = 1, FILLED = 2, NONE = 3;
    private static final int FLOORS = 4, ABOVE = 24, BELOW = 64;

    private final int half, size;
    private final byte[] state;
    private final byte[] floorCount;
    private final float[] floors;
    private final int[] queue;
    private int queueHead, queueTail;
    private double originX, originY, originZ;
    private int centreX, centreZ, burstY;
    private int epoch;
    private boolean world;

    /** A grid {@code 2 * half + 1} columns on a side. */
    public CgVfxGround(int half) {
        this.half = half;
        this.size = 2 * half + 1;
        int cells = size * size;
        state = new byte[cells];
        floorCount = new byte[cells];
        floors = new float[cells * FLOORS];
        queue = new int[cells];
    }

    /** Centres the grid on a burst at {@code (x, y, z)} from the effect's origin, forgetting every column. */
    public CgVfxGround reset(double originX, double originY, double originZ, float x, float y, float z) {
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        centreX = (int) Math.floor(originX + x);
        centreZ = (int) Math.floor(originZ + z);
        burstY = (int) Math.floor(originY + y);
        forget();
        return this;
    }

    private void forget() {
        Arrays.fill(state, UNKNOWN);
        queueHead = queueTail = 0;
    }

    /** Scans up to {@code budget} columns particles have asked about; forgets them all when the level changes. */
    public void fill(int budget) {
        CgWorldQuery query = CgPlatform.get(CgWorldQuery.SERVICE);
        int now = query.levelEpoch();
        world = now != 0;
        if (now != epoch) {
            epoch = now;
            forget();
        }
        if (!world) return;
        for (int n = 0; n < budget && queueHead != queueTail; n++) {
            scan(query, queue[queueHead++]);
        }
    }

    private void scan(CgWorldQuery query, int cell) {
        int bx = centreX - half + cell % size, bz = centreZ - half + cell / size;
        if (!query.loaded(bx, bz)) {
            state[cell] = NONE;
            return;
        }
        int count = 0, lowest = Math.max(burstY - BELOW, query.minY());
        float aboveBottom = Float.NaN;   // the bottom of the solid span over the block being looked at, NaN for open air
        for (int b = Math.min(burstY + ABOVE, query.maxY() - 1); b >= lowest && count < FLOORS; b--) {
            float top = query.collisionTop(bx, b, bz);
            // A surface: a solid top with a gap over it, the next span up starting above it (its bottom counts from b + 1).
            if (!Float.isNaN(top) && (Float.isNaN(aboveBottom) || aboveBottom > top - 1f)) {
                floors[cell * FLOORS + count++] = b + top;
                if (b <= burstY) break;
            }
            aboveBottom = Float.isNaN(top) ? Float.NaN : query.collisionBottom(bx, b, bz);
        }
        floorCount[cell] = (byte) count;
        state[cell] = FILLED;
    }

    /**
     * The floor under a particle at {@code (x, y, z)} from the effect's origin, that was at {@code previousY} a tick
     * ago, as a height from the origin: the highest surface not above either, so it cannot tunnel through in one tick.
     * {@code fixed} with no level; NaN for no floor.
     */
    public float floor(float x, float y, float previousY, float z, float fixed) {
        if (!world) return fixed;
        int cx = (int) Math.floor(originX + x) - centreX + half, cz = (int) Math.floor(originZ + z) - centreZ + half;
        if (cx < 0 || cz < 0 || cx >= size || cz >= size) return Float.NaN;
        int cell = cz * size + cx;
        byte s = state[cell];
        if (s != FILLED) {
            if (s == UNKNOWN) {
                state[cell] = QUEUED;
                queue[queueTail++] = cell;
            }
            return Float.NaN;
        }
        double over = originY + Math.max(y, previousY) + 0.01;
        for (int k = 0, n = floorCount[cell]; k < n; k++) {
            float surface = floors[cell * FLOORS + k];
            if (surface <= over) return (float) (surface - originY);
        }
        return Float.NaN;
    }
}
