package com.crystalgraphics.api.material;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.api.buffer.CgBufferLifetime;
import com.crystalgraphics.api.buffer.CgGpuType;
import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.api.shader.CgShader;
import com.crystalgraphics.api.shader.CgShaderBindings;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.gl.buffer.shader.CgEngineBufferRegistry;
import com.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import com.crystalgraphics.gl.buffer.shader.CgUniformBuffer;
import com.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import com.crystalgraphics.gl.buffer.staging.CgStagingBuffer;
import com.crystalgraphics.gl.material.CgMaterialProperties;
import com.crystalgraphics.gl.material.CgMaterialProperty;
import com.crystalgraphics.gl.material.CgMaterialShader;
import com.crystalgraphics.gl.material.CgMaterialShaderRegistry;
import com.crystalgraphics.gl.material.parse.CgParsedPass;
import com.crystalgraphics.gl.material.parse.CgParsedShader;
import com.crystalgraphics.render.draw.CgBindingTable;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPipeline;
import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.annotation.Nullable;

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

    /** Monotonically incrementing counter — produces unique per-instance IDs across all materials. */
    private static final AtomicInteger NEXT_MATERIAL_ID = new AtomicInteger(0);

    /** Stable per-instance integer ID. Assigned at construction, never changes. */
    private final int materialId = NEXT_MATERIAL_ID.getAndIncrement();

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
     * {@link #bindForPass(CgRenderPassVariant)} call. Used by {@link #unbind()} to
     * deactivate the correct program. {@code null} before the first successful bind.
     */
    private CgShader lastBoundShader = null;


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
     *
     * <p>{@link CgBufferLifetime#FRAME}: every draw of this material binds it first, and {@link #syncProps}
     * uploads it at every bind, which {@link CgUniformBuffer#upload()} turns into nothing unless the bytes moved or
     * this is the frame's first bind. The UI rewrites its blocks on nearly every draw, and orphaning cost a driver
     * rename per rewrite.</p>
     */
    private CgUniformBuffer matPropsUbo = null;

    /** Whether a block-backed property's value moved since the block was last packed. */
    private boolean materialPropsDirty = true;

    /** The parse {@link #propStore} was built from: a new parse rebuilds it, keeping the values set. */
    @Nullable
    private CgParsedShader propsParse;

    /** The properties block as a capture copies it: CPU only, unlike {@link #matPropsUbo}'s writer. */
    @Nullable
    private CgBufferWriter capturePacker;
    private boolean capturePropsDirty = true;
    /** This instance's own contents for uniform blocks attached to the shared shader, read in their place. */
    private Map<CgShaderBuffer, CgBufferWriter> blockOverrides;

    /** Forward pipelines by instance kind and keyword mask, for {@link #pipelinesParse}. */
    @Nullable
    private CgPipeline[] pipelines;
    @Nullable
    private CgParsedShader pipelinesParse;

    /**
     * Active feature-flag keywords declared via {@code #pragma cg_feature}.
     * Only names present in {@link CgParsedShader#featureNames()} are accepted.
     */
    private final Set<String> enabledKeywords = new LinkedHashSet<>();

    /**
     * Programs that have had per-instance state wired: matPropsUbo block binding and sampler
     * unit assignments. Cleared on hot-reload so newly compiled variants are re-wired on first bind.
     */
    private final Set<CgShader> wiredPrograms = new HashSet<>();

    /**
     * Property-apply consumers buffered before {@code propStore} was first initialised.
     * This happens when {@link #applyProperties} is called before the first {@link #bind()}
     * and the shader hasn't compiled yet.
     * Drained into {@code propStore} inside {@link #onShaderRecompiled()} after the first
     * successful compile. Lazily allocated — null until first buffered call.
     */
    private List<Consumer<CgShaderBindings>> pendingApplyConsumers = null;

    /**
     * Optional chained material for multi-draw decorative effects (outline, additive glow,
     * stencil fill, etc.).
     *
     * <p>When {@link #drawChain(Runnable)} or {@link #drawChain(CgRenderPassVariant, Runnable)}
     * is called, this material's draw executes first; then the chained material's draw executes
     * immediately after with the same mesh and the same draw command. Ordering is deterministic
     * and immediate — not deferred through a sort queue.</p>
     *
     * <p>Each chained material is a fully independent {@code CgMaterial}: different shader,
     * different render state, different properties, different keywords. This is NOT Unity's
     * built-in multi-pass (multiple {@code Pass} blocks in one shader file compiled into one
     * material). It is analogous to Godot's {@code next_pass}, but with guaranteed immediate
     * ordering instead of Godot's sort-queue-based execution which has known ordering bugs.</p>
     *
     * <p>The CrystalShader {@code .shader} format handles the "different pass types in one
     * material" axis via {@code LightMode} tags ({@code Forward}, {@code ShadowCaster},
     * {@code Depth}). This field handles the orthogonal "same pass type, drawn twice for
     * a visual effect" axis.</p>
     *
     * -- GETTER --
     * Returns the chained material, or {@code null} if none.
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
    /**
     * What the driver said about this material's last failed compile, or null.
     *
     * <p>Forwarded from the shader so a <b>generated</b> material's owner can act on it: a graph-emitted
     * shader that the driver refuses is a message the user needs, and the line number in it maps back to
     * the node that wrote the line. For a shipped asset this is still just the log.</p>
     */
    @Nullable
    public String lastCompileError() {
        return cgMaterialShader == null ? null : cgMaterialShader.lastCompileError();
    }

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
     * Creates a material from {@code .shader} source held in memory rather than loaded from a file —
     * what a node graph compiles to.
     *
     * <pre>{@code
     * CgMaterial mat = CgMaterial.fromSource(graphCompiler.emit(graph));
     * }</pre>
     *
     * <p>The source is parsed, compiled, cached and drawn by exactly the same path a file takes: a
     * generated shader is not a second kind of material, it is the same kind that arrived by a different
     * route. Keywords, passes, properties, shadow and depth auto-generation all behave identically.</p>
     *
     * <p><b>Shared by content, not by call.</b> Two identical sources return materials backed by one
     * asset and therefore one GL program — which is what keeps a grid of node previews from compiling
     * the same thing a dozen times. It also means an edit that does not change the emitted GLSL costs
     * nothing.</p>
     *
     * <p><b>Not touched by hot reload</b>, because there is no file to re-read. A generated shader is
     * invalidated by its owner emitting different source, which is a different content hash and so a
     * different asset.</p>
     *
     * @param source complete {@code .shader} text
     * @return a fresh {@code CgMaterial} over the shared generated asset
     * @throws IllegalArgumentException if {@code source} is null or empty
     */
    public static CgMaterial fromSource(String source) {
        return new CgMaterial(CgMaterialShaderRegistry.get().getOrCreateGenerated(source));
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
     * Brings the properties block up to date for a bind, doing the least it can -- in the UI 79% of block
     * uploads once carried the bytes already there:
     * <ul>
     *   <li>repacked only after a property's value actually moved ({@link #applyProperties}), not after a write
     *       of the same value or of a sampler;</li>
     *   <li>then {@link CgUniformBuffer#upload()}, which sends it only if the bytes differ from the last upload,
     *       or on the frame's first bind, since the block lives on the frame ring.</li>
     * </ul>
     */
    private void syncProps() {
        if (materialPropsDirty) {
            CgTrace.add(CgChannels.GL, PROPS_PACK, 1);
            propStore.writeUboProps(matPropsUbo.writer());
            matPropsUbo.endRecord();
            materialPropsDirty = false;
        }
        matPropsUbo.upload();
    }

    private static final int PROPS_PACK = CgTrace.name("material.propsPack");

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
        // From the parse alone, so setting a property compiles nothing.
        syncPropsToParse();

        if (propStore == null) {
            // The source does not parse, or a forTest material with no asset: replayed once properties exist.
            if (pendingApplyConsumers == null) pendingApplyConsumers = new ArrayList<>();
            pendingApplyConsumers.add(consumer);
            return this;
        }
        consumer.accept(propStore);
        // Only a value that moved: rewriting the same opacity, or only a sampler, leaves the block as it is.
        if (propStore.consumeBlockChanged()) {
            materialPropsDirty = true;
            capturePropsDirty = true;
        }
        if (propStore.consumeSamplerUnitChanged()) wiredPrograms.clear();
        return this;
    }

    // ── Keyword API ───────────────────────────────────────────────────────────

    /**
     * Enables a feature-flag keyword declared via {@code #pragma cg_feature NAME} in the shader.
     * Subsequent {@link #bind()} calls will compile and use a variant with
     * {@code #define NAME 1} injected into both vertex and fragment sources.
     *
     * <p>Safe to call before the first {@link #bind()}: the shader is parsed, not compiled, to validate the name.</p>
     *
     * @param name keyword name to enable; must be declared in the shader's {@code #pragma cg_feature} list
     * @throws IllegalArgumentException if {@code name} is not declared as a feature in this shader
     */
    public void enableKeyword(String name) {
        checkNotDeleted();
        // The declared features are a parse product: validating a keyword compiles nothing.
        if (cgMaterialShader != null) cgMaterialShader.ensureParsed();
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

    /** Disables all previously-enabled keyword. */
    public void disableAllKeywords() {
        checkNotDeleted();
        enabledKeywords.clear();
    }

    /**
     * The forward program per keyword set, indexed by {@link #keywordMask}, for the shader revision in
     * {@link #variantsRevision}. A UI material is bound hundreds of times a frame with its keywords toggled between
     * draws, and each resolve built a program key and copied the keyword set: most of what a busy frame allocated.
     */
    @Nullable
    private CgShader[] variants;
    private int variantsRevision = -1;
    @Nullable
    private List<String> variantsDeclared;

    /** The enabled keywords as bits over the declared features, or -1 past the eight a shader may declare. */
    private int keywordMask() {
        return keywordMask(getDeclaredFeatureNames());
    }

    private int keywordMask(List<String> declared) {
        if (declared.size() > 8) return -1;
        int mask = 0;
        for (int i = 0; i < declared.size(); i++) {
            if (enabledKeywords.contains(declared.get(i))) mask |= 1 << i;
        }
        return mask;
    }

    /** The forward pass's render state, and the parse it was read from. */
    @Nullable
    private CgRenderState forwardRenderState;
    @Nullable
    private CgParsedShader forwardRenderStateParse;

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

    /**
     * Toggles keyword on/off according to the enabled boolean passed.
     *
     * @param name keyword name to query
     */
    public void toggleKeyword(String name, boolean enabled) {
        if (enabled && !isKeywordEnabled(name)) enableKeyword(name);
        else if (!enabled && isKeywordEnabled(name)) disableKeyword(name);
    }

    // ── Draw-time API ─────────────────────────────────────────────────────────

    /**
     * {@code -Dcrystalgraphics.shader.parallelCompile=false} makes {@link #prepare} answer ready at once, so the
     * first bind compiles on the spot: the escape hatch for a driver that misreports completion.
     */
    private static final boolean PARALLEL_COMPILE =
            !"false".equals(System.getProperty("crystalgraphics.shader.parallelCompile"));

    /**
     * Starts this material's compile without waiting for it, and says whether {@link #bind} would now return
     * without waiting on the driver. What a caller that must never stall a frame polls instead of binding.
     *
     * <pre>{@code
     * CgMaterial next = CgMaterial.fromSource(source);
     * // each frame:
     * if (next.prepare()) {        // compiled, or failed: next.lastCompileError() says which
     *     current.delete();
     *     current = next;
     * }
     * current.bind();              // the old picture until the new one is ready
     * }</pre>
     *
     * <p>False on the frame it starts a compile, always: where the driver cannot report progress, that frame is
     * the head start its own compile threads get. Binding before it answers true waits for the compile, as a
     * material that was never prepared does.</p>
     *
     * <p>It compiles what {@link #bind} needs, the Forward pass. A shadow or depth program the shader does not
     * author is generated the first time {@link #bindForPass} or {@link #hasCompiledDepthPass} asks for it.</p>
     */
    public boolean prepare() {
        checkNotDeleted();
        if (cgMaterialShader == null || !PARALLEL_COMPILE) return true;
        if (cgMaterialShader.isDirty()) {
            cgMaterialShader.submitRecompile(true);
            return false;
        }
        return cgMaterialShader.pollPending();
    }

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
            // Before the revision check, which a commit here moves: a bind is never allowed to draw half-wired.
            cgMaterialShader.awaitPending();
            if (cgMaterialShader.isDirty()) {
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "material.recompile")) {
                    cgMaterialShader.recompile();
                }
                wiredPrograms.clear();
                onShaderRecompiled();
            } else if (cgMaterialShader.getRevisionNumber() != lastKnownRevision) {
                wiredPrograms.clear();
                onShaderRecompiled();
            }

            int revision = cgMaterialShader.getRevisionNumber();
            int mask = keywordMask();
            // The declared list too: a reload that parses and then fails to compile moves it without the revision,
            // and a bit would then name another keyword than the one its variant was cached under.
            List<String> declared = getDeclaredFeatureNames();
            if (variantsRevision != revision || variantsDeclared != declared || variants == null) {
                variants = mask < 0 ? null : new CgShader[1 << declared.size()];
                variantsRevision = revision;
                variantsDeclared = declared;
            }
            CgShader shader = mask >= 0 && variants != null && mask < variants.length ? variants[mask] : null;
            if (shader == null) {
                // Compiles-and-caches the variant for this exact keyword set on first use, so the
                // first bind after a keyword toggle pays a full GLSL compile+link. Scoped because
                // that cost is otherwise invisible: it surfaces inside whatever draw happened to
                // trigger the toggle, not at any obvious "compiling now" call site.
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "material.getOrCompileVariant")) {
                    shader = cgMaterialShader.getOrCompileForwardPass(Collections.unmodifiableSet(enabledKeywords));
                }
                if (shader == null) return;
                if (mask >= 0 && variants != null && mask < variants.length) variants[mask] = shader;
            }
            lastBoundShader = shader;
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "material.doBind")) {
                doBind(shader, CgRenderPassVariant.FORWARD);
            }
        }
        // forTest without cgMaterialShader — no-op
    }

    /**
     * Unbinds the shader and its property UBO.
     *
     * <p>Deliberately does <strong>not</strong> restore GL state. Nothing here saved any: the enclosing
     * render pass owns the handback to Minecraft, and material-to-material isolation is handled by
     * {@code CgGlStateManager} when the next material applies its render state — see the note in
     * {@code doBind}.</p>
     *
     * <p>Still never call {@code renderState.clear()} from this path: that resets domains to GL defaults
     * rather than to what the pass began with, which is not what unbinding a material should mean.</p>
     */
    public void unbind() {
        if (deleted) return;
        if (lastBoundShader != null) lastBoundShader.unbind();
        if (matPropsUbo != null) matPropsUbo.unbind();
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    /**
     * This material's pipeline for {@code kind}: its Forward pass under the keywords enabled now, with that pass's
     * render state. Compiles nothing; the program is resolved when a batch draws with it.
     *
     * <pre>{@code
     * material.enableKeyword("WITH_MASK");
     * CgPipeline p = material.pipeline(CgInstanceKind.QUAD);
     * int b = material.captureBindings(table);
     * // record a draw with p and b; toggling the keyword after changes neither
     * }</pre>
     *
     * @return null when the shader does not parse: there is nothing to draw
     */
    @Nullable
    public CgPipeline pipeline(CgInstanceKind kind) {
        checkNotDeleted();
        if (cgMaterialShader == null) return null;
        CgParsedShader parsed = cgMaterialShader.ensureParsed();
        if (parsed == null) return null;
        List<String> declared = parsed.featureNames();
        int mask = keywordMask(declared);
        if (mask < 0) return pipeline(CgRenderPassVariant.FORWARD, kind);
        int variants = 1 << declared.size();
        if (pipelinesParse != parsed || pipelines == null) {
            pipelines = new CgPipeline[CgInstanceKind.values().length * variants];
            pipelinesParse = parsed;
        }
        int at = kind.ordinal() * variants + mask;
        CgPipeline pipeline = pipelines[at];
        if (pipeline == null) pipelines[at] = pipeline = pipeline(CgRenderPassVariant.FORWARD, kind);
        return pipeline;
    }

    /**
     * As {@link #pipeline(CgInstanceKind)}, for any pass. Keywords apply to {@link CgRenderPassVariant#FORWARD} only.
     *
     * @return null when the shader does not parse
     */
    @Nullable
    public CgPipeline pipeline(CgRenderPassVariant pass, CgInstanceKind kind) {
        checkNotDeleted();
        if (cgMaterialShader == null || cgMaterialShader.ensureParsed() == null) return null;
        return CgPipeline.of(cgMaterialShader, pass, enabledKeywords, getPassRenderState(pass), kind);
    }

    /**
     * Snapshots what a draw of this material reads, as it is now, into {@code table}: the properties block's bytes,
     * each sampler's texture at its declared unit, and any buffer attached with {@link #attach}. Compiles nothing.
     *
     * <pre>{@code
     * material.applyProperties(b -> b.colorARGB("_Color", 0xFFFF0000));
     * int red = material.captureBindings(table);
     * material.applyProperties(b -> b.colorARGB("_Color", 0xFF0000FF));
     * int blue = material.captureBindings(table);   // a different id; red still reads red
     * }</pre>
     *
     * <ul>
     *   <li>Equal snapshots answer one id, so two draws of one material with the same values batch.</li>
     *   <li>An attached buffer is bound as it is when the draw executes, not copied.</li>
     *   <li>A sampler's unit is its index among the shader's declared samplers, whatever unit
     *       {@code sampler(name, unit, texture)} was given.</li>
     * </ul>
     *
     * @return the snapshot's id in {@code table}
     */
    public int captureBindings(CgBindingTable table) {
        checkNotDeleted();
        syncPropsToParse();
        table.begin();
        if (propStore != null) {
            if (propStore.hasUboProps()) {
                CgBufferWriter packer = capturePacker();
                table.block(CgBindingPoints.MATERIAL_PROPERTIES_UBO, packer.rawData(), 0, packer.rawCursor());
            }
            propStore.captureSamplers(table);
        }
        if (cgMaterialShader != null) captureAttachedBuffers(table);
        return table.end();
    }

    /** The properties block packed as it is now, repacked only after a value moved. */
    private CgBufferWriter capturePacker() {
        if (capturePacker == null) {
            CgBufferFormat format = propStore.buildUboFormat();
            capturePacker = new CgBufferWriter(new CgStagingBuffer(format.getFloatCount()), format);
            capturePropsDirty = true;
        }
        if (capturePropsDirty) {
            propStore.writeUboProps(capturePacker);
            capturePropsDirty = false;
        }
        return capturePacker;
    }

    /**
     * Gives this material its own contents for {@code block}, a uniform block attached to the shader every instance
     * of it shares: {@link #captureBindings} keeps {@code contents} instead of the block's own writer. For a renderer
     * that keeps per-instance values in a shared block, so two instances recording at once cannot read each other's.
     *
     * <pre>{@code
     * CgBufferWriter mine = new CgBufferWriter(new CgStagingBuffer(format.getFloatCount()), format);
     * material.overrideBlock(sharedBlock, mine);
     * mine.reset().beginRecord().mat4("u_Projection", projection);   // what this instance's next capture keeps
     * }</pre>
     *
     * <p>Captures only: a {@link #bind()} still reads the block itself.</p>
     */
    public CgMaterial overrideBlock(CgUniformBuffer block, CgBufferWriter contents) {
        if (blockOverrides == null) blockOverrides = new IdentityHashMap<>();
        blockOverrides.put(block, contents);
        return this;
    }

    /**
     * The buffers a user attached, leaving out those an engine token declared: an executor binds its kind's own. A
     * uniform block is kept by value, as written now, since its owner rewrites it before the draw executes.
     */
    private void captureAttachedBuffers(CgBindingTable table) {
        List<CgAttachedBuffer> attached = cgMaterialShader.getAttachedBuffers();
        if (attached.isEmpty()) return;
        CgParsedShader parsed = cgMaterialShader.getLastParsed();
        for (int i = 0; i < attached.size(); i++) {
            CgAttachedBuffer buffer = attached.get(i);
            CgShaderBuffer target = buffer.getBuffer();
            if (buffer.isUbo()) {
                CgBufferWriter override = blockOverrides == null ? null : blockOverrides.get(target);
                CgBufferWriter written = override != null ? override : target.writer();
                if (written.rawCursor() > 0) {
                    table.block(target.getBindingLocation(), written.rawData(), 0, written.rawCursor());
                    continue;
                }
            } else if (parsed != null && declaresEngineBuffer(parsed, buffer.getMacroName())) {
                continue;
            }
            table.buffer(target);
        }
    }

    private static boolean declaresEngineBuffer(CgParsedShader parsed, String macroName) {
        for (String token : parsed.engineBuffers()) {
            if (CgEngineBufferRegistry.get(token).macroName().equals(macroName)) return true;
        }
        return false;
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
            if (forwardRenderState != null && forwardRenderStateParse == parsed) return forwardRenderState;
            CgParsedPass forwardPass = parsed.getPassByLightMode(CgRenderPassVariant.FORWARD.lightModeName());
            CgRenderState state = forwardPass == null ? CgRenderState.DEFAULT : forwardPass.renderState();
            forwardRenderState = state;
            forwardRenderStateParse = parsed;
            return state;
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
     * (e.g. a shadow renderer that calls {@code bindForPass(CgRenderPassVariant.SHADOW)} and
     * then draws into a shadow map FBO).</p>
     *
     * @param variant the pass variant to bind; must not be null
     */
    public void bindForPass(CgRenderPassVariant variant) {
        checkNotDeleted();
        if (cgMaterialShader == null) return;

        cgMaterialShader.awaitPending();
        if (cgMaterialShader.isDirty()) {
            cgMaterialShader.recompile();
            wiredPrograms.clear();
            onShaderRecompiled();
        } else if (cgMaterialShader.getRevisionNumber() != lastKnownRevision) {
            wiredPrograms.clear();
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
     * GL program. Called by both {@link #bind()} and {@link #bindForPass(CgRenderPassVariant)}
     * after shader resolution.
     *
     * @param shader  the fully compiled and wired GL program for this frame
     */
    private void wirePerInstance(CgShader shader) {
        shader.bind();
        if (matPropsUbo != null) matPropsUbo.wireShader(shader);
        if (propStore != null) propStore.wireSamplerUnits(shader);
        shader.unbind();
    }

    private void doBind(CgShader shader, CgRenderPassVariant variant) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL_DETAIL, "doBind.wire")) {
            if (!wiredPrograms.contains(shader)) {
                wirePerInstance(shader);
                wiredPrograms.add(shader);
            }
        }

        // No per-bind GL state scope here. Two independent reasons, both learned the hard way:
        //
        // 1. It CANNOT be a scope, because material bind/unbind is not last-in-first-out.
        //    CgQuadRenderer.useMaterial calls bind() on every call but unbind() only when the material
        //    changes, and UI painting opens nested FBO/viewport scopes while a material is bound. A
        //    stack-based scope closed under those conditions throws "closed out of order" — which is
        //    exactly what happened when this was briefly reinstated. The old glGet-based version did not
        //    crash only because overwriting its single `stateScope` field leaked the previous capture
        //    silently, restoring stale values or none at all.
        //
        // 2. It is NOT NEEDED. The enclosing render pass (CgRenderPipeline's opaque/transparent passes,
        //    CgUiPaintContext.beginFrame) restores Minecraft's state at pass exit, and per-material
        //    isolation falls out of getPassRenderState(variant).apply() below: every DECLARED domain is
        //    written through CgGlStateManager, so it overrides whatever the previous material left.
        //    Undeclared domains are deliberately left alone — callers configure ambient state around
        //    materials (CgUiPaintContext enables blending for UI text), and a variant that reverted
        //    undeclared domains to the scope baseline switched that blending off and rendered every
        //    glyph as an opaque block.
        //
        // What this removes is ~25 glGet* driver synchronisation points per bind; one observed frame
        // spent 346.8 ms in them.

        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL_DETAIL, "doBind.propsUpload")) {
            // Every draw that reads the frame block binds a material first, so this is where a frame's copy
            // of it is made -- see CgRenderPipeline.carryFrameBlock.
            CgRenderPipeline.carryFrameBlock();
            if (matPropsUbo != null) syncProps();
        }

        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL_DETAIL, "doBind.uboBind")) {
            if (matPropsUbo != null) matPropsUbo.bind();
        }

        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL_DETAIL, "doBind.samplers")) {
            if (propStore != null && propStore.hasSamplerProps())
                propStore.bindSamplerTextures();
        }

        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL_DETAIL, "doBind.renderState")) {
            getPassRenderState(variant).apply();
        }

        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL_DETAIL, "doBind.shaderBind")) {
            shader.bind();
        }
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
     * <p>A shadow renderer should call {@code bindForPass(CgRenderPassVariant.SHADOW)} only when
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
     * {@link #bindForPass(CgRenderPassVariant) bindForPass(DEPTH)}.
     *
     * <p>This is a parse-state check (explicit authored {@code [Depth]} block only).
     * To also detect auto-generated depth variants produced by {@code recompile()},
     * use {@link #hasCompiledDepthPass()} instead.</p>
     *
     * @see #hasCompiledDepthPass()
     */
    public boolean hasDepthPass() {
        return cgMaterialShader != null && cgMaterialShader.hasParsedPass(CgRenderPassVariant.DEPTH.lightModeName());
    }

    /**
     * Returns {@code true} when a compiled depth prepass GL program exists for this material.
     *
     * <p>Unlike {@link #hasDepthPass()} (which checks for an explicit authored
     * {@code [Depth]} pass), this method also returns {@code true} for auto-generated depth
     * variants produced by {@link CgMaterialShader#recompile()}
     * during {@code attemptDepthAutoGen()}. It is the correct query for
     * {@code CgDepthPrepassRenderer} to decide whether to use
     * {@code bindForPass(DEPTH)} or fall back to the engine depth shader.</p>
     *
     * @return {@code true} if a compiled depth variant program is available
     */
    public boolean hasCompiledDepthPass() {
        return cgMaterialShader != null && cgMaterialShader.hasCompiledPass(CgRenderPassVariant.DEPTH.lightModeName());
    }

    /**
     * Returns a stable integer ID unique to this {@code CgMaterial} instance.
     * Assigned at construction from a monotonically incrementing counter.
     * Used by {@code CgRenderCommandQueue.submit()} as the 16-bit materialId
     * in the opaque sort key — more reliable than {@code System.identityHashCode}
     * which can collide for different live instances.
     *
     * @return the per-instance stable material ID (non-negative, unique per instance)
     */
    public int getMaterialId() {
        return materialId;
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
     * buf.endWrite();                     // uploads, and re-binds at the new offset
     * material.bind();
     * mesh.drawInstanced(N);
     * material.unbind();
     * }</pre>
     *
     * <p>Write the records in the frame that draws them. The buffer is on the frame ring, where a region is
     * reused three frames on, so records kept from an earlier frame are not there to draw.</p>
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
     * {@link #bindForPass(CgRenderPassVariant)} call. Useful for advanced wiring of per-frame
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
        wiredPrograms.clear();
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
        // Parse products are now published even when the GLSL compile failed, so a non-null parse no
        // longer implies a live program. Keep the old state in that case: building propStore and a
        // per-instance UBO for a material with no working shader allocates GL objects for something
        // that cannot draw, and hides the failure behind half-initialised state.
        if (cgMaterialShader.hasCompileFailed()) return;

        syncPropsToParse();   // a parse is in hand, so this always leaves a propStore

        if (propStore.hasUboProps()) {
            CgBufferFormat newFormat = propStore.buildUboFormat();
            if (matPropsUbo == null) {
                matPropsUbo = new CgUniformBuffer(MATERIAL_PROPERTIES_BLOCK, newFormat, CgBindingPoints.MATERIAL_PROPERTIES_UBO,
                        CgBufferLifetime.FRAME);
            } else {
                matPropsUbo.resetFormat(newFormat);
            }

        // Wire this material's per-instance state to the newly compiled STANDARD forward variant
        CgShader shader = cgMaterialShader.getOrCompileForwardPass(Collections.emptySet());
        if (shader != null) {
            wirePerInstance(shader);
            wiredPrograms.add(shader);
        }

            // Upload property defaults immediately after successful compile/link (T7 behaviour).
            materialPropsDirty = true;
            syncProps();
        } else {
            // Shader dropped all non-sampler properties on hot-reload — free the instance UBO.
            if (matPropsUbo != null) {
                matPropsUbo.delete();
                matPropsUbo = null;
            }
        }

        lastKnownRevision = cgMaterialShader.getRevisionNumber();
    }

    /**
     * Builds {@link #propStore} from the shader's parse, or rebuilds it when the shader has parsed anew, keeping each
     * value whose property kept its name and type. CPU only: what a capture and a property write need, before any
     * compile.
     */
    private void syncPropsToParse() {
        if (cgMaterialShader == null) return;
        CgParsedShader parsed = cgMaterialShader.ensureParsed();
        if (parsed == null || parsed == propsParse) return;
        propsParse = parsed;
        List<CgMaterialProperty> clones = cloneProperties(parsed.properties());
        if (propStore == null) {
            propStore = new CgMaterialProperties(clones);
        } else {
            Map<String, CgMaterialProperty> oldValues = new HashMap<>();
            for (CgMaterialProperty p : propStore.all()) oldValues.put(p.getName(), p);
            for (CgMaterialProperty clone : clones) {
                CgMaterialProperty old = oldValues.get(clone.getName());
                if (old != null && old.getType() == clone.getType()) clone.copyValueFrom(old);
            }
            propStore.rebuild(clones);
        }
        capturePacker = null;
        materialPropsDirty = true;
        capturePropsDirty = true;

        if (pendingApplyConsumers != null && !pendingApplyConsumers.isEmpty()) {
            for (Consumer<CgShaderBindings> pending : pendingApplyConsumers) pending.accept(propStore);
            pendingApplyConsumers.clear();
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

    // ── Multi-draw chain (decorative effects: outline, glow, stencil, etc.) ────

    /**
     * Chains another material to this one for multi-draw decorative effects.
     *
     * <p>When {@link #drawChain} is called, this material draws first, then {@code next}
     * draws immediately after with the same mesh. The chain is traversed in-order with
     * deterministic, immediate execution — not via a sort queue.</p>
     *
     * <p>{@code next} must be a fully independent {@link CgMaterial} with its own shader,
     * render state, and properties. Typical uses: outline (enlarged backface pass),
     * additive glow, stencil fill. Cycles are detected eagerly and throw
     * {@link IllegalStateException}.</p>
     *
     * @param next the material to draw immediately after this one, or {@code null} to clear
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
     * Executes {@code drawCommand} once per material in this draw chain, binding each
     * material's Forward pass before the draw and unbinding it after.
     *
     * <p>This is the canonical way to draw a mesh with a chained multi-draw material.
     * If {@link #getNextPass()} is {@code null}, only this material draws.</p>
     *
     * @param drawCommand the draw logic (e.g. {@code () -> mesh.drawInstanced(N)})
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
     * Executes {@code drawCommand} once per material in this draw chain, binding each
     * material for the given {@link CgRenderPassVariant} before the draw and unbinding after.
     *
     * <p>Use this overload in renderer code where the pass variant must be explicit
     * (e.g. {@code FORWARD} in {@code CgForwardRenderer}). If {@link #getNextPass()} is
     * {@code null}, only this material draws.</p>
     *
     * @param variant     the pass variant to activate for each material in the chain
     * @param drawCommand the draw logic (e.g. {@code () -> mesh.drawInstanced(N)})
     */
    public void drawChain(CgRenderPassVariant variant, Runnable drawCommand) {
        CgMaterial pass = this;
        while (pass != null) {
            pass.bindForPass(variant);
            drawCommand.run();
            pass.unbind();
            pass = pass.nextPass;
        }
    }
}
