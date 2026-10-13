package com.crystalgraphics.collision.debug;

import com.crystalgraphics.collision.CgCollisionQuery;
import com.crystalgraphics.collision.hit.CgHit;
import com.crystalgraphics.collision.hit.CgHitPart;
import com.crystalgraphics.collision.hitbox.CgHitbox;
import com.crystalgraphics.platform.world.CgWorldQuery;
import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.bounds.CgAABBc;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * What {@link CgCollisionShow} saw lately, as world-space segments each kept until it expires: every world query as
 * it ended, and every hitbox each time it ticked. Written at once, since a query's shape may be resized the moment
 * after. Queries and hitboxes run on any thread.
 *
 * <ul>
 *   <li>A cast is its shape where it starts and where it stops, joined by its path; a ray its path; an overlap its
 *       shape; the nearest hit a cross on the surface and its normal.</li>
 *   <li>A hitbox is its parts as that tick tested them, kept a little past a game tick so one ticking each tick shows
 *       steadily, and {@link #LINGER_NANOS} once it stops: a blast's front lives a few ticks.</li>
 * </ul>
 */
final class CgRecentLines implements CgShapeLines.Sink {

    /** A query's shapes and path while it hits nothing, and the shape where it stops once it does. */
    static final int PATH = 0xFF60C0FF, HIT = 0xFFFF4040;
    /** A client's own hitboxes. */
    static final int HITBOX = 0xFF40FF60;
    /** How long a query or a hitbox's tick is kept: past a game tick, so one each tick shows steadily. */
    static final long HOLD_NANOS = 60_000_000L;
    static final long LINGER_NANOS = 500_000_000L;
    private static final double MARK = 0.4;

    private final CgShapeLines shapes = new CgShapeLines();
    private final CgPose end = new CgPose();
    private final CgHitbox.PartSink parts = this::part;
    private final int max;
    private double[] points = new double[6 * 256];
    private int[] colours = new int[256];
    /** Each segment's hitbox's own {@link CgHitbox#showColour()}, which a server's pack carries; 0 for none. */
    private int[] shows = new int[256];
    private long[] expires = new long[256];
    private CgWorldQuery[] levels = new CgWorldQuery[256];
    private int count;
    // what is being written
    private int colour, show;
    private long until;
    private CgWorldQuery level;

    /** Keeps at most {@code max} segments; past that, lines are dropped until some expire. */
    CgRecentLines(int max) {
        this.max = max;
    }

    synchronized void eye(double x, double y, double z) {
        shapes.eye(x, y, z);
    }

    /** Writes one query's lines, as {@link CgCollisionQuery.Watcher#ran} hands it, kept {@link #HOLD_NANOS}. */
    synchronized void query(@Nullable CgShape shape, CgPose pose, @Nullable CgAABBc box, double dx, double dy,
                            double dz, double length, CgWorldQuery level, @Nullable CgHit nearest, long now) {
        this.level = level;
        until = now + HOLD_NANOS;
        show = 0;
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

    /** Writes a hitbox's parts as its last tick tested them; kept {@link #LINGER_NANOS} once it has stopped. */
    synchronized void hitbox(CgHitbox hitbox, CgWorldQuery level, long now) {
        this.level = level;
        until = now + (hitbox.stopped() ? LINGER_NANOS : HOLD_NANOS);
        show = hitbox.showColour();
        colour = show != 0 ? show : HITBOX;
        hitbox.parts(parts);
        this.level = null;
    }

    private void part(@Nullable CgHitPart name, CgShape shape, CgPose pose) {
        shapes.write(shape, pose, this);
    }

    @Override
    public void line(double ax, double ay, double az, double bx, double by, double bz) {
        if (count == max) return;
        if (count == colours.length) {
            int grown = Math.min(max, count * 2);
            points = Arrays.copyOf(points, grown * 6);
            colours = Arrays.copyOf(colours, grown);
            shows = Arrays.copyOf(shows, grown);
            expires = Arrays.copyOf(expires, grown);
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
        shows[count] = show;
        expires[count] = until;
        levels[count++] = level;
    }

    /** Where {@link #drawTo} puts each segment. */
    @FunctionalInterface
    interface Out {
        void line(int argb, double ax, double ay, double az, double bx, double by, double bz);
    }

    /** Drops what has expired by {@code now}. */
    synchronized void expire(long now) {
        int kept = 0;
        for (int i = 0; i < count; i++) {
            if (expires[i] - now <= 0) continue;
            if (kept != i) {
                System.arraycopy(points, i * 6, points, kept * 6, 6);
                colours[kept] = colours[i];
                shows[kept] = shows[i];
                expires[kept] = expires[i];
                levels[kept] = levels[i];
            }
            kept++;
        }
        Arrays.fill(levels, kept, count, null);
        count = kept;
    }

    /** Drops what has expired by {@code now}, then hands {@code out} the rest. */
    synchronized void drawTo(long now, Out out) {
        expire(now);
        for (int i = 0; i < count; i++) {
            int at = i * 6;
            out.line(colours[i], points[at], points[at + 1], points[at + 2], points[at + 3], points[at + 4],
                    points[at + 5]);
        }
    }

    /** Hands {@code pack} each segment of {@code level} that starts within {@code range} of a point, in its show colour. */
    synchronized void packTo(CgWorldQuery level, double x, double y, double z, double range, CgLinePack pack) {
        double r2 = range * range;
        for (int i = 0; i < count && !pack.full(); i++) {
            int at = i * 6;
            double ex = points[at] - x, ey = points[at + 1] - y, ez = points[at + 2] - z;
            if (levels[i] != level || ex * ex + ey * ey + ez * ez > r2) continue;
            pack.colour(shows[i]);
            pack.line(points[at], points[at + 1], points[at + 2], points[at + 3], points[at + 4], points[at + 5]);
        }
    }

    synchronized void clear() {
        Arrays.fill(levels, 0, count, null);
        count = 0;
    }
}
