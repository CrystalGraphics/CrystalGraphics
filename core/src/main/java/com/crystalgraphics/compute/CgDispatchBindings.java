package com.crystalgraphics.compute;

import com.crystalgraphics.compute.source.CgComputeSource;

/**
 * What one dispatch below compute is bound to, in GL names: what a lowered kernel ({@code CgLoweredKernel}) and a Java
 * body ({@code CgCpuRunner}) run over. Filled by the frame graph's executor, or by a direct dispatch; reusable, with
 * every index the file's own for its buffers and images.
 *
 * <pre>{@code
 * CgDispatchBindings b = new CgDispatchBindings(source);
 * b.buffer(state.index(), stateBuffer, 0, bytes);
 * b.elements(count, 1, 1);
 * lowered.dispatch(b);
 * }</pre>
 *
 * <ul>
 *   <li>A buffer's view starts at a whole element: below compute a buffer is read as a texture of words from its
 *       start, and the view's first element is a uniform.</li>
 *   <li>The property block and sampler properties are bound by the caller, as for a compute dispatch.</li>
 * </ul>
 */
public final class CgDispatchBindings {

    private final int[] buffer, counter;
    private final long[] offset, bytes, counterOffset;
    private final int[] image, imageTarget, level, levels, layer, width, height, depth;
    private int x, y, z;
    private boolean indirect;
    private int args;
    private long argsOffset;
    private long frame;

    public CgDispatchBindings(CgComputeSource source) {
        int n = source.buffers().size(), m = source.images().size();
        buffer = new int[n];
        counter = new int[n];
        offset = new long[n];
        bytes = new long[n];
        counterOffset = new long[n];
        image = new int[m];
        imageTarget = new int[m];
        level = new int[m];
        levels = new int[m];
        layer = new int[m];
        width = new int[m];
        height = new int[m];
        depth = new int[m];
    }

    /** {@code bytes} of GL buffer {@code name} from {@code offset} as declared buffer {@code index}. */
    public CgDispatchBindings buffer(int index, int name, long offset, long bytes) {
        buffer[index] = name;
        this.offset[index] = offset;
        this.bytes[index] = bytes;
        return this;
    }

    /** Append buffer {@code index}'s count: the {@code uint} at {@code offset} in GL buffer {@code name}. */
    public CgDispatchBindings counter(int index, int name, long offset) {
        counter[index] = name;
        counterOffset[index] = offset;
        return this;
    }

    /**
     * Mip {@code level} of texture {@code name} ({@code GL_TEXTURE_2D}, {@code _3D} or {@code _2D_ARRAY}, of
     * {@code levels} levels) as declared image {@code index}, that level being {@code width} x {@code height} x
     * {@code depth}; {@code layer} -1 for every layer.
     */
    public CgDispatchBindings image(int index, int name, int target, int level, int levels, int layer, int width,
                                    int height, int depth) {
        image[index] = name;
        imageTarget[index] = target;
        this.level[index] = level;
        this.levels[index] = levels;
        this.layer[index] = layer;
        this.width[index] = width;
        this.height[index] = height;
        this.depth[index] = depth;
        return this;
    }

    /** {@code x * y * z} elements. */
    public CgDispatchBindings elements(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
        indirect = false;
        return this;
    }

    /** Group counts from three {@code uint}s at {@code offset} in GL buffer {@code args}, written on the GPU. */
    public CgDispatchBindings indirect(int args, long offset) {
        this.args = args;
        this.argsOffset = offset;
        indirect = true;
        return this;
    }

    /** The frame these bindings run in, for what is known of a buffer within one frame. */
    public CgDispatchBindings frame(long frame) {
        this.frame = frame;
        return this;
    }

    // ── Read by the forms ─────────────────────────────────────────────────────

    /** Buffer {@code index}'s GL name, 0 where none is bound. */
    public int buffer(int index) { return buffer[index]; }

    public long offset(int index) { return offset[index]; }

    public long bytes(int index) { return bytes[index]; }

    public int counter(int index) { return counter[index]; }

    public long counterOffset(int index) { return counterOffset[index]; }

    public int image(int index) { return image[index]; }

    public int imageTarget(int index) { return imageTarget[index]; }

    public int level(int index) { return level[index]; }

    public int levels(int index) { return levels[index]; }

    public int layer(int index) { return layer[index]; }

    public int width(int index) { return width[index]; }

    public int height(int index) { return height[index]; }

    public int depth(int index) { return depth[index]; }

    public int x() { return x; }

    public int y() { return y; }

    public int z() { return z; }

    public boolean isIndirect() { return indirect; }

    public int args() { return args; }

    public long argsOffset() { return argsOffset; }

    public long frame() { return frame; }
}
