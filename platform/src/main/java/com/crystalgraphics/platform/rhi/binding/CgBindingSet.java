package com.crystalgraphics.platform.rhi.binding;

import com.crystalgraphics.platform.rhi.resource.CgRHIResource;

/**
 * Bindings made once and bound many times (a Vulkan descriptor set; Unreal's uniform buffer): a material's block and
 * textures, a view's buffers. Bound at its set index with {@code setBindingSet}; a pipeline's pushed set is
 * {@code pushBindings}' instead.
 *
 * <pre>{@code
 * CgBindingLayout materialLayout = rhi.createBindingLayout("material", slots, false);
 * CgBindingSet stone = rhi.createBindingSet("stone", materialLayout,
 *         new CgBindings().buffer(0, CgBindingLayout.Type.UNIFORM_BUFFER, params, 0, 64).texture(1, albedo, linear));
 * pass.setBindingSet(2, stone);
 * }</pre>
 *
 * <p>A layout's dynamic slots take their offsets at each bind, so a block whose place moves every frame keeps its set:</p>
 *
 * <pre>{@code
 * CgBindingSet frame = rhi.createBindingSet("frame", dynamicLayout,           // slot 0: a dynamic uniform buffer
 *         new CgBindings().buffer(0, CgBindingLayout.Type.UNIFORM_BUFFER, ring, 0, 256));   // its offset unused
 * offsets[0] = (int) ringOffsetThisFrame;
 * pass.setBindingSet(0, frame, offsets, 1);
 * }</pre>
 *
 * <ul>
 *   <li>Its contents are fixed when made: another texture is another set. What a buffer holds may change.</li>
 *   <li>Every slot of its layout is given, and the layout is not a pushed one.</li>
 *   <li>A dynamic slot's offset in the contents is not read: each bind gives it, aligned to
 *       {@code Limits.uniformOffsetAlignment}, its range inside the buffer.</li>
 * </ul>
 */
public interface CgBindingSet extends CgRHIResource {

    /** The offsets of a bind of a set with no dynamic slot. */
    int[] NO_OFFSETS = new int[0];

    CgBindingLayout layout();
}
