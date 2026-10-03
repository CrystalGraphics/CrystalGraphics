package com.crystalgraphics.compute.source;

/**
 * A storage image a {@code .compute} declares in {@code Images { }}, bound at image unit {@code index}, its place in
 * the block. The texture bound must be of its format.
 *
 * <pre>{@code
 * Images {
 *     SOURCE  ("Source",  rgba8, readonly)        // SOURCE_LOAD(p), SOURCE_SIZE()
 *     DENSITY ("Density", r32ui, readwrite, 3d)   // DENSITY_WRITE(v), and in a general kernel DENSITY_ADD(p, n)
 * }
 * }</pre>
 */
public record CgImageDecl(String name, String display, CgImageFormat format, CgImageAccess access,
                          CgImageDimension dimension, int index) {

    /** {@code _cg_img_DENSITY}: the image uniform the generated accessors read. */
    public String uniform() { return "_cg_img_" + name; }

    /** {@code uimage3D}. */
    public String glslType() { return format.kind.prefix + dimension.glslType; }
}
