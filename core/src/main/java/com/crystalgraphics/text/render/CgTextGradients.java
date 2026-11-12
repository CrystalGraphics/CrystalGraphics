package com.crystalgraphics.text.render;

import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.api.texture.CgTextures;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgraphics.gpu.resource.CgTexture2D;
import com.crystalgraphics.text.style.CgTextPaint;
import com.crystalgraphics.platform.rhi.format.CgFormat;

/**
 * Every text gradient drawn, a row each of one ramp texture that every text material samples as {@code _Gradients}:
 * a glyph quad carries its row and how its corners map onto 0 to 1, so gradients of any kind share one draw.
 *
 * <ul>
 *   <li>A gradient gets its row the first time it draws, and keeps it for the session: rows are never freed.</li>
 *   <li>{@value #ROWS} distinct gradients at most; one more throws, naming the count.</li>
 * </ul>
 */
final class CgTextGradients {

    /** Texels along a row: a stop falls within half a texel of where it is written. */
    static final int WIDTH = 256;
    static final int ROWS = 256;

    private static final Map<CgTextPaint, Integer> ROW_OF = new HashMap<>();
    @Nullable
    private static CgTexture2D texture;
    private static final ByteBuffer ROW = ByteBuffer.allocateDirect(WIDTH * 4).order(ByteOrder.nativeOrder());

    private CgTextGradients() {
    }

    /** The ramp texture, made at the first ask. */
    static synchronized CgTexture2D texture() {
        if (texture == null) texture = CgTextures.of("crystalgraphics.text.gradients", CgTextureDesc.of(WIDTH, ROWS, CgFormat.RGBA8_UNORM));
        return texture;
    }

    /**
     * {@code gradient}'s row as a texture coordinate, its centre: written the first time it is asked for.
     *
     * @throws IllegalStateException past {@value #ROWS} gradients
     */
    static synchronized float row(CgTextPaint gradient) {
        Integer row = ROW_OF.get(gradient);
        if (row == null) {
            if (ROW_OF.size() == ROWS) throw new IllegalStateException("more than " + ROWS + " text gradients drawn");
            row = ROW_OF.size();
            ROW.clear();
            for (int x = 0; x < WIDTH; x++) {
                int argb = gradient.at(x / (float) (WIDTH - 1));
                ROW.put((byte) (argb >>> 16)).put((byte) (argb >>> 8)).put((byte) argb).put((byte) (argb >>> 24));
            }
            ROW.flip();
            texture().uploadRegion(0, 0, row, WIDTH, 1, ROW);
            ROW_OF.put(gradient, row);
        }
        return (row + 0.5f) / ROWS;
    }

    /** Forgets every row and the texture: the context is going. */
    static synchronized void releaseAll() {
        ROW_OF.clear();
        texture = null;
    }
}
