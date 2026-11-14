package com.crystalgraphics.render.scene.component;

import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.shape.bounds.CgAABB;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * A frame's CPU for 2,000 still props kept in a scene (their records settled, the view's gather, each culled by its
 * kept world box) against the same props each a dynamic primitive giving its box every frame, culled and collected as
 * a per-frame submit is. Prints both; times are not asserted.
 */
public class CgSceneCostTest {

    private static final int PROPS = 2000, WARM = 2000, FRAMES = 2000;

    @Test
    public void stillPropsInTheScene_againstTheSamePropsCollectedEachFrame() {
        CgView view = CgView.main();
        // Above the props' rows and behind them, looking along them: every prop in view, so none is culled
        CgCamera camera = CgCamera.perspective(70f, 1f, 0.05f, 1000f).position(25, 110, 70).lookAt(25, 110, 70, 25, 64, -20);
        CgMeshBatches out = new CgMeshBatches();

        CgScene still = new CgScene();
        for (int i = 0; i < PROPS; i++) {
            still.mesh(CgSceneTest.CUBE, CgSceneTest.STONE).at(i % 50, 64, -(i / 50)).customData(0, i, 0, 0, 1).spawn();
        }
        CgScene each = new CgScene();
        for (int i = 0; i < PROPS; i++) each.dynamic(new Submitted(i % 50, 64, -(i / 50), i)).spawn();

        double stillNs = run(still, view, camera, out), eachNs = run(each, view, camera, out);
        assertEquals(PROPS, out.size());
        System.out.printf("[scene cost] %d props a frame, each culled: kept %.1f us, submitted each frame %.1f us (%.2fx)%n",
                PROPS, stillNs / 1e3, eachNs / 1e3, stillNs / eachNs);
    }

    private static double run(CgScene scene, CgView view, CgCamera camera, CgMeshBatches out) {
        for (int f = 0; f < WARM; f++) frame(scene, view, camera, out);
        long t = System.nanoTime();
        for (int f = 0; f < FRAMES; f++) frame(scene, view, camera, out);
        return (System.nanoTime() - t) / (double) FRAMES;
    }

    /** One prop as a per-frame submit is: its box made and culled, then its row collected, every frame. */
    private static final class Submitted implements CgDynamicPrimitive {
        private final double x, y, z;
        private final float id;

        Submitted(double x, double y, double z, float id) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.id = id;
        }

        @Override
        public boolean bounds(CgAABB out) {
            out.set(x - 0.5, y - 0.5, z - 0.5, x + 0.5, y + 0.5, z + 0.5);
            return true;
        }

        @Override
        public void collect(CgView view, CgMeshCollector out) {
            out.mesh(CgSceneTest.CUBE, CgSceneTest.STONE).at(x, y, z).customData(0, id, 0, 0, 1).add();
        }
    }

    private static void frame(CgScene scene, CgView view, CgCamera camera, CgMeshBatches out) {
        scene.gpu().settle();
        scene.primitives().gather(view, camera, out);
    }
}
