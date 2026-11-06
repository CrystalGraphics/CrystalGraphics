package com.crystalgraphics.platform.rhi.binding;

import com.crystalgraphics.platform.rhi.resource.CgRHIResource;
import com.crystalgraphics.platform.rhi.command.CgRenderPass;

import java.util.List;

/**
 * The bindings of one set: what a pipeline reads there, by binding number and type. A pushed layout is filled per
 * draw ({@link CgRenderPass#pushBindings}); any other is filled once, as a {@link CgBindingSet}.
 *
 * <pre>{@code
 * CgBindingLayout text = rhi.createBindingLayout("text", List.of(
 *         new CgBindingLayout.Slot(0, CgBindingLayout.Type.UNIFORM_BUFFER),     // CgViewBlock
 *         new CgBindingLayout.Slot(1, CgBindingLayout.Type.STORAGE_BUFFER),     // CgObjectDataBuffer
 *         new CgBindingLayout.Slot(2, CgBindingLayout.Type.SAMPLED_TEXTURE)),   // the atlas
 *         true);                                                                // pushed per draw
 * }</pre>
 *
 * <p>A pipeline names its sets in a {@code CgPipelineLayout}, which takes at most one pushed layout.</p>
 */
public interface CgBindingLayout extends CgRHIResource {

    enum Type { UNIFORM_BUFFER, STORAGE_BUFFER, TEXEL_BUFFER, SAMPLED_TEXTURE, STORAGE_IMAGE }

    /** Visible to every stage of the pipeline: a program's bindings are few, and splitting them buys nothing. */
    record Slot(int binding, Type type) {}

    List<Slot> slots();

    /** Filled per draw by {@code pushBindings}, never made into a {@link CgBindingSet}. */
    boolean pushed();
}
