package com.crystalgraphics.vfx.particle.renderer;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.settings.CgQuality;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * How an emitter's particles draw: one renderer of its {@code renderers:}, compiled from the file. Mirrors Niagara's
 * {@code UNiagaraRendererProperties}: an emitter holds several, each drawing the same particles with its own material.
 * {@link CgVfxRenderers} compiles one from its tree and builds one in Java.
 *
 * <pre>{@code
 * CgVfxRendererProperties r = CgVfxRenderers.compile("sparks", entry);   // a renderers: entry of an emitter file
 * if (r instanceof CgVfxSpriteRendererProperties sprite) draw(sprite.facing(), sprite.common().material());
 * Map<String, Object> written = r.tree();                                // what the file holds, as compiled
 * }</pre>
 *
 * <ul>
 *   <li>{@link #tree()} is the renderer as declared ({@code CgParticleKinds.RENDERERS}): the writer writes it back
 *       unchanged, so a file round-trips through its renderers byte for byte.</li>
 *   <li>A kind no renderer is built for yet fails at compile, naming what builds it.</li>
 * </ul>
 */
public sealed interface CgVfxRendererProperties permits CgVfxSpriteRendererProperties, CgVfxMeshRendererProperties, CgVfxBeamRendererProperties,
        CgVfxRibbonRendererProperties, CgVfxArcRendererProperties,
        CgVfxSequenceRendererProperties, CgVfxLightRendererProperties {

    /** What every renderer holds. */
    Common common();

    /** The renderer as its file declares it: its id, {@code kind}, then its fields. */
    Map<String, Object> tree();

    /**
     * The fields every renderer kind shares.
     *
     * @param id              its id among its emitter's renderers
     * @param enabled         whether it draws
     * @param quality         the tier it draws from, null for every tier
     * @param sortOrderHint   its place among its emitter's renderers, Niagara's {@code SortOrderHint}
     * @param motionVectors   whether it writes motion vectors
     * @param material        its material asset's id
     * @param definition      that material compiled, null where its emitter compiled with no dependencies to ask
     * @param bind            material parameter to the user parameter it reads per draw, {@code user.<name>}
     * @param values          material parameter to a constant, this renderer's alone
     * @param keywords        material keywords it enables
     * @param dynamicParameterIndex which dynamic parameter each index of {@code cg_dynamic_parameter} reads
     */
    record Common(String id, boolean enabled, @Nullable CgQuality quality, int sortOrderHint, boolean motionVectors,
                  @Nullable String material, @Nullable CgMaterialDefinition definition, Map<String, Object> bind,
                  Map<String, Object> values, List<String> keywords, CgVfxDynamicParameterIndex dynamicParameterIndex) {
    }
}
