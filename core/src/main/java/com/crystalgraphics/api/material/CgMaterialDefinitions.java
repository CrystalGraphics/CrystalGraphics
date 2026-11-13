package com.crystalgraphics.api.material;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import com.crystalgraphics.api.state.CgCullState;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.material.CgMaterialProperty;
import com.crystalgraphics.material.shader.CgShaderSpec;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.validate.CgValidation;
import com.github.bsideup.jabel.Desugar;

/**
 * The {@code material} kind's compiler: a resolved asset to a {@link CgMaterialDefinition}, every name checked against
 * its archetype's {@code Properties} and keywords.
 *
 * <pre>{@code
 * CgMaterialDefinitions.register();                                   // at init: the pipeline compiles materials so
 * CgMaterialDefinition d = CgMaterialDefinitions.compile(asset);      // or directly, from a resolved asset
 * }</pre>
 *
 * <ul>
 *   <li>Fails naming the material and the name: a {@code values} or {@code textures} key, or a keyword, its archetype
 *       does not declare, a number on a sampler, a texture on a number, a value with too many floats.</li>
 *   <li>A child may not set {@code post}: a post material's place in the frame is its root's.</li>
 *   <li>Runs on a worker: the archetypes it reads must be GL-free ({@link CgMaterialArchetypes}).</li>
 * </ul>
 */
public final class CgMaterialDefinitions {

    private static volatile CgMaterialArchetypes archetypes = CgMaterialArchetypes.FILES;
    /** Each parent's derived children, dropped with it: a definition's equality is its arrays', so its identity. */
    private static final Map<CgMaterialDefinition, Map<Object, CgMaterialDefinition>> DERIVED = new WeakHashMap<>();
    /** The threshold a masked material's {@code MaskClip} sets: what {@code CG_CLIP_THRESHOLD} reads. */
    static final String CLIP = "_Clip";

    private CgMaterialDefinitions() {
    }

    /** Sets {@code material}'s compiler on the asset pipeline. */
    public static void register() {
        CgAssetCompiler.register(CgLookKinds.MATERIAL, (asset, deps) -> compile(asset));
    }

    /** Where archetypes are read from: {@link CgMaterialArchetypes#FILES} unless a test or tool sets its own. */
    public static void archetypes(CgMaterialArchetypes source) {
        archetypes = source;
    }

    /** {@code asset}, compiled against the archetypes set. */
    public static CgMaterialDefinition compile(CgAssetData asset) {
        return compile(asset, archetypes);
    }

