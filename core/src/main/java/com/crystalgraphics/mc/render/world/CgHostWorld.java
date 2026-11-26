package com.crystalgraphics.mc.render.world;

import com.crystalgraphics.mc.render.CgHostAllocation;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.trace.CgTrace;
import java.util.List;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.FrustumIntersection;
import org.joml.Matrix4fc;

import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.mc.block.CgBlockState;
import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.mc.render.entity.CgEntityPose;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgHostDrawnComponent;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.bounds.CgAABBc;

/**
 * The world draw point: Minecraft draws the client scene's {@link CgHostDrawnComponent}s with its own renderers, into its
 * world, before the engine's opaque passes read its depth. Each era's opaque hook calls {@link #drawPoint} ahead of its
 * device section; a mod never does.
 */
public final class CgHostWorld {

    /** What an era answers, render thread. */
    public interface Host {

        /** Why this node draws no host-drawn components; null where it does. */
        @Nullable
        String absent();

        /**
         * Opens a draw point: Minecraft's buffers ready for {@link #drawItem} and the rest. {@code view} is the level's
         * camera rotation, as the host frame's camera holds it, at {@code camX, camY, camZ}. {@code fullLight}: lit as a
         * pocket level is, block and sky 15, never by the client world's light at the component's block.
         */
        void begin(Matrix4fc view, double camX, double camY, double camZ, float partialTick, boolean fullLight);

        /** The game's stack for {@code stack}; {@code previous} is what this answered last for the same component. */
        @Nullable
        Object item(CgItemStack stack, @Nullable Object previous);

        /** The game's block state, and its block entity where it has one; null where this node has no block states. */
        @Nullable
        Object block(CgBlockState state, @Nullable Object previous);

        /** {@code item} under {@code model} (camera-relative), as {@code display} holds it, lit at its position. */
        void drawItem(Object item, CgItemDisplay display, Matrix4fc model);

        /**
         * {@code entity} (a ref resolved, a handle's, or the author's) under {@code model}, its feet at the origin,
         * posed relative to the model's facing; its own angles and motion put back after.
         */
        void drawEntity(Object entity, CgEntityPose pose, float ageTicks, Matrix4fc model);

        /** A block, its corner at the origin, and its block entity. */
        void drawBlock(Object block, Matrix4fc model);

        /**
         * The box Minecraft culls {@code drawn} (an entity, or what {@link #block} answered) by, relative to its
         * origin: an entity's culling box inflated as its renderer inflates it, a block's cube. False where Minecraft
         * never culls it (an entity drawn whole wherever it is, a block entity drawn past its block). Render thread.
         */
        default boolean bounds(Object drawn, CgAABB out) {
            return false;
        }

        /** Whether Minecraft draws {@code drawn} {@code distanceSq} from the camera, as it decides for its own. */
        default boolean inRange(Object drawn, double distanceSq) {
            return true;
        }

        /** Closes the draw point: what was drawn is flushed into Minecraft's targets. */
        void end();

        Host NONE = new Host() {
            @Override public String absent() { return "no world draw point on this node"; }
            @Override public void begin(Matrix4fc view, double camX, double camY, double camZ, float partialTick,
                                        boolean fullLight) { }
            @Override public Object item(CgItemStack stack, Object previous) { return null; }
            @Override public Object block(CgBlockState state, Object previous) { return state; }
            @Override public void drawItem(Object item, CgItemDisplay display, Matrix4fc model) { }
            @Override public void drawEntity(Object entity, CgEntityPose pose, float ageTicks, Matrix4fc model) { }
            @Override public void drawBlock(Object block, Matrix4fc model) { }
            @Override public void end() { }
        };
    }

    public static final CgService<Host> HOST = CgService.of("crystalgraphics:host_world", Host.NONE);

    private static final Logger LOGGER = LogManager.getLogger("CgHostWorld");
    private static final CgHostAllocation ALLOCATION = new CgHostAllocation(CgChannels.PICTURES, "host-world-our-bytes");
    private static final Matrix4d relative = new Matrix4d();
    private static final Matrix4f model = new Matrix4f();
    private static final Matrix4f viewProjection = new Matrix4f();
    private static final FrustumIntersection frustum = new FrustumIntersection();
    private static boolean absentSaid;
    private static volatile int drawn, lastCulled;

    private CgHostWorld() {
    }

    /** What the last draw point allocated of its own, Minecraft's draws left out; -1 where unmeasured. Any thread. */
    public static long ourBytes() {
        return ALLOCATION.last();
    }

