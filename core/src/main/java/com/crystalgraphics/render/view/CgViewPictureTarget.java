package com.crystalgraphics.render.view;

import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.render.target.CgRenderTarget;

import javax.annotation.Nullable;
import java.util.ArrayDeque;

/**
 * A view's picture of its own: a render target at the size it is drawn, kept from frame to frame and replaced only when
 * the size changes. {@link CgView#renderPicture} draws into it.
 */
final class CgViewPictureTarget {

    /**
     * Ring frames a replaced target outlives its replacement: a frame recorded against it elsewhere (a UI built on its
     * own thread) may still execute after the frame that replaced it.
     */
    private static final int RETIRE_FRAMES = 4;

    private record Retired(CgRenderTarget target, long frame) {
    }

    private final String name;
    @Nullable
    private CgRenderTarget current;
    private final ArrayDeque<Retired> retired = new ArrayDeque<>();

    CgViewPictureTarget(String name) {
        this.name = name;
    }

    /** Its target at {@code width} x {@code height}, to be cleared to {@code argb}: the same one while the size holds. */
    CgRenderTarget at(int width, int height, int argb) {
        long now = CgFrameRing.frame();
        while (!retired.isEmpty() && now - retired.peekFirst().frame() > RETIRE_FRAMES) retired.pollFirst().target().release();
        if (current == null || current.width() != width || current.height() != height) {
            if (current != null) retired.addLast(new Retired(current, now));
            current = CgRenderTarget.create(name, width, height, CgRenderTarget.Format.RGBA8);
        }
        return current.clearColor(argb);
    }

    void release() {
        if (current != null) current.release();
        current = null;
        while (!retired.isEmpty()) retired.pollFirst().target().release();
    }
}
