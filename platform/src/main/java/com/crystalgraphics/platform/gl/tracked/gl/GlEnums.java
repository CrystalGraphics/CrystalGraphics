package com.crystalgraphics.platform.gl.tracked.gl;

import com.crystalgraphics.platform.rhi.command.CgAccess;
import com.crystalgraphics.platform.rhi.format.CgCompare;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc;
import com.crystalgraphics.platform.gl.CgGL;

/** GL's enums to the device's. An enum GL would reject throws, naming it. */
public final class GlEnums {

    private GlEnums() {}

    static CgCompare compare(int func) {
        switch (func) {
            case CgGL.GL_NEVER:    return CgCompare.NEVER;
            case CgGL.GL_LESS:     return CgCompare.LESS;
            case CgGL.GL_EQUAL:    return CgCompare.EQUAL;
            case CgGL.GL_LEQUAL:   return CgCompare.LESS_EQUAL;
            case CgGL.GL_GREATER:  return CgCompare.GREATER;
            case CgGL.GL_NOTEQUAL: return CgCompare.NOT_EQUAL;
            case CgGL.GL_GEQUAL:   return CgCompare.GREATER_EQUAL;
            case CgGL.GL_ALWAYS:   return CgCompare.ALWAYS;
            default: throw bad("compare function", func);
        }
    }

    static CgGraphicsPipelineDesc.BlendFactor blendFactor(int f) {
        switch (f) {
            case CgGL.GL_ZERO:                return CgGraphicsPipelineDesc.BlendFactor.ZERO;
            case CgGL.GL_ONE:                 return CgGraphicsPipelineDesc.BlendFactor.ONE;
            case CgGL.GL_SRC_COLOR:           return CgGraphicsPipelineDesc.BlendFactor.SRC_COLOR;
            case CgGL.GL_ONE_MINUS_SRC_COLOR: return CgGraphicsPipelineDesc.BlendFactor.ONE_MINUS_SRC_COLOR;
            case CgGL.GL_DST_COLOR:           return CgGraphicsPipelineDesc.BlendFactor.DST_COLOR;
            case CgGL.GL_ONE_MINUS_DST_COLOR: return CgGraphicsPipelineDesc.BlendFactor.ONE_MINUS_DST_COLOR;
            case CgGL.GL_SRC_ALPHA:           return CgGraphicsPipelineDesc.BlendFactor.SRC_ALPHA;
            case CgGL.GL_ONE_MINUS_SRC_ALPHA: return CgGraphicsPipelineDesc.BlendFactor.ONE_MINUS_SRC_ALPHA;
            case CgGL.GL_DST_ALPHA:           return CgGraphicsPipelineDesc.BlendFactor.DST_ALPHA;
            case CgGL.GL_ONE_MINUS_DST_ALPHA: return CgGraphicsPipelineDesc.BlendFactor.ONE_MINUS_DST_ALPHA;
            case 0x8001:                      return CgGraphicsPipelineDesc.BlendFactor.CONSTANT_COLOR;
            case 0x8002:                      return CgGraphicsPipelineDesc.BlendFactor.ONE_MINUS_CONSTANT_COLOR;
            case 0x8003:                      return CgGraphicsPipelineDesc.BlendFactor.CONSTANT_ALPHA;
            case 0x8004:                      return CgGraphicsPipelineDesc.BlendFactor.ONE_MINUS_CONSTANT_ALPHA;
            case CgGL.GL_SRC_ALPHA_SATURATE:  return CgGraphicsPipelineDesc.BlendFactor.SRC_ALPHA_SATURATE;
            default: throw bad("blend factor", f);
        }
    }

    static CgGraphicsPipelineDesc.BlendOp blendOp(int mode) {
        switch (mode) {
            case CgGL.GL_FUNC_ADD:              return CgGraphicsPipelineDesc.BlendOp.ADD;
            case CgGL.GL_FUNC_SUBTRACT:         return CgGraphicsPipelineDesc.BlendOp.SUBTRACT;
            case CgGL.GL_FUNC_REVERSE_SUBTRACT: return CgGraphicsPipelineDesc.BlendOp.REVERSE_SUBTRACT;
            case CgGL.GL_MIN:                   return CgGraphicsPipelineDesc.BlendOp.MIN;
            case CgGL.GL_MAX:                   return CgGraphicsPipelineDesc.BlendOp.MAX;
            default: throw bad("blend equation", mode);
        }
    }

