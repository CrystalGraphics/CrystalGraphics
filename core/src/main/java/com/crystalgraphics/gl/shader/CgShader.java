package com.crystalgraphics.gl.shader;

import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineRecipes;
import com.crystalgraphics.platform.rhi.shader.CgGlslCompiler;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;
import com.crystalgraphics.render.shader.CgBindingModel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A material pass and keyword set compiled from the sources the engine generated: the program as written
 * ({@link CgGlslCompiler#compileExplicit}) and its shader modules on a device, which its pipelines are built from.
 * Internal: a consumer reaches a shader through a material.
 *
 * <pre>{@code
 * CgShader program = CgShader.submit(vertex, fragment, format, "stone Forward");   // compiling on a worker
 * if (program.isReady() && program.isCompiled()) pipelines(program);
 * }</pre>
 *
 * <ul>
 *   <li>A failure is kept ({@link #getLastCompileError}), never thrown, but by {@link #explicit}.</li>
 *   <li>Anything asked of a submitted program before {@link #isReady} waits for its compile.</li>
 *   <li>Render thread.</li>
 * </ul>
 */
public final class CgShader {

    /** {@link #sceneReads} bits: the program samples the scene depth, or the scene colour. */
    public static final int READS_DEPTH = 1, READS_COLOR = 2;

    private final String vertexSource, fragmentSource, label;
    private final CgVertexFormat format;
    private CgShaderCompile<CgGlslCompiler.Explicit> compile;
    private CgGlslCompiler.Explicit explicit;
    private String lastCompileError;
    private boolean deleted;
    /** {@link #explicit}'s stages on {@link #modulesOf}: the vertex stage as written and in GL depth, the fragment. */
    private CgShaderModule deviceVertex, deviceVertexGlDepth, deviceFragment;
    private CgRHI modulesOf;

    private CgShader(String vertexSource, String fragmentSource, CgVertexFormat format, String label) {
        this.vertexSource = vertexSource;
        this.fragmentSource = fragmentSource;
        this.format = format;
        this.label = label;
    }

    /** Compiled before it returns, from preprocessed sources. */
    public static CgShader compile(String vertex, String fragment, CgVertexFormat format, String label) {
        CgShader shader = submit(vertex, fragment, format, label);
        shader.finish();
        return shader;
    }

    /** Compiling on a worker where the compiler allows it, else at its first ask; from preprocessed sources. */
    public static CgShader submit(String vertex, String fragment, CgVertexFormat format, String label) {
        CgShader shader = new CgShader(vertex, fragment, format, label);
        Map<String, Integer> attribs = shader.attribs();
        shader.compile = CgShaderCompile.start(() -> CgGlslCompiler.get().compileExplicit(vertex, fragment, attribs, label));
        return shader;
    }

    /** Whether it compiled, waiting for a submitted compile. */
    public boolean isCompiled() {
        finish();
        return explicit != null;
    }

    /** Whether asking it anything waits for its compile no longer. */
    public boolean isReady() {
        return compile == null || compile.isDone();
    }

    /** The compiler's log of the failure; null when it compiled. */
    public String getLastCompileError() {
        finish();
        return lastCompileError;
    }

    /** The vertex format its attributes are bound from, attribute {@code i} at location {@code i}; null for none. */
    public CgVertexFormat format() {
        return format;
    }

    /**
     * The program as written, for a device pipeline: each vertex attribute at its format's location.
     *
     * @throws CgShaderModule.CompileException with the compiler's log, where it failed
     */
    public CgGlslCompiler.Explicit explicit() {
        finish();
        if (explicit == null) throw new CgShaderModule.CompileException(label + ": " + lastCompileError);
        return explicit;
    }

    /**
     * What it samples of the scene, as {@link #READS_DEPTH} bits: from its own layout, so a keyword variant answers for
     * itself. 0 for a program that failed.
     */
    public int sceneReads() {
        if (!isCompiled()) return 0;
        List<List<CgBindingLayout.Slot>> sets = explicit.sets();
        if (sets.size() <= CgBindingModel.VIEW) return 0;
        int reads = 0;
        for (CgBindingLayout.Slot slot : sets.get(CgBindingModel.VIEW)) {
            if (slot.binding() == CgBindingModel.DEPTH) reads |= READS_DEPTH;
            if (slot.binding() == CgBindingModel.SCENE_COLOR) reads |= READS_COLOR;
        }
        return reads;
    }

    /** Each vertex attribute at its index in {@link #format}. */
    private Map<String, Integer> attribs() {
        Map<String, Integer> attribs = new HashMap<>();
        if (format != null) for (int i = 0; i < format.getAttributeCount(); i++) attribs.put(format.getAttribute(i).getName(), i);
        return attribs;
    }

    /** What its device pipelines are recorded by ({@code CgPipelineRecipes}): a hash of its sources. */
    public long deviceKey() {
        if (deviceKey == 0) deviceKey = CgPipelineRecipes.programKey(DEVICE_KEY + vertexSource, fragmentSource, attribs());
        return deviceKey;
    }

    private static final String DEVICE_KEY = "device\u0000";
    private long deviceKey;
    /** The device its recorded pipelines were started on ({@link #startsOn}). */
    private CgRHI startedOn;

    /** True once a device: whoever asks first starts its recorded pipelines there. */
    public boolean startsOn(CgRHI rhi) {
        if (startedOn == rhi) return false;
        startedOn = rhi;
        return true;
    }

    /**
     * {@link #explicit}'s {@code stage} as {@code rhi}'s shader module, every stage made once a device. With
     * {@code glDepth}, the vertex stage mapping GL's clip depth to the device's.
     */
    public CgShaderModule deviceModule(CgRHI rhi, CgShaderModule.Stage stage, boolean glDepth) {
        if (modulesOf != rhi) {
            releaseModules();
            CgGlslCompiler.Explicit p = explicit();
            deviceVertex = rhi.createShaderModule(CgShaderModule.Stage.VERTEX, p.vertex(), label);
            deviceVertexGlDepth = rhi.createShaderModule(CgShaderModule.Stage.VERTEX, p.vertexGlDepth(), label);
            deviceFragment = rhi.createShaderModule(CgShaderModule.Stage.FRAGMENT, p.fragment(), label);
            modulesOf = rhi;
        }
        if (stage != CgShaderModule.Stage.VERTEX) return deviceFragment;
        return glDepth ? deviceVertexGlDepth : deviceVertex;
    }

    private void releaseModules() {
        if (modulesOf == null) return;
        modulesOf.release(deviceVertex);
        modulesOf.release(deviceVertexGlDepth);
        modulesOf.release(deviceFragment);
        deviceVertex = deviceVertexGlDepth = deviceFragment = null;
        modulesOf = null;
    }

    public boolean isDeleted() {
        return deleted;
    }

    /** Releases its modules; a compile still running finishes unread. */
    public void delete() {
        compile = null;
        explicit = null;
        releaseModules();
        deviceKey = 0;
        deleted = true;
    }

    private void finish() {
        if (compile == null) return;
        CgShaderCompile<CgGlslCompiler.Explicit> pending = compile;
        compile = null;
        try {
            explicit = pending.get();
            lastCompileError = null;
        } catch (RuntimeException e) {
            explicit = null;
            lastCompileError = String.valueOf(e.getMessage());
        }
    }

    @Override
    public String toString() {
        return label;
    }
}
