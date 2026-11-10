package com.crystalgraphics.platform.gl.binding;

import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.binding.CgBindingSet;
import com.crystalgraphics.platform.rhi.binding.CgBindings;

/** A binding set: its contents, copied once, bound at the points the drawing pipeline's layout gives its set. */
public final class GLBindingSet implements CgBindingSet {

    private final String label;
    private final CgBindingLayout layout;
    public final CgBindings contents;
    /** Each entry's place among a bind's dynamic offsets, or -1; and how many a bind gives. */
    public final int[] dynamicIndex;
    public final int dynamicCount;

    public GLBindingSet(String label, CgBindingLayout layout, CgBindings contents) {
        this.label = label;
        this.layout = layout;
        this.contents = copy(contents, new CgBindings());
        dynamicIndex = new int[this.contents.count()];
        for (int i = 0; i < dynamicIndex.length; i++) {
            dynamicIndex[i] = CgBindingLayout.dynamicIndex(layout.slots(), this.contents.binding(i));
        }
        dynamicCount = layout.dynamicCount();
    }

    /** {@code from}'s entries appended to {@code to}, cleared first. */
    public static CgBindings copy(CgBindings from, CgBindings to) {
        to.clear();
        for (int i = 0; i < from.count(); i++) {
            switch (from.type(i)) {
                case UNIFORM_BUFFER, STORAGE_BUFFER -> to.buffer(from.binding(i), from.type(i), from.buffer(i),
                        from.offset(i), from.size(i));
                case TEXEL_BUFFER -> to.texel(from.binding(i), from.buffer(i), from.offset(i), from.size(i),
                        from.texelFormat(i));
                case SAMPLED_TEXTURE -> to.texture(from.binding(i), from.view(i), from.sampler(i));
                case STORAGE_IMAGE -> to.image(from.binding(i), from.view(i));
            }
        }
        return to;
    }

    @Override public CgBindingLayout layout() { return layout; }

    @Override public String label() { return label; }
}
