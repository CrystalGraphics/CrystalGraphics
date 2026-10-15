package com.crystalgraphics.vfx.trail;

import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.gpu.CgUploadLease;
import com.crystalgraphics.gpu.CgUploads;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.util.CgBufferUtils;

import java.nio.ByteBuffer;

/**
 * This frame's {@link CgVfxTrail} points, every trail's run one after another in an RGBA32F texture
 * {@link #WIDTH} texels a row, which trail shaders read with {@code texelFetch} ({@code fx_trail.glsl}). A point is
 * three texels: (position, age), (a blade's tip or the facing, distance along), (width, intensity, the effect's two).
 *
 * <pre>{@code
 * trails.begin();
 * int first = trails.add(trail, frame.alpha(), anchorX, anchorY, anchorZ, bounds);   // as many as the frame draws
 * trails.upload();                                                                  // before the world stages record
 * material.applyProperties(b -> b.sampler("_FxTrail", 0, trails.texture()));
 * }</pre>
 *
 * <ul>
 *   <li>Render thread. Upload every frame that draws a trail: the texture holds the last upload.</li>
 *   <li>A texture rather than a storage buffer so every trail shader runs at every tier.</li>
 * </ul>
 */
public final class CgVfxTrailTexture {

    /** Texels a row; {@code FX_TRAIL_ROW} in {@code fx_trail.glsl}. */
    public static final int WIDTH = 1024;
    /** Texels a point. */
    public static final int POINT_TEXELS = 3;
    private static final int TEXEL_BYTES = 4 * Float.BYTES, ROW_BYTES = WIDTH * TEXEL_BYTES;
    private static final CgTextureSpec SPEC = CgTextureSpec.builder()
            .type(CgTextureType.RGBA32F)
            .minFilter(CgGL.GL_NEAREST).magFilter(CgGL.GL_NEAREST)
            .build();

    /** Where a trail writes its points, in order. */
    interface Sink {
        void point(float x, float y, float z, float age, float bx, float by, float bz, float along,
                   float width, float intensity, float user0, float user1);
    }

    private final Sink sink = this::put;
    private CgTexture2D texture;
    private ByteBuffer pixels;
    private int capacityTexels, textureRows;
    private int texels;

    public void begin() {
        texels = 0;
    }

    /**
     * Adds {@code trail} drawn at {@code alpha} between its last two ticks, its points from the anchor, and answers its
     * first texel; {@code bounds} (8 floats) gets the box round its points from the anchor, then its head's and its
     * oldest point's distance along. Answers -1 when it has nothing to draw.
     */
    public int add(CgVfxTrail trail, float alpha, double anchorX, double anchorY, double anchorZ, float[] bounds) {
        if (!trail.drawable()) return -1;
        ensureCapacity(texels + (trail.count() + 1) * POINT_TEXELS);
        int start = texels;
        int written = trail.write(sink, alpha, anchorX, anchorY, anchorZ, bounds);
        if (written < 2) {
            texels = start;
            return -1;
        }
        return start;
    }

    /** How many points the trail added at {@code first} wrote: up to where the next begins. */
    public int points(int first) {
        return (texels - first) / POINT_TEXELS;
    }

    private void put(float x, float y, float z, float age, float bx, float by, float bz, float along,
                     float width, float intensity, float user0, float user1) {
        int at = texels * TEXEL_BYTES;
        pixels.putFloat(at, x).putFloat(at + 4, y).putFloat(at + 8, z).putFloat(at + 12, age);
        pixels.putFloat(at + 16, bx).putFloat(at + 20, by).putFloat(at + 24, bz).putFloat(at + 28, along);
        pixels.putFloat(at + 32, width).putFloat(at + 36, intensity).putFloat(at + 40, user0).putFloat(at + 44, user1);
        texels += POINT_TEXELS;
    }

    public void upload() {
        if (texels == 0) return;
        int rows = (texels + WIDTH - 1) / WIDTH;
        if (texture == null || textureRows < rows) {
            // A new texture when it must grow: re-specifying one frames in flight still sample blocks the render thread.
            if (texture != null) texture.delete();
            textureRows = Math.max(rows, capacityTexels / WIDTH);
            texture = CgTexture2D.createEmpty(WIDTH, textureRows, SPEC);
        }
        // Whole rows, from an upload lease: copied on the GPU's timeline, behind the draws that read them.
        int bytes = rows * ROW_BYTES;
        pixels.position(0).limit(bytes);
        CgUploadLease lease = CgUploads.lease(bytes).put(pixels);
        pixels.clear();
        texture.uploadRegion(0, 0, 0, WIDTH, rows, lease, CgGL.GL_RGBA, CgGL.GL_FLOAT);
    }

    /** The texture trail materials sample; null before the first upload. */
    public CgTexture2D texture() {
        return texture;
    }

    public void delete() {
        if (texture != null) texture.delete();
        texture = null;
        textureRows = 0;
    }

    private void ensureCapacity(int needed) {
        if (needed <= capacityTexels) return;
        // Whole rows, so an upload of the last row never reads past the buffer.
        int grown = Math.max(WIDTH, Integer.highestOneBit(needed - 1) << 1);
        grown = (grown + WIDTH - 1) / WIDTH * WIDTH;
        ByteBuffer next = CgBufferUtils.createByteBuffer(grown * TEXEL_BYTES);
        if (pixels != null) {
            pixels.position(0).limit(texels * TEXEL_BYTES);
            next.put(pixels);
            next.clear();
        }
        pixels = next;
        capacityTexels = grown;
    }
}
