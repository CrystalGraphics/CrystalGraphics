package com.crystalgraphics.platform.gl.pipeline;

import com.crystalgraphics.platform.gl.binding.GLBindingPoints;
import com.crystalgraphics.platform.gl.driver.CgGLDriver;
import com.crystalgraphics.platform.gl.format.GLFormats;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipeline;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc.VertexAttrib;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc.VertexBuffer;

import static com.crystalgraphics.platform.gl.driver.GLC.*;

/**
 * A graphics pipeline on GL: its linked program, a vertex array holding its vertex format, and its binding points. Its
 * fixed-function state is applied by the pass before a draw (Unreal's PSO fallback), never at creation.
 */
public final class GLGraphicsPipeline implements CgGraphicsPipeline {

    private final CgGraphicsPipelineDesc desc;
    public final int program;
    public final int vao;
    public final int mode;
    public final GLBindingPoints points;

    public GLGraphicsPipeline(CgGLDriver gl, GLShaderTranslator translator, CgGraphicsPipelineDesc desc) {
        this.desc = desc;
        this.points = new GLBindingPoints(desc.layout());
        this.program = GLPrograms.link(gl, translator, points, desc.label(), desc.vertex(), desc.fragment());
        this.mode = mode(desc.topology());
        this.vao = gl.glCreateVertexArrays();
        for (VertexBuffer vb : desc.vertexBuffers()) {
            if (vb.perInstance()) gl.glVertexArrayBindingDivisor(vao, vb.binding(), 1);
            for (VertexAttrib a : vb.attribs()) {
                gl.glEnableVertexArrayAttrib(vao, a.location());
                int size = GLFormats.attribSize(a.format()), type = GLFormats.attribType(a.format());
                if (GLFormats.attribInteger(a.format())) gl.glVertexArrayAttribIFormat(vao, a.location(), size, type, a.offset());
                else gl.glVertexArrayAttribFormat(vao, a.location(), size, type, GLFormats.attribNormalized(a.format()),
                        a.offset());
                gl.glVertexArrayAttribBinding(vao, a.location(), vb.binding());
            }
        }
        gl.glObjectLabel(GL_VERTEX_ARRAY, vao, desc.label());
    }

    private static int mode(CgGraphicsPipelineDesc.Topology t) {
        return switch (t) {
            case POINTS -> GL_POINTS;
            case LINES -> GL_LINES;
            case LINE_STRIP -> GL_LINE_STRIP;
            case TRIANGLES -> GL_TRIANGLES;
            case TRIANGLE_STRIP -> GL_TRIANGLE_STRIP;
            case TRIANGLE_FAN -> GL_TRIANGLE_FAN;
        };
    }

    /** The stride of vertex buffer {@code binding}, which GL takes with the buffer. */
    public int stride(int binding) {
        for (VertexBuffer vb : desc.vertexBuffers()) if (vb.binding() == binding) return vb.stride();
        throw new IllegalArgumentException(desc.label() + " has no vertex buffer " + binding);
    }

    @Override public CgGraphicsPipelineDesc desc() { return desc; }

    public void delete(CgGLDriver gl) {
        gl.glDeleteProgram(program);
        gl.glDeleteVertexArrays(vao);
    }
}
