package com.crystalgraphics.vfx.prefab;

import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshLoader;
import com.crystalgraphics.api.mesh.CgMeshLods;
import com.crystalgraphics.vfx.look.CgVfxKit;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.look.CgVfxSlot;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A model made of parts, reused by many effects: a tree of nodes, each with a parent, a rest transform, an opacity and
 * the meshes it draws, each with a material. Unreal's actor and its component tree; CC2's clump. An effect plays one
 * through a {@link CgVfxPrefabInstance}, and its look takes the prefab's {@link #kit()}.
 *
 * <pre>{@code
 * static final CgVfxLayer.Builder LENS = CgVfxLayer.of("mymod:shaders/vfx/lens.shader");   // a material, no slot
 *
 * static final CgVfxPrefab CORE = CgVfxPrefab.builder("mymod:core")
 *         .node("ball03")
 *         .node("ball02").parent("ball03").at(0f, 1f, 0f).part(LENS, "mymod:models/core/ball02.glb")
 *         .node("ball01").parent("ball02").scale(0.5f).opacity(0.5f).part(LENS, "mymod:models/core/ball01.glb")
 *         .build();
 *
 * static final CgVfxLook LOOK = CgVfxLook.builder(SCHEMA).with(CORE.kit()).build();
 * }</pre>
 *
 * <ul>
 *   <li>A material is one layer however many parts draw with it: the kit holds a layer per distinct material, built on
 *       a slot of the prefab's own, so give it no slot. A look draws at most 256 layers.</li>
 *   <li>A parent is named before its children: nodes are kept in that order, which is the order they evaluate in.</li>
 *   <li>Names are unique within a prefab. A node's opacity multiplies down its subtree: half a parent's and half its
 *       own draws a quarter.</li>
 *   <li>A mesh path loads at its first draw, so a server naming one loads nothing.</li>
 * </ul>
 */
public final class CgVfxPrefab {

    /** A node drawn at less presence than this, after its ancestors', draws nothing. */
    public static final float MIN_OPACITY = 1e-3f;
    /** Floats of a node's rest: location xyz, rotation as a quaternion xyzw, scale xyz. */
    static final int REST = 10;

    private final String id;
    private final String[] names;
    private final int[] parents;
    private final float[] rest;
    private final float[] opacity;
    /** Node i's parts are {@code partSlots[partStart[i]]} to {@code partStart[i + 1]}. */
    private final int[] partStart;
    private final CgVfxSlot[] partSlots;
    private final Mesh[] partMeshes;
    private final Map<String, Integer> byName;
    private final CgVfxKit kit;

    private CgVfxPrefab(Builder b) {
        int n = b.names.size();
        this.id = b.id;
        this.names = b.names.toArray(new String[0]);
        this.parents = new int[n];
        this.rest = Arrays.copyOf(b.rest, n * REST);
        this.opacity = new float[n];
        this.byName = new HashMap<>(b.byName);
        this.partStart = new int[n + 1];
        Map<CgVfxLayer.Builder, CgVfxSlot> materials = new IdentityHashMap<>();
        CgVfxKit.Builder kit = CgVfxKit.of(id);
        List<CgVfxSlot> slots = new ArrayList<>();
        List<Mesh> meshes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            parents[i] = b.parents.get(i);
            opacity[i] = b.opacity.get(i);
            partStart[i] = slots.size();
            for (Part part : b.parts.get(i)) {
                CgVfxSlot slot = materials.get(part.material);
                if (slot == null) {
                    slot = CgVfxSlot.mesh(id + "#" + materials.size());
                    materials.put(part.material, slot);
                    kit.layer(part.material.on(slot).build());
                }
                slots.add(slot);
                meshes.add(part.mesh);
            }
        }
        partStart[n] = slots.size();
        this.partSlots = slots.toArray(new CgVfxSlot[0]);
        this.partMeshes = meshes.toArray(new Mesh[0]);
        this.kit = kit.build();
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

    /** {@code node}'s parent, -1 for a root; always lower than {@code node}. */
    public int parent(int node) {
        return parents[node];
    }

    /** The node of that name, or -1. */
    public int node(String name) {
        Integer i = byName.get(name);
        return i == null ? -1 : i;
    }

    /** {@code node}'s own opacity at rest, before its ancestors'. */
    public float opacity(int node) {
        return opacity[node];
    }

    /** The first of {@code node}'s parts; its last is {@code firstPart(node + 1) - 1}. */
    public int firstPart(int node) {
        return partStart[node];
    }

    /** The slot part {@code part} draws on: its material's. */
    public CgVfxSlot partSlot(int part) {
        return partSlots[part];
    }

    /** Part {@code part}'s mesh, loaded the first time it is asked; null draws its material's own, else the sphere. */
    @Nullable
    public CgMeshLods partMesh(int part) {
        Mesh mesh = partMeshes[part];
        return mesh == null ? null : mesh.lods();
    }

    /** A layer per material its parts draw with: what a look takes to draw it. */
    public CgVfxKit kit() {
        return kit;
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
            if (l == null) lods = l = CgMeshLods.of(CgMeshLoader.load(path));
            return l;
        }
    }

    private static final class Part {
        final CgVfxLayer.Builder material;
        final Mesh mesh;

        Part(CgVfxLayer.Builder material, Mesh mesh) {
            this.material = Objects.requireNonNull(material, "material");
            this.mesh = mesh;
        }
    }

    /** Builds a prefab node by node; each call after {@link #node} sets that node. */
    public static final class Builder {
        private final String id;
        private final List<String> names = new ArrayList<>();
        private final List<Integer> parents = new ArrayList<>();
        private final List<Float> opacity = new ArrayList<>();
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
            parents.add(-1);
            opacity.add(1f);
            parts.add(new ArrayList<>());
            if (rest.length < (at + 1) * REST) rest = Arrays.copyOf(rest, rest.length * 2);
            int o = at * REST;
            rest[o + 6] = 1f;
            rest[o + 7] = rest[o + 8] = rest[o + 9] = 1f;
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

        /** Its own opacity, 0 to 1, multiplied by its ancestors'. */
        public Builder opacity(float a) {
            opacity.set(current(), a);
            return this;
        }

        /** A mesh it draws with {@code material}: the same builder object is the same material in every part. */
        public Builder part(CgVfxLayer.Builder material, CgMeshLods mesh) {
            parts.get(current()).add(new Part(material, new Mesh(Objects.requireNonNull(mesh, "mesh"))));
            return this;
        }

        public Builder part(CgVfxLayer.Builder material, CgMesh mesh) {
            return part(material, CgMeshLods.of(Objects.requireNonNull(mesh, "mesh")));
        }

        /** As {@link #part(CgVfxLayer.Builder, CgMeshLods)}, an OBJ or glTF file loaded at its first draw. */
        public Builder part(CgVfxLayer.Builder material, String path) {
            parts.get(current()).add(new Part(material, new Mesh(Objects.requireNonNull(path, "path"))));
            return this;
        }

        /** Its material's own mesh, else the unit sphere. */
        public Builder part(CgVfxLayer.Builder material) {
            parts.get(current()).add(new Part(material, null));
            return this;
        }

        public CgVfxPrefab build() {
            if (names.isEmpty()) throw new IllegalStateException(id + " has no nodes");
            return new CgVfxPrefab(this);
        }

        private int current() {
            if (at < 0) throw new IllegalStateException(id + ": name a node first");
            return at;
        }
    }
}
