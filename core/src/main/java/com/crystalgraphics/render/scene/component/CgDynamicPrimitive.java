package com.crystalgraphics.render.scene.component;

import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.shape.bounds.CgAABB;

/**
 * Geometry that changes every frame (a trail, an effect, a debug shape), asked for per view rather than kept: Unreal's
 * dynamic mesh elements ({@code GetDynamicMeshElements}). Added to a scene by {@code scene.dynamic(primitive)}, whose
 * component carries its settings (sort, passes, custom depth), which everything it collects takes.
 *
 * <pre>{@code
 * final class Trail implements CgDynamicPrimitive {
 *     public void collect(CgView view, CgMeshCollector out) {
 *         out.mesh(ribbon, trailMaterial).at(x, y, z).customData(0, r, g, b, a).add();
 *     }
 * }
 * CgDynamicComponent trail = scene.dynamic(new Trail()).layer(CgSortLayer.EFFECTS).spawn();
 * }</pre>
 *
 * <p>Culled against each view before it is asked: by {@link #bounds} where it moves every frame, else by its
 * component's {@code bounds(box)}; with neither, every view asks it.</p>
 *
 * <pre>{@code
 * final class Trail implements CgDynamicPrimitive {
 *     public boolean bounds(CgAABB out) {
 *         out.set(box);                         // absolute, this frame's
 *         return true;
 *     }
 *     public void collect(CgView view, CgMeshCollector out) { ... }
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Called on the frame thread, once per view that sees it this frame.</li>
 *   <li>{@code out} writes into the view's reused storage: collecting allocates nothing.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgDynamicPrimitive {

    /** Adds what {@code view} draws of it this frame. */
    void collect(CgView view, CgMeshCollector out);

    /** Its bounds this frame into {@code out}, absolute; false for none, which leaves its component's stated bounds. */
    default boolean bounds(CgAABB out) {
        return false;
    }
}
