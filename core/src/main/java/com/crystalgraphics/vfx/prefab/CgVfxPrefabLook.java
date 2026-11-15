package com.crystalgraphics.vfx.prefab;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.material.CgMaterialProperty;
import com.crystalgraphics.render.scene.prefab.CgPrefab;
import com.crystalgraphics.vfx.look.CgVfxKit;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.look.CgVfxSlot;
import com.crystalgraphics.vfx.render.CgVfxSlotRenderer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * How a VFX look draws a {@link CgPrefab}: a slot renderer a material its parts draw, and the material values a sequence
 * keys as the look's parameters. What an effect playing a prefab ({@link CgVfxPrefabEffect}) and a particle playing one
 * as a clip draw through; the prefab itself names no VFX.
 *
 * <pre>{@code
 * CgVfxLook look = CgVfxPrefabLook.of(CORE).look();                           // an effect playing it alone
 * CgVfxLook mine = CgVfxLook.builder(SCHEMA).with(CgVfxPrefabLook.of(CORE).kit()).build();   // inside a look of yours
 * }</pre>
 *
 * <p>A node's material values keyed while it plays are parameters of a variant made for them:</p>
 * <pre>{@code
 * CgVfxPrefabLook keyed = CgVfxPrefabLook.of(CORE).keyed(Map.of("ball01", List.of("_Glare", "_UV0Window")));
 * int ball01 = CORE.node("ball01");
 * effect.set((CgVfxParam.Scalar) keyed.param(ball01, "_Glare"), 0.5f);                  // what a material track writes
 * effect.set((CgVfxParam.Color) keyed.param(ball01, "_UV0Window"), 0f, -0.5f, 1f, 1f);   // a vec4, its floats raw
 * }</pre>
 *
 * <ul>
 *   <li>A material is one slot renderer however many parts draw with it; a node with values of its own draws a material
 *       derived with them, shared by every node with the same. A look draws at most 256.</li>
 *   <li>{@link #of} is one look a prefab, made at its first ask; a {@link #keyed} variant is made at each call: hold it.</li>
 * </ul>
 */
public final class CgVfxPrefabLook {

    private static final AtomicInteger VARIANTS = new AtomicInteger();

    private final CgPrefab prefab;
    private final CgVfxSchema schema;
    private final CgVfxSlot[] partSlots;
    /** Per node, its keyed material values' parameters by property (a scalar, or a colour holding a vec4); null for none. */
    private final Map<String, CgVfxParam>[] params;
    /** Per node, each keyed property's value before any track; null where none is keyed. */
    private final Map<String, float[]>[] keyedRest;
    /** Per node, its keyed properties in order: a slot's place. */
    private final List<String>[] keyedNames;
    private final CgVfxKit kit;
    @Nullable
    private volatile CgVfxLook look;

    @SuppressWarnings("unchecked")
    private CgVfxPrefabLook(CgPrefab prefab, @Nullable Map<String, ? extends Collection<String>> keyed) {
        int n = prefab.nodes();
        String id = prefab.id();
        this.prefab = prefab;
        this.schema = CgVfxSchema.of(id + "#" + VARIANTS.incrementAndGet());
        this.params = new Map[n];
        this.keyedNames = new List[n];
        this.keyedRest = new Map[n];
        if (keyed != null) {
            for (Map.Entry<String, ? extends Collection<String>> e : keyed.entrySet()) {
                int node = prefab.node(e.getKey());
                if (node < 0) throw new CgAssetException(id + " has no node " + e.getKey());
                Map<String, CgVfxParam> own = new LinkedHashMap<>();
                Map<String, float[]> rests = new LinkedHashMap<>();
                for (String property : e.getValue()) {
                    float[] r = restValue(prefab, node, property);
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
        int renderers = 0;
        for (int i = 0; i < n; i++) {
            // A keyed node binds its own slot renderers; the rest share one a material.
            Map<CgMaterialDefinition, CgVfxSlot> own = params[i] == null ? shared : new IdentityHashMap<>();
            for (int k = prefab.firstPart(i); k < prefab.firstPart(i + 1); k++) {
                CgMaterialDefinition material = prefab.drawnMaterials(k).get(0);
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
            }
        }
        this.partSlots = slots.toArray(new CgVfxSlot[0]);
        this.kit = kit.params(schema).build();
    }

    /** {@code prefab}'s own look, its material values unkeyed: one a prefab, kept with it. */
    public static CgVfxPrefabLook of(CgPrefab prefab) {
        return prefab.derived(CgVfxPrefabLook.class, p -> new CgVfxPrefabLook(p, null));
    }

    /**
     * This look with {@code keyed}'s material values (by node name, then property) as its parameters, each starting at
     * its rest: what a sequence keying them plays. Made at each call; hold it.
     *
     * @throws CgAssetException naming a node it lacks, or a property neither the node, its material nor its shader gives
     */
    public CgVfxPrefabLook keyed(Map<String, ? extends Collection<String>> keyed) {
        return keyed.isEmpty() ? this : new CgVfxPrefabLook(prefab, keyed);
    }

    /** A keyed property's value before any track: the node's own, else its material's, else its shader's default. */
    private static float[] restValue(CgPrefab prefab, int node, String property) {
        float[] own = prefab.values(node).get(property);
        if (own != null) return own.clone();
        for (int k = prefab.firstPart(node); k < prefab.firstPart(node + 1); k++) {
            CgMaterialDefinition material = prefab.partMaterials(k).get(0);
            float[] constant = material.constants().get(property);
            if (constant != null) return constant.clone();
            for (CgMaterialProperty p : CgMaterial.of(material).properties()) {
                if (p.getName().equals(property)) return p.getFloatValue();
            }
            throw new CgAssetException(prefab.id() + ": " + prefab.name(node) + " keys " + property + ", which "
                    + material.id() + " does not declare");
        }
        throw new CgAssetException(prefab.id() + ": " + prefab.name(node) + " keys material value " + property + " but draws nothing");
    }

    public CgPrefab prefab() {
        return prefab;
    }

    /** The slot part {@code part} draws on: its material's. */
    public CgVfxSlot partSlot(int part) {
        return partSlots[part];
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

    /** A slot renderer a material its parts draw with, and its keyed parameters: what a look takes to draw it. */
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
     * Throws, naming {@code who} and the part, where a part draws what a look does not: several materials, one a
     * section of its mesh (a character's), or an overlay, a shadow or a stencil its slot sets.
     */
    public void requireDrawable(String who) {
        for (int node = 0; node < prefab.nodes(); node++) {
            for (int k = prefab.firstPart(node); k < prefab.firstPart(node + 1); k++) {
                int materials = prefab.partMaterials(k).size();
                if (materials != 1) {
                    throw new CgAssetException(who + ": " + prefab.id() + "'s node " + prefab.name(node) + " draws " + materials
                            + " materials, one a section of its mesh, which a VFX look does not draw: spawn it in a scene");
                }
                for (Map.Entry<String, Object> s : prefab.partSettings(k).entrySet()) {
                    if (!Boolean.FALSE.equals(s.getValue())) {
                        throw new CgAssetException(who + ": " + prefab.id() + "'s node " + prefab.name(node) + "'s mesh sets "
                                + s.getKey() + ", which a VFX look does not draw: spawn it in a scene");
                    }
                }
            }
        }
    }
}
