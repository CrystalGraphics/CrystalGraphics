package com.crystalgraphics.collision.debug;

import com.crystalgraphics.collision.query.CgHit;
import com.crystalgraphics.collision.query.CgQuery;
import com.crystalgraphics.platform.service.CgWorldQuery;
import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.bounds.CgAABBc;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * The world queries {@link CgCollisionShow} heard, as world-space segments: written as each query ends, since its shape
 * may be resized the moment after. A cast is its shape where it starts and where it stops, joined by its path; a ray
 * its path; an overlap its shape; the nearest hit a cross on the surface and its normal. Queries run on any thread.
 */
final class CgQueryLines implements CgShapeLines.Sink {

    /** A query's shapes and path while it hits nothing, and the shape where it stops once it does. */
    static final int PATH = 0xFF60C0FF, HIT = 0xFFFF4040;
    private static final double MARK = 0.4;

    private final CgShapeLines shapes = new CgShapeLines();
    private final CgPose end = new CgPose();
    private final int max;
    private double[] points = new double[6 * 256];
    private int[] colours = new int[256];
    private long[] born = new long[256];
    private CgWorldQuery[] levels = new CgWorldQuery[256];
    private int count;
    // the query being written
    private int colour;
    private long now;
    private CgWorldQuery level;

    /** Keeps at most {@code max} segments; past that, queries are dropped until some expire. */
    CgQueryLines(int max) {
        this.max = max;
    }

    synchronized void eye(double x, double y, double z) {
        shapes.eye(x, y, z);
    }

    /** Writes one query's lines, as {@link CgQuery.Watcher#ran} hands it. */
    synchronized void add(@Nullable CgShape shape, CgPose pose, @Nullable CgAABBc box, double dx, double dy, double dz,
                          double length, CgWorldQuery level, @Nullable CgHit nearest, long now) {
        this.level = level;
        this.now = now;
        int stopped = nearest == null ? PATH : HIT;
        double reach = nearest == null ? length : nearest.distance();
        if (box != null) {
            colour = stopped;
            CgShapeLines.box(box, this);
        } else if (shape == null) {
            colour = PATH;
            line(pose.x, pose.y, pose.z, pose.x + dx * reach, pose.y + dy * reach, pose.z + dz * reach);
        } else if (length > 0) {
            colour = PATH;
            shapes.write(shape, pose, this);
            line(pose.x, pose.y, pose.z, pose.x + dx * reach, pose.y + dy * reach, pose.z + dz * reach);
            colour = stopped;
            end.set(pose).at(pose.x + dx * reach, pose.y + dy * reach, pose.z + dz * reach);
            shapes.write(shape, end, this);
        } else {
            colour = stopped;
            shapes.write(shape, pose, this);
        }
        if (nearest != null) {
            colour = HIT;
            CgShapeLines.mark(nearest.x(), nearest.y(), nearest.z(), nearest.normalX(), nearest.normalY(),
                    nearest.normalZ(), MARK, this);
        }
        this.level = null;
    }

    @Override
    public void line(double ax, double ay, double az, double bx, double by, double bz) {
        if (count == max) return;
        if (count == colours.length) {
            int grown = Math.min(max, count * 2);
            points = Arrays.copyOf(points, grown * 6);
            colours = Arrays.copyOf(colours, grown);
            born = Arrays.copyOf(born, grown);
            levels = Arrays.copyOf(levels, grown);
        }
        int at = count * 6;
        points[at] = ax;
        points[at + 1] = ay;
        points[at + 2] = az;
        points[at + 3] = bx;
        points[at + 4] = by;
        points[at + 5] = bz;
        colours[count] = colour;
        born[count] = now;
        levels[count++] = level;
    }

    /** Where {@link #drawTo} puts each segment. */
    @FunctionalInterface
    interface Out {
        void line(int argb, double ax, double ay, double az, double bx, double by, double bz);
    }

    /** Drops what was written more than {@code hold} nanos before {@code now}, then hands {@code out} the rest. */
    synchronized void drawTo(long now, long hold, Out out) {
        int old = 0;
        while (old < count && now - born[old] > hold) old++;
        if (old > 0) {
            System.arraycopy(points, old * 6, points, 0, (count - old) * 6);
            System.arraycopy(colours, old, colours, 0, count - old);
            System.arraycopy(born, old, born, 0, count - old);
            System.arraycopy(levels, old, levels, 0, count - old);
            Arrays.fill(levels, count - old, count, null);
            count -= old;
        }
        for (int i = 0; i < count; i++) {
            int at = i * 6;
            out.line(colours[i], points[at], points[at + 1], points[at + 2], points[at + 3], points[at + 4],
                    points[at + 5]);
        }
    }

    /** Hands {@code pack} each segment asked of {@code level} that starts within {@code range} of a point. */
    synchronized void packTo(CgWorldQuery level, double x, double y, double z, double range, CgLinePack pack) {
        double r2 = range * range;
        for (int i = 0; i < count && !pack.full(); i++) {
            int at = i * 6;
            double ex = points[at] - x, ey = points[at + 1] - y, ez = points[at + 2] - z;
            if (levels[i] != level || ex * ex + ey * ey + ez * ez > r2) continue;
            pack.line(points[at], points[at + 1], points[at + 2], points[at + 3], points[at + 4], points[at + 5]);
        }
    }

    synchronized void clear() {
        Arrays.fill(levels, 0, count, null);
        count = 0;
    }
}
