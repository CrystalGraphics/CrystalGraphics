package com.crystalgraphics.collision.block;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The block cells a box covers as it moves, nearest first: slices across the motion's longest axis, each with the
 * cross-section the box covers while it is in that slice. A ray is a box of no size; an overlap, a box that does not
 * move. Each cell is visited once, and the walk stops at the first slice the box reaches only past
 * {@link Visitor#limit}.
 *
 * <p>Under every {@code CgCollisionQuery} and line of sight; one per thread, reused.
 */
public final class CgBlockCells {

    /** What a walk visits. */
    public interface Visitor {
        /** Cell {@code (x, y, z)}; false stops the walk. */
        boolean cell(int x, int y, int z);

        /** The fraction of the motion past which no cell matters: the nearest hit so far. */
        double limit();
    }

    /** Past this many cells a walk stops, once warned: a query over a volume no tick can afford. */
    static final int MAX_CELLS = 32768;

    private static final Logger LOGGER = LogManager.getLogger("CgCollision");
    private static volatile boolean warned;

    private final double[] lo = new double[3], hi = new double[3], move = new double[3];
    private final int[] cell = new int[3];

    /** The box at the start and its motion; then {@link #walk}. */
    public CgBlockCells set(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double mx,
                            double my, double mz) {
        lo[0] = minX;
        lo[1] = minY;
        lo[2] = minZ;
        hi[0] = maxX;
        hi[1] = maxY;
        hi[2] = maxZ;
        move[0] = mx;
        move[1] = my;
        move[2] = mz;
        return this;
    }

    /** Visits every cell from {@code minY} up to but not including {@code maxY}. */
    public void walk(int minY, int maxY, Visitor v) {
        int a = Math.abs(move[0]) >= Math.abs(move[1]) ? (Math.abs(move[0]) >= Math.abs(move[2]) ? 0 : 2)
                : (Math.abs(move[1]) >= Math.abs(move[2]) ? 1 : 2);
        int b = (a + 1) % 3, c = (a + 2) % 3;
        double ma = move[a];
        int first = floor(ma >= 0 ? lo[a] : hi[a]), last = floor(ma >= 0 ? hi[a] + ma : lo[a] + ma);
        int step = ma >= 0 ? 1 : -1;
        int visited = 0;
        for (int ca = first; ; ca += step) {
            double t0 = 0, t1 = 1;
            if (ma > 0) {
                t0 = Math.max(0, (ca - hi[a]) / ma);
                t1 = Math.min(1, (ca + 1 - lo[a]) / ma);
            } else if (ma < 0) {
                t0 = Math.max(0, (ca + 1 - lo[a]) / ma);
                t1 = Math.min(1, (ca - hi[a]) / ma);
            }
            if (t0 > v.limit()) return;
            if (a != 1 || ca >= minY && ca < maxY) {
                int b0 = floor(lo[b] + Math.min(move[b] * t0, move[b] * t1));
                int b1 = floor(hi[b] + Math.max(move[b] * t0, move[b] * t1));
                int c0 = floor(lo[c] + Math.min(move[c] * t0, move[c] * t1));
                int c1 = floor(hi[c] + Math.max(move[c] * t0, move[c] * t1));
                if (b == 1) {
                    b0 = Math.max(b0, minY);
                    b1 = Math.min(b1, maxY - 1);
                } else if (c == 1) {
                    c0 = Math.max(c0, minY);
                    c1 = Math.min(c1, maxY - 1);
                }
                cell[a] = ca;
                int sb = move[b] < 0 ? -1 : 1, sc = move[c] < 0 ? -1 : 1;
                for (int ib = 0; ib <= b1 - b0; ib++) {
                    cell[b] = sb > 0 ? b0 + ib : b1 - ib;
                    for (int ic = 0; ic <= c1 - c0; ic++) {
                        cell[c] = sc > 0 ? c0 + ic : c1 - ic;
                        if (++visited > MAX_CELLS) {
                            if (!warned) {
                                warned = true;
                                LOGGER.warn("[CgCollision] a block query passed {} cells and stopped there: narrow it",
                                        MAX_CELLS);
                            }
                            return;
                        }
                        if (!v.cell(cell[0], cell[1], cell[2])) return;
                    }
                }
            }
            if (ca == last) return;
        }
    }

    static int floor(double d) {
        int i = (int) d;
        return d < i ? i - 1 : i;
    }
}
