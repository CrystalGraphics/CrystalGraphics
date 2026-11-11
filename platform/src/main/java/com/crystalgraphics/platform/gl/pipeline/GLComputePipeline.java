package com.crystalgraphics.platform.gl.pipeline;

import com.crystalgraphics.platform.gl.binding.GLBindingPoints;
import com.crystalgraphics.platform.gl.driver.CgGLDriver;
import com.crystalgraphics.platform.rhi.pipeline.CgComputePipeline;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineLayout;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;

/** A compute pipeline on GL: its linked program (shared through {@link GLProgramCache}) and binding points. */
public final class GLComputePipeline implements CgComputePipeline {

    private final String label;
    private final CgShaderModule module;
    private final CgPipelineLayout layout;
    private final GLProgramCache.Program program;
    public final GLBindingPoints points;

    /** @param programs where its program is linked, shared with every pipeline of the same module */
    public GLComputePipeline(GLProgramCache programs, String label, CgShaderModule module, CgPipelineLayout layout) {
        this.label = label;
        this.module = module;
        this.layout = layout;
        this.program = programs.acquire(label, layout, (GLShaderModule) module);
        this.points = program.points;
    }

    @Override public CgShaderModule module() { return module; }

    @Override public CgPipelineLayout layout() { return layout; }

    @Override public String label() { return label; }

    /** The linked program, waiting for the link worker if it has not landed. */
    public int program(CgGLDriver gl) {
        return program.name(gl);
    }

    /** Whether {@link #program} answers without waiting. */
    public boolean ready() {
        return program.ready();
    }

    public void delete(CgGLDriver gl, GLProgramCache programs) {
        programs.release(gl, program);
    }
}
