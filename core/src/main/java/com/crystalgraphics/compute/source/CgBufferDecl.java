package com.crystalgraphics.compute.source;

import com.crystalgraphics.api.buffer.CgBufferField;
import com.crystalgraphics.api.buffer.CgBufferLayout;

import java.util.List;

/**
 * A buffer a {@code .compute} declares in {@code Buffers { }}. Its kernels reach it through macros named after it, and
 * it is bound at storage binding point {@code index}, its place in the block.
 *
 * <pre>{@code
 * struct Particle { vec4 positionLife; vec4 velocitySeed; };
 * Buffers {
 *     STATE ("Particle state", Particle, readwrite)   // STATE(i), STATE_WRITE(p), STATE_LENGTH()
 *     BINS  ("Bins",           uint,     counter)     // BINS(i), BINS_INC(i), BINS_ADD(i, n)
 * }
 * }</pre>
 *
 * @param element   the GLSL type of one element: a scalar, a vector or a struct the file declares
 * @param stride    bytes from one element to the next, by std430
 * @param lowerable whether a tier below compute can hold the element: a 4-, 8- or 16-byte scalar
 *                  or vector, or a struct of only 16-byte vectors
 * @param scalar    whether the element is one {@code float}, {@code int} or {@code uint}: what {@code NAME_ADD},
 *                  {@code NAME_MIN} and {@code NAME_MAX} take
 * @param fields    where each field of the element sits, in declaration order; one unnamed field for a non-struct
 */
public record CgBufferDecl(String name, String display, String element, CgBufferAccess access, int stride,
                           boolean lowerable, boolean scalar, int index, List<CgElementField> fields) {

    /** {@code _cg_STATE}: the array the generated accessors index. */
    public String array() { return "_cg_" + name; }

    /**
     * Refuses {@code layout} unless it is this buffer's element field for field: one stride, and each of its fields at
     * the shader's offset with the shader's type.
     *
     * @throws IllegalArgumentException naming both, and the first difference
     */
    public void check(CgBufferLayout layout) {
        String both = layout.getGlslName() + " (" + layout.getStride() + " bytes) against " + name + "'s " + element
                + " (" + stride + " bytes)";
        if (layout.getStride() != stride) throw new IllegalArgumentException(both + ": the strides differ");
        for (int i = 0; i < layout.getFieldCount(); i++) {
            CgBufferField f = layout.getField(i);
            CgElementField theirs = field(f.getName());
            if (theirs == null) throw new IllegalArgumentException(both + ": the shader has no " + f.getName());
            if (theirs.offset() != f.getByteOffset() || !theirs.type().equals(f.getType().getGlslName())) {
                throw new IllegalArgumentException(both + ": " + f.getName() + " is " + f.getType().getGlslName() + " at "
                        + f.getByteOffset() + " here, " + theirs.type() + " at " + theirs.offset() + " there");
            }
        }
    }

    /** The field of that name, or null. */
    public CgElementField field(String name) {
        for (CgElementField f : fields) if (f.name().equals(name)) return f;
        return null;
    }

    /** Whether the element is a struct the file declares, not a scalar, vector or matrix. */
    public boolean struct() {
        return fields.size() != 1 || !fields.get(0).name().isEmpty();
    }
}
