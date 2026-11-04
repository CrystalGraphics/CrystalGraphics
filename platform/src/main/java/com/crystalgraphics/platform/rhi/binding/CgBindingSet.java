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
 * <ul>
 *   <li>Its contents are fixed when made: another texture is another set. What a buffer holds may change.</li>
 *   <li>Every slot of its layout is given, and the layout is not a pushed one.</li>
 * </ul>
 */
public interface CgBindingSet extends CgRHIResource {

    CgBindingLayout layout();
}
