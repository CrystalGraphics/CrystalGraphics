package io.github.somehussar.crystalgraphics.api.material;

import io.github.somehussar.crystalgraphics.api.CgBindingPoints;
import io.github.somehussar.crystalgraphics.api.buffer.CgBufferFormat;
import io.github.somehussar.crystalgraphics.api.buffer.CgGpuType;
import io.github.somehussar.crystalgraphics.api.shader.CgShader;
import io.github.somehussar.crystalgraphics.api.shader.CgShaderBindings;
import io.github.somehussar.crystalgraphics.api.state.CgGlSlot;
import io.github.somehussar.crystalgraphics.api.state.CgRenderState;
import io.github.somehussar.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import io.github.somehussar.crystalgraphics.gl.buffer.shader.CgUniformBuffer;
import io.github.somehussar.crystalgraphics.gl.material.CgMaterialProperties;
import io.github.somehussar.crystalgraphics.gl.material.CgMaterialProperty;
import io.github.somehussar.crystalgraphics.gl.material.CgMaterialShader;
import io.github.somehussar.crystalgraphics.gl.material.CgMaterialShaderRegistry;
import io.github.somehussar.crystalgraphics.gl.material.parse.CgParsedShader;
import io.github.somehussar.crystalgraphics.gl.state.CgGlScope;
import io.github.somehussar.crystalgraphics.gl.state.CgGlState;
import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * User-facing material handle backed by a shared {@link CgMaterialShader} asset.
 *
 * <p>A {@code CgMaterial} holds per-instance state only: property values ({@code propStore}),
 * the material properties UBO ({@code matPropsUbo}), and revision tracking. The compiled GL
 * programs, render state, and render queue all live on the shared {@link CgMaterialShader} asset,
 * which may be shared across multiple {@code CgMaterial} instances created from the same path.</p>
 *
 * <h3>Load pattern (cached — same instance per path)</h3>
 * <pre>{@code
 * CgMaterial material = CgMaterial.load("mymod:shaders/terrain.shader");
 * material.applyProperties(b -> b.vec4("_Color", 1f, 0f, 0f, 1f));
 * }</pre>
 *
 * <h3>Multiple instances for the same shader (independent property values)</h3>
 * <pre>{@code
 * CgMaterial mat1 = CgMaterial.newInstance("mymod:shaders/terrain.shader");
 * CgMaterial mat2 = CgMaterial.newInstance("mymod:shaders/terrain.shader");
 * // mat1 and mat2 share compiled GL programs but have independent property values.
 * // Both callers must call mat.delete() when done.
 * }</pre>
 *
 * <h3>Per-frame bind pattern</h3>
 * <pre>{@code
 * // pipeline.beginFrame() uploads frame UBO automatically.
 * objectBuffer.writeSingle(modelMatrix);
 * material.bind();
 * mesh.drawDirect();
 * material.unbind();
 * }</pre>
 *
 * <h3>Hot-reload</h3>
 * <p>Hot-reload is asset-level: {@link CgMaterialShader#markDirty()} is set on the shared asset,
 * which increments its {@link CgMaterialShader#getRevisionNumber()} on the next successful
 * compile. {@code CgMaterial.bind()} detects the revision change and calls
 * {@link #onShaderRecompiled()} to rebuild per-instance state.</p>
 *
 * <h3>Ownership</h3>
 * <p>The material owns its per-instance property UBO only. The backing GL programs are owned
 * by the shared {@link CgMaterialShader} and managed by {@link CgMaterialShaderRegistry}.
 * Call {@link #delete()} to free the property UBO. Registry-managed materials are deleted
 * by {@link CgMaterialRegistry#deleteAll()}.
 * {@link CgUniformBuffer} and {@link CgShaderBuffer} passed to {@link #attach} are
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

    /** Block name of the engine-managed material properties UBO. */
    public static final String MATERIAL_PROPERTIES_BLOCK = "CgMaterialBlock";

    // ── Shared shader asset (asset-level, not instance-level) ─────────────────

    /**
     * Shared shader asset for this material. Owns compilation, render state, render queue,
     * and attached buffers. {@code null} for materials created via {@link #forTest}.
     */
    private final CgMaterialShader cgMaterialShader;

    /**
     * Last shader revision seen by this material instance.
     * Starts at {@code -1}; set to {@link CgMaterialShader#getRevisionNumber()} after
     * each successful {@link #onShaderRecompiled()} call. A mismatch triggers a rebuild.
     */
    private int lastKnownRevision = -1;

    // ── Per-instance state ─────────────────────────────────────────────────────

    /** Whether {@link #delete()} has been called. */
    private boolean deleted;

    /**
     * Fallback render state for materials created via {@link #forTest(CgRenderState, int)}.
     * In real materials this is delegated to {@link CgMaterialShader#getRenderState()}.
     */
    private CgRenderState renderState = CgRenderState.DEFAULT;

    /**
     * Fallback render queue for materials created via {@link #forTest(CgRenderState, int)}.
     * In real materials this is delegated to {@link CgMaterialShader#getRenderQueue()}.
     * -- GETTER --
     * Returns the numeric render queue priority for this material (default: 2000 = Geometry).
     */
    @Getter
    private int renderQueue = 2000;

    /** GL state snapshot saved on bind and restored on unbind via {@link CgGlScope#close()}. */
    private CgGlScope stateScope = null;

    /**
     * Partitioned view of all properties for this material instance. Null until first
     * {@link #onShaderRecompiled()} call. Never reallocated after creation —
     * {@link CgMaterialProperties#rebuild} updates it in-place on hot-reload.
     */
    private CgMaterialProperties propStore = null;

    /**
     * Per-instance UBO backing the non-sampler properties (CgMaterialBlock).
     * Created on first {@link #onShaderRecompiled()} with UBO props, then reused
     * (via {@code resetFormat}) forever — never deleted or nulled on subsequent recompiles.
     */
    private CgUniformBuffer matPropsUbo = null;

    /** Whether property values have changed since the last GPU upload. */
    private boolean materialPropsDirty = true;

    /**
     * Optional next material pass in a multi-pass draw chain.
     * -- GETTER --
     * Returns the next material pass in the draw chain, or {@code null} if none.
     */
    @Getter
    private CgMaterial nextPass = null;

    // ── Constructors (private — use factory methods) ──────────────────────────

    /** Private no-arg constructor used by {@link #forTest} overloads. */
    private CgMaterial() {
        this.cgMaterialShader = null;
    }

    /** Private constructor used by {@link #create(String)}, {@link #newInstance(String)}, and {@link #fromShader(CgMaterialShader)}. */
    private CgMaterial(CgMaterialShader asset) {
        this.cgMaterialShader = asset;
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Returns the cached material for {@code resourcePath}, loading it on first access.
     * Delegates to {@link CgMaterialRegistry#get()} which caches by resource path.
     *
     * <p>For independent property values on the same shader, use {@link #newInstance(String)}.</p>
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
     * Creates a new, non-registry-cached {@code CgMaterial} backed by the shared shader asset
     * for {@code resourcePath}. Use when you need independent property values for the same shader.
     *
     * <p>Unlike {@link #load(String)}, this always returns a new instance. The caller is
     * responsible for calling {@link #delete()} when done.</p>
     *
     * <p>Two calls with the same path share the same compiled GL programs but have independent
     * {@code propStore} and {@code matPropsUbo}.</p>
     *
     * @param resourcePath resource path to the {@code .shader} file
     * @return a fresh {@code CgMaterial} instance; never the same object as a prior call
     */
    public static CgMaterial newInstance(String resourcePath) {
        CgMaterialShader asset = CgMaterialShaderRegistry.get().getOrCreate(resourcePath);
        return new CgMaterial(asset);
    }

    /**
     * Creates a new {@code CgMaterial} from an explicit {@link CgMaterialShader} asset.
     * Intended for shader-graph-generated or programmatically constructed shaders that have
     * no backing resource path.
     * The caller is responsible for calling {@link #delete()} when done.
     *
     * @param shaderAsset the shared shader asset to back this material
     * @return a fresh {@code CgMaterial} instance
     * @throws IllegalArgumentException if {@code shaderAsset} is null
     */
    public static CgMaterial fromShader(CgMaterialShader shaderAsset) {
        if (shaderAsset == null) throw new IllegalArgumentException("shaderAsset must not be null");
        return new CgMaterial(shaderAsset);
    }

    /**
     * Creates a new {@code CgMaterial} from a {@code .shader} file.
     * Called only by {@link CgMaterialRegistry}; external callers use {@link #load}.
     *
     * <p>Compilation is <strong>deferred</strong> to the first {@link #bind()} call so that
     * {@link #attach(CgShaderBuffer, String) attach()} calls made between {@code load()} and
     * {@code bind()} are included in the compiled GLSL.</p>
     *
     * @throws IllegalArgumentException  if the source cannot be loaded or is empty (from {@code bind()})
     * @throws CgShaderParseException    on parse error (from {@code bind()})
     * @throws IllegalStateException     if compilation or linking fails (from {@code bind()})
     * @throws UnsupportedOperationException if the hardware does not support GL 3.3+ (from {@code bind()})
     */
    static CgMaterial create(String resourcePath) {
        CgMaterialShader asset = CgMaterialShaderRegistry.get().getOrCreate(resourcePath);
        return new CgMaterial(asset);
    }

    static CgMaterial forTest(CgRenderState renderState, int renderQueue) {
        CgMaterial m = new CgMaterial();
        m.renderState = renderState;
        m.renderQueue = renderQueue;
        return m;
    }

    static CgMaterial forTest(CgRenderState renderState, int renderQueue, CgMaterialProperties propStore) {
        CgMaterial m = forTest(renderState, renderQueue);
        m.propStore = propStore;
        return m;
    }

    CgMaterialProperty getPropertyForTest(String name) {
        if (propStore == null) return null;
        return propStore.all().stream().filter(p -> p.getName().equals(name)).findFirst().orElse(null);
    }

    // ── Buffer attachment API ─────────────────────────────────────────────────

    /**
     * Attaches a user-defined auxiliary buffer to this material's backing shader asset.
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
     * <em>shared cached instance</em> from {@code CgMaterialRegistry}, backed by a shared
     * {@link CgMaterialShader}. Attaching a buffer affects all materials sharing the same
     * shader. Only call {@code attach()} on materials you exclusively own.</p>
     *
     * <p><strong>TBO-path constraints</strong> (validated at compile time, not here):
     * <ul>
     *   <li>All field types must satisfy {@link CgGpuType#isTboCompatible()} — float-family only.
     *       INT, UINT, BOOL, IVEC*, UVEC*, INT64, UINT64 are not accepted.</li>
     *   <li>Format stride must be a multiple of 16 bytes (one TBO texel = 16 bytes).</li>
     * </ul>
     *
     * <p><strong>Runtime binding</strong>: per-context GL binding ({@code glBindBufferBase} /
     * {@code glActiveTexture+glBindTexture}) is <em>your responsibility</em>. Call
     * {@code buffer.bind()} before your draw call.</p>
     *
     * @param buffer    the buffer to attach; format must use {@code STD430}
     * @param macroName uppercase GLSL-style identifier function, e.g. {@code "FONT_METRICS"}
     * @return {@code this} for fluent chaining
     * @apiNote Never throws — validation failures are logged as warnings and the call becomes a no-op.
     */
    public CgMaterial attach(CgShaderBuffer buffer, String macroName) {
        if (cgMaterialShader != null) cgMaterialShader.attach(buffer, macroName);
        return this;
    }

    /**
     * Detaches the SSBO/TBO buffer registered under {@code macroName}; no-op if not found.
     *
     * @return {@code this} for fluent chaining
     */
    public CgMaterial detach(String macroName) {
        if (cgMaterialShader != null) cgMaterialShader.detach(macroName);
        return this;
    }

    /**
     * Detaches the SSBO/TBO buffer by reference; no-op if not attached.
     *
     * @return {@code this} for fluent chaining
     */
    public CgMaterial detach(CgShaderBuffer buffer) {
        if (cgMaterialShader != null) cgMaterialShader.detach(buffer);
        return this;
    }

    /**
     * Attaches a UBO to this material's backing shader asset for GLSL flat-block auto-injection.
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
     * <p><strong>Runtime binding</strong>: call {@code buffer.bind()} before your draw call.</p>
     *
     * @param buffer UBO to attach; format must use {@link CgBufferFormat.MemoryLayout#STD140}
     * @return {@code this} for fluent chaining
     * @apiNote Never throws — validation failures are logged as warnings and the call becomes a no-op.
     */
    public CgMaterial attach(CgUniformBuffer buffer) {
        if (cgMaterialShader != null) cgMaterialShader.attach(buffer);
        return this;
    }

    /**
     * Detaches the UBO with the given block name; no-op if not found.
     *
     * @return {@code this} for fluent chaining
     */
    public CgMaterial detachUbo(String blockName) {
        if (cgMaterialShader != null) cgMaterialShader.detachUbo(blockName);
        return this;
    }

    /**
     * Detaches the UBO by reference; no-op if not attached.
     *
     * @return {@code this} for fluent chaining
     */
    public CgMaterial detach(CgUniformBuffer buffer) {
        if (cgMaterialShader != null) cgMaterialShader.detach(buffer);
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
     *   <li>If the shader asset is dirty, triggers {@link CgMaterialShader#recompile()} and
     *       {@link #onShaderRecompiled()} to rebuild per-instance state.</li>
     *   <li>If the shader's revision changed since last bind, calls {@link #onShaderRecompiled()}.</li>
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

        // Detect shader recompile (hot-reload or first compile).
        if (cgMaterialShader != null) {
            if (cgMaterialShader.isDirty()) {
                cgMaterialShader.recompile();
                onShaderRecompiled();
            } else if (cgMaterialShader.getRevisionNumber() != lastKnownRevision) {
                onShaderRecompiled();
            }
        }

        CgShader shader = cgMaterialShader != null ? cgMaterialShader.getShader() : null;
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

        CgRenderState rs = cgMaterialShader != null ? cgMaterialShader.getRenderState() : renderState;
        rs.apply();
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
        CgShader shader = cgMaterialShader != null ? cgMaterialShader.getShader() : null;
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
     * after initial setup). May be {@code null} before the first successful compile.
     *
     * @return the compiled shader handle, or {@code null} if not yet compiled
     */
    public CgShader getShader() {
        checkNotDeleted();
        return cgMaterialShader != null ? cgMaterialShader.getShader() : null;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Marks the backing shader asset dirty so it will be recompiled on the next
     * {@link #bind()} call. Called by {@link CgMaterialRegistry#reloadAll()} during
     * hot-reload (F3+T), but now delegates through to the shared asset.
     *
     * <p>If the material has already been deleted or has no shader asset (forTest), this is a no-op.</p>
     */
    public void markDirty() {
        if (!deleted && cgMaterialShader != null) cgMaterialShader.markDirty();
    }

    /**
     * Triggers a recompile of the backing shader asset, then rebuilds per-instance state.
     *
     * <p>Serves as the public API for explicit recompilation. In normal usage, recompilation
     * happens lazily on {@link #bind()}. No-op when there is no shader asset ({@code forTest} path).</p>
     */
    public void recompile() {
        if (cgMaterialShader == null) return; // forTest() path
        cgMaterialShader.recompile();
        onShaderRecompiled();
    }

    /**
     * Frees the per-instance property UBO. After this call, the material is unusable.
     *
     * <p>The backing shader programs are <strong>not</strong> deleted here — they are owned by
     * the shared {@link CgMaterialShader} asset and managed by {@link CgMaterialShaderRegistry}.
     * Only the property UBO (instance-owned) is freed.</p>
     *
     * <p>Idempotent — subsequent calls are no-ops.</p>
     */
    public void delete() {
        if (!deleted) {
            deleted = true;
            // Do NOT delete cgMaterialShader.getShader() — that is asset-owned, not instance-owned.
            if (matPropsUbo != null) {
                matPropsUbo.delete();
                matPropsUbo = null;
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Rebuilds per-instance state (propStore, matPropsUbo) after the shared shader asset
     * has been successfully recompiled. Called from {@link #bind()} whenever the asset's
     * {@link CgMaterialShader#getRevisionNumber()} diverges from {@link #lastKnownRevision}.
     *
     * <p>No-op if the shader's parse result is null (compile failed — keep old state).</p>
     */
    private void onShaderRecompiled() {
        CgParsedShader parsed = cgMaterialShader.getParsed();
        if (parsed == null) return; // compile failed, keep old state

        // Rebuild property store with fresh clones of parsed defaults.
        if (propStore == null) {
            propStore = new CgMaterialProperties(cloneProperties(parsed.properties()));
        } else {
            propStore.rebuild(cloneProperties(parsed.properties()));
        }

        // Re-create or reset the per-instance UBO if property layout changed.
        if (propStore.hasUboProps()) {
            CgBufferFormat newFormat = propStore.buildUboFormat();
            if (matPropsUbo == null) {
                matPropsUbo = new CgUniformBuffer(MATERIAL_PROPERTIES_BLOCK, newFormat, CgBindingPoints.MATERIAL_PROPERTIES_UBO);
            } else {
                matPropsUbo.resetFormat(newFormat);
            }

            // Wire this material's UBO block index to the (potentially new) shader program.
            CgShader shader = cgMaterialShader.getShader();
            if (shader != null) {
                shader.bind();
                matPropsUbo.wireShader(shader);
                shader.unbind();
            }

            // Upload property defaults immediately after successful compile/link (T7 behaviour),
            // so a freshly-loaded material has its parsed default values in the GPU buffer
            // before any applyProperties() call from the caller.
            propStore.writeUboProps(matPropsUbo.writer());
            matPropsUbo.endRecord();
            matPropsUbo.upload();
            materialPropsDirty = false;
        }

        lastKnownRevision = cgMaterialShader.getRevisionNumber();
    }

    /**
     * Returns a fresh list of per-instance property clones from a shared parsed property list.
     * Each property is cloned via {@link CgMaterialProperty#copyWithDefaults()} so that each
     * material instance has independent, mutable property objects.
     */
    private static List<CgMaterialProperty> cloneProperties(List<CgMaterialProperty> source) {
        List<CgMaterialProperty> result = new ArrayList<>(source.size());
        for (CgMaterialProperty p : source)
            result.add(p.copyWithDefaults());
        return result;
    }

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
