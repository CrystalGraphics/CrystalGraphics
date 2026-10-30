package com.crystalgraphics.render.post.look;

import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.post.CgPostContext;
import com.crystalgraphics.render.post.CgPostEffect;
import com.crystalgraphics.render.post.CgPostPoint;
import com.crystalgraphics.render.post.composite.CgCompositeFeature;
import com.crystalgraphics.render.post.composite.CgPostComposite;
import com.crystalgraphics.render.post.volume.CgPostSettings;
import com.crystalgraphics.render.stage.CgFrameKeys;
import com.crystalgraphics.settings.CgGraphicsSettings;

import java.util.EnumSet;

/**
 * The engine's screen-wide looks, from the firing's blended volumes ({@link CgPostContext#settings()}) into the
 * composite: a flash, a vignette, chromatic aberration, impact frames, a fade, Unreal's colour grading and its
 * tonemapper. Built in;
 * nothing calls it but the stack.
 *
 * <ul>
 *   <li>Flash and impact frames are scaled by the player's {@code CgGraphicsSettings.FLASHES}: 0 shows neither. A fade
 *       is not: it is a cut, not a flash.</li>
 *   <li>A look at its neutral value records nothing, so a firing with no volume draws as before.</li>
 *   <li>A drawn impact frame reads the world renderer's subject ({@link CgFrameKeys#SUBJECT}), bent as the scene was.</li>
 *   <li>The tonemapper's table is rebaked only when its film settings or the grade move ({@link CgFilmLut}); with it
 *       on, the grade is in the table, and without it the composite grades ({@link CgColorGrade}).</li>
 * </ul>
 */
public final class CgPostLooks implements CgPostEffect {

    private final CgFilmLut lut = new CgFilmLut();
    private final CgColorGrade grading = new CgColorGrade();

    @Override
    public CgPostPoint point() {
        return CgPostPoint.BEFORE_COMPOSITE;
    }

    @Override
    public boolean active(CgPostContext post) {
        CgPostSettings s = post.settings();
        float flashes = CgGraphicsSettings.FLASHES.get();
        return s.flash() * flashes != 0f || s.vignette() > 0f || s.chromatic() > 0f || s.impact() * flashes > 0f
                || s.fade() > 0f || s.tonemaps() || s.grades();
    }

    /**
     * The composite features {@code s} switches on, as {@link #record} switches them: what a preparer compiles the
     * composite for before a volume of {@code s} plays.
     *
     * <pre>{@code
     * CgPostLooks.features(new CgPostSettings().chromatic(3f));   // [CHROMATIC]
     * }</pre>
     */
    public static EnumSet<CgCompositeFeature> features(CgPostSettings s) {
        EnumSet<CgCompositeFeature> on = EnumSet.noneOf(CgCompositeFeature.class);
        float flashes = CgGraphicsSettings.FLASHES.get();
        if (s.flash() * flashes != 0f) on.add(CgCompositeFeature.FLASH);
        if (s.vignette() > 0f) on.add(CgCompositeFeature.VIGNETTE);
        if (s.chromatic() > 0f) on.add(CgCompositeFeature.CHROMATIC);
        if (s.impact() * flashes > 0f) on.add(CgCompositeFeature.IMPACT);
        if (s.fade() > 0f) on.add(CgCompositeFeature.FADE);
        if (s.tonemaps()) on.add(CgCompositeFeature.TONEMAP);
        else if (s.grades()) on.add(CgCompositeFeature.GRADE);
        return on;
    }

    /** Whether {@code s} shows an impact frame that reads the subject: what the stack asks the world renderer for. */
    public boolean drawsImpact(CgPostSettings s) {
        return s.impact() * CgGraphicsSettings.FLASHES.get() > 0f && s.impactLook().readsSubject();
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
        if (impact > 0f) {
            CgGraphTexture subject = s.impactLook().readsSubject() ? post.resources().get(CgFrameKeys.SUBJECT) : null;
            composite.impact(s.impactLook(), impact, s.impactSeed(), s.focusX(), s.focusY(),
                    subject != null ? post.distorted(subject) : null);
        }
        if (s.fade() > 0f) composite.fade(s.fade(), s.fadeR(), s.fadeG(), s.fadeB());
        if (s.tonemaps()) composite.tonemap(lut.texture(s));
        else if (s.grades()) composite.grade(grading.constants(s));
    }
}
