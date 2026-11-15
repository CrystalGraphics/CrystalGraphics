package com.crystalgraphics.world.actor.kit;

import com.crystalgraphics.api.animation.CgSkeletonDefinition;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.object.value.CgUnits;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a character's files say beyond its mesh: the sockets of its skeleton asset and the materials its prefab names.
 * Both read the kit's {@code units.yaml}. Worker-safe.
 *
 * <pre>{@code
 * CgSkeletalMesh mesh = CgModels.withSockets(CgMeshLoader.skeletal(glb), kitRoot, "skeletons/Cpl001p1_Skeleton.yaml");
 * List<String> slots = CgKitModels.prefabMaterials(kitRoot.resolve("prefabs/AT_Character_BP.yaml"));   // one a slot
 * }</pre>
 */
public final class CgKitModels {

    private CgKitModels() {
    }

    /**
     * {@code mesh} with the virtual bones and sockets of {@code skeleton} (under {@code kit}), the sockets in the kit's
     * units; {@code mesh} where the kit has none.
     */
    public static CgSkeletalMesh withSockets(CgSkeletalMesh mesh, Path kit, String skeleton) {
        Path file = kit.resolve(skeleton), units = kit.resolve("units.yaml");
        if (!Files.isRegularFile(file) || !Files.isRegularFile(units)) return mesh;
        try {
            CgUnits kitUnits = CgUnits.of(((Map<?, ?>) CgAssetYaml.read(Files.readString(units))).get("units"), CgUnits.HOST);
            Map<?, ?> tree = (Map<?, ?>) CgAssetYaml.read(Files.readString(file));
            CgSkeletonDefinition d = CgSkeletonDefinition.compile(skeleton, tree, kitUnits);
            CgSkeletalMesh withBones = mesh.withVirtualBones(d.virtualBones());
            return withBones.withSockets(d.socketsOn(withBones.skeleton()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The materials a prefab's first mesh names, one a slot in slot order: Unreal's component materials as the kit
     * exported them. Empty where it has none.
     */
    public static List<String> prefabMaterials(Path prefab) {
        if (!Files.isRegularFile(prefab)) return List.of();
        try {
            Map<?, ?> tree = (Map<?, ?>) CgAssetYaml.read(Files.readString(prefab));
            for (Object node : (List<?>) tree.get("nodes")) {
                if (!(((Map<?, ?>) node).get("meshes") instanceof List<?> meshes) || meshes.isEmpty()) continue;
                List<String> ids = new ArrayList<>();
                for (Object id : (List<?>) ((Map<?, ?>) meshes.get(0)).get("materials")) ids.add(String.valueOf(id));
                return ids;
            }
            return List.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
