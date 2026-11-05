package com.crystalgraphics.platform.gl.pipeline;

import com.crystalgraphics.platform.gl.binding.GLBindingPoints;
import com.crystalgraphics.platform.gl.driver.CgGLDriver;
import com.crystalgraphics.platform.rhi.pipeline.CgComputePipeline;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineLayout;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;

/** A compute pipeline on GL: its linked program and binding points. */
public final class GLComputePipeline implements CgComputePipeline {

    private final String label;
    private final CgShaderModule module;
    private final CgPipelineLayout layout;
    private int program;
    private GLProgramBuild build;
    public final GLBindingPoints points;

    /** @param worker links the program off this thread; null links it here */
    public GLComputePipeline(CgGLDriver gl, GLShaderTranslator translator, GLProgramBinaries binaries, GLLinkWorker worker,
                             String label, CgShaderModule module, CgPipelineLayout layout) {
        this.label = label;
        this.module = module;
        this.layout = layout;
        this.points = new GLBindingPoints(layout);
        GLShaderModule m = (GLShaderModule) module;
        if (worker != null) worker.submit(build = new GLProgramBuild(translator, binaries, points, label, m));
        else program = GLPrograms.link(gl, translator, binaries, points, label, m);
    }

    @Override public CgShaderModule module() { return module; }

    @Override public CgPipelineLayout layout() { return layout; }

    @Override public String label() { return label; }

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
    }
}
