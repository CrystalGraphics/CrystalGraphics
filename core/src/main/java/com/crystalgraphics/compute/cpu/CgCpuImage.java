package com.crystalgraphics.compute.cpu;

import com.crystalgraphics.compute.source.CgImageDecl;
import com.crystalgraphics.compute.source.CgImageFormat;

/**
 * An image as the CPU tier sees it: one mip level, four components a texel, floats for a float or normalized format
 * and integers for an integer one, as {@code NAME_LOAD} answers them on the GPU.
 *
 * <pre>{@code
 * CgCpuImage source = d.image("SOURCE"), heat = d.image("HEAT");
 * for (int e = d.first(); e < d.end(); e++) {
 *     int x = d.x(e), y = d.y(e);
 *     float r = source.loadFloat(x, y, 0, 0);
 *     heat.store(x, y, 0, r, r, r, 1f);
 * }
 * }</pre>
 */
public final class CgCpuImage {

    private final CgImageDecl decl;
    private final int width, height, depth;
    final float[] floats;
    final int[] ints;

    CgCpuImage(CgImageDecl decl, int width, int height, int depth) {
        this.decl = decl;
        this.width = width;
        this.height = height;
        this.depth = depth;
        boolean real = decl.format().kind == CgImageFormat.Kind.FLOAT;
        int n = width * height * depth * 4;
        this.floats = real ? new float[n] : null;
        this.ints = real ? null : new int[n];
    }

    public CgImageDecl decl() { return decl; }

    public int width() { return width; }

    public int height() { return height; }

    public int depth() { return depth; }

    /** Component {@code c} of a float or normalized image's texel. */
    public float loadFloat(int x, int y, int z, int c) {
        return floats[at(x, y, z) + c];
    }

    /** Component {@code c} of an integer image's texel. */
    public int loadInt(int x, int y, int z, int c) {
        return ints[at(x, y, z) + c];
    }

    public void store(int x, int y, int z, float r, float g, float b, float a) {
        int i = at(x, y, z);
        floats[i] = r;
        floats[i + 1] = g;
        floats[i + 2] = b;
        floats[i + 3] = a;
    }

    public void storeInt(int x, int y, int z, int r, int g, int b, int a) {
        int i = at(x, y, z);
        ints[i] = r;
        ints[i + 1] = g;
        ints[i + 2] = b;
        ints[i + 3] = a;
    }

    private int at(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= depth) {
            throw new IndexOutOfBoundsException(decl.name() + " (" + x + ", " + y + ", " + z + ") of " + width + "x"
                    + height + "x" + depth);
        }
        return ((z * height + y) * width + x) * 4;
    }
}
