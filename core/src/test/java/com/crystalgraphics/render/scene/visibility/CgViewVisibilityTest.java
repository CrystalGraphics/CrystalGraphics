package com.crystalgraphics.render.scene.visibility;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.render.scene.component.CgDynamicPrimitive;
import com.crystalgraphics.render.scene.component.CgMeshCollector;
import com.crystalgraphics.render.scene.component.CgMeshComponent;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.shape.bounds.CgAABB;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class CgViewVisibilityTest {

    private static final CgMesh CUBE = CgMeshShapes.cube();
    private static final CgMaterialInterface STONE = material(1, CgRenderQueue.GEOMETRY),
            GLASS = material(2, CgRenderQueue.TRANSPARENT);

    private final CgView view = CgView.create("visibility-test");
    private final CgCamera camera = CgCamera.perspective(70f, 1f, 0.05f, 1000f).position(0, 0, 0).lookAt(0, 0, 0, 0, 0, -1);

    @Test
    public void whatIsOutOfTheFrustumIsCulled_andAnUnboundedPrimitiveStays() {
        CgScene scene = new CgScene();
        CgMeshComponent ahead = scene.mesh(CUBE, STONE).at(0, 0, -5).spawn();
        scene.mesh(CUBE, STONE).at(0, 0, 5).spawn();      // behind the eye
        scene.mesh(CUBE, STONE).at(50, 0, -5).spawn();    // far to the side
        scene.dynamic((v, o) -> o.mesh(CUBE, STONE).add()).at(0, 0, 50).spawn();   // no bounds: never culled

        CgViewVisibility seen = CgViewVisibility.of(view, scene, camera);
        assertEquals(4, seen.tested());
        assertEquals(2, seen.culled());
        CgMeshBatches rows = seen.batches();
        assertEquals(2, rows.size());
        assertEquals(ahead.objectId(), rows.objectId(0));
        assertEquals(-1, rows.objectId(1));
    }

    @Test
    public void aDynamicPrimitiveIsAskedOnlyOnceInView() {
        CgScene scene = new CgScene();
        int[] asked = {0};
        double[] z = {10};
        scene.dynamic(new CgDynamicPrimitive() {
            public void collect(CgView v, CgMeshCollector out) {
                asked[0]++;
                out.mesh(CUBE, GLASS).at(0, 0, z[0]).add();
            }

            public boolean bounds(CgAABB out) {
                out.set(-1, -1, z[0] - 1, 1, 1, z[0] + 1);
                return true;
            }
        }).spawn();

        assertEquals(0, CgViewVisibility.of(view, scene, camera).batches().size());
        assertEquals(0, asked[0]);
        z[0] = -10;
        CgFrameRing.endFrame();
        assertEquals(1, CgViewVisibility.of(view, scene, camera).batches().size());
        assertEquals(1, asked[0]);
    }

    @Test
    public void opaqueKeysRunNearToFar_transparentFarToNear_andPriorityOrdersWithinALayer() {
        CgScene scene = new CgScene();
        scene.mesh(CUBE, STONE).at(0, 0, -20).spawn();
        scene.mesh(CUBE, STONE).at(0, 0, -5).spawn();
        scene.mesh(CUBE, GLASS).at(0, 0, -20).spawn();
        scene.mesh(CUBE, GLASS).at(0, 0, -5).spawn();
        scene.mesh(CUBE, GLASS).at(0, 0, -5).sortPriority(3).spawn();
        CgMeshBatches rows = CgViewVisibility.of(view, scene, camera).batches();
        assertEquals(5, rows.size());
        assertEquals(20f, rows.distance(0), 0.6f);
        assertTrue("opaque: nearer first", rows.key(1) < rows.key(0));
        assertTrue("transparent: farther first", rows.key(2) < rows.key(3));
        assertTrue("a higher priority after, in the same layer", rows.key(3) < rows.key(4));
        assertTrue("every opaque before any transparent", rows.key(0) < rows.key(2));
    }

    @Test
    public void madeOnceAFrame_whateverAsks_andAgainTheNext() {
        CgScene scene = new CgScene();
        scene.mesh(CUBE, STONE).at(0, 0, -5).spawn();
        CgViewVisibility first = CgViewVisibility.of(view, scene, camera);
        scene.mesh(CUBE, STONE).at(0, 1, -5).spawn();   // after this frame's visibility: seen next frame
        CgViewVisibility again = CgViewVisibility.of(view, scene, camera);
        assertSame(first, again);
        assertEquals(1, again.computations());
        assertEquals(1, again.batches().size());

        CgFrameRing.endFrame();
        CgViewVisibility next = CgViewVisibility.of(view, scene, camera);
        assertSame(first, next);
        assertEquals(2, next.computations());
        assertEquals(2, next.batches().size());
        assertEquals(CgFrameRing.frame(), next.frame());

        CgView other = CgView.create("visibility-test.other");
        assertEquals(1, CgViewVisibility.of(other, scene, camera).computations());   // a view of its own
    }

    private static CgMaterialInterface material(int id, int queue) {
        return new CgMaterialInterface() {
            public CgMaterial material() {
                return null;
            }

            public int changes() {
                return 0;
            }

            public int getMaterialId() {
                return id;
            }

            public boolean precache() {
                return true;
            }

            public int renderQueue() {
                return queue;
            }

            public boolean writesDepth() {
                return false;
            }
        };
    }
}
