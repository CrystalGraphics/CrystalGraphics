package com.crystalgraphics.render.scene.component;

import com.crystalgraphics.render.scene.renderer.feature.light.CgLightCollector;
import org.joml.Quaternionf;

/**
 * A light coming from a line along its +x, centred on where it stands: Unreal's point light with a
 * {@code SourceLength}, the light of a beam, a neon bar or a blade.
 *
 * <pre>{@code
 * CgTubeLight beam = scene.tubeLight().between(ax, ay, az, bx, by, bz).radius(24f).color(0xFF60A0FF).intensity(40f).spawn();
 * beam.between(ax, ay, az, nx, ny, nz);                        // its ends moved: placed, turned and sized again
 * }</pre>
 *
 * <ul>
 *   <li>Its radius is still from its centre, so it reaches past its ends by less than it does at its middle.</li>
 * </ul>
 */
public final class CgTubeLight extends CgLightComponent<CgTubeLight> {

    private float length;
    private final Quaternionf turn = new Quaternionf();

    CgTubeLight(CgScenePrimitives scene) {
        super(scene);
    }

    /** The line it comes from, {@code blocks} long along its +x. */
    public CgTubeLight length(float blocks) {
        length = Math.max(blocks, 0f);
        return this;
    }

    public float length() {
        return length;
    }

    /** From absolute {@code a} to {@code b}: placed at their middle, turned along them, as long as they are apart. */
    public CgTubeLight between(double ax, double ay, double az, double bx, double by, double bz) {
        float dx = (float) (bx - ax), dy = (float) (by - ay), dz = (float) (bz - az);
        float n = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        at((ax + bx) * 0.5, (ay + by) * 0.5, (az + bz) * 0.5);
        if (n > 0f) rotation(turn.rotationTo(1f, 0f, 0f, dx / n, dy / n, dz / n));
        return length(n);
    }

    @Override
    void shape(CgLightCollector.Light light, float fx, float fy, float fz) {
        if (length > 0f) light.tube(fx, fy, fz, length);
    }

    @Override
    public String toString() {
        return "CgTubeLight";
    }
}
