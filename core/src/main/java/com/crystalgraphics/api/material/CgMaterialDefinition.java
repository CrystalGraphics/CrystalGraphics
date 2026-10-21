package com.crystalgraphics.api.material;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgraphics.api.state.CgCullState;
import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.validate.CgValidation;
import com.github.bsideup.jabel.Desugar;

/**
 * A {@code material} asset compiled: its parent chain merged, its archetype's names checked, and split into what
 * changes its programs (the {@link CgMaterialPermutation}) and what does not (constants, textures, render state). What
 * the renderers draw a material from; compiled on a worker by {@link CgMaterialDefinitions}.
 *
 * <pre>{@code
 * CgMaterialDefinitions.register();                                       // once, at init
 * CgAssetLoad load = pipeline.request(CgLookKinds.MATERIAL, "kakarot_kit:Art/EFF/e/cmn/M_Glow");
 * CgMaterialDefinition glow = (CgMaterialDefinition) load.definition();   // once COMPILED
 * glow.permutation();            // its programs' key: shared with its parent when it overrides only values
 * glow.constants().get("_Glow"); // the values, the parent's under the child's
 * glow.rows().slot("_Color");    // where a bind writes it per draw
 * }</pre>
 *
 * @param id          its asset id
 * @param parent      its parent's id, null for a root
 * @param permutation what changes its programs
 * @param constants   numeric {@code values}, each padded to its property's floats
 * @param forms       {@code values} given as a value form ({@code bind}, {@code curve}, ...): not constants
 * @param textures    {@code textures}, by sampler property
 * @param rows        its archetype's row layout
 * @param draw        its state's fields that are pipeline state, not program
 * @param order       its order among its effect's draws
 * @param sortLayer   the world renderer's sort layer
 * @param post        its root's {@code post} block, null when it is not a post material
 * @param domain      where it is drawn: its archetype's
 * @param shading     the shading model it names, null for its archetype's own
 * @param distortion  whether its archetype has a Distortion pass: it bends what is drawn before it
 * @param resolved    the asset it was compiled from, its parent chain merged: what a derived child is merged over
 */
