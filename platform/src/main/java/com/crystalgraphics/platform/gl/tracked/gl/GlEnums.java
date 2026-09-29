package com.crystalgraphics.platform.gl.tracked.gl;

import com.crystalgraphics.platform.device.format.CgCompare;
import com.crystalgraphics.platform.device.pipeline.CgPipelineDesc;
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

    static CgPipelineDesc.BlendFactor blendFactor(int f) {
        switch (f) {
            case CgGL.GL_ZERO:                return CgPipelineDesc.BlendFactor.ZERO;
            case CgGL.GL_ONE:                 return CgPipelineDesc.BlendFactor.ONE;
            case CgGL.GL_SRC_COLOR:           return CgPipelineDesc.BlendFactor.SRC_COLOR;
            case CgGL.GL_ONE_MINUS_SRC_COLOR: return CgPipelineDesc.BlendFactor.ONE_MINUS_SRC_COLOR;
            case CgGL.GL_DST_COLOR:           return CgPipelineDesc.BlendFactor.DST_COLOR;
            case CgGL.GL_ONE_MINUS_DST_COLOR: return CgPipelineDesc.BlendFactor.ONE_MINUS_DST_COLOR;
            case CgGL.GL_SRC_ALPHA:           return CgPipelineDesc.BlendFactor.SRC_ALPHA;
            case CgGL.GL_ONE_MINUS_SRC_ALPHA: return CgPipelineDesc.BlendFactor.ONE_MINUS_SRC_ALPHA;
            case CgGL.GL_DST_ALPHA:           return CgPipelineDesc.BlendFactor.DST_ALPHA;
            case CgGL.GL_ONE_MINUS_DST_ALPHA: return CgPipelineDesc.BlendFactor.ONE_MINUS_DST_ALPHA;
            case 0x8001:                      return CgPipelineDesc.BlendFactor.CONSTANT_COLOR;
            case 0x8002:                      return CgPipelineDesc.BlendFactor.ONE_MINUS_CONSTANT_COLOR;
            case 0x8003:                      return CgPipelineDesc.BlendFactor.CONSTANT_ALPHA;
            case 0x8004:                      return CgPipelineDesc.BlendFactor.ONE_MINUS_CONSTANT_ALPHA;
            case CgGL.GL_SRC_ALPHA_SATURATE:  return CgPipelineDesc.BlendFactor.SRC_ALPHA_SATURATE;
            default: throw bad("blend factor", f);
        }
    }

    static CgPipelineDesc.BlendOp blendOp(int mode) {
        switch (mode) {
            case CgGL.GL_FUNC_ADD:              return CgPipelineDesc.BlendOp.ADD;
            case CgGL.GL_FUNC_SUBTRACT:         return CgPipelineDesc.BlendOp.SUBTRACT;
            case CgGL.GL_FUNC_REVERSE_SUBTRACT: return CgPipelineDesc.BlendOp.REVERSE_SUBTRACT;
            case CgGL.GL_MIN:                   return CgPipelineDesc.BlendOp.MIN;
            case CgGL.GL_MAX:                   return CgPipelineDesc.BlendOp.MAX;
            default: throw bad("blend equation", mode);
        }
    }

    static CgPipelineDesc.StencilOp stencilOp(int op) {
        switch (op) {
            case CgGL.GL_KEEP:      return CgPipelineDesc.StencilOp.KEEP;
            case CgGL.GL_ZERO:      return CgPipelineDesc.StencilOp.ZERO;
            case CgGL.GL_REPLACE:   return CgPipelineDesc.StencilOp.REPLACE;
            case CgGL.GL_INCR:      return CgPipelineDesc.StencilOp.INCREMENT_CLAMP;
            case CgGL.GL_DECR:      return CgPipelineDesc.StencilOp.DECREMENT_CLAMP;
            case CgGL.GL_INVERT:    return CgPipelineDesc.StencilOp.INVERT;
            case CgGL.GL_INCR_WRAP: return CgPipelineDesc.StencilOp.INCREMENT_WRAP;
            case CgGL.GL_DECR_WRAP: return CgPipelineDesc.StencilOp.DECREMENT_WRAP;
            default: throw bad("stencil op", op);
        }
    }

    static CgPipelineDesc.CullMode cull(boolean enabled, int mode) {
        if (!enabled) return CgPipelineDesc.CullMode.NONE;
        switch (mode) {
            case CgGL.GL_FRONT:          return CgPipelineDesc.CullMode.FRONT;
            case CgGL.GL_BACK:           return CgPipelineDesc.CullMode.BACK;
            case CgGL.GL_FRONT_AND_BACK: return CgPipelineDesc.CullMode.FRONT_AND_BACK;
            default: throw bad("cull face", mode);
        }
    }

    static CgPipelineDesc.PolygonMode polygonMode(int mode) {
        switch (mode) {
            case CgGL.GL_FILL:  return CgPipelineDesc.PolygonMode.FILL;
            case CgGL.GL_LINE:  return CgPipelineDesc.PolygonMode.LINE;
            case CgGL.GL_POINT: return CgPipelineDesc.PolygonMode.POINT;
            default: throw bad("polygon mode", mode);
        }
    }

    public static CgPipelineDesc.Topology topology(int mode) {
        switch (mode) {
            case CgGL.GL_POINTS:         return CgPipelineDesc.Topology.POINTS;
            case CgGL.GL_LINES:          return CgPipelineDesc.Topology.LINES;
            case CgGL.GL_LINE_STRIP:     return CgPipelineDesc.Topology.LINE_STRIP;
            case CgGL.GL_TRIANGLES:      return CgPipelineDesc.Topology.TRIANGLES;
            case CgGL.GL_TRIANGLE_STRIP: return CgPipelineDesc.Topology.TRIANGLE_STRIP;
            case CgGL.GL_TRIANGLE_FAN:   return CgPipelineDesc.Topology.TRIANGLE_FAN;
            default: throw bad("draw mode (a core profile has no quads or line loops here)", mode);
        }
    }

    static IllegalArgumentException bad(String what, int value) {
        return new IllegalArgumentException("Not a " + what + ": 0x" + Integer.toHexString(value));
    }
}
