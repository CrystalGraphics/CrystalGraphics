package com.crystalgraphics.render.world;

import javax.annotation.Nullable;

import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.post.volume.CgPostSettings;
import com.crystalgraphics.render.stage.CgFrameKey;

/**
 * A firing's graded layers: the light each grade's draws ({@code Draw.grade}) added to the HDR scene, premultiplied, and
 * how much of what was behind they covered, one RGBA16F layer a grade. Published by the world renderer as
 * {@link #KEY}; the post stack blooms and tones each layer's light as its grade says and adds only
 * that over the picture, so what no graded draw reached keeps the frame's own look.
 *
 * <pre>{@code
 * CgGradeLayers layers = post.resources().get(CgGradeLayers.KEY);
 * for (int k = 0; k < CgGradeLayers.MAX; k++) {
 *     if (layers.look(k) == null) continue;
 *     bloom(layers.layer(k), layers.look(k));            // its own bloom, from its own light
 * }
 * }</pre>
 *
 * <ul>
 *   <li>A slot keeps its grade from frame to frame while the grade draws, so a table baked for it stays baked.</li>
 *   <li>At most {@link #MAX} grades a frame; a further grade's draws keep the frame's look, warned once.</li>
 * </ul>
 */
public final class CgGradeLayers {

    /** Where the world renderer publishes them, in a transparent firing under the HDR scene with a graded draw. */
    public static final CgFrameKey<CgGradeLayers> KEY = CgFrameKey.of("crystalgraphics:grade_layers", CgGradeLayers.class);

    /** The most grades a frame. */
    public static final int MAX = 2;

    final CgGraphTexture[] layers = new CgGraphTexture[MAX];
    final CgPostSettings[] looks = new CgPostSettings[MAX];

    /** Slot {@code k}'s grade this firing; null where none draws. */
    @Nullable
    public CgPostSettings look(int k) {
        return looks[k];
    }

    /** Slot {@code k}'s layer: rgb the light its draws added, a how much they covered. Null where none draws. */
    @Nullable
    public CgGraphTexture layer(int k) {
        return looks[k] == null ? null : layers[k];
    }
}
