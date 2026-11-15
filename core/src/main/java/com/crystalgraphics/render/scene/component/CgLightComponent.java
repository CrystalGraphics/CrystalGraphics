package com.crystalgraphics.render.scene.component;

import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.render.scene.renderer.feature.light.CgLightCollector;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.shape.bounds.CgAABB;
import org.joml.Matrix4dc;

/**
 * A light standing in a scene, kept until removed: Unreal's {@code ULocalLightComponent}. The kinds are
 * {@link CgPointLight}, {@link CgSpotLight} and {@link CgTubeLight}; what every light has is here.
 *
 * <pre>{@code
 * CgPointLight torch = scene.pointLight().at(x, y + 1.5, z).radius(6f).color(0xFFFF9030).intensity(40f).spawn();
 * torch.intensity(flicker);                                    // changed when it changes, drawn as it stands
 * torch.castsShadow(false).sourceRadius(0.1f).volumetric(0.5f);
 * torch.remove();
 * }</pre>
 *
 * <ul>
 *   <li>Its colour is sRGB, as authored colours are; its intensity multiplies it.</li>
 *   <li>Unreal's defaults: white, a radius of 10 blocks (its 1000 cm), inverse-squared, casting shadows, lighting the
 *       air at 1.</li>
 *   <li>Hidden ({@code visible(false)}), it lights nothing. Render thread, as its scene.</li>
 * </ul>
 */
public abstract class CgLightComponent<T extends CgLightComponent<T>> extends CgPrimitiveComponent<T> {

    private int argb = 0xFFFFFFFF;
    private float intensity = 1f, radius = 10f, exponent, sourceRadius, volumetric = 1f;
    private boolean castsShadow = true, sampled = true;

    CgLightComponent(CgScenePrimitives scene) {
        super(scene);
    }

    @SuppressWarnings("unchecked")
    private T self() {
        return (T) this;
    }

    /** Its colour, sRGB {@code 0xAARRGGBB}; alpha scales it. */
    public T color(int argb) {
        this.argb = argb;
        return self();
    }

    public int color() {
        return argb;
    }

    /** What its colour is multiplied by. */
    public T intensity(float intensity) {
        this.intensity = Math.max(intensity, 0f);
        return self();
    }

    public float intensity() {
        return intensity;
    }

    /** The blocks it reaches: Unreal's {@code AttenuationRadius}. */
    public T radius(float blocks) {
        radius = Math.max(blocks, 0f);
        return self();
    }

    public float radius() {
        return radius;
    }

    /** Unreal's {@code LightFalloffExponent}; 0, the default, is inverse-squared ({@code bUseInverseSquaredFalloff}). */
    public T exponent(float exponent) {
        this.exponent = Math.max(exponent, 0f);
        return self();
    }

    /** Its source's radius, Unreal's {@code SourceRadius}: one touching surfaces gives one, or it only grazes them. */
    public T sourceRadius(float blocks) {
        sourceRadius = Math.max(blocks, 0f);
        return self();
    }

    /** Whether blocks, entities and our meshes withhold it: Unreal's {@code bCastShadows}. */
    public T castsShadow(boolean on) {
        castsShadow = on;
        return self();
    }

    /** How strongly it lights the air: Unreal's {@code VolumetricScatteringIntensity}, 0 for none. */
    public T volumetric(float intensity) {
        volumetric = Math.max(intensity, 0f);
        return self();
    }

    /** Whether MegaLights samples it rather than shading it exactly: on unless told. */
    public T sampled(boolean on) {
        sampled = on;
        return self();
    }

    // ── Engine side ───────────────────────────────────────────────────────────────────────────────

    /** Writes it into this frame's lights where it stands, its kind's shape over what every light has. */
    final void write(CgLightCollector out) {
        if (!visible || radius <= 0f || intensity <= 0f) return;
        double[] m = place();
        CgLightCollector.Light l = out.point(m[0], m[1], m[2], radius, argb, intensity).exponent(exponent)
                .source(sourceRadius).castsShadow(castsShadow).volumetric(volumetric).sampled(sampled);
        shape(l, (float) m[3], (float) m[4], (float) m[5]);
    }

    /** Its kind's shape on the light written, {@code (fx, fy, fz)} its world +x, where a spot points and a tube runs. */
    abstract void shape(CgLightCollector.Light light, float fx, float fy, float fz);

    private final double[] placed = new double[6];

    /** Its world position, then its +x in the world. */
    private double[] place() {
        Matrix4dc m = worldTransform();
        placed[0] = m.m30();
        placed[1] = m.m31();
        placed[2] = m.m32();
        double fx = m.m00(), fy = m.m01(), fz = m.m02(), n = Math.sqrt(fx * fx + fy * fy + fz * fz);
        placed[3] = n > 0 ? fx / n : 1.0;
        placed[4] = n > 0 ? fy / n : 0.0;
        placed[5] = n > 0 ? fz / n : 0.0;
        return placed;
    }

    @Override
    void onSpawn() {
        scene.lights.add(this);
    }

    @Override
    void onRemove() {
        scene.lights.remove(this);
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
}
