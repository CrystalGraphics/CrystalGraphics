package com.crystalgraphics.vfx;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialDefinitions;
import com.crystalgraphics.api.material.CgMaterialUsage;
import com.crystalgraphics.api.shader.CgShaderBindings;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gl.buffer.shader.CgPropertyRows;
import com.crystalgraphics.gl.material.CgMaterialShader;
import com.crystalgraphics.gl.material.CgMaterialShaderRegistry;
import com.crystalgraphics.gl.material.CgMaterialProperty;
import com.crystalgraphics.vfx.look.CgVfxLayout;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSlot;
import com.crystalgraphics.vfx.look.CgVfxValues;
import com.crystalgraphics.vfx.fluid.CgVfxFluidAtlas;
import com.crystalgraphics.vfx.grid.CgVfxGridAtlas;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRendererProperties;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRenderers;
import com.crystalgraphics.vfx.particle.renderer.CgVfxSpriteRendererProperties;
import com.crystalgraphics.vfx.render.CgVfxSlotRenderer;
import com.crystalgraphics.vfx.look.CgVfxFlipbook;
import com.crystalgraphics.vfx.prefab.CgVfxPrefab;
import com.crystalgraphics.vfx.sequence.CgVfxClipAtlas;
import com.crystalgraphics.vfx.sequence.CgVfxNodeClip;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.vfx.particle.gpu.CgVfxDrawLayout;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A system's renderers made drawable: each slot renderer's and emitter renderer's material resolved against each look
 * it is in (its binds to the look's user parameters, its values), a material per material definition, slot kind and
 * binding pattern, and every draw's row of bound values written once a frame per effect and draw into
 * {@link CgPropertyRows}. Render thread.
 */
final class CgVfxMaterials {

    /**
     * Binding a colour by its property's name: retired by the format, whose per-draw values arrive only through a
     * {@code bind} ({@code CgMaterialRoute}). Kept on until the engine's kits bind explicitly, or every engine beam,
     * explosion and haze draws white.
     */
    /** What a renderer resolved to in one look: where each bound value comes from, and its materials by kind. */
    static final class Resolved {
        final CgVfxLook.Placed placed;
        final CgMaterialDefinition definition;
        /** Its size: a slot renderer's, or an emitter renderer's {@code _cg_Size}. */
        final float size;
        /** What its materials are made from, for their batch keys: the material asset. */
        final String source0;
        /**
         * Per row value: its float offset in the look's values (-1 for a renderer's constant, {@link #fixed}), its floats
         * (1, 4, or 4 an entry), its factor.
         */
        final int[] source, floats;
        final float[] factor;
        final float[][] fixed;
        /** The row's length in vec4s. */
        final int vec4s;
        /** Each bound property's place in the row, by name: what makes a material's slot ints. */
        final Map<String, Integer> slots;
        /** The signature its materials are shared by: the bound properties and their places. */
        final String signature;
        final CgMaterial[] byKind = new CgMaterial[CgVfxSlot.Kind.values().length];
        /** Its trails renderer's materials by pool, each bound to that pool's history ({@link #material(Resolved, CgVfxParticlePool)}). */
        final Map<CgVfxParticlePool, CgMaterial> byPool = new IdentityHashMap<>();

        Resolved(CgVfxLook.Placed placed, CgMaterialDefinition definition, int[] source, int[] floats, float[] factor,
                 float[][] fixed, int vec4s, Map<String, Integer> slots, String signature) {
            this.placed = placed;
            this.definition = definition;
            this.size = placed.slotRenderer() != null ? placed.slotRenderer().size()
                    : placed.renderer().common().values().get(DRAW_SIZE) instanceof Number n ? n.floatValue() : 1f;
            this.source0 = definition.id();
            this.source = source;
            this.floats = floats;
            this.factor = factor;
            this.fixed = fixed;
            this.vec4s = vec4s;
            this.slots = slots;
            this.signature = signature;
        }
    }

    /** Every material made, by material definition, then by kind and signature. */
    private final Map<Object, Map<String, CgMaterial>> materials = new IdentityHashMap<>();
    /** A probe material per renderer material definition, made to read what its shader declares. */
    private final Map<CgMaterialDefinition, CgMaterial> definitionProbes = new IdentityHashMap<>();
    private final List<CgMaterial> all = new ArrayList<>();
    /** This frame's rows, four floats a vec4, and how many vec4s are written. */
    private float[] rows = new float[256];
    private int rowCount;
    private long frame = -1;

    /** Each look's draws as this system resolved them, by their order; made at each one's first draw. */
    private final Map<CgVfxLook, Resolved[]> resolved = new IdentityHashMap<>();
    /** The look asked for last, and its array: one effect's draws ask for one look many times in a row. */
    private CgVfxLook lastLook;
    private Resolved[] lastResolved;

    /** {@code placed}'s resolution in {@code look}, made at its first draw. */
    Resolved resolve(CgVfxLook look, CgVfxLook.Placed placed) {
        if (look != lastLook) {
            lastLook = look;
            lastResolved = resolved.computeIfAbsent(look, l -> new Resolved[l.draws()]);
        }
        Resolved r = lastResolved[placed.order()];
        if (r == null) lastResolved[placed.order()] = r = rendered(look, placed);
        return r;
    }

    /**
     * A renderer's resolution: its material definition, derived where its keywords or a value on a property without
     * rows change it (05's interning), its {@code bind}s to the look's user parameters by name and its values on row
     * properties into the row, in the shader's declaration order.
     */
    private Resolved rendered(CgVfxLook look, CgVfxLook.Placed placed) {
        CgVfxRendererProperties renderer = placed.renderer();
        String who;
        String materialId;
        CgMaterialDefinition definition;
        Map<String, Object> values, binds;
        List<String> moreKeywords;
        if (renderer != null) {
            CgVfxRendererProperties.Common common = renderer.common();
            who = placed.emitter().name() + "'s renderer " + common.id();
            materialId = common.material();
            definition = common.definition();
            values = common.values();
            binds = common.bind();
            moreKeywords = common.keywords();
        } else {
            CgVfxSlotRenderer slot = placed.slotRenderer();
            who = slot.toString();
            materialId = slot.material();
            definition = slot.definition();
            values = Map.of();
            binds = slot.bind();
            moreKeywords = List.of();
        }
        if (definition == null) {
            throw new IllegalStateException(who + " names material " + materialId + ", which nothing compiled: one built"
                    + " in Java takes it with material(CgMaterialDefinition), a file through its asset");
        }
        Map<String, Object> derived = new LinkedHashMap<>();
        Map<String, Object> rowValues = new LinkedHashMap<>();
        for (Map.Entry<String, Object> v : values.entrySet()) {
            if (definition.rows().slot(v.getKey()) >= 0) rowValues.put(v.getKey(), v.getValue());
            else derived.put(v.getKey(), v.getValue());
        }
        Map<String, Object> overrides = new LinkedHashMap<>();
        if (!derived.isEmpty()) overrides.put("values", derived);
        if (!moreKeywords.isEmpty()) {
            List<String> keywords = new ArrayList<>(definition.permutation().keywords());
            for (String k : moreKeywords) if (!keywords.contains(k)) keywords.add(k);
            overrides.put("keywords", keywords);
        }
        if (!overrides.isEmpty()) definition = CgMaterialDefinitions.derive(definition, overrides);
        CgMaterialDefinition material = definition;
        CgMaterial probe = definitionProbes.computeIfAbsent(material, d -> CgMaterial.newInstance(d, null));
        if (renderer instanceof CgVfxSpriteRendererProperties sprite) {
            // The sprite factory's settings, the renderer's own: rows, so renderers differing only in them share a draw.
            material.requireRow(FACING, who);
            rowValues.put(FACING, sprite.factoryFacing(who));
            CgVfxFlipbook flipbook = sprite.subImage();
            if (flipbook != null) {
                rowValues.put(SUB_IMAGE, floats(flipbook.subImage()));
                rowValues.put(SUB_UV, floats(flipbook.subUv()));
            }
        }
        CgVfxLayout layout = look.layout();
        Map<String, CgVfxParam> bound = new HashMap<>();
        Map<String, Float> factors = new HashMap<>();
        for (Map.Entry<String, Object> b : binds.entrySet()) {
            Object form = b.getValue();
            String from = String.valueOf(form instanceof Map<?, ?> m ? m.get("bind") : form);
            float scale = form instanceof Map<?, ?> m && m.get("scale") instanceof Number n ? n.floatValue() : 1f;
            String name = from.substring("user.".length());
            CgVfxParam param = layout.param(name);
            if (param == null) {
                throw new IllegalStateException(who + " binds " + b.getKey() + " to " + from + ", which its look does not"
                        + " declare: its user parameters are " + names(layout));
            }
            material.requireRow(b.getKey(), who);
            bound.put(b.getKey(), param);
            factors.put(b.getKey(), scale);
        }
        List<int[]> entries = new ArrayList<>();
        List<Float> entryFactors = new ArrayList<>();
        List<float[]> entryFixed = new ArrayList<>();
        Map<String, Integer> slots = new HashMap<>();
        StringBuilder signature = new StringBuilder();
        int vec4s = 0;
        for (CgMaterialProperty p : probe.properties()) {
            CgVfxParam param = bound.get(p.getName());
            Object constant = rowValues.get(p.getName());
            if (param == null && constant == null) continue;
            if (param != null && !fits(p, param)) {
                throw new IllegalStateException(who + " binds '" + p.getName() + "' (" + p.getType() + ") to " + param
                        + ", which does not fit it");
            }
            slots.put(p.getName(), vec4s);
            signature.append(p.getName()).append('@').append(vec4s).append(';');
            if (param != null) {
                entries.add(new int[] {layout.offset(param), param.floats()});
                entryFactors.add(factors.get(p.getName()));
                entryFixed.add(null);
                vec4s += param instanceof CgVfxParam.Palette ? ((CgVfxParam.Palette) param).count() : 1;
            } else {
                float[] v = constant(who, p, constant);
                entries.add(new int[] {-1, v.length});
                entryFactors.add(1f);
                entryFixed.add(v);
                vec4s += (v.length + 3) / 4;
            }
        }
        int n = entries.size();
        int[] source = new int[n], floats = new int[n];
        float[] factor = new float[n];
        float[][] fixed = new float[n][];
        for (int i = 0; i < n; i++) {
            source[i] = entries.get(i)[0];
            floats[i] = entries.get(i)[1];
            factor[i] = entryFactors.get(i);
            fixed[i] = entryFixed.get(i);
        }
        return new Resolved(placed, material, source, floats, factor, fixed, vec4s, slots, signature.toString());
    }

    /** The sprite factory's facing and flipbook settings, which a sprite renderer's fields write. */
    /** A renderer's drawn extent ({@code _cg_Size}), what it sets through its {@code values}: the draw's size. */
    private static final String DRAW_SIZE = "_cg_Size";
    private static final String FACING = "_cg_Facing", SUB_IMAGE = "_cg_SubImage", SUB_UV = "_cg_SubUV";

    private static List<Object> floats(float[] v) {
        List<Object> out = new ArrayList<>(v.length);
        for (float f : v) out.add(f);
        return out;
    }

    /** A renderer's value for row property {@code p}: a number, or a vector of up to its floats, padded to a vec4 each. */
    private static float[] constant(String who, CgMaterialProperty p, Object value) {
        int want = p.isArray() ? 4 * p.getCount() : 4;
        float[] out = new float[want];
        if (value instanceof Number n) {
            out[0] = n.floatValue();
            return p.isArray() || p.getType().getComponents() <= 1 ? Arrays.copyOf(out, 4) : out;
        }
        if (value instanceof List<?> list && list.size() <= want) {
            for (int i = 0; i < list.size(); i++) {
                if (!(list.get(i) instanceof Number n)) break;
                out[i] = n.floatValue();
                if (i == list.size() - 1) return out;
            }
        }
        throw new IllegalStateException(who + " sets " + p.getName() + " to " + value + ", which is not a number or up to "
                + want + " numbers");
    }

    /**
     * The colour or palette a property reads by name: {@code _ShellHot} reads {@code shellHot}, the effect's schema
     * first. A number never binds by name: a shader's {@code _Speed} is not the effect's {@code speed}.
     */
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

    /** {@code r}'s material for slots of {@code kind}: made at its first ask, shared by layers resolving alike. */
    CgMaterial material(Resolved r, CgVfxSlot.Kind kind) {
        CgMaterial material = r.byKind[kind.ordinal()];
        if (material != null) return withClips(material);
        Map<String, CgMaterial> mine = materials.computeIfAbsent(owner(r), l -> new HashMap<>());
        String key = kind + "|" + r.signature;
        material = mine.get(key);
        if (material == null) {
            material = made(r, kind);
            mine.put(key, material);
            all.add(material);
            keys.put(material, batchKey(r, kind, ""));
            if (material.declaresBuffer(CLIPS)) clipReaders.put(material, Boolean.TRUE);
        }
        r.byKind[kind.ordinal()] = material;
        return withClips(material);
    }

    /** The baked clips, as a shader declaring them reads them ({@code fx_sequence.glsl}). */
    private static final String CLIPS = "FX_CLIPS";
    /** Materials whose shader declares {@link #CLIPS}: each is pointed at the atlas's buffer as it is asked for. */
    private final Map<CgMaterial, Boolean> clipReaders = new IdentityHashMap<>();

    /** {@code material}, pointed at the baked clips' buffer where its shader reads them: the atlas grows it as it bakes. */
    private CgMaterial withClips(CgMaterial material) {
        if (clipReaders.containsKey(material)) material.buffer(CLIPS, CgVfxClipAtlas.buffer());
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
            material = made(r, CgVfxSlot.Kind.PARTICLES);
            materials.get(owner(r)).put(CgVfxSlot.Kind.PARTICLES + "|" + r.signature + "|pool" + (nextPool++), material);
            all.add(material);
            keys.put(material, batchKey(r, CgVfxSlot.Kind.PARTICLES, pool.shape().key()));
            r.byPool.put(pool, material);
        }
        material.buffer("HISTORY", pool.history());
        return material;
    }

    private int nextPool;

    /** What {@code r}'s materials are kept under: its material definition. */
    private static Object owner(Resolved r) {
        return r.definition;
    }

    private static List<String> names(CgVfxLayout layout) {
        List<String> names = new ArrayList<>();
        for (CgVfxParam p : layout.params()) names.add(layout.qualifiedName(p));
        return names;
    }

    /** The keyword of the usage {@code r} draws its material as; null for static mesh, the base program. */
    @Nullable
    private static String usageKeyword(Resolved r) {
        CgMaterialUsage usage = r.placed.renderer() != null ? CgVfxRenderers.usage(r.placed.renderer())
                : CgMaterialUsage.STATIC_MESH;
        return r.definition.requireUsage(usage, r.placed.toString());
    }

    /** A new material of {@code r} for slots of {@code kind}, from its definition, under its usage's keyword. */
    private static CgMaterial made(Resolved r, CgVfxSlot.Kind kind) {
        String usage = usageKeyword(r);
        CgMaterial material = CgMaterial.newInstance(r.definition, usage);
        // A clip's part places its vertices by its node whatever its usage: its program branches on FX_ON_SEQUENCE
        // inside the mesh-particle factory.
        boolean byKind = usage == null || !material.declaredKeywords().contains(usage) || kind == CgVfxSlot.Kind.SEQUENCE;
        if (byKind && material.declaredKeywords().contains(kind.keyword())) {
            material.enableKeyword(kind.keyword());
        }
        if (kind == CgVfxSlot.Kind.SEQUENCE && !material.isKeywordEnabled(kind.keyword())) {
            throw new IllegalStateException(r.definition.id() + " draws a part a clip plays, but its shader declares no "
                    + kind.keyword() + ": place its vertices by FX_SEQUENCE_TO_WORLD (fx_sequence.glsl)");
        }
        material.applyProperties(b -> {
            for (Map.Entry<String, Integer> slot : r.slots.entrySet()) {
                b.set1i(CgMaterialProperty.rowSlotName(slot.getKey()), slot.getValue());
            }
        });
        return material;
    }

    /** The fluid shade each fluid material was last pointed at: the atlas makes it anew when its pool changes size. */
    private final Map<CgMaterial, CgTexture> fluidShades = new IdentityHashMap<>();

    /** Points fluid {@code material} at the atlas's shade, UV grids and page tables, once each time the atlas made them. */
    void fluid(CgMaterial material, CgTexture shade, CgTexture restA, CgTexture restB, CgTexture pages) {
        if (fluidShades.get(material) == shade) return;
        fluidShades.put(material, shade);
        material.applyProperties(b -> b.sampler(CgVfxFluidAtlas.SHADE_SAMPLER, 0, shade)
                .sampler(CgVfxFluidAtlas.REST_A_SAMPLER, 1, restA).sampler(CgVfxFluidAtlas.REST_B_SAMPLER, 2, restB)
                .sampler(CgVfxFluidAtlas.PAGES_SAMPLER, 3, pages));
    }

    /** The grid view each grid material was last pointed at: the atlas makes it anew in a new context. */
    private final Map<CgMaterial, CgTexture> views = new IdentityHashMap<>();

    /** Points grid {@code material} at the atlas's view, light and page tables, once each time the atlas made them. */
    void grid(CgMaterial material, CgTexture view, CgTexture light, CgTexture pages) {
        if (views.get(material) == view) return;
        views.put(material, view);
        material.applyProperties(b -> b.sampler(CgVfxGridAtlas.VIEW_SAMPLER, 0, view).sampler(CgVfxGridAtlas.LIGHT_SAMPLER, 1, light)
                .sampler(CgVfxGridAtlas.PAGES_SAMPLER, 2, pages));
    }

    /** Each material's batch key: draws of one material given one key sort together. */
    private final Map<CgMaterial, Integer> keys = new IdentityHashMap<>();

    /**
     * A material's batch key, from what it is rather than when it was made, so a warm budget reordering creation never
     * reorders blending. Strings only: an enum's hash differs run to run. Two materials sharing a key only batch less.
     */
    private static int batchKey(Resolved r, CgVfxSlot.Kind kind, String pool) {
        int h = (r.source0 + '|' + kind.name() + '|' + r.signature + '|' + pool).hashCode();
        h ^= h >>> 16;
        return (h ^ h >>> 12) & 0xFFF;
    }

    /** {@code material}'s batch key, 0 to 4095. */
    int key(CgMaterial material) {
        Integer key = keys.get(material);
        return key == null ? 0 : key;
    }

    /** Every material made, for the system to bind its path texture on and warm. */
    List<CgMaterial> all() {
        return all;
    }

    /** {@code r}'s size for {@code effect}. */
    static float size(CgVfxEffect<?> effect, Resolved r) {
        return r.size;
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
            int layers = effect.look().draws();
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
            if (r.source[i] < 0) {
                System.arraycopy(r.fixed[i], 0, rows, write, floats);
                write += floats;
            } else if (floats == 1) {
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

    /** Warms {@code look}'s materials: one per draw and kind; those drawn at half size also in {@code halved}. */
    void prepare(CgVfxLook look, List<CgMaterial> warming, Set<CgMaterial> halved) {
        for (int i = 0; i < look.placed().size(); i++) prepare(look, i, warming, halved);
        prepareParticles(look, warming);
    }

    /**
     * {@link #prepare}'s step for {@code look}'s {@code i}th draw, a warm-up's unit of work: a slot renderer on its
     * slot's kind, an emitter's renderer on its particles' (a clip's emitter is drawn by its parts).
     */
    void prepare(CgVfxLook look, int i, List<CgMaterial> warming, Set<CgMaterial> halved) {
        CgVfxLook.Placed placed = look.placed().get(i);
        if (placed.slotRenderer() == null && placed.emitter().drawLayout() == CgVfxDrawLayout.SEQUENCE) return;
        CgVfxSlot.Kind kind = placed.slotRenderer() != null ? placed.slotRenderer().slot().kind() : CgVfxSlot.Kind.PARTICLES;
        CgMaterial material = material(resolve(look, placed), kind);
        if (!warming.contains(material)) warming.add(material);
        if (halfResolution(placed, kind)) halved.add(material);
    }

    /** {@link #prepare}'s last step: a clip's parts, which draw their prefab's slot renderers on SEQUENCE materials. */
    void prepareParticles(CgVfxLook look, List<CgMaterial> warming) {
        for (CgVfxEmitter emitter : look.emitters()) {
            CgVfxNodeClip clip = emitter.clip();
            if (clip == null) continue;
            CgVfxPrefab prefab = clip.prefab();
            for (int part = 0; part < prefab.firstPart(prefab.nodes()); part++) {
                for (CgVfxLook.Placed placed : look.on(prefab.partSlot(part))) {
                    CgMaterial material = material(resolve(look, placed), CgVfxSlot.Kind.SEQUENCE);
                    if (!warming.contains(material)) warming.add(material);
                }
            }
        }
    }

    /**
     * The shader assets {@link #prepare} would make {@code look}'s materials from, added to {@code out}: made here, on the
     * render thread, so a worker can parse them ahead ({@link CgMaterialShader#ensureParsed}).
     */
    void shaders(CgVfxLook look, List<CgMaterialShader> out) {
        CgMaterialShaderRegistry registry = CgMaterialShaderRegistry.get();
        for (CgVfxLook.Placed placed : look.placed()) {
            CgMaterialDefinition d = placed.definition();
            if (d == null) continue;
            CgMaterialShader shader = registry.getOrCreate(d.permutation().shader(), d.state());
            if (!out.contains(shader)) out.add(shader);
        }
    }

    /** Whether {@code CgVfxFrame} draws {@code placed} on a slot of {@code kind} {@code .halfResolution()}. */
    private static boolean halfResolution(CgVfxLook.Placed placed, CgVfxSlot.Kind kind) {
        return kind == CgVfxSlot.Kind.FLUID || kind == CgVfxSlot.Kind.GRID || kind == CgVfxSlot.Kind.TUBE && placed.volume();
    }

}
