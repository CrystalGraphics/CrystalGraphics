package com.crystalgraphics.render.scene.component;

import com.crystalgraphics.render.scene.renderer.feature.light.CgLightCollector;

/**
 * A light shining every way from a point: Unreal's {@code UPointLightComponent}.
 *
 * <pre>{@code
 * CgPointLight torch = scene.pointLight().at(x, y + 1.5, z).radius(6f).color(0xFFFF9030).intensity(40f).spawn();
 * CgPointLight lamp = scene.pointLight().attach(body, "hand_r").radius(4f).intensity(10f).spawn();   // rides a socket
 * }</pre>
 */
public final class CgPointLight extends CgLightComponent<CgPointLight> {

    CgPointLight(CgScenePrimitives scene) {
        super(scene);
    }

    @Override
    void shape(CgLightCollector.Light light, float fx, float fy, float fz) {
    }

    @Override
    public String toString() {
        return "CgPointLight";
    }
}
