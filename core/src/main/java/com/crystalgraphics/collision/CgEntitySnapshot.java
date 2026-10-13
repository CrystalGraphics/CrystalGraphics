package com.crystalgraphics.collision;

import com.crystalgraphics.platform.service.CgBoxSink;
import com.crystalgraphics.platform.service.CgEntityQuery;
import com.crystalgraphics.shape.bounds.CgAABBc;

import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * A level's entities in a box, copied on the level's thread and read from any: each one's flags, kind, parent, mount,
 * box and pose as they were. Never changed once made.
 *
 * <ul>
 *   <li>It holds the entities whose boxes touch the box, and the wholes and mounts they belong to; any other id
 *       answers as none (flags 0, parent -1, no box).</li>
 *   <li>Boxes and poses are where the host read them at the snapshot's partial tick, whatever a caller asks.</li>
 * </ul>
 */
final class CgEntitySnapshot extends CgEntityTable {

    /** An entity spanning more cells than this is tested by every walk instead of listed. */
    private static final int MAX_CELLS = 64;

    private final int localPlayer, camera;
    private double[] poses = new double[POSE_LENGTH * 64];
    /** Entries too wide for the cells, tested by every walk. */
    private int[] wide;

    private CgEntitySnapshot(CgEntityQuery host) {
        localPlayer = host.localPlayer();
        camera = host.cameraEntity();
    }

    /** {@code host}'s entities touching {@code bounds}, read now at {@code partialTick}. The host's thread. */
    static CgEntitySnapshot of(CgEntityQuery host, float partialTick, CgAABBc bounds) {
        CgEntitySnapshot s = new CgEntitySnapshot(host);
        double[] pose = new double[POSE_LENGTH];
        IntConsumer reader = id -> {
            if (id < 0 || s.entry(id) >= 0) return;
            int e = s.read(host, partialTick, id);
            // NaN at X marks no pose
            if (host.pose(id, partialTick, pose)) System.arraycopy(pose, 0, s.poses, e * POSE_LENGTH, POSE_LENGTH);
            else s.poses[e * POSE_LENGTH] = Double.NaN;
        };
        host.within(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ(), reader);
        // the wholes and mounts of what was found, which a query asks of a part or a rider
        for (int e = 0; e < s.count; e++) {
            reader.accept(s.parents[e]);
            reader.accept(s.roots[e]);
        }
        s.listCells();
        return s;
    }

    @Override
    void grown(int size) {
        poses = Arrays.copyOf(poses, size * POSE_LENGTH);
    }

    /** Counts each cell's members, then lays each cell's run out in members. */
    private void listCells() {
        int listed = 0, wideCount = 0;
        for (int e = 0; e < count; e++) {
            if (!boxed[e]) continue;
            int span = span(e);
            if (span > MAX_CELLS) wideCount++;
            else listed += span;
        }
        growCells(listed);
        members = new int[listed];
        wide = new int[wideCount];
        wideCount = 0;
        for (int e = 0; e < count; e++) {
            if (!boxed[e]) continue;
            if (span(e) > MAX_CELLS) wide[wideCount++] = e;
            else forCells(e, -1);
        }
        int at = 0;
        for (int s = 0; s < cellKeys.length; s++) {
            if (cellKeys[s] == NO_CELL) continue;
            cellStart[s] = at;
            at += cellLength[s];
            cellLength[s] = 0;
        }
        for (int e = 0; e < count; e++) {
            if (boxed[e] && span(e) <= MAX_CELLS) forCells(e, e);
        }
        memberCount = listed;
    }

    /** With {@code member} below 0, counts {@code e} in each cell its box touches; else lists it there. */
    private void forCells(int e, int member) {
        int b = e * 6;
        for (int cx = cellOf(boxes[b]); cx <= cellOf(boxes[b + 3]); cx++) {
            for (int cy = cellOf(boxes[b + 1]); cy <= cellOf(boxes[b + 4]); cy++) {
                for (int cz = cellOf(boxes[b + 2]); cz <= cellOf(boxes[b + 5]); cz++) {
                    long k = key(cx, cy, cz);
                    int s = findCell(k);
                    if (cellKeys[s] == NO_CELL) {
                        cellKeys[s] = k;
                        cells++;
                    }
                    if (member >= 0) members[cellStart[s] + cellLength[s]] = member;
                    cellLength[s]++;
                }
            }
        }
    }

    private int span(int e) {
        int b = e * 6;
        return (cellOf(boxes[b + 3]) - cellOf(boxes[b]) + 1) * (cellOf(boxes[b + 4]) - cellOf(boxes[b + 1]) + 1)
                * (cellOf(boxes[b + 5]) - cellOf(boxes[b + 2]) + 1);
    }

    @Override
    int cellSlot(int cx, int cy, int cz) {
        long k = key(cx, cy, cz);
        int s = findCell(k);
        return cellKeys[s] == k ? s : -1;
    }

    /** Entities held, for the measurements. */
    int size() {
        return count;
    }

    @Override
    public int localPlayer() {
        return localPlayer;
    }

    @Override
    public int cameraEntity() {
        return camera;
    }

    @Override
    public boolean pose(int id, float partialTick, double[] out) {
        int e = entry(id);
        if (e < 0 || Double.isNaN(poses[e * POSE_LENGTH])) return false;
        System.arraycopy(poses, e * POSE_LENGTH, out, 0, POSE_LENGTH);
        return true;
    }

    @Override
    public int flags(int id) {
        int e = entry(id);
        return e < 0 ? 0 : flags[e];
    }

    @Override
    public int kind(int id) {
        int e = entry(id);
        return e < 0 ? KIND_OTHER : kinds[e];
    }

    @Override
    public boolean box(int id, float partialTick, CgBoxSink out) {
        int e = entry(id);
        if (e < 0 || !boxed[e]) return false;
        int b = e * 6;
        out.set(boxes[b], boxes[b + 1], boxes[b + 2], boxes[b + 3], boxes[b + 4], boxes[b + 5]);
        return true;
    }

    @Override
    public int parent(int id) {
        int e = entry(id);
        return e < 0 ? -1 : parents[e];
    }

    @Override
    public int rootVehicle(int id) {
        int e = entry(id);
        return e < 0 ? -1 : roots[e];
    }

    @Override
    public void within(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                       IntConsumer visitor) {
        long span = (long) (cellOf(maxX) - cellOf(minX) + 1) * (cellOf(maxY) - cellOf(minY) + 1)
                * (cellOf(maxZ) - cellOf(minZ) + 1);
        if (span > count) {
            // more cells than entities: test each once
            for (int e = 0; e < count; e++) {
                if (meets(e, minX, minY, minZ, maxX, maxY, maxZ)) visitor.accept(ids[e]);
            }
            return;
        }
        walk(minX, minY, minZ, maxX, maxY, maxZ, visitor);
        for (int e : wide) {
            if (meets(e, minX, minY, minZ, maxX, maxY, maxZ)) visitor.accept(ids[e]);
        }
    }
}
