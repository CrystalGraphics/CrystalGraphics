package com.crystalgraphics.platform.device;

import java.util.List;

/**
 * Everything a draw fixes that a device compiles into a pipeline: the program, its vertex input, the render
 * state, and the formats it renders to. Viewport, scissor, depth-bias values and the stencil reference are
 * set on the pass instead ({@link CgRenderPass}). The tracker builds one per distinct key and caches the
 * {@link CgPipeline}; a device is never asked for the same one twice.
 *
 * <pre>{@code
 * CgPipelineDesc desc = new CgPipelineDesc("quad", layout, vertex, fragment,
 *         List.of(new CgPipelineDesc.VertexBuffer(0, 20, false, List.of(
 *                 new CgPipelineDesc.VertexAttrib(0, CgAttribFormat.FLOAT32X3, 0),
 *                 new CgPipelineDesc.VertexAttrib(1, CgAttribFormat.FLOAT32X2, 12)))),
 *         CgPipelineDesc.Topology.TRIANGLES, CgPipelineDesc.Raster.DEFAULT, CgPipelineDesc.DepthStencil.OFF,
 *         List.of(new CgPipelineDesc.ColorTarget(CgFormat.RGBA8_UNORM, CgPipelineDesc.Blend.ALPHA, 0xF)),
 *         null, 1);
 * }</pre>
 *
 * @param depthFormat the pass's depth attachment format, {@code null} with none
 * @param samples     the pass's sample count
 */
public record CgPipelineDesc(String label, CgBindingLayout layout, CgShaderModule vertex, CgShaderModule fragment,
                             List<VertexBuffer> vertexBuffers, Topology topology, Raster raster,
                             DepthStencil depthStencil, List<ColorTarget> colorTargets, CgFormat depthFormat,
                             int samples) {

    public enum Topology { POINTS, LINES, LINE_STRIP, TRIANGLES, TRIANGLE_STRIP, TRIANGLE_FAN }

    /** @param offset from the start of a vertex, in the buffer bound at the layout's binding */
    public record VertexAttrib(int location, CgAttribFormat format, int offset) {}

    /** @param perInstance advances once per instance (a GL divisor of 1) rather than per vertex */
    public record VertexBuffer(int binding, int stride, boolean perInstance, List<VertexAttrib> attribs) {}

    public enum CullMode { NONE, FRONT, BACK, FRONT_AND_BACK }

    public enum FrontFace { CCW, CW }

    public enum PolygonMode { FILL, LINE, POINT }

    /** @param depthBias the pass's {@link CgRenderPass#setDepthBias} values apply */
    public record Raster(CullMode cull, FrontFace frontFace, PolygonMode polygonMode, boolean depthBias) {
        public static final Raster DEFAULT = new Raster(CullMode.NONE, FrontFace.CCW, PolygonMode.FILL, false);
    }

    public enum StencilOp { KEEP, ZERO, REPLACE, INCREMENT_CLAMP, DECREMENT_CLAMP, INVERT, INCREMENT_WRAP, DECREMENT_WRAP }

    public record StencilFace(CgCompare compare, StencilOp fail, StencilOp depthFail, StencilOp pass) {
        public static final StencilFace KEEP = new StencilFace(CgCompare.ALWAYS, StencilOp.KEEP, StencilOp.KEEP, StencilOp.KEEP);
    }

    /** One set of stencil masks for both faces, which is all GL's non-separate calls express. */
    public record DepthStencil(boolean depthTest, boolean depthWrite, CgCompare depthCompare, boolean stencilTest,
                               StencilFace front, StencilFace back, int readMask, int writeMask) {
        public static final DepthStencil OFF = new DepthStencil(false, false, CgCompare.LESS, false,
                StencilFace.KEEP, StencilFace.KEEP, 0xFF, 0xFF);
    }

    public enum BlendFactor {
        ZERO, ONE, SRC_COLOR, ONE_MINUS_SRC_COLOR, DST_COLOR, ONE_MINUS_DST_COLOR, SRC_ALPHA, ONE_MINUS_SRC_ALPHA,
        DST_ALPHA, ONE_MINUS_DST_ALPHA, CONSTANT_COLOR, ONE_MINUS_CONSTANT_COLOR, CONSTANT_ALPHA,
        ONE_MINUS_CONSTANT_ALPHA, SRC_ALPHA_SATURATE
    }

    public enum BlendOp { ADD, SUBTRACT, REVERSE_SUBTRACT, MIN, MAX }

    public record Blend(BlendFactor srcColor, BlendFactor dstColor, BlendOp colorOp,
                        BlendFactor srcAlpha, BlendFactor dstAlpha, BlendOp alphaOp) {
        public static final Blend ALPHA = new Blend(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA,
                BlendOp.ADD, BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA, BlendOp.ADD);
    }

    /**
     * @param blend     {@code null} with blending off
     * @param writeMask bit 0 red, 1 green, 2 blue, 3 alpha
     */
    public record ColorTarget(CgFormat format, Blend blend, int writeMask) {}
}
