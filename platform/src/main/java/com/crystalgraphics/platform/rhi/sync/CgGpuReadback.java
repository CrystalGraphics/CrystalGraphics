package com.crystalgraphics.platform.rhi.sync;

import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.command.CgCommandList;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureRegion;

import java.nio.ByteBuffer;

/**
 * Bytes copied off the GPU without a stall (Unreal's {@code FRHIGPUBufferReadback} and {@code FRHIGPUTextureReadback}):
 * a copy into memory the CPU reads, there once the frame that recorded it has retired, a frame or two later. Reused:
 * one per value in flight, enqueued again once read.
 *
 * <pre>{@code
 * CgGpuReadback counts = new CgGpuReadback(rhi, "particle counts", 16);
 * counts.enqueue(cmd, countBuffer, 0, 16);           // after the transition to COPY_READ that its writer needs
 * ...
 * if (counts.ready()) alive = counts.data().getInt(0);   // a later frame; never waits
 * }</pre>
 *
 * <p>A texture's texels come back tightly packed:</p>
 *
 * <pre>{@code
 * heights.enqueue(cmd, heightMap, CgTextureRegion.of2D(0, 0, 0, 64, 64));
 * }</pre>
 *
 * <ul>
 *   <li>{@link #data()} is the readback's memory itself, in native order: read it before enqueueing again.</li>
 *   <li>Outside a render pass, as every copy.</li>
 *   <li>{@link #release()} it as any device object; it is gone once its last frame retires.</li>
 * </ul>
 */
public final class CgGpuReadback {

    private final CgRHI rhi;
    private final CgGpuBuffer memory;
    private long frame = -1;
    private long size;

    /** @param capacity the most bytes one copy brings back */
    public CgGpuReadback(CgRHI rhi, String label, long capacity) {
        this.rhi = rhi;
        this.memory = rhi.createBuffer(new CgGpuBuffer.Desc(label, capacity, CgGpuBuffer.Usage.ALL, true, true));
    }

    /** Copies {@code size} bytes of {@code source} from {@code offset} back. */
    public void enqueue(CgCommandList cmd, CgGpuBuffer source, long offset, long size) {
        if (size > memory.size()) throw new IllegalArgumentException(size + " bytes into a readback of " + memory.size());
        cmd.copyBuffer(source, offset, memory, 0, size);
        issued(size);
    }

    /** Copies {@code region} of {@code source} back, its texels tightly packed. */
    public void enqueue(CgCommandList cmd, CgGpuTexture source, CgTextureRegion region) {
        long bytes = source.desc().format().bytes(region.width(), region.height(), region.depth());
        if (bytes > memory.size()) throw new IllegalArgumentException(bytes + " bytes into a readback of " + memory.size());
        cmd.copyTextureToBuffer(source, region, memory, 0);
        issued(bytes);
    }

    private void issued(long bytes) {
        size = bytes;
        frame = rhi.frameIndex();
    }

    /** Whether the last copy has landed: its frame has retired. False before any. */
    public boolean ready() {
        return frame >= 0 && frame <= rhi.retiredFrame();
    }

    /** The last copy's bytes, from 0; valid once {@link #ready()}. */
    public ByteBuffer data() {
        if (!ready()) throw new IllegalStateException(memory.label() + ": the copy has not landed");
        return memory.mapped();
    }

    /** Bytes the last copy brought back. */
    public long size() { return size; }

    public void release() {
        rhi.release(memory);
    }
}
