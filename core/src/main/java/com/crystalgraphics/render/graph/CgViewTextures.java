package com.crystalgraphics.render.graph;

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
 * CgBindings parameters = view.bindings(rhi);           // each texture's storage as it is now
 * }</pre>
 */
final class CgViewTextures {

    private final CgTexture[] textures = new CgTexture[8];
    private final CgBindings bindings = new CgBindings();
    private int version, built = -1;

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

    /** The textures as the view set's bindings, each resolved to its storage now. */
    CgBindings bindings(CgRHI rhi) {
        if (built == version) return bindings;
        bindings.clear();
        for (int b = 0; b < textures.length; b++) {
            CgTexture texture = textures[b];
            CgTextureView view = texture == null ? null : texture.rhiView();
            if (view != null) bindings.texture(b, view, CgDeviceSamplers.of(rhi, null, texture));
        }
        built = version;
        return bindings;
    }
}
