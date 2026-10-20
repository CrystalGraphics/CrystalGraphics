package com.crystalgraphics.api.material;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.gl.material.CgMaterialProperty;
import com.crystalgraphics.gl.material.parse.CgParsedShader;

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
        String blend = state != null && state.get("blend") instanceof String ? (String) state.get("blend") : "opaque";
        String shading = state != null && state.get("shading") instanceof String ? (String) state.get("shading") : null;
        CgMaterialPermutation permutation = CgMaterialPermutation.of(shader, new TreeSet<>(keywords), blend,
                "masked".equals(blend), shading);

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
        int order = m.get("order") instanceof Number ? ((Number) m.get("order")).intValue() : 0;
        String sortLayer = m.get("sort_layer") instanceof String ? (String) m.get("sort_layer") : "default";
        return new CgMaterialDefinition(id, asset.parent() == null ? null : asset.parent().id(), permutation,
                Collections.unmodifiableMap(constants), Collections.unmodifiableMap(forms),
                Collections.unmodifiableMap(textures), CgMaterialRowLayout.of(parsed.properties()), draw, order,
                sortLayer, map(m.get("post")));
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
