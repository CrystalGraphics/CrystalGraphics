package com.crystalgraphics.render.post.look;

import com.crystalgraphics.render.post.CgPostContext;
import com.crystalgraphics.render.post.CgPostEffect;
import com.crystalgraphics.render.post.CgPostPoint;
import com.crystalgraphics.render.post.composite.CgPostComposite;
import com.crystalgraphics.render.post.volume.CgPostSettings;
import com.crystalgraphics.settings.CgGraphicsSettings;

/**
 * The engine's screen-wide looks, from the firing's blended volumes ({@link CgPostContext#settings()}) into the
 * composite: a flash, a vignette, chromatic aberration and impact frames. Built in; nothing calls it but the stack.
 *
 * <ul>
 *   <li>Flash and impact frames are scaled by the player's {@code CgGraphicsSettings.FLASHES}: 0 shows neither.</li>
 *   <li>A look at its neutral value records nothing, so a firing with no volume draws as before.</li>
 * </ul>
 */
public final class CgPostLooks implements CgPostEffect {

    @Override
    public CgPostPoint point() {
        return CgPostPoint.BEFORE_COMPOSITE;
    }

    @Override
    public boolean active(CgPostContext post) {
        CgPostSettings s = post.settings();
        return s.flash() != 0f || s.vignette() > 0f || s.chromatic() > 0f || s.impact() > 0f;
    }

    @Override
    public void record(CgPostContext post) {
        CgPostSettings s = post.settings();
        CgPostComposite composite = post.composite();
        float flashes = CgGraphicsSettings.FLASHES.get();
        float flash = s.flash() * flashes, impact = s.impact() * flashes;
        if (flash != 0f) composite.flash((float) Math.pow(2.0, flash));
        if (s.vignette() > 0f) composite.vignette(s.vignette());
        if (s.chromatic() > 0f) composite.chromatic(s.chromatic(), s.focusX(), s.focusY());
        if (impact > 0f) composite.impact(s.impactLook(), impact, s.focusX(), s.focusY());
    }
}
