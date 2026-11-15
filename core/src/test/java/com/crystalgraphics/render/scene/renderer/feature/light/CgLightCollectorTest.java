package com.crystalgraphics.render.scene.renderer.feature.light;

import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgSpotLight;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CgLightCollectorTest {

    private static final float EPS = 1e-4f;

    @Test
    public void aSpawnedSpot_isWrittenWhereItStands_pointingAlongItsTurn_untilHiddenOrRemoved() {
        CgScene scene = new CgScene();
        CgSpotLight lamp = scene.spotLight().at(1, 6, -2).direction(0f, -1f, 0f).cone(20f, 35f).radius(12f)
                .intensity(3f).castsShadow(false).spawn();
        CgLightCollector out = frame(scene);
        assertEquals(1, out.count());
        assertEquals(1.0, out.positions()[0], EPS);
        assertEquals(6.0, out.positions()[1], EPS);
        assertEquals(-2.0, out.positions()[2], EPS);
        float[] row = out.rows();
        assertEquals(12f, row[3], EPS);
        assertEquals("white times its intensity, linear", 3f, row[4], EPS);
        assertEquals("its +x turned down", -1f, row[9], EPS);
        assertEquals((float) Math.cos(Math.toRadians(35)), row[11], EPS);
        assertEquals("unshadowed", 0f, row[19], EPS);

        lamp.visible(false);
        assertEquals(0, frame(scene).count());
        lamp.visible(true);
        assertEquals(1, frame(scene).count());
        lamp.remove();
        assertEquals(0, frame(scene).count());
    }

    @Test
    public void aTubeBetweenTwoPoints_standsAtTheirMiddle_asLongAsTheyAreApart() {
        CgScene scene = new CgScene();
        scene.tubeLight().between(0, 0, 0, 4, 0, 0).radius(8f).spawn();
        CgLightCollector out = frame(scene);
        assertEquals(1, out.count());
        assertEquals(2.0, out.positions()[0], EPS);
        float[] row = out.rows();
        assertEquals("its line along +x, 4 long", 4f, row[12], EPS);
        assertEquals(0f, row[13], EPS);
        assertEquals("no cone", -2f, row[11], EPS);
    }

    /** A new ring frame's lights from {@code scene}'s components. */
    private static CgLightCollector frame(CgScene scene) {
        CgFrameRing.endFrame();
        CgLightCollector out = new CgLightCollector();
        out.open();
        scene.primitives().writeLights(out);
        return out;
    }
}
