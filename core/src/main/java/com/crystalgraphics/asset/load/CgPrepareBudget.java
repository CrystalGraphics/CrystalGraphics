package com.crystalgraphics.asset.load;

import com.crystalgraphics.render.CgWarmBudget;

/**
 * What one frame may spend preparing assets: upload bytes (a GPU-time cap per tier, as bytes) and the warm budget's
 * milliseconds ({@link CgWarmBudget}). The host sets the bytes for its tier.
 *
 * <pre>{@code
 * CgVfxAssets.instance().budget().uploadBytesPerFrame(4 << 20).chunkBytes(8 << 20);   // Vulkan
 * CgVfxAssets.instance().budget().uploadBytesPerFrame(Long.MAX_VALUE);               // GL with UNPACK leases
 * }</pre>
 */
public final class CgPrepareBudget {

    /** 4 MB a frame: about 1 ms of GPU on GL's DIRECT tier, one staging chunk's half on Vulkan. */
    public static final long DEFAULT_UPLOAD_BYTES = 4L << 20;
    /** Vulkan's staging chunk: no single upload passes it. */
    public static final long DEFAULT_CHUNK_BYTES = 8L << 20;

    private long perFrame = DEFAULT_UPLOAD_BYTES;
    private long chunk = DEFAULT_CHUNK_BYTES;
    long left = DEFAULT_UPLOAD_BYTES;
    long spentThisFrame;

    /** Upload bytes a frame may start. */
    public CgPrepareBudget uploadBytesPerFrame(long bytes) {
        perFrame = Math.max(1, bytes);
        return this;
    }

    /** The most one upload piece may be. */
    public CgPrepareBudget chunkBytes(long bytes) {
        chunk = Math.max(1, bytes);
        return this;
    }

    public long uploadBytesPerFrame() {
        return perFrame;
    }

    public long chunkBytes() {
        return chunk;
    }

    /** Bytes uploaded in the frame last prepared. */
    public long spentLastFrame() {
        return spentThisFrame;
    }

    void begin() {
        left = perFrame;
        spentThisFrame = 0;
    }

    /** The next piece's length, of {@code remaining}; 0 when the frame's bytes are spent. */
    long piece(long remaining) {
        if (left <= 0) return 0;
        return Math.min(remaining, Math.min(chunk, left));
    }

    void uploaded(long bytes) {
        left -= bytes;
        spentThisFrame += bytes;
    }

    boolean warmAllows() {
        return CgWarmBudget.allows();
    }

    void warmed(long nanos) {
        CgWarmBudget.spend(nanos);
    }
}
