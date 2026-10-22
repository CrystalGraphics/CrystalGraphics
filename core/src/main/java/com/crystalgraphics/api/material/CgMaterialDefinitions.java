package com.crystalgraphics.api.material;

import java.util.ArrayList;
import java.util.Collections;
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

import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.gl.material.CgMaterialProperty;
import com.crystalgraphics.gl.material.parse.CgParsedShader;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.validate.CgValidation;

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
    /** The threshold a {@code masked} material's {@code mask_clip} sets: what {@code CG_CLIP_THRESHOLD} reads. */
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
        CgParsedShader parsed;
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
            if (!parsed.featureNames().contains(k)) {
                throw new CgAssetException(id + " enables keyword " + k + ", which " + shader + " does not declare; it "
                        + "declares " + parsed.featureNames());
            }
        }
        Map<Object, Object> state = map(m.get("state"));
        // No blend keeps the archetype's own, as an instance without bOverride_BlendMode keeps its parent's.
        String blend = state != null && state.get("blend") instanceof String ? (String) state.get("blend") : null;
        String shading = state != null && state.get("shading") instanceof String ? (String) state.get("shading") : null;
        CgShadingModel model = null;
        if (shading != null) {
            model = CgShadingModels.get(shading);
            if (model == null) {
                throw new CgAssetException(id + " names shading model " + shading + ", which nothing registered: a kit "
                        + "registers its own (CgShadingModels.registerKit)");
            }
            String k = model.keyword();
            if (k != null && parsed.featureNames().contains(k) && !keywords.contains(k)) {
                keywords = new ArrayList<>(keywords);
                keywords.add(k);
            }
        }
        CgMaterialDomain domain = archetypes.domain(shader);
        if (m.get("post") != null && domain != CgMaterialDomain.POST) {
            throw new CgAssetException(id + " has a post block, but " + shader + " is a "
                    + domain.name().toLowerCase(Locale.ROOT) + " archetype: a post material's is tagged \"Domain\" = \"Post\"");
        }
        CgMaterialPermutation permutation = CgMaterialPermutation.of(archetypes.resolve(shader),
                new TreeSet<>(keywords), blend, "masked".equals(blend), shading);

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
                if (!(texture instanceof String)) throw new CgAssetException(id + ": textures." + name + " names no texture");
                textures.put(name, new CgMaterialDefinition.Texture((String) texture, map(binding.get("sampler"))));
            }
        }
        CgMaterialDefinition.Draw draw = new CgMaterialDefinition.Draw(bool(state, "two_sided"),
                state != null && state.get("cull") instanceof String ? (String) state.get("cull") : null,
                bool(state, "depth_test"), bool(state, "depth_write"),
                state != null && state.get("mask_clip") instanceof Number ? ((Number) state.get("mask_clip")).floatValue()
                        : null);
        if (permutation.clip() && draw.maskClip() != null && !constants.containsKey(CLIP)) {
            CgMaterialProperty clip = props.get(CLIP);
            if (clip == null) {
                throw new CgAssetException(id + " sets mask_clip, but " + shader + " declares no " + CLIP
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
                sortLayer, map(m.get("post")), domain, model, parsed.getPassByLightMode("Distortion") != null, parsed.tube(),
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
    private static Set<CgMaterialUsage> usage(String id, String shader, CgParsedShader parsed, List<String> names) {
        Set<CgMaterialUsage> usage = EnumSet.noneOf(CgMaterialUsage.class);
        for (String n : names) usage.add(CgMaterialUsage.of(n));
        if (usage.isEmpty()) usage.add(CgMaterialUsage.STATIC_MESH);
        Set<CgMaterialUsage> serves = EnumSet.noneOf(CgMaterialUsage.class);
        for (CgMaterialUsage u : CgMaterialUsage.values()) {
            // Every particle program takes FX_ON_RIBBONS undeclared (CgMaterialShaderCompiler.STRIP): it says nothing.
            if (u.keyword() != null && u != CgMaterialUsage.RIBBONS && parsed.featureNames().contains(u.keyword())) serves.add(u);
        }
        boolean particles = parsed.engineBuffers().contains("particle");
        if (!serves.isEmpty()) serves.add(CgMaterialUsage.STATIC_MESH);
        else if (particles) serves.addAll(EnumSet.of(CgMaterialUsage.SPRITES, CgMaterialUsage.MESH_PARTICLES, CgMaterialUsage.RIBBONS));
        else serves.addAll(EnumSet.of(CgMaterialUsage.STATIC_MESH, CgMaterialUsage.SKELETAL_MESH));
        for (CgMaterialUsage u : usage) {
            if (!serves.contains(u)) {
                throw new CgAssetException(id + " is used with " + u.fileName() + ", which " + shader + " is not compiled"
                        + " for: " + (u.keyword() == null ? "it is a particle program (#pragma cg_use particle)"
                        : "it declares no #pragma cg_feature " + u.keyword()));
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

    @Nullable
    private static Boolean bool(@Nullable Map<Object, Object> state, String key) {
        return state != null && state.get(key) instanceof Boolean ? (Boolean) state.get(key) : null;
    }
}
