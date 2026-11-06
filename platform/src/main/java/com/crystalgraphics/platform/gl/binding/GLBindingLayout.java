package com.crystalgraphics.platform.gl.binding;

import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;

import java.util.List;

/**
 * A binding layout: GL has none, so it is the slots alone, read when a pipeline layout gives its sets binding points
 * ({@link GLBindingPoints}). The bindless heap's is marked: its one slot is a storage buffer of handles on GL.
 */
public final class GLBindingLayout implements CgBindingLayout {

    private final String label;
    private final List<Slot> slots;
    private final boolean pushed;
    public final boolean heap;

    public GLBindingLayout(String label, List<Slot> slots, boolean pushed, boolean heap) {
        this.label = label;
        this.slots = List.copyOf(slots);
        this.pushed = pushed;
        this.heap = heap;
    }

    @Override public List<Slot> slots() { return slots; }

    @Override public boolean pushed() { return pushed; }

    @Override public String label() { return label; }
}
