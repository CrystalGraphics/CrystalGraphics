package com.crystalgraphics.render;

import com.crystalgraphics.platform.rhi.CgExternalTarget;
import com.crystalgraphics.render.graph.CgExecutor;

import java.util.function.BooleanSupplier;

/**
 * Which way depth runs where the renderer draws (Unreal's {@code ERHIZBuffer}). The engine's own convention is Unreal's
 * reversed Z: near at depth 1, far at 0, clip depth 0 to 1. Every depth number the API takes is in it: a projection
 * ({@code CgCamera.perspectiveMatrix}), a clear ({@code CgLoad.CLEAR_DEPTH}, 0), a material's compare ({@code LEQUAL}
 * meaning nearer or equal). A target drawn as a host's ({@code CgExternalTarget.host}) runs the host's convention
 * instead, which the host declares here; every execution into one has its numbers converted at its edge.
 *
 * <pre>{@code
 * // a host whose world draws reversed (Minecraft 26.2), around its world passes:
 * CgDepthConvention.host(true);
 * try {
 *     CgRenderStage.WORLD_OPAQUE.fire();
 * } finally {
 *     CgDepthConvention.host(false);
 * }
 *
 * // declared once, before the first world frame: what a frame drawn as the world's outside one builds
 * CgDepthConvention.world(true);
 * }</pre>
 *
 * <ul>
 *   <li>A host's convention is standard (GL's: far at 1) until {@link #host} says otherwise; a host that draws as the
 *       engine does (the harness) declares reversed once.</li>
 *   <li>One execution, one convention: its current target's, so a pass into a texture of ours reading the host's depth
 *       reads it as written. A depth target kept across executions is drawn in one convention throughout.</li>
 *   <li>Render thread.</li>
 * </ul>
 */
public final class CgDepthConvention {

    private static boolean host, world;

    private CgDepthConvention() {
    }

    /**
     * Whether the host draws its depth reversed from here (nearer greater, cleared to 0, as the engine's own), or
     * standard. Around the host's passes; a target drawn as the host's takes it.
     */
    public static void host(boolean reversed) {
        host = reversed;
    }

    /** Whether the host draws its depth reversed now, as {@link #host} declared. */
    public static boolean hostReversed() {
        return host;
    }

    /** Whether the host's world draws reversed: declared once, before its first world frame. */
    public static void world(boolean reversed) {
        world = reversed;
    }

    /** Whether the host's world draws reversed, as {@link #world} declared. */
    public static boolean worldReversed() {
        return world;
    }

    /**
     * Runs {@code body} with the host's convention the world's ({@link #world}), then puts back the one in force: a frame
     * drawn as the world's outside one, whose pipelines must be the world's.
     *
     * <pre>{@code
     * CgDepthConvention.inWorld(() -> { stage.fire(); return true; });
     * }</pre>
     */
    public static boolean inWorld(BooleanSupplier body) {
        boolean was = host;
        host = world;
        try {
            return body.getAsBoolean();
        } finally {
            host = was;
        }
    }

    /** Whether what executes now runs reversed: its current target's convention, the engine's where none is open. */
    public static boolean reversed() {
        CgExternalTarget current = CgExecutor.currentTarget();
        return current == null || reversed(current);
    }

    /** Whether {@code target} is drawn reversed: the engine's convention, or the host's for one drawn as the host's. */
    public static boolean reversed(CgExternalTarget target) {
        return !target.drawsAsHost() || host;
    }

    /** The engine's depth {@code depth} (far 0) as a target running {@code reversed} or not stores it. */
    public static float stored(double depth, boolean reversed) {
        return (float) (reversed ? depth : 1.0 - depth);
    }
}
