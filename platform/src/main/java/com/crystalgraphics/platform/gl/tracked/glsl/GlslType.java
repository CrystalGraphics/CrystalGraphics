package com.crystalgraphics.platform.gl.tracked.glsl;

import java.util.HashMap;
import java.util.Map;

/**
 * A GLSL basic or opaque type: its GL enum, its std140 alignment and size, and how many varying locations it takes.
 * Matrices are columns of four floats in std140, whatever their row count.
 */
final class GlslType {

    final String name;
    final int glType;
    final int columns, rows;
    final boolean integer, opaque, texel;

    private GlslType(String name, int glType, int columns, int rows, boolean integer, boolean opaque, boolean texel) {
        this.name = name;
        this.glType = glType;
        this.columns = columns;
        this.rows = rows;
        this.integer = integer;
        this.opaque = opaque;
        this.texel = texel;
    }

    int align() {
        if (columns > 1) return 16;
        return rows == 1 ? 4 : rows == 2 ? 8 : 16;
    }

    int size() {
        return columns > 1 ? columns * 16 : rows * 4;
    }

    /** Varying locations: one per column. */
    int locations() {
        return columns;
    }

    private static final Map<String, GlslType> TYPES = new HashMap<>();

    private static void put(String name, int gl, int columns, int rows, boolean integer) {
        TYPES.put(name, new GlslType(name, gl, columns, rows, integer, false, false));
    }

    private static void opaque(String name, int gl, boolean texel) {
        TYPES.put(name, new GlslType(name, gl, 1, 1, false, true, texel));
    }

    static {
        put("float", 0x1406, 1, 1, false); put("vec2", 0x8B50, 1, 2, false);
        put("vec3", 0x8B51, 1, 3, false);  put("vec4", 0x8B52, 1, 4, false);
        put("int", 0x1404, 1, 1, true);    put("ivec2", 0x8B53, 1, 2, true);
        put("ivec3", 0x8B54, 1, 3, true);  put("ivec4", 0x8B55, 1, 4, true);
        put("uint", 0x1405, 1, 1, true);   put("uvec2", 0x8DC6, 1, 2, true);
        put("uvec3", 0x8DC7, 1, 3, true);  put("uvec4", 0x8DC8, 1, 4, true);
        put("bool", 0x8B56, 1, 1, true);   put("bvec2", 0x8B57, 1, 2, true);
        put("bvec3", 0x8B58, 1, 3, true);  put("bvec4", 0x8B59, 1, 4, true);
        put("mat2", 0x8B5A, 2, 2, false);  put("mat3", 0x8B5B, 3, 3, false);
        put("mat4", 0x8B5C, 4, 4, false);
        opaque("sampler2D", 0x8B5E, false);        opaque("sampler3D", 0x8B5F, false);
        opaque("samplerCube", 0x8B60, false);      opaque("sampler2DShadow", 0x8B62, false);
        opaque("sampler2DArray", 0x8DC1, false);   opaque("sampler2DArrayShadow", 0x8DC4, false);
        opaque("samplerCubeShadow", 0x8DC5, false); opaque("sampler2DMS", 0x9108, false);
        opaque("isampler2D", 0x8DCA, false);       opaque("usampler2D", 0x8DD2, false);
        opaque("isampler3D", 0x8DCB, false);       opaque("usampler3D", 0x8DD3, false);
        opaque("isampler2DArray", 0x8DCF, false);  opaque("usampler2DArray", 0x8DD7, false);
        opaque("samplerBuffer", 0x8DC2, true);     opaque("isamplerBuffer", 0x8DD0, true);
        opaque("usamplerBuffer", 0x8DD8, true);
    }

    /** The type, or {@code null} for a struct or anything unknown. */
    static GlslType of(String name) {
        return TYPES.get(name);
    }

    static boolean isOpaque(String name) {
        GlslType t = TYPES.get(name);
        return t != null ? t.opaque : name.contains("sampler") || name.contains("image");
    }

    static int roundUp(int n, int to) {
        return (n + to - 1) / to * to;
    }
}
