package com.crystalgraphics.render.scene.component;

import com.crystalgraphics.render.scene.CgScene;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class CgSceneAirTest {

    @Test
    public void aScene_drawsItsFirstShownHeightFog_atTheHeightItStands() {
        CgScene scene = new CgScene();
        assertNull(scene.primitives().shownFog());
        CgHeightFogComponent low = scene.heightFog().at(5, 62, -3).density(0.1f).spawn();
        CgHeightFogComponent high = scene.heightFog().at(0, 90, 0).density(0.1f).spawn();
        assertSame(low, scene.primitives().shownFog());
        assertEquals(62.0, low.height(), 1e-9);
        low.weight(0f);
        assertSame("a fog weighed to nothing shows none", high, scene.primitives().shownFog());
        high.visible(false);
        assertNull(scene.primitives().shownFog());
        low.weight(1f);
        low.remove();
        assertNull(scene.primitives().shownFog());
    }

    @Test
    public void aSpawnedHaze_holdsTheAir_untilThinnedHiddenOrRemoved() {
        CgScene scene = new CgScene();
        CgHazeComponent haze = scene.haze().box(-4, 0, -4, 4, 6, 4).density(0.03f, 2f);
        assertFalse("unspawned, it is not in the scene", scene.primitives().holdsHaze());
        haze.spawn();
        assertTrue(scene.primitives().holdsHaze());
        assertTrue(scene.renderer().media().holdsAny());
        haze.density(0f, 2f);
        assertFalse(scene.primitives().holdsHaze());
        assertFalse("an empty air records nothing", scene.renderer().media().holdsAny());
        haze.density(0.03f, 2f).visible(false);
        assertFalse(scene.primitives().holdsHaze());
        haze.visible(true);
        haze.remove();
        assertFalse(scene.primitives().holdsHaze());
    }
}
