package com.crystalgraphics.collision.debug;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.mesh.CgMeshWriter;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.collision.hit.CgHit;
import com.crystalgraphics.collision.hit.CgHitPart;
import com.crystalgraphics.collision.hitbox.CgHitbox;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.world.CgSortLayer;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;

import java.util.Arrays;

/**
 * Shapes and hitboxes drawn in the world, for the next world frame only: call each frame you want them seen.
 * Depth-tested against the scene, never hiding what is behind them.
 *
 * <pre>{@code
 * CgCollisionDebug.shape(CgSphere.of(2), pose.at(x, y, z), 0xFFFF4040);    // a red ball this frame
 * CgCollisionDebug.hitbox(slam, 0xFF40FF60);                               // each part as its last tick tested it
 * CgCollisionDebug.line(x, y, z, x, y + 3, z, 0xFFFFFFFF);
 * if (world.ray().at(x, y, z).along(0, -1, 0, 8).first(hit)) CgCollisionDebug.mark(hit, 0xFFFF4040);
 * }</pre>
 *
 * <ul>
 *   <li>Render thread. Colours are ARGB.</li>
 *   <li>Every live hitbox and world query at once is {@link CgCollisionShow#SHOW} or the host's F3+B, which this
 *       draws too, so a query drawn here as well is drawn twice.</li>
 *   <li>Under {@link CgCollisionShow.Style#TRANSPARENT}, shapes and hitboxes, the show's and those drawn here, have
 *       their faces drawn as well, at {@link CgCollisionShow#FILL_OPACITY} of their colour's alpha, so each reads as
 *       the space it tests with what is behind it still seen. A hitbox with its own
 *       {@link CgHitbox#showFill(float) fill} is filled under either style, at that share of the opacity. Lines and
 *       marks are lines under both.</li>
 *   <li>Drawn over the world's other transparent draws ({@link CgSortLayer#OVERLAY}): the faces turned away, the
 *       lines, then the faces turned toward the eye, each far to near, so a face tints the edges behind it.</li>
 * </ul>
 */
public final class CgCollisionDebug {

    static final String SHADER = "crystalgraphics:shaders/debug/collision_lines.shader";
    private static final double MARK = 0.4;

    private static final Batch LINES = new Batch(2), FACES = new Batch(3);
    private static final CgShapeLines SHAPES = new CgShapeLines();
    private static final CgRecentLines.Out SHOWN = new CgRecentLines.Out() {
        @Override
        public void line(int argb, double ax, double ay, double az, double bx, double by, double bz) {
            colour = argb;
            LINES.line(ax, ay, az, bx, by, bz);
        }

        @Override
        public void triangle(int argb, double ax, double ay, double az, double bx, double by, double bz, double cx,
                double cy, double cz) {
            colour = argb;
            FACES.triangle(ax, ay, az, bx, by, bz, cx, cy, cz);
        }

        @Override
        public void piece() {
            FACES.piece();
        }
    };
    private static boolean installed;
    /** {@link CgCollisionShow#opacity()} and {@link CgCollisionShow#transparent()}, read once a frame. */
    private static int opacity;
    private static boolean transparent;
    private static CgMaterial material;
    private static int colour, fill;

    private CgCollisionDebug() {
    }

    /** Draws {@code shape} at {@code pose} on the next world frame. */
    public static void shape(CgShape shape, CgPose pose, int argb) {
        shape(shape, pose, argb, transparent ? 255 : 0);
    }

    /**
     * Draws each part of {@code hitbox} as its last tick tested it on the next world frame, the last once stopped;
     * filled by its {@link CgHitbox#showFill(float)}.
     */
    public static void hitbox(CgHitbox hitbox, int argb) {
        colour = argb;
        int own = CgRecentLines.ownFill(hitbox);
        fill = own == CgRecentLines.DEFAULT_FILL ? transparent ? 255 : 0 : own;
        hitbox.parts(CgCollisionDebug::part);
    }

    /** Draws a segment on the next world frame. */
    public static void line(double ax, double ay, double az, double bx, double by, double bz, int argb) {
        colour = argb;
        LINES.line(ax, ay, az, bx, by, bz);
    }