    /**
     * {@code asset}, a resolved {@code material}, compiled against {@code archetypes}.
     *
     * @throws CgAssetException naming the material and what it names that its archetype lacks
     */
    public static CgMaterialDefinition compile(CgAssetData asset, CgMaterialArchetypes archetypes) {
        Map<Object, Object> m = asset.resolved();
        String id = asset.id();
        Object shaderValue = m.get("shader");
        if (!(shaderValue instanceof String)) throw new CgAssetException(id + " names no shader, nor does any parent");
        String shader = (String) shaderValue;
        CgShaderSpec parsed;
        try {
            parsed = archetypes.parse(shader);
        } catch (RuntimeException e) {
            throw new CgAssetException(id + ": its archetype " + shader + " does not load: " + e.getMessage());
        }
        if (asset.parent() != null && asset.delta().containsKey("post")) {
            throw new CgAssetException(id + " sets post, which only a root material may: its place in the frame is "
                    + "its root's");
        }
        Map<String, CgMaterialProperty> props = new LinkedHashMap<>();
        for (CgMaterialProperty p : parsed.properties()) if (!p.isHidden()) props.put(p.getName(), p);

        List<String> keywords = strings(m.get("keywords"));
        for (String k : keywords) {
            if (!parsed.features().contains(k)) {
                throw new CgAssetException(id + " enables keyword " + k + ", which " + shader + " does not declare; it "
                        + "declares " + parsed.features());
            }
        }
        // No BlendMode keeps the archetype's own, as an instance without bOverride_BlendMode keeps its parent's.
        AssetState state = assetState(id, map(m.get("state")));
        CgShadingModel model = null;
        if (state.shading() != null) {
            model = CgShadingModels.get(state.shading());
            if (model == null) {
                throw new CgAssetException(id + " names ShadingModel " + state.shading() + ", which nothing registered:"
                        + " a kit registers its own (CgShadingModels.registerKit)");
            }
            String k = model.keyword();
            if (k != null && parsed.features().contains(k) && !keywords.contains(k)) {
                keywords = new ArrayList<>(keywords);
                keywords.add(k);
            }
        }
        CgMaterialDomain domain = archetypes.domain(shader);
        if (m.get("post") != null && domain != CgMaterialDomain.POST) {
            throw new CgAssetException(id + " has a post block, but " + shader + " is a "
                    + domain.name().toLowerCase(Locale.ROOT) + " archetype: a post material's is tagged \"Domain\" = \"Post\"");
        }
        if (!state.depthTest() && state.blend() == CgBlendMode.OPAQUE) {
            throw new CgAssetException(id + " turns DepthTest off on BlendMode " + (state.clip() ? "Masked" : "Opaque")
                    + ": only a translucent blend may (Unreal's bDisableDepthTest)");
        }
        CgMaterialPermutation permutation = CgMaterialPermutation.of(shader, new TreeSet<>(keywords), state.blend(),
                state.clip(), state.depthTest(), model == null ? null : model.id());

        Map<String, float[]> constants = new LinkedHashMap<>();
        Map<String, Object> forms = new LinkedHashMap<>();
        Map<Object, Object> values = map(m.get("values"));
        if (values != null) {
            for (Map.Entry<Object, Object> e : values.entrySet()) {
                String name = String.valueOf(e.getKey());
                CgMaterialProperty p = declared(id, shader, props, name);
                if (p.getType().isSampler()) {
                    throw new CgAssetException(id + " sets " + name + " in values, but it is a sampler of " + shader
                            + ": give it under textures");
                }
                if (e.getValue() instanceof Map) {
                    forms.put(name, e.getValue());
                } else {
                    constants.put(name, floats(id, name, p, e.getValue()));
                }
            }
        }
        Map<String, CgMaterialDefinition.Texture> textures = new LinkedHashMap<>();
        Map<Object, Object> tex = map(m.get("textures"));
        if (tex != null) {
            for (Map.Entry<Object, Object> e : tex.entrySet()) {
                String name = String.valueOf(e.getKey());
                CgMaterialProperty p = declared(id, shader, props, name);
                if (!p.getType().isSampler()) {
                    throw new CgAssetException(id + " gives a texture to " + name + ", which " + shader
                            + " declares as " + p.getType().getPropertyTypeName());
                }
                Map<Object, Object> binding = map(e.getValue());
                Object texture = binding == null ? null : binding.get("texture");
                Map<Object, Object> sampler = binding == null ? null : map(binding.get("sampler"));
                if (texture instanceof String path) {
                    textures.put(name, new CgMaterialDefinition.Texture(path, null, sampler));
                } else if (texture instanceof CgTexture made) {
                    textures.put(name, new CgMaterialDefinition.Texture(null, () -> made, sampler));
                } else if (texture instanceof CgMaterialDefinition.MadeTexture made) {
                    textures.put(name, new CgMaterialDefinition.Texture(null, made, sampler));
                } else {
                    throw new CgAssetException(id + ": textures." + name + " names no texture");
                }
            }
        }
        CgMaterialDefinition.Draw draw = new CgMaterialDefinition.Draw(state.cull(), state.depthWrite(),
                state.maskClip(), state.separateTranslucency());
        if (permutation.clip() && draw.maskClip() != null && !constants.containsKey(CLIP)) {
            CgMaterialProperty clip = props.get(CLIP);
            if (clip == null) {
                throw new CgAssetException(id + " sets MaskClip, but " + shader + " declares no " + CLIP
                        + " for the threshold to reach");
            }
            constants.put(CLIP, floats(id, CLIP, clip, draw.maskClip()));
        }
        Set<CgMaterialUsage> usage = usage(id, shader, parsed, strings(m.get("usage")));
        int order = m.get("order") instanceof Number ? ((Number) m.get("order")).intValue() : 0;
        String sortLayer = m.get("sort_layer") instanceof String ? (String) m.get("sort_layer") : "default";
        CgMaterialDefinition d = new CgMaterialDefinition(id, asset.parent() == null ? null : asset.parent().id(),
                permutation, Collections.unmodifiableMap(constants), Collections.unmodifiableMap(forms),
                Collections.unmodifiableMap(textures), CgMaterialRowLayout.of(parsed.properties()), draw, order,
                sortLayer, map(m.get("post")), domain, model, parsed.pass(CgRenderPassVariant.DISTORTION) != null, parsed.tube(),
                usage, Collections.unmodifiableMap(m));
        for (String name : forms.keySet()) {
            if (d.route(name) == CgMaterialRoute.ROW) d.requireRow(name, "its bind");
        }
        return d;
    }

