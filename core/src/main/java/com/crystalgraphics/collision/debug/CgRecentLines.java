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
 * What {@link CgCollisionShow} saw lately, as world-space segments and triangles each kept until it expires: every
 * world query as it ended, and every hitbox each time it ticked. Written at once, since a query's shape may be
 * resized the moment after. Queries and hitboxes run on any thread.
 *
 * <ul>
 *   <li>A cast is its shape where it starts and where it stops, joined by its path; a ray its path; an overlap its
 *       shape; the nearest hit a cross on the surface and its normal.</li>
 *   <li>A hitbox is its parts as that tick tested them, their edges and their faces, kept a little past a game tick
 *       so one ticking each tick shows steadily; once stopped, kept its linger longer, fading.</li>
 *   <li>Edges and faces are capped apart, so faces never crowd out a later hitbox's edges.</li>
 * </ul>
 */
final class CgRecentLines implements CgShapeLines.Sink {

    /** A query's shapes and path while it hits nothing, and the shape where it stops once it does. */
    static final int PATH = 0xFF60C0FF, HIT = 0xFFFF4040;
    /** A client's own hitboxes. */
    static final int HITBOX = 0xFF40FF60;
    /** How long a query or a hitbox's tick is kept: past a game tick, so one each tick shows steadily. */
    static final long HOLD_NANOS = 60_000_000L;
    /** How much of its colour's alpha a hitbox's faces take unless it says ({@link CgHitbox#showFill(float)}). */
    static final float FILL = 0.25f;
    private static final double MARK = 0.4;

    private final CgShapeLines shapes = new CgShapeLines();
    private final CgPose end = new CgPose();
    private final CgHitbox.PartSink parts = this::part;
    private final int max;
    /** Nine a record: a segment's two ends, or a face's three corners. */
    private double[] points = new double[9 * 256];
    private boolean[] faces = new boolean[256];
    private int[] colours = new int[256];
    /** Each segment's hitbox's own {@link CgHitbox#showColour()}, which a server's pack carries; 0 for none. */
    private int[] shows = new int[256];
    private long[] expires = new long[256];
    /** How long before it expires a record starts fading; 0 for never. */
    private long[] fades = new long[256];
    /** How much of its colour's alpha a face takes, of 255; 255 for a segment. */
    private int[] fills = new int[256];
    private CgWorldQuery[] levels = new CgWorldQuery[256];
    private int count, lineCount, faceCount;
    // what is being written
    private int colour, show, fill, faceColour;
    private long until, fade;
    private CgWorldQuery level;

    /** Keeps at most {@code max} segments and as many triangles; past that, each is dropped until some expire. */
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
        fade = 0;
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

    /**
     * Writes a hitbox's parts as its last tick tested them, edges and faces, kept {@link #HOLD_NANOS}; a stopped one
     * {@code linger} longer, fading out over it.
     */
    synchronized void hitbox(CgHitbox hitbox, CgWorldQuery level, long now, long linger) {
        this.level = level;
        fade = hitbox.stopped() ? linger : 0;
        until = now + HOLD_NANOS + fade;
        show = hitbox.showColour();
        colour = show != 0 ? show : HITBOX;
        fill = fillOf(hitbox);
        faceColour = scaled(colour, fill);
        hitbox.parts(parts);
        this.level = null;
    }

    private void part(@Nullable CgHitPart name, CgShape shape, CgPose pose) {
        shapes.write(shape, pose, this);
        if (fill > 0) shapes.faces(shape, pose, this);
    }

    /** A hitbox's {@link CgHitbox#showFill(float)} of 255, {@link #FILL} unless it says. */
    static int fillOf(CgHitbox hitbox) {
        float own = hitbox.showFill();
        return Math.round((own < 0 ? FILL : own) * 255);
    }

    /** {@code argb} with its alpha times {@code of255} of 255. */
    static int scaled(int argb, int of255) {
        return ((argb >>> 24) * of255 / 255) << 24 | argb & 0xFFFFFF;
    }

    @Override
    public void line(double ax, double ay, double az, double bx, double by, double bz) {
        int at = record(false);
        if (at < 0) return;
        points[at] = ax;
        points[at + 1] = ay;
        points[at + 2] = az;
        points[at + 3] = bx;
        points[at + 4] = by;
        points[at + 5] = bz;
    }

    @Override
    public void triangle(double ax, double ay, double az, double bx, double by, double bz, double cx, double cy,
            double cz) {
        int at = record(true);
        if (at < 0) return;
        points[at] = ax;
        points[at + 1] = ay;
        points[at + 2] = az;
        points[at + 3] = bx;
        points[at + 4] = by;
        points[at + 5] = bz;
        points[at + 6] = cx;
        points[at + 7] = cy;
        points[at + 8] = cz;
    }

