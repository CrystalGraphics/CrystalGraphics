package com.crystalgraphics.render.view;

import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.api.texture.CgTextures;
import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.gpu.resource.CgTexture2D;
import com.crystalgraphics.platform.rhi.CgExternalTarget;
import com.crystalgraphics.render.CgRenderer;
import com.crystalgraphics.platform.rhi.format.CgFormat;

import javax.annotation.Nullable;
import java.util.ArrayDeque;

/**
 * A view's picture of its own, Unreal's scene capture render target: the colour and depth it is drawn into at a size,
 * kept from frame to frame and remade only when the size changes. {@link CgView#renderPicture} draws into it.
 */
final class CgViewPicture {

    /**
     * Ring frames a replaced picture outlives its replacement: a frame recorded against it elsewhere (a UI built on its
     * own thread) may still execute after the frame that replaced it.
     */
    private static final int RETIRE_FRAMES = 4;

    private record Target(CgTexture2D colour, CgTexture2D depth, CgExternalTarget target) {
        void release() {
            colour.release();
            depth.release();
        }
    }

    private record Retired(Target target, long frame) {
    }

    private final String name;
    @Nullable
    private Target current;
    private final ArrayDeque<Retired> retired = new ArrayDeque<>();

    CgViewPicture(String name) {
        this.name = name;
    }

    /** Its target at {@code width} x {@code height}, cleared to {@code argb} and the far depth. */
    CgExternalTarget begin(int width, int height, int argb) {
        long now = CgFrameRing.frame();
        while (!retired.isEmpty() && now - retired.peekFirst().frame() > RETIRE_FRAMES) retired.pollFirst().target().release();
        if (current == null || current.target().width() != width || current.target().height() != height) {
            if (current != null) retired.addLast(new Retired(current, now));
            CgTexture2D colour = CgTextures.of(name, CgTextureDesc.of(width, height, CgFormat.RGBA8_UNORM));
            CgTexture2D depth = CgTextures.of(name + ".depth", CgTextureDesc.of(width, height, CgFormat.DEPTH32_FLOAT_STENCIL8));
            current = new Target(colour, depth, CgTextures.target(name, colour, depth));
        }
        CgRenderer.clear(current.target(), true, (argb >> 16 & 0xFF) / 255f, (argb >> 8 & 0xFF) / 255f, (argb & 0xFF) / 255f,
                (argb >>> 24) / 255f, true, 0.0, true, 0);
        return current.target();
    }

    CgTexture2D colour() {
        return current.colour();
    }

    void release() {
        if (current != null) current.release();
        current = null;
        while (!retired.isEmpty()) retired.pollFirst().target().release();
    }
}
