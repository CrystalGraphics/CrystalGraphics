package com.crystalgraphics.render.post.fog;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.material.CgMaterialValues;
import com.crystalgraphics.render.graph.CgScreenTexture;
import com.crystalgraphics.render.post.CgPostContext;
import com.crystalgraphics.render.post.CgPostEffect;
import com.crystalgraphics.render.post.CgPostMaterial;
import com.crystalgraphics.render.scene.component.CgHeightFogComponent;

import javax.annotation.Nullable;

/**
 * Engine side, every post chain's first effect at {@code SURFACES}: Unreal's exponential height fog pass
 * (FogRendering.cpp), drawing the height fog of the scene each view draws ({@code scene.heightFog()}) over its lit
 * opaque scene before translucency. Without the HDR scene it draws after the world onto the host's target, decoding and
 * encoding it.
 */
public final class CgHeightFogPass implements CgPostEffect {

    public static final String SHADER = "crystalgraphics:shaders/post/height_fog.shader";
    /** How far over the fog's height the observer is held (FogRendering.cpp's MaxObserverHeightDifference, 65536 cm). */
    private static final double MAX_OBSERVER_ABOVE = 655.36;

    private CgMaterialInstance material;
    private CgPostMaterial draw;
    private final float[] fog0 = new float[4], fog1 = new float[4];

    /** The fog the view's scene shows, or null. */
    @Nullable
    private static CgHeightFogComponent fog(CgPostContext ctx) {
        return ctx.view().scene().primitives().shownFog();
    }

    @Override
    public CgScreenTexture apply(CgPostContext ctx, CgScreenTexture input) {
        CgHeightFogComponent fog = fog(ctx);
        if (fog == null) return input;
        if (draw == null) {
            material = CgMaterial.load(SHADER).instance();
            draw = CgPostMaterial.drawer(material);
        }
        draw.weight(fog.weight());
        if (!draw.shows(ctx)) return input;
        double height = fog.height();
        float falloff = fog.heightFalloff(), density = fog.density();
        // FogRendering.cpp: the observer held below the fog's height plus 65536 cm, and the density at it collapsed
        double eye = ctx.view().camera().y();
        double observer = Math.min(eye, height + MAX_OBSERVER_ABOVE);
        double power = Math.max(-125.0, Math.min(126.0, -falloff * (observer - height)));
        fog0[0] = (float) (density * Math.pow(2.0, power));
        fog0[1] = falloff;
        fog0[2] = (float) (eye - observer);
        fog0[3] = fog.startDistance();
        fog1[0] = density;
        fog1[1] = (float) (observer - height);
        fog1[2] = fog.cutoffDistance();
        fog1[3] = 1f - fog.maxOpacity();
        write(material, fog);
        draw.draw(ctx, input);
        return input;
    }

    private void write(CgMaterialValues b, CgHeightFogComponent fog) {
        b.vec4("_Fog0", fog0[0], fog0[1], fog0[2], fog0[3]);
        b.vec4("_Fog1", fog1[0], fog1[1], fog1[2], fog1[3]);
        b.vec4("_FogColour", fog.red(), fog.green(), fog.blue(), 1f);
    }
}
