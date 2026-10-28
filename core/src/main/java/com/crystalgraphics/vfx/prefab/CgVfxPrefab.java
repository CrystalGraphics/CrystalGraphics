package com.crystalgraphics.vfx.prefab;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialDefinitions;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshLoader;
import com.crystalgraphics.api.mesh.CgMeshLods;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.gl.material.CgMaterialProperty;
import com.crystalgraphics.vfx.look.CgVfxKit;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.look.CgVfxSlot;
import com.crystalgraphics.vfx.render.CgVfxSlotRenderer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A model made of parts, reused by many effects: a tree of nodes, each with a parent, a rest transform, an opacity,
 * material values of its own and the meshes it draws, each with a material. Unreal's actor and its component tree; CC2's
 * clump. An effect plays one through a {@link CgVfxPrefabInstance}, and its look takes the prefab's {@link #kit()}; a
 * sequence spawns one through {@link CgVfxPrefabSpawner}. Its file is a {@code prefabs/} asset
 * ({@link CgVfxPrefabAssets}).
 *
 * <pre>{@code
 * static final CgMaterialDefinition LENS = CgMaterialDefinition.of("mymod:materials/lens",
 *         "mymod:shaders/vfx/lens.shader").build();
 *
 * static final CgVfxPrefab CORE = CgVfxPrefab.builder("mymod:core")
 *         .node("ball03")
 *         .node("ball02").parent("ball03").at(0f, 1f, 0f).part(LENS, "mymod:models/core/ball02.glb")
 *         .node("ball01").parent("ball02").scale(0.5f).opacity(0.5f).value("_Glare", 0.2f)
 *             .part(LENS, "mymod:models/core/ball01.glb")
 *         .build();
 *
 * static final CgVfxLook LOOK = CgVfxLook.builder(SCHEMA).with(CORE.kit()).build();
 * }</pre>
 *
 * <p>A node's material values keyed while it plays are parameters of a variant made for them:</p>
 * <pre>{@code
 * CgVfxPrefab keyed = CORE.keyed(Map.of("ball01", List.of("_Glare", "_UV0Window")));
 * int ball01 = keyed.node("ball01");
 * effect.set((CgVfxParam.Scalar) keyed.param(ball01, "_Glare"), 0.5f);                  // what a material track writes
 * effect.set((CgVfxParam.Color) keyed.param(ball01, "_UV0Window"), 0f, -0.5f, 1f, 1f);   // a vec4, its floats raw
 * }</pre>
 *
 * <ul>
 *   <li>A material is one slot renderer however many parts draw with it; a node with values of its own draws a material
 *       derived with them, shared by every node with the same. A look draws at most 256.</li>
 *   <li>A parent is named before its children: nodes are kept in that order, which is the order they evaluate in.</li>
 *   <li>Names are unique within a prefab. A node's opacity is its own: a parent's never fades its children.</li>
 *   <li>A mesh path loads at its first draw, so a server naming one loads nothing.</li>
 *   <li>A mesh slot is kept as written ({@link #partMaterials}, {@link #partSettings}): one drawing several materials, or
 *       setting an overlay or a shadow, compiles but is refused where it would be drawn ({@link #requireDrawable}).</li>
 * </ul>
 */
public final class CgVfxPrefab {

    /** A node drawn at less presence than this draws nothing. */
    public static final float MIN_OPACITY = 1e-3f;
    /** Floats of a node's rest: location xyz, rotation as a quaternion xyzw, scale xyz. */
    static final int REST = 10;
    private static final AtomicInteger VARIANTS = new AtomicInteger();

    private final String id;
    private final String[] names, ids;
    private final int[] parents;
    private final float[] rest;
    private final float[] opacity;
    private final List<Map<String, float[]>> values;
    /** Node i's parts are {@code parts[partStart[i]]} to {@code partStart[i + 1]}. */
    private final int[] partStart;
    private final Part[] parts;
    private final CgVfxSlot[] partSlots;
    private final Map<String, Integer> byName;
    private final CgVfxSchema schema;
    /** Per node, its keyed material values' parameters by property (a scalar, or a colour holding a vec4); null for none. */
    private final Map<String, CgVfxParam>[] params;
    /** Per node, each keyed property's value before any track; null where none is keyed. */
    private final Map<String, float[]>[] keyedRest;
    /** Per node, its keyed properties in order: a slot's place. */
    private final List<String>[] keyedNames;
    private final CgVfxKit kit;
    /** What it was built from, for its variants. */
    private final Builder builder;
    @Nullable
    private volatile CgVfxLook look;

    @SuppressWarnings("unchecked")
    private CgVfxPrefab(Builder b, @Nullable Map<String, ? extends Collection<String>> keyed) {
        int n = b.names.size();
        this.id = b.id;
        this.builder = b;
        this.names = b.names.toArray(new String[0]);
        this.ids = b.ids.toArray(new String[0]);
        this.parents = new int[n];
        this.rest = Arrays.copyOf(b.rest, n * REST);
        this.opacity = new float[n];
        this.values = new ArrayList<>(b.values);
        this.byName = new HashMap<>(b.byName);
        this.partStart = new int[n + 1];
        this.schema = CgVfxSchema.of(id + "#" + VARIANTS.incrementAndGet());
        this.params = new Map[n];
        this.keyedNames = new List[n];
        this.keyedRest = new Map[n];
        if (keyed != null) {
            for (Map.Entry<String, ? extends Collection<String>> e : keyed.entrySet()) {
                Integer node = byName.get(e.getKey());
                if (node == null) throw new CgAssetException(id + " has no node " + e.getKey());
                Map<String, CgVfxParam> own = new LinkedHashMap<>();
                Map<String, float[]> rests = new LinkedHashMap<>();
                for (String property : e.getValue()) {
                    float[] r = restValue(b, node, property);
                    String name = e.getKey() + "/" + property;
                    // A vec4 binds as a colour's four floats, written raw: how a row takes a vector property.
                    own.put(property, r.length == 1 ? schema.scalar(name, r[0]) : schema.color(name, 0));
                    rests.put(property, r);
                }
                params[node] = own;
                keyedRest[node] = rests;
                keyedNames[node] = List.copyOf(own.keySet());
            }
        }
        Map<CgMaterialDefinition, CgVfxSlot> shared = new IdentityHashMap<>();
        CgVfxKit.Builder kit = CgVfxKit.of(id);
        List<CgVfxSlot> slots = new ArrayList<>();
        List<Part> all = new ArrayList<>();
        int renderers = 0;
        for (int i = 0; i < n; i++) {
            parents[i] = b.parents.get(i);
            opacity[i] = b.opacity.get(i);
            partStart[i] = slots.size();
            // A keyed node binds its own slot renderers; the rest share one a material.
            Map<CgMaterialDefinition, CgVfxSlot> own = params[i] == null ? shared : new IdentityHashMap<>();
            for (Part part : b.parts.get(i)) {
                CgMaterialDefinition material = drawn(part.materials.get(0), values.get(i));
                CgVfxSlot slot = own.get(material);
                if (slot == null) {
                    slot = CgVfxSlot.mesh(id + "#" + renderers);
                    CgVfxSlotRenderer.Builder r = CgVfxSlotRenderer.of("part" + renderers, slot).material(material);
                    if (params[i] != null) {
                        for (Map.Entry<String, CgVfxParam> p : params[i].entrySet()) r.bind(p.getKey(), p.getValue());
                    }
                    kit.slot(r.build());
                    own.put(material, slot);
                    renderers++;
                }
                slots.add(slot);
                all.add(part);
            }
        }
        partStart[n] = slots.size();
        this.partSlots = slots.toArray(new CgVfxSlot[0]);
        this.parts = all.toArray(new Part[0]);
        this.kit = kit.params(schema).build();
    }

    /** {@code material} with a node's values over its own: the same definition for the same values. */
    private static CgMaterialDefinition drawn(CgMaterialDefinition material, Map<String, float[]> values) {
        if (values.isEmpty()) return material;
        Map<String, Object> written = new LinkedHashMap<>();
        for (Map.Entry<String, float[]> v : values.entrySet()) written.put(v.getKey(), CgVfxPrefabAssets.number(v.getValue()));
        return CgMaterialDefinitions.derive(material, Map.of("values", written));
    }

    /** A keyed property's value before any track: the node's own, else its material's, else its shader's default. */
    private static float[] restValue(Builder b, int node, String property) {
        float[] own = b.values.get(node).get(property);
        if (own != null) return own.clone();
        for (Part part : b.parts.get(node)) {
            CgMaterialDefinition material = part.materials.get(0);
            float[] constant = material.constants().get(property);
            if (constant != null) return constant.clone();
            for (CgMaterialProperty p : CgMaterial.newInstance(material, null).properties()) {
                if (p.getName().equals(property)) return p.getFloatValue();
            }
            throw new CgAssetException(b.id + ": " + b.names.get(node) + " keys " + property + ", which " + material.id()
                    + " does not declare");
        }
        throw new CgAssetException(b.id + ": " + b.names.get(node) + " keys material value " + property + " but draws nothing");
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    /**
     * This prefab with {@code keyed}'s material values (by node name, then property) as parameters of its look, each
     * starting at its rest: what a sequence keying them plays. Built once per set; hold it.
     *
     * @throws CgAssetException naming a node it lacks, or a property neither the node, its material nor its shader gives
     */
    public CgVfxPrefab keyed(Map<String, ? extends Collection<String>> keyed) {
        return keyed.isEmpty() ? this : new CgVfxPrefab(builder, keyed);
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
     * {@code node}'s rest transform into {@code out} (10 floats): location xyz, rotation as a unit quaternion xyzw, scale
     * xyz, in its parent's space. Answers {@code out}.
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

    /**
     * The parameter {@code node}'s {@code property} is keyed through in a {@link #keyed} variant, or null: a
     * {@link CgVfxParam.Scalar} for a number, a {@link CgVfxParam.Color} holding a vector's four floats.
     */
    @Nullable
    public CgVfxParam param(int node, String property) {
        return params[node] == null ? null : params[node].get(property);
    }

    /** {@code node}'s keyed {@code property}'s value before any track: its own, its material's, or its shader's. */
    @Nullable
    public float[] keyedRest(int node, String property) {
        return keyedRest[node] == null ? null : keyedRest[node].get(property);
    }

    /** The material values {@code node} keys through parameters in a {@link #keyed} variant, in order; empty for none. */
    public List<String> keyedNames(int node) {
        return keyedNames[node] == null ? List.of() : keyedNames[node];
    }

    /** The first of {@code node}'s parts; its last is {@code firstPart(node + 1) - 1}. */
    public int firstPart(int node) {
        return partStart[node];
    }

    /** The slot part {@code part} draws on: its material's. */
    public CgVfxSlot partSlot(int part) {
        return partSlots[part];
    }

    /** Part {@code part}'s mesh, loaded the first time it is asked; null draws the unit sphere. */
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

    /** Part {@code part}'s mesh slot's other fields as written ({@code overlay}, {@code cast_shadow}...); empty for none. */
    public Map<String, Object> partSettings(int part) {
        return parts[part].settings;
    }

    /** A slot renderer per material its parts draw with, and its keyed parameters: what a look takes to draw it. */
    public CgVfxKit kit() {
        return kit;
    }

    /** A look of its kit alone: what an effect playing it by itself draws with. */
    public CgVfxLook look() {
        CgVfxLook l = look;
        if (l == null) look = l = CgVfxLook.builder(schema).with(kit).build();
        return l;
    }

    /**
     * Throws, naming {@code who} and the part, where a part draws what is not built yet: several materials, one a
     * section of its mesh (a character's), or an overlay, a shadow or a stencil its slot sets.
     */
    public void requireDrawable(String who) {
        for (int node = 0; node < names.length; node++) {
            for (int k = partStart[node]; k < partStart[node + 1]; k++) {
                Part part = parts[k];
                if (part.materials.size() != 1) {
                    throw new CgAssetException(who + ": " + id + "'s node " + names[node] + " draws " + part.materials.size()
                            + " materials, one a section of its mesh, which a prefab does not draw yet");
                }
                for (Map.Entry<String, Object> s : part.settings.entrySet()) {
                    if (!Boolean.FALSE.equals(s.getValue())) {
                        throw new CgAssetException(who + ": " + id + "'s node " + names[node] + "'s mesh sets " + s.getKey()
                                + ", which a prefab does not draw yet");
                    }
                }
            }
        }
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
        final List<CgMaterialDefinition> materials;
        final Mesh mesh;
        final Map<String, Object> settings;

        Part(List<CgMaterialDefinition> materials, Mesh mesh, Map<String, Object> settings) {
            if (materials.isEmpty()) throw new IllegalArgumentException("a part draws a material");
            for (CgMaterialDefinition m : materials) Objects.requireNonNull(m, "material");
            this.materials = List.copyOf(materials);
            this.mesh = mesh;
            this.settings = Map.copyOf(settings);
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
            parts.get(current()).add(new Part(List.of(material), new Mesh(Objects.requireNonNull(mesh, "mesh")), Map.of()));
            return this;
        }

        public Builder part(CgMaterialDefinition material, CgMesh mesh) {
            return part(material, CgMeshLods.of(Objects.requireNonNull(mesh, "mesh")));
        }

        /** As {@link #part(CgMaterialDefinition, CgMeshLods)}, a file or provided mesh loaded at its first draw. */
        public Builder part(CgMaterialDefinition material, String path) {
            return part(List.of(material), path, Map.of());
        }

        /**
         * A mesh slot as its file writes it: its materials (one, or one a section of the mesh), and its other fields
         * ({@code overlay}, {@code cast_shadow}...), kept for what draws them.
         */
        public Builder part(List<CgMaterialDefinition> materials, String path, Map<String, Object> settings) {
            parts.get(current()).add(new Part(materials, new Mesh(Objects.requireNonNull(path, "path")), settings));
            return this;
        }

        /** The unit sphere. */
        public Builder part(CgMaterialDefinition material) {
            parts.get(current()).add(new Part(List.of(material), null, Map.of()));
            return this;
        }

        public CgVfxPrefab build() {
            if (names.isEmpty()) throw new IllegalStateException(id + " has no nodes");
            return new CgVfxPrefab(this, null);
        }

        private int current() {
            if (at < 0) throw new IllegalStateException(id + ": name a node first");
            return at;
        }
    }
}
