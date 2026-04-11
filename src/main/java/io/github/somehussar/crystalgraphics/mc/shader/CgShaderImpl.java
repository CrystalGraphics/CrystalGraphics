package io.github.somehussar.crystalgraphics.mc.shader;

import io.github.somehussar.crystalgraphics.api.shader.*;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.gl.CrossApiTransition;
import io.github.somehussar.crystalgraphics.gl.shader.CgShaderFactory;
import io.github.somehussar.crystalgraphics.gl.state.CallFamily;
import io.github.somehussar.crystalgraphics.gl.state.CgStateBoundary;
import io.github.somehussar.crystalgraphics.gl.state.CgStateSnapshot;
import io.github.somehussar.crystalgraphics.gl.state.GLStateMirror;
import io.github.somehussar.crystalgraphics.util.io.CgIO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Concrete implementation of {@link CgShader} that loads GLSL sources
 * from Minecraft resources, preprocesses them, and compiles via
 * {@link CgShaderFactory}.
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
final class CgShaderImpl implements CgShader {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    /**
     * A no-op scope that does nothing when closed. Returned when the shader
     * is not compiled or when closing has already occurred. This prevents
     * NPE and multiple-close issues.
     */
    private static final CgShaderScope NOOP_SCOPE = new CgShaderScope() {
        @Override
        public void close() {
        }
    };

    /**
     * Deterministic cache key (vertex location, fragment location, defines).
     * Used to identify this shader uniquely in the shader manager cache.
     */
    private final CgShaderCacheKey cacheKey;
    
    /**
     * Asset path of the vertex shader source file.
     * <p>Either an absolute classpath path (e.g. {@code "/assets/crystalgraphics/shader/bitmap_text.vert"})
     * or a domain-relative path (e.g.  {@code "crystalgraphics/shader/bitmap_text.vert", "shader/bitmap_text.vert"}) with an optional
     * resource domain stored separately.
     * Loaded lazily from Minecraft resources on first {@link #bind()} call.</p>
     */
    private final String vertexPath;
    
    /**
     * Asset path of the fragment shader source file.
     * <p>Same path conventions as {@link #vertexPath}.</p>
     */
    private final String fragmentPath;

    /**
     * Attribute format for the VAO that feeds this shader
     */
    private final CgVertexFormat format;
    /**
     * Preprocessor for GLSL sources (header text + #define injection).
     * Applied to loaded sources before compilation.
     */
    private final CgShaderPreprocessor preprocessor;

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

    CgShaderImpl(String vertexPath, String fragmentPath, CgVertexFormat format, Map<String, String> defines) {
        this.vertexPath = Objects.requireNonNull(vertexPath, "vertexPath must not be null");
        this.fragmentPath = Objects.requireNonNull(fragmentPath, "fragmentPath must not be null");
        this.format = format;
        this.preprocessor = new CgShaderPreprocessor(defines);
        this.cacheKey = new CgShaderCacheKey(vertexPath, fragmentPath, defines);
        this.program = null;
        this.compiled = false;
        this.dirty = true;
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
    public CgShaderScope bindScoped() {
        if (dirty) recompile();
        
        if (!compiled) return NOOP_SCOPE;

        final int previousProgram = queryCurrentProgram();
        final CallFamily previousFamily = GLStateMirror.getCurrentProgramFamily();
        program.bind();
        applyAllBindings();

        return new CgShaderScope() {
            private boolean closed;

            @Override
            public void close() {
                if (!closed) {
                    closed = true;
                    CrossApiTransition.bindProgram(previousProgram, previousFamily);
                }
            }
        };
    }

    @Override
    public CgShaderScope bindScoped(CgScopeRestoreOption... options) {
        if (options == null || options.length == 0 || !containsFullBindings(options)) return bindScoped();
        
        if (dirty) recompile();
        
        if (!compiled) return NOOP_SCOPE;
        

        final CgStateSnapshot snapshot = CgStateBoundary.save();
        program.bind();
        applyAllBindings();

        return new CgShaderScope() {
            private boolean closed;

            @Override
            public void close() {
                if (!closed) {
                    closed = true;
                    CgStateBoundary.restore(snapshot);
                }
            }
        };
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

    private static int queryCurrentProgram() {
        ContextCapabilities glCaps = GLContext.getCapabilities();
        if (glCaps.OpenGL20) return GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        if (glCaps.GL_ARB_shader_objects) return ARBShaderObjects.glGetHandleARB(ARBShaderObjects.GL_PROGRAM_OBJECT_ARB);
        
        return 0;
    }

    private static boolean containsFullBindings(CgScopeRestoreOption[] options) {
        for (CgScopeRestoreOption option : options) {
            if (option == CgScopeRestoreOption.FULL_BINDINGS) return true;
            
        }
        return false;
    }

    private void applyAllBindings() {
        if (!compiled) return;
        
        CgSystemUniformRegistry.getInstance().applyAll(this);
        bindings.apply(this);
        ephemeralBindings.apply(this);
        ephemeralBindings.clear();
    }

    protected void recompile() {
        this.dirty = false;

        String vertex;
        String fragment;
        try {
            vertex = CgIO.loadSource(vertexPath);
            fragment = CgIO.loadSource(fragmentPath);
        } catch (Exception e) {
            LOGGER.error("Failed to load shader sources for {}", cacheKey, e);
            clearProgram();
            return;
        }

        vertex = preprocessor.process(vertex);
        fragment = preprocessor.process(fragment);

        CgShaderProgram newProgram;
        try {
            newProgram = CgShaderFactory.compile(vertex, fragment, format);
        } catch (Exception e) {
            LOGGER.error("Failed to compile shader {}", cacheKey, e);
            clearProgram();
            return;
        }

        if (program != null && !program.isDeleted()) {
            program.delete();
        }
        program = newProgram;
        compiled = true;
        uniformLocationCache.clear();
    }

    private void clearProgram() {
        if (program != null && !program.isDeleted()) 
            program.delete();
        
        program = null;
        compiled = false;
        uniformLocationCache.clear();
    }
}
