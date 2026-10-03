package com.crystalgraphics.platform.device.command;

/**
 * Where a resource was last used, or is used next: a pipeline stage and a kind of access, as a barrier between the
 * two names them. Vulkan takes both sides; GL's {@code glMemoryBarrier} takes the bits of the second.
 *
 * <pre>{@code
 * encoder.bufferBarrier(particles, CgAccess.COMPUTE_WRITE, CgAccess.VERTEX_READ);   // a kernel wrote what a draw reads
 * encoder.bufferBarrier(args, CgAccess.COMPUTE_WRITE, CgAccess.INDIRECT);           // ... a draw's arguments
 * encoder.imageBarrier(density, CgAccess.COMPUTE_WRITE, CgAccess.SAMPLED_READ);     // ... a texture a material samples
 * }</pre>
 */
public enum CgAccess {
    /** A storage buffer or image read in a kernel. */
    COMPUTE_READ,
    /** A storage buffer or image write in a kernel. */
    COMPUTE_WRITE,
    /** A storage buffer read in a vertex shader: a record a draw pulls by index. */
    VERTEX_READ,
    /** A storage buffer or image read in a fragment shader. */
    FRAGMENT_READ,
    /** A uniform block, in any stage. */
    UNIFORM_READ,
    /** A texture sampled in any stage. */
    SAMPLED_READ,
    /** Vertex attributes. */
    VERTEX_INPUT,
    /** Indices. */
    INDEX_INPUT,
    /** A draw's or a dispatch's arguments. */
    INDIRECT,
    COPY_READ,
    COPY_WRITE,
    /** Read by the CPU through a mapping, after the frame's fence. */
    HOST_READ,
    /** A render target's colour. */
    COLOR_WRITE
}
