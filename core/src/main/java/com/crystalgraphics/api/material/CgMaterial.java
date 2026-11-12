package com.crystalgraphics.api.material;

import com.crystalgraphics.material.CgMaterialProperties;
import com.crystalgraphics.material.CgMaterialProperty;
import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.material.CgMaterialShader;
import com.crystalgraphics.material.CgMaterialShaderRegistry;
import com.crystalgraphics.material.parse.CgPermutationDomain;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.render.draw.CgBufferHandle;

import javax.annotation.Nullable;
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
 * <h3>Drawing with a material</h3>
 * <pre>{@code
 * CgWorldRenderer.get().draw(mesh, material).at(x, y, z).submit();        // into the world, recorded
 *
 * CgRasterPass pass = rec.raster("preview").color(picture, CgLoad.CLEAR).camera(camera);   // a pass of yours
 * pass.draw(mesh, material).transform(model);
 * pass.end();
 * }</pre>
 *
 * <h3>Keyword system</h3>
 * <p>Shaders declare optional features via {@code #pragma cg_feature NAME} in their preamble.
 * Enabling a keyword injects {@code #define NAME 1} into both vertex and fragment sources.
 * Each distinct (variant, keyword-set) combination is compiled lazily and cached.</p>
 * <pre>{@code
 * material.enableKeyword("SHADOWS_ON");
 * world.draw(mesh, material).submit();     // {"SHADOWS_ON"}'s program, built at its first draw
 * }</pre>
 *
 * <h3>Hot-reload</h3>
 * <p>Hot-reload is asset-level: {@link CgMaterialShader#markDirty()} is set on the shared asset,
 * which increments its {@link CgMaterialShader#getRevisionNumber()} on the next successful
 * compile. The next draw sees the new parse and rebuilds the properties, keeping each
 * value whose property kept its name and type.</p>
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

    /** Block name of the engine-managed material properties UBO. */
    public static final String MATERIAL_PROPERTIES_BLOCK = CgMaterialProxy.MATERIAL_PROPERTIES_BLOCK;

    static {
        CgMaterialProxy.install(m -> m.proxy);
    }

    /** What the renderer asks of it ({@link CgMaterialProxy#of}). */
    private final CgMaterialProxy proxy;

    CgMaterial(CgMaterialProxy proxy) {
        this.proxy = proxy;
        proxy.owner(this);
    }

    /** Made by {@link CgMaterialRegistry} alone; external callers use {@link #load}. */
    static CgMaterial create(String resourcePath) {
        return new CgMaterial(new CgMaterialProxy(CgMaterialShaderRegistry.get().getOrCreate(resourcePath)));
    }

    static CgMaterial forTest(CgRenderState renderState, int renderQueue) {
        return new CgMaterial(CgMaterialProxy.forTest(renderState, renderQueue));
    }

    static CgMaterial forTest(CgRenderState renderState, int renderQueue, CgMaterialProperties propStore) {
        return new CgMaterial(CgMaterialProxy.forTest(renderState, renderQueue, propStore));
    }

    static CgMaterial forTest(CgRenderState renderState, int renderQueue, List<String> featureNames) {
        return new CgMaterial(CgMaterialProxy.forTest(renderState, renderQueue, featureNames));
    }

    CgMaterialProperty getPropertyForTest(String name) {
        return proxy.getPropertyForTest(name);
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * The material a {@code material} asset's definition draws as, shared by every caller: its archetype, state,
     * keywords, constants and textures. {@link CgMaterialRegistry#getOrCreate(CgMaterialDefinition, String)} adds a
     * draw's keyword.
     *
     * <pre>{@code
     * CgMaterial glow = CgMaterial.of(definition);
     * CgWorldRenderer.get().draw(mesh, glow).at(x, y, z).submit();
     * }</pre>
     */
    public static CgMaterial of(CgMaterialDefinition definition) {
        return CgMaterialRegistry.get().getOrCreate(definition, null);
    }

    /**
     * A fresh material of {@code definition}, the caller's to configure further and to {@link #delete()}: what
     * {@link #of} shares, for a drawer that adds per-draw state of its own (row slots, buffers).
     *
     * <pre>{@code
     * CgMaterial m = CgMaterial.newInstance(definition, "FX_ON_MESH");   // the draw keyword, where the shader declares it
     * m.applyProperties(b -> b.set1i(CgMaterialProperty.rowSlotName("_Color"), 0));
     * }</pre>
     *
     * @throws IllegalStateException naming the definition and what no material state or binding carries yet
     */
    public static CgMaterial newInstance(CgMaterialDefinition definition, @Nullable String drawKeyword) {
        return CgMaterialRegistry.make(definition, drawKeyword);
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
        return new CgMaterial(new CgMaterialProxy(CgMaterialShaderRegistry.get().getOrCreate(resourcePath)));
    }

    /**
     * {@link #newInstance(String)} drawn under {@code state} where it is not the shader's: Unreal's material instance
     * overriding its parent's blend mode or two-sidedness.
     *
     * <pre>{@code
     * CgMaterial glow = CgMaterial.newInstance("mymod:shaders/fx/soft.shader",
     *         CgMaterialState.builder().blend(CgBlendMode.ADDITIVE).twoSided().build());
     * }</pre>
     *
     * <ul>
     *   <li>Materials of one path and one blend and clip share programs, whatever their cull and depth write.</li>
     *   <li>A state the shader refuses (a depth write under its {@code "Depth"} tag) fails at its parse, logged like
     *       any parse error: {@link #pipeline} answers null.</li>
     * </ul>
     */
    public static CgMaterial newInstance(String resourcePath, CgMaterialState state) {
        return new CgMaterial(new CgMaterialProxy(CgMaterialShaderRegistry.get().getOrCreate(resourcePath, state), state));
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
        return new CgMaterial(new CgMaterialProxy(shaderAsset));
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
        return new CgMaterial(new CgMaterialProxy(CgMaterialShaderRegistry.get().getOrCreateGenerated(source)));
    }

    /** {@link #fromSource(String)} drawn under {@code state}, as {@link #newInstance(String, CgMaterialState)} is. */
    public static CgMaterial fromSource(String source, CgMaterialState state) {
        return new CgMaterial(new CgMaterialProxy(CgMaterialShaderRegistry.get().getOrCreateGenerated(source, state), state));
    }

    // ── What a consumer sets and reads ────────────────────────────────────────

    /**
     * How many times something that may change what it draws was set: a canvas starts a new draw where this moved, so
     * a value set between two items takes effect at the second.
     */
    public int changes() {
        return proxy.changes();
    }

    /**
     * What the driver said about this material's last failed compile, or null.
     *
     * <p>Forwarded from the shader so a <b>generated</b> material's owner can act on it: a graph-emitted
     * shader that the driver refuses is a message the user needs, and the line number in it maps back to
     * the node that wrote the line. For a shipped asset this is still just the log.</p>
     */
    @Nullable
    public String lastCompileError() {
        return proxy.lastCompileError();
    }

    /** Its render state where it is not its shader's; {@link CgMaterialState#SHADER} for none. */
    public CgMaterialState state() {
        return proxy.state();
    }

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
    public CgMaterial applyProperties(Consumer<CgMaterialValues> consumer) {
        proxy.applyProperties(consumer);
        return this;
    }

    /**
     * The properties its shader declares, and under {@code cg_use rows} the hidden row slots the parser adds, from the
     * parse alone: what a binder checks a name and its kind against. Empty while the source does not parse.
     *
     * <pre>{@code
     * for (CgMaterialProperty p : material.properties()) if (p.isRowable()) bindable.add(p.getName());
     * }</pre>
     */
    public List<CgMaterialProperty> properties() {
        return proxy.properties();
    }

    /** The keywords its shader declares ({@code #pragma cg_feature}), from the parse alone. */
    public List<String> declaredKeywords() {
        return proxy.declaredKeywords();
    }

    /**
     * The keywords a renderer switches on its shader at runtime ({@code #pragma cg_permutation}), from the parse alone:
     * every combination a precache of the shader builds.
     */
    public CgPermutationDomain permutations() {
        return proxy.permutations();
    }

    /**
     * Whether its shader's {@code Buffers { }} declares {@code name}, from the parse alone: what {@link #buffer} must bind
     * before its first draw.
     *
     * <pre>{@code
     * if (material.declaresBuffer("FX_CLIPS")) material.buffer("FX_CLIPS", clips);
     * }</pre>
     */
    public boolean declaresBuffer(String name) {
        return proxy.declaresBuffer(name);
    }

    /**
     * Enables a feature-flag keyword declared via {@code #pragma cg_feature NAME} in the shader.
     * Draws afterwards use a variant with
     * {@code #define NAME 1} injected into both vertex and fragment sources.
     *
     * <p>Safe to call before any compile: the shader is parsed, not compiled, to validate the name.</p>
     *
     * @param name keyword name to enable; must be declared in the shader's {@code #pragma cg_feature} list
     * @throws IllegalArgumentException if {@code name} is not declared as a feature in this shader
     */
    public void enableKeyword(String name) {
        proxy.enableKeyword(name);
    }

    /**
     * Disables a previously-enabled keyword. No-op if the keyword is not currently enabled.
     *
     * @param name keyword name to disable
     */
    public void disableKeyword(String name) {
        proxy.disableKeyword(name);
    }

    /** Disables all previously-enabled keyword. */
    public void disableAllKeywords() {
        proxy.disableAllKeywords();
    }

    /**
     * Returns {@code true} if the given keyword is currently enabled on this material instance.
     *
     * @param name keyword name to query
     * @return {@code true} if enabled, {@code false} otherwise
     */
    public boolean isKeywordEnabled(String name) {
        return proxy.isKeywordEnabled(name);
    }

    /**
     * Toggles keyword on/off according to the enabled boolean passed.
     *
     * @param name keyword name to query
     */
    public void toggleKeyword(String name, boolean enabled) {
        proxy.toggleKeyword(name, enabled);
    }

    public boolean precache() {
        return proxy.precache();
    }

    public CgMaterial buffer(String name, CgBufferHandle buffer) {
        proxy.buffer(name, buffer);
        return this;
    }

    public int getMaterialId() {
        return proxy.getMaterialId();
    }

    /**
     * Whether a pass of its shader names {@code identifier} as a whole word, in its declarations or its stages' code,
     * parsing it if it is not yet: what a renderer asks before writing a value only some shaders read.
     *
     * <pre>{@code
     * boolean bounds = material.names("CG_OBJECT_POSITION") || material.names("CG_OBJECT_RADIUS");
     * }</pre>
     *
     * <ul>
     *   <li>A name used only inside an included file is not seen: the shader's own code is searched.</li>
     * </ul>
     */
    public boolean names(String identifier) {
        return proxy.names(identifier);
    }

    /**
     * Marks the backing shader asset dirty so it recompiles at its next use. Called by {@link CgMaterialRegistry#reloadAll()} during
     * hot-reload (F3+T), but now delegates through to the shared asset.
     *
     * <p>If the material has already been deleted or has no shader asset (forTest), this is a no-op.</p>
     */
    public void markDirty() {
        proxy.markDirty();
    }

    /**
     * Triggers a recompile of the backing shader asset, then rebuilds per-instance state.
     *
     * <p>Explicit recompilation; otherwise a dirty shader recompiles at its next use. No-op without a shader
     * asset.</p>
     */
    public void recompile() {
        proxy.recompile();
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
        proxy.delete();
    }

    public CgMaterial setNextPass(CgMaterial next) {
        proxy.setNextPass(next == null ? null : next.proxy);
        return this;
    }
}
