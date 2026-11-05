package com.crystalgraphics.render.draw;

import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgColorMask;
import com.crystalgraphics.api.state.CgCullState;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.state.CgStencilState;
import com.crystalgraphics.api.vertex.CgAttribType;
import com.crystalgraphics.api.vertex.CgVertexAttribute;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.format.CgAttribFormat;
import com.crystalgraphics.platform.rhi.format.CgCompare;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc.Blend;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc.BlendFactor;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc.BlendOp;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc.CullMode;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc.DepthStencil;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc.FrontFace;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc.Raster;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc.StencilFace;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc.StencilOp;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineLayout;
import com.crystalgraphics.platform.rhi.shader.CgGlslCompiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * The engine's render state, vertex formats and explicit programs as a device's pipeline description: the pieces
 * {@link CgPipeline#device} assembles, Unreal's {@code FGraphicsPipelineStateInitializer} from a material's state.
 *
 * <pre>{@code
 * CgRenderState state = CgDeviceStates.over(passState, pipeline.state());   // the pass's, the pipeline's over it
 * CgGraphicsPipelineDesc.Raster raster = CgDeviceStates.raster(state.getCull());
 * CgPipelineLayout layout = CgDeviceStates.layout(rhi, explicit);           // the same layout objects each ask
 * }</pre>
 *
 * <ul>
 *   <li>The alpha test has no device state: a shader discards.</li>
 *   <li>The stencil reference is the pass's to set ({@code CgRenderPass.setStencilReference}), not the pipeline's.</li>
 * </ul>
 */
public final class CgDeviceStates {

    /** Binding layouts by their slots and the device that made them: a layout made once per distinct slot list. */
    private static final Map<List<CgBindingLayout.Slot>, CgBindingLayout> LAYOUTS = new HashMap<>();
    private static CgRHI layoutsOf;

    /** The GL enum values the engine's state records hold, which this maps from. */
    private static final int GL_ZERO = 0, GL_ONE = 1, GL_SRC_COLOR = 0x0300, GL_ONE_MINUS_SRC_COLOR = 0x0301,
            GL_SRC_ALPHA = 0x0302, GL_ONE_MINUS_SRC_ALPHA = 0x0303, GL_DST_ALPHA = 0x0304, GL_ONE_MINUS_DST_ALPHA = 0x0305,
            GL_DST_COLOR = 0x0306, GL_ONE_MINUS_DST_COLOR = 0x0307, GL_SRC_ALPHA_SATURATE = 0x0308,
            GL_CONSTANT_COLOR = 0x8001, GL_ONE_MINUS_CONSTANT_COLOR = 0x8002, GL_CONSTANT_ALPHA = 0x8003,
            GL_ONE_MINUS_CONSTANT_ALPHA = 0x8004, GL_MIN = 0x8007, GL_MAX = 0x8008, GL_FUNC_SUBTRACT = 0x800A,
            GL_FUNC_REVERSE_SUBTRACT = 0x800B, GL_NEVER = 0x0200, GL_LESS = 0x0201, GL_EQUAL = 0x0202, GL_LEQUAL = 0x0203,
            GL_GREATER = 0x0204, GL_NOTEQUAL = 0x0205, GL_GEQUAL = 0x0206, GL_INVERT = 0x150A, GL_REPLACE = 0x1E01,
            GL_INCR = 0x1E02, GL_DECR = 0x1E03, GL_INCR_WRAP = 0x8507, GL_DECR_WRAP = 0x8508, GL_FRONT = 0x0404,
            GL_FRONT_AND_BACK = 0x0408, GL_CW = 0x0900;

    private CgDeviceStates() {}

    /** {@code own}'s set domains over {@code pass}'s. */
    public static CgRenderState over(CgRenderState pass, CgRenderState own) {
        CgRenderState.Builder b = CgRenderState.builder();
        b.blend(own.getBlend() != null ? own.getBlend() : pass.getBlend());
        b.depth(own.getDepth() != null ? own.getDepth() : pass.getDepth());
        b.cull(own.getCull() != null ? own.getCull() : pass.getCull());
        b.stencil(own.getStencil() != null ? own.getStencil() : pass.getStencil());
        // Masks per target, as GL applies them: the pass's, then the pipeline's over them where it names a target.
        List<CgColorMask> masks = new ArrayList<>();
        if (pass.getColorMasks() != null) masks.addAll(pass.getColorMasks());
        if (own.getColorMasks() != null) masks.addAll(own.getColorMasks());
        if (!masks.isEmpty()) b.colorMasks(masks);
        return b.build();
    }

    public static Raster raster(CgCullState cull) {
        if (cull == null) return Raster.DEFAULT;
        CullMode mode = !cull.enabled() ? CullMode.NONE : switch (cull.face()) {
            case GL_FRONT -> CullMode.FRONT;
            case GL_FRONT_AND_BACK -> CullMode.FRONT_AND_BACK;
            default -> CullMode.BACK;
        };
        return new Raster(mode, cull.frontFace() == GL_CW ? FrontFace.CW : FrontFace.CCW,
                CgGraphicsPipelineDesc.PolygonMode.FILL, false);
    }

    public static DepthStencil depthStencil(CgDepthState depth, CgStencilState stencil) {
        boolean test = depth != null && depth.test(), write = depth != null && depth.write();
        CgCompare compare = depth == null ? CgCompare.LESS : compare(depth.compareFunc());
        if (stencil == null || !stencil.enabled()) {
            return new DepthStencil(test, write, compare, false, StencilFace.KEEP, StencilFace.KEEP, 0xFF, 0xFF);
        }
        StencilFace face = new StencilFace(compare(stencil.compFunc()), stencilOp(stencil.failOp()),
                stencilOp(stencil.zfailOp()), stencilOp(stencil.passOp()));
        return new DepthStencil(test, write, compare, true, face, face, stencil.readMask() & 0xFF, stencil.writeMask() & 0xFF);
    }

    /** Null for no blending. */
    public static Blend blend(CgBlendState blend) {
        if (blend == null || !blend.enabled()) return null;
        return new Blend(factor(blend.srcRgb()), factor(blend.dstRgb()), op(blend.blendEquationRgb()),
                factor(blend.srcAlpha()), factor(blend.dstAlpha()), op(blend.blendEquationAlpha()));
    }

    /** Target {@code index}'s RGBA write bits (1 2 4 8) under {@code masks}; all where none names it. */
    public static int writeMask(List<CgColorMask> masks, int index) {
        if (masks == null) return 0xF;
        CgColorMask m = null;
        for (CgColorMask c : masks) if (c.targetIndex() == index || c.targetIndex() < 0) m = c;   // the last applied wins
        if (m == null) return 0xF;
        return (m.r() ? 1 : 0) | (m.g() ? 2 : 0) | (m.b() ? 4 : 0) | (m.a() ? 8 : 0);
    }

    /** {@code format} as one interleaved vertex buffer at binding 0, attribute {@code i} at location {@code i}. */
    public static List<CgGraphicsPipelineDesc.VertexBuffer> vertexBuffers(CgVertexFormat format) {
        if (format == null || format.getAttributeCount() == 0) return List.of();
        List<CgGraphicsPipelineDesc.VertexAttrib> attribs = new ArrayList<>(format.getAttributeCount());
        for (int i = 0; i < format.getAttributeCount(); i++) {
            CgVertexAttribute a = format.getAttribute(i);
            attribs.add(new CgGraphicsPipelineDesc.VertexAttrib(i, attribFormat(a), a.getOffset()));
        }
        return List.of(new CgGraphicsPipelineDesc.VertexBuffer(0, format.getStride(), false, attribs));
    }

    /** A vertex attribute's device format. */
    public static CgAttribFormat attribFormat(CgVertexAttribute a) {
        boolean norm = a.isNormalized(), integer = a.isInteger();
        String base = switch (a.getType()) {
            case FLOAT -> "FLOAT32";
            case HALF_FLOAT -> "FLOAT16";
            case UNSIGNED_BYTE -> norm ? "UNORM8" : integer ? "UINT8" : "USCALED8";
            case BYTE -> norm ? "SNORM8" : integer ? "SINT8" : "SSCALED8";
            case UNSIGNED_SHORT -> norm ? "UNORM16" : integer ? "UINT16" : "USCALED16";
            case SHORT -> norm ? "SNORM16" : integer ? "SINT16" : "SSCALED16";
            case UNSIGNED_INT -> "UINT32";
            case INT -> "SINT32";
        };
        String name = a.getComponents() > 1 ? base + "X" + a.getComponents() : base;
        try {
            return CgAttribFormat.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(a.getName() + ": no device attribute format " + name, e);
        }
    }

    /**
     * {@code program}'s pipeline layout on {@code rhi}: a binding layout per set (made once per distinct slot list), the
     * device's heap layout at set 3 where it reads the heap, its push range.
     */
    public static synchronized CgPipelineLayout layout(CgRHI rhi, CgGlslCompiler.Explicit program) {
        if (layoutsOf != rhi) {
            LAYOUTS.clear();
            layoutsOf = rhi;
        }
        List<CgBindingLayout> sets = new ArrayList<>(program.sets().size());
        for (int s = 0; s < program.sets().size(); s++) {
            if (program.heap() && s == CgRHI.BINDLESS_SET) {
                CgBindingLayout heap = rhi.bindlessLayout();
                if (heap == null) throw new IllegalStateException(rhi.globals().name() + " has no bindless heap");
                sets.add(heap);
                continue;
            }
            List<CgBindingLayout.Slot> slots = program.sets().get(s);
            sets.add(LAYOUTS.computeIfAbsent(slots, k -> rhi.createBindingLayout("set " + k, k, false)));
        }
        return new CgPipelineLayout(sets, program.pushConstantBytes());
    }

    static CgCompare compare(int gl) {
        return switch (gl) {
            case GL_NEVER -> CgCompare.NEVER;
            case GL_LESS -> CgCompare.LESS;
            case GL_EQUAL -> CgCompare.EQUAL;
            case GL_LEQUAL -> CgCompare.LESS_EQUAL;
            case GL_GREATER -> CgCompare.GREATER;
            case GL_NOTEQUAL -> CgCompare.NOT_EQUAL;
            case GL_GEQUAL -> CgCompare.GREATER_EQUAL;
            default -> CgCompare.ALWAYS;
        };
    }

    static StencilOp stencilOp(int gl) {
        return switch (gl) {
            case GL_ZERO -> StencilOp.ZERO;
            case GL_REPLACE -> StencilOp.REPLACE;
            case GL_INCR -> StencilOp.INCREMENT_CLAMP;
            case GL_DECR -> StencilOp.DECREMENT_CLAMP;
            case GL_INVERT -> StencilOp.INVERT;
            case GL_INCR_WRAP -> StencilOp.INCREMENT_WRAP;
            case GL_DECR_WRAP -> StencilOp.DECREMENT_WRAP;
            default -> StencilOp.KEEP;
        };
    }

    static BlendFactor factor(int gl) {
        return switch (gl) {
            case GL_ZERO -> BlendFactor.ZERO;
            case GL_ONE -> BlendFactor.ONE;
            case GL_SRC_COLOR -> BlendFactor.SRC_COLOR;
            case GL_ONE_MINUS_SRC_COLOR -> BlendFactor.ONE_MINUS_SRC_COLOR;
            case GL_DST_COLOR -> BlendFactor.DST_COLOR;
            case GL_ONE_MINUS_DST_COLOR -> BlendFactor.ONE_MINUS_DST_COLOR;
            case GL_SRC_ALPHA -> BlendFactor.SRC_ALPHA;
            case GL_ONE_MINUS_SRC_ALPHA -> BlendFactor.ONE_MINUS_SRC_ALPHA;
            case GL_DST_ALPHA -> BlendFactor.DST_ALPHA;
            case GL_ONE_MINUS_DST_ALPHA -> BlendFactor.ONE_MINUS_DST_ALPHA;
            case GL_CONSTANT_COLOR -> BlendFactor.CONSTANT_COLOR;
            case GL_ONE_MINUS_CONSTANT_COLOR -> BlendFactor.ONE_MINUS_CONSTANT_COLOR;
            case GL_CONSTANT_ALPHA -> BlendFactor.CONSTANT_ALPHA;
            case GL_ONE_MINUS_CONSTANT_ALPHA -> BlendFactor.ONE_MINUS_CONSTANT_ALPHA;
            case GL_SRC_ALPHA_SATURATE -> BlendFactor.SRC_ALPHA_SATURATE;
            default -> throw new IllegalArgumentException("blend factor 0x" + Integer.toHexString(gl));
        };
    }

    static BlendOp op(int gl) {
        return switch (gl) {
            case GL_FUNC_SUBTRACT -> BlendOp.SUBTRACT;
            case GL_FUNC_REVERSE_SUBTRACT -> BlendOp.REVERSE_SUBTRACT;
            case GL_MIN -> BlendOp.MIN;
            case GL_MAX -> BlendOp.MAX;
            default -> BlendOp.ADD;
        };
    }
}
