package com.crystalgraphics.render.scene.prefab;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialDefinitions;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshLoader;
import com.crystalgraphics.api.mesh.CgMeshLods;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Components as an asset: a tree of nodes, each with a parent, a rest transform, an opacity, material values of its own
 * and the meshes it draws, each with its materials. Unreal's Blueprint component tree and packed level actor; CC2's
 * clump. A scene spawns one as components ({@code scene.spawn(prefab)}); a VFX effect plays one through its look
 * ({@code CgVfxPrefabEffect}); its file is a {@code prefabs/} asset ({@link CgPrefabAssets}).
 *
 * <pre>{@code
 * static final CgPrefab LANTERN = CgPrefab.builder("mymod:lantern")
 *         .node("frame").part(IRON, "mymod:models/lantern/frame.glb")
 *         .node("glass").parent("frame").at(0f, 0.3f, 0f).part(GLASS, "mymod:models/lantern/glass.glb")
 *         .node("flame").parent("frame").at(0f, 0.25f, 0f).scale(0.5f).value("_Heat", 2f)
 *             .part(FLAME, "mymod:models/lantern/flame.glb")
 *         .build();
 *
 * CgPrefabInstance lantern = scene.spawn(LANTERN).at(x, y, z).spawn();   // a component a part, a root a node
 * }</pre>
 *
 * <p>From a file, by id:</p>
 * <pre>{@code
 * CgPrefab wyvern = CgVfxAssets.<CgPrefab>ref(CgBodyKinds.PREFAB, "mymod:prefabs/wyvern").get();
 * }</pre>
 *
 * <ul>
 *   <li>A parent is named before its children: nodes are kept in that order, which is the order they evaluate in.</li>
 *   <li>Names are unique within a prefab. A node's opacity is its own: a parent's never fades its children.</li>
 *   <li>A node's values are drawn as its parts' materials derived with them ({@link #drawnMaterials}), shared by every
 *       node with the same.</li>
 *   <li>A mesh path loads at its first draw, so a server naming one loads nothing.</li>
 *   <li>A mesh slot is kept as written ({@link #partMaterials}, {@link #partSettings}): several materials draw its mesh's
 *       sections in order.</li>
 * </ul>
 */
public final class CgPrefab {

    /** A node drawn at less presence than this draws nothing. */
    public static final float MIN_OPACITY = 1e-3f;
    /** Floats of a node's rest: location xyz, rotation as a quaternion xyzw, scale xyz. */
    public static final int REST = 10;

    private final String id;
    private final String[] names, ids;
    private final int[] parents;
    private final float[] rest;
    private final float[] opacity;
    private final List<Map<String, float[]>> values;
    /** Node i's parts are {@code parts[partStart[i]]} to {@code partStart[i + 1]}. */
    private final int[] partStart;
    private final Part[] parts;
    private final Map<String, Integer> byName;
    private final Map<Class<?>, Object> derived = new ConcurrentHashMap<>(2);

    private CgPrefab(Builder b) {
        int n = b.names.size();
        this.id = b.id;
        this.names = b.names.toArray(new String[0]);
        this.ids = b.ids.toArray(new String[0]);
        this.parents = new int[n];
        this.rest = Arrays.copyOf(b.rest, n * REST);
        this.opacity = new float[n];
        this.values = new ArrayList<>(b.values);
        this.byName = new HashMap<>(b.byName);
        this.partStart = new int[n + 1];
        List<Part> all = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            parents[i] = b.parents.get(i);
            opacity[i] = b.opacity.get(i);
            partStart[i] = all.size();
            for (Part part : b.parts.get(i)) all.add(part.drawn(values.get(i)));
        }
        partStart[n] = all.size();
        this.parts = all.toArray(new Part[0]);
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public String id() {
        return id;
    }

    /** How many nodes it has. */
    public int nodes() {
        return names.length;
    }

    public String name(int node) {
        return names[node];
    }

    /** {@code node}'s element id in its file: its name where it was built in Java. */
    public String elementId(int node) {
        return ids[node];
    }

    /** {@code node}'s parent, -1 for a root; always lower than {@code node}. */
    public int parent(int node) {
        return parents[node];
    }

    /** The node of that name, or -1. */
    public int node(String name) {
        Integer i = byName.get(name);
        return i == null ? -1 : i;
    }

    /**
     * {@code node}'s rest transform into {@code out} ({@link #REST} floats): location xyz, rotation as a unit quaternion
     * xyzw, scale xyz, in its parent's space. Answers {@code out}.
     */
    public float[] rest(int node, float[] out) {
        System.arraycopy(rest, node * REST, out, 0, REST);
        return out;
    }

    /** {@code node}'s own opacity at rest. */
    public float opacity(int node) {
        return opacity[node];
    }

    /** {@code node}'s own material values, over each of its parts' materials. */
    public Map<String, float[]> values(int node) {
        return values.get(node);
    }

    /** The first of {@code node}'s parts; its last is {@code firstPart(node + 1) - 1}. */
    public int firstPart(int node) {
        return partStart[node];
    }

    /** Part {@code part}'s mesh, loaded the first time it is asked; null for the unit sphere a VFX look draws. */
    @Nullable
    public CgMeshLods partMesh(int part) {
        Mesh mesh = parts[part].mesh;
        return mesh == null ? null : mesh.lods();
    }

    /** Part {@code part}'s mesh path, or null for a mesh given in code or the unit sphere. */
    @Nullable
    public String partPath(int part) {
        Mesh mesh = parts[part].mesh;
        return mesh == null ? null : mesh.path;
    }

    /** Part {@code part}'s materials as written: one, or one a section of its mesh. */
    public List<CgMaterialDefinition> partMaterials(int part) {
        return parts[part].materials;
    }

    /** Part {@code part}'s materials as drawn: each with its node's values over it, the same definition for the same. */
    public List<CgMaterialDefinition> drawnMaterials(int part) {
        return parts[part].drawn;
    }

    /** Part {@code part}'s mesh slot's other fields as written ({@code cast_shadow}, {@code overlay}...); empty for none. */
    public Map<String, Object> partSettings(int part) {
        return parts[part].settings;
    }

    /**
     * The nodes with a part drawing {@code material}, a sequence's {@code { spawnable, material }} binding: its id, or
     * its id's last segment ({@code blade} for {@code mymod:ring/blade}). Empty when none draws it.
     */
    public int[] nodesDrawing(String material) {
        int[] out = new int[nodes()];
        int count = 0;
        for (int n = 0; n < nodes(); n++) {
            parts:
            for (int k = firstPart(n); k < firstPart(n + 1); k++) {
                for (CgMaterialDefinition m : partMaterials(k)) {
                    String id = m.id();
                    if (id.equals(material) || id.endsWith("/" + material) || id.endsWith(":" + material)) {
                        out[count++] = n;
                        break parts;
                    }
                }
            }
        }
        return Arrays.copyOf(out, count);
    }

    /**
     * What {@code make} derives from this prefab, made at the first ask for {@code type} and kept with it: how a player
     * keeps its own form of the prefab (a VFX look) without the prefab naming it. Unreal's asset user data. Any thread.
     *
     * <pre>{@code
     * MyBake bake = prefab.derived(MyBake.class, MyBake::new);     // MyBake(CgPrefab): made once, then the same
     * }</pre>
     */
    public <T> T derived(Class<T> type, Function<CgPrefab, T> make) {
        return type.cast(derived.computeIfAbsent(type, t -> make.apply(this)));
    }

    float[] rest() {
        return rest;
    }

    float[] restOpacity() {
        return opacity;
    }

    /** A part's mesh: given, or a path loaded at its first draw. */
    private static final class Mesh {
        private final String path;
        private volatile CgMeshLods lods;

        Mesh(CgMeshLods lods) {
            this.lods = lods;
            this.path = null;
        }

        Mesh(String path) {
            this.path = path;
        }

        CgMeshLods lods() {
            CgMeshLods l = lods;
            if (l == null) lods = l = CgMeshLoader.lods(path);
            return l;
        }
    }

    private static final class Part {
        final List<CgMaterialDefinition> materials, drawn;
        final Mesh mesh;
        final Map<String, Object> settings;

        Part(List<CgMaterialDefinition> materials, List<CgMaterialDefinition> drawn, Mesh mesh, Map<String, Object> settings) {
            this.materials = materials;
            this.drawn = drawn;
            this.mesh = mesh;
            this.settings = settings;
        }

        static Part of(List<CgMaterialDefinition> materials, @Nullable Mesh mesh, Map<String, Object> settings) {
            if (materials.isEmpty()) throw new IllegalArgumentException("a part draws a material");
            for (CgMaterialDefinition m : materials) Objects.requireNonNull(m, "material");
            List<CgMaterialDefinition> written = List.copyOf(materials);
            return new Part(written, written, mesh, Map.copyOf(settings));
        }

        /** This part with {@code values} over each of its materials. */
        Part drawn(Map<String, float[]> values) {
            if (values.isEmpty()) return this;
            Map<String, Object> written = new LinkedHashMap<>();
            for (Map.Entry<String, float[]> v : values.entrySet()) written.put(v.getKey(), CgPrefabAssets.number(v.getValue()));
            List<CgMaterialDefinition> out = new ArrayList<>(materials.size());
            for (CgMaterialDefinition m : materials) out.add(CgMaterialDefinitions.derive(m, Map.of("values", written)));
            return new Part(materials, List.copyOf(out), mesh, settings);
        }
    }

    /** Builds a prefab node by node; each call after {@link #node} sets that node. */
    public static final class Builder {
        private final String id;
        private final List<String> names = new ArrayList<>(), ids = new ArrayList<>();
        private final List<Integer> parents = new ArrayList<>();
        private final List<Float> opacity = new ArrayList<>();
        private final List<Map<String, float[]>> values = new ArrayList<>();
        private final List<List<Part>> parts = new ArrayList<>();
        private final Map<String, Integer> byName = new HashMap<>();
        private float[] rest = new float[16 * REST];
        private int at = -1;

        private Builder(String id) {
            if (id == null || id.indexOf(':') < 1) throw new IllegalArgumentException("a prefab id is namespace:path: " + id);
            this.id = id;
        }

        /** A new node, a root until {@link #parent} says otherwise, at rest at the origin. */
        public Builder node(String name) {
            if (name == null || name.isEmpty()) throw new IllegalArgumentException("a node needs a name");
            if (byName.containsKey(name)) throw new IllegalArgumentException(id + " has two nodes named " + name);
            at = names.size();
            byName.put(name, at);
            names.add(name);
            ids.add(name);
            parents.add(-1);
            opacity.add(1f);
            values.add(new LinkedHashMap<>());
            parts.add(new ArrayList<>());
            if (rest.length < (at + 1) * REST) rest = Arrays.copyOf(rest, rest.length * 2);
            int o = at * REST;
            rest[o + 6] = 1f;
            rest[o + 7] = rest[o + 8] = rest[o + 9] = 1f;
            return this;
        }

        /** Its element id in a file, its name unless given. */
        public Builder elementId(String elementId) {
            ids.set(current(), Objects.requireNonNull(elementId, "elementId"));
            return this;
        }

        /** Its parent, a node named before it. */
        public Builder parent(String name) {
            Integer p = byName.get(name);
            if (p == null || p == current()) {
                throw new IllegalArgumentException(id + ": " + names.get(at) + "'s parent " + name + " is not a node before it");
            }
            parents.set(at, p);
            return this;
        }

        /** Its rest location in its parent's space. */
        public Builder at(float x, float y, float z) {
            int o = current() * REST;
            rest[o] = x;
            rest[o + 1] = y;
            rest[o + 2] = z;
            return this;
        }

        /** Its rest rotation in its parent's space, a quaternion, normalised here. */
        public Builder rotation(float x, float y, float z, float w) {
            int o = current() * REST + 3;
            float len = (float) Math.sqrt(x * x + y * y + z * z + w * w);
            if (len == 0f) throw new IllegalArgumentException(id + ": " + names.get(at) + "'s rotation is zero");
            rest[o] = x / len;
            rest[o + 1] = y / len;
            rest[o + 2] = z / len;
            rest[o + 3] = w / len;
            return this;
        }

        public Builder scale(float x, float y, float z) {
            int o = current() * REST + 7;
            rest[o] = x;
            rest[o + 1] = y;
            rest[o + 2] = z;
            return this;
        }

        public Builder scale(float s) {
            return scale(s, s, s);
        }

        /** Its own opacity, 0 to 1, which its parts draw at. */
        public Builder opacity(float a) {
            opacity.set(current(), a);
            return this;
        }

        /** A material value of its own, over each of its parts' materials: CC2's per-clump material values. */
        public Builder value(String property, float... v) {
            if (v.length == 0) throw new IllegalArgumentException(id + ": " + names.get(current()) + "'s " + property + " has no value");
            values.get(current()).put(property, v.clone());
            return this;
        }

        /** A mesh it draws with {@code material}: one definition is one material in every part. */
        public Builder part(CgMaterialDefinition material, CgMeshLods mesh) {
            parts.get(current()).add(Part.of(List.of(material), new Mesh(Objects.requireNonNull(mesh, "mesh")), Map.of()));
            return this;
        }

        public Builder part(CgMaterialDefinition material, CgMesh mesh) {
            return part(material, CgMeshLods.of(Objects.requireNonNull(mesh, "mesh")));
        }

        /** As {@link #part(CgMaterialDefinition, CgMeshLods)}, a file's mesh loaded at its first draw. */
        public Builder part(CgMaterialDefinition material, String path) {
            return part(List.of(material), path, Map.of());
        }

        /**
         * A mesh slot as its file writes it: its materials (one, or one a section of the mesh), and its other fields
         * ({@code cast_shadow}, {@code overlay}...), kept for what draws them.
         */
        public Builder part(List<CgMaterialDefinition> materials, String path, Map<String, Object> settings) {
            parts.get(current()).add(Part.of(materials, new Mesh(Objects.requireNonNull(path, "path")), settings));
            return this;
        }

        /** The unit sphere a VFX look draws: a scene spawning the prefab refuses it. */
        public Builder part(CgMaterialDefinition material) {
            parts.get(current()).add(Part.of(List.of(material), null, Map.of()));
            return this;
        }

        public CgPrefab build() {
            if (names.isEmpty()) throw new IllegalStateException(id + " has no nodes");
            return new CgPrefab(this);
        }

        private int current() {
            if (at < 0) throw new IllegalStateException(id + ": name a node first");
            return at;
        }
    }
}
