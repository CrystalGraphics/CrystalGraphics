package com.crystalgraphics.gl.shader;

import com.crystalgraphics.api.shader.CgShaderPreprocessor;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.shader.CgGlslCompiler;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * A linked program from sources the engine generated: what a material's pass and keyword set, or a kernel, compiles
 * into until S4 makes them device pipelines. Internal: a consumer reaches a shader through a material or a kernel.
 *
 * <pre>{@code
 * CgShader program = CgShaderFactory.submit(vertex, fragment, format);   // returns before the driver links
 * if (program.isReady() && program.isCompiled()) draw(program);
 * try (CgGlScope ignored = program.bindScoped()) { ... }                // the host's program restored
 * }</pre>
 *
 * <ul>
 *   <li>A failure is logged and kept ({@link #getLastCompileError}), never thrown; {@link #bind} on a failed program
 *       changes nothing.</li>
 *   <li>Anything asked of a submitted program before {@link #isReady} waits for the driver.</li>
 *   <li>Render thread.</li>
 * </ul>
 */
public final class CgShader {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    private final String vertexSource, fragmentSource;
    private final CgVertexFormat format;
    private CgProgram program;
    private boolean compiled, pending, deleted;
    private String lastCompileError;
    /** Locations by name for the current program; cleared with it. */
    private final HashMap<String, Integer> locations = new HashMap<>();
    /** The sources the program linked, preprocessed: what {@link #explicit} compiles. */
    private String linkedVertex, linkedFragment;
    private CgGlslCompiler.Explicit explicit;
    /** {@link #explicit}'s stages on {@link #modulesOf}: the vertex stage as written and in GL depth, the fragment. */
    private CgShaderModule deviceVertex, deviceVertexGlDepth, deviceFragment;
    private CgRHI modulesOf;

    CgShader(String vertexSource, String fragmentSource, CgVertexFormat format) {
        this.vertexSource = vertexSource;
        this.fragmentSource = fragmentSource;
        this.format = format;
    }

    /** Compiles and links now, preprocessing the sources first. */
    void compile() {
        pending = false;
        CgShaderPreprocessor pp = new CgShaderPreprocessor();
        try {
            linkedVertex = pp.process(vertexSource, "");
            linkedFragment = pp.process(fragmentSource, "");
            program = CgProgram.compile(linkedVertex, linkedFragment, format);
        } catch (Exception e) {
            LOGGER.error("Failed to compile inline shader: {}", e.getMessage());
            lastCompileError = e.getMessage();
            compiled = false;
            return;
        }
        compiled = true;
        lastCompileError = null;
    }

    /** Starts the link and returns before the driver has finished it; the sources are used as given. */
    void submit() {
        CgProgram next = CgProgram.create();
        try {
            next.submitLink(vertexSource, fragmentSource, format);
        } catch (RuntimeException failed) {
            next.delete();
            lastCompileError = failed.getMessage();
            compiled = false;
            return;
        }
        program = next;
        linkedVertex = vertexSource;
        linkedFragment = fragmentSource;
        pending = true;
        compiled = false;
    }

    /** Whether it linked, waiting for a submitted link. */
    public boolean isCompiled() {
        finishPending();
        return compiled;
    }

    /** Whether asking it anything waits for the driver no longer. */
    public boolean isReady() {
        return !pending || program.isLinkDone();
    }

    /** The driver's or shaderc's log of the last failure; null when it linked. */
    public String getLastCompileError() {
        finishPending();
        return lastCompileError;
    }

    /** The program object; null when it failed. */
    public CgProgram getProgram() {
        finishPending();
        return program;
    }

    /** A uniform's location, -1 for one the program does not use. */
    public int getUniformLocation(String name) {
        finishPending();
        if (!compiled) return -1;
        Integer cached = locations.get(name);
        if (cached != null) return cached;
        int location = program.getUniformLocation(name);
        locations.put(name, location);
        return location;
    }

    public void bind() {
        finishPending();
        if (compiled) program.bind();
    }

    public void unbind() {
        if (compiled) program.unbind();
    }

    /** Binds it; the scope restores the program bound before. */
    public CgGlScope bindScoped() {
        finishPending();
        if (!compiled) return CgGlScope.NOOP_SCOPE;
        CgGlScope saved = CgGlState.saveProgram();
        program.bind();
        return saved;
    }

    /** Binds it; the scope restores {@code slots} as they were before. */
    public CgGlScope bindScoped(CgGlSlot... slots) {
        if (slots == null || slots.length == 0) return bindScoped();
        finishPending();
        if (!compiled) return CgGlScope.NOOP_SCOPE;
        CgGlScope saved = CgGlState.save(slots);
        program.bind();
        return saved;
    }

    /** The vertex format its attributes are bound from, attribute {@code i} at location {@code i}; null for none. */
    public CgVertexFormat format() {
        return format;
    }

    /**
     * The program as written, for a device pipeline: the sources it linked through {@link CgGlslCompiler#compileExplicit},
     * each vertex attribute at the location the GL program binds it. Compiled on first ask.
     *
     * @throws CgShaderModule.CompileException where the sources leave a resource unbound, or no compiler is installed
     */
    public CgGlslCompiler.Explicit explicit(String label) {
        if (explicit == null) {
            if (linkedVertex == null) throw new IllegalStateException(label + ": never linked");
            Map<String, Integer> attribs = new HashMap<>();
            if (format != null) for (int i = 0; i < format.getAttributeCount(); i++) attribs.put(format.getAttribute(i).getName(), i);
            explicit = CgGlslCompiler.get().compileExplicit(linkedVertex, linkedFragment, attribs, label);
        }
        return explicit;
    }

    /**
     * {@link #explicit}'s {@code stage} as {@code rhi}'s shader module, every stage made once a device. With
     * {@code glDepth}, the vertex stage mapping GL's clip depth to the device's.
     */
    public CgShaderModule deviceModule(CgRHI rhi, CgShaderModule.Stage stage, boolean glDepth, String label) {
        if (modulesOf != rhi) {
            releaseModules();
            CgGlslCompiler.Explicit p = explicit(label);
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

    public void delete() {
        if (program != null && !program.isDeleted()) program.delete();
        program = null;
        compiled = false;
        pending = false;
        locations.clear();
        releaseModules();
        deleted = true;
    }

    private void finishPending() {
        if (!pending) return;
        pending = false;
        try {
            program.finishLink();
        } catch (IllegalStateException e) {
            LOGGER.error("Failed to compile inline shader: {}", e.getMessage());
            lastCompileError = e.getMessage();
            compiled = false;
            return;
        }
        compiled = true;
        lastCompileError = null;
    }
}
