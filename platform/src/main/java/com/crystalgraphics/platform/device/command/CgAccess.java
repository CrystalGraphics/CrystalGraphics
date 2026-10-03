package com.crystalgraphics.platform.device.command;

/**
 * Where a resource was last used, or is used next: a pipeline stage and a kind of access, as bits a barrier joins
 * with {@code |}. Vulkan takes both sides; GL's {@code glMemoryBarrier} takes the bits of the second.
 *
 * <pre>{@code
 * encoder.bufferBarrier(particles, CgAccess.COMPUTE_WRITE, CgAccess.VERTEX_READ);     // a kernel wrote what a draw reads
 * encoder.bufferBarrier(args, CgAccess.COMPUTE_WRITE, CgAccess.INDIRECT);             // ... a draw's arguments
 * encoder.imageBarrier(density, CgAccess.COMPUTE_WRITE, CgAccess.SAMPLED_READ);       // ... a texture a material samples
 * encoder.bufferBarrier(cells, CgAccess.VERTEX_READ | CgAccess.FRAGMENT_READ,         // two readers, then a write
 *         CgAccess.COMPUTE_WRITE);
 * }</pre>
 */
public final class CgAccess {

    /** A storage buffer or image read in a kernel. */
    public static final int COMPUTE_READ = 1;
    /** A storage buffer or image write in a kernel. */
    public static final int COMPUTE_WRITE = 1 << 1;
    /** A storage buffer read in a vertex shader: a record a draw pulls by index. */
    public static final int VERTEX_READ = 1 << 2;
    /** A storage buffer or image read in a fragment shader. */
    public static final int FRAGMENT_READ = 1 << 3;
    /** A uniform block, in any stage. */
    public static final int UNIFORM_READ = 1 << 4;
    /** A texture or buffer texture sampled in any stage. */
    public static final int SAMPLED_READ = 1 << 5;
    /** Vertex attributes. */
    public static final int VERTEX_INPUT = 1 << 6;
    /** Indices. */
    public static final int INDEX_INPUT = 1 << 7;
    /** A draw's or a dispatch's arguments. */
    public static final int INDIRECT = 1 << 8;
    public static final int COPY_READ = 1 << 9;
    public static final int COPY_WRITE = 1 << 10;
    /** Read by the CPU through a mapping, after the frame's fence. */
    public static final int HOST_READ = 1 << 11;
    /** A render target's colour. */
    public static final int COLOR_WRITE = 1 << 12;

    /** Storage access from any shader stage: a storage image is in its general layout for these. */
    public static final int STORAGE = COMPUTE_READ | COMPUTE_WRITE | VERTEX_READ | FRAGMENT_READ;
    /** Everything a draw reads or writes. */
    public static final int GRAPHICS = VERTEX_READ | FRAGMENT_READ | UNIFORM_READ | SAMPLED_READ | VERTEX_INPUT
            | INDEX_INPUT | INDIRECT | COLOR_WRITE;

    private static final String[] NAMES = {"COMPUTE_READ", "COMPUTE_WRITE", "VERTEX_READ", "FRAGMENT_READ",
            "UNIFORM_READ", "SAMPLED_READ", "VERTEX_INPUT", "INDEX_INPUT", "INDIRECT", "COPY_READ", "COPY_WRITE",
            "HOST_READ", "COLOR_WRITE"};

    private CgAccess() {}

    /** {@code COMPUTE_WRITE|VERTEX_READ}: the bits by name. */
    public static String names(int access) {
        if (access == 0) return "NONE";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < NAMES.length; i++) {
            if ((access & (1 << i)) == 0) continue;
            if (s.length() > 0) s.append('|');
            s.append(NAMES[i]);
        }
        return s.toString();
    }
}
