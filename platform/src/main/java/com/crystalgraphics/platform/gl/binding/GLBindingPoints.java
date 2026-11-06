package com.crystalgraphics.platform.gl.binding;

import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineLayout;

import java.util.Arrays;

/**
 * Where a pipeline layout's slots bind on GL, as Unreal gives a program's blocks {@code FirstUniformBuffer + index}
 * (`OpenGLShaders.cpp`), here by set: each set, in order, takes a range of each class of binding point (uniform
 * buffers, storage buffers, texture units, image units) as wide as its slots of that class, which take it in binding
 * order. Dense, as GL's limits are few: a kernel's images at set 2 binding 96 are image unit 0. Push constants take the
 * uniform point after every set's.
 *
 * <pre>{@code
 * GLBindingPoints points = new GLBindingPoints(layout);
 * int unit = points.point(1, 2, GLBindingPoints.TEXTURE);   // set 1, binding 2, a sampled texture
 * }</pre>
 */
public final class GLBindingPoints {

    public static final int UNIFORM = 0, STORAGE = 1, TEXTURE = 2, IMAGE = 3;

    private final int[] base;   // [set * 4 + class]
    /** [set * 4 + class]: that class's bindings in the set, ascending; binding {@code bindings[i]} is at base + i. */
    private final int[][] bindings;
    private final int pushConstants;

    public GLBindingPoints(CgPipelineLayout layout) {
        int sets = layout.sets().size();
        base = new int[sets * 4];
        bindings = new int[sets * 4][];
        int[] next = new int[4];
        int[] count = new int[4];
        for (int s = 0; s < sets; s++) {
            CgBindingLayout set = layout.sets().get(s);
            boolean heap = set instanceof GLBindingLayout g && g.heap;
            Arrays.fill(count, 0);
            for (CgBindingLayout.Slot slot : set.slots()) count[heap ? STORAGE : classOf(slot.type())]++;
            for (int c = 0; c < 4; c++) {
                base[s * 4 + c] = next[c];
                bindings[s * 4 + c] = new int[count[c]];
                next[c] += count[c];
            }
            Arrays.fill(count, 0);
            for (CgBindingLayout.Slot slot : set.slots()) {
                int c = heap ? STORAGE : classOf(slot.type());
                bindings[s * 4 + c][count[c]++] = slot.binding();
            }
            for (int c = 0; c < 4; c++) Arrays.sort(bindings[s * 4 + c]);
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

    /**
     * The point of {@code binding} in {@code set}, of class {@code kind}.
     *
     * @throws IllegalArgumentException for a binding the layout has no slot of that class at
     */
    public int point(int set, int binding, int kind) {
        int point = find(set, binding, kind);
        if (point < 0) throw new IllegalArgumentException("set " + set + " binding " + binding + " is no class " + kind + " slot");
        return point;
    }

    /**
     * {@link #point}, or -1 where the layout has no such slot: a set bound for another pipeline's layout holds slots
     * this one never reads.
     */
    public int find(int set, int binding, int kind) {
        if (set * 4 >= base.length)
            throw new IllegalArgumentException("set " + set + " is outside a layout of " + base.length / 4);
        int i = Arrays.binarySearch(bindings[set * 4 + kind], binding);
        return i < 0 ? -1 : base[set * 4 + kind] + i;
    }

    /** Every set's bases, bindings and the push point: two layouts with the same string bind alike. */
    @Override
    public String toString() {
        return Arrays.toString(base) + " " + Arrays.deepToString(bindings) + " push " + pushConstants;
    }

    /** The uniform buffer point push constants bind at. */
    public int pushConstants() { return pushConstants; }
}
