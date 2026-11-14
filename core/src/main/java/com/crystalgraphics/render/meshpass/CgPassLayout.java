package com.crystalgraphics.render.meshpass;

import com.crystalgraphics.platform.rhi.format.CgFormat;

import javax.annotation.Nullable;
import java.util.List;

/**
 * What a mesh pass draws into: its colour attachments' formats, its depth's and its samples. Vulkan builds a pipeline
 * for one such layout, so a pass type declares it when registered and the pipelines its processor picks are built for it
 * ahead of the draw (Unreal's PSO precaching for a mesh pass's render targets).
 *
 * <pre>{@code
 * CgPassLayout thermal = CgPassLayout.color(CgFormat.R16_FLOAT).depth(CgFormat.DEPTH32_FLOAT);
 * CgPassLayout marks = CgPassLayout.depthOnly(CgFormat.DEPTH32_FLOAT_STENCIL8);
 * }</pre>
 *
 * @param colors  the colour attachments' formats, in slot order; empty for a depth-only pass
 * @param depth   its depth's format, or null for none
 * @param samples samples per pixel, 1 unless multisampled
 */
public record CgPassLayout(List<CgFormat> colors, @Nullable CgFormat depth, int samples) {

    public CgPassLayout {
        colors = List.copyOf(colors);
        if (samples < 1) throw new IllegalArgumentException("a pass has at least one sample, not " + samples);
        if (colors.isEmpty() && depth == null) throw new IllegalArgumentException("a pass draws into a colour or a depth");
    }

    /** Colour attachments of {@code formats}, no depth: add one with {@link #depth}. */
    public static CgPassLayout color(CgFormat... formats) {
        return new CgPassLayout(List.of(formats), null, 1);
    }

    /** A depth of {@code format} alone. */
    public static CgPassLayout depthOnly(CgFormat format) {
        return new CgPassLayout(List.of(), format, 1);
    }

    /** This layout with a depth of {@code format}. */
    public CgPassLayout depth(CgFormat format) {
        return new CgPassLayout(colors, format, samples);
    }

    /** This layout at {@code n} samples. */
    public CgPassLayout samples(int n) {
        return new CgPassLayout(colors, depth, n);
    }
}