@Desugar
public record CgMaterialDefinition(String id, @Nullable String parent, CgMaterialPermutation permutation,
                                   Map<String, float[]> constants, Map<String, Object> forms,
                                   Map<String, Texture> textures, CgMaterialRowLayout rows, Draw draw, int order,
                                   String sortLayer, @Nullable Map<Object, Object> post, CgMaterialDomain domain,
                                   @Nullable CgShadingModel shading, boolean distortion,
                                   Map<Object, Object> resolved) {

    /**
     * A material written in Java: the same tree its file holds, compiled by the same compiler.
     *
     * <pre>{@code
     * CgMaterialDefinition glow = CgMaterialDefinition.builder("mymod:materials/glow", "mymod:shaders/fx/soft.shader")
     *         .blend(CgBlendMode.ADDITIVE).twoSided()
     *         .keyword("SOFT")
     *         .color("_Color", 0xFFFF8A20, 2f)                                   // rgb times 2: past white
     *         .value("_Speed", 1.5f)
     *         .texture("_MainTex", "mymod:textures/fx/noise.png", CgSampler.Filter.BILINEAR, CgSampler.Address.WRAP)
     *         .build();
     * Map<String, Object> file = builder.tree();   // what CgAssetYaml writes as its file
     * }</pre>
     *
     * <ul>
     *   <li>A name its shader does not declare fails at {@link Builder#build()}, as the file would at load.</li>
     *   <li>{@link Builder#masked(float)} is a blend of its own: it clips at the threshold and writes depth there.</li>
     * </ul>
     */
    public static Builder builder(String id, String shader) {
        return new Builder(id, shader);
    }

    /**
     * Its render state: its permutation's blend and clip, and its {@link Draw}'s cull and depth write over the
     * archetype's.
     *
     * <pre>{@code
     * CgMaterial m = CgMaterial.newInstance(definition.permutation().shader(), definition.state());
     * }</pre>
     */
    public CgMaterialState state() {
        CgMaterialState p = permutation.state();
        CgMaterialState.Builder b = CgMaterialState.builder();
        if (p.blend() != null) b.blend(p.blend());
        if (p.clip()) b.clip();
        else if (draw.depthWrite() != null) b.depthWrite(draw.depthWrite());
        else if (p.depthWrite() != null) b.depthWrite(p.depthWrite());
        if (Boolean.TRUE.equals(draw.twoSided())) b.twoSided();
        else if ("front".equals(draw.cull())) b.cull(CgCullState.FRONT);
        else if ("back".equals(draw.cull())) b.cull(CgCullState.BACK);
        return b.build();
    }

    /**
     * Checks it is drawn where {@code who} draws it.
     *
     * <pre>{@code
     * definition.requireDomain(CgMaterialDomain.SURFACE, "renderer sprite of emitter kakarot_kit:sparks");   // at load
     * }</pre>
     *
     * @throws CgAssetException naming both when its domain is another
     */
    public void requireDomain(CgMaterialDomain expected, String who) {
        if (domain != expected) {
            throw new CgAssetException(who + " draws " + id + " as a " + expected.name().toLowerCase(Locale.ROOT)
                    + " material, but it is a " + domain.name().toLowerCase(Locale.ROOT) + " material ("
                    + permutation.shader() + ")");
        }
    }

    /**
     * A texture binding: a {@code textures} asset's id and the sampler it is read with.
     *
     * @param texture its texture asset's id
     * @param sampler its {@code sampler} block, null for the texture's own
     */
    @Desugar
    public record Texture(String texture, @Nullable Map<Object, Object> sampler) {
    }

    /**
     * The state fields that never change a program: applied per pass ({@link CgMaterialState#onPass}). Null is the
     * archetype's own.
     *
     * @param twoSided   both faces drawn
     * @param cull       {@code back} or {@code front}
     * @param depthTest  whether it tests depth
     * @param depthWrite whether it writes depth
     * @param maskClip   a {@code masked} material's threshold
     */
    @Desugar
    public record Draw(@Nullable Boolean twoSided, @Nullable String cull, @Nullable Boolean depthTest,
                       @Nullable Boolean depthWrite, @Nullable Float maskClip) {
    }

    /** Whether it draws with {@code other}'s programs: the same permutation. */
    public boolean sharesProgramsWith(CgMaterialDefinition other) {
        return permutation == other.permutation;
    }

    /** How {@code property} reaches a draw from this material alone: its {@code bind} form's route, else a constant. */
    public CgMaterialRoute route(String property) {
        Object form = forms.get(property);
        if (form instanceof Map && ((Map<?, ?>) form).get("bind") instanceof String) {
            return CgMaterialRoute.of((String) ((Map<?, ?>) form).get("bind"));
        }
        return CgMaterialRoute.CONSTANT;
    }

    /**
     * {@code property}'s first vec4 in a draw's row, for {@code who} keying it per draw: a track, a renderer's bind.
     *
     * <pre>{@code
     * int at = definition.requireRow("_Glow", "track glow of sequence kakarot_kit:moves/kamehameha");   // at load
     * }</pre>
     *
     * @throws CgAssetException naming both when its archetype cannot read it per draw (no {@code cg_use rows}, a
     *                          sampler, an int): a track keying it would move nothing
     */
    public int requireRow(String property, String who) {
        int slot = rows.slot(property);
        if (slot < 0) {
            throw new CgAssetException(who + " keys " + property + " of " + id + ", which " + permutation.shader()
                    + " cannot read per draw: only a number, vector or colour of a shader declaring #pragma cg_use rows"
                    + " is a row property; its row properties are " + rows.properties());
        }
        return slot;
    }

    /** Writes a material's tree and compiles it: see {@link CgMaterialDefinition#builder}. */
    public static final class Builder {
        private final String id, shader;
        private final Map<String, Object> state = new LinkedHashMap<>(), values = new LinkedHashMap<>(),
                textures = new LinkedHashMap<>();
        private final List<String> keywords = new ArrayList<>();
        private Integer order;
        private String sortLayer;

        private Builder(String id, String shader) {
            this.id = id;
            this.shader = shader;
        }

        /** How it blends: Unreal's blend mode. */
        public Builder blend(CgBlendMode blend) {
            state.put("blend", BLEND_CLASS.get(blend));
            return this;
        }

        /** Opaque where its opacity reaches {@code clip}, discarded below: Unreal's masked blend and its clip value. */
        public Builder masked(float clip) {
            state.put("blend", "masked");
            state.put("mask_clip", clip);
            return this;
        }

        /** Both faces drawn. */
        public Builder twoSided() {
            state.put("two_sided", true);
            return this;
        }

        /** Which faces it culls: {@link CgCullState#BACK} or {@link CgCullState#FRONT}. */
        public Builder cull(CgCullState cull) {
            if (cull == CgCullState.NONE) return twoSided();
            state.put("cull", cull == CgCullState.FRONT ? "front" : "back");
            return this;
        }

        /** Whether it tests depth. */
        public Builder depthTest(boolean test) {
            state.put("depth_test", test);
            return this;
        }

        /** Whether it writes depth, over its blend's own. */
        public Builder depthWrite(boolean write) {
            state.put("depth_write", write);
            return this;
        }

        /** The shading model it names: {@code unlit}, {@code lit}, {@code subsurface}, or a kit's id. */
        public Builder shading(String model) {
            state.put("shading", model);
            return this;
        }

        /** A keyword its shader declares ({@code #pragma cg_feature}). */
        public Builder keyword(String keyword) {
            keywords.add(keyword);
            return this;
        }

        /** A number or vector property's value: one float, or as many as the property holds. */
        public Builder value(String property, float... v) {
            if (v.length == 1) {
                values.put(property, (double) v[0]);
            } else {
                List<Object> list = new ArrayList<>(v.length);
                for (float f : v) list.add((double) f);
                values.put(property, list);
            }
            return this;
        }

        /** A colour property's value, {@code 0xAARRGGBB}. */
        public Builder color(String property, int argb) {
            return color(property, argb, 1f);
        }

        /** A colour property's value, {@code 0xAARRGGBB}, its rgb times {@code intensity}. */
        public Builder color(String property, int argb, float intensity) {
            return value(property, (argb >> 16 & 0xFF) / 255f * intensity, (argb >> 8 & 0xFF) / 255f * intensity,
                    (argb & 0xFF) / 255f * intensity, (argb >>> 24) / 255f);
        }

        /** A sampler property's texture, sampled as the texture says. */
        public Builder texture(String property, String texture) {
            Map<String, Object> binding = new LinkedHashMap<>();
            binding.put("texture", texture);
            textures.put(property, binding);
            return this;
        }

        /** A sampler property's texture, sampled with {@code filter} and {@code address} on every axis. */
        public Builder texture(String property, String texture, CgSampler.Filter filter, CgSampler.Address address) {
            texture(property, texture);
            Map<String, Object> sampler = new LinkedHashMap<>();
            sampler.put("filter", filter.name().toLowerCase(Locale.ROOT));
            sampler.put("wrap", address == CgSampler.Address.WRAP ? "repeat" : address.name().toLowerCase(Locale.ROOT));
            @SuppressWarnings("unchecked") Map<String, Object> binding = (Map<String, Object>) textures.get(property);
            binding.put("sampler", sampler);
            return this;
        }

        /** Its order among its effect's draws, 0 to 255. */
        public Builder order(int order) {
            this.order = order;
            return this;
        }

        /** The world renderer's sort layer: {@code background}, {@code default}, {@code effects} or {@code overlay}. */
        public Builder sortLayer(String layer) {
            this.sortLayer = layer;
            return this;
        }

        /** Its file's tree, in the declaration's key order. */
        public Map<String, Object> tree() {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put(CgType.VERSION, CgLookKinds.MATERIAL.version());
            t.put("shader", shader);
            if (!state.isEmpty()) t.put("state", new LinkedHashMap<>(state));
            if (order != null) t.put("order", order);
            if (sortLayer != null) t.put("sort_layer", sortLayer);
            if (!keywords.isEmpty()) t.put("keywords", new ArrayList<>(keywords));
            if (!values.isEmpty()) t.put("values", new LinkedHashMap<>(values));
            if (!textures.isEmpty()) t.put("textures", new LinkedHashMap<>(textures));
            return t;
        }

        /**
         * Validated against the {@code material} declaration and compiled.
         *
         * @throws CgAssetException naming what the declaration or its shader refuses
         */
        public CgMaterialDefinition build() {
            CgAssetData asset = CgAssetData.of(id, CgLookKinds.MATERIAL, tree(), null);
            CgValidation v = asset.validate(CgValidation.Usecase.LOAD);
            if (!v.valid()) throw new CgAssetException(id + ": " + v.issues());
            return CgMaterialDefinitions.compile(asset);
        }

        private static final Map<CgBlendMode, String> BLEND_CLASS = new EnumMap<>(CgBlendMode.class);

        static {
            BLEND_CLASS.put(CgBlendMode.OPAQUE, "opaque");
            BLEND_CLASS.put(CgBlendMode.ALPHA, "translucent");
            BLEND_CLASS.put(CgBlendMode.PREMULTIPLIED, "premultiplied");
            BLEND_CLASS.put(CgBlendMode.ADDITIVE, "additive");
            BLEND_CLASS.put(CgBlendMode.MODULATE, "modulate");
            BLEND_CLASS.put(CgBlendMode.SUBTRACT, "subtract");
        }
    }
}
