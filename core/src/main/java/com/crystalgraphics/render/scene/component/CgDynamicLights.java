package com.crystalgraphics.render.scene.component;

import com.crystalgraphics.render.scene.renderer.feature.light.CgLightCollector;
import com.crystalgraphics.render.view.CgView;

/**
 * Many short-lived lights a frame, collected rather than kept: sparks, embers, a demo's moving lamps. Unreal's simple
 * lights, gathered each frame from what makes them.
 *
 * <pre>{@code
 * final class Embers implements CgDynamicLights {
 *     public void collect(CgView view, CgLightCollector out) {
 *         for (Ember e : live) out.point(e.x, e.y, e.z, 2f, 0xFFFF8030, 6f).castsShadow(false);
 *     }
 * }
 * AutoCloseable on = scene.dynamicLights(new Embers());
 * on.close();                                                    // collected no more
 * }</pre>
 *
 * <ul>
 *   <li>Called once a frame, before the frame's first world firing, with the view being drawn; render thread.</li>
 *   <li>Allocate nothing in it: it runs every frame.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgDynamicLights {

    /** This frame's lights into {@code out}. */
    void collect(CgView view, CgLightCollector out);
}
