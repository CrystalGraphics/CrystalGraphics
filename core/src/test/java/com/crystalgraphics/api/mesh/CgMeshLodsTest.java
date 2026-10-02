package com.crystalgraphics.api.mesh;

import org.junit.Test;

import static org.junit.Assert.*;

/** Mesh rewrite M6: a level per screen height, culled below the last. */
public class CgMeshLodsTest {

    @Test
    public void aLevelHoldsDownToItsScreenHeightAndBelowTheLastNothingDraws() {
        CgMesh high = CgMeshShapes.sphere(16, 32), low = CgMeshShapes.sphere(4, 8);
        CgMeshLods lods = CgMeshLods.builder().level(high, 0.2f).level(low, 0.01f).build();
        assertSame(high, lods.pick(5f));
        assertSame(high, lods.pick(0.2f));
        assertSame(low, lods.pick(0.19f));
        assertSame(low, lods.pick(0.01f));
        assertNull(lods.pick(0.009f));
        assertSame(high, lods.finest());
    }

    @Test
    public void screenHeightsMustDescend() {
        CgMeshLods.Builder lods = CgMeshLods.builder().level(CgMeshShapes.cube(), 0.1f);
        assertThrows(IllegalArgumentException.class, () -> lods.level(CgMeshShapes.cube(), 0.1f));
        assertThrows(IllegalStateException.class, () -> CgMeshLods.builder().build());
    }

    /** Each level holds until the next coarser one's silhouette strays half a pixel at 1080 lines; the last never culls. */
    @Test
    public void sphereLevelsHalveTheirSectorsAndNeverCull() {
        CgMeshLods lods = CgMeshShapes.sphereLods();
        assertSame(lods, CgMeshShapes.sphereLods());
        assertEquals(5, lods.levelCount());
        for (int i = 1; i < lods.levelCount(); i++) {
            assertTrue(lods.level(i).vertexCount() < lods.level(i - 1).vertexCount() / 3);
            assertTrue(lods.screenHeight(i) < lods.screenHeight(i - 1));
        }
        assertEquals(0.769f, lods.screenHeight(0), 0.001f);   // 64 sectors stray half a pixel above this
        assertEquals(0f, lods.screenHeight(4), 0f);
        assertNotNull(lods.pick(0f));
    }
}
