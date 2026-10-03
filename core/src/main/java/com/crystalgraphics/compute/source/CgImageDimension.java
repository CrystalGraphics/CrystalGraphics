package com.crystalgraphics.compute.source;

/** A storage image's shape: what GLSL type declares it, and how many coordinates address a texel. */
public enum CgImageDimension {
    D2("2d", "image2D", 2), D3("3d", "image3D", 3), D2_ARRAY("2darray", "image2DArray", 3), CUBE("cube", "imageCube", 3);

    /** The token an {@code Images} entry names it by. */
    public final String token;
    /** The GLSL type without a kind prefix. */
    public final String glslType;
    public final int coordinates;

    CgImageDimension(String token, String glslType, int coordinates) {
        this.token = token;
        this.glslType = glslType;
        this.coordinates = coordinates;
    }

    /** {@code ivec2} or {@code ivec3}. */
    public String coordinateType() { return "ivec" + coordinates; }

    /** The dimension a token names, or null. */
    public static CgImageDimension of(String token) {
        for (CgImageDimension d : values()) if (d.token.equalsIgnoreCase(token)) return d;
        return null;
    }
}
