package com.crystalgraphics.render.host;

import javax.annotation.Nullable;

import com.crystalgraphics.gpu.resource.CgTexture2D;
import com.crystalgraphics.platform.rhi.CgExternalTarget;
import com.crystalgraphics.render.view.CgView;

/**
 * A level its host draws under a scene of yours: Minecraft's blocks drawn by Minecraft into a target of its own, the
 * scene then drawn into that target beside its depth, as the game's view draws over Minecraft's world. A pocket level
 * ({@code mc.render.world.CgPocketLevel}) is one; a scene takes it with {@code scene.hostLevel(level)}.
 *
 * <pre>{@code
 * CgScene editor = new CgScene();
 * editor.hostLevel(level);                 // Minecraft draws the level under it
 * canvas.view(CgView.of(editor), x, y, w, h);
 * }</pre>
 *
 * <ul>
 *   <li>Engine side: a view asks it as it draws its picture, render thread, inside the device section. Its host draws
 *       outside the section, so what it answers was drawn earlier in the frame, at the host's draw point.</li>
 *   <li>A view's first frame, or its first at a new size, has no target yet: the view draws its scene alone that frame,
 *       and the level from the next.</li>
 *   <li>Its depth is in the host's world convention ({@code CgDepthConvention.worldReversed}), as its world pipelines
 *       write it: an implementation draws in it, and a view draws over it in it.</li>
 * </ul>
 */
public interface CgHostLevel {

    /** What its host drew for one view: the target (colour and depth, the host's own) and its colour as a texture. */
    record Drawn(CgExternalTarget target, CgTexture2D colour) {
    }

    /**
     * What its host drew for {@code view} at {@code width} x {@code height} this frame, or null where it has none yet. The
     * ask is remembered: the host draws it from the next draw point, from the view's camera as last set.
     */
    @Nullable
    Drawn drawn(CgView view, int width, int height);
}
