package com.crystalgraphics.render.post.debug;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgScreenTexture;
import com.crystalgraphics.render.post.CgPostContext;
import com.crystalgraphics.render.post.bloom.CgBloom;
import com.crystalgraphics.render.scene.renderer.feature.distortion.CgDistortionField;
import com.crystalgraphics.variable.CgVar;

/**
 * Draws what bloom works from over the whole frame, last: one stage of its layers. Set by
 * {@code -Dcrystalgraphics.post.debug=level<N>} (0 is the glow the composite reads), so a material's
 * glow can be seen without the picture round it; {@code =overdraw} shows the world renderer's overdraw count through a
 * heat ramp (blue 1, green 4, red 16, white 32), {@code =distortion} the distortion field's offsets summed (|offset| x
 * 50 in red and green, the split in blue). Each post chain runs it as its last step when the flag is set.
 */
public final class CgPostDebug {

    private static final String SHADER = "crystalgraphics:shaders/post/debug.shader";
    private static final String DISTORTION_SHADER = "crystalgraphics:shaders/post/debug_distortion.shader";

    private static final int OVERDRAW = -2, DISTORTION = -3;

    /** {@link #OVERDRAW}, {@link #DISTORTION}, else bloom's stage. */
    private final int level;
    private CgMaterialInstance material;
    private CgTexture bound;
    private int boundCount;

    private CgPostDebug(int level) {
        this.level = level;
    }

    /** What the frame shows for debugging; empty for nothing. */
    public static final CgVar.Text VIEW = CgVar.text("crystalgraphics.post.debug", "", CgVar.DEV,
            "level<N>: a stage of bloom's layers, 0 the glow the composite"
                    + " reads. overdraw: transparent fragments per pixel as a heat ramp (blue 1, green 4, red 16, white 32)."
                    + " distortion: the distortion target, |offset| x 50 in red and green, the split in blue.");

    /** The view {@link #VIEW} asks for; null when it is unset. Throws on a value it cannot read. */
    public static CgPostDebug fromProperty() {
        String view = VIEW.get();
        if (view.isEmpty()) return null;
        if (view.equals("overdraw")) return new CgPostDebug(OVERDRAW);
        if (view.equals("distortion")) return new CgPostDebug(DISTORTION);
        if (view.startsWith("level")) {
            try {
                return new CgPostDebug(Math.max(0, Integer.parseInt(view.substring(5))));
            } catch (NumberFormatException ignored) {
                // falls through to the refusal
            }
        }
        throw new IllegalArgumentException("-Dcrystalgraphics.post.debug=" + view + ": overdraw, distortion or level<N>");
    }

    /** Draws its view over {@code picture} where there is one this frame; answers {@code picture}. */
    public CgScreenTexture apply(CgPostContext ctx, CgScreenTexture picture) {
        CgTexture source;
        int count = 0;
        if (level == DISTORTION) {
            CgDistortionField field = ctx.view().read(CgDistortionField.KEY);
            if (field == null) return picture;
            source = field.offsets();
            count = field.count();
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
            CgMaterial shown = CgMaterial.load(level == DISTORTION ? DISTORTION_SHADER : SHADER);
            material = (level == OVERDRAW ? shown.keyword("HEAT", true) : shown).instance();
        }
        if (source != bound || count != boundCount) {
            if (level == DISTORTION) material.sampler("_Fields", 0, source).set1i("_FieldCount", count);
            else material.sampler("_Source", 0, source);
            bound = source;
            boundCount = count;
        }
        return ctx.screen(material).name("postDebug.target").reads(source).over(picture);
    }

    /** Forgets its material, which the material registry frees with the context. */
    public void release() {
        material = null;
        bound = null;
    }
}
