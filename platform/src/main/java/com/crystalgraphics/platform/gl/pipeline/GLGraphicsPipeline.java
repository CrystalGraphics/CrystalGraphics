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
    private int program;
    private GLProgramBuild build;
    public final int vao;
    public final int mode;
    public final GLBindingPoints points;

    /** @param worker links the program off this thread; null links it here */
    public GLGraphicsPipeline(CgGLDriver gl, GLShaderTranslator translator, GLProgramBinaries binaries,
                              GLLinkWorker worker, CgGraphicsPipelineDesc desc) {
        this.desc = desc;
        this.points = new GLBindingPoints(desc.layout());
        GLShaderModule vs = (GLShaderModule) desc.vertex(), fs = (GLShaderModule) desc.fragment();
        if (worker != null) worker.submit(build = new GLProgramBuild(translator, binaries, points, desc.label(), vs, fs));
        else program = GLPrograms.link(gl, translator, binaries, points, desc.label(), vs, fs);
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

    /** The linked program, waiting for the link worker if it has not landed. */
    public int program(CgGLDriver gl) {
        if (build != null) {
            GLProgramBuild b = build;
            build = null;
            program = b.program(gl);
        }
        return program;
    }

    /** Whether {@link #program} answers without waiting. */
    public boolean ready() {
        return build == null || build.ready();
    }

    public void delete(CgGLDriver gl) {
        if (program != 0) gl.glDeleteProgram(program);
        gl.glDeleteVertexArrays(vao);
    }
}
