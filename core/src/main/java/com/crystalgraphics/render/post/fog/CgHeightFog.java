package com.crystalgraphics.render.post.fog;

import java.util.function.Consumer;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialValues;
import com.crystalgraphics.render.post.CgPostContext;
import com.crystalgraphics.render.post.CgPostEffect;
import com.crystalgraphics.render.post.CgPostMaterial;
import com.crystalgraphics.render.post.CgPostPoint;
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.render.stage.CgRenderStage;

/**
 * Unreal's exponential height fog ({@code UExponentialHeightFogComponent}): fog thickest at and below a height, thinning
 * above it, drawn over the lit opaque scene before translucency as Unreal draws it. One layer: no directional
 * inscattering, no cubemap, no volumetric fog. Everything is in the host's blocks.
 *
 * <pre>{@code
 * CgHeightFog fog = new CgHeightFog().density(0.2f).heightFalloff(10f).height(64).colour(0.41f, 0.89f, 1f)
 *         .maxOpacity(0.65f).startDistance(300f);
 * CgRenderStage.Registration on = fog.show();
 * on.close();
 * }</pre>
 *
 * <pre>{@code
 * // From an Unreal component, written in centimetres: Unreal divides its density and falloff by 1000
 * new CgHeightFog().density(FogDensity / 1000f * 100f).heightFalloff(FogHeightFalloff / 1000f * 100f).height(z / 100.0);
 * }</pre>
 *
 * <ul>
 *   <li>{@code density} and {@code heightFalloff} are per block: the component's values over 1000, times the units in a
 *       block. A stage's {@code fog} block is converted for you ({@code CgStage}).</li>
 *   <li>Without the HDR scene it draws after the world onto the host's target, decoding and encoding it.</li>
 *   <li>Our transparent draws come after it, so they are not fogged; Unreal fogs translucency in its own shaders.</li>
 * </ul>
 */
public final class CgHeightFog implements CgPostEffect {

    public static final String SHADER = "crystalgraphics:shaders/post/height_fog.shader";
    /** How far over the fog's height the observer is held (FogRendering.cpp's MaxObserverHeightDifference, 65536 cm). */
    private static final double MAX_OBSERVER_ABOVE = 655.36;

    // Unreal's defaults (FogDensity 0.02, FogHeightFalloff 0.2) at a metre a block
    private float density = 0.002f, falloff = 0.02f, red = 0.447f, green = 0.638f, blue = 1f;
    private float maxOpacity = 1f, start, cutoff, weight = 1f;
    private double height;

    private final CgMaterial material = CgMaterial.newInstance(SHADER);
    private final CgPostMaterial draw = CgPostMaterial.of(material, CgPostPoint.BEFORE_TRANSLUCENCY, 0);
    private final float[] fog0 = new float[4], fog1 = new float[4];
    private final Consumer<CgMaterialValues> write = this::write;

    /** Its density at {@link #height}, per block. */
    public CgHeightFog density(float perBlock) {
        density = Math.max(0f, perBlock);
        return this;
    }

    /** How fast it thins with height, per block: the density halves every {@code 1 / falloff} blocks up. */
    public CgHeightFog heightFalloff(float perBlock) {
        falloff = Math.max(0.001f, perBlock);
        return this;
    }

    /** The world height its density is given at, absolute. */
    public CgHeightFog height(double y) {
        height = y;
        return this;
    }

    /** Its inscattering colour, linear. */
    public CgHeightFog colour(float r, float g, float b) {
        red = r;
        green = g;
        blue = b;
        return this;
    }

    /** The most of the scene it hides, 0 to 1. */
    public CgHeightFog maxOpacity(float opacity) {
        maxOpacity = Math.max(0f, Math.min(1f, opacity));
        return this;
    }

    /** No fog nearer the eye than this, in blocks. */
    public CgHeightFog startDistance(float blocks) {
        start = Math.max(0f, blocks);
        return this;
    }

    /** No fog on what is farther than this, in blocks; 0 for none. */
    public CgHeightFog cutoffDistance(float blocks) {
        cutoff = Math.max(0f, blocks);
        return this;
    }

    /** How much of it shows, 0 to 1: a stage fading in or out. */
    public CgHeightFog weight(float weight) {
        this.weight = Math.max(0f, Math.min(1f, weight));
        return this;
    }

    /** Shows it on the post stack until the registration closes. */
    public CgRenderStage.Registration show() {
        return CgPostStack.get().add(this);
    }

    @Override
    public CgPostPoint point() {
        return CgPostPoint.BEFORE_TRANSLUCENCY;
    }

    @Override
    public boolean active(CgPostContext post) {
        return weight > 0f && density > 0f && draw.active(post);
    }

    @Override
    public void record(CgPostContext post) {
        // FogRendering.cpp: the observer held below the fog's height plus 65536 cm, and the density at it collapsed
        double eye = post.stage().host().camera().y();
        double observer = Math.min(eye, height + MAX_OBSERVER_ABOVE);
        double power = Math.max(-125.0, Math.min(126.0, -falloff * (observer - height)));
        fog0[0] = (float) (density * Math.pow(2.0, power));
        fog0[1] = falloff;
        fog0[2] = (float) (eye - observer);
        fog0[3] = start;
        fog1[0] = density;
        fog1[1] = (float) (observer - height);
        fog1[2] = cutoff;
        fog1[3] = 1f - maxOpacity;
        draw.weight(weight);
        material.applyProperties(write);
        draw.record(post);
    }

    private void write(CgMaterialValues b) {
        b.vec4("_Fog0", fog0[0], fog0[1], fog0[2], fog0[3]);
        b.vec4("_Fog1", fog1[0], fog1[1], fog1[2], fog1[3]);
        b.vec4("_FogColour", red, green, blue, 1f);
    }
}
