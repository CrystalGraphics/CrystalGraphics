package com.crystalgraphics.collision.entity;

import com.crystalgraphics.collision.CgCollisionWorld;
import com.crystalgraphics.platform.world.CgBoxSink;
import com.crystalgraphics.platform.world.CgEntityQuery;

import java.util.Arrays;
import java.util.UUID;
import java.util.function.IntConsumer;

/**
 * One world's entities as a {@link CgCollisionWorld.Batch} first finds them: each 16-block cell asked of the host once,
 * and each entity read once, so a thousand hitboxes in a tick ask the host about an entity once rather than a thousand
 * times. What it has not seen it asks the host.
 *
 * <ul>
 *   <li>It caches only past {@link #CACHE_AFTER} asks in a batch, since filling its cells costs more than a few queries
 *       save: until then the host answers.</li>
 *   <li>Bounds spanning more than {@link #MAX_CELLS} cells go straight to the host, uncached.</li>
 *   <li>An entity {@link #touch}ed is the host's again for the rest of the batch, and every query tests it wherever it
 *       now is: what a hit's listener damages, moves or kills is never answered as it was.</li>
 * </ul>
 *
 * <p>Kept per thread by {@link CgCollisionWorld#batch}; a mod never makes one.
 */
public final class CgEntityCache extends CgEntityTable {

    static final int MAX_CELLS = 512;
    /** Break-even, measured: 1,000 overlaps over 200 entities 29% faster batched, 100 slower (CgEntityCacheTest). */
    static final int CACHE_AFTER = 160;
    /** Grows a cell's bounds as it is asked of the host, so a box ending on the cell's face is found in it. */
    private static final double EDGE = 1.0e-7;

    private CgEntityQuery host = CgEntityQuery.NONE;
    private float partialTick;
    private int generation = -1;
    private int asked;

    private boolean[] stale = new boolean[64];
    /** The stale entries: few, each tested live by every query. */
    private int[] touched = new int[16];
    private int touchedCount;

    private final Collector collector = new Collector();
    private final Probe probe = new Probe();

    /** Forgets everything, answering for {@code host} at {@code partialTick} from now on. */
    public void reset(CgEntityQuery host, float partialTick, int generation) {
        this.host = host;
        this.partialTick = partialTick;
        this.generation = generation;
        asked = 0;
        forget();
    }

    /** Forgets every entity and cell, still answering for the same batch. */
    public void forget() {
        clearAll();
        touchedCount = 0;
    }

    public boolean answersFor(CgEntityQuery host, float partialTick, int generation) {
        return this.generation == generation && this.host == host && this.partialTick == partialTick;
    }

    /** Hands {@code id} back to the host for the rest of the batch: it moved, died or changed. */
    public void touch(int id) {
        int e = entry(id);
        if (e < 0) e = add(id);
        if (stale[e]) return;
        stale[e] = true;
        if (touchedCount == touched.length) touched = Arrays.copyOf(touched, touchedCount * 2);
        touched[touchedCount++] = e;
    }

    /** Counts one ask; whether the batch has asked enough to answer from the cache. */
    public boolean warm() {
        return ++asked > CACHE_AFTER;
    }

    @Override
    boolean listed(int e) {
        return !stale[e];
    }

    @Override
    void grown(int size) {
        stale = Arrays.copyOf(stale, size);
    }

    private int add(int id) {
        int e = read(host, partialTick, id);
        stale[e] = false;
        return e;
    }

    /** The entry answering for {@code id}, or -1 for the host: unseen, or touched. */
    private int fresh(int id) {
        int e = entry(id);
        return e >= 0 && stale[e] ? -1 : e;
    }

    // ---- the host's questions, from what was seen

    @Override
    public int localPlayer() {
        return host.localPlayer();
    }

    @Override
    public int cameraEntity() {
        return host.cameraEntity();
    }

    @Override
    public boolean pose(int id, float partialTick, double[] out) {
        return host.pose(id, partialTick, out);
    }

    @Override
    public int flags(int id) {
        int e = fresh(id);
        return e < 0 ? host.flags(id) : flags[e];
    }

    @Override
    public int kind(int id) {
        int e = fresh(id);
        return e < 0 ? host.kind(id) : kinds[e];
    }

    @Override
    public boolean box(int id, float partialTick, CgBoxSink out) {
        int e = fresh(id);
        if (e < 0 || partialTick != this.partialTick) return host.box(id, partialTick, out);
        if (!boxed[e]) return false;
        int b = e * 6;
        out.set(boxes[b], boxes[b + 1], boxes[b + 2], boxes[b + 3], boxes[b + 4], boxes[b + 5]);
        return true;
    }

    @Override
    public int parent(int id) {
        int e = fresh(id);
        return e < 0 ? host.parent(id) : parents[e];
    }

    @Override
    public int rootVehicle(int id) {
        int e = fresh(id);
        return e < 0 ? host.rootVehicle(id) : roots[e];
    }

    @Override
    public <V> V entity(int id) {
        return host.entity(id);
    }

    @Override
    public <V> V entity(UUID id) {
        return host.entity(id);
    }

    @Override
    public void within(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                       IntConsumer visitor) {
        long span = (long) (cellOf(maxX) - cellOf(minX) + 1) * (cellOf(maxY) - cellOf(minY) + 1)
                * (cellOf(maxZ) - cellOf(minZ) + 1);
        if (span > MAX_CELLS) {
            host.within(minX, minY, minZ, maxX, maxY, maxZ, visitor);
            return;
        }
        walk(minX, minY, minZ, maxX, maxY, maxZ, visitor);
        for (int t = 0; t < touchedCount; t++) {
            int id = ids[touched[t]];
            if (host.box(id, partialTick, probe) && probe.meets(minX, minY, minZ, maxX, maxY, maxZ)) visitor.accept(id);
        }
    }

    /** The cell's slot, asking the host for it the first time. */
    @Override
    int cellSlot(int cx, int cy, int cz) {
        long k = key(cx, cy, cz);
        int s = findCell(k);
        if (cellKeys[s] == k) return s;
        if ((cells + 1) * 2 > cellKeys.length) {
            growCells(cells + 1);
            s = findCell(k);
        }
        int start = memberCount;
        double x = cx << SHIFT, y = cy << SHIFT, z = cz << SHIFT;
        host.within(x - EDGE, y - EDGE, z - EDGE, x + CELL + EDGE, y + CELL + EDGE, z + CELL + EDGE, collector);
        cellKeys[s] = k;
        cellStart[s] = start;
        cellLength[s] = memberCount - start;
        cells++;
        return s;
    }

    /** Each entity the host finds in a cell: read once, listed in the cell. */
    private final class Collector implements IntConsumer {
        @Override
        public void accept(int id) {
            int e = entry(id);
            addMember(e < 0 ? add(id) : e);
        }
    }

    /** A touched entity's box, as the host has it now. */
    private static final class Probe implements CgBoxSink {
        private double minX, minY, minZ, maxX, maxY, maxZ;

        @Override
        public CgBoxSink set(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            return this;
        }

        boolean meets(double x0, double y0, double z0, double x1, double y1, double z1) {
            return minX <= x1 && maxX >= x0 && minY <= y1 && maxY >= y0 && minZ <= z1 && maxZ >= z0;
        }
    }
}
