package com.crystalgraphics.platform.gl.pipeline;

import com.crystalgraphics.platform.rhi.shader.CgShaderModule;

import java.nio.ByteBuffer;

/** One stage's SPIR-V, kept until a pipeline translates it under its layout's binding points. */
public final class GLShaderModule implements CgShaderModule {

    private final Stage stage;
    private final String label;
    public final ByteBuffer spirv;

    public GLShaderModule(Stage stage, ByteBuffer spirv, String label) {
        this.stage = stage;
        this.label = label;
        this.spirv = ByteBuffer.allocateDirect(spirv.remaining()).order(spirv.order()).put(spirv.duplicate()).flip();
    }

    @Override public Stage stage() { return stage; }

    @Override public String label() { return label; }
}
