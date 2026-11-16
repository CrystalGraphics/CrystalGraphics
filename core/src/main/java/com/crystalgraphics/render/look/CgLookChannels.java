package com.crystalgraphics.render.look;

import javax.annotation.Nullable;

import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.post.settings.CgPostSettings;
import com.crystalgraphics.render.view.CgViewKey;

/**
 * A firing's looks, by channel: which look each of the look weights' four channels is ({@code grade(look)} on what
 * drew), its id in the custom stencil ({@code CgLookIds}), and the light and cover its translucent draws added, one RGBA16F
 * layer a channel. Published by the world renderer as {@link #KEY}; the look weights read it, the composite tones each
 * channel by its look.
 *
 * <pre>{@code
 * CgLookChannels looks = post.view().read(CgLookChannels.KEY);
 * for (int k = 0; k < CgLookChannels.MAX; k++) {
 *     if (looks.look(k) == null) continue;
 *     int id = looks.id(k);                    // what its solids wrote in custom stencil bits 5-7
 *     CgGraphTexture light = looks.layer(k);   // its translucent light and cover, or null where it drew none
 * }
 * }</pre>
 *
 * <ul>
 *   <li>A channel keeps its look from frame to frame while the look draws, so a table baked for it stays baked.</li>
 *   <li>At most {@link #MAX} looks a frame; a further look's draws keep the frame's look, warned once by name.</li>
 * </ul>
 */
public final class CgLookChannels {

    /** Where the scene renderer publishes them in its view, a frame under the HDR scene with a graded draw. */
    public static final CgViewKey<CgLookChannels> KEY = new CgViewKey<>("crystalgraphics:look_channels");

    /** The most looks a frame: the look weights' channels. */
    public static final int MAX = 4;

    private final CgGraphTexture[] layers = new CgGraphTexture[MAX];
    private final CgPostSettings[] looks = new CgPostSettings[MAX];
    private final int[] ids = new int[MAX];

    /** Engine side: every channel empty, a new firing. */
    public void reset() {
        for (int k = 0; k < MAX; k++) {
            looks[k] = null;
            layers[k] = null;
            ids[k] = 0;
        }
    }

    /** Engine side: channel {@code k} holds {@code look}, whose solids write {@code id} in the custom stencil. */
    public void setLook(int k, CgPostSettings look, int id) {
        looks[k] = look;
        ids[k] = id;
    }

    /** Engine side: channel {@code k}'s translucent light and cover. */
    public void setLayer(int k, @Nullable CgGraphTexture layer) {
        layers[k] = layer;
    }

    /** Channel {@code k}'s look this firing; null where none draws. */
    @Nullable
    public CgPostSettings look(int k) {
        return looks[k];
    }

    /** Channel {@code k}'s look id ({@code CgLookIds}): what its solids wrote in custom stencil bits 5-7; 0 for none. */
    public int id(int k) {
        return looks[k] == null ? 0 : ids[k];
    }

    /** Channel {@code k}'s layer: rgb the light its translucent draws added, a how much they covered. Null where none drew. */
    @Nullable
    public CgGraphTexture layer(int k) {
        return looks[k] == null ? null : layers[k];
    }

    /** Whether any channel holds a look this firing. */
    public boolean any() {
        for (int k = 0; k < MAX; k++) if (looks[k] != null) return true;
        return false;
    }
}
