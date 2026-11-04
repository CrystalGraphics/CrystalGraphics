package com.crystalgraphics.platform.rhi.command;

import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;

import java.util.Arrays;

/**
 * Resources moving from one use to the next (Unreal's {@code FRHITransition}): what the next use waits on and sees,
 * an image's layout included. Reused: fill it, record it, clear it; a steady frame allocates nothing.
 *
 * <pre>{@code
 * moves.clear()
 *         .buffer(particles, CgAccess.COMPUTE_WRITE, CgAccess.VERTEX_READ)
 *         .texture(CgTextureView.whole(density), CgAccess.COMPUTE_WRITE, CgAccess.SAMPLED_READ);
 * cmd.transition(moves);                  // here, at once
 * }</pre>
 *
 * <p>Split, where work touching none of them fits between the writer and the reader:</p>
 *
 * <pre>{@code
 * cmd.beginTransitions(moves);            // the writer is done
 * ... other work ...
 * cmd.endTransitions(moves);              // the reader may start
 * }</pre>
 *
 * <ul>
 *   <li>A buffer takes no image access ({@link CgAccess#IMAGE_ONLY}); an image no buffer access
 *   ({@link CgAccess#BUFFER_ONLY}). Both are refused here.</li>
 *   <li>A begun batch is ended, unchanged, before it is cleared or the frame ends.</li>
 * </ul>
 */
public final class CgTransition {

    private int count;
    private CgGpuBuffer[] buffers = new CgGpuBuffer[8];
    private CgTextureView[] textures = new CgTextureView[8];
    private int[] from = new int[8];
    private int[] to = new int[8];

    public CgTransition clear() {
        Arrays.fill(buffers, 0, count, null);
        Arrays.fill(textures, 0, count, null);
        count = 0;
        return this;
    }

    public CgTransition buffer(CgGpuBuffer buffer, int from, int to) {
        if (((from | to) & CgAccess.IMAGE_ONLY) != 0)
            throw new IllegalArgumentException(buffer.label() + ": a buffer moving " + CgAccess.names(from) + " to "
                    + CgAccess.names(to));
        add(buffer, null, from, to);
        return this;
    }

    public CgTransition texture(CgTextureView texture, int from, int to) {
        if (((from | to) & CgAccess.BUFFER_ONLY) != 0)
            throw new IllegalArgumentException(texture.texture().label() + ": an image moving " + CgAccess.names(from)
                    + " to " + CgAccess.names(to));
        add(null, texture, from, to);
        return this;
    }

    private void add(CgGpuBuffer buffer, CgTextureView texture, int f, int t) {
        if (count == buffers.length) {
            int n = count * 2;
            buffers = Arrays.copyOf(buffers, n);
            textures = Arrays.copyOf(textures, n);
            from = Arrays.copyOf(from, n);
            to = Arrays.copyOf(to, n);
        }
        buffers[count] = buffer;
        textures[count] = texture;
        from[count] = f;
        to[count] = t;
        count++;
    }

    public int count() { return count; }

    /** The buffer at {@code i}, null where it is an image. */
    public CgGpuBuffer buffer(int i) { return buffers[i]; }

    /** The image at {@code i}, null where it is a buffer. */
    public CgTextureView texture(int i) { return textures[i]; }

    public int from(int i) { return from[i]; }

    public int to(int i) { return to[i]; }
}
