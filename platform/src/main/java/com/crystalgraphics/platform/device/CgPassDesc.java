package com.crystalgraphics.platform.device;

import java.util.List;

/**
 * The attachments of one render pass and what happens to them at its start and end. A clear the tracker saw
 * before the pass's first draw is a {@link LoadOp#CLEAR} here; a pass it had to end early and resume starts
 * again with {@link LoadOp#LOAD}.
 *
 * <pre>{@code
 * CgPassDesc desc = new CgPassDesc("ui",
 *         List.of(new CgPassDesc.Color(view, CgPassDesc.LoadOp.CLEAR, 0, 0, 0, 1, CgPassDesc.StoreOp.STORE)),
 *         null, view.width(), view.height());
 * }</pre>
 *
 * @param depth {@code null} with no depth or stencil attachment
 */
public record CgPassDesc(String label, List<Color> colors, Depth depth, int width, int height) {

    public enum LoadOp { LOAD, CLEAR, DONT_CARE }

    public enum StoreOp { STORE, DONT_CARE }

    public record Color(CgTextureView view, LoadOp load, float r, float g, float b, float a, StoreOp store) {}

    public record Depth(CgTextureView view, LoadOp depthLoad, float clearDepth, LoadOp stencilLoad,
                        int clearStencil, StoreOp store) {}

    /** The sample count of the attachments, which must agree. */
    public int samples() {
        CgTextureView any = colors.isEmpty() ? depth.view() : colors.get(0).view();
        return any.texture().desc().samples();
    }
}
