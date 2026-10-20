package com.crystalgraphics.api.material;

import javax.annotation.Nullable;

import com.github.bsideup.jabel.Desugar;

/**
 * A shading model a material's {@code state.shading} names: the engine's {@code unlit}, {@code lit} and
 * {@code subsurface}, or one a game's kit registers under its namespace ({@code kakarot:cell_tone}). Unreal's
 * {@code EMaterialShadingModel}, open to a namespace rather than an enum.
 *
 * <pre>{@code
 * CgShadingModels.register(new CgShadingModel("kakarot:cell_tone", "lit", "CELL_TONE",
 *         "kakarot_kit:shaders/lib/cell_tone.glsl"));
 * }</pre>
 *
 * @param id      its name, {@code namespace:name} for any but the engine's three
 * @param base    the engine model it builds on, which sets lighting: {@code unlit}, {@code lit} or {@code subsurface}
 * @param keyword the archetype keyword that carries it, enabled on each material naming it; null for none
 * @param library the GLSL library its passes include, null for none
 */
@Desugar
public record CgShadingModel(String id, String base, @Nullable String keyword, @Nullable String library) {
}
