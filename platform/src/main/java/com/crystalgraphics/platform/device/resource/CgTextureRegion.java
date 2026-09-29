package com.crystalgraphics.platform.device.resource;

/**
 * A box in one mip of a texture, in texels. {@code y = 0} is the first row in memory — the row GL calls the
 * bottom — so a region means the same texels on every backend.
 *
 * @param z the first layer of an array or cube (a face is a layer), or the first slice of a 3D texture
 */
public record CgTextureRegion(int mip, int x, int y, int z, int width, int height, int depth) {

    public static CgTextureRegion of2D(int mip, int x, int y, int width, int height) {
        return new CgTextureRegion(mip, x, y, 0, width, height, 1);
    }

    /** Texels in the region. */
    public long texels() { return (long) width * height * depth; }
}
