package com.crystalgraphics.gl.shader;

import com.crystalgraphics.api.shader.CgShaderPreprocessor;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;

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
            program = CgProgram.compile(pp.process(vertexSource, ""), pp.process(fragmentSource, ""), format);
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

    public boolean isDeleted() {
        return deleted;
    }

    public void delete() {
        if (program != null && !program.isDeleted()) program.delete();
        program = null;
        compiled = false;
        pending = false;
        locations.clear();
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
