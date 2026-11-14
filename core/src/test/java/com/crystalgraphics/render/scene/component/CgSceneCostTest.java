package com.crystalgraphics.render.scene.component;

import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.view.CgView;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * A frame's CPU for 2,000 still props kept in a scene (their records settled, the view's gather)
 * against the same props collected every frame as a dynamic primitive. Prints both; times are not asserted.
 */
public class CgSceneCostTest {

    private static final int PROPS = 2000, WARM = 2000, FRAMES = 2000;

    @Test
    public void stillPropsInTheScene_againstTheSamePropsCollectedEachFrame() {
        CgView view = CgView.main();
        CgCamera camera = CgCamera.perspective(70f, 1f, 0.05f, 1000f).lookAt(0, 0, 0, 0, 0, -1);
        CgMeshBatches out = new CgMeshBatches();

        CgScene still = new CgScene();
        for (int i = 0; i < PROPS; i++) {
            still.mesh(CgSceneTest.CUBE, CgSceneTest.STONE).at(i % 50, 64, -(i / 50)).customData(0, i, 0, 0, 1).spawn();
        }
        CgScene each = new CgScene();
        each.dynamic((v, o) -> {
            for (int i = 0; i < PROPS; i++) {
                o.mesh(CgSceneTest.CUBE, CgSceneTest.STONE).at(i % 50, 64, -(i / 50)).customData(0, i, 0, 0, 1).add();
            }
        }).spawn();

        double stillNs = run(still, view, camera, out), eachNs = run(each, view, camera, out);
        assertEquals(PROPS, out.size());
        System.out.printf("[scene cost] %d props a frame: kept %.1f us, collected each frame %.1f us (%.2fx)%n",
                PROPS, stillNs / 1e3, eachNs / 1e3, stillNs / eachNs);
    }

    private static double run(CgScene scene, CgView view, CgCamera camera, CgMeshBatches out) {
        for (int f = 0; f < WARM; f++) frame(scene, view, camera, out);
        long t = System.nanoTime();
        for (int f = 0; f < FRAMES; f++) frame(scene, view, camera, out);
        return (System.nanoTime() - t) / (double) FRAMES;
    }

    private static void frame(CgScene scene, CgView view, CgCamera camera, CgMeshBatches out) {
        scene.gpu().settle();
        scene.gather(view, camera, out);
    }
}
