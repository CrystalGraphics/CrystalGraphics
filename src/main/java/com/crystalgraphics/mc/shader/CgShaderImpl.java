package com.crystalgraphics.mc.shader;

import com.crystalgraphics.api.shader.*;
import io.github.somehussar.crystalgraphics.api.shader.*;
import com.crystalgraphics.api.state.CgGlSlot;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.shader.CgShaderFactory;
import com.crystalgraphics.gl.state.CgGlScope;
import com.crystalgraphics.gl.state.CgGlState;
import com.crystalgraphics.util.io.CgIO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Concrete implementation of {@link CgShader} that supports two creation modes:
 *
 * <ol>
 *   <li><strong>Path-based</strong> — loads GLSL sources from Minecraft resources via
 *       {@link CgIO#loadSource(String)}, preprocesses them, and compiles. Created by
 *       {@link CgShaderManagerImpl} via the
 *       {@link #CgShaderImpl(String, String, CgVertexFormat)} constructor.</li>
 *   <li><strong>Inline-source</strong> — compiles directly from raw GLSL strings
 *       supplied at construction time, without any resource loading. Created by
 *       {@link CgShaderFactory#fromSource} via the
 *       {@link #CgShaderImpl(String, String, CgVertexFormat)} constructor.
 *       Inline shaders have no cache key ({@link #getCacheKey()} returns
 *       {@code null}) and are not tracked by the shader manager cache.</li>
 * </ol>
 *
 * <p><strong>Lifecycle model:</strong></p>
 * <ul>
 *   <li>Compilation is deferred until first {@link #bind()} call (lazy compilation).</li>
 *   <li>Failures are logged but never thrown; {@link #isCompiled()} becomes false.</li>
 *   <li>{@link #bind()} on an uncompiled shader is a pure no-op (no GL mutations).</li>
 *   <li>{@link #markDirty()} schedules recompilation on next bind without immediate action.</li>
 * </ul>
 *
 * <p><strong>Uniform caching:</strong></p>
 * <p>Uniform location lookups are cached per compiled program instance and
 * automatically cleared on recompile/delete. This minimizes
 * {@code glGetUniformLocation} calls which can be expensive.</p>
 *
 * <p><strong>Binding semantics:</strong></p>
 * <p>Bindings accumulated in {@link #bindings()} are applied on every bind
 * operation. This decouples uniform configuration from the bind/unbind lifecycle.</p>
 *
 * <p><strong>Thread safety:</strong></p>
 * <p>Not thread-safe. All operations must occur on the render thread only.</p>
 *
 * <p>Package-private — created exclusively by {@link CgShaderManagerImpl}.</p>
 *
 * @see CgShaderManager
 * @see CgShaderFactory
 */
 public class CgShaderImpl implements CgShader {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");
    
    /**
     * Deterministic cache key (vertex location, fragment location, defines).
     * Used to identify this shader uniquely in the shader manager cache.
     * {@code null} for inline-source shaders, which are not cached.
     */
    private final CgShaderCacheKey cacheKey;
    
    /**
     * Asset path of the vertex shader source file.
     * {@code null} when created via the inline-source constructor; in that
     * case {@link #vertexSource} holds the raw GLSL instead.
     */
    private final String vertexPath;
    
    /**
     * Asset path of the fragment shader source file.
     * {@code null} when created via the inline-source constructor; in that
     * case {@link #fragmentSource} holds the raw GLSL instead.
     */
    private final String fragmentPath;

    /**
     * Inline vertex shader source. Non-null when created via the inline
     * constructor or after {@link #setSource} is called. Null for path-based
     * shaders that have not had their source overridden.
     */
    private String vertexSource;

    /**
     * Inline fragment shader source. Non-null when created via the inline
     * constructor or after {@link #setSource} is called. Null for path-based
     * shaders that have not had their source overridden.
     */
    private String fragmentSource;

    /**
     * Attribute format for the VAO that feeds this shader
     */
    private final CgVertexFormat format;
    /**
     * Preprocessor from constructor (legacy defines path). Used as fallback
     * when no explicit preprocessor has been attached via {@link #preprocess}.
     */
    private final CgShaderPreprocessor preprocessor;

    /**
     * Preprocessor attached post-construction via {@link #preprocess}.
     * When non-null, takes precedence over {@link #preprocessor}.
     */
    private CgShaderPreprocessor activePreprocessor;

    /**
     * The underlying compiled OpenGL shader program, or null if not yet compiled
     * or if compilation failed. Always check {@link #compiled} before using.
     */
    private CgShaderProgram program;
    
    /**
     * True if {@link #program} is a valid, successfully compiled program.
     * False if not yet compiled, or if the last compilation failed.
     * When false, {@link #bind()} is a pure no-op.
     */
    private boolean compiled;
    
    /**
     * True if recompilation is needed. Set by {@link #markDirty()} and cleared
     * after {@link #recompile()} completes. When true, the next {@link #bind()}
     * will trigger recompilation before binding.
     */
    private boolean dirty;

    /**
     * The error message from the last failed compilation attempt, or {@code null}
     * if the last compile succeeded or has not been attempted yet.
     */
    private String lastCompileError;

    /**
     * Cache of uniform name -> location mappings for the current {@link #program}.
     * Automatically cleared when the program is recompiled or deleted to prevent
     * stale location references.
     */
    private final HashMap<String, Integer> uniformLocationCache = new HashMap<String, Integer>();
    
    /**
     * Persistent shader bindings that accumulate across frames.
     * Bindings in this container are applied on every {@link #bind()} / {@link #bindScoped()} call
     * and persist until explicitly modified or cleared. Use this for per-shader state that
     * changes infrequently (e.g., textures, shader parameters loaded once at init).
     */
    private final CgShaderBindings bindings = new CgShaderBindingsImpl();
    
    /**
     * Ephemeral (temporary) bindings that are applied once per bind/scope and then auto-cleared.
     * Use {@link #applyBindings(java.util.function.Consumer)} to populate this container
     * for a single frame/draw call. Bindings are applied after persistent bindings and system uniforms,
     * then automatically cleared so they never leak to subsequent frames.
     */
    private final CgShaderBindings ephemeralBindings = new CgShaderBindingsImpl();

    /** Path-based constructor used by {@link CgShaderManagerImpl}. */
    public CgShaderImpl(String vertexPath, String fragmentPath, CgVertexFormat format) {
        this.vertexPath   = Objects.requireNonNull(vertexPath,   "vertexPath must not be null");
        this.fragmentPath = Objects.requireNonNull(fragmentPath, "fragmentPath must not be null");
        this.vertexSource   = null;
        this.fragmentSource = null;
        this.format       = format;
        this.preprocessor = new CgShaderPreprocessor();
        this.cacheKey     = new CgShaderCacheKey(vertexPath, fragmentPath);
        this.program      = null;
        this.compiled     = false;
        this.dirty        = true;
    }

    /**
     * Creates an inline-source shader that compiles from raw GLSL strings directly.
     * {@link #getCacheKey()} returns {@code null}; not tracked in the manager cache.
     */
    public static CgShaderImpl fromSource(String vertexSource, String fragmentSource, CgVertexFormat format) {
        return new CgShaderImpl(null, null, vertexSource, fragmentSource, format);
    }

    /** Unified private constructor. Exactly one of (paths, sources) pair is non-null. */
    private CgShaderImpl(String vertexPath, String fragmentPath, 
                         String vertexSource, String fragmentSource,
                         CgVertexFormat format) {
        this.vertexPath     = vertexPath;
        this.fragmentPath   = fragmentPath;
        this.vertexSource   = vertexSource;
        this.fragmentSource = fragmentSource;
        this.format         = format;
        this.preprocessor   = new CgShaderPreprocessor();
        this.cacheKey       = (vertexPath != null)
                ? new CgShaderCacheKey(vertexPath, fragmentPath)
                : null;
        this.program  = null;
        this.compiled = false;
        this.dirty    = true;
    }

    @Override
    public CgShaderCacheKey getCacheKey() {
        return cacheKey;
    }

    @Override
    public boolean isCompiled() {
        return compiled;
    }

    @Override
    public String getLastCompileError() {
        return lastCompileError;
    }

    @Override
    public List<CgActiveUniform> getActiveUniforms() {
        if (!compiled || program == null) return Collections.emptyList();
        return program.getActiveUniforms();
    }

    @Override
    public CgShaderProgram getProgram() {
        return program;
    }

    @Override
    public int getUniformLocation(String name) {
        if (name == null) throw new IllegalArgumentException("Uniform name must not be null");
        if (!compiled) return -1;
        
        Integer cached = uniformLocationCache.get(name);
        if (cached != null) return cached.intValue();
        
        int loc = program.getUniformLocation(name);
        uniformLocationCache.put(name, Integer.valueOf(loc));
        return loc;
    }

    @Override
    public CgShader applyBindings(Consumer<CgShaderBindings> consumer) {
        consumer.accept(ephemeralBindings);
        return this;
    }

    @Override
    public CgShaderBindings bindings() {
        return bindings;
    }

    @Override
    public void bind() {
        if (dirty) recompile();
        
        if (compiled) {
            program.bind();
            applyAllBindings();
        }
    }

    @Override
    public void unbind() {
        if (compiled) {
            program.unbind();
        }
    }

    @Override
    public CgGlScope bindScoped() {
        if (dirty) recompile();

        if (!compiled) return CgGlScope.NOOP_SCOPE;

        CgGlScope saved = CgGlState.saveProgram(); // capture BEFORE binding ours
        program.bind();
        applyAllBindings();
        return saved;
    }

    @Override
    public CgGlScope bindScoped(CgGlSlot... slots) {
        if (slots == null || slots.length == 0) return bindScoped();
        if (dirty) recompile();

        if (!compiled) return CgGlScope.NOOP_SCOPE;

        CgGlScope saved = CgGlState.save(slots); // capture BEFORE binding
        program.bind();
        applyAllBindings();
        return saved;
    }

    @Override
    public CgShader setSource(String vertexSource, String fragmentSource) {
        this.vertexSource = Objects.requireNonNull(vertexSource, "vertexSource must not be null");
        this.fragmentSource = Objects.requireNonNull(fragmentSource, "fragmentSource must not be null");
        markDirty();
        return this;
    }

    @Override
    public CgShader preprocess(CgShaderPreprocessor pp) {
        this.activePreprocessor = pp;
        markDirty();
        return this;
    }

    @Override
    public void markDirty() {
        this.dirty = true;
    }

    @Override
    public void delete() {
        if (program != null && !program.isDeleted()) 
            program.delete();
        
        program = null;
        compiled = false;
        dirty = false;
        uniformLocationCache.clear();
    }

    private void applyAllBindings() {
        if (!compiled) return;
        
        CgSystemUniformRegistry.getInstance().applyAll(this);
        bindings.apply(this);
        ephemeralBindings.apply(this);
        ephemeralBindings.clear();
    }

    @Override
    public void recompile() {
        this.dirty = false;

        String vertex;
        String fragment;

        if (vertexSource != null) {
            vertex = vertexSource;
            fragment = fragmentSource;
        } else {
            try {
                vertex = CgIO.loadSource(vertexPath);
                fragment = CgIO.loadSource(fragmentPath);
            } catch (Exception e) {
                LOGGER.error("Failed to load shader sources for {}", cacheKey, e);
                lastCompileError = e.getMessage();
                compiled = false;
                uniformLocationCache.clear();
                return;
            }
        }

        CgShaderPreprocessor pp = (activePreprocessor != null) ? activePreprocessor : preprocessor;
        String vertPath = (vertexPath != null) ? vertexPath : "";
        String fragPath = (fragmentPath != null) ? fragmentPath : "";
        vertex = pp.process(vertex, vertPath);
        fragment = pp.process(fragment, fragPath);

        try {
            if (program != null && !program.isDeleted()) {
                program.relink(vertex, fragment, format);
            } else {
                CgShaderProgram newProgram = CgShaderFactory.compile(vertex, fragment, format);
                if (program != null && !program.isDeleted()) program.delete();
                program = newProgram;
            }
        } catch (Exception e) {
            if (cacheKey != null) {
                LOGGER.error("Failed to compile shader {}", cacheKey, e);
            } else {
                LOGGER.error("Failed to compile inline shader: {}", e.getMessage());
            }
            lastCompileError = e.getMessage();
            compiled = false;
            uniformLocationCache.clear();
            return;
        }

        compiled = true;
        lastCompileError = null;
        uniformLocationCache.clear();
    }

}
