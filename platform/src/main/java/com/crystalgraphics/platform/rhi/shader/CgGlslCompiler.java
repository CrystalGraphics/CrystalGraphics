package com.crystalgraphics.platform.rhi.shader;

import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

/**
 * Compiles a GL program's GLSL, as GL takes it, into SPIR-V a {@link CgRHI} can load, and reports where every
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

    /** A compute stage, every binding assigned as {@link #compile} assigns them. @throws CgShaderModule.CompileException */
    ComputeProgram compileCompute(String glsl, String label);

    /**
     * Both stages as Vulkan SPIR-V with their sets and bindings as the source writes them, under Vulkan's own rules
     * with nothing bound for them, and the pipeline layout they declare: what a device builds a pipeline from.
     *
     * <pre>{@code
     * CgGlslCompiler.Explicit p = CgGlslCompiler.get().compileExplicit(vertexGlsl, fragmentGlsl, Map.of("cg_Position", 0),
 *         "stone");
     * CgShaderModule vs = rhi.createShaderModule(CgShaderModule.Stage.VERTEX, p.vertex(), "stone");
     * }</pre>
     *
     * @param attribLocations each named vertex input's location, as {@link #compile} binds them; the rest placed around
     * @throws CgShaderModule.CompileException with the compiler's log, a source leaving a resource unbound included
     */
    default Explicit compileExplicit(String vertexGlsl, String fragmentGlsl, Map<String, Integer> attribLocations,
                                     String label) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " compiles no explicit programs");
    }

    /**
     * A program as written: each stage's SPIR-V, each set's slots by binding in set order (empty for a set it skips),
     * whether it reads the bindless heap at set 3 (then that set's slot list is empty), its push-constant bytes, and the
     * colour outputs its fragment stage writes. Two vertex stages: {@code vertex} as written, for a projection already in
     * a device's zero-to-one depth; {@code vertexGlDepth} with its clip depth mapped from GL's {@code [-w, w]} to
     * {@code [0, w]}, for the engine's usual projections.
     */
    record Explicit(ByteBuffer vertex, ByteBuffer vertexGlDepth, ByteBuffer fragment, List<List<CgBindingLayout.Slot>> sets,
                    boolean heap, int pushConstantBytes, int colorOutputs) {}

    /** The compiler installed now, for code making device pipelines; throws until a host installs one. */
    static CgGlslCompiler get() {
        CgGlslCompiler c = Slot.installed;
        if (c == null) throw new IllegalStateException("no CgGlslCompiler installed: a host with a shader compiler installs one");
        return c;
    }

    /** Whether a compiler is installed. */
    static boolean installed() { return Slot.installed != null; }

    /** Installs {@code compiler}; null when it closes. */
    static void install(CgGlslCompiler compiler) { Slot.installed = compiler; }

    /** Where {@link #get} reads from. */
    final class Slot {
        static volatile CgGlslCompiler installed;

        private Slot() {}
    }

    /** What GL's program queries read, from either kind of program. */
    sealed interface Reflection permits Program, ComputeProgram {
        List<Block> uniformBlocks();

        List<Block> storageBlocks();

        List<Sampler> samplers();

        List<Uniform> uniforms();
    }

    /** A vertex input and the location it reads. */
    record Attribute(String name, int location, int glType) {}

    /**
     * A uniform or storage block and its binding. {@code declared} is its {@code layout(binding = n)}, 0 where the
     * source gives none: the point a GL program starts it at, until {@code glUniformBlockBinding} moves it.
     */
    record Block(String name, int binding, int declared) {
        public Block(String name, int binding) {
            this(name, binding, 0);
        }
    }

    /** A sampler or texel buffer and its binding; {@code declared} as {@link Block}'s, its starting unit. */
    record Sampler(String name, int binding, int glType, boolean texel, int declared) {
        public Sampler(String name, int binding, int glType, boolean texel) {
            this(name, binding, glType, texel, 0);
        }
    }

    /** A storage image and its binding; {@code declared} as {@link Block}'s, its starting unit. */
    record Image(String name, int binding, int glType, int declared) {
        public Image(String name, int binding, int glType) {
            this(name, binding, glType, 0);
        }
    }

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
                   List<CgBindingLayout.Slot> slots) implements Reflection {}

    /**
     * A compiled compute stage. Its loose uniforms are in one block, {@link #uniformBinding}, each at its
     * {@link Uniform#vertexOffset}: the block every stage-indexed reader takes first.
     *
     * @param localSize      the work group's size, as the source declares it
     * @param uniformBinding the loose-uniform block, or -1 without one
     */
    record ComputeProgram(ByteBuffer spirv, int[] localSize, List<Block> uniformBlocks, List<Block> storageBlocks,
                          List<Sampler> samplers, List<Image> images, List<Uniform> uniforms,
                          int uniformBinding, int uniformSize, List<CgBindingLayout.Slot> slots)
            implements Reflection {}
}
