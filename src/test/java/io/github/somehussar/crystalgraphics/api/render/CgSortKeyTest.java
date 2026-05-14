package io.github.somehussar.crystalgraphics.api.render;

import io.github.somehussar.crystalgraphics.api.material.CgRenderQueue;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CgSortKey}.
 *
 * Tests that sort keys encode ordering correctly: opaque before transparent,
 * front-to-back within opaque, back-to-front within transparent, queue bucket ordering.
 */
public class CgSortKeyTest {

    private static final float FAR = 1000f;

    @Test
    public void opaqueKey_geometryBeforeAlphaTest() {
        long geoKey = CgSortKey.buildOpaqueKey(CgRenderQueue.GEOMETRY,   0, 0, 50f, FAR);
        long atKey  = CgSortKey.buildOpaqueKey(CgRenderQueue.ALPHA_TEST, 0, 0, 50f, FAR);
        // GEOMETRY slot (0) sorts before ALPHA_TEST slot (1)
        assertTrue("GEOMETRY key must be less than ALPHA_TEST key at same depth",
                   geoKey < atKey);
    }

    @Test
    public void opaqueKey_backgroundBeforeGeometry() {
        long bgKey  = CgSortKey.buildOpaqueKey(CgRenderQueue.BACKGROUND, 0, 0, 100f, FAR);
        long geoKey = CgSortKey.buildOpaqueKey(CgRenderQueue.GEOMETRY,   0, 0, 100f, FAR);
        // BACKGROUND and GEOMETRY both map to slot 0 — keys must be equal
        assertEquals("BACKGROUND and GEOMETRY map to same slot 0", bgKey, geoKey);
    }

    @Test
    public void opaqueKey_closerDepthSmallerKey_frontToBack() {
        long near = CgSortKey.buildOpaqueKey(CgRenderQueue.GEOMETRY, 0, 0, 10f,  FAR);
        long far  = CgSortKey.buildOpaqueKey(CgRenderQueue.GEOMETRY, 0, 0, 500f, FAR);
        // Smaller cameraDepth → smaller key → sorted first = front-to-back
        assertTrue("Closer opaque object must have smaller sort key", near < far);
    }

    @Test
    public void transparentKey_fartherDepthSmallerKey_backToFront() {
        long farT  = CgSortKey.buildTransparentKey(CgRenderQueue.TRANSPARENT, 0, 500f, FAR);
        long nearT = CgSortKey.buildTransparentKey(CgRenderQueue.TRANSPARENT, 0, 10f,  FAR);
        // Larger cameraDepth → smaller key → sorted first = back-to-front
        assertTrue("Farther transparent object must have smaller sort key (back-to-front)",
                   farT < nearT);
    }

    @Test
    public void opaqueAlwaysSortsBefore_transparent() {
        long opaque = CgSortKey.buildOpaqueKey(CgRenderQueue.GEOMETRY, 0, 0, 999f, FAR);
        long trans  = CgSortKey.buildTransparentKey(CgRenderQueue.TRANSPARENT, 0, 0.1f, FAR);
        // Opaque slot (0) must always sort before transparent slot (2)
        assertTrue("Opaque key must sort before transparent key", opaque < trans);
    }

    @Test
    public void priorityTiebreaks_withinSameQueue() {
        long prio0 = CgSortKey.buildOpaqueKey(CgRenderQueue.GEOMETRY, 0, 0, 50f, FAR);
        long prio1 = CgSortKey.buildOpaqueKey(CgRenderQueue.GEOMETRY, 1, 0, 50f, FAR);
        // Different priority values must produce different keys
        assertNotEquals("Priority 0 and priority 1 must produce different keys", prio0, prio1);
        // Lower priority renders earlier (smaller key)
        assertTrue("Lower priority must produce smaller sort key", prio0 < prio1);
    }
}
