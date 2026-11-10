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

    /**
     * Visible to every stage of the pipeline: a program's bindings are few, and splitting them buys nothing.
     *
     * @param dynamic a uniform or storage buffer whose offset is given each time its set is bound, the set holding its
     *                buffer and range: one set serves every place a per-frame ring puts it (Vulkan's
     *                {@code *_BUFFER_DYNAMIC}, WebGPU's {@code hasDynamicOffset})
     */
    record Slot(int binding, Type type, boolean dynamic) {
        public Slot {
            if (dynamic && type != Type.UNIFORM_BUFFER && type != Type.STORAGE_BUFFER)
                throw new IllegalArgumentException("a dynamic " + type + " at " + binding);
        }

        public Slot(int binding, Type type) {
            this(binding, type, false);
        }
    }

    List<Slot> slots();

    /** Its dynamic slots: the offsets each bind of one of its sets gives, in binding order. */
    default int dynamicCount() {
        List<Slot> slots = slots();
        int n = 0;
        for (int i = 0; i < slots.size(); i++) if (slots.get(i).dynamic()) n++;
        return n;
    }

    /** Where {@code binding}'s offset is among a bind's dynamic offsets (binding order), or -1 where it is not dynamic. */
    static int dynamicIndex(List<Slot> slots, int binding) {
        int at = 0;
        boolean dynamic = false;
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            if (!s.dynamic()) continue;
            if (s.binding() == binding) dynamic = true;
            else if (s.binding() < binding) at++;
        }
        return dynamic ? at : -1;
    }

    /** Filled per draw by {@code pushBindings}, never made into a {@link CgBindingSet}. */
    boolean pushed();
}
