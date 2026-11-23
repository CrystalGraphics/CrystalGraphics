package com.crystalgraphics.render.meshpass;

import com.crystalgraphics.api.material.CgMaterialInterface;

/**
 * Which rows of a mesh pass draw this frame, asked per row and per view before its kept commands are drawn: the part
 * of a pass's answer that changes from frame to frame, so its processor's commands can still be kept. Unreal's velocity
 * pass caches its commands and gates them at visibility by {@code PrimitiveHasVelocityForFrame}; this is that gate.
 *
 * <pre>{@code
 * // The processor keeps a command for every row that may ever draw; the filter picks this frame's
 * public static final CgMeshPassType TRAILS = CgMeshPassType.register("mymod:trails", layout,
 *         (batch, primitive, out) -> {
 *             if (primitive.keepsPrevious()) out.draw(batch, batch.material());
 *         },
 *         (primitive, material) -> primitive.hasVelocity());
 * }</pre>
 *
 * <ul>
 *   <li>Render thread, per row and per view each frame: answer from fields, never allocate.</li>
 *   <li>It only removes: a row the processor kept no command for draws nothing whatever it answers.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgMeshPassFilter {

    /** Whether {@code primitive}'s row, drawn with {@code material}, draws in the pass this frame. */
    boolean draws(CgPrimitiveInfo primitive, CgMaterialInterface material);
}