    /**
     * What draws a material: its file's {@code usage}, static mesh where it lists none, each checked against what its
     * archetype can be compiled for (see {@link CgMaterialUsage}).
     */
    private static Set<CgMaterialUsage> usage(String id, String shader, CgShaderSpec parsed, List<String> names) {
        Set<CgMaterialUsage> usage = EnumSet.noneOf(CgMaterialUsage.class);
        for (String n : names) usage.add(CgMaterialUsage.of(n));
        if (usage.isEmpty()) usage.add(CgMaterialUsage.STATIC_MESH);
        Set<CgMaterialUsage> serves = EnumSet.noneOf(CgMaterialUsage.class);
        for (CgMaterialUsage u : CgMaterialUsage.values()) {
            // What a particle program takes undeclared (CgShaderSpec.implicitFeatures) says nothing of what it serves
            String k = u.keyword();
            if (k != null && parsed.features().contains(k) && !parsed.implicitFeatures().contains(k)) serves.add(u);
        }
        boolean particles = parsed.engineBuffers().contains("particle");
        // With no usage keyword a particle program is placed by each particle factory
        if (!serves.isEmpty()) serves.add(CgMaterialUsage.STATIC_MESH);
        else if (particles) serves.addAll(EnumSet.of(CgMaterialUsage.SPRITES, CgMaterialUsage.MESH_PARTICLES, CgMaterialUsage.RIBBONS));
        else serves.addAll(EnumSet.of(CgMaterialUsage.STATIC_MESH, CgMaterialUsage.SKELETAL_MESH));
        for (CgMaterialUsage u : usage) {
            if (!serves.contains(u)) {
                String why = u.keyword() == null ? "it is drawn by a particle factory"
                        : "it declares no keyword " + u.keyword();
                throw new CgAssetException(id + " is used with " + u.fileName() + ", which " + shader + " is not compiled for: " + why);
            }
        }
        return Collections.unmodifiableSet(usage);
    }

    /**
     * {@code parent} with {@code overrides} over it, as a child file holding them would compile: what a renderer's own
     * textures, keywords or state make of its material. Interned: the same overrides on the same parent answer the
     * same definition, which shares its parent's programs where its permutation does.
     *
     * <pre>{@code
     * CgMaterialDefinition soft = CgMaterialDefinitions.derive(glow, Map.of("keywords", List.of("SOFT")));
     * soft == CgMaterialDefinitions.derive(glow, Map.of("keywords", List.of("SOFT")));   // true
     * CgMaterialDefinition tinted = CgMaterialDefinitions.derive(glow, Map.of("values", Map.of("_Speed", 2.0)));
     * tinted.sharesProgramsWith(glow);                                                    // true: values only
     * }</pre>
     *
     * <ul>
     *   <li>{@code overrides} holds a material file's keys ({@code values}, {@code textures}, {@code keywords},
     *       {@code state}); a list replaces the parent's, a map merges into it.</li>
     *   <li>A new generation of {@code parent} (a reload compiles a new definition) derives its children again; the
     *       old ones are dropped with it.</li>
     * </ul>
     *
     * @throws CgAssetException naming what the declaration or the archetype refuses
     */
    public static CgMaterialDefinition derive(CgMaterialDefinition parent, Map<String, ?> overrides) {
        Object key = canonical(overrides);
        Map<Object, CgMaterialDefinition> children;
        synchronized (DERIVED) {
            children = DERIVED.computeIfAbsent(parent, p -> new HashMap<>());
            CgMaterialDefinition had = children.get(key);
            if (had != null) return had;
        }
        Map<Object, Object> root = new LinkedHashMap<>(parent.resolved());
        root.remove(CgType.PARENT);
        CgAssetData base = CgAssetData.of(parent.id(), CgLookKinds.MATERIAL, root, null);
        Map<Object, Object> file = new LinkedHashMap<>();
        file.put(CgType.PARENT, parent.id());
        file.putAll(overrides);
        CgAssetData child = CgAssetData.of(parent.id() + "#" + Integer.toHexString(key.hashCode()), CgLookKinds.MATERIAL,
                file, base);
        CgValidation v = child.validate(CgValidation.Usecase.LOAD);
        if (!v.valid()) throw new CgAssetException(child.id() + ": " + v.issues());
        CgMaterialDefinition d = compile(child);
        synchronized (DERIVED) {
            CgMaterialDefinition raced = children.putIfAbsent(key, d);
            return raced != null ? raced : d;
        }
    }

