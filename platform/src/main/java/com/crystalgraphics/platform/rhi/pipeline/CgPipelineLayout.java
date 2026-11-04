package com.crystalgraphics.platform.rhi.pipeline;

import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;

import java.util.List;

/**
 * The binding layouts a pipeline reads, by set index, and the bytes of push constants it takes. A value: equal
 * layouts are one on the device, which builds each distinct one once (as Unreal's RHIs derive theirs from the shaders
 * and keep them below the API).
 *
 * <pre>{@code
 * CgPipelineLayout program = CgPipelineLayout.of(bindings);                      // one pushed set, no constants
 * CgPipelineLayout scene = new CgPipelineLayout(List.of(view, pass, material), 8); // sets 0-2; a draw index and pass bits
 * }</pre>
 *
 * <ul>
 *   <li>At most one set is pushed ({@link CgBindingLayout#pushed()}): {@link #pushSet()} is its index.</li>
 *   <li>Push constants are a multiple of 4 bytes, at most {@link #MAX_PUSH_CONSTANT_BYTES}.</li>
 * </ul>
 */
public record CgPipelineLayout(List<CgBindingLayout> sets, int pushConstantBytes) {

    /** Vulkan's guaranteed minimum, which every device here meets. */
    public static final int MAX_PUSH_CONSTANT_BYTES = 128;

    public CgPipelineLayout {
        sets = List.copyOf(sets);
        if (pushConstantBytes < 0 || pushConstantBytes % 4 != 0 || pushConstantBytes > MAX_PUSH_CONSTANT_BYTES)
            throw new IllegalArgumentException("push constants of " + pushConstantBytes + " bytes: a multiple of 4, at most "
                    + MAX_PUSH_CONSTANT_BYTES);
        int pushed = 0;
        for (CgBindingLayout s : sets) if (s.pushed()) pushed++;
        if (pushed > 1) throw new IllegalArgumentException(pushed + " pushed sets: a pipeline pushes at most one");
    }

    /** {@code sets} in order, no push constants. */
    public static CgPipelineLayout of(CgBindingLayout... sets) {
        return new CgPipelineLayout(List.of(sets), 0);
    }

    /** The index of the pushed set, -1 with none. */
    public int pushSet() {
        for (int i = 0; i < sets.size(); i++) if (sets.get(i).pushed()) return i;
        return -1;
    }
}
