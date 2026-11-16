package com.crystalgraphics.render.post.debug;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgScreenTexture;
import com.crystalgraphics.render.post.CgPostContext;
import com.crystalgraphics.render.post.bloom.CgBloom;
import com.crystalgraphics.render.look.CgLookWeights;
import com.crystalgraphics.render.post.tsr.CgTemporalSuperResolution;
import com.crystalgraphics.render.scene.renderer.feature.distortion.CgDistortionField;
import com.crystalgraphics.variable.CgVar;

/**
 * Draws what bloom works from over the whole frame, last: one stage of its layers. Set by
 * {@code -Dcrystalgraphics.post.debug=level<N>} (0 is the glow the composite reads), so a material's
 * glow can be seen without the picture round it; {@code =overdraw} shows the world renderer's overdraw count through a
 * heat ramp (blue 1, green 4, red 16, white 32), {@code =distortion} the distortion field's offsets summed (|offset| x
 * 50 in red and green, the split in blue), {@code =velocity} what the velocity pass wrote (0.5 + texels a frame / 32 in
 * red and green, blue where it wrote, half blue where a blended draw covered, black where nothing did: every engine solid
 * writes while it shows), {@code =tsr.rejection|tsr.samples|tsr.guide|tsr.reprojection|tsr.flicker|tsr.resurrection}
 * one of TSR's passes' targets ({@code CgTemporalSuperResolution}'s visualize kernel says what each colour is). Each post
 * chain runs it as its last step while the variable is set, following it as it changes.
 */
public final class CgPostDebug {

    private static final String SHADER = "crystalgraphics:shaders/post/debug.shader";
    private static final String DISTORTION_SHADER = "crystalgraphics:shaders/post/debug_distortion.shader";
    private static final String VELOCITY_SHADER = "crystalgraphics:shaders/post/debug_velocity.shader";

    private static final int OVERDRAW = -2, DISTORTION = -3, VELOCITY = -4, TSR = -5, LOOKS = -6;

    /** {@link #OVERDRAW}, {@link #DISTORTION}, {@link #VELOCITY}, else bloom's stage. */
    private final int level;
    private CgMaterialInstance material;
    private CgTexture bound, boundResponsive;
    private int boundCount;

    private CgPostDebug(int level) {
        this.level = level;
    }

    /** What the frame shows for debugging; empty for nothing. */
    public static final CgVar.Text VIEW = CgVar.text("crystalgraphics.post.debug", "", CgVar.DEV,
            "level<N>: a stage of bloom's layers, 0 the glow the composite"
                    + " reads. overdraw: transparent fragments per pixel as a heat ramp (blue 1, green 4, red 16, white 32)."
                    + " distortion: the distortion target, |offset| x 50 in red and green, the split in blue."
                    + " velocity: what the velocity pass wrote, 0.5 + texels a frame / 32 in red and green, blue where it"
                    + " wrote. tsr.rejection, tsr.samples, tsr.guide, tsr.reprojection, tsr.flicker, tsr.resurrection: TSR's"
                    + " passes. looks: the look weights (CgLookWeights), look channels 0-2 in red, green and blue.");

    /** The view {@code view} names ({@link #VIEW}'s values); null for empty. Throws on a value it cannot read. */
    public static CgPostDebug of(String view) {
        if (view.isEmpty()) return null;
        if (view.equals("overdraw")) return new CgPostDebug(OVERDRAW);
        if (view.equals("distortion")) return new CgPostDebug(DISTORTION);
        if (view.equals("velocity")) return new CgPostDebug(VELOCITY);
        if (view.equals("looks")) return new CgPostDebug(LOOKS);
        if (CgTemporalSuperResolution.debugMode(view) > 0) return new CgPostDebug(TSR);
        if (view.startsWith("level")) {
            try {
                return new CgPostDebug(Math.max(0, Integer.parseInt(view.substring(5))));
            } catch (NumberFormatException ignored) {
                // falls through to the refusal
            }
        }
        throw new IllegalArgumentException("crystalgraphics.post.debug=" + view
                + ": overdraw, distortion, velocity, looks, level<N> or tsr.<rejection|samples|guide|reprojection|flicker|resurrection>");
    }

    /** Whether it shows the velocity target: the frame asks for it, every engine solid writing. */
    public boolean showsVelocity() {
        return level == VELOCITY;
    }

    /** Draws its view over {@code picture} where there is one this frame; answers {@code picture}. */
    public CgScreenTexture apply(CgPostContext ctx, CgScreenTexture picture) {
        CgTexture source, responsive = null;
        int count = 0;
        if (level == DISTORTION) {
            CgDistortionField field = ctx.view().read(CgDistortionField.KEY);
            if (field == null) return picture;
            source = field.offsets();
            count = field.count();
        } else if (level == VELOCITY) {
            source = ctx.scene().velocity();
            if (source == null) return picture;
            responsive = ctx.scene().responsive();
        } else if (level == TSR) {
            source = ctx.view().read(CgTemporalSuperResolution.DEBUG_VIEW);
            if (source == null) return picture;
        } else if (level == LOOKS) {
            source = CgLookWeights.current(ctx.view());
            if (source == null) return picture;
        } else if (level < 0) {
            source = ctx.scene().overdraw();
            if (source == null) return picture;
        } else {
            CgGraphTexture[] stages = ctx.view().read(CgBloom.LEVELS);
            if (stages == null) return picture;
            int k = Math.min(level, stages.length - 1);
            while (k > 0 && stages[k] == null) k--;
            source = stages[k];
        }
        if (material == null) {
            CgMaterial shown = CgMaterial.load(level == DISTORTION ? DISTORTION_SHADER
                    : level == VELOCITY ? VELOCITY_SHADER : SHADER);
            material = (level == OVERDRAW ? shown.keyword("HEAT", true) : shown).instance();
        }
        if (source != bound || count != boundCount) {
            if (level == DISTORTION) material.sampler("_Fields", 0, source).set1i("_FieldCount", count);
            else material.sampler("_Source", 0, source);
            bound = source;
            boundCount = count;
        }
        if (responsive != null && responsive != boundResponsive) {
            material.sampler("_Responsive", 1, responsive);
            boundResponsive = responsive;
        }
        return ctx.screen(material).name("postDebug.target").reads(source).reads(responsive).over(picture);
    }

    /** Forgets its material, which the material registry frees with the context. */
    public void release() {
        material = null;
        bound = boundResponsive = null;
    }
}
