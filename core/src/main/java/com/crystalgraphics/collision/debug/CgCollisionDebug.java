package com.crystalgraphics.collision.debug;

import com.crystalgraphics.render.debug.CgDebugDraw;
import com.crystalgraphics.render.debug.CgDebugBatch;
import com.crystalgraphics.render.debug.CgLineSink;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.mesh.CgMeshWriter;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.collision.hit.CgHit;
import com.crystalgraphics.collision.hit.CgHitPart;
import com.crystalgraphics.collision.hitbox.CgHitbox;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.scene.sort.CgSortLayer;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

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
 *       their faces drawn as well, at a quarter of their colour's alpha, so each reads as the space it tests with
 *       what is behind it still seen. A hitbox with its own {@link CgHitbox#showFill(float) fill} is filled at that
 *       under either style. Lines and marks are lines under both.</li>
 *   <li>Drawn over the world's other transparent draws ({@link CgSortLayer#OVERLAY}): the faces turned away, the
 *       lines, then the faces turned toward the eye, each far to near, so a face tints the edges behind it.</li>
 * </ul>
 */
public final class CgCollisionDebug {

    private static final double MARK = 0.4;
    private static final int ZONE_SHOW = CgTrace.name("collision.debug.show"), ZONE_FACES = CgTrace.name("collision.debug.faces"),
            ZONE_LINES = CgTrace.name("collision.debug.lines");

    private static final CgDebugBatch LINES = new CgDebugBatch(2), FACES = new CgDebugBatch(3);
    private static final CgShapeLines SHAPES = new CgShapeLines();
    private static final CgRecentLines.Out SHOWN = new CgRecentLines.Out() {
        @Override
        public void line(int argb, double ax, double ay, double az, double bx, double by, double bz) {
            colour(argb);
            LINES.line(ax, ay, az, bx, by, bz);
        }

        @Override
        public void triangle(int argb, double ax, double ay, double az, double bx, double by, double bz, double cx,
                double cy, double cz) {
            colour(argb);
            FACES.triangle(ax, ay, az, bx, by, bz, cx, cy, cz);
        }

        @Override
        public void piece() {
            FACES.piece();
        }
    };
    private static boolean installed;
    /** {@link CgCollisionShow#transparent()}, read once a frame. */
    private static boolean transparent;
    private static CgMaterial material;
    private static int colour, fill;

    private CgCollisionDebug() {
    }

    /** The colour the next lines and faces take. */
    private static void colour(int argb) {
        colour = argb;
        LINES.colour(argb);
        FACES.colour(argb);
    }

    /** Draws {@code shape} at {@code pose} on the next world frame. */
    public static void shape(CgShape shape, CgPose pose, int argb) {
        shape(shape, pose, argb, transparent ? CgRecentLines.FILL : 0);
    }

    /**
     * Draws each part of {@code hitbox} as its last tick tested it on the next world frame, the last once stopped;
     * filled by its {@link CgHitbox#showFill(float)}.
     */
    public static void hitbox(CgHitbox hitbox, int argb) {
        colour(argb);
        int own = CgRecentLines.ownFill(hitbox);
        fill = own == CgRecentLines.DEFAULT_FILL ? transparent ? CgRecentLines.FILL : 0 : own;
        hitbox.parts(CgCollisionDebug::part);
    }

    /** Draws a segment on the next world frame. */
    public static void line(double ax, double ay, double az, double bx, double by, double bz, int argb) {
        colour(argb);
        LINES.line(ax, ay, az, bx, by, bz);
    }

    /** Draws a hit on the next world frame: a cross on the surface and its normal. */
    public static void mark(CgHit hit, int argb) {
        mark(hit.x(), hit.y(), hit.z(), hit.normalX(), hit.normalY(), hit.normalZ(), argb);
    }

    /** Draws a cross on a surface at a point and its unit normal on the next world frame. */
    public static void mark(double x, double y, double z, double nx, double ny, double nz, int argb) {
        colour(argb);
        CgShapeLines.mark(x, y, z, nx, ny, nz, MARK, LINES);
    }

    /** Draws what was queued each world frame, and the show's. Once, at the engine's start. */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        transparent = CgCollisionShow.transparent();
        CgCollisionShow.installClient();
        CgWorld.client().schedule().add(CgTickGroup.SUBMIT, "crystalgraphics:collision-debug",
                dt -> frame(CgWorld.client().view())).whilePaused(true);
    }

    private static CgMaterial material() {
        if (material == null) material = CgMaterial.load(CgDebugDraw.SHADER);
        return material;
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

    /** Its edges, and its faces at {@code fill} of 255 of its colour's alpha. */
    private static void shape(CgShape shape, CgPose pose, int argb, int fill) {
        colour(argb);
        SHAPES.write(shape, pose, LINES);
        if (fill <= 0) return;
        colour(CgRecentLines.scaled(argb, fill));
        SHAPES.faces(shape, pose, FACES);
        colour(argb);
    }

    private static void frame(CgCamera view) {
        // for what is written before the next frame: the outline lags the camera by a frame
        SHAPES.eye(view.x(), view.y(), view.z());
        transparent = CgCollisionShow.transparent();
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.MISC, ZONE_SHOW)) {
            CgCollisionShow.drawTo(view.x(), view.y(), view.z(), SHOWN);
        }
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.MISC, ZONE_FACES)) {
            FACES.draw(view.x(), view.y(), view.z(), material(), 0);
        }
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.MISC, ZONE_LINES)) {
            LINES.draw(view.x(), view.y(), view.z(), material(), 0);
        }
    }
}
