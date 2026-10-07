package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.texture.CgTexture3D;
import com.crystalgraphics.platform.gl.CgGL;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A 3D grid of vectors a {@link CgVfxModule.VectorField} pushes particles by, stretched over its box: Godot's vector
 * field attractor, Unity VFX Graph's and Niagara's vector field. The CPU path samples it here and the GPU path samples
 * its texture, both trilinearly between texel centres by the same sums ({@code fx_vector_field.glsl} filters by hand), so
 * the two agree to float rounding.
 *
 * <pre>{@code
 * CgVfxField swirl = CgVfxField.of(16, 16, 16, false, (x, y, z, out) -> {
 *     out[0] = (float) Math.sin(y * 0.4); out[1] = 0.3f; out[2] = (float) Math.cos(x * 0.4);
 * });
 * CgVfxModule field = new CgVfxModule.VectorField(swirl, CgVfxModule.Volume.box(4f, 4f, 4f), 3f, 1f);
 * }</pre>
 *
 * <ul>
 *   <li>Its texture is made the first time a pool asks; a server never makes one.</li>
 *   <li>A field that does not tile ({@code tiles} false) pushes nothing outside its box, as Godot's; one that tiles
 *       repeats past it.</li>
 * </ul>
 */
public final class CgVfxField {

    /** Fills one texel: {@code (x, y, z)} its index, {@code out} its vector. */
    public interface Filler {
        void vector(int x, int y, int z, float[] out);
    }

    private final int width, height, depth;
    private final boolean tiles;
    /** xyz a texel, x fastest. */
    private final float[] vectors;
    private final float largest;
    private CgTexture[] texture;

    private CgVfxField(int width, int height, int depth, boolean tiles, float[] vectors) {
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.tiles = tiles;
        this.vectors = vectors;
        float most = 0f;
        for (int i = 0; i < vectors.length; i += 3) {
            most = Math.max(most, (float) Math.sqrt(vectors[i] * vectors[i] + vectors[i + 1] * vectors[i + 1] + vectors[i + 2] * vectors[i + 2]));
        }
        largest = most;
    }

    /** A field of {@code width x height x depth} texels, each filled by {@code filler}. */
    public static CgVfxField of(int width, int height, int depth, boolean tiles, Filler filler) {
        if (width < 1 || height < 1 || depth < 1) throw new IllegalArgumentException(width + "x" + height + "x" + depth + " texels");
        float[] vectors = new float[width * height * depth * 3], one = new float[3];
        for (int z = 0, at = 0; z < depth; z++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++, at += 3) {
                    one[0] = one[1] = one[2] = 0f;
                    filler.vector(x, y, z, one);
                    vectors[at] = one[0];
                    vectors[at + 1] = one[1];
                    vectors[at + 2] = one[2];
                }
            }
        }
        return new CgVfxField(width, height, depth, tiles, vectors);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int depth() {
        return depth;
    }

    public boolean tiles() {
        return tiles;
    }

    /** The longest vector it holds: what bounds how hard it pushes. */
    public float largest() {
        return largest;
    }

    /**
     * The field at {@code (u, v, w)}, 0 to 1 across its box, into {@code out}: trilinear between texel centres, wrapped
     * or held at the edge.
     */
    public void sample(float u, float v, float w, float[] out) {
        float fx = u * width - 0.5f, fy = v * height - 0.5f, fz = w * depth - 0.5f;
        float x0f = (float) Math.floor(fx), y0f = (float) Math.floor(fy), z0f = (float) Math.floor(fz);
        float tx = fx - x0f, ty = fy - y0f, tz = fz - z0f;
        int x0 = (int) x0f, y0 = (int) y0f, z0 = (int) z0f;
        int ax = index(x0, width), bx = index(x0 + 1, width), ay = index(y0, height), by = index(y0 + 1, height);
        int az = index(z0, depth), bz = index(z0 + 1, depth);
        for (int c = 0; c < 3; c++) {
            float c00 = lerp(at(ax, ay, az, c), at(bx, ay, az, c), tx), c10 = lerp(at(ax, by, az, c), at(bx, by, az, c), tx);
            float c01 = lerp(at(ax, ay, bz, c), at(bx, ay, bz, c), tx), c11 = lerp(at(ax, by, bz, c), at(bx, by, bz, c), tx);
            out[c] = lerp(lerp(c00, c10, ty), lerp(c01, c11, ty), tz);
        }
    }

    private int index(int i, int size) {
        return tiles ? Math.floorMod(i, size) : Math.max(0, Math.min(i, size - 1));
    }

    private float at(int x, int y, int z, int c) {
        return vectors[((z * height + y) * width + x) * 3 + c];
    }

    /** {@code fx_vector_field.glsl}'s mix, term for term. */
    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Its texture, an RGBA32F volume, made at the first ask: the array {@link CgVfxModule.VectorField#textures()} answers. */
    CgTexture[] textures() {
        if (texture == null) {
            CgTexture3D made = CgTexture3D.createEmpty(width, height, depth, CgTextureType.RGBA32F.toTextureSpec());
            ByteBuffer texels = ByteBuffer.allocateDirect(width * height * depth * 16).order(ByteOrder.nativeOrder());
            for (int i = 0; i < vectors.length; i += 3) texels.putFloat(vectors[i]).putFloat(vectors[i + 1]).putFloat(vectors[i + 2]).putFloat(0f);
            made.uploadRegion(0, 0, 0, 0, width, height, depth, texels.flip(), CgGL.GL_RGBA, CgGL.GL_FLOAT);
            texture = new CgTexture[]{made};
        }
        return texture;
    }
}
