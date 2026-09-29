package com.crystalgraphics.platform.gl.tracked;

import com.crystalgraphics.platform.device.pipeline.CgBindingLayout;
import com.crystalgraphics.platform.device.recording.CgRecordingDevice;
import com.crystalgraphics.platform.device.shader.CgGlslCompiler;
import com.crystalgraphics.platform.device.shader.CgShaderModule;

import java.util.List;
import java.util.Map;

/** A compiler that answers with a table the test wrote, and fails on a source containing {@code BROKEN}. */
public final class FakeGlslCompiler implements CgGlslCompiler {

    public static final FakeGlslCompiler EMPTY = new FakeGlslCompiler(List.of(), List.of(), List.of(), List.of(), -1, 0, List.of());

    private final List<Attribute> attributes;
    private final List<Block> uniformBlocks;
    private final List<Sampler> samplers;
    private final List<Uniform> uniforms;
    private final int vertexUniformBinding, vertexUniformSize;
    private final List<CgBindingLayout.Slot> slots;

    public FakeGlslCompiler(List<Attribute> attributes, List<Block> uniformBlocks, List<Sampler> samplers,
                     List<Uniform> uniforms, int vertexUniformBinding, int vertexUniformSize,
                     List<CgBindingLayout.Slot> slots) {
        this.attributes = attributes;
        this.uniformBlocks = uniformBlocks;
        this.samplers = samplers;
        this.uniforms = uniforms;
        this.vertexUniformBinding = vertexUniformBinding;
        this.vertexUniformSize = vertexUniformSize;
        this.slots = slots;
    }

    @Override
    public Program compile(String vertexGlsl, String fragmentGlsl, Map<String, Integer> attribLocations, String label) {
        if (vertexGlsl.contains("BROKEN") || fragmentGlsl.contains("BROKEN"))
            throw new CgShaderModule.CompileException(label + ": 'BROKEN' : undeclared identifier");
        return new Program(CgRecordingDevice.emptySpirv(), CgRecordingDevice.emptySpirv(), CgRecordingDevice.emptySpirv(),
                attributes, uniformBlocks, List.of(), samplers, uniforms, vertexUniformBinding, vertexUniformSize, -1, 0, slots);
    }
}
