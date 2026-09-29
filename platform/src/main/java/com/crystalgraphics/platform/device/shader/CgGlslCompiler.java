package com.crystalgraphics.platform.device.shader;

import com.crystalgraphics.platform.device.CgDevice;
import com.crystalgraphics.platform.device.pipeline.CgBindingLayout;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

/**
 * Compiles a GL program's GLSL, as GL takes it, into SPIR-V a {@link CgDevice} can load, and reports where every
 * name the program's GL calls use ended up. Implemented over shaderc and SPIRV-Cross in
 * {@code runtime/lwjgl/vulkan}, as Minecraft 26.2 compiles its own shaders.
 *
 * <pre>{@code
 * CgGlslCompiler.Program p = compiler.compile(vertexGlsl, fragmentGlsl, Map.of("cg_Position", 0), "text");
 * CgShaderModule vs = device.createShaderModule(CgShaderModule.Stage.VERTEX, p.vertexGlDepth(), "text");
 * CgBindingLayout layout = device.createBindingLayout("text", p.slots());
 * }</pre>
 *
 * <ul>
 *   <li>Every binding is in set 0; a name both stages use has one binding.</li>
 *   <li>Vertex inputs take the locations {@code attribLocations} gives them; fragment inputs take the location of
 *       the vertex output with their name.</li>
 *   <li>Loose uniforms live in a block per stage, {@link Program#vertexUniformBinding} and
 *       {@link Program#fragmentUniformBinding}, at the offsets {@link Program#uniforms} gives.</li>
 * </ul>
 */
public interface CgGlslCompiler {

    /** @throws CgShaderModule.CompileException with the compiler's log */
    Program compile(String vertexGlsl, String fragmentGlsl, Map<String, Integer> attribLocations, String label);

    /** A vertex input and the location it reads. */
    record Attribute(String name, int location, int glType) {}

    /** A uniform or storage block and its binding. */
    record Block(String name, int binding) {}

    /** A sampler or texel buffer and its binding. */
    record Sampler(String name, int binding, int glType, boolean texel) {}

    /**
     * One leaf of a loose uniform: {@code -1} for a stage that does not declare it. Element {@code e}, column
     * {@code c}, row {@code r} is at {@code offset + e * stride + c * matrixStride + r * 4}.
     */
    record Uniform(String name, int glType, int count, int stride, int matrixStride, int vertexOffset,
                   int fragmentOffset, int columns, int rows, boolean integer) {}

    /**
     * @param vertexGlDepth   the vertex stage for our own passes: clip depth remapped from GL's range
     * @param vertexZeroToOne the vertex stage for a pass into a host's zero-to-one depth
     * @param vertexUniformBinding the vertex stage's loose-uniform block, or -1 without one
     */
    record Program(ByteBuffer vertexGlDepth, ByteBuffer vertexZeroToOne, ByteBuffer fragment,
                   List<Attribute> attributes, List<Block> uniformBlocks, List<Block> storageBlocks,
                   List<Sampler> samplers, List<Uniform> uniforms,
                   int vertexUniformBinding, int vertexUniformSize,
                   int fragmentUniformBinding, int fragmentUniformSize,
                   List<CgBindingLayout.Slot> slots) {}
}