    /** Draws a hit on the next world frame: a cross on the surface and its normal. */
    public static void mark(CgHit hit, int argb) {
        mark(hit.x(), hit.y(), hit.z(), hit.normalX(), hit.normalY(), hit.normalZ(), argb);
    }

    /** Draws a cross on a surface at a point and its unit normal on the next world frame. */
    public static void mark(double x, double y, double z, double nx, double ny, double nz, int argb) {
        colour = argb;
        CgShapeLines.mark(x, y, z, nx, ny, nz, MARK, LINES);
    }

    /** Draws what was queued each world frame, and the show's. Once, with the world renderer. */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        opacity = CgCollisionShow.opacity();
        transparent = CgCollisionShow.transparent();
        CgCollisionShow.installClient();
        CgWorldRenderer.get().onFrame(CgCollisionDebug::frame);
    }

    /** Frees the mesh and material with the context. */
    public static void release() {
        LINES.release();
        FACES.release();
        material = null;
    }

    private static void part(CgHitPart name, CgShape shape, CgPose pose) {
        shape(shape, pose, colour, fill);
    }

    /** Its edges, and its faces at {@code share} of 255 of the opacity. */
    private static void shape(CgShape shape, CgPose pose, int argb, int share) {
        colour = argb;
        SHAPES.write(shape, pose, LINES);
        if (share <= 0 || opacity <= 0) return;
        colour = CgRecentLines.scaled(CgRecentLines.scaled(argb, share), opacity);
        SHAPES.faces(shape, pose, FACES);
        colour = argb;
    }

    private static void frame(CgHostView view) {
        // for what is written before the next frame: the outline lags the camera by a frame
        SHAPES.eye(view.x(), view.y(), view.z());
        opacity = CgCollisionShow.opacity();
        transparent = CgCollisionShow.transparent();
        CgCollisionShow.drawTo(view.x(), view.y(), view.z(), SHOWN);
        FACES.draw(view.x(), view.y(), view.z());
        LINES.draw(view.x(), view.y(), view.z());
    }

    private static void write(CgMeshWriter m, Batch batch) {
        m.topology(batch.corners == 2 ? CgMeshTopology.LINES : CgMeshTopology.TRIANGLES);
        float[] p = batch.points;
        for (int k = batch.from; k < batch.to; k++) {
            int i = batch.sorted ? batch.order[k] : k, at = i * batch.corners * 3;
            int argb = batch.colours[i];
            float r = (argb >>> 16 & 0xFF) / 255f, g = (argb >>> 8 & 0xFF) / 255f, b = (argb & 0xFF) / 255f;
            float a = (argb >>> 24) / 255f;
            // the colour rides in the normal, its alpha in u: collision_lines.shader reads them back
            for (int c = 0; c < batch.corners; c++, at += 3) {
                m.vertex().position(p[at], p[at + 1], p[at + 2]).uv(a, 0f).normal(r, g, b).end();
            }
        }
    }

    /**
     * The frame's segments, or its triangles, as floats from the first one's start, which the draw stands at in
     * doubles. Triangles blend over one another and write no depth, so they draw as two meshes, the back faces before
     * the lines and the front faces after. Each set goes piece by piece far to near, the back faces by a piece's far
     * side and the front by its near: one shape inside another draws between its back and its front.
     */
    static final class Batch implements CgShapeLines.Sink {
        /** Points a record: two a segment, three a triangle. */
        final int corners;
        float[] points;
        int[] colours = new int[512];
        int count;
        double ox, oy, oz;
        /** Whether {@link #order} holds the draw order: triangles, sorted far to near. */
        boolean sorted;
        int[] order = new int[0];
        /** The records the mesh being written takes, in draw order. */
        int from, to;
        private int[] keys = new int[0], nearer = new int[0], orderScratch = new int[0];
        private final int[] counts = new int[256];
        /** Each triangle's piece ({@link CgShapeLines.Sink#piece}), and each piece's middle and reach. */
        int[] pieceOf = new int[512];
        private double[] pieceX = new double[0], pieceY = new double[0], pieceZ = new double[0];
        private double[] pieceR = new double[0];
        private int[] pieceN = new int[0];
        private int pieces, piece = -1;
        private boolean pieceNext;
        /** The lines, or the back faces; and the front faces. */
        private CgMesh mesh, frontMesh;

        Batch(int corners) {
            this.corners = corners;
            points = new float[corners * 3 * 512];
        }

        @Override
        public void line(double ax, double ay, double az, double bx, double by, double bz) {
            int at = record(ax, ay, az);
            point(at, ax, ay, az);
            point(at + 3, bx, by, bz);
        }

        @Override
        public void piece() {
            pieceNext = true;
        }

        @Override
        public void triangle(double ax, double ay, double az, double bx, double by, double bz, double cx, double cy,
                double cz) {
            if (pieceNext || piece < 0) {
                piece = pieces++;
                pieceNext = false;
            }
            int at = record(ax, ay, az);
            pieceOf[count - 1] = piece;
            point(at, ax, ay, az);
            point(at + 3, bx, by, bz);
            point(at + 6, cx, cy, cz);
        }

        /** Books the next record in the colour being drawn, the first one's start the origin; where its points go. */
        private int record(double x, double y, double z) {
            if (count == 0) {
                ox = x;
                oy = y;
                oz = z;
            }
            if (count == colours.length) {
                points = Arrays.copyOf(points, points.length * 2);
                colours = Arrays.copyOf(colours, colours.length * 2);
                pieceOf = Arrays.copyOf(pieceOf, colours.length);
            }
            colours[count] = colour;
            return count++ * corners * 3;
        }

        private void point(int at, double x, double y, double z) {
            points[at] = (float) (x - ox);
            points[at + 1] = (float) (y - oy);
            points[at + 2] = (float) (z - oz);
        }

        /** Submits what the frame queued, seen from the eye, and empties it. */
        void draw(double ex, double ey, double ez) {
            if (count == 0) return;
            if (material == null) material = CgMaterial.load(SHADER);
            sorted = corners == 3;
            if (sorted) {
                int backs = sortFrom(ex, ey, ez);
                mesh = submit(mesh, 0, backs, 0, ex, ey, ez);
                frontMesh = submit(frontMesh, backs, count, 2, ex, ey, ez);
            } else {
                mesh = submit(mesh, 0, count, 1, ex, ey, ez);
            }
            count = pieces = 0;
            piece = -1;
        }

        /** Writes records {@code from} to {@code to} into {@code into} and draws it at {@code order} of the group. */
        private CgMesh submit(CgMesh into, int from, int to, int order, double ex, double ey, double ez) {
            if (from == to) return into;
            this.from = from;
            this.to = to;
            if (into == null) {
                into = CgMesh.build(CgVertexFormat.SPATIAL, CgMesh.Usage.FRAME, m -> write(m, this));
            } else {
                into.edit(this, CgCollisionDebug::write);
            }
            // one group at the eye, so back faces, lines and front faces draw in that order whatever their distances
            CgWorldRenderer.get().draw(into, material).at(ox, oy, oz).layer(CgSortLayer.OVERLAY).group(ex, ey, ez)
                    .order(order).submit();
            return into;
        }

        /**
         * {@link #order}: back faces then front faces, piece by piece far to near, a piece's back faces by its far side
         * and its front by its near, and within a piece by each triangle's middle; how many are back faces. Two
         * stable LSD radix sorts on float bits, the lesser key first.
         */
        int sortFrom(double ex, double ey, double ez) {
            if (order.length < count) {
                order = new int[colours.length];
                keys = new int[colours.length];
                nearer = new int[colours.length];
                orderScratch = new int[colours.length];
            }
            if (pieceN.length < pieces) {
                int size = Math.max(pieces, 2 * pieceN.length);
                pieceX = new double[size];
                pieceY = new double[size];
                pieceZ = new double[size];
                pieceR = new double[size];
                pieceN = new int[size];
            }
            Arrays.fill(pieceX, 0, pieces, 0);
            Arrays.fill(pieceY, 0, pieces, 0);
            Arrays.fill(pieceZ, 0, pieces, 0);
            Arrays.fill(pieceR, 0, pieces, 0);
            Arrays.fill(pieceN, 0, pieces, 0);
            double cx = ox - ex, cy = oy - ey, cz = oz - ez;
            // each piece's middle, from the eye: its corners' mean; then its reach, its farthest corner from that
            for (int i = 0, at = 0; i < count; i++, at += 9) {
                int g = pieceOf[i];
                pieceX[g] += points[at] + points[at + 3] + points[at + 6] + 3 * cx;
                pieceY[g] += points[at + 1] + points[at + 4] + points[at + 7] + 3 * cy;
                pieceZ[g] += points[at + 2] + points[at + 5] + points[at + 8] + 3 * cz;
                pieceN[g] += 3;
            }
            for (int g = 0; g < pieces; g++) {
                pieceX[g] /= pieceN[g];
                pieceY[g] /= pieceN[g];
                pieceZ[g] /= pieceN[g];
            }
            for (int i = 0, at = 0; i < count; i++, at += 9) {
                int g = pieceOf[i];
                for (int c = 0; c < 9; c += 3) {
                    double dx = points[at + c] + cx - pieceX[g], dy = points[at + c + 1] + cy - pieceY[g];
                    double dz = points[at + c + 2] + cz - pieceZ[g];
                    pieceR[g] = Math.max(pieceR[g], dx * dx + dy * dy + dz * dz);
                }
            }
            int backs = 0;
            for (int i = 0, at = 0; i < count; i++, at += 9) {
                double dx = (points[at] + points[at + 3] + points[at + 6]) / 3 + cx;
                double dy = (points[at + 1] + points[at + 4] + points[at + 7]) / 3 + cy;
                double dz = (points[at + 2] + points[at + 5] + points[at + 8]) / 3 + cz;
                // faces wind outward (CgShapeLines), so a back face's normal points away from the eye
                double ux = points[at + 3] - points[at], uy = points[at + 4] - points[at + 1];
                double uz = points[at + 5] - points[at + 2], vx = points[at + 6] - points[at];
                double vy = points[at + 7] - points[at + 1], vz = points[at + 8] - points[at + 2];
                double facing = (uy * vz - uz * vy) * dx + (uz * vx - ux * vz) * dy + (ux * vy - uy * vx) * dz;
                boolean front = facing < 0;
                if (!front) backs++;
                int g = pieceOf[i];
                double middle = Math.sqrt(pieceX[g] * pieceX[g] + pieceY[g] * pieceY[g] + pieceZ[g] * pieceZ[g]);
                double reach = Math.sqrt(pieceR[g]), side = front ? Math.max(0, middle - reach) : middle + reach;
                // a non-negative float's bits order as it does; flipped, ascending is far to near
                keys[i] = (front ? Integer.MIN_VALUE : 0) | Integer.MAX_VALUE - Float.floatToRawIntBits((float) side);
                nearer[i] = Integer.MAX_VALUE - Float.floatToRawIntBits((float) (dx * dx + dy * dy + dz * dz));
                order[i] = i;
            }
            radix(nearer);
            radix(keys);
            return backs;
        }

        /** Orders {@link #order} by {@code key} of each triangle, keeping the order of equals. */
        private void radix(int[] key) {
            for (int shift = 0; shift < 32; shift += 8) {
                Arrays.fill(counts, 0);
                for (int i = 0; i < count; i++) counts[key[order[i]] >>> shift & 0xFF]++;
                for (int b = 0, sum = 0; b < 256; b++) {
                    int n = counts[b];
                    counts[b] = sum;
                    sum += n;
                }
                for (int i = 0; i < count; i++) orderScratch[counts[key[order[i]] >>> shift & 0xFF]++] = order[i];
                int[] o = order;
                order = orderScratch;
                orderScratch = o;
            }
        }

        void release() {
            if (mesh != null) mesh.release();
            if (frontMesh != null) frontMesh.release();
            mesh = frontMesh = null;
            count = 0;
        }
    }
}
