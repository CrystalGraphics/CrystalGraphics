package com.crystalgraphics.compute.source;

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
 * @param lowerable whether a tier below compute can hold the element (gpu-compute §6.2): a 4-, 8- or 16-byte scalar
 *                  or vector, or a struct of only 16-byte vectors
 * @param scalar    whether the element is one {@code float}, {@code int} or {@code uint}: what {@code NAME_ADD},
 *                  {@code NAME_MIN} and {@code NAME_MAX} take
 */
public record CgBufferDecl(String name, String display, String element, CgBufferAccess access, int stride,
                           boolean lowerable, boolean scalar, int index) {

    /** {@code _cg_STATE}: the array the generated accessors index. */
    public String array() { return "_cg_" + name; }
}
