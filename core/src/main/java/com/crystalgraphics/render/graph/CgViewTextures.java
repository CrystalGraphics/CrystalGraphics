package com.crystalgraphics.render.graph;

import com.crystalgraphics.gpu.resource.CgFallbackTextures;
import com.crystalgraphics.render.draw.CgExecutedBlocks;
import com.crystalgraphics.render.draw.CgFrameBindings;
import com.crystalgraphics.render.shader.CgBindless;
import javax.annotation.Nullable;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.binding.CgBindings;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.render.draw.CgDeviceSamplers;
import com.crystalgraphics.render.shader.CgBindingModel;

import java.util.Arrays;

/**
 * The view textures a pass states as it executes, over those its snapshot recorded: a scene copy made for it, the
 * depth it samples in place. What its view set takes ahead of the snapshot ({@code CgDeviceSets.set}'s parameters).
 *
 * <pre>{@code
 * view.clear();                                         // a pass begins
 * view.put(CgBindingModel.SCENE_COLOR, copy.color);    // after the copy, outside the render pass
 * CgBindings parameters = view.bindings(rhi, table, constants, executed);   // each texture's storage as it is now
 * }</pre>
 */
final class CgViewTextures {

    private final CgTexture[] textures = new CgTexture[8];
    private final CgBindings bindings = new CgBindings();
    private int version, built = -1, builtConstants = -1;
    /**
     * The bindless path's index block: the view's textures at bindings 1 to 5 (scene depth, scene colour, lightmap,
     * media, local lights), one uint each.
     */
    private static final int FIRST = CgBindingModel.SCENE_DEPTH.binding(), COUNT = 5;
    private static final int[] INDEX_OFFSETS = {0, 1, 2, 3, 4};
    private final CgTexture[] indexed = new CgTexture[COUNT];

    /** Forgets every texture: a pass begins. */
    void clear() {
        Arrays.fill(textures, null);
        version++;
    }

    /** {@code texture} at {@code slot}, one of the view set's. */
    void put(CgBindingModel.Slot slot, CgTexture texture) {
        if (slot.set() != CgBindingModel.VIEW) throw new IllegalArgumentException(slot + " is not a view slot");
        textures[slot.binding()] = texture;
        version++;
    }

    /** A number that moves whenever a texture is put or the textures are cleared: a kept view set is stale then. */
    int version() {
        return version;
    }

    /**
     * The textures as the view set's bindings, each resolved to its storage now: bound, or on the bindless path their
     * indices in a block written now into {@code executed}'s ring, those the pass did not state read from its {@code constants} entry of
     * {@code table}. Bound wherever a handle would freeze the host's textures ({@link CgBindless#viewTexturesBound}).
     */
    CgBindings bindings(CgRHI rhi, CgFrameBindings table, int constants, CgExecutedBlocks executed) {
        if (built == version && builtConstants == constants) return bindings;
        bindings.clear();
        if (CgBindless.active() && !CgBindless.viewTexturesBound()) {
            for (int i = 0; i < COUNT; i++) {
                int binding = FIRST + i;
                CgTexture t = textures[binding] != null ? textures[binding] : stated(table, constants, binding);
                indexed[i] = t != null ? t : empty(binding);
            }
            executed.values(null, 8, INDEX_OFFSETS, indexed, null, CgBindingModel.VIEW_TEXTURE_INDICES.binding(), bindings);
        } else {
            for (int b = 0; b < textures.length; b++) {
                CgTexture texture = textures[b];
                CgTextureView view = texture == null ? null : texture.rhiView();
                if (view != null) bindings.texture(b, view, CgDeviceSamplers.of(rhi, null, texture));
            }
        }
        built = version;
        builtConstants = constants;
        return bindings;
    }

    /** What view {@code binding} reads in a pass that names none: cg_env's empties, local lights black, media white. */
    private static CgTexture empty(int binding) {
        if (binding == CgBindingModel.LOCAL_LIGHTS.binding()) return CgFallbackTextures.BLACK_VOLUME;
        if (binding == CgBindingModel.MEDIA.binding()) return CgFallbackTextures.WHITE_VOLUME;
        return CgFallbackTextures.WHITE_1x1;
    }

    /** The texture the pass's constants entry names at view {@code binding}; null for none. */
    @Nullable
    private static CgTexture stated(CgFrameBindings table, int constants, int binding) {
        int slot = CgBindingModel.pack(CgBindingModel.VIEW, binding);
        for (int t = 0; t < table.textures(constants); t++) if (table.textureUnit(constants, t) == slot) return table.texture(constants, t);
        return null;
    }
}
