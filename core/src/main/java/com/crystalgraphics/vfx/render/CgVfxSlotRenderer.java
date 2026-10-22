package com.crystalgraphics.vfx.render;

import com.crystalgraphics.api.material.CgMaterialArchetypes;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.mesh.CgMeshLoader;
import com.crystalgraphics.api.mesh.CgMeshLods;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.gl.material.parse.CgParsedShader;
import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.vfx.look.CgVfxSlot;
import com.crystalgraphics.vfx.look.CgVfxLayout;
import com.crystalgraphics.vfx.look.CgVfxParam;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a native behaviour's slot draws: a material on the slot, fed by user parameters. A system's {@code slots:}
 * entry, or one built in Java into a look or a kit; the particle renderers' counterpart for what an effect's own code
 * places (a beam's body, a charge ball, a trail).
 *
 * <pre>{@code
 * // In Java: the same tree a system file's slots: entry holds
 * CgVfxSlotRenderer shell = CgVfxSlotRenderer.of("l1", CgBeamBody.BODY).material(SHELL_MATERIAL)
 *         .bind("_Shell", "user.shell").size(1.15f).build();
 * CgVfxLook look = CgEnergyWave.kamehameha().derive().slot(shell).build();
 *
 * // From a file:   slots: [ { id: l1, slot: crystalgraphics:wave/body, material: mymod:materials/shell,
 * //                           bind: { _Shell: user.shell }, size: 1.15 } ]
 * CgVfxSlotRenderer loaded = CgVfxSlotRenderer.compile("mymod:wave", entry, deps);
 * }</pre>
 *
 * <ul>
 *   <li>Its slot is found by name among the slots the engine's and a mod's behaviours declare; an unknown one fails.</li>
 *   <li>A tube slot draws the geometry its material's archetype names ({@code Tags { "Tube" = "Volume" | "Closed" }});
 *       a Tube-tagged archetype on another slot fails at load, unless it draws tubes as a variant
 *       ({@code #pragma cg_feature FX_ON_TUBE}) beside its own kind.</li>
 *   <li>Binds read user parameters alone, as a particle renderer's do; {@code size} is times the slot's own.</li>
 *   <li>Drawn in the look's order of its slot renderers, then its material's {@code order}; after its effect's
 *       distortion unless its material has a Distortion pass.</li>
 * </ul>
 */
public final class CgVfxSlotRenderer {

    private final String id;
    private final CgVfxSlot slot;
    private final String material;
    @Nullable private final CgMaterialDefinition definition;
    private final Map<String, Object> bind;
    private final float size;
    @Nullable private final String meshPath;
    @Nullable private final CgQuality quality;
    private final Map<String, Object> tree;

    private CgVfxSlotRenderer(String id, CgVfxSlot slot, String material, @Nullable CgMaterialDefinition definition,
                              Map<String, Object> bind, float size, @Nullable String meshPath,
                              @Nullable CgQuality quality, Map<String, Object> tree) {
        this.id = id;
        this.slot = slot;
        this.material = material;
        this.definition = definition;
        this.bind = bind;
        this.size = size;
        this.meshPath = meshPath;
        this.quality = quality;
        this.tree = tree;
    }

    /** A slot renderer of id {@code id} on {@code slot}, built as its file entry would read. */
    public static Builder of(String id, CgVfxSlot slot) {
        return new Builder(id, slot);
    }

    /**
     * {@code system}'s {@code slots:} entry; {@code deps} answers its material, a hard reference its asset compiled
     * first, null leaving {@link #definition()} to the caller's ask.
     *
     * @throws IllegalArgumentException naming the entry: an unknown slot, a mesh on a slot that takes none, a bind that
     *                                  reads no user parameter, a Tube-tagged material off a tube slot
     */
    public static CgVfxSlotRenderer compile(String system, Map<?, ?> entry, @Nullable CgAssetCompiler.Dependencies deps) {
        Map<String, Object> tree = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : entry.entrySet()) tree.put(String.valueOf(e.getKey()), e.getValue());
        String who = system + "'s slot " + tree.get("id");
        if (!(tree.get("id") instanceof String id)) throw new IllegalArgumentException(who + " has no id");
        if (!(tree.get("slot") instanceof String name)) throw new IllegalArgumentException(who + " names no slot");
        CgVfxSlot slot = CgVfxSlot.named(name);
        if (slot == null) throw new IllegalArgumentException(who + " draws on slot " + name + ", which no behaviour declares");
        if (!(tree.get("material") instanceof String material)) {
            throw new IllegalArgumentException(who + " names no material asset");
        }
        CgMaterialDefinition definition = null;
        if (deps != null) {
            Object d = deps.definition(CgLookKinds.MATERIAL, material);
            if (d instanceof CgMaterialDefinition md) definition = md;
            else if (d != null) throw new IllegalArgumentException(who + "'s material " + material + " is not a material");
        }
        if (definition != null && definition.tube() != null && slot.kind() != CgVfxSlot.Kind.TUBE
                && !CgMaterialArchetypes.FILES.parse(definition.permutation().shader()).featureNames()
                        .contains(CgVfxSlot.Kind.TUBE.keyword())) {
            throw new IllegalArgumentException(who + " draws " + material + ", whose archetype needs a tube (\"Tube\" = \""
                    + definition.tube() + "\"), on " + slot);
        }
        Map<String, Object> bind = new LinkedHashMap<>();
        if (tree.get("bind") instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> b : m.entrySet()) {
                Object form = b.getValue();
                String from = String.valueOf(form instanceof Map<?, ?> f ? f.get("bind") : form);
                if (!from.startsWith("user.")) {
                    throw new IllegalArgumentException(who + " binds " + b.getKey() + " to " + from + ": a bind reads a user"
                            + " parameter, user.<name>");
                }
                bind.put(String.valueOf(b.getKey()), form);
            }
        }
        float size = tree.get("size") instanceof Number n ? n.floatValue() : 1f;
        String meshPath = null;
        if (tree.get("mesh") instanceof String path) {
            if (slot.kind() != CgVfxSlot.Kind.MESH) throw new IllegalArgumentException(who + " gives a mesh to " + slot + ", which takes none");
            meshPath = path;
        } else if (tree.get("mesh") != null) {
            throw new IllegalArgumentException(who + "'s mesh is a path; a bound mesh is not built yet");
        }
        CgQuality quality = tree.get("quality") instanceof String q ? CgQuality.valueOf(q.toUpperCase(Locale.ROOT)) : null;
        return new CgVfxSlotRenderer(id, slot, material, definition, Collections.unmodifiableMap(bind), size, meshPath,
                quality, Collections.unmodifiableMap(tree));
    }

    public String id() {
        return id;
    }

    public CgVfxSlot slot() {
        return slot;
    }

    /** Its material asset's id. */
    public String material() {
        return material;
    }

    /** Its material compiled; null where it compiled with no dependencies to ask. */
    @Nullable
    public CgMaterialDefinition definition() {
        return definition;
    }

    /** Material parameter to the user parameter it reads per draw, {@code user.<name>} or {@code { bind, scale }}. */
    public Map<String, Object> bind() {
        return bind;
    }

    /** Times its slot's own size. */
    public float size() {
        return size;
    }

    /** The tier it draws from; null for every tier. */
    @Nullable
    public CgQuality quality() {
        return quality;
    }

    /** The tube geometry its material's archetype needs; null for the chunked tube or no definition. */
    @Nullable
    public CgParsedShader.Tube tube() {
        return definition == null ? null : definition.tube();
    }

    /** Its mesh on a mesh slot, loaded at its first ask and kept; null for the slot's sphere. */
    @Nullable
    public CgMeshLods mesh() {
        return meshPath == null ? null : CgMeshLoader.lods(meshPath);
    }

    /** The entry as its file holds it. */
    public Map<String, Object> tree() {
        return tree;
    }

    @Override
    public String toString() {
        return "slot renderer " + id + " on " + slot + " (" + material + ")";
    }

    /** Writes a {@code slots:} entry as a file would hold it, then compiles it. */
    public static final class Builder {
        private final Map<String, Object> tree = new LinkedHashMap<>();
        private final Map<String, Object> bind = new LinkedHashMap<>();
        private CgMaterialDefinition definition;

        private Builder(String id, CgVfxSlot slot) {
            tree.put("id", id);
            tree.put("slot", slot.name());
        }

        /** Its material, compiled: its id is written. */
        public Builder material(CgMaterialDefinition material) {
            definition = material;
            tree.put("material", material.id());
            return this;
        }

        /** Material parameter {@code parameter} read per draw from user parameter {@code source}, {@code user.<name>}. */
        public Builder bind(String parameter, String source) {
            bind.put(parameter, source);
            return this;
        }

        /** Material parameter {@code parameter} read per draw from {@code param}, bound by its full name. */
        public Builder bind(String parameter, CgVfxParam param) {
            return bind(parameter, "user." + CgVfxLayout.fullName(param));
        }

        /** Material parameter {@code parameter} read per draw from {@code param} times {@code scale}. */
        public Builder bind(String parameter, CgVfxParam param, float scale) {
            Map<String, Object> form = new LinkedHashMap<>();
            form.put("bind", "user." + CgVfxLayout.fullName(param));
            form.put("scale", (double) scale);
            bind.put(parameter, form);
            return this;
        }

        /** Times its slot's own size. */
        public Builder size(float size) {
            tree.put("size", (double) size);
            return this;
        }

        /** A mesh slot's mesh, by path or {@link CgMeshLoader#provide}d id, in place of the sphere. */
        public Builder mesh(String path) {
            tree.put("mesh", path);
            return this;
        }

        /** Drawn from {@code quality} up. */
        public Builder quality(CgQuality quality) {
            tree.put("quality", quality.name().toLowerCase(Locale.ROOT));
            return this;
        }

        /** The slot renderer, compiled from the entry written, in the declaration's key order. */
        public CgVfxSlotRenderer build() {
            Map<String, Object> entry = new LinkedHashMap<>();
            for (String key : List.of("id", "slot", "material")) if (tree.containsKey(key)) entry.put(key, tree.get(key));
            if (!bind.isEmpty()) entry.put("bind", new LinkedHashMap<>(bind));
            for (String key : List.of("size", "mesh", "quality")) if (tree.containsKey(key)) entry.put(key, tree.get(key));
            CgMaterialDefinition d = definition;
            return compile("java", entry, d == null ? null : (kind, id) -> id.equals(d.id()) ? d : null);
        }
    }
}
