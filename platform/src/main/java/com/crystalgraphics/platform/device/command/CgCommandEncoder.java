package com.crystalgraphics.platform.device.command;

import com.crystalgraphics.platform.device.resource.CgGpuBuffer;
import com.crystalgraphics.platform.device.resource.CgGpuSampler;
import com.crystalgraphics.platform.device.resource.CgGpuTexture;
import com.crystalgraphics.platform.device.resource.CgTextureRegion;
import com.crystalgraphics.platform.device.resource.CgTextureView;
import com.crystalgraphics.platform.device.resource.CgTimerQuery;

import java.nio.ByteBuffer;

/**
 * The current frame's commands, in the order they run. Transfers happen between passes: every method but the
 * timers throws while a pass from {@link #beginPass} is open.
 *
 * <pre>{@code
 * CgCommandEncoder enc = device.encoder();
 * enc.writeTexture(atlas, CgTextureRegion.of2D(0, x, y, w, h), rows);   // outside a pass
 * CgRenderPass pass = enc.beginPass(desc);
 * ... draws ...
 * pass.end();
 * }</pre>
 *
 * <p>Coordinates are memory rows: {@code y = 0} is the first row in memory, which GL calls the bottom.</p>
 */
public interface CgCommandEncoder {

    CgRenderPass beginPass(CgPassDesc desc);
    /** A compute pass. Transfers and barriers may sit between its dispatches; a render pass may not open inside it. */
    CgComputePass beginCompute(String label);
    /** {@code buffer}'s uses at {@code from} finished before {@code to}, writes visible: {@link CgAccess} bits. Outside a render pass. */
    void bufferBarrier(CgGpuBuffer buffer, int from, int to);
    /** The same for a texture, in the layout {@code to} reads it in. Outside a render pass. */
    void imageBarrier(CgGpuTexture texture, int from, int to);
    /** Every resource's uses at {@code from} before {@code to}: GL's {@code glMemoryBarrier}, which names none. */
    void memoryBarrier(int from, int to);

    /** Copies {@code data}'s remaining bytes into a device-local buffer, through staging. */
    void writeBuffer(CgGpuBuffer dst, long dstOffset, ByteBuffer data);

    void copyBuffer(CgGpuBuffer src, long srcOffset, CgGpuBuffer dst, long dstOffset, long size);

    /** {@code value} into every four bytes of a range: offset and size multiples of 4. Outside a render pass. */
    void fillBuffer(CgGpuBuffer dst, long dstOffset, long size, int value);

    /** Writes tightly packed texels in {@code dst}'s format; {@code data}'s remaining bytes must fill the region. */
    void writeTexture(CgGpuTexture dst, CgTextureRegion region, ByteBuffer data);

    /** Two regions of the same size and compatible formats. */
    void copyTexture(CgGpuTexture src, CgTextureRegion srcRegion, CgGpuTexture dst, CgTextureRegion dstRegion);

    /**
     * Scales {@code src}'s rectangle onto {@code dst}'s; a rectangle whose second corner comes first flips.
     * Depth and stencil blit with {@link CgGpuSampler.Filter#NEAREST} only.
     */
    void blit(CgTextureView src, int sx0, int sy0, int sx1, int sy1,
              CgTextureView dst, int dx0, int dy0, int dx1, int dy1, CgGpuSampler.Filter filter);

    /** Resolves a multisampled view into a single-sampled one of the same size. */
    void resolve(CgTextureView src, CgTextureView dst);

    /** Fills every mip below 0 from mip 0. */
    void generateMipmaps(CgGpuTexture texture);

    /** Reads texels back, tightly packed; waits for the GPU. */
    void readTexture(CgGpuTexture src, CgTextureRegion region, ByteBuffer out);

    /** Reads {@code out.remaining()} bytes from {@code srcOffset} back into {@code out}; waits for the GPU. */
    void readBuffer(CgGpuBuffer src, long srcOffset, ByteBuffer out);

    /** Copies texels into a buffer, tightly packed, without waiting. */
    void copyTextureToBuffer(CgGpuTexture src, CgTextureRegion region, CgGpuBuffer dst, long dstOffset);

    /**
     * What follows, dispatches, barriers and transfers but no render pass, goes to a compute queue beside the frame's
     * and starts after everything recorded before it, until {@link #endAsync}. A device with no such queue records it in
     * order. Outside a pass.
     *
     * <pre>{@code
     * enc.beginAsync();
     * ... a compute pass ...
     * long done = enc.endAsync();
     * ... draws that do not touch what it wrote, overlapping it ...
     * enc.waitAsync(done);
     * ... what reads it ...
     * }</pre>
     */
    void beginAsync();

    /** Back to the frame's queue: answers the point {@link #waitAsync} waits for, 0 on a device that ran it in order. */
    long endAsync();

    /** What follows on the frame's queue runs after the async work up to {@code point}. Outside a pass. */
    void waitAsync(long point);

    /**
     * Runs everything recorded this frame and waits for it, the frame staying open: what reading host-visible memory
     * the GPU wrote this frame needs. Outside a render pass.
     *
     * @throws IllegalStateException on a device whose host submits, since waiting for it would deadlock
     */
    void finish();

    /** Allowed inside a pass. */
    void beginTimer(CgTimerQuery query);

    /** Allowed inside a pass. */
    void endTimer(CgTimerQuery query);

    /**
     * Writes the GPU's clock into {@code query} once the commands before it finish: its {@code resultNanos()} is then
     * that time, in nanoseconds of a clock only differences of mean anything. Allowed inside a pass, and while
     * another query times.
     */
    void timestamp(CgTimerQuery query);
}
