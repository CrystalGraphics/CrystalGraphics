package com.crystalgraphics.render.graph;

/**
 * What a frame graph orders work on: a {@link CgGraphTexture} or a {@link CgGraphBuffer}. A pass reads and writes
 * them; the graph runs passes in the order their reads and writes were recorded, keeps transient storage for the
 * passes between a resource's first and last use, and puts a barrier wherever a kernel's access meets another.
 */
public abstract sealed class CgGraphResource permits CgGraphTexture, CgGraphBuffer {

    final String name;
    /** Render thread: how many executing steps recorded it, which may bind it ({@link CgGraphCheck}). */
    int allowed;

    CgGraphResource(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    /** What {@code view} is ordered and stored as: a history's previous version is its history. */
    static CgGraphResource resourceOf(CgGraphResource view) {
        return view instanceof CgGraphBuffer buffer ? buffer.resource()
                : view instanceof CgGraphTexture texture ? texture.resource() : view;
    }

    /** Whether its storage lives for one frame only, from the pool. */
    abstract boolean isTransient();

    /** Whether a pass writing it is an effect outside the frame, which no cull may remove. */
    abstract boolean outlivesFrame();
}
