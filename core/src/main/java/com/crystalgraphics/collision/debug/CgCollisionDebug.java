package com.crystalgraphics.collision.debug;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.mesh.CgMeshWriter;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.collision.CgHit;
import com.crystalgraphics.collision.CgHitPart;
import com.crystalgraphics.collision.hitbox.CgHitbox;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;

import java.util.Arrays;

/**
 * Shapes and hitboxes drawn as lines in the world, for the next world frame only: call each frame you want them seen.
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
 * </ul>
 */
public final class CgCollisionDebug {

    static final String SHADER = "crystalgraphics:shaders/debug/collision_lines.shader";
    private static final double MARK = 0.4;

    private static final Lines LINES = new Lines();
    private static final CgShapeLines SHAPES = new CgShapeLines();
    private static boolean installed;
    private static CgMesh mesh;
    private static CgMaterial material;
    private static int colour;

    private CgCollisionDebug() {
    }

    /** Draws {@code shape} at {@code pose} on the next world frame. */
    public static void shape(CgShape shape, CgPose pose, int argb) {
        colour = argb;
        SHAPES.write(shape, pose, LINES);
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
        CgCollisionShow.installClient();
        CgWorldRenderer.get().onFrame(CgCollisionDebug::frame);
    }

    /** Frees the mesh and material with the context. */
    public static void release() {
        if (mesh != null) mesh.release();
        mesh = null;
        material = null;
        LINES.count = 0;
    }

    private static void part(CgHitPart name, CgShape shape, CgPose pose) {
        SHAPES.write(shape, pose, LINES);
    }

    private static void frame(CgHostView view) {
        // for what is written before the next frame: the outline lags the camera by a frame
        SHAPES.eye(view.x(), view.y(), view.z());
        CgCollisionShow.drawTo(view.x(), view.y(), view.z(), CgCollisionDebug::coloured);
        if (LINES.count == 0) return;
        if (mesh == null) {
            mesh = CgMesh.build(CgVertexFormat.SPATIAL, CgMesh.Usage.FRAME, m -> write(m, LINES));
        } else {
            mesh.edit(LINES, CgCollisionDebug::write);
        }
        if (material == null) material = CgMaterial.load(SHADER);
        CgWorldRenderer.get().draw(mesh, material).at(LINES.ox, LINES.oy, LINES.oz).submit();
        LINES.count = 0;
    }

    private static void coloured(int argb, double ax, double ay, double az, double bx, double by, double bz) {
        colour = argb;
        LINES.line(ax, ay, az, bx, by, bz);
    }

    private static void write(CgMeshWriter m, Lines lines) {
        m.topology(CgMeshTopology.LINES);
        float[] p = lines.points;
        for (int i = 0; i < lines.count; i++) {
            int argb = lines.colours[i];
            float r = (argb >>> 16 & 0xFF) / 255f, g = (argb >>> 8 & 0xFF) / 255f, b = (argb & 0xFF) / 255f;
            float a = (argb >>> 24) / 255f;
            // the colour rides in the normal, its alpha in u: collision_lines.shader reads them back
            m.vertex().position(p[i * 6], p[i * 6 + 1], p[i * 6 + 2]).uv(a, 0f).normal(r, g, b).end();
            m.vertex().position(p[i * 6 + 3], p[i * 6 + 4], p[i * 6 + 5]).uv(a, 0f).normal(r, g, b).end();
        }
    }

    /** The frame's segments as floats from the first one's start, which the draw stands at in doubles. */
    private static final class Lines implements CgShapeLines.Sink {
        float[] points = new float[6 * 512];
        int[] colours = new int[512];
        int count;
        double ox, oy, oz;

        @Override
        public void line(double ax, double ay, double az, double bx, double by, double bz) {
            if (count == 0) {
                ox = ax;
                oy = ay;
                oz = az;
            }
            if (count == colours.length) {
                points = Arrays.copyOf(points, points.length * 2);
                colours = Arrays.copyOf(colours, colours.length * 2);
            }
            int at = count * 6;
            points[at] = (float) (ax - ox);
            points[at + 1] = (float) (ay - oy);
            points[at + 2] = (float) (az - oz);
            points[at + 3] = (float) (bx - ox);
            points[at + 4] = (float) (by - oy);
            points[at + 5] = (float) (bz - oz);
            colours[count++] = colour;
        }
    }
}
