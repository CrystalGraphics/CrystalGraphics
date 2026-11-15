package com.crystalgraphics.render.scene.component;

import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.shape.bounds.CgAABB;

/**
 * Fog thickest at and below the height it stands at, thinning above it: Unreal's {@code UExponentialHeightFogComponent},
 * drawn over the lit opaque scene before translucency by every view of its scene. One layer: no directional
 * inscattering, no cubemap. Everything is in blocks.
 *
 * <pre>{@code
 * CgHeightFogComponent fog = scene.heightFog().at(0, 64, 0).density(0.2f).heightFalloff(10f)
 *         .colour(0.41f, 0.89f, 1f).maxOpacity(0.65f).startDistance(300f).spawn();
 * fog.weight(0.5f);                                    // half shown: a stage fading it in
 * fog.remove();
 * }</pre>
 *
 * <pre>{@code
 * // From an Unreal component, written in centimetres: Unreal divides its density and falloff by 1000
 * scene.heightFog().at(0, z / 100.0, 0).density(FogDensity / 1000f * 100f).heightFalloff(FogHeightFalloff / 1000f * 100f);
 * }</pre>
 *
 * <ul>
 *   <li>Only its height counts: it covers the whole scene, wherever across it stands.</li>
 *   <li>{@code density} and {@code heightFalloff} are per block: the component's values over 1000, times the units in a
 *       block. A stage's {@code fog} block is converted for you ({@code CgStage}).</li>
 *   <li>A scene draws one: the first spawned that shows. Our transparent draws come after it, so they are not fogged;
 *       Unreal fogs translucency in its own shaders.</li>
 * </ul>
 */
public final class CgHeightFogComponent extends CgPrimitiveComponent<CgHeightFogComponent> {

    // Unreal's defaults (FogDensity 0.02, FogHeightFalloff 0.2) at a metre a block
    private float density = 0.002f, falloff = 0.02f, red = 0.447f, green = 0.638f, blue = 1f;
    private float maxOpacity = 1f, start, cutoff, weight = 1f;

    CgHeightFogComponent(CgScenePrimitives scene) {
        super(scene);
    }

    /** Its density at the height it stands at, per block. */
    public CgHeightFogComponent density(float perBlock) {
        density = Math.max(0f, perBlock);
        return this;
    }

    public float density() {
        return density;
    }

    /** How fast it thins with height, per block: the density halves every {@code 1 / falloff} blocks up. */
    public CgHeightFogComponent heightFalloff(float perBlock) {
        falloff = Math.max(0.001f, perBlock);
        return this;
    }

    public float heightFalloff() {
        return falloff;
    }

    /** Its inscattering colour, linear. */
    public CgHeightFogComponent colour(float r, float g, float b) {
        red = r;
        green = g;
        blue = b;
        return this;
    }

    public float red() {
        return red;
    }

    public float green() {
        return green;
    }

    public float blue() {
        return blue;
    }

    /** The most of the scene it hides, 0 to 1. */
    public CgHeightFogComponent maxOpacity(float opacity) {
        maxOpacity = Math.max(0f, Math.min(1f, opacity));
        return this;
    }

    public float maxOpacity() {
        return maxOpacity;
    }

    /** No fog nearer the eye than this, in blocks. */
    public CgHeightFogComponent startDistance(float blocks) {
        start = Math.max(0f, blocks);
        return this;
    }

    public float startDistance() {
        return start;
    }

    /** No fog on what is farther than this, in blocks; 0 for none. */
    public CgHeightFogComponent cutoffDistance(float blocks) {
        cutoff = Math.max(0f, blocks);
        return this;
    }

    public float cutoffDistance() {
        return cutoff;
    }

    /** How much of it shows, 0 to 1: a stage fading in or out. */
    public CgHeightFogComponent weight(float weight) {
        this.weight = Math.max(0f, Math.min(1f, weight));
        return this;
    }

    public float weight() {
        return weight;
    }

    /** The world height its density is given at, absolute: where it stands. */
    public double height() {
        return worldTransform().m31();
    }

    // ── Engine side ───────────────────────────────────────────────────────────────────────────────

    /** Whether it shows: visible, weighed and thick. */
    boolean shows() {
        return visible && weight > 0f && density > 0f;
    }

    @Override
    void onSpawn() {
        scene.fogs.add(this);
    }

    @Override
    void onRemove() {
        scene.fogs.remove(this);
    }

    @Override
    void onMoved() {
    }

    @Override
    void onLooked() {
    }

    @Override
    boolean kindBounds(CgAABB out) {
        return false;
    }

    @Override
    void gather(CgView view, CgCamera camera, CgMeshBatches out, CgSceneGather gather) {
    }

    @Override
    public String toString() {
        return "CgHeightFogComponent";
    }
}
