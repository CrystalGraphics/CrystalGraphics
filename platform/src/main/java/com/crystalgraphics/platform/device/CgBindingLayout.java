package com.crystalgraphics.platform.device;

import java.util.List;

/**
 * Every binding a program reads, in one set that is pushed per draw ({@link CgRenderPass#pushBindings}). Made
 * once per linked program from the link-time rewrite's table.
 *
 * <pre>{@code
 * CgBindingLayout layout = device.createBindingLayout("text", List.of(
 *         new CgBindingLayout.Slot(0, CgBindingLayout.Type.UNIFORM_BUFFER),     // CgFrameBlock
 *         new CgBindingLayout.Slot(1, CgBindingLayout.Type.STORAGE_BUFFER),     // CgObjectDataBuffer
 *         new CgBindingLayout.Slot(2, CgBindingLayout.Type.SAMPLED_TEXTURE)));  // the atlas
 * }</pre>
 */
public interface CgBindingLayout extends CgDeviceObject {

    enum Type { UNIFORM_BUFFER, STORAGE_BUFFER, TEXEL_BUFFER, SAMPLED_TEXTURE }

    /** Visible to both stages: a program's bindings are few, and splitting them buys nothing. */
    record Slot(int binding, Type type) {}

    List<Slot> slots();
}
