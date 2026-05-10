package io.github.somehussar.crystalgraphics.api.material;

import io.github.somehussar.crystalgraphics.api.CgBindingPoints;
import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.buffer.CgBufferFormat;
import io.github.somehussar.crystalgraphics.api.buffer.CgGpuType;
import io.github.somehussar.crystalgraphics.api.shader.CgShader;
import io.github.somehussar.crystalgraphics.api.shader.CgShaderBindings;
import io.github.somehussar.crystalgraphics.api.shader.CgPreprocessorException;
import io.github.somehussar.crystalgraphics.api.shader.CgShaderPreprocessor;
import io.github.somehussar.crystalgraphics.api.state.CgGlSlot;
import io.github.somehussar.crystalgraphics.api.state.CgRenderState;
import io.github.somehussar.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import io.github.somehussar.crystalgraphics.gl.buffer.shader.CgUniformBuffer;
import io.github.somehussar.crystalgraphics.gl.material.*;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.gl.material.parse.*;
import io.github.somehussar.crystalgraphics.gl.shader.CgShaderFactory;
import io.github.somehussar.crystalgraphics.gl.state.CgGlScope;
import io.github.somehussar.crystalgraphics.gl.state.CgGlState;
import io.github.somehussar.crystalgraphics.util.io.CgIO;
import lombok.Getter;
import lombok.Setter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * User-facing material that wraps a compiled {@code .shader} file.
 *
 * <p>A {@code CgMaterial} encapsulates a linked GLSL shader program,
 * property uniforms (declared in the {@code Properties { }} block), and the
 * {@code bind()} / {@code unbind()} lifecycle for draw calls. One material
 * instance is shared across both non-instanced ({@code drawDirect()}) and
 * instanced ({@code drawInstanced(N)}) draw calls — no modification, no
 * variants, no {@code #ifdef INSTANCED}.</p>
 *
 * <h3>Load pattern</h3>
 * <pre>{@code
 * CgMaterial material = CgMaterial.load("mymod:shaders/terrain.shader");
 * material.applyProperties(b -> b.vec4("_Color", 1f, 0f, 0f, 1f));
 * }</pre>
 *
 * <h3>Per-frame bind pattern</h3>
 * <pre>{@code
 * // pipeline.beginFrame() uploads frame UBO automatically.
 * // Write per-object data:
 * objectBuffer.writeSingle(modelMatrix);
 * material.bind();
 * mesh.drawDirect();
 * material.unbind();
 * }</pre>
 *
 * <h3>Hot-reload</h3>
 * <p>{@link #markDirty()} marks the material dirty; the full load→parse→compile
 * pipeline runs lazily on the next {@link #bind()} call via {@link #recompile()}.
 * The backing {@link CgShader} reference is stable across reloads — only the
 * underlying GL program ID changes.</p>
 *
 * <h3>Ownership</h3>
 * <p>The material owns its backing {@link CgShader}. Call {@link #delete()} to free it.
 * {@link CgUniformBuffer} and {@link CgShaderBuffer} passed to {@link #bind} are
 * <em>caller-owned</em> — this class never deletes them.</p>
 *
 * <h3>Scope guardrails</h3>
 * <ul>
 *   <li>No render-state management — no blend, depth, cull setters.</li>
 *   <li>No shader variants — no DEPTH/SHADOW pass in this MVP.</li>
 * </ul>
 */
public final class CgMaterial {

    private static final Logger LOGGER = LogManager.getLogger("CgMaterial");
    
    /** Block name of matPropsUbo**/
    public static final String MATERIAL_PROPERTIES_BLOCK = "CgMaterialBlock";
    
    /** The compiled shader backing this material. Owned by this instance. Null until first {@link #recompile()}. */
    private CgShader shader;

    /** Live property objects for this material, containing current values. Set/updated by {@link #recompile()}. */
    private List<CgMaterialProperty> properties;

    /** Whether {@link #delete()} has been called. */
    private boolean deleted;

    /**
     * Whether this material needs a full recompile on the next {@link #bind()}.
     * Set by {@link #markDirty()}; cleared at the start of {@link #recompile()}.
     */
    private boolean dirty;

    /** Render state applied during {@link #bind()} and saved/restored on unbind. */
    private CgRenderState renderState = CgRenderState.DEFAULT;

    /** Numeric render queue priority — 2000 by default (Geometry queue). 
     * -- GETTER --
     * Returns the numeric render queue priority for this material (default: 2000 = Geometry). 
     */
    @Getter
    private int renderQueue = 2000;

    /** GL state snapshot saved on bind and restored on unbind via {@link CgGlScope#close()}. */
    private CgGlScope stateScope = null;

    /**
     * Partitioned view of all properties for this material. Null until first recompile.
     * Never reallocated after creation — {@link CgMaterialProperties#rebuild} updates it in-place.
     */
    private CgMaterialProperties propStore = null;

    /**
     * UBO backing the non-sampler properties (CgMaterialBlock).
     * Created on first recompile with UBO props, then reused (via {@code resetFormat}) forever —
     * never deleted or nulled on subsequent recompiles.
     */
    private CgUniformBuffer matPropsUbo = null;

    /** Whether property values have changed since the last GPU upload. */
    private boolean materialPropsDirty = true;

    /** Optional next material pass in a multi-pass draw chain. 
     * -- GETTER --
     * Returns the next material pass in the draw chain, or 
     *  if none. 
     */
    @Getter
    private CgMaterial nextPass = null;

    /**
     * User-defined auxiliary buffers (SSBO/TBO and UBO) attached to this material for GLSL
     * auto-injection. SSBO/TBO entries have a non-null {@code macroName}; UBO entries have
     * {@code macroName == null} (see {@link CgAttachedBuffer#isUbo()}).
     * These are NOT engine pipeline buffers — see {@link CgAttachedBuffer} for details.
     */
    private final List<CgAttachedBuffer> attachedBuffers = new ArrayList<>();

    /**
     * Resource path to the {@code .shader} file.
     * {@code null} for programmatic or shader-graph materials that have no backing file.
     * Set post-construction via {@link #setResourcePath(String)} in {@link #create(String)}.
     */
    @Setter
    private String resourcePath;

    // ── Constructor (private — use load()) ───────────────────────────────────

    private CgMaterial() {}

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Returns the cached material for {@code resourcePath}, loading it on first access.
     * Delegates to {@link CgMaterialRegistry#get()} which caches by resource path.
     *
     * @param resourcePath resource path to the {@code .shader} file,
     *                     e.g. {@code "mymod:shaders/terrain.shader"}
     * @return a ready-to-use {@code CgMaterial}; the same instance is returned on repeated calls
     */
    public static CgMaterial load(String resourcePath) {
        return CgMaterialRegistry.get().getOrCreate(resourcePath);
    }

    /**
     * Returns the cached material for {@code key.name()}, loading it on first access.
     * Delegates to {@link CgMaterialRegistry#get()}.
     *
     * @param key typed material key
     * @return a ready-to-use {@code CgMaterial}; the same instance is returned for the same key
     */
    public static CgMaterial load(CgMaterialKey key) {
        return CgMaterialRegistry.get().getOrCreate(key);
    }

    /**
     * Creates a new {@code CgMaterial} from a {@code .shader} file.
     * Called only by {@link CgMaterialRegistry}; external callers use {@link #load}.
     *
     * <p>Compilation is <strong>deferred</strong> to the first {@link #bind()} call so that
     * {@link #attach(CgShaderBuffer, String) attach()}
     * calls made between {@code load()} and {@code bind()} are included in the compiled GLSL.
     * Any exceptions that were previously thrown here (compile/link failures,
     * {@link IllegalStateException}, {@link UnsupportedOperationException}) will now surface
     * from the first {@code bind()} invocation instead.</p>
     *
     * <p>Typical usage:</p>
     * <pre>{@code
     * CgMaterial mat = CgMaterial.load("mymod:shaders/foo.shader");
     * mat.attach(myBuffer, "MY_BUF");   // attach before first bind()
     * // ... later, on each frame:
     * mat.bind();                        // lazy compile happens here on the first call
     * mesh.drawInstanced(N);
     * mat.unbind();
     * }</pre>
     *
     * @throws IllegalArgumentException  if the source cannot be loaded or is empty (from {@code bind()})
     * @throws CgShaderParseException    on parse error (from {@code bind()})
     * @throws IllegalStateException     if compilation or linking fails (from {@code bind()})
     * @throws UnsupportedOperationException if the hardware does not support GL 3.3+ (from {@code bind()})
     */
    static CgMaterial create(String resourcePath) {
        CgMaterial mat = new CgMaterial();
        mat.setResourcePath(resourcePath);
        mat.markDirty();
        return mat;
    }

    static CgMaterial forTest(CgRenderState renderState, int renderQueue) {
        CgMaterial m = new CgMaterial();
        m.renderState = renderState;
        m.renderQueue = renderQueue;
        return m;
    }

    // ── Buffer attachment API ─────────────────────────────────────────────────

    /**
     * Attaches a user-defined auxiliary buffer to this material.
     *
     * <p>On the next compile, the buffer's GLSL struct and either an SSBO block or a TBO
     * sampler + fetch function are injected into both vertex and fragment shader source.
     * Access it in GLSL via {@code macroName(n).fieldName}.</p>
     *
     * <p><strong>What this is for</strong>: per-material auxiliary data — font glyph metrics,
     * light tables, bindless texture handle pools ({@code uvec2} handles), tile properties,
     * animation curves, etc. Any structured dataset your shader needs beyond what the engine's
     * {@code cg_env.glsl} provides.</p>
     *
     * <p><strong>What NOT to pass</strong>: engine pipeline buffers
     * ({@code CgMaterialPipeline.objectBuffer()}, etc.). Those are wired automatically by the
     * engine and declared in {@code cg_env.glsl}. Passing them here causes duplicate declarations.</p>
     *
     * <p><strong>Ownership warning</strong>: {@code CgMaterial.load(path)} returns a
     * <em>shared cached instance</em> from {@code CgMaterialRegistry}. Do NOT call
     * {@code attach()} on a loaded material that other systems may hold. Either create the
     * material outside the registry, or ensure you are the sole owner before attaching.</p>
     *
     * <p><strong>TBO-path constraints</strong> (validated at compile time, not here):
     * <ul>
     *   <li>All field types must satisfy {@link CgGpuType#isTboCompatible()} — float-family only.
     *       INT, UINT, BOOL, IVEC*, UVEC*, INT64, UINT64 are not accepted and will throw an exception.</li>
     *   <li>Format stride must be a multiple of 16 bytes (one TBO texel = 16 bytes).</li>
     * </ul>
     *
     * <p><strong>Runtime binding</strong>: per-context GL binding ({@code glBindBufferBase} /
     * {@code glActiveTexture+glBindTexture}) is <em>your responsibility</em>. Call
     * {@code buffer.bind()} before your draw call. This method only registers the buffer for
     * GLSL generation and per-program block/sampler wiring.</p>
     *
     * @param buffer    the buffer to attach; format must use {@code STD430}
     * @param macroName uppercase GLSL-style identifier function, e.g. {@code "FONT_METRICS"};
     *                  used in shader as {@code macroName(n).fieldName}
     * @return {@code this} for fluent chaining
     * @apiNote Never throws — validation failures are logged as warnings and the call becomes a no-op.
     */
    public CgMaterial attach(CgShaderBuffer buffer, String macroName) {
        CgAttachedBuffer ab;
        try {
            ab = CgAttachedBuffer.of(buffer, macroName);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("attach() skipped for macro '{}': {}", macroName, e.getMessage());
            return this;
        }
        for (CgAttachedBuffer existing : attachedBuffers) {
            if (existing.isUbo()) continue;
            if (existing.getMacroName().equals(macroName)) {
                LOGGER.warn("attach() skipped: macro name \"{}\" is already attached to this material.", macroName);
                return this;
            }
            if (existing.getStructName().equals(ab.getStructName())) {
                LOGGER.warn("attach() skipped: GLSL struct name collision for \"{}\" (already in use by macro \"{}\").",
                        ab.getStructName(), existing.getMacroName());
                return this;
            }
        }
        attachedBuffers.add(ab);
        markDirty();
        return this;
    }

    /**
     * Detaches the SSBO/TBO buffer registered under {@code macroName}; no-op if not found.
     *
     * @return {@code this} for fluent chaining
     */
    public CgMaterial detach(String macroName) {
        if (attachedBuffers.removeIf(ab -> ab.getMacroName().equals(macroName))) {
            markDirty();
        }
        return this;
    }

    /**
     * Detaches the SSBO/TBO buffer by reference; no-op if not attached.
     *
     * @return {@code this} for fluent chaining
     */
    public CgMaterial detach(CgShaderBuffer buffer) {
        if (attachedBuffers.removeIf(ab -> ab.getBuffer() == buffer)) {
            markDirty();
        }
        return this;
    }

    /**
     * Attaches a UBO to this material for GLSL flat-block auto-injection.
     *
     * <p>On the next compile, a {@code layout(std140) uniform BlockName { ... };} block is
     * injected into both vertex and fragment shader source. All fields land in direct scope —
     * write {@code fieldName} in your shader, not {@code BlockName.fieldName}.</p>
     *
     * <p>Use this for single-instance uniform data: scene parameters, per-pass constants,
     * camera extras, light properties — anything that is the same for all instances in a draw.</p>
     *
     * <p>For per-instance indexed data, use {@link #attach(CgShaderBuffer, String)} instead.</p>
     *
     * <p><strong>Ownership warning</strong>: see {@link #attach(CgShaderBuffer, String)}.
     * Same rule applies here.</p>
     *
     * <p><strong>Runtime binding</strong>: call {@code buffer.bind()} before your draw call.
     * This method only registers the buffer for GLSL generation and block-index wiring.</p>
     *
     * @param buffer UBO to attach; format must use {@link CgBufferFormat.MemoryLayout#STD140}
     * @return {@code this} for fluent chaining
     * @apiNote Never throws — validation failures are logged as warnings and the call becomes a no-op.
     */
    public CgMaterial attach(CgUniformBuffer buffer) {
        CgAttachedBuffer ab;
        try {
            ab = CgAttachedBuffer.of(buffer);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("attach(CgUniformBuffer) skipped for '{}': {}", buffer == null ? "null" : buffer.getName(), e.getMessage());
            return this;
        }
        for (CgAttachedBuffer existing : attachedBuffers) {
            if (existing.isUbo() && existing.getBuffer().getName().equals(buffer.getName())) {
                LOGGER.warn("attach(CgUniformBuffer) skipped: UBO block name \"{}\" is already attached.",
                        buffer.getName());
                return this;
            }
        }
        attachedBuffers.add(ab);
        markDirty();
        return this;
    }

    /**
     * Detaches the UBO with the given block name; no-op if not found.
     *
     * @return {@code this} for fluent chaining
     */
    public CgMaterial detachUbo(String blockName) {
        if (attachedBuffers.removeIf(ab -> ab.isUbo() && ab.getBuffer().getName().equals(blockName))) {
            markDirty();
        }
        return this;
    }

    /**
     * Detaches the UBO by reference; no-op if not attached.
     *
     * @return {@code this} for fluent chaining
     */
    public CgMaterial detach(CgUniformBuffer buffer) {
        if (attachedBuffers.removeIf(ab -> ab.isUbo() && ab.getBuffer() == buffer)) {
            markDirty();
        }
        return this;
    }

    // ── Property bindings ─────────────────────────────────────────────────────

    /**
     * Sets material property values by name. Only Properties block declarations are accepted.
     * Matrix uniforms, raw buffers, and other non-property operations are not supported here;
     * use {@code shader.bindings()} for those.
     *
     * <pre>{@code
     * material.applyProperties(b -> {
     *     b.set1f("_Alpha", 0.5f);
     *     b.vec4("_Color", 1f, 0f, 0f, 1f);
     * });
     * }</pre>
     *
     * @param consumer receives a property-routing bindings adapter; must not be null
     * @return this for chaining
     */
    public CgMaterial applyProperties(Consumer<CgShaderBindings> consumer) {
        checkNotDeleted();
        if (propStore == null) return this;
        consumer.accept(propStore);
        materialPropsDirty = true;
        return this;
    }

    // ── Draw-time API ─────────────────────────────────────────────────────────

    /**
     * Binds this material for rendering. Must be called after
     * {@link CgMaterialPipeline#beginFrame} and after writing object records into
     * {@link CgMaterialPipeline#objectBuffer()}.
     *
     * <h3>Operations performed in order</h3>
     * <ol>
     *   <li>If {@link #dirty}, calls {@link #recompile()} to hot-reload the shader.</li>
     *   <li>Saves blend, depth, cull, stencil, alpha-test, and color-mask GL state.</li>
     *   <li>Uploads material property UBO if values are dirty.</li>
     *   <li>Binds material property UBO (if present).</li>
     *   <li>Applies sampler properties to the shader.</li>
     *   <li>Applies {@link CgRenderState} GL state.</li>
     *   <li>{@code shader.bind()} — activates the GL program.</li>
     * </ol>
     *
     * <p>The pipeline's object buffer is bound once per frame in {@link CgMaterialPipeline#beginFrame()},
     * not per material.</p>
     */
    public void bind() {
        checkNotDeleted();

        if (dirty) recompile();
        if (shader == null) return;

        stateScope = CgGlState.save(
                CgGlSlot.BLEND, CgGlSlot.DEPTH, CgGlSlot.CULL,
                CgGlSlot.STENCIL, CgGlSlot.ALPHA_TEST, CgGlSlot.COLOR_MASK);

        if (materialPropsDirty && matPropsUbo != null) {
            propStore.writeUboProps(matPropsUbo.writer());
            matPropsUbo.endRecord();
            matPropsUbo.upload();
            materialPropsDirty = false;
        }

        if (matPropsUbo != null) matPropsUbo.bind();

        if (propStore != null && propStore.hasSamplerProps()) 
            shader.applyBindings(b -> propStore.applySamplerProps(b));

        renderState.apply();
        shader.bind();
    }

    /**
     * Unbinds shader and restores all GL state saved during {@link #bind()}.
     *
     * <p>Must be called after the draw call sequence to leave GL state clean.
     * State is restored via the saved {@link CgGlScope} — never by calling
     * {@code renderState.clear()}, which would set state incorrectly.</p>
     */
    public void unbind() {
        if (deleted) return;
        if (shader != null) shader.unbind();
        if (matPropsUbo != null) matPropsUbo.unbind();
        if (stateScope != null) {
            stateScope.close();
            stateScope = null;
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    /**
     * Returns the pipeline's shared per-object SSBO/TBO.
     * Equivalent to {@code CgMaterialPipeline.getInstance().objectBuffer()}.
     *
     * <p>Each object record is exactly {@link CgMaterialPipeline#OBJECT_FORMAT} = 48 floats.
     * Use named writes — unwritten fields are auto-zeroed per record:</p>
     * <pre>{@code
     * CgShaderBuffer buf = material.objectBuffer();
     * CgBufferWriter w = buf.beginWrite(N);
     * for (int i = 0; i < N; i++) {
     *     Matrix4f model  = ...;
     *     Matrix4f normal = new Matrix4f(model).invert().transpose();
     *     w.beginRecord()
     *      .mat4("modelMatrix", model)
     *      .mat4("normalMatrix", normal)
     *      .vec4("custom0", r, g, b, a);   // custom1-3 auto-zeroed
     *     buf.endRecord();
     * }
     * buf.endWrite();
     * material.bind();
     * mesh.drawInstanced(N);
     * material.unbind();
     * }</pre>
     *
     * <p>The buffer auto-grows when {@code N} exceeds its current capacity.
     * Each {@code beginWrite/endWrite} cycle overwrites the previous content —
     * write a fresh batch every frame.</p>
     *
     * @return the pipeline's object buffer; never {@code null}
     * @throws IllegalStateException if {@link CgMaterialPipeline} has not been initialized
     */
    public CgShaderBuffer objectBuffer() {
        return CgMaterialPipeline.getInstance().objectBuffer();
    }

    /**
     * Returns the material properties UBO, or {@code null} for sampler-only shaders.
     * Engine-owned — do not delete.
     */
    public CgUniformBuffer materialBuffer() {
        return matPropsUbo;
    }

    /**
     * Returns the backing {@link CgShader} for advanced use (e.g. wiring a UBO block
     * after initial setup).
     *
     * @return the compiled shader handle; never {@code null}
     */
    public CgShader getShader() {
        checkNotDeleted();
        return shader;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Marks this material dirty so it will be fully recompiled from {@link #resourcePath}
     * on the next {@link #bind()} call. Called by {@link CgMaterialRegistry#reloadAll()}
     * during hot-reload (F3+T).
     *
     * <p>If the material has already been deleted, this is a no-op.</p>
     */
    public void markDirty() {
        if (!deleted) dirty = true;
    }

    /**
     * Compiles (or recompiles) this material from {@link #resourcePath}.
     * Serves as the single compilation path for both initial load and hot-reload.
     *
     * <p>On first call ({@code shader == null}): throws on any failure — never returns
     * with a broken state. On subsequent calls (hot-reload): logs errors and keeps the
     * old program running on failure.</p>
     *
     * <p>No-op when {@code resourcePath} is null (shader-graph / programmatic materials).</p>
     */
    public void recompile() {
        dirty = false;
        if (resourcePath == null) return;

        boolean isFirst = (shader == null);

        String source;
        try {
            source = CgIO.loadSource(resourcePath);
        } catch (Exception e) {
            if (isFirst) throw new IllegalArgumentException("Could not load shader source from: " + resourcePath, e);
            LOGGER.error("Reload failed for '{}': could not load source — {}", resourcePath, e.getMessage());
            return;
        }
        if (source == null || source.isEmpty()) {
            if (isFirst) throw new IllegalArgumentException("Could not load shader source from: " + resourcePath);
            LOGGER.error("Reload failed for '{}': empty source", resourcePath);
            return;
        }

        CgParsedShader parsed;
        try {
            parsed = CgShaderParser.parse(source, resourcePath);
        } catch (CgShaderParseException e) {
            if (isFirst) throw e;
            LOGGER.error("Reload failed for '{}': parse error — {}", resourcePath, e.getMessage());
            return;
        }

        this.renderState = parsed.renderState();
        this.renderQueue = parsed.renderQueue();
        if (propStore == null) propStore = new CgMaterialProperties(parsed.properties());
        else propStore.rebuild(parsed.properties());
        


        CgCapabilities.ShaderBufferPath path = CgCapabilities.detect().shaderBufferPath();
        if (path == CgCapabilities.ShaderBufferPath.NONE) {
            if (isFirst) throw new UnsupportedOperationException("GL 3.3+ required for CrystalShader materials");
            LOGGER.error("Reload failed for '{}': GL 3.3+ not available", resourcePath);
            return;
        }

        if (propStore.hasUboProps()) {
            CgBufferFormat newFormat = propStore.buildUboFormat();
            if (matPropsUbo == null) 
                matPropsUbo = new CgUniformBuffer(MATERIAL_PROPERTIES_BLOCK, newFormat, CgBindingPoints.MATERIAL_PROPERTIES_UBO);
            else matPropsUbo.resetFormat(newFormat);
        }
        
        this.materialPropsDirty = true;

        CgMaterialShaderCompiler.CompiledSource compiled;
        try {
            compiled = CgMaterialShaderCompiler.compile(parsed, path, attachedBuffers, matPropsUbo);
        } catch (CgPreprocessorException e) {
            if (isFirst) throw e;
            LOGGER.error("Reload failed for '{}': buffer injection error — {}", resourcePath, e.getMessage());
            return;
        }
        String processedVert = new CgShaderPreprocessor().process(compiled.vertexSource(), resourcePath);
        String processedFrag = new CgShaderPreprocessor().process(compiled.fragmentSource(), resourcePath);

        if (System.getProperty("crystalgraphics.material.dumpGlsl") != null) {
            LOGGER.info("=== CgMaterial GLSL dump for '{}' ===", resourcePath);
            LOGGER.info("--- VERTEX ---\n{}", processedVert);
            LOGGER.info("--- FRAGMENT ---\n{}", processedFrag);
            LOGGER.info("=== end GLSL dump ===");
        }

        if (isFirst) {
            shader = CgShaderFactory.fromSource(processedVert, processedFrag, CgVertexFormat.SPATIAL);
            if (!shader.isCompiled()) {
                String err = shader.getLastCompileError();
                shader.delete();
                shader = null;
                LOGGER.error("CgMaterial.create() failed for '{}': {}", resourcePath, err);
                return;
            }
        } else {
            shader.setSource(processedVert, processedFrag);
            shader.recompile();
            if (!shader.isCompiled()) {
                LOGGER.error("Reload failed for '{}': compile error — {}", resourcePath, shader.getLastCompileError());
                return;
            }
        }

        properties = new ArrayList<>(parsed.properties());

        // Wire per-program block indices after each link — does NOT bind to GL context.
        // glUniform1i (TBO path) requires an active program, so bind shader around all wiring calls.
        CgMaterialPipeline pipeline = CgMaterialPipeline.getInstance();
        shader.bind();
        
        pipeline.frameBuffer().wireShader(shader);   // glUniformBlockBinding for CgFrameBlock
        pipeline.objectBuffer().wireShader(shader);  // glShaderStorageBlockBinding (SSBO) or glUniform1i (TBO)
        if(matPropsUbo != null) matPropsUbo.wireShader(shader);
        
        for (CgAttachedBuffer ab : attachedBuffers) 
            ab.getBuffer().wireShader(shader);
        
        shader.unbind();

        if (!isFirst) {
            LOGGER.info("Reloaded '{}'", resourcePath);
        }
    }

    /**
     * Frees the backing shader program and material properties UBO.
     * After this call, the material is unusable.
     *
     * <p>Idempotent — subsequent calls are no-ops.</p>
     */
    public void delete() {
        if (!deleted) {
            deleted = true;
            if (shader != null) {
                shader.delete();
                shader = null;
            }
            if (matPropsUbo != null) {
                matPropsUbo.delete();
                matPropsUbo = null;
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void checkNotDeleted() {
        if (deleted) throw new IllegalStateException("CgMaterial has been deleted");
    }

    // ── Multi-pass draw chain ─────────────────────────────────────────────────

    /**
     * Sets the next material pass in a multi-pass draw chain.
     * Cycles are detected eagerly; a cyclic chain throws {@link IllegalStateException}.
     *
     * @param next the next pass material, or {@code null} to clear the chain
     * @return {@code this} for chaining
     */
    public CgMaterial setNextPass(CgMaterial next) {
        CgMaterial cursor = next;
        while (cursor != null) {
            if (cursor == this) throw new IllegalStateException("Cyclic nextPass chain detected, nextPass was set as this");
            cursor = cursor.nextPass;
        }
        this.nextPass = next;
        return this;
    }

    /**
     * Executes {@code drawCommand} once per pass in this material's draw chain,
     * binding each pass before the draw and unbinding it after.
     *
     * @param drawCommand the draw logic to invoke for each pass
     */
    public void drawChain(Runnable drawCommand) {
        CgMaterial pass = this;
        while (pass != null) {
            pass.bind();
            drawCommand.run();
            pass.unbind();
            pass = pass.nextPass;
        }
    }
}
