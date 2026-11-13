package com.crystalgraphics.api.material;

import com.crystalgraphics.material.CgMaterialProperties;
import com.crystalgraphics.material.CgMaterialProperty;
import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.material.CgMaterialShader;
import com.crystalgraphics.material.CgMaterialShaderRegistry;
import com.crystalgraphics.material.shader.CgShaderPermutations;
import com.crystalgraphics.api.state.CgRenderState;

import javax.annotation.Nullable;
import java.util.List;

/**
 * A material: a shader with its state, keywords and default values, shared by everyone who draws it and never changed
 * once made. Unreal's material and material instance constant; values of a caller's own are a
 * {@link CgMaterialInstance}.
 *
 * <pre>{@code
 * CgMaterial stone = CgMaterial.load("mymod:shaders/stone.shader");          // a shader with its defaults
 * CgMaterial rock = CgMaterial.of(definition);                               // a materials/ asset
 * CgWorldRenderer.get().draw(mesh, stone).at(x, y, z).submit();
 * }</pre>
 *
 * <h3>Keywords and state make other materials</h3>
 * <pre>{@code
 * CgMaterial shadowed = stone.keyword("SHADOWS_ON", true);                  // made once, then the same
 * CgMaterial glow = CgMaterial.load("mymod:shaders/fx/soft.shader",
 *         CgMaterialState.builder().blend(CgBlendMode.ADDITIVE).twoSided().build());
 * }</pre>
 *
 * <h3>Values of your own</h3>
 * <pre>{@code
 * CgMaterialInstance tinted = stone.instance().colorARGB("_Tint", 0xFF3080FF);
 * world.draw(mesh, tinted).submit();
 * }</pre>
 *
 * <ul>
 *   <li>Every factory answers the same material for the same arguments; none is the caller's to free.</li>
 *   <li>Hot reload is the asset's: an edited {@code .shader} reaches every material and instance made from it.</li>
 *   <li>A keyword its shader does not declare throws, naming it; switching one off that is not on answers this.</li>
 * </ul>
 */
public final class CgMaterial implements CgMaterialInterface {

    /** Block name of the engine-managed material properties UBO. */
    public static final String MATERIAL_PROPERTIES_BLOCK = CgMaterialProxy.MATERIAL_PROPERTIES_BLOCK;

    static {
        CgMaterialProxy.install(m -> m instanceof CgMaterial own ? own.proxy : ((CgMaterialInstance) m).proxy,
                CgMaterial::new);
    }

    /** What the renderer asks of it ({@link CgMaterialProxy#of}). */
    private final CgMaterialProxy proxy;

