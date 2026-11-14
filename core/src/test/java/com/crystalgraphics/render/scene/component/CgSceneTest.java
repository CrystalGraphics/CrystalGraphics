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
import com.crystalgraphics.gpu.resource.CgFrameRing;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
        CgMeshComponent rock = scene.mesh(CUBE, STONE).at(1, 0.5, -3).layer(CgSortLayer.OVERLAY).customDepth(3).spawn();
        scene.primitives().gather(view, camera, out);
        assertEquals(1, out.size());
        assertSame(CUBE, out.mesh(0));
        assertSame(STONE, out.material(0));
        assertEquals(rock.objectId(), out.objectId(0));
        assertTrue((out.objectId(0) & CgGpuScene.SCENE_RECORD) != 0);
        assertSame(CgSortLayer.OVERLAY, out.layer(0));
        assertSame(rock, out.primitive(0));
        assertEquals(3, rock.customDepth().value());
        assertEquals(1, scene.gpu().size());

        rock.remove();
        scene.primitives().gather(view, camera, out);
        assertEquals(0, out.size());
        assertEquals(0, scene.gpu().size());
    }

    @Test
    public void aPixel_picksTheNearestBoxUnderIt_hiddenOnesSkipped() {
        CgScene scene = new CgScene();
        CgMeshComponent near = scene.mesh(CUBE, STONE).at(0, 0, -3).spawn();
        CgMeshComponent far = scene.mesh(CUBE, STONE).at(0, 0, -8).spawn();
        CgView eye = CgView.of(scene).size(100, 100);
        eye.camera().set(camera);
        assertSame(near, eye.pick(50, 50));
        near.visible(false);
        assertSame(far, eye.pick(50, 50));
        assertNull("a corner's ray passes beside both", eye.pick(0, 0));
    }

    @Test
    public void hiddenAndOutOfReach_areNotGathered() {
        CgScene scene = new CgScene();
        CgMeshComponent near = scene.mesh(CUBE, STONE).at(0, 0, -5).maxDistance(10f).spawn();
        CgMeshComponent far = scene.mesh(CUBE, STONE).at(0, 0, -50).maxDistance(10f).spawn();
        scene.primitives().gather(view, camera, out);
        assertEquals(1, out.size());
        assertEquals(near.objectId(), out.objectId(0));

        near.visible(false);
        far.maxDistance(100f);
        scene.primitives().gather(view, camera, out);
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
        scene.primitives().gather(view, camera, out);
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
        scene.primitives().gather(view, camera, out);
        assertEquals(1, out.size());
        assertEquals(2, out.instanceCount(0));
        assertNotNull(out.instances(0));
        int[] ids = out.instances(0).ids();
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
        scene.primitives().gather(view, camera, out);
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
    public void drawOnce_drawsInItsFrameOnly() {
        CgScene scene = new CgScene();
        scene.drawOnce(CUBE, GLOW, 4, 5, -60);
        scene.primitives().gather(view, camera, out);
        assertEquals(1, out.size());
        assertEquals(-60.0, out.position(0, 2), 0.0);
        scene.primitives().gather(view, camera, out);
        assertEquals("a second view of the frame draws it too", 1, out.size());
        CgFrameRing.endFrame();
        scene.primitives().gather(view, camera, out);
        assertEquals(0, out.size());
    }

    @Test
    public void onceRows_outsideTheView_areLeftOut() {
        CgScene scene = new CgScene();
        scene.once().mesh(CUBE, GLOW).at(0, 0, -10).add();
        scene.once().mesh(CUBE, GLOW).at(0, 0, 10).add();   // behind the eye
        scene.primitives().gather(view, camera, out);
        assertEquals(1, out.size());
        assertEquals(-10.0, out.position(0, 2), 0.0);
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
    public void velocity_isWrittenTheFrameAMeshMoves_orAlwaysWhereAsked() {
        CgScene scene = new CgScene();
        CgMeshComponent rock = scene.mesh(CUBE, STONE).at(0, 0, -5).spawn();
        CgFrameRing.endFrame();
        assertFalse(rock.hasVelocity());
        rock.at(1, 0, -5);
        assertTrue(rock.hasVelocity());
        CgFrameRing.endFrame();
        assertFalse(rock.hasVelocity());
        rock.writesVelocity(true);
        assertTrue(rock.hasVelocity());
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
