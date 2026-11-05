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
    public final int program;
    public final GLBindingPoints points;

    public GLComputePipeline(CgGLDriver gl, GLShaderTranslator translator, String label, CgShaderModule module,
                             CgPipelineLayout layout) {
        this.label = label;
        this.module = module;
        this.layout = layout;
        this.points = new GLBindingPoints(layout);
        this.program = GLPrograms.link(gl, translator, points, label, module);
    }

    @Override public CgShaderModule module() { return module; }

    @Override public CgPipelineLayout layout() { return layout; }

    @Override public String label() { return label; }
}
