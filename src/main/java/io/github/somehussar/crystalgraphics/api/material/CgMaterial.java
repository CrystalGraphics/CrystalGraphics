package io.github.somehussar.crystalgraphics.api.material;

import io.github.somehussar.crystalgraphics.api.CgBindingPoints;
import io.github.somehussar.crystalgraphics.api.buffer.CgBufferFormat;
import io.github.somehussar.crystalgraphics.api.buffer.CgGpuType;
import io.github.somehussar.crystalgraphics.api.render.CgRenderPipeline;
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
import io.github.somehussar.crystalgraphics.gl.material.parse.CgParsedPass;
import io.github.somehussar.crystalgraphics.gl.material.parse.CgParsedShader;
import io.github.somehussar.crystalgraphics.gl.state.CgGlScope;
import io.github.somehussar.crystalgraphics.gl.state.CgGlState;
import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * <h3>Keyword system</h3>
 * <p>Shaders declare optional features via {@code #pragma cg_feature NAME} in their preamble.
 * Enabling a keyword injects {@code #define NAME 1} into both vertex and fragment sources.
 * Each distinct (variant, keyword-set) combination is compiled lazily and cached.</p>
 * <pre>{@code
 * material.enableKeyword("SHADOWS_ON");
 * material.bind(); // compiles STANDARD + {"SHADOWS_ON"} variant on first call
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
     * In real materials, render state is per-pass and fetched via {@link #getPassRenderState(CgRenderPassVariant)}.
     */
    private CgRenderState renderState = CgRenderState.DEFAULT;

    /**
     * Fallback render queue for materials created via {@link #forTest(CgRenderState, int)}.
     * In real materials this is delegated to {@link CgMaterialShader#getRenderQueue()}.
     */
    private int renderQueue = 2000;

    /**
     * The most recently bound {@link CgShader} from the last {@link #bind()} or
     * {@link #bindForVariant(CgRenderPassVariant)} call. Used by {@link #unbind()} to
     * deactivate the correct program. {@code null} before the first successful bind.
     */
    private CgShader lastBoundShader = null;

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
     * Active feature-flag keywords declared via {@code #pragma cg_feature}.
     * Only names present in {@link CgParsedShader#featureNames()} are accepted.
     */
    private final Set<String> enabledKeywords = new LinkedHashSet<>();

    /**
     * Tracks which compiled {@link CgShader} instances the per-instance {@code matPropsUbo}
     * has been wired to. Cleared on hot-reload so newly compiled variants get wired on first bind.
     */
    private final Set<CgShader> wiredToMatPropsUbo = new HashSet<>();

    /**
     * Property-apply consumers buffered before {@code propStore} was first initialised.
     * This happens when {@link #applyProperties} is called before the first {@link #bind()}
     * and the shader hasn't compiled yet.
     * Drained into {@code propStore} inside {@link #onShaderRecompiled()} after the first
     * successful compile. Lazily allocated — null until first buffered call.
     */
    private List<Consumer<CgShaderBindings>> pendingApplyConsumers = null;

    /**
     * Optional next material pass in a multi-pass draw chain.
     * -- GETTER --
     * Returns the next material pass in the draw chain, or {@code null} if none.
     */
    @Getter
    private CgMaterial nextPass = null;

    // ── forTest-only fields (null for real materials) ──────────────────────────

    /**
     * Declared feature names injected for the forTest path (used by
     * {@link #enableKeyword(String)} validation in forTest mode).
     */
    private List<String> testFeatureNames;

    // ── Immutable key for the forTest program cache ────────────────────────────

    /** Cache key for active keywords on the forTest path. Set-equality for keywords. */
    private static final class ForTestKey {
        final Set<String> keywords;

        ForTestKey(Set<String> keywords) {
            this.keywords = Collections.unmodifiableSet(new LinkedHashSet<>(keywords));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ForTestKey)) return false;
            return keywords.equals(((ForTestKey) o).keywords);
        }

        @Override
        public int hashCode() {
            return Objects.hash(keywords);
        }
    }

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

    /**
     * Creates a test material with declared feature names for unit-testing keyword
     * enable/disable validation without a real GL context.
     *
     * @param renderState  render state for this test material
     * @param renderQueue  render queue priority
     * @param featureNames declared feature names to accept in {@link #enableKeyword(String)}
     * @return a test material with keyword validation backed by the given names
     */
    static CgMaterial forTest(CgRenderState renderState, int renderQueue,
                               List<String> featureNames) {
        CgMaterial m = forTest(renderState, renderQueue);
        m.testFeatureNames = Collections.unmodifiableList(new ArrayList<>(featureNames));
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
     * ({@code CgRenderPipeline.objectBuffer()}, etc.). Those are wired automatically by the
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
        // Lazy-init propStore if the shader asset exists but propStore hasn't been built yet
        // (e.g. applyProperties() called before the first bind()). Mirrors the enableKeyword pattern.
        if (propStore == null && cgMaterialShader != null) recompile();
        
        if (propStore == null) {
            // Shader hasn't compiled yet (compile failed, or forTest path with no asset) —
            // buffer the consumer so it's replayed after the first successful compile.
            if (pendingApplyConsumers == null) pendingApplyConsumers = new ArrayList<>();
            pendingApplyConsumers.add(consumer);
            return this;
        }
        consumer.accept(propStore);
        materialPropsDirty = true;
        return this;
    }

    // ── Keyword API ───────────────────────────────────────────────────────────

    /**
     * Enables a feature-flag keyword declared via {@code #pragma cg_feature NAME} in the shader.
     * Subsequent {@link #bind()} calls will compile and use a variant with
     * {@code #define NAME 1} injected into both vertex and fragment sources.
     *
     * <p>Safe to call before the first {@link #bind()} — if the backing shader asset has not
     * been compiled yet, a lazy compile is triggered here so that {@code featureNames} are
     * available for validation. This mirrors the same compile path taken by {@code bind()}.</p>
     *
     * @param name keyword name to enable; must be declared in the shader's {@code #pragma cg_feature} list
     * @throws IllegalArgumentException if {@code name} is not declared as a feature in this shader
     */
    public void enableKeyword(String name) {
        checkNotDeleted();
        // If the shader asset exists but hasn't been compiled yet (e.g. called in init() before
        // bind()), trigger a compile now so featureNames are populated for validation.
        if (cgMaterialShader != null && cgMaterialShader.getLastParsed() == null) {
            cgMaterialShader.recompile();
        }
        List<String> declared = getDeclaredFeatureNames();
        if (!declared.contains(name)) {
            throw new IllegalArgumentException(
                    "Keyword '" + name + "' is not declared as #pragma cg_feature in this shader");
        }
        enabledKeywords.add(name);
    }

    /**
     * Disables a previously-enabled keyword. No-op if the keyword is not currently enabled.
     *
     * @param name keyword name to disable
     */
    public void disableKeyword(String name) {
        checkNotDeleted();
        enabledKeywords.remove(name);
    }

    /**
     * Returns {@code true} if the given keyword is currently enabled on this material instance.
     *
     * @param name keyword name to query
     * @return {@code true} if enabled, {@code false} otherwise
     */
    public boolean isKeywordEnabled(String name) {
        checkNotDeleted();
        return enabledKeywords.contains(name);
    }

    // ── Draw-time API ─────────────────────────────────────────────────────────

    /**
     * Binds this material for rendering using the current set of enabled keywords.
     *
     * <p>The keyword set is compiled lazily on first call and cached
     * for subsequent calls. After a hot-reload all cached programs are cleared and the first
     * bind triggers recompilation.</p>
     *
     * <h3>Operations performed in order</h3>
     * <ol>
     *   <li>If the shader asset is dirty, triggers {@link CgMaterialShader#recompile()} and
     *       {@link #onShaderRecompiled()} to rebuild per-instance state.</li>
     *   <li>If the shader's revision changed since last bind, calls {@link #onShaderRecompiled()}.</li>
     *   <li>{@link CgMaterialShader#getOrCompile(Set)} — retrieves or compiles the program.</li>
     *   <li>Delegates the remaining bind steps to {@link #doBind(CgShader, CgRenderPassVariant)}.</li>
     * </ol>
     */
    public void bind() {
        checkNotDeleted();

        if (cgMaterialShader != null) {
            if (cgMaterialShader.isDirty()) {
                cgMaterialShader.recompile();
                wiredToMatPropsUbo.clear();
                onShaderRecompiled();
            } else if (cgMaterialShader.getRevisionNumber() != lastKnownRevision) {
                wiredToMatPropsUbo.clear();
                onShaderRecompiled();
            }

            Set<String> keywords = Collections.unmodifiableSet(enabledKeywords);
            CgShader shader = cgMaterialShader.getOrCompileForwardPass(keywords);
            if (shader == null) return;
            lastBoundShader = shader;
            doBind(shader, CgRenderPassVariant.FORWARD);
        }
        // forTest without cgMaterialShader — no-op
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
        if (lastBoundShader != null) lastBoundShader.unbind();
        if (matPropsUbo != null) matPropsUbo.unbind();
        if (stateScope != null) {
            stateScope.close();
            stateScope = null;
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    /**
     * Returns the render state for the given pass variant from the last successful compile.
     * Falls back to the per-instance {@code renderState} field for forTest materials.
     *
     * <p>For FORWARD: resolves via the first Forward-lit pass. For SHADOW/DEPTH: resolves by
     * LightMode tag name. Returns {@link CgRenderState#DEFAULT} when the pass is not found.</p>
     *
     * @param variant the pass variant whose render state to retrieve; must not be null
     * @return render state for the pass; never {@code null}
     */
    public CgRenderState getPassRenderState(CgRenderPassVariant variant) {
        if (cgMaterialShader == null) return renderState;
        CgParsedShader parsed = cgMaterialShader.getLastParsed();
        if (parsed == null) return CgRenderState.DEFAULT;
        if (variant == CgRenderPassVariant.FORWARD) {
            CgParsedPass forwardPass = parsed.getPassByLightMode(CgRenderPassVariant.FORWARD.lightModeName());
            if (forwardPass == null) return CgRenderState.DEFAULT;
            return cgMaterialShader.getRenderState(forwardPass.name());
        }
        return cgMaterialShader.getRenderState(variant.lightModeName());
    }

    /**
     * Binds the compiled program for the given pass variant.
     *
     * <p>FORWARD uses the active enabled-keyword set. SHADOW and DEPTH always use an empty
     * keyword set — keywords apply to forward passes only. The render state for the targeted
     * pass is applied. Per-instance property UBO is wired and uploaded as needed.</p>
     *
     * <p>Designed for orchestrators that drive multi-pass shadow / depth rendering externally
     * (e.g. a shadow renderer that calls {@code bindForVariant(CgRenderPassVariant.SHADOW)} and
     * then draws into a shadow map FBO).</p>
     *
     * @param variant the pass variant to bind; must not be null
     */
    public void bindForVariant(CgRenderPassVariant variant) {
        checkNotDeleted();
        if (cgMaterialShader == null) return;

        if (cgMaterialShader.isDirty()) {
            cgMaterialShader.recompile();
            wiredToMatPropsUbo.clear();
            onShaderRecompiled();
        } else if (cgMaterialShader.getRevisionNumber() != lastKnownRevision) {
            wiredToMatPropsUbo.clear();
            onShaderRecompiled();
        }

        CgShader shader;
        if (variant == CgRenderPassVariant.FORWARD) {
            shader = cgMaterialShader.getOrCompileForwardPass(Collections.unmodifiableSet(enabledKeywords));
        } else {
            shader = cgMaterialShader.getOrCompile(variant.lightModeName(), Collections.emptySet());
        }
        if (shader == null) return;
        lastBoundShader = shader;
        doBind(shader, variant);
    }

    /**
     * Shared bind body: wires the per-instance UBO, saves GL state, uploads dirty properties,
     * binds the UBO and sampler properties, applies per-pass render state, and activates the
     * GL program. Called by both {@link #bind()} and {@link #bindForVariant(CgRenderPassVariant)}
     * after shader resolution.
     *
     * @param shader  the fully compiled and wired GL program for this frame
     * @param variant the pass variant whose render state to apply
     */
    private void doBind(CgShader shader, CgRenderPassVariant variant) {
        // Wire per-instance matPropsUbo to this keyword variant on first use
        if (matPropsUbo != null && !wiredToMatPropsUbo.contains(shader)) {
            matPropsUbo.wireShader(shader);
            wiredToMatPropsUbo.add(shader);
        }

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

        getPassRenderState(variant).apply();
        shader.bind();
    }

    /**
     * Returns {@code true} when this material will cast shadows.
     *
     * <p>Based on the {@code castShadows} flag from the last successful parse and the render
     * queue — opaque/alpha-test materials ({@code renderQueue < CgRenderQueue.TRANSPARENT})
     * with {@code CastShadows = true} (the default) return {@code true} regardless of whether
     * a ShadowCaster program has been compiled yet. Transparent materials always return
     * {@code false}.</p>
     *
     * <p>A shadow renderer should call {@code bindForVariant(CgRenderPassVariant.SHADOW)} only when
     * this method returns {@code true}.</p>
     */
    public boolean hasShadowCasterPass() {
        if (cgMaterialShader == null) return false;
        if (getRenderQueue() >= CgRenderQueue.TRANSPARENT_THRESHOLD) return false;
        CgParsedShader parsed = cgMaterialShader.getLastParsed();
        if (parsed == null) return false;
        return parsed.castShadows();
    }

    /**
     * Returns {@code true} when this material has an explicitly authored Depth pass in the
     * last successful parse. Based on parse-time pass presence — use to gate calls to
     * {@link #bindForVariant(CgRenderPassVariant) bindForVariant(DEPTH)}.
     */
    public boolean hasDepthPass() {
        return cgMaterialShader != null
                && cgMaterialShader.hasParsedPass(CgRenderPassVariant.DEPTH.lightModeName());
    }

    /**
     * Returns the pipeline's shared per-object SSBO/TBO.
     * Equivalent to {@code CgRenderPipeline.getInstance().objectBuffer()}.
     *
     * <p>Each object record is exactly {@code CgRenderPipeline.OBJECT_FORMAT} = 48 floats.
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
     * @throws IllegalStateException if {@link CgRenderPipeline} has not been initialized
     */
    public CgShaderBuffer objectBuffer() {
        return CgRenderPipeline.getInstance().objectBuffer();
    }

    /**
     * Returns the material properties UBO, or {@code null} for sampler-only shaders.
     * Engine-owned — do not delete.
     */
    public CgUniformBuffer materialBuffer() {
        return matPropsUbo;
    }

    /**
     * Returns the most recently bound {@link CgShader} from the last {@link #bind()} or
     * {@link #bindForVariant(CgRenderPassVariant)} call. Useful for advanced wiring of per-frame
     * UBOs or texture units after bind. {@code null} before the first successful bind.
     *
     * @return the last-bound compiled shader handle, or {@code null} if not yet bound
     */
    public CgShader getShader() {
        checkNotDeleted();
        return lastBoundShader;
    }

    /**
     * Returns the numeric render queue priority for this material (default: 2000 = Geometry).
     * For real materials, delegates to {@link CgMaterialShader#getRenderQueue()} so the value
     * always reflects the last successful compile.
     */
    public int getRenderQueue() {
        if (cgMaterialShader != null) return cgMaterialShader.getRenderQueue();
        return renderQueue;
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
     * happens lazily on {@link #bind()}. For the forTest path (no cgMaterialShader), this
     * clears the test program cache so the next bind triggers the factory again.</p>
     */
    public void recompile() {
        if (cgMaterialShader == null) return;
        cgMaterialShader.recompile();
        wiredToMatPropsUbo.clear();
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
     * has been successfully recompiled. Called from {@link #bind()} whenever
     * the asset's {@link CgMaterialShader#getRevisionNumber()} diverges from
     * {@link #lastKnownRevision}.
     *
     * <p>No-op if the shader's parse result is null (compile failed — keep old state).</p>
     */
    private void onShaderRecompiled() {
        CgParsedShader parsed = cgMaterialShader.getLastParsed();
        if (parsed == null) return;

        if (propStore == null) {
            propStore = new CgMaterialProperties(cloneProperties(parsed.properties()));
        } else {
            // Snapshot the current user-set values before rebuild so they survive hot-reload.
            // Keyed by name; type-checked during restore so type-changed properties reset to default.
            Map<String, CgMaterialProperty> oldValues = new HashMap<>();
            for (CgMaterialProperty p : propStore.all()) {
                oldValues.put(p.getName(), p);
            }
            List<CgMaterialProperty> newClones = cloneProperties(parsed.properties());
            for (CgMaterialProperty newProp : newClones) {
                CgMaterialProperty old = oldValues.get(newProp.getName());
                if (old != null && old.getType() == newProp.getType()) {
                    newProp.copyValueFrom(old);
                }
            }
            propStore.rebuild(newClones);
        }

        if (propStore.hasUboProps()) {
            CgBufferFormat newFormat = propStore.buildUboFormat();
            if (matPropsUbo == null) {
                matPropsUbo = new CgUniformBuffer(MATERIAL_PROPERTIES_BLOCK, newFormat, CgBindingPoints.MATERIAL_PROPERTIES_UBO);
            } else {
                matPropsUbo.resetFormat(newFormat);
            }

        // Wire this material's UBO to the newly compiled STANDARD forward variant
        CgShader shader = cgMaterialShader.getOrCompileForwardPass(Collections.emptySet());
            if (shader != null) {
                shader.bind();
                matPropsUbo.wireShader(shader);
                shader.unbind();
                wiredToMatPropsUbo.add(shader);
            }

            // Upload property defaults immediately after successful compile/link (T7 behaviour)
            propStore.writeUboProps(matPropsUbo.writer());
            matPropsUbo.endRecord();
            matPropsUbo.upload();
            materialPropsDirty = false;
        } else {
            // Shader dropped all non-sampler properties on hot-reload — free the instance UBO.
            if (matPropsUbo != null) {
                matPropsUbo.delete();
                matPropsUbo = null;
            }
        }

        lastKnownRevision = cgMaterialShader.getRevisionNumber();

        // Drain any consumers buffered by applyProperties() calls made before propStore existed.
        if (pendingApplyConsumers != null && !pendingApplyConsumers.isEmpty()) {
            for (Consumer<CgShaderBindings> pending : pendingApplyConsumers) {
                pending.accept(propStore);
            }
            pendingApplyConsumers.clear();
            materialPropsDirty = true;
        }
    }

    /**
     * Returns the declared feature names for this material.
     * For real materials: from the last successful parse. For forTest: from {@code testFeatureNames}.
     */
    private List<String> getDeclaredFeatureNames() {
        if (cgMaterialShader != null && cgMaterialShader.getLastParsed() != null) {
            return cgMaterialShader.getLastParsed().featureNames();
        }
        if (testFeatureNames != null) {
            return testFeatureNames;
        }
        return Collections.emptyList();
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

    /**
     * Executes {@code drawCommand} once per pass in this material's draw chain,
     * binding each pass for the specified {@link CgRenderPassVariant} before the draw
     * and unbinding it after.
     *
     * <p>Analogues: Unity {@code Material.SetPass(index)} per pass type;
     * Godot {@code _render_list_template<PASS_MODE>()}; Filament variant routing.</p>
     *
     * <p>Source: render-pipeline-curation.md Section 14 (SHOULD-HAVE, Oracle correction D).</p>
     *
     * @param variant     the render pass variant to activate for each pass in the chain
     * @param drawCommand the draw logic to invoke for each pass
     */
    public void drawChain(CgRenderPassVariant variant, Runnable drawCommand) {
        CgMaterial pass = this;
        while (pass != null) {
            pass.bindForVariant(variant);
            drawCommand.run();
            pass.unbind();
            pass = pass.nextPass;
        }
    }
}
