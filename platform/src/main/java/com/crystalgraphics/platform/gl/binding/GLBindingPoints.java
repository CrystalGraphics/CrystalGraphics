package com.crystalgraphics.platform.gl.binding;

import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineLayout;

/**
 * Where a pipeline layout's slots bind on GL, as Unreal gives a program's blocks {@code FirstUniformBuffer + index}
 * (`OpenGLShaders.cpp`), here by set: each set, in order, takes a range of each class of binding point (uniform
 * buffers, storage buffers, texture units, image units) as wide as its highest binding of that class. Push constants
 * take the uniform point after every set's.
 *
 * <pre>{@code
 * GLBindingPoints points = new GLBindingPoints(layout);
 * int unit = points.point(1, 2, GLBindingPoints.TEXTURE);   // set 1, binding 2, a sampled texture
 * }</pre>
 */
public final class GLBindingPoints {

    public static final int UNIFORM = 0, STORAGE = 1, TEXTURE = 2, IMAGE = 3;

    private final int[] base;   // [set * 4 + class]
    private final int pushConstants;

    public GLBindingPoints(CgPipelineLayout layout) {
        int sets = layout.sets().size();
        base = new int[sets * 4];
        int[] next = new int[4];
        for (int s = 0; s < sets; s++) {
            CgBindingLayout set = layout.sets().get(s);
            System.arraycopy(next, 0, base, s * 4, 4);
            boolean heap = set instanceof GLBindingLayout g && g.heap;
            for (CgBindingLayout.Slot slot : set.slots()) {
                int c = heap ? STORAGE : classOf(slot.type());
                next[c] = Math.max(next[c], base[s * 4 + c] + slot.binding() + 1);
            }
        }
        pushConstants = next[UNIFORM];
    }

    /** The binding-point class a slot of {@code type} takes. */
    public static int classOf(CgBindingLayout.Type type) {
        return switch (type) {
            case UNIFORM_BUFFER -> UNIFORM;
            case STORAGE_BUFFER -> STORAGE;
            case TEXEL_BUFFER, SAMPLED_TEXTURE -> TEXTURE;
            case STORAGE_IMAGE -> IMAGE;
        };
    }

    /** The point of {@code binding} in {@code set}, of class {@code kind}. */
    public int point(int set, int binding, int kind) {
        if (set * 4 >= base.length)
            throw new IllegalArgumentException("set " + set + " is outside a layout of " + base.length / 4);
        return base[set * 4 + kind] + binding;
    }

    /** The uniform buffer point push constants bind at. */
    public int pushConstants() { return pushConstants; }
}
