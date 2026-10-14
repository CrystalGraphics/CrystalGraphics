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
 *   <li>Shapes and hitboxes, the show's and those drawn here, have their faces drawn as well, at
 *       {@link #FACE_ALPHA} of their colour, so each reads as the space it tests with what is behind it still seen;
 *       not under {@link CgCollisionShow.Style#WIREFRAME}. Lines and marks are lines under both.</li>
 * </ul>
 */
public final class CgCollisionDebug {

    static final String SHADER = "crystalgraphics:shaders/debug/collision_lines.shader";
    private static final double MARK = 0.4;
    /** How much of a shown hitbox's faces show over what is behind them; its edges are drawn as they are. */
    static final float FACE_ALPHA = 0.25f;

    private static final Batch LINES = new Batch(2, 1f), FACES = new Batch(3, FACE_ALPHA);
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
            if (!faces) return;
            colour = argb;
            FACES.triangle(ax, ay, az, bx, by, bz, cx, cy, cz);
        }
    };
    private static boolean installed;
    /** Whether faces are drawn: {@link CgCollisionShow#STYLE}, read once a frame. */
    private static boolean faces;
    private static CgMaterial material;
    private static int colour;

    private CgCollisionDebug() {
    }

    /** Draws {@code shape} at {@code pose} on the next world frame. */
    public static void shape(CgShape shape, CgPose pose, int argb) {
        colour = argb;
        SHAPES.write(shape, pose, LINES);
        if (faces) SHAPES.faces(shape, pose, FACES);
    }

    /** Draws each part of {@code hitbox} as its last tick tested it on the next world frame; the last, once stopped. */
    public static void hitbox(CgHitbox hitbox, int argb) {
        colour = argb;
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
        faces = CgCollisionShow.STYLE.get() == CgCollisionShow.Style.TRANSPARENT;
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
        shape(shape, pose, colour);
    }

    private static void frame(CgHostView view) {
        // for what is written before the next frame: the outline lags the camera by a frame
        SHAPES.eye(view.x(), view.y(), view.z());
        faces = CgCollisionShow.STYLE.get() == CgCollisionShow.Style.TRANSPARENT;
        CgCollisionShow.drawTo(view.x(), view.y(), view.z(), SHOWN);
        FACES.draw();
        LINES.draw();
    }

    private static void write(CgMeshWriter m, Batch batch) {
        m.topology(batch.corners == 2 ? CgMeshTopology.LINES : CgMeshTopology.TRIANGLES);
        float[] p = batch.points;
        for (int i = 0, at = 0; i < batch.count; i++) {
            int argb = batch.colours[i];
            float r = (argb >>> 16 & 0xFF) / 255f, g = (argb >>> 8 & 0xFF) / 255f, b = (argb & 0xFF) / 255f;
            float a = (argb >>> 24) / 255f * batch.alpha;
            // the colour rides in the normal, its alpha in u: collision_lines.shader reads them back
            for (int c = 0; c < batch.corners; c++, at += 3) {
                m.vertex().position(p[at], p[at + 1], p[at + 2]).uv(a, 0f).normal(r, g, b).end();
            }
        }
    }

    /**
     * The frame's segments, or its triangles, as floats from the first one's start, which the draw stands at in
     * doubles.
     */
    private static final class Batch implements CgShapeLines.Sink {
        /** Points a record: two a segment, three a triangle. */
        final int corners;
        /** What each record's own alpha is drawn at. */
        final float alpha;
        float[] points;
        int[] colours = new int[512];
        int count;
        double ox, oy, oz;
        private CgMesh mesh;

        Batch(int corners, float alpha) {
            this.corners = corners;
            this.alpha = alpha;
            points = new float[corners * 3 * 512];
        }

        @Override
        public void line(double ax, double ay, double az, double bx, double by, double bz) {
            int at = record(ax, ay, az);
            point(at, ax, ay, az);
            point(at + 3, bx, by, bz);
        }

        @Override
        public void triangle(double ax, double ay, double az, double bx, double by, double bz, double cx, double cy,
                double cz) {
            int at = record(ax, ay, az);
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
            }
            colours[count] = colour;
            return count++ * corners * 3;
        }

        private void point(int at, double x, double y, double z) {
            points[at] = (float) (x - ox);
            points[at + 1] = (float) (y - oy);
            points[at + 2] = (float) (z - oz);
        }

        /** Submits what the frame queued, and empties it. */
        void draw() {
            if (count == 0) return;
            if (mesh == null) {
                mesh = CgMesh.build(CgVertexFormat.SPATIAL, CgMesh.Usage.FRAME, m -> write(m, this));
            } else {
                mesh.edit(this, CgCollisionDebug::write);
            }
            if (material == null) material = CgMaterial.load(SHADER);
            CgWorldRenderer.get().draw(mesh, material).at(ox, oy, oz).submit();
            count = 0;
        }

        void release() {
            if (mesh != null) mesh.release();
            mesh = null;
            count = 0;
        }
    }
}