    CgMaterial(CgMaterialProxy proxy) {
        this.proxy = proxy;
        proxy.owner(this);
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
     * The material a {@code materials/} asset's definition draws as: its archetype, state, keywords, constants and
     * textures.
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
     * {@link #of(CgMaterialDefinition)} with a drawer's keyword where its archetype declares it.
     *
     * <pre>{@code
     * CgMaterialInstance m = CgMaterial.of(definition, "FX_ON_MESH").instance();   // with row slots of its own
     * m.set1i(CgMaterialProperty.rowSlotName("_Color"), 0);
     * }</pre>
     *
     * @throws IllegalStateException naming the definition and what no material state or binding carries yet
     */
    public static CgMaterial of(CgMaterialDefinition definition, @Nullable String drawKeyword) {
        return CgMaterialRegistry.get().getOrCreate(definition, drawKeyword);
    }

    /**
     * The material of the {@code .shader} at {@code resourcePath}, with its defaults.
     *
     * <pre>{@code
     * CgMaterial terrain = CgMaterial.load("mymod:shaders/terrain.shader");
     * }</pre>
     */
    public static CgMaterial load(String resourcePath) {
        return CgMaterialRegistry.get().getOrCreate(resourcePath);
    }

    /** {@link #load(String)} by a typed key. */
    public static CgMaterial load(CgMaterialKey key) {
        return CgMaterialRegistry.get().getOrCreate(key);
    }

    /**
     * {@link #load(String)} drawn under {@code state} where it is not the shader's: Unreal's material instance
     * overriding its parent's blend mode or two-sidedness.
     *
     * <pre>{@code
     * CgMaterial glow = CgMaterial.load("mymod:shaders/fx/soft.shader",
     *         CgMaterialState.builder().blend(CgBlendMode.ADDITIVE).twoSided().build());
     * }</pre>
     *
     * <ul>
     *   <li>Materials of one path and one blend and clip share programs, whatever their cull and depth write.</li>
     *   <li>A state the shader refuses (a depth write under its {@code "Depth"} tag) fails at its parse, logged like
     *       any parse error: it draws nothing.</li>
     * </ul>
     */
    public static CgMaterial load(String resourcePath, CgMaterialState state) {
        return CgMaterialRegistry.get().of(CgMaterialShaderRegistry.get().getOrCreate(resourcePath, state), state);
    }

    /**
     * The material of a {@link CgMaterialShader} with no file behind it: one a shader graph or a program built.
     *
     * @throws IllegalArgumentException if {@code shaderAsset} is null
     */
    public static CgMaterial fromShader(CgMaterialShader shaderAsset) {
        if (shaderAsset == null) throw new IllegalArgumentException("shaderAsset must not be null");
        return CgMaterialRegistry.get().of(shaderAsset, CgMaterialState.SHADER);
    }

    /**
     * The material of {@code .shader} source held in memory: what a node graph compiles to. Parsed, compiled and drawn
     * as a file is; two identical sources answer one material, so an edit that emits the same source costs nothing.
     *
     * <pre>{@code
     * CgMaterial preview = CgMaterial.fromSource(graphCompiler.emit(graph));
     * if (preview.precache()) shown = preview;       // compiled without stalling a frame
     * }</pre>
     *
     * <p>Not touched by hot reload: there is no file. Different source is a different material.</p>
     *
     * @throws IllegalArgumentException if {@code source} is null or empty
     */
    public static CgMaterial fromSource(String source) {
        return fromSource(source, CgMaterialState.SHADER);
    }

    /** {@link #fromSource(String)} drawn under {@code state}, as {@link #load(String, CgMaterialState)} is. */
    public static CgMaterial fromSource(String source, CgMaterialState state) {
        return CgMaterialRegistry.get().of(CgMaterialShaderRegistry.get().getOrCreateGenerated(source, state), state);
    }

    // ── Other materials of it ─────────────────────────────────────────────────

    /**
     * This material with keyword {@code name} on or off: made at the first ask with this one's values, then the same.
     * Allocates nothing once made, so a drawer may ask per draw.
     *
     * <pre>{@code
     * CgMaterial m = base.keyword("WITH_MASK", rounded).keyword("RADIAL", radial);
     * }</pre>
     *
     * @throws IllegalArgumentException if {@code on} and its shader declares no keyword {@code name}
     */
    public CgMaterial keyword(String name, boolean on) {
        return proxy.keyword(name, on);
    }

    /**
     * A new instance: this material's programs, its values to start from, values of its own after. The caller's to
     * {@link CgMaterialInstance#delete}.
     *
     * <pre>{@code
     * CgMaterialInstance mine = material.instance().set1f("_Alpha", 0.5f);
     * }</pre>
     */
    public CgMaterialInstance instance() {
        return new CgMaterialInstance(this, proxy);
    }

    // ── Reads ─────────────────────────────────────────────────────────────────

    @Override
    public CgMaterial material() {
        return this;
    }

    @Override
    public int changes() {
        return proxy.changes();
    }

    @Override
    public int getMaterialId() {
        return proxy.getMaterialId();
    }

    @Override
    public boolean precache() {
        return proxy.precache();
    }

    @Nullable
    @Override
    public String lastCompileError() {
        return proxy.lastCompileError();
    }

    @Override
    public CgMaterialState state() {
        return proxy.state();
    }

    @Override
    public List<CgMaterialProperty> properties() {
        return proxy.properties();
    }

    @Override
    public List<String> declaredKeywords() {
        return proxy.declaredKeywords();
    }

    @Override
    public boolean isKeywordEnabled(String name) {
        return proxy.isKeywordEnabled(name);
    }

    @Override
    public CgShaderPermutations permutations() {
        return proxy.permutations();
    }

    @Override
    public boolean declaresBuffer(String name) {
        return proxy.declaresBuffer(name);
    }

    @Override
    public boolean names(String identifier) {
        return proxy.names(identifier);
    }

    @Override
    public String toString() {
        return proxy.toString();
    }
}
