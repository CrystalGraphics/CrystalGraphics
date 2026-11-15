package com.crystalgraphics.render.scene.component;

import com.crystalgraphics.render.scene.renderer.feature.light.CgLightCollector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A light limited to a cone along its +x: Unreal's {@code USpotLightComponent}, full inside its inner angle and gone past
 * its outer one.
 *
 * <pre>{@code
 * CgSpotLight lamp = scene.spotLight().at(x, y + 6, z).direction(0, -1, 0).cone(20f, 35f).radius(12f).intensity(300f).spawn();
 * lamp.rotation(turn);                                          // or turned: it points along its +x
 * }</pre>
 *
 * <ul>
 *   <li>Unreal's defaults: an inner angle of 0 and an outer one of 44 degrees.</li>
 *   <li>{@code direction} sets its turn; a turn set after it, or a socket it rides, points it instead.</li>
 * </ul>
 */
public final class CgSpotLight extends CgLightComponent<CgSpotLight> {

    private float inner, outer = 44f;
    private final Quaternionf turn = new Quaternionf();
    private final Vector3f along = new Vector3f();

    CgSpotLight(CgScenePrimitives scene) {
        super(scene);
    }

    /** Points it along {@code (dx, dy, dz)}: a turn taking its +x there. */
    public CgSpotLight direction(float dx, float dy, float dz) {
        if (dx == 0f && dy == 0f && dz == 0f) throw new IllegalArgumentException("a spot points somewhere: (0, 0, 0)");
        along.set(dx, dy, dz).normalize();
        return rotation(turn.rotationTo(1f, 0f, 0f, along.x, along.y, along.z));
    }

    /** Full inside {@code innerDegrees} of its axis, none past {@code outerDegrees}: Unreal's cone angles. */
    public CgSpotLight cone(float innerDegrees, float outerDegrees) {
        inner = Math.max(innerDegrees, 0f);
        outer = Math.max(outerDegrees, inner);
        return this;
    }

    public float inner() {
        return inner;
    }

    public float outer() {
        return outer;
    }

    @Override
    void shape(CgLightCollector.Light light, float fx, float fy, float fz) {
        light.spot(fx, fy, fz, inner, outer);
    }

    @Override
    public String toString() {
        return "CgSpotLight";
    }
}
