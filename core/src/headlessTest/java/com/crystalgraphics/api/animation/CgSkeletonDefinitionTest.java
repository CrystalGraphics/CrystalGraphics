package com.crystalgraphics.api.animation;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.object.value.CgUnits;
import org.joml.Quaternionf;
import org.junit.Assume;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * A skeleton asset's sockets in the host's units: Unreal's centimetres and rotators through its glTF axes, as
 * CUE4Parse writes a bone ({@code Gltf.cs:130-132, 274-280}). With {@code -Pkits=kakarot_kit=<dir>}, Goku's.
 */
public class CgSkeletonDefinitionTest {

    private static final float EPS = 1e-6f;

    @Test
    public void unrealSocketsReachTheHostAsTheBonesDid() {
        Map<?, ?> tree = Map.of("sockets", List.of(
                Map.of("id", "a", "name", "palm", "from", "bone", "bone", "Hand_R",
                        "location", List.of(10.0, 4.5, 13.5), "rotation", Map.of("pitch", 5.0, "yaw", 19.0, "roll", 5.2)),
                Map.of("id", "b", "name", "Lockon", "from", "bone", "bone", "spine_02"),
                Map.of("id", "c", "name", "feet", "from", "entity", "at", "feet")));
        CgSkeletonDefinition d = CgSkeletonDefinition.compile("test:skeleton", tree, CgUnits.UNREAL);

        assertEquals("an entity socket is not the mesh's", 2, d.sockets().size());
        CgSocket palm = d.sockets().get(0);
        assertEquals("Hand_R", palm.bone());
        // (X, Y, Z) cm -> (X, Z, Y) m: Gltf.cs SwapYZ(t * 0.01)
        assertEquals(0.100f, palm.tx(), EPS);
        assertEquals(0.135f, palm.ty(), EPS);
        assertEquals(0.045f, palm.tz(), EPS);
        float[] q = CgUnits.rotatorToHost(Math.toRadians(5.2), Math.toRadians(5.0), Math.toRadians(19.0));
        assertEquals(q[0], palm.qx(), EPS);
        assertEquals(q[1], palm.qy(), EPS);
        assertEquals(q[2], palm.qz(), EPS);
        assertEquals(q[3], palm.qw(), EPS);
        assertEquals(1f, new Quaternionf(palm.qx(), palm.qy(), palm.qz(), palm.qw()).lengthSquared(), 1e-5f);

        CgSocket lockon = d.sockets().get(1);
        assertEquals(0f, lockon.tx(), 0f);
        assertEquals(1f, lockon.qw(), 0f);
        assertEquals(1f, lockon.sx(), 0f);
    }

    @Test
    public void gokusSkeletonCompiles() throws Exception {
        Path kit = kit("kakarot_kit");
        Assume.assumeTrue("-Pkits=kakarot_kit=<dir> for Goku's skeleton", kit != null);
        Map<?, ?> units = (Map<?, ?>) CgAssetYaml.read(Files.readString(kit.resolve("units.yaml")));
        Map<?, ?> tree = (Map<?, ?>) CgAssetYaml.read(Files.readString(
                kit.resolve("skeletons/Art/MDL/Cpl/Cpl001/Model/Cpl001p1_Skeleton.yaml")));
        CgSkeletonDefinition d = CgSkeletonDefinition.compile("kakarot_kit:Art/MDL/Cpl/Cpl001/Model/Cpl001p1_Skeleton",
                tree, CgUnits.of(units.get("units"), CgUnits.HOST));
        CgSocket kameha = d.sockets().stream().filter(s -> s.name().equals("hand_r_kameha_socket_02")).findFirst().orElseThrow();
        assertEquals(0.10244f, kameha.tx(), 1e-5f);
        assertEquals(0.13706f, kameha.ty(), 1e-5f);
        assertEquals(0.04748f, kameha.tz(), 1e-5f);
    }

    private static Path kit(String namespace) {
        String kits = System.getProperty("crystalgraphics.kits");
        if (kits == null) return null;
        for (String entry : kits.split(";")) {
            int eq = entry.indexOf('=');
            if (eq > 0 && entry.substring(0, eq).equals(namespace)) return Paths.get(entry.substring(eq + 1));
        }
        return null;
    }
}
