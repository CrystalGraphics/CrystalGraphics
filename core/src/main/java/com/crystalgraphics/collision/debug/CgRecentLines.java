package com.crystalgraphics.collision.debug;

import com.crystalgraphics.collision.CgCollisionQuery;
import com.crystalgraphics.collision.hit.CgHit;
import com.crystalgraphics.collision.hit.CgHitPart;
import com.crystalgraphics.collision.hitbox.CgHitbox;
import com.crystalgraphics.platform.world.CgWorldQuery;
import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.bounds.CgAABBc;
import org.joml.Vector3d;

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
 *       so one ticking each tick shows steadily; once stopped, kept its linger longer, fading. An arrow from its first
 *       part's centre to a little past its front shows which way it faces, blue on every side, as F3+B's line shows
 *       where an entity looks.</li>
 *   <li>Edges and faces are capped apart, so faces never crowd out a later hitbox's edges.</li>
 * </ul>
 */
final class CgRecentLines implements CgShapeLines.Sink {

    /** A query's shapes and path while it hits nothing, and the shape where it stops once it does. */
    static final int PATH = 0xFF60C0FF, HIT = 0xFFFF4040;
    /** A client's own hitboxes. */
    static final int HITBOX = 0xFF40FF60;
    /** The arrow a hitbox faces along: F3+B's blue. */
    static final int FORWARD = 0xFF0000FF;
    /** How far past its part's front the arrow reaches, and its head's length. */
    private static final double FORWARD_PAST = 0.4, FORWARD_HEAD = 0.15;
    /** How long a query or a hitbox's tick is kept: past a game tick, so one each tick shows steadily. */
    static final long HOLD_NANOS = 60_000_000L;
    /** A face's fill when its hitbox sets none: the viewer's style decides, {@link #FILL} or none. */
    static final int DEFAULT_FILL = -1;
    /** How much of its colour's alpha a face takes under the transparent style, of 255: a quarter. */
    static final int FILL = 64;
    private static final double MARK = 0.4;

    private final CgShapeLines shapes = new CgShapeLines();
    private final CgPose end = new CgPose();
    private final CgHitbox.PartSink parts = this::part;
    private final Vector3d forward = new Vector3d(), up = new Vector3d(), side = new Vector3d();
    private boolean firstPart;
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
    /** How much of its colour's alpha a face takes, of 255, or {@link #DEFAULT_FILL}; 255 for a segment. */
    private int[] fills = new int[256];
    /** Whether a face begins a piece ({@link CgShapeLines.Sink#piece}). */
    private boolean[] starts = new boolean[256];
    private boolean pieceNext;
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
     * Writes a hitbox's parts as its last tick tested them, kept {@link #HOLD_NANOS}; a stopped one {@code linger}
     * longer, fading out over it. Its edges, and its faces where it sets its own fill, or where it sets none and
     * {@code defaultFaces}: someone draws transparent.
     */
    synchronized void hitbox(CgHitbox hitbox, CgWorldQuery level, long now, long linger, boolean defaultFaces) {
        long fading = hitbox.stopped() ? linger : 0;
        write(hitbox, level, now + HOLD_NANOS + fading, fading, defaultFaces, Float.NaN);
    }

    /**
     * Writes a hitbox where the frame draws it at {@code partialTick} ({@link CgHitbox#parts(float,
     * CgHitbox.PartSink)}), kept until {@code until}: a record cleared each frame.
     */
    synchronized void drawn(CgHitbox hitbox, CgWorldQuery level, long until, float partialTick, boolean defaultFaces) {
        write(hitbox, level, until, 0, defaultFaces, partialTick);
    }

    private void write(CgHitbox hitbox, CgWorldQuery level, long until, long fade, boolean defaultFaces,
                       float partialTick) {
        this.level = level;
        this.fade = fade;
        this.until = until;
        show = hitbox.showColour();
        colour = show != 0 ? show : HITBOX;
        fill = ownFill(hitbox);
        if (fill == DEFAULT_FILL && !defaultFaces) fill = 0;
        faceColour = fill == DEFAULT_FILL ? colour : scaled(colour, fill);
        firstPart = true;
        if (Float.isNaN(partialTick)) hitbox.parts(parts);
        else hitbox.parts(partialTick, parts);
        this.level = null;
    }

    private void part(@Nullable CgHitPart name, CgShape shape, CgPose pose) {
        if (firstPart) {
            firstPart = false;
            arrow(shape, pose);
        }
        shapes.write(shape, pose, this);
        if (fill != 0) shapes.faces(shape, pose, this);
    }