    /** How many components the last draw point drew. */
    public static int drawn() {
        return drawn;
    }

    /** How many the last draw left out, outside the frustum or Minecraft's range. */
    public static int culled() {
        return lastCulled;
    }

    /**
     * Host side, render thread, outside any device section: draws every spawned, visible host-drawn component of the
     * client scene in the level's {@code camera}'s view and range, the camera the host frame holds once its opaque hook
     * has captured it.
     */
    public static void drawPoint(CgCamera camera, float partialTick) {
        drawPoint(camera, camera.view(), partialTick);
    }

    /**
     * The same, drawn under {@code view} rather than the camera's own: what a host passes where it submits into
     * Minecraft's own frame (1.21.10 on), which turns what is submitted itself. {@code camera} places and culls.
     */
    public static void drawPoint(CgCamera camera, Matrix4fc view, float partialTick) {
        drawn = drawScene(CgScene.client(), camera, view, partialTick, false);
    }

    /**
     * Host side: {@code scene}'s host-drawn components drawn where Minecraft is drawing that scene's world (a pocket
     * level's, into its view's target), and how many drew: those in {@code camera}'s frustum and Minecraft's range,
     * drawn under {@code view} at the camera's origin.
     */
    public static int drawScene(CgScene scene, CgCamera camera, Matrix4fc view, float partialTick, boolean fullLight) {
        double camX = camera.originX(), camY = camera.originY(), camZ = camera.originZ();
        double eyeX = camera.x(), eyeY = camera.y(), eyeZ = camera.z();
        List<CgHostDrawnComponent<?>> all = scene.primitives().hostDrawn();
        if (all.isEmpty()) return 0;
        int drew = 0;
        Host host = CgPlatform.get(HOST);
        if (host.absent() != null) {
            if (!absentSaid) LOGGER.info("[cg] Minecraft's objects in the scene are not drawn: {}", host.absent());
            absentSaid = true;
            return 0;
        }
        ALLOCATION.begin();
        long hostMark = ALLOCATION.hostBegin();
        host.begin(view, camX, camY, camZ, partialTick, fullLight);
        ALLOCATION.hostEnd(hostMark);
        long zone = CgTrace.begin(CgChannels.PICTURES, "hostWorld.draw");
        // Camera-relative, as the engine's own primitives are culled (CgSceneGather)
        frustum.set(viewProjection.set(camera.projection()).mul(camera.view()));
        int culled = 0;
        try {
            for (int i = 0; i < all.size(); i++) {
                CgHostDrawnComponent<?> c = all.get(i);
                if (!c.visible()) continue;
                CgAABBc box = c.worldBounds();
                if (box != null && !frustum.testAab((float) (box.minX() - camX), (float) (box.minY() - camY),
                        (float) (box.minZ() - camZ), (float) (box.maxX() - camX), (float) (box.maxY() - camY),
                        (float) (box.maxZ() - camZ))) {
                    culled++;
                    continue;
                }
                double dx = c.x() - eyeX, dy = c.y() - eyeY, dz = c.z() - eyeZ;
                long rangeMark = ALLOCATION.hostBegin();
                boolean inRange;
                try {
                    inRange = c.inRangeHosted(dx * dx + dy * dy + dz * dz);
                } finally {
                    ALLOCATION.hostEnd(rangeMark);
                }
                if (!inRange) {
                    culled++;
                    continue;
                }
                // Camera-relative in doubles, so a far world keeps its precision in the float the host draws with
                relative.set(c.worldTransform());
                relative.m30(relative.m30() - camX).m31(relative.m31() - camY).m32(relative.m32() - camZ);
                model.set(relative);
                long drawMark = ALLOCATION.hostBegin();
                try {
                    c.drawHosted(model);
                    drew++;
                } catch (RuntimeException e) {
                    // One object's renderer failing leaves the rest drawn, as a picture's does
                    LOGGER.warn("[cg] {} failed to draw, hidden: {}", c.getClass().getSimpleName(), e.toString());
                    c.visible(false);
                } finally {
                    ALLOCATION.hostEnd(drawMark);
                }
            }
        } finally {
            long endMark = ALLOCATION.hostBegin();
            host.end();
            ALLOCATION.hostEnd(endMark);
            CgTrace.end(zone);
            ALLOCATION.end();
        }
        CgTrace.add(CgChannels.PICTURES, "host-drawn-components", drew);
        CgTrace.add(CgChannels.PICTURES, "host-drawn-culled", culled);
        lastCulled = culled;
        return drew;
    }
}
