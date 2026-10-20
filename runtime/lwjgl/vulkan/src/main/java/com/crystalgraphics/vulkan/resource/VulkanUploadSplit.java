package com.crystalgraphics.vulkan.resource;

import com.crystalgraphics.platform.device.format.CgFormat;
import com.crystalgraphics.platform.device.resource.CgTextureRegion;

/**
 * Cuts a texture upload into pieces no larger than a staging chunk: whole slices (depth or layers) while one fits, else
 * runs of block rows of one slice. Its source bytes are tightly packed, slice after slice, as the upload's are.
 *
 * <pre>{@code
 * VulkanUploadSplit split = new VulkanUploadSplit(VulkanStaging.CHUNK);
 * split.begin(format, region);
 * while (split.next()) upload(split.region(region.mip()), data, split.sourceOffset(), split.bytes());
 * }</pre>
 *
 * <p>Reused: nothing allocates but {@link #region}. A block row larger than the limit cannot be cut and throws.</p>
 */
public final class VulkanUploadSplit {

    private final long limit;
    private CgFormat format;
    private int x, y, z, width, height, depth;
    private long sliceBytes, blockRowBytes;
    private int blockRows, slice, row;
    private int pieceY, pieceZ, pieceHeight, pieceDepth;
    private long pieceOffset, pieceBytes;

    public VulkanUploadSplit(long limit) {
        this.limit = limit;
    }

    /** Whether an upload of {@code bytes} must be cut at all. */
    public boolean needed(long bytes) {
        return bytes > limit;
    }

    /** Starts cutting {@code region} of a texture in {@code format}. */
    public void begin(CgFormat format, CgTextureRegion region) {
        this.format = format;
        x = region.x();
        y = region.y();
        z = region.z();
        width = region.width();
        height = region.height();
        depth = region.depth();
        sliceBytes = format.bytes(width, height, 1);
        blockRowBytes = format.bytes(width, format.block(), 1);
        blockRows = (height + format.block() - 1) / format.block();
        if (sliceBytes > limit && blockRowBytes > limit) {
            throw new IllegalArgumentException("a row of blocks " + width + " wide (" + blockRowBytes
                    + " B) passes a staging chunk of " + limit + " B");
        }
        slice = 0;
        row = 0;
    }

    /** Moves to the next piece; false once the region is covered. */
    public boolean next() {
        if (slice >= depth) return false;
        int block = format.block();
        if (sliceBytes <= limit) {
            int slices = (int) Math.min(depth - slice, Math.max(1L, limit / sliceBytes));
            pieceZ = z + slice;
            pieceDepth = slices;
            pieceY = y;
            pieceHeight = height;
            pieceOffset = slice * sliceBytes;
            pieceBytes = slices * sliceBytes;
            slice += slices;
            return true;
        }
        int rows = (int) Math.min(blockRows - row, limit / blockRowBytes);
        pieceZ = z + slice;
        pieceDepth = 1;
        pieceY = y + row * block;
        pieceHeight = Math.min(rows * block, height - row * block);
        pieceOffset = slice * sliceBytes + row * blockRowBytes;
        pieceBytes = format.bytes(width, pieceHeight, 1);
        row += rows;
        if (row >= blockRows) {
            row = 0;
            slice++;
        }
        return true;
    }

    /** This piece as a region of mip {@code mip}. */
    public CgTextureRegion region(int mip) {
        return new CgTextureRegion(mip, x, pieceY, pieceZ, width, pieceHeight, pieceDepth);
    }

    /** Where this piece's bytes start in the upload's. */
    public long sourceOffset() {
        return pieceOffset;
    }

    /** This piece's bytes. */
    public long bytes() {
        return pieceBytes;
    }
}