    /** From {@code shape}'s centre along {@code pose}'s +z to {@link #FORWARD_PAST} beyond its front, headed. */
    private void arrow(CgShape shape, CgPose pose) {
        int edges = colour, shown = show;
        colour = show = FORWARD;
        double length = shape.support(0, 0, 1, forward).z + FORWARD_PAST;
        pose.rotate(0, 0, 1, forward);
        pose.rotate(0, 1, 0, up);
        pose.rotate(1, 0, 0, side);
        double tx = pose.x + forward.x * length, ty = pose.y + forward.y * length, tz = pose.z + forward.z * length;
        double bx = tx - forward.x * FORWARD_HEAD, by = ty - forward.y * FORWARD_HEAD, bz = tz - forward.z * FORWARD_HEAD;
        double w = FORWARD_HEAD * 0.5;
        line(pose.x, pose.y, pose.z, tx, ty, tz);
        line(tx, ty, tz, bx + up.x * w, by + up.y * w, bz + up.z * w);
        line(tx, ty, tz, bx - up.x * w, by - up.y * w, bz - up.z * w);
        line(tx, ty, tz, bx + side.x * w, by + side.y * w, bz + side.z * w);
        line(tx, ty, tz, bx - side.x * w, by - side.y * w, bz - side.z * w);
        colour = edges;
        show = shown;
    }

    /** A hitbox's {@link CgHitbox#showFill(float)}, of 255; {@link #DEFAULT_FILL} where it sets none. */
    static int ownFill(CgHitbox hitbox) {
        float own = hitbox.showFill();
        return own < 0 ? DEFAULT_FILL : Math.round(own * 255);
    }

    /** {@code argb} with its alpha times {@code of255} of 255. */
    static int scaled(int argb, int of255) {
        return ((argb >>> 24) * of255 / 255) << 24 | argb & 0xFFFFFF;
    }

    @Override
    public void piece() {
        pieceNext = true;
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
            starts = Arrays.copyOf(starts, grown);
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
        starts[count] = face && pieceNext;
        if (face) pieceNext = false;
        levels[count] = level;
        return count++ * 9;
    }

    /** Where {@link #drawTo} puts each segment and each face's triangle. */
    @FunctionalInterface
    interface Out {
        void line(int argb, double ax, double ay, double az, double bx, double by, double bz);

        /** A triangle of a hitbox's face, at its fill; an out of lines alone leaves it. */
        default void triangle(int argb, double ax, double ay, double az, double bx, double by, double bz, double cx,
                double cy, double cz) {
        }

        /** The triangles after this, up to the next, are one piece of a shape ({@link CgShapeLines.Sink#piece}). */
        default void piece() {
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
                starts[kept] = starts[i];
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

    /**
     * Drops what has expired by {@code now}, then hands {@code out} the rest: faces whose hitbox sets no fill only
     * {@code transparent}, at {@link #FILL}.
     */
    synchronized void drawTo(long now, Out out, boolean transparent) {
        expire(now);
        for (int i = 0; i < count; i++) {
            int at = i * 9, argb = colours[i];
            if (faces[i]) {
                if (starts[i]) out.piece();
                if (fills[i] == DEFAULT_FILL) {
                    if (!transparent) continue;
                    argb = scaled(argb, FILL);
                }
                out.triangle(faded(argb, i, now), points[at], points[at + 1], points[at + 2], points[at + 3], points[at + 4],
                        points[at + 5], points[at + 6], points[at + 7], points[at + 8]);
            } else {
                out.line(faded(argb, i, now), points[at], points[at + 1], points[at + 2], points[at + 3], points[at + 4],
                        points[at + 5]);
            }
        }
    }

    /**
     * Hands {@code pack} what of {@code level} starts within {@code range} of a point, in its show colour: edges
     * first, so a full pack drops faces. A face carries its hitbox's fill in its alpha; one whose hitbox sets none
     * only {@code withDefaultFaces}, marked, for a reader drawing transparent to fill. A fading record goes in its own
     * colour or {@code fallback}, its alpha in eighths so runs stay few.
     */
    synchronized void packTo(CgWorldQuery level, double x, double y, double z, double range,
                             boolean withDefaultFaces, int fallback, long now, CgLinePack pack) {
        double r2 = range * range;
        for (int kind = 0; kind < 2; kind++) {
            for (int i = 0; i < count && !pack.full(); i++) {
                int at = i * 9;
                double ex = points[at] - x, ey = points[at + 1] - y, ez = points[at + 2] - z;
                if (faces[i] != (kind == 1) || levels[i] != level || ex * ex + ey * ey + ez * ez > r2) continue;
                if (fills[i] == DEFAULT_FILL && !withDefaultFaces) continue;
                pack.defaultFill(fills[i] == DEFAULT_FILL);
                pack.colour(packed(i, fallback, now));
                if (faces[i]) {
                    if (starts[i]) pack.piece();
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
        int base = shows[i] != 0 ? shows[i] : fallback;
        int argb = fills[i] == DEFAULT_FILL ? base : scaled(base, fills[i]);
        int faded = faded(argb, i, now);
        // a face's colour is explicit: its own fill in its alpha, or its whole colour for the reader to fill
        if (faded == argb) return faces[i] ? argb : shows[i];
        int eighths = Math.max(1, ((faded >>> 24) * 8 + 254) / 255);
        return (eighths * 255 / 8) << 24 | argb & 0xFFFFFF;
    }

    synchronized void clear() {
        Arrays.fill(levels, 0, count, null);
        count = 0;
    }
}
