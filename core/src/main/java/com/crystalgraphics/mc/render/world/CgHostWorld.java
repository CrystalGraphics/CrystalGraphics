package com.crystalgraphics.mc.render.world;

import java.util.List;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.mc.block.CgBlockState;
import com.crystalgraphics.mc.entity.CgEntityRef;
import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.mc.render.entity.CgEntityPose;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgHostDrawnComponent;

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

        /** The game's entity a component draws, made client-side, never in the level; as NR4's slot makes it. */
        @Nullable
        Object entity(CgEntityRef ref, @Nullable Object previous);

        /** The game's block state, and its block entity where it has one; null where this node has no block states. */
        @Nullable
        Object block(CgBlockState state, @Nullable Object previous);

        /** {@code item} under {@code model} (camera-relative), as {@code display} holds it, lit at its position. */
        void drawItem(Object item, CgItemDisplay display, Matrix4fc model);

        /** {@code entity} under {@code model}, its feet at the origin, posed relative to the model's facing. */
        void drawEntity(Object entity, CgEntityPose pose, float ageTicks, Matrix4fc model);

        /** A block, its corner at the origin, and its block entity. */
        void drawBlock(Object block, Matrix4fc model);

        /** Closes the draw point: what was drawn is flushed into Minecraft's targets. */
        void end();

        Host NONE = new Host() {
            @Override public String absent() { return "no world draw point on this node"; }
            @Override public void begin(Matrix4fc view, double camX, double camY, double camZ, float partialTick,
                                        boolean fullLight) { }
            @Override public Object item(CgItemStack stack, Object previous) { return null; }
            @Override public Object entity(CgEntityRef ref, Object previous) { return null; }
            @Override public Object block(CgBlockState state, Object previous) { return state; }
            @Override public void drawItem(Object item, CgItemDisplay display, Matrix4fc model) { }
            @Override public void drawEntity(Object entity, CgEntityPose pose, float ageTicks, Matrix4fc model) { }
            @Override public void drawBlock(Object block, Matrix4fc model) { }
            @Override public void end() { }
        };
    }

    public static final CgService<Host> HOST = CgService.of("crystalgraphics:host_world", Host.NONE);

    private static final Logger LOGGER = LogManager.getLogger("CgHostWorld");
    private static final Matrix4d relative = new Matrix4d();
    private static final Matrix4f model = new Matrix4f();
    private static boolean absentSaid;
    private static volatile int drawn;

    private CgHostWorld() {
    }

    /** How many components the last draw point drew. */
    public static int drawn() {
        return drawn;
    }

    /**
     * Host side, render thread, outside any device section: draws every spawned, visible host-drawn component of the
     * client scene under the level's {@code camera}, which the host frame holds once its opaque hook has captured it.
     */
    public static void drawPoint(CgCamera camera, float partialTick) {
        drawPoint(camera.x(), camera.y(), camera.z(), camera.view(), partialTick);
    }

    /**
     * The same, the camera given as Minecraft holds it where its own entities are drawn: at {@code camX, camY, camZ},
     * turned by {@code view}. What a host calls where it submits into Minecraft's own frame (1.21.10 on).
     */
    public static void drawPoint(double camX, double camY, double camZ, Matrix4fc view, float partialTick) {
        drawn = drawScene(CgScene.client(), camX, camY, camZ, view, partialTick, false);
    }

    /**
     * Host side: {@code scene}'s host-drawn components drawn where Minecraft is drawing that scene's world (a pocket
     * level's, into its view's target), and how many drew.
     */
    public static int drawScene(CgScene scene, double camX, double camY, double camZ, Matrix4fc view, float partialTick,
                                boolean fullLight) {
        List<CgHostDrawnComponent<?>> all = scene.primitives().hostDrawn();
        if (all.isEmpty()) return 0;
        int drew = 0;
        Host host = CgPlatform.get(HOST);
        if (host.absent() != null) {
            if (!absentSaid) LOGGER.info("[cg] Minecraft's objects in the scene are not drawn: {}", host.absent());
            absentSaid = true;
            return 0;
        }
        host.begin(view, camX, camY, camZ, partialTick, fullLight);
        try {
            for (int i = 0; i < all.size(); i++) {
                CgHostDrawnComponent<?> c = all.get(i);
                if (!c.visible()) continue;
                // Camera-relative in doubles, so a far world keeps its precision in the float the host draws with
                relative.set(c.worldTransform());
                relative.m30(relative.m30() - camX).m31(relative.m31() - camY).m32(relative.m32() - camZ);
                model.set(relative);
                try {
                    c.drawHosted(model);
                    drew++;
                } catch (RuntimeException e) {
                    // One object's renderer failing leaves the rest drawn, as a picture's does
                    LOGGER.warn("[cg] {} failed to draw, hidden: {}", c.getClass().getSimpleName(), e.toString());
                    c.visible(false);
                }
            }
        } finally {
            host.end();
        }
        return drew;
    }
}