    static CgGraphicsPipelineDesc.StencilOp stencilOp(int op) {
        switch (op) {
            case CgGL.GL_KEEP:      return CgGraphicsPipelineDesc.StencilOp.KEEP;
            case CgGL.GL_ZERO:      return CgGraphicsPipelineDesc.StencilOp.ZERO;
            case CgGL.GL_REPLACE:   return CgGraphicsPipelineDesc.StencilOp.REPLACE;
            case CgGL.GL_INCR:      return CgGraphicsPipelineDesc.StencilOp.INCREMENT_CLAMP;
            case CgGL.GL_DECR:      return CgGraphicsPipelineDesc.StencilOp.DECREMENT_CLAMP;
            case CgGL.GL_INVERT:    return CgGraphicsPipelineDesc.StencilOp.INVERT;
            case CgGL.GL_INCR_WRAP: return CgGraphicsPipelineDesc.StencilOp.INCREMENT_WRAP;
            case CgGL.GL_DECR_WRAP: return CgGraphicsPipelineDesc.StencilOp.DECREMENT_WRAP;
            default: throw bad("stencil op", op);
        }
    }

    static CgGraphicsPipelineDesc.CullMode cull(boolean enabled, int mode) {
        if (!enabled) return CgGraphicsPipelineDesc.CullMode.NONE;
        switch (mode) {
            case CgGL.GL_FRONT:          return CgGraphicsPipelineDesc.CullMode.FRONT;
            case CgGL.GL_BACK:           return CgGraphicsPipelineDesc.CullMode.BACK;
            case CgGL.GL_FRONT_AND_BACK: return CgGraphicsPipelineDesc.CullMode.FRONT_AND_BACK;
            default: throw bad("cull face", mode);
        }
    }

    static CgGraphicsPipelineDesc.PolygonMode polygonMode(int mode) {
        switch (mode) {
            case CgGL.GL_FILL:  return CgGraphicsPipelineDesc.PolygonMode.FILL;
            case CgGL.GL_LINE:  return CgGraphicsPipelineDesc.PolygonMode.LINE;
            case CgGL.GL_POINT: return CgGraphicsPipelineDesc.PolygonMode.POINT;
            default: throw bad("polygon mode", mode);
        }
    }

    public static CgGraphicsPipelineDesc.Topology topology(int mode) {
        switch (mode) {
            case CgGL.GL_POINTS:         return CgGraphicsPipelineDesc.Topology.POINTS;
            case CgGL.GL_LINES:          return CgGraphicsPipelineDesc.Topology.LINES;
            case CgGL.GL_LINE_STRIP:     return CgGraphicsPipelineDesc.Topology.LINE_STRIP;
            case CgGL.GL_TRIANGLES:      return CgGraphicsPipelineDesc.Topology.TRIANGLES;
            case CgGL.GL_TRIANGLE_STRIP: return CgGraphicsPipelineDesc.Topology.TRIANGLE_STRIP;
            case CgGL.GL_TRIANGLE_FAN:   return CgGraphicsPipelineDesc.Topology.TRIANGLE_FAN;
            default: throw bad("draw mode (a core profile has no quads or line loops here)", mode);
        }
    }

    /** {@code glMemoryBarrier}'s bits: the {@link CgAccess} uses that then see a kernel's writes. */
    public static int accesses(int barriers) {
        int to = 0;
        if ((barriers & CgGL.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT) != 0) to |= CgAccess.VERTEX_INPUT;
        if ((barriers & CgGL.GL_ELEMENT_ARRAY_BARRIER_BIT) != 0) to |= CgAccess.INDEX_INPUT;
        if ((barriers & CgGL.GL_UNIFORM_BARRIER_BIT) != 0) to |= CgAccess.UNIFORM_READ;
        if ((barriers & CgGL.GL_TEXTURE_FETCH_BARRIER_BIT) != 0) to |= CgAccess.SAMPLED_READ;
        if ((barriers & (CgGL.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | CgGL.GL_SHADER_STORAGE_BARRIER_BIT
                | CgGL.GL_ATOMIC_COUNTER_BARRIER_BIT)) != 0) to |= CgAccess.STORAGE;
        if ((barriers & CgGL.GL_COMMAND_BARRIER_BIT) != 0) to |= CgAccess.INDIRECT;
        if ((barriers & (CgGL.GL_PIXEL_BUFFER_BARRIER_BIT | CgGL.GL_TEXTURE_UPDATE_BARRIER_BIT
                | CgGL.GL_BUFFER_UPDATE_BARRIER_BIT)) != 0) to |= CgAccess.COPY_READ | CgAccess.COPY_WRITE;
        if ((barriers & CgGL.GL_FRAMEBUFFER_BARRIER_BIT) != 0) to |= CgAccess.COLOR_WRITE;
        if ((barriers & CgGL.GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT) != 0) to |= CgAccess.HOST_READ;
        return to;
    }

    static IllegalArgumentException bad(String what, int value) {
        return new IllegalArgumentException("Not a " + what + ": 0x" + Integer.toHexString(value));
    }
}