    /** Overrides in a form equal exactly when they compile alike: floats by bits, NaN as one, keywords sorted. */
    private static Object canonical(Object o) {
        if (o instanceof Map) {
            Map<String, Object> out = new TreeMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                String k = String.valueOf(e.getKey());
                Object v = canonical(e.getValue());
                if (k.equals("keywords") && v instanceof List) {
                    List<Object> sorted = new ArrayList<>((List<?>) v);
                    sorted.sort((a, b) -> String.valueOf(a).compareTo(String.valueOf(b)));
                    v = sorted;
                }
                out.put(k, v);
            }
            return out;
        }
        if (o instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object v : (List<?>) o) out.add(canonical(v));
            return out;
        }
        if (o instanceof Float || o instanceof Double) return Float.floatToIntBits(((Number) o).floatValue());
        if (o instanceof Number) return ((Number) o).longValue();
        return o;
    }

    private static CgMaterialProperty declared(String id, String shader, Map<String, CgMaterialProperty> props,
                                               String name) {
        CgMaterialProperty p = props.get(name);
        if (p == null) {
            throw new CgAssetException(id + " sets " + name + ", which " + shader + " does not declare; it declares "
                    + props.keySet());
        }
        return p;
    }

    /** A constant's floats, padded to its property's. */
    private static float[] floats(String id, String name, CgMaterialProperty p, Object value) {
        int want = Math.max(1, p.getType().getComponents()) * p.getCount();
        float[] out = new float[want];
        if (value instanceof Number) {
            out[0] = ((Number) value).floatValue();
            if (p.getType().getComponents() == 4 && !p.isArray()) {
                out[1] = out[2] = out[0];
                out[3] = 1f;
            }
            return out;
        }
        if (value instanceof Boolean) {
            out[0] = (Boolean) value ? 1f : 0f;
            return out;
        }
        if (!(value instanceof List)) {
            throw new CgAssetException(id + " sets " + name + " to " + value + ", which is not a number or a list");
        }
        List<?> list = (List<?>) value;
        List<Object> flat = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof List) flat.addAll((List<?>) o);
            else flat.add(o);
        }
        if (flat.size() > want) {
            throw new CgAssetException(id + " sets " + name + " to " + flat.size() + " floats; its "
                    + p.getType().getPropertyTypeName() + (p.isArray() ? "[" + p.getCount() + "]" : "") + " holds " + want);
        }
        for (int i = 0; i < flat.size(); i++) {
            if (!(flat.get(i) instanceof Number)) {
                throw new CgAssetException(id + " sets " + name + " with " + flat.get(i) + ", which is not a number");
            }
            out[i] = ((Number) flat.get(i)).floatValue();
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static Map<Object, Object> map(Object o) {
        return o instanceof Map ? (Map<Object, Object>) o : null;
    }

    private static List<String> strings(Object o) {
        if (!(o instanceof List)) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (Object s : (List<?>) o) out.add(String.valueOf(s));
        return out;
    }

    // ── state ─────────────────────────────────────────────────────────────────────────

    /** The {@code Material { }} properties an asset's {@code state} may set over its shader's; the rest are the shader's. */
    private static final List<String> STATE = Collections.unmodifiableList(Arrays.asList("BlendMode", "ShadingModel", "Faces",
            "MaskClip", "DepthTest", "DepthWrite", "TranslucencyPass"));
    /** {@code BlendMode}'s values but {@code Masked}, which is {@link CgBlendMode#OPAQUE} with a clip. */
    static final Map<CgBlendMode, String> BLEND_MODES = new EnumMap<>(CgBlendMode.class);
    /** {@code ShadingModel}'s engine values by model; a kit's model is written by its id. */
    private static final Map<CgShadingModel, String> SHADING = new HashMap<>();

    static {
        BLEND_MODES.put(CgBlendMode.OPAQUE, "Opaque");
        BLEND_MODES.put(CgBlendMode.ALPHA, "Translucent");
        BLEND_MODES.put(CgBlendMode.PREMULTIPLIED, "AlphaComposite");
        BLEND_MODES.put(CgBlendMode.ADDITIVE, "Additive");
        BLEND_MODES.put(CgBlendMode.MODULATE, "Modulate");
        BLEND_MODES.put(CgBlendMode.SUBTRACT, "Subtract");
        SHADING.put(CgShadingModels.UNLIT, "Unlit");
        SHADING.put(CgShadingModels.LIT, "WorldLit");
        SHADING.put(CgShadingModels.SUBSURFACE, "Subsurface");
    }

    /** {@code model} as an asset's {@code ShadingModel} names it. */
    static String shadingName(CgShadingModel model) {
        String name = SHADING.get(model);
        return name != null ? name : model.id();
    }

    /**
     * An asset's {@code state}, read: each null where the asset leaves its shader's own.
     *
     * @param blend   {@code BlendMode}, {@code Masked} as {@link CgBlendMode#OPAQUE} with {@code clip}
     * @param shading the id of the {@code ShadingModel}'s model
     */
    @Desugar
    private record AssetState(@Nullable CgBlendMode blend, boolean clip, @Nullable String shading,
                              @Nullable CgCullState cull, @Nullable Float maskClip, boolean depthTest,
                              @Nullable Boolean depthWrite, boolean separateTranslucency) {
    }

    /** {@code state} by {@code Material { }}'s names and values, each refused naming the asset. */
    private static AssetState assetState(String id, @Nullable Map<Object, Object> state) {
        if (state == null) state = Collections.emptyMap();
        for (Object key : state.keySet()) {
            if (!STATE.contains(String.valueOf(key))) {
                throw new CgAssetException(id + " sets state " + key + ": an asset sets " + STATE + " over its shader's"
                        + " Material { }, and the rest is the shader's");
            }
        }
        CgBlendMode blend = null;
        boolean clip = false;
        Object b = state.get("BlendMode");
        if ("Masked".equals(b)) {
            blend = CgBlendMode.OPAQUE;
            clip = true;
        } else if (b != null) {
            for (Map.Entry<CgBlendMode, String> e : BLEND_MODES.entrySet()) if (e.getValue().equals(b)) blend = e.getKey();
            if (blend == null) {
                throw new CgAssetException(id + " sets BlendMode " + b + "; it is Masked or one of " + BLEND_MODES.values());
            }
        }
        String shading = null;
        Object s = state.get("ShadingModel");
        if (s != null) {
            for (Map.Entry<CgShadingModel, String> e : SHADING.entrySet()) if (e.getValue().equals(s)) shading = e.getKey().id();
            if (shading == null && String.valueOf(s).indexOf(':') > 0) shading = String.valueOf(s);
            if (shading == null) {
                throw new CgAssetException(id + " sets ShadingModel " + s + "; it is one of " + SHADING.values()
                        + " or a kit's model by its id (kakarot:cell_tone)");
            }
        }
        CgCullState cull = null;
        Object f = state.get("Faces");
        if (f != null) {
            cull = "Front".equals(f) ? CgCullState.BACK : "Back".equals(f) ? CgCullState.FRONT
                    : "Both".equals(f) ? CgCullState.NONE : null;
            if (cull == null) throw new CgAssetException(id + " sets Faces " + f + "; it is Front, Back or Both");
        }
        Object clipAt = state.get("MaskClip");
        if (clipAt != null && !(clipAt instanceof Number)) {
            throw new CgAssetException(id + " sets MaskClip " + clipAt + ", which is not a number");
        }
        Object pass = state.get("TranslucencyPass");
        if (pass != null && !"Separate".equals(pass)) {
            throw new CgAssetException(id + " sets TranslucencyPass " + pass + ": an asset may move its material to"
                    + " Separate only; Standard, AfterDistortion and Overlay are its shader's");
        }
        Boolean depthTest = toggle(id, state, "DepthTest");
        return new AssetState(blend, clip, shading, cull, clipAt == null ? null : ((Number) clipAt).floatValue(),
                depthTest == null || depthTest, toggle(id, state, "DepthWrite"), pass != null);
    }

    /** {@code key}'s {@code On} or {@code Off}, which YAML reads as true and false; null where it is not set. */
    @Nullable
    private static Boolean toggle(String id, Map<Object, Object> state, String key) {
        Object v = state.get(key);
        if (v == null || v instanceof Boolean) return (Boolean) v;
        throw new CgAssetException(id + " sets " + key + " " + v + "; an asset's " + key + " is On or Off"
                + ("Strict".equals(v) ? " (Strict is its shader's)" : ""));
    }
}