    /** Books the next record as what is being written; where its points go, or -1 when full. */
    private int record(boolean face) {
        if ((face ? faceCount : lineCount) == max) return -1;
        if (count == colours.length) {
            int grown = Math.min(2 * max, count * 2);
            points = Arrays.copyOf(points, grown * 9);
            faces = Arrays.copyOf(faces, grown);
            colours = Arrays.copyOf(colours, grown);
            shows = Arrays.copyOf(shows, grown);
            expires = Arrays.copyOf(expires, grown);
            fades = Arrays.copyOf(fades, grown);
            fills = Arrays.copyOf(fills, grown);
            levels = Arrays.copyOf(levels, grown);
        }
        if (face) faceCount++;
        else lineCount++;
        faces[count] = face;
        colours[count] = face ? faceColour : colour;
        shows[count] = show;
        expires[count] = until;
        fades[count] = fade;
        fills[count] = face ? fill : 255;
        levels[count] = level;
        return count++ * 9;
    }

    /** Where {@link #drawTo} puts each segment and each face's triangle. */
    @FunctionalInterface
    interface Out {
        void line(int argb, double ax, double ay, double az, double bx, double by, double bz);

        /** A triangle of a hitbox's face, in its edges' colour; an out of lines alone leaves it. */
        default void triangle(int argb, double ax, double ay, double az, double bx, double by, double bz, double cx,
                double cy, double cz) {
        }
    }

    /** Drops what has expired by {@code now}. */
    synchronized void expire(long now) {
        int kept = 0;
        lineCount = faceCount = 0;
        for (int i = 0; i < count; i++) {
            if (expires[i] - now <= 0) continue;
            if (kept != i) {
                System.arraycopy(points, i * 9, points, kept * 9, 9);
                faces[kept] = faces[i];
                colours[kept] = colours[i];
                shows[kept] = shows[i];
                expires[kept] = expires[i];
                fades[kept] = fades[i];
                fills[kept] = fills[i];
                levels[kept] = levels[i];
            }
            if (faces[kept]) faceCount++;
            else lineCount++;
            kept++;
        }
        Arrays.fill(levels, kept, count, null);
        count = kept;
    }

    /** {@code argb} with its alpha scaled by how far record {@code i} has faded at {@code now}. */
    private int faded(int argb, int i, long now) {
        long left = expires[i] - now;
        if (fades[i] == 0 || left >= fades[i]) return argb;
        return (int) ((argb >>> 24) * left / fades[i]) << 24 | argb & 0xFFFFFF;
    }

    /** Drops what has expired by {@code now}, then hands {@code out} the rest. */
    synchronized void drawTo(long now, Out out) {
        expire(now);
        for (int i = 0; i < count; i++) {
            int at = i * 9, argb = faded(colours[i], i, now);
            if (faces[i]) {
                out.triangle(argb, points[at], points[at + 1], points[at + 2], points[at + 3], points[at + 4],
                        points[at + 5], points[at + 6], points[at + 7], points[at + 8]);
            } else {
                out.line(argb, points[at], points[at + 1], points[at + 2], points[at + 3], points[at + 4],
                        points[at + 5]);
            }
        }
    }

    /**
     * Hands {@code pack} what of {@code level} starts within {@code range} of a point, in its show colour: edges
     * first, so a full pack drops faces, and faces only {@code withFaces}. A fading record goes in its own colour or
     * {@code fallback}, its alpha in eighths so runs stay few.
     */
    synchronized void packTo(CgWorldQuery level, double x, double y, double z, double range, boolean withFaces,
                             int fallback, long now, CgLinePack pack) {
        double r2 = range * range;
        for (int kinds = withFaces ? 2 : 1, kind = 0; kind < kinds; kind++) {
            for (int i = 0; i < count && !pack.full(); i++) {
                int at = i * 9;
                double ex = points[at] - x, ey = points[at + 1] - y, ez = points[at + 2] - z;
                if (faces[i] != (kind == 1) || levels[i] != level || ex * ex + ey * ey + ez * ez > r2) continue;
                pack.colour(packed(i, fallback, now));
                if (faces[i]) {
                    pack.triangle(points[at], points[at + 1], points[at + 2], points[at + 3], points[at + 4],
                            points[at + 5], points[at + 6], points[at + 7], points[at + 8]);
                } else {
                    pack.line(points[at], points[at + 1], points[at + 2], points[at + 3], points[at + 4],
                            points[at + 5]);
                }
            }
        }
    }

    /**
     * Record {@code i}'s colour in a pack: a segment's show colour, 0 for the reader's own; a face's, or a fading
     * one's, explicit, with its fill, a fading one's alpha in eighths.
     */
    private int packed(int i, int fallback, long now) {
        int argb = scaled(shows[i] != 0 ? shows[i] : fallback, fills[i]);
        int faded = faded(argb, i, now);
        // a face's fill travels in its colour
        if (faded == argb) return fills[i] == 255 ? shows[i] : argb;
        int eighths = Math.max(1, ((faded >>> 24) * 8 + 254) / 255);
        return (eighths * 255 / 8) << 24 | argb & 0xFFFFFF;
    }

    synchronized void clear() {
        Arrays.fill(levels, 0, count, null);
        count = 0;
    }
}
