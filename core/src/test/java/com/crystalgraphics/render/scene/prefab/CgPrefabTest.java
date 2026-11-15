package com.crystalgraphics.render.scene.prefab;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgSurfaceMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgMeshComponent;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CgPrefabTest {

    private static final float HALF_TURN_Y = (float) Math.sqrt(0.5);
    private static final CgMaterialDefinition SKIN = CgSurfaceMaterial.of("test:skin").definition(),
            GLOW = CgSurfaceMaterial.of("test:glow").definition();
    private static final CgMesh CUBE = CgMeshShapes.cube();

    private static CgPrefab chain() {
        return CgPrefab.builder("test:chain")
                .node("root").at(1f, 0f, 0f)
                .node("arm").parent("root").at(0f, 2f, 0f).rotation(0f, HALF_TURN_Y, 0f, HALF_TURN_Y).scale(2f).opacity(0.5f)
                .part(SKIN, CUBE)
                .node("hand").parent("arm").at(1f, 0f, 0f).opacity(0.5f).value("_Emissive", 3f).part(SKIN, CUBE).part(GLOW, CUBE)
                .build();
    }

    @Test
    public void childrenComposeWithTheirParentsAndKeepTheirOwnOpacity() {
        CgPrefab prefab = chain();
        CgPrefabPose pose = new CgPrefabPose(prefab).evaluate();
        int hand = prefab.node("hand");

        // hand's origin: arm turned a quarter about +y and doubled, so its +x of 1 lands at -z 2, from arm at (1, 2, 0)
        Vector3f at = pose.world(hand).getTranslation(new Vector3f());
        assertEquals(1f, at.x, 1e-5f);
        assertEquals(2f, at.y, 1e-5f);
        assertEquals(-2f, at.z, 1e-5f);
        assertEquals("its own, not times arm's 0.5", 0.5f, pose.opacity(hand), 1e-6f);

        pose.opacity(prefab.node("root"), 0f).evaluate();
        assertEquals("a parent at 0 leaves its child visible (C5)", 0.5f, pose.opacity(hand), 0f);
        pose.opacity(hand, 0.1f).reset();
        assertEquals(0.5f, pose.opacity(hand), 1e-6f);
    }

    @Test
    public void aNodesValuesAreDrawnAsItsMaterialsDerived() {
        CgPrefab prefab = chain();
        int arm = prefab.firstPart(prefab.node("arm")), hand = prefab.firstPart(prefab.node("hand"));
        assertSame(SKIN, prefab.drawnMaterials(arm).get(0));
        CgMaterialDefinition handSkin = prefab.drawnMaterials(hand).get(0);
        assertNotSame(SKIN, handSkin);
        assertSame(SKIN, prefab.partMaterials(hand).get(0));
        assertEquals(3f, handSkin.constants().get("_Emissive")[0], 0f);
    }

    @Test
    public void aSceneSpawnsARootANodeAndAMeshAPart_placedAsThePoseIs() {
        CgPrefab prefab = chain();
        CgScene scene = new CgScene();
        CgPrefabInstance chain = scene.spawn(prefab).at(10, 0, 0).spawn();
        CgMeshComponent hand = chain.mesh("hand"), glow = chain.mesh("hand", 1);
        assertSame(chain.node("hand"), hand.attachParent());
        assertSame(chain.node("hand"), glow.attachParent());
        assertSame(chain.node("arm"), chain.node("hand").attachParent());
        assertSame(chain.root(), chain.node("root").attachParent());
        assertNull("root draws nothing", chain.mesh("root"));
        assertNull(chain.mesh("hand", 2));
        scene.update();

        Vector3d at = hand.worldTransform().getTranslation(new Vector3d());
        assertEquals(11.0, at.x, 1e-5);
        assertEquals(2.0, at.y, 1e-5);
        assertEquals(-2.0, at.z, 1e-5);

        chain.remove();
        assertTrue(hand.removed());
        assertTrue(chain.root().removed());
    }

    @Test
    public void whatASceneDoesNotDrawIsRefusedAtSpawn_namingTheNode() {
        CgScene scene = new CgScene();
        CgPrefab sphere = CgPrefab.builder("test:orb").node("core").part(GLOW).build();
        CgAssetException e = assertThrows(CgAssetException.class, () -> scene.spawn(sphere).spawn());
        assertTrue(e.getMessage(), e.getMessage().contains("core") && e.getMessage().contains("sphere"));
        CgPrefab overlaid = CgPrefab.builder("test:hero").node("body")
                .part(List.of(SKIN), "test:models/body.glb", Map.of("overlay", true)).build();
        e = assertThrows(CgAssetException.class, () -> scene.spawn(overlaid).spawn());
        assertTrue(e.getMessage(), e.getMessage().contains("body") && e.getMessage().contains("overlay"));
    }

    @Test
    public void parentsComeFirstAndNamesAreUnique() {
        assertThrows(IllegalArgumentException.class, () -> CgPrefab.builder("test:bad").node("a").parent("b").build());
        assertThrows(IllegalArgumentException.class, () -> CgPrefab.builder("test:bad").node("a").node("a").build());
        assertThrows(IllegalStateException.class, () -> CgPrefab.builder("test:bad").at(0f, 0f, 0f));
    }
}
