package com.crystalgraphics.render.scene.component;

import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.render.scene.gpu.CgGpuScene;
import com.crystalgraphics.render.scene.sort.CgSortLayer;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshLods;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.view.CgView;
import org.joml.Quaternionf;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CgSceneTest {

    static final CgMaterialInterface STONE = material(1), GLOW = material(2);
    static final CgMesh CUBE = CgMeshShapes.cube();

    private final CgView view = CgView.main();
    private final CgCamera camera = CgCamera.perspective(70f, 1f, 0.05f, 1000f).position(0, 0, 0).lookAt(0, 0, 0, 0, 0, -1);
    private final CgMeshBatches out = new CgMeshBatches();

    @Test
    public void aSpawnedMesh_isGatheredByItsRecord_andRemovingItFreesTheRecord() {
        CgScene scene = new CgScene();
        CgMeshComponent rock = scene.mesh(CUBE, STONE).at(10, 64, -3).layer(CgSortLayer.OVERLAY).customDepth(3).spawn();
        scene.gather(view, camera, out);
        assertEquals(1, out.size());
        assertSame(CUBE, out.mesh(0));
        assertSame(STONE, out.material(0));
        assertEquals(rock.objectId(), out.objectId(0));
        assertTrue((out.objectId(0) & CgGpuScene.SCENE_RECORD) != 0);
        assertSame(CgSortLayer.OVERLAY, out.layer(0));
        assertEquals(CgMeshBatches.CUSTOM_DEPTH, out.flags(0) & CgMeshBatches.CUSTOM_DEPTH);
        assertEquals(3, out.stencil(0));
        assertEquals(1, scene.gpu().size());

        rock.remove();
        scene.gather(view, camera, out);
        assertEquals(0, out.size());
        assertEquals(0, scene.gpu().size());
    }

    @Test
    public void hiddenAndOutOfReach_areNotGathered() {
        CgScene scene = new CgScene();
        CgMeshComponent near = scene.mesh(CUBE, STONE).at(0, 0, -5).maxDistance(10f).spawn();
        CgMeshComponent far = scene.mesh(CUBE, STONE).at(0, 0, -50).maxDistance(10f).spawn();
        scene.gather(view, camera, out);
        assertEquals(1, out.size());
        assertEquals(near.objectId(), out.objectId(0));

        near.visible(false);
        far.maxDistance(100f);
        scene.gather(view, camera, out);
        assertEquals(1, out.size());
        assertEquals(far.objectId(), out.objectId(0));
    }

    @Test
    public void levels_arePickedByScreenSize_perView() {
        CgMesh coarse = CgMeshShapes.cube();
        CgMeshLods lods = CgMeshLods.builder().level(CUBE, 0.2f).level(coarse, 0.01f).build();
        CgScene scene = new CgScene();
        scene.mesh(lods, STONE).at(0, 0, -3).spawn();
        CgMeshComponent distant = scene.mesh(lods, STONE).at(0, 0, -40).spawn();
        scene.mesh(lods, STONE).at(0, 0, -900).spawn();   // under every level: culled
        scene.gather(view, camera, out);
        assertEquals(2, out.size());
        assertSame(CUBE, out.mesh(0));
        assertSame(coarse, out.mesh(1));
        assertEquals(distant.objectId(), out.objectId(1));
    }

    @Test
    public void instances_drawAsOneBatch_eachItsOwnRecord() {
        CgScene scene = new CgScene();
        CgInstancedMeshComponent grass = scene.instanced(CUBE, STONE).spawn();
        Quaternionf none = new Quaternionf();
        int a = grass.add(1, 0, 0, none, 1f);
        grass.add(2, 0, 0, none, 1f);
        grass.add(3, 0, 0, none, 1f);
        grass.remove(a);
        scene.gather(view, camera, out);
        assertEquals(1, out.size());
        assertEquals(2, out.instanceCount(0));
        int[] ids = out.instances(0);
        assertNotNull(ids);
        for (int i = 0; i < 2; i++) assertTrue((ids[i] & CgGpuScene.SCENE_RECORD) != 0);
        assertEquals(2, scene.gpu().size());

        grass.remove();
        assertEquals(0, scene.gpu().size());
    }

    @Test
    public void aDynamicPrimitive_collectsPerView_withItsComponentsSettings() {
        CgScene scene = new CgScene();
        scene.dynamic((v, o) -> {
            o.mesh(CUBE, GLOW).at(5, 6, 7).customData(1, 0.5f, 0f, 0f, 1f).add();
            o.mesh(CUBE, STONE).add();
        }).at(1, 2, 3).layer(CgSortLayer.EFFECTS).emission(3f).spawn();
        scene.gather(view, camera, out);
        assertEquals(2, out.size());
        assertEquals(-1, out.objectId(0));
        assertSame(CgSortLayer.EFFECTS, out.layer(1));
        assertEquals(5.0, out.position(0, 0), 0.0);
        assertEquals(1.0, out.position(1, 0), 0.0);   // the component's position, unless the row sets one
        float[] v = out.values();
        assertEquals(0.5f, v[CgMeshBatches.CUSTOM + 4], 0f);
        assertEquals(3f, v[CgMeshBatches.VALUES + CgMeshBatches.EMISSION], 0f);
        assertEquals(0, scene.gpu().size());
    }

    @Test
    public void drawOnce_drawsInTheNextFrameOnly() {
        CgScene scene = new CgScene();
        scene.drawOnce(CUBE, GLOW, 4, 5, 6);
        scene.primitives().beginFrame();
        scene.gather(view, camera, out);
        assertEquals(1, out.size());
        assertEquals(6.0, out.position(0, 2), 0.0);
        scene.primitives().beginFrame();
        scene.gather(view, camera, out);
        assertEquals(0, out.size());
    }

    @Test
    public void movingAMesh_rewritesItsRecord_withThePreviousBesideIt() {
        CgScene scene = new CgScene();
        CgMeshComponent rock = scene.mesh(CUBE, STONE).at(100, 0, 0).spawn();
        scene.gpu().settle();
        rock.at(101, 0, 0);
        int slot = rock.objectId() & ~CgGpuScene.SCENE_RECORD;
        assertEquals(101.0, scene.gpu().position(slot, 0), 0.0);
        assertEquals(100.0, scene.gpu().previousPosition(slot, 0), 0.0);
    }

    @Test
    public void aRemovedComponent_refusesSetters() {
        CgScene scene = new CgScene();
        CgMeshComponent rock = scene.mesh(CUBE, STONE).spawn();
        rock.remove();
        assertTrue(rock.removed());
        assertThrows(IllegalStateException.class, () -> rock.at(1, 2, 3));
    }

    static CgMaterialInterface material(int id) {
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
        };
    }
}
