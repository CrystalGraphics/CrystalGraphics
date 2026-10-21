package com.crystalgraphics.platform.gl.tracked.tracker;

import com.crystalgraphics.platform.device.CgDevice;
import com.crystalgraphics.platform.device.resource.CgGpuTexture;
import com.crystalgraphics.platform.device.resource.CgTextureView;

import java.util.List;
import java.util.Objects;

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
        for (int i = 0; i < colors.size(); i++) w = Math.min(w, colors.get(i).width());
        return depth == null ? w : Math.min(w, depth.width());
    }

    public int height() {
        int h = Integer.MAX_VALUE;
        for (int i = 0; i < colors.size(); i++) h = Math.min(h, colors.get(i).height());
        return depth == null ? h : Math.min(h, depth.height());
    }

    /** By value, index by index: a record's would walk the list with an iterator, and a draw compares its target. */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CgTarget t) || !Objects.equals(depth, t.depth) || colors.size() != t.colors.size()) return false;
        for (int i = 0; i < colors.size(); i++) if (!colors.get(i).equals(t.colors.get(i))) return false;
        return true;
    }

    @Override
    public int hashCode() {
        int h = Objects.hashCode(depth);
        for (int i = 0; i < colors.size(); i++) h = 31 * h + colors.get(i).hashCode();
        return h;
    }

    /** Whether {@code view} reads what an attachment renders to: another level of the same texture does not. */
    public boolean attaches(CgTextureView view) {
        for (int i = 0; i < colors.size(); i++) {   // indexed: an iterator a draw
            if (colors.get(i).overlaps(view)) return true;
        }
        return depth != null && depth.overlaps(view);
    }
}
