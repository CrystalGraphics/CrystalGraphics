package com.crystalgraphics.api.material;

import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.tree.CgAssetException;
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
 */
@Desugar
public record CgMaterialDefinition(String id, @Nullable String parent, CgMaterialPermutation permutation,
                                   Map<String, float[]> constants, Map<String, Object> forms,
                                   Map<String, Texture> textures, CgMaterialRowLayout rows, Draw draw, int order,
                                   String sortLayer, @Nullable Map<Object, Object> post) {

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
}
