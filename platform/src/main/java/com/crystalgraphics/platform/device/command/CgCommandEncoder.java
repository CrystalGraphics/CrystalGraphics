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
    /** {@code buffer}'s writes at {@code from} made visible to {@code to}. Outside a render pass. */
    void bufferBarrier(CgGpuBuffer buffer, CgAccess from, CgAccess to);
    /** The same for a texture, in the layout {@code to} reads it in. Outside a render pass. */
    void imageBarrier(CgGpuTexture texture, CgAccess from, CgAccess to);
    /** Every resource's writes at {@code from} made visible to {@code to}: GL's {@code glMemoryBarrier}, which names none. */
    void memoryBarrier(CgAccess from, CgAccess to);

    /** Copies {@code data}'s remaining bytes into a device-local buffer, through staging. */
    void writeBuffer(CgGpuBuffer dst, long dstOffset, ByteBuffer data);

    void copyBuffer(CgGpuBuffer src, long srcOffset, CgGpuBuffer dst, long dstOffset, long size);

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

    /** Copies texels into a buffer, tightly packed, without waiting. */
    void copyTextureToBuffer(CgGpuTexture src, CgTextureRegion region, CgGpuBuffer dst, long dstOffset);

    /** Allowed inside a pass. */
    void beginTimer(CgTimerQuery query);

    /** Allowed inside a pass. */
    void endTimer(CgTimerQuery query);
}
