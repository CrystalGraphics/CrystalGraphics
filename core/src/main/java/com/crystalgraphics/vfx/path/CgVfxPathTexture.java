package com.crystalgraphics.vfx.path;

import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.gpu.CgUploadLease;
import com.crystalgraphics.gpu.CgUploads;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.util.CgBufferUtils;

import java.nio.ByteBuffer;

/**
 * This frame's {@link CgVfxPath}s, one per row of an RGBA32F texture that tube shaders read with {@code texelFetch}
 * ({@code fx_tube.glsl}). Texel 0 of a row is its header (ring count, length, seed, age); ring {@code i} is texels
 * {@code 1 + 3i} to {@code 3 + 3i}: position and radius, tangent and arc length, normal and intensity.
 *
 * <pre>{@code
 * paths.begin();
 * int row = paths.add(path, seed, age);   // as many as the frame draws
 * paths.upload();                         // before the world stages record
 * material.applyProperties(b -> b.sampler("_FxPath", 0, paths.texture()));
 * }</pre>
 *
 * <ul>
 *   <li>Render thread. Upload every frame that draws a tube: the texture holds the last upload.</li>
 *   <li>A texture rather than a storage buffer so every tube shader compiles on its own, as the shader audit
 *       compiles it.</li>
 * </ul>
 */
public final class CgVfxPathTexture {

    /** Texels per row: the header and three per ring. */
    public static final int WIDTH = 1 + 3 * CgVfxPath.MAX_RINGS;
    private static final CgTextureSpec SPEC = CgTextureSpec.builder()
            .type(CgTextureType.RGBA32F)
            .minFilter(CgGL.GL_NEAREST).magFilter(CgGL.GL_NEAREST)
            .build();
    private static final int ROW_BYTES = WIDTH * 4 * Float.BYTES;

    private CgTexture2D texture;
    private ByteBuffer pixels;
    private int capacity, textureRows;
    private int rows;

    public void begin() {
        rows = 0;
    }

    /** Adds {@code path} as the next row and answers its index. */
    public int add(CgVfxPath path, float seed, float age) {
        ensureCapacity(rows + 1);
        int base = rows * ROW_BYTES;
        pixels.putFloat(base, path.count());
        pixels.putFloat(base + 4, path.length());
        pixels.putFloat(base + 8, seed);
        pixels.putFloat(base + 12, age);
        float[] data = path.data();
        int floats = path.count() * CgVfxPath.RING_FLOATS;
        int at = base + 16;
        for (int i = 0; i < floats; i++, at += 4) pixels.putFloat(at, data[i]);
        return rows++;
    }

    public void upload() {
        if (rows == 0) return;
        if (texture == null || textureRows < capacity) {
            // A new texture when it must grow: re-specifying one frames in flight still sample blocks the render thread.
            if (texture != null) texture.delete();
            texture = CgTexture2D.createEmpty(WIDTH, capacity, SPEC);
            textureRows = capacity;
        }
        // This frame's rows only, from an upload lease: copied on the GPU's timeline, behind the draws that read them.
        pixels.position(0).limit(rows * ROW_BYTES);
        CgUploadLease lease = CgUploads.lease(rows * ROW_BYTES).put(pixels);
        pixels.clear();
        texture.uploadRegion(0, 0, 0, WIDTH, rows, lease, CgGL.GL_RGBA, CgGL.GL_FLOAT);
    }

    /** The texture tube materials sample; null before the first upload. */
    public CgTexture2D texture() {
        return texture;
    }

    public void delete() {
        if (texture != null) texture.delete();
        texture = null;
        textureRows = 0;
    }

    private void ensureCapacity(int needed) {
        if (needed <= capacity) return;
        int grown = Math.max(4, Integer.highestOneBit(needed - 1) << 1);
        ByteBuffer next = CgBufferUtils.createByteBuffer(grown * ROW_BYTES);
        if (pixels != null) {
            pixels.position(0).limit(capacity * ROW_BYTES);
            next.put(pixels);
            next.clear();
        }
        pixels = next;
        capacity = grown;
    }
}
