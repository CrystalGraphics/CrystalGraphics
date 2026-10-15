package com.crystalgraphics.vfx;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.shader.CgShaderBindings;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gl.buffer.shader.CgPropertyRows;
import com.crystalgraphics.gl.material.CgMaterialProperty;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.look.CgVfxLayout;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSlot;
import com.crystalgraphics.vfx.look.CgVfxValues;
import com.crystalgraphics.vfx.gas.CgVfxGasAtlas;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A system's layers made drawable: each layer's properties resolved against each look it is in (set, bound, bound by
 * name, or the shader's), a material per layer, slot kind and binding pattern, and every draw's row of bound values
 * written once a frame per effect and layer into {@link CgPropertyRows}. Render thread.
 */
final class CgVfxMaterials {

    /** What a layer resolved to in one look: where each bound value comes from, and its materials by kind. */
    static final class Resolved {
        final CgVfxLook.Placed placed;
        final CgVfxLayer layer;
        /** Per bound value: its float offset in the look's values, its floats (1, 4, or 4 an entry), its factor. */
        final int[] source, floats;
        final float[] factor;
        /** The row's length in vec4s. */
        final int vec4s;
        /** Each bound property's place in the row, by name: what makes a material's slot ints. */
        final Map<String, Integer> slots;
        /** The signature its materials are shared by: the bound properties and their places. */
        final String signature;
        /** Where its size parameter is, -1 for none. */
        final int sizeAt;
        final CgMaterial[] byKind = new CgMaterial[CgVfxSlot.Kind.values().length];
        /** Its trails layer's materials by pool, each bound to that pool's history ({@link #material(Resolved, CgVfxParticlePool)}). */
        final Map<CgVfxParticlePool, CgMaterial> byPool = new IdentityHashMap<>();
        final int[] keyByKind = new int[CgVfxSlot.Kind.values().length];
        /** The layer's {@code tunings()} its materials' constants were last set at. */
        int tunings;

        Resolved(CgVfxLook.Placed placed, int[] source, int[] floats, float[] factor, int vec4s, Map<String, Integer> slots,
                 String signature, int sizeAt) {
            this.placed = placed;
            this.layer = placed.layer();
            this.source = source;
            this.floats = floats;
            this.factor = factor;
            this.vec4s = vec4s;
            this.slots = slots;
            this.signature = signature;
            this.sizeAt = sizeAt;
        }
    }

    /** Every material made, by layer, then by kind and signature. */
    private final Map<CgVfxLayer, Map<String, CgMaterial>> materials = new IdentityHashMap<>();
    /** A probe material per layer, made to read what its shader declares. */
    private final Map<CgVfxLayer, CgMaterial> probes = new IdentityHashMap<>();
    private final List<CgMaterial> all = new ArrayList<>();
    private int nextKey;
    /** This frame's rows, four floats a vec4, and how many vec4s are written. */
    private float[] rows = new float[256];
    private int rowCount;
    private long frame = -1;

    /** Each look's layers as this system resolved them, by their order; made at each one's first draw. */
    private final Map<CgVfxLook, Resolved[]> resolved = new IdentityHashMap<>();
    /** The look asked for last, and its array: one effect's draws ask for one look many times in a row. */
    private CgVfxLook lastLook;
    private Resolved[] lastResolved;

    /** {@code placed}'s resolution in {@code look}, made at its first draw. */
    Resolved resolve(CgVfxLook look, CgVfxLook.Placed placed) {
        if (look != lastLook) {
            lastLook = look;
            lastResolved = resolved.computeIfAbsent(look, l -> new Resolved[l.layers().size()]);
        }
        Resolved r = lastResolved[placed.order()];
        if (r == null) lastResolved[placed.order()] = r = make(look, placed);
        return r;
    }

    private Resolved make(CgVfxLook look, CgVfxLook.Placed placed) {
        CgVfxLayer layer = placed.layer();
        CgMaterial probe = probe(layer);
        List<CgMaterialProperty> props = probe.properties();
        Map<String, CgMaterialProperty> byName = new HashMap<>();
        for (CgMaterialProperty p : props) if (!p.isHidden()) byName.put(p.getName(), p);
        for (String name : layer.constants().keySet()) declared(layer, byName, name, false);
        for (String name : layer.textures().keySet()) {
            if (!declared(layer, byName, name, true).getType().isSampler()) {
                throw new IllegalStateException(layer + ": '" + name + "' is not a sampler");
            }
        }
        Map<String, CgVfxParam> bound = new HashMap<>();
        Map<String, Float> factors = new HashMap<>();
        for (CgVfxLayer.Binding b : layer.bindings()) {
            CgMaterialProperty p = declared(layer, byName, b.property(), false);
            if (!p.isRows()) {
                throw new IllegalStateException(layer + " binds '" + b.property() + "', which its shader cannot read per"
                        + " draw: declare #pragma cg_use rows, and bind a number, a vector, a colour or an array");
            }
            if (!fits(p, b.param())) {
                throw new IllegalStateException(layer + " binds '" + b.property() + "' (" + p.getType() + ") to "
                        + b.param() + ", which does not fit it");
            }
            bound.put(b.property(), b.param());
            factors.put(b.property(), b.factor());
        }
        CgVfxLayout layout = look.layout();
        for (CgMaterialProperty p : props) {
            String name = p.getName();
            if (!p.isRows() || bound.containsKey(name) || layer.constants().containsKey(name)
                    || layer.unbound().contains(name)) {
                continue;
            }
            CgVfxParam match = byPropertyName(layout, name);
            if (match != null && fits(p, match)) {
                bound.put(name, match);
                factors.put(name, 1f);
            }
        }
        // The row: the bound values in the shader's declaration order, a vec4 each, a palette one an entry.
        List<int[]> entries = new ArrayList<>();
        List<Float> entryFactors = new ArrayList<>();
        Map<String, Integer> slots = new HashMap<>();
        StringBuilder signature = new StringBuilder();
        int vec4s = 0;
        for (CgMaterialProperty p : props) {
            CgVfxParam param = bound.get(p.getName());
            if (param == null) continue;
            slots.put(p.getName(), vec4s);
            signature.append(p.getName()).append('@').append(vec4s).append(';');
            int floats = param.floats();
            entries.add(new int[] {layout.offset(param), floats});
            entryFactors.add(factors.get(p.getName()));
            vec4s += param instanceof CgVfxParam.Palette ? ((CgVfxParam.Palette) param).count() : 1;
        }
        int[] source = new int[entries.size()], floats = new int[entries.size()];
        float[] factor = new float[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            source[i] = entries.get(i)[0];
            floats[i] = entries.get(i)[1];
            factor[i] = entryFactors.get(i);
        }
        int sizeAt = layer.sizeParam() == null ? -1 : layout.offset(layer.sizeParam());
        boolean distorts = probe.hasDistortionPass();
        if (distorts != (layer.role() == CgVfxLayer.Role.DISTORTION)) {
            throw new IllegalStateException(layer + (distorts ? " has a Distortion pass: give it role(DISTORTION)"
                    : " has no Distortion pass, yet its role is DISTORTION"));
        }
        return new Resolved(placed, source, floats, factor, vec4s, slots, signature.toString(), sizeAt);
    }

    /**
     * The colour or palette a property reads by name: {@code _ShellHot} reads {@code shellHot}, the effect's schema
     * first. A number never binds by name: a shader's {@code _Speed} is not the effect's {@code speed}.
     */
    private static CgVfxParam byPropertyName(CgVfxLayout layout, String property) {
        String bare = property.startsWith("_") ? property.substring(1) : property;
        if (bare.isEmpty()) return null;
        String name = bare.substring(0, 1).toLowerCase(Locale.ROOT) + bare.substring(1);
        for (CgVfxParam p : layout.params()) {
            if ((p instanceof CgVfxParam.Color || p instanceof CgVfxParam.Palette) && p.name().equals(name)) return p;
        }
        return null;
    }

    /** Whether {@code param} fits {@code property}: a number a float, a colour a vec4, a palette an array. */
    private static boolean fits(CgMaterialProperty property, CgVfxParam param) {
        if (property.isArray()) {
            return param instanceof CgVfxParam.Palette && ((CgVfxParam.Palette) param).count() <= property.getCount();
        }
        switch (property.getType()) {
            case FLOAT:
            case RANGE:
                return param instanceof CgVfxParam.Scalar;
            case COLOR:
            case VEC4:
                return param instanceof CgVfxParam.Color;
            default:
                return false;
        }
    }

    private static CgMaterialProperty declared(CgVfxLayer layer, Map<String, CgMaterialProperty> byName, String name,
                                               boolean sampler) {
        CgMaterialProperty p = byName.get(name);
        if (p == null) {
            throw new IllegalStateException(layer + " names '" + name + "', which its shader does not declare; it"
                    + " declares " + byName.keySet());
        }
        if (!sampler && p.getType().isSampler()) throw new IllegalStateException(layer + ": '" + name + "' is a sampler");
        return p;
    }

    private CgMaterial probe(CgVfxLayer layer) {
        CgMaterial probe = probes.get(layer);
        if (probe == null) {
            probe = CgMaterial.newInstance(layer.shader());
            probes.put(layer, probe);
        }
        return probe;
    }

    /** {@code r}'s material for slots of {@code kind}: made at its first ask, shared by layers resolving alike. */
    CgMaterial material(Resolved r, CgVfxSlot.Kind kind) {
        if (r.tunings != r.layer.tunings()) retune(r);
        CgMaterial material = r.byKind[kind.ordinal()];
        if (material != null) return material;
        if (r.layer.hasMesh() && kind != CgVfxSlot.Kind.MESH && kind != CgVfxSlot.Kind.PARTICLES) {
            throw new IllegalStateException(r.layer + " draws a mesh, which a " + kind + " slot does not take");
        }
        Map<String, CgMaterial> mine = materials.computeIfAbsent(r.layer, l -> new HashMap<>());
        String key = kind + "|" + r.signature;
        material = mine.get(key);
        if (material == null) {
            material = CgMaterial.newInstance(r.layer.shader());
            configure(material, r, kind);
            mine.put(key, material);
            all.add(material);
            keys.put(material, nextKey++ & 0xFFF);
        }
        r.byKind[kind.ordinal()] = material;
        r.keyByKind[kind.ordinal()] = keys.get(material);
        return material;
    }

    /**
     * {@code r}'s particle material for {@code pool}'s trails, its {@code HISTORY} that pool's ({@code trails()} layers):
     * one per pool, since a material's bindings are captured once a stage, so two pools drawing one layer through one
     * material would both read the last history set. Made at its first ask, tuned with the rest.
     */
    CgMaterial material(Resolved r, CgVfxParticlePool pool) {
        material(r, CgVfxSlot.Kind.PARTICLES);
        CgMaterial material = r.byPool.get(pool);
        if (material == null) {
            material = CgMaterial.newInstance(r.layer.shader());
            configure(material, r, CgVfxSlot.Kind.PARTICLES);
            materials.get(r.layer).put(CgVfxSlot.Kind.PARTICLES + "|" + r.signature + "|pool" + (nextPool++), material);
            all.add(material);
            keys.put(material, nextKey++ & 0xFFF);
            r.byPool.put(pool, material);
        }
        material.buffer("HISTORY", pool.history());
        return material;
    }

    private int nextPool;

    /** The gas shade each volume material was last pointed at: the atlas makes it anew when its bricks change size. */
    private final Map<CgMaterial, CgTexture> shades = new IdentityHashMap<>();

    /** Points volume {@code material} at {@code shade}, once each time the atlas remade it. */
    void shade(CgMaterial material, CgTexture shade) {
        if (shades.get(material) == shade) return;
        shades.put(material, shade);
        material.applyProperties(b -> b.sampler(CgVfxGasAtlas.SHADE_SAMPLER, 0, shade));
    }

    /** Each material's batch key, in the order they were made: draws of one material given one key sort together. */
    private final Map<CgMaterial, Integer> keys = new IdentityHashMap<>();

    /** {@code material}'s batch key, 0 to 4095. */
    int key(CgMaterial material) {
        Integer key = keys.get(material);
        return key == null ? 0 : key;
    }

    /** A tuning changed {@code r}'s layer's constants: every material of the layer takes them again. */
    private void retune(Resolved r) {
        r.tunings = r.layer.tunings();
        Map<String, CgMaterial> mine = materials.get(r.layer);
        if (mine == null) return;
        for (CgMaterial material : mine.values()) material.applyProperties(b -> constants(b, r.layer));
    }

    private static void constants(CgShaderBindings b, CgVfxLayer layer) {
        for (String property : layer.constants().keySet()) {
            float[] v = layer.constant(property);
            if (v.length == 1) b.set1f(property, v[0]);
            else if (v.length == 2) b.vec2(property, v[0], v[1]);
            else if (v.length == 4) b.vec4(property, v[0], v[1], v[2], v[3]);
            else b.array(property, v);
        }
    }

    private static void configure(CgMaterial material, Resolved r, CgVfxSlot.Kind kind) {
        CgVfxLayer layer = r.layer;
        List<String> declared = material.declaredKeywords();
        if (declared.contains(kind.keyword())) material.enableKeyword(kind.keyword());
        for (String keyword : layer.keywords()) material.enableKeyword(keyword);
        material.applyProperties(b -> {
            constants(b, layer);
            for (Map.Entry<String, Integer> slot : r.slots.entrySet()) {
                b.set1i(CgMaterialProperty.rowSlotName(slot.getKey()), slot.getValue());
            }
            int unit = 0;
            for (Map.Entry<String, CgTexture> t : layer.textures().entrySet()) {
                b.sampler(t.getKey(), unit++, t.getValue());
            }
        });
    }

    /** Every material made, for the system to bind its path texture on and warm. */
    List<CgMaterial> all() {
        return all;
    }

    /** {@code r}'s size for {@code effect}: its own, times its size parameter's value when it has one. */
    static float size(CgVfxEffect<?> effect, Resolved r) {
        return r.sizeAt < 0 ? r.layer.size() : r.layer.size() * effect.values().raw(r.sizeAt);
    }

    /** Starts a frame's rows. */
    void begin(long frame) {
        this.frame = frame;
        rowCount = 0;
    }

    /**
     * Where {@code effect}'s row for {@code r} starts this frame, in vec4s, writing it at its first ask; 0 for a layer
     * binding nothing, whose shader reads no row.
     */
    int row(CgVfxEffect<?> effect, Resolved r) {
        if (r.vec4s == 0) return 0;
        int index = r.placed.order();
        if (effect.rowsFrame != frame) {
            effect.rowsFrame = frame;
            int layers = effect.look().layers().size();
            if (effect.rows.length < layers) effect.rows = new int[layers];
            Arrays.fill(effect.rows, 0, layers, -1);
        }
        int at = effect.rows[index];
        if (at >= 0) return at;
        at = rowCount;
        int need = (rowCount + r.vec4s) * 4;
        if (rows.length < need) rows = Arrays.copyOf(rows, Math.max(need, rows.length * 2));
        CgVfxValues values = effect.values();
        int write = at * 4;
        for (int i = 0; i < r.source.length; i++) {
            int floats = r.floats[i];
            if (floats == 1) {
                rows[write] = values.raw(r.source[i]) * r.factor[i];
                rows[write + 1] = rows[write + 2] = rows[write + 3] = 0f;
                write += 4;
            } else {
                values.copy(r.source[i], floats, rows, write);
                write += floats;
            }
        }
        rowCount += r.vec4s;
        effect.rows[index] = at;
        return at;
    }

    /** This frame's rows to the GPU: after every effect's submit. */
    void upload() {
        CgPropertyRows.upload(rows, rowCount);
    }

    /** Warms {@code look}'s materials: one per layer and kind it draws on. */
    void prepare(CgVfxLook look, List<CgMaterial> warming) {
        for (CgVfxLook.Placed placed : look.placed()) {
            Resolved r = resolve(look, placed);
            for (CgVfxSlot slot : placed.layer().slots()) {
                CgMaterial material = material(r, slot.kind());
                if (!warming.contains(material)) warming.add(material);
            }
        }
    }

}
