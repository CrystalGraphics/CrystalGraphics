package com.crystalgraphics.vfx.prefab;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.asset.kind.CgBodyKinds;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.object.value.CgQuantity;
import com.crystalgraphics.object.value.CgUnits;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code prefab} kind's compiler and writer: a {@code prefabs/} file to the {@link CgVfxPrefab} it describes, and a
 * prefab back to its file. Unreal's Blueprint actor and its component tree, CC2's clump.
 *
 * <pre>{@code
 * CgVfxPrefabAssets.register();                                    // the pipeline compiles prefabs so
 * CgVfxPrefab shock = CgVfxAssets.<CgVfxPrefab>ref(CgBodyKinds.PREFAB, "nsuns4_kit:1efcmn/1efc_shock08").get();
 * }</pre>
 *
 * <pre>{@code
 * Map<String, Object> tree = CgVfxPrefabAssets.write(CORE);       // a prefab built in Java, as its file
 * Files.write(path, CgAssetYaml.write(tree, CgBodyKinds.PREFAB).getBytes(StandardCharsets.UTF_8));
 * }</pre>
 *
 * <pre>{@code
 * # assets/mymod/prefabs/core.yaml
 * _v: 1
 * nodes:
 *   - { id: n1, name: ball03 }
 *   - { id: n2, name: ball02, parent: ball03, location: [0, 1, 0], meshes: [ { mesh: mymod:models/core/ball02.glb, material: mymod:lens } ] }
 * }</pre>
 *
 * <ul>
 *   <li>Its values convert from its units to the host's: a location and a scale through the axes, a rotation in its
 *       convention to a quaternion.</li>
 *   <li>A mesh slot is kept as written, its materials and its other fields: a character's several materials and its
 *       overlay compile, and fail only where a prefab would draw them ({@link CgVfxPrefab#requireDrawable}).</li>
 *   <li>A material it names must be compiled with it: one that is not fails, naming the node.</li>
 *   <li>{@link #write} writes in the host's units; a part whose mesh was made in code has no file and fails there.</li>
 * </ul>
 */
public final class CgVfxPrefabAssets {

    /** The prefab kind's compiler. */
    public static final CgAssetCompiler<CgVfxPrefab> COMPILER = (asset, deps) -> compile(asset.id(), asset.resolved(), deps);

    private CgVfxPrefabAssets() {
    }

    /** Sets {@link #COMPILER} as the prefab kind's. */
    public static void register() {
        CgAssetCompiler.register(CgBodyKinds.PREFAB, COMPILER);
    }

    /** {@code tree}, a prefab file, as a prefab: its materials from {@code deps}, its values from {@code deps}' units. */
    public static CgVfxPrefab compile(String id, Map<?, ?> tree, CgAssetCompiler.Dependencies deps) {
        CgUnits units = deps.units();
        CgVfxPrefab.Builder b = CgVfxPrefab.builder(id);
        Quaternionf turn = new Quaternionf();
        if (!(tree.get("nodes") instanceof List<?> nodes)) throw new CgAssetException(id + " has no nodes");
        for (Object o : nodes) {
            Map<?, ?> node = (Map<?, ?>) o;
            String name = (String) node.get("name");
            String where = id + ": nodes." + node.get("id");
            b.node(name).elementId(String.valueOf(node.get("id")));
            if (node.get("parent") instanceof String parent) b.parent(parent);
            if (node.get("location") != null) {
                float[] v = three(units.toHost(vector(node.get("location"), where + ".location"), CgQuantity.POSITION));
                b.at(v[0], v[1], v[2]);
            }
            if (node.get("rotation") != null) {
                try {
                    units.rotationToHost(node.get("rotation"), turn);
                } catch (IllegalArgumentException e) {
                    throw new CgAssetException(where + ".rotation: " + e.getMessage());
                }
                b.rotation(turn.x, turn.y, turn.z, turn.w);
            }
            if (node.get("scale") != null) {
                float[] v = three(units.toHost(vector(node.get("scale"), where + ".scale"), CgQuantity.SCALE));
                b.scale(v[0], v[1], v[2]);
            }
            if (node.get("opacity") instanceof Number a) b.opacity(a.floatValue());
            if (node.get("values") instanceof Map<?, ?> values) {
                for (Map.Entry<?, ?> v : values.entrySet()) b.value((String) v.getKey(), floats(v.getValue(), where + ".values." + v.getKey()));
            }
            if (node.get("meshes") instanceof List<?> meshes) {
                for (int k = 0; k < meshes.size(); k++) part(b, (Map<?, ?>) meshes.get(k), deps, where + ".meshes[" + k + "]");
            }
        }
        return b.build();
    }

    private static void part(CgVfxPrefab.Builder b, Map<?, ?> slot, CgAssetCompiler.Dependencies deps, String where) {
        List<CgMaterialDefinition> materials = new ArrayList<>();
        if (slot.get("material") instanceof String m) materials.add(material(m, deps, where + ".material"));
        if (slot.get("materials") instanceof List<?> ms) {
            for (int i = 0; i < ms.size(); i++) materials.add(material((String) ms.get(i), deps, where + ".materials[" + i + "]"));
        }
        if (materials.isEmpty()) throw new CgAssetException(where + " names no material");
        if (!(slot.get("mesh") instanceof String mesh)) throw new CgAssetException(where + " names no mesh");
        Map<String, Object> settings = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : slot.entrySet()) {
            String key = (String) e.getKey();
            if (!key.equals("mesh") && !key.equals("material") && !key.equals("materials")) settings.put(key, e.getValue());
        }
        b.part(materials, mesh, settings);
    }

    private static CgMaterialDefinition material(String id, CgAssetCompiler.Dependencies deps, String where) {
        if (deps.definition(CgLookKinds.MATERIAL, id) instanceof CgMaterialDefinition d) return d;
        throw new CgAssetException(where + ": " + id + " is not compiled with it");
    }

    /** {@code prefab} as its file's tree, in the host's units: compiling it gives a prefab that writes the same tree. */
    public static Map<String, Object> write(CgVfxPrefab prefab) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("_v", 1);
        List<Object> nodes = new ArrayList<>();
        float[] rest = new float[CgVfxPrefab.REST];
        for (int n = 0; n < prefab.nodes(); n++) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", prefab.elementId(n));
            node.put("name", prefab.name(n));
            if (prefab.parent(n) >= 0) node.put("parent", prefab.name(prefab.parent(n)));
            prefab.rest(n, rest);
            if (rest[0] != 0 || rest[1] != 0 || rest[2] != 0) node.put("location", list(rest, 0, 3));
            if (rest[3] != 0 || rest[4] != 0 || rest[5] != 0 || rest[6] != 1) node.put("rotation", list(rest, 3, 4));
            if (rest[7] != 1 || rest[8] != 1 || rest[9] != 1) node.put("scale", list(rest, 7, 3));
            if (prefab.opacity(n) != 1f) node.put("opacity", prefab.opacity(n));
            if (!prefab.values(n).isEmpty()) {
                Map<String, Object> values = new LinkedHashMap<>();
                for (Map.Entry<String, float[]> v : prefab.values(n).entrySet()) values.put(v.getKey(), number(v.getValue()));
                node.put("values", values);
            }
            List<Object> meshes = new ArrayList<>();
            for (int k = prefab.firstPart(n); k < prefab.firstPart(n + 1); k++) {
                String path = prefab.partPath(k);
                if (path == null) throw new IllegalArgumentException(prefab.id() + "'s node " + prefab.name(n) + " draws a mesh made in code, which has no file");
                Map<String, Object> slot = new LinkedHashMap<>();
                slot.put("mesh", path);
                List<CgMaterialDefinition> materials = prefab.partMaterials(k);
                if (materials.size() == 1) {
                    slot.put("material", materials.get(0).id());
                } else {
                    List<Object> ids = new ArrayList<>();
                    for (CgMaterialDefinition m : materials) ids.add(m.id());
                    slot.put("materials", ids);
                }
                slot.putAll(prefab.partSettings(k));
                meshes.add(slot);
            }
            if (!meshes.isEmpty()) node.put("meshes", meshes);
            nodes.add(node);
        }
        tree.put("nodes", nodes);
        return tree;
    }

    /** A value as a file writes it: a number, or a list of them. */
    static Object number(float[] v) {
        if (v.length == 1) return v[0];
        List<Object> out = new ArrayList<>(v.length);
        for (float f : v) out.add(f);
        return out;
    }

    private static List<Object> list(float[] v, int from, int n) {
        List<Object> out = new ArrayList<>(n);
        for (int i = from; i < from + n; i++) out.add(v[i]);
        return out;
    }

    private static float[] floats(Object v, String where) {
        if (v instanceof Number n) return new float[] {n.floatValue()};
        if (v instanceof List<?> l) {
            float[] out = new float[l.size()];
            for (int i = 0; i < out.length; i++) out[i] = ((Number) l.get(i)).floatValue();
            return out;
        }
        throw new CgAssetException(where + ": " + v + " is not a number or a list of them");
    }

    private static List<?> vector(Object v, String where) {
        if (v instanceof List<?> l && l.size() == 3) return l;
        throw new CgAssetException(where + ": " + v + " is not three numbers");
    }

    private static float[] three(Object v) {
        List<?> l = (List<?>) v;
        return new float[] {((Number) l.get(0)).floatValue(), ((Number) l.get(1)).floatValue(), ((Number) l.get(2)).floatValue()};
    }
}
