package com.crystalgraphics.asset.camera;

import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgCameraKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** CA1: a rig compiles to its nodes in evaluation order with their registered kinds; an unknown kind fails at load. */
public class CgCameraRigDefinitionTest {

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    private static Map<String, Object> node(String id, String kind) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        n.put("node", kind);
        return n;
    }

    private static CgCameraRigDefinition rig(List<Object> root) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("tags", List.of("third_person"));
        Map<String, Object> distance = new LinkedHashMap<>();
        distance.put("type", "float");
        distance.put("default", 4.0);
        tree.put("parameters", Map.of("distance", distance));
        tree.put("root", root);
        return CgCameraRigDefinition.compile(CgAssetData.of("test:rig", CgCameraKinds.CAMERA_RIG, tree, null));
    }

    @Test
    public void nodesFlattenParentsFirstWithTheirKinds() {
        Map<String, Object> group = node("group", "array");
        group.put("children", List.of(node("fov", "field_of_view"), node("lens", "lens")));
        CgCameraRigDefinition rig = rig(List.of(node("boom", "boom_arm"), group, node("shake", "shake")));

        List<CgCameraRigDefinition.Node> nodes = rig.nodes();
        assertEquals(5, nodes.size());
        assertEquals(List.of("boom", "group", "fov", "lens", "shake"), nodes.stream().map(CgCameraRigDefinition.Node::id).toList());
        assertEquals(-1, nodes.get(1).parent());
        assertEquals("children follow their array", 1, nodes.get(2).parent());
        assertEquals(1, nodes.get(3).parent());
        assertSame(CgCameraKinds.NODES.member("boom_arm"), nodes.get(0).type());
        assertEquals(List.of("third_person"), rig.tags());
        assertEquals("distance", rig.parameters().get(0).name());
        assertEquals("none", rig.initialOrientation());
    }

    @Test
    public void anUnknownNodeKindFailsAtLoad() {
        Map<String, Object> group = node("group", "array");
        group.put("children", List.of(node("warp", "warp_drive")));
        RuntimeException e = assertThrows(RuntimeException.class, () -> rig(List.of(node("boom", "boom_arm"), group)));
        assertTrue(e.getMessage(), e.getMessage().contains("warp_drive"));
    }
}
