package com.crystalgraphics.platform.gl.tracked.tracker;

import com.crystalgraphics.platform.device.CgDevice;
import com.crystalgraphics.platform.device.resource.CgGpuTexture;
import com.crystalgraphics.platform.device.resource.CgTextureView;

import java.util.List;

/**
 * What a draw renders into: a framebuffer's attachments, colour attachment {@code i} receiving fragment output
 * location {@code i}. A value: two targets with the same views are the same target, so rebinding one does not
 * break the pass.
 *
 * @param depth the depth or depth-stencil attachment, {@code null} with none
 */
public record CgTarget(List<CgTextureView> colors, CgTextureView depth) {

    /** The default framebuffer. */
    public static CgTarget surface(CgDevice device) {
        CgGpuTexture depth = device.surfaceDepth();
        return new CgTarget(List.of(CgTextureView.whole(device.surfaceColor())),
                depth == null ? null : CgTextureView.whole(depth));
    }

    public int width() {
        int w = Integer.MAX_VALUE;
        for (CgTextureView v : colors) w = Math.min(w, v.width());
        return depth == null ? w : Math.min(w, depth.width());
    }

    public int height() {
        int h = Integer.MAX_VALUE;
        for (CgTextureView v : colors) h = Math.min(h, v.height());
        return depth == null ? h : Math.min(h, depth.height());
    }

    /** Whether {@code texture} is one of the attachments. */
    public boolean attaches(CgGpuTexture texture) {
        for (CgTextureView v : colors) {
            if (v.texture() == texture) return true;
        }
        return depth != null && depth.texture() == texture;
    }
}
