package com.crystalgraphics.gl.material;

import com.github.bsideup.jabel.Desugar;
import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.api.material.CgAttachedBuffer;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.api.shader.CgPreprocessorException;
import com.crystalgraphics.api.shader.CgShader;
import com.crystalgraphics.api.shader.CgShaderPreprocessor;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.gl.buffer.shader.CgEngineBufferRegistry;
import com.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import com.crystalgraphics.gl.buffer.shader.CgUniformBuffer;
import com.crystalgraphics.gl.material.parse.CgMaterialShaderCompiler;
import com.crystalgraphics.gl.material.parse.CgParsedPass;
import com.crystalgraphics.gl.material.parse.CgParsedShader;
import com.crystalgraphics.gl.material.parse.CgShaderParseException;
import com.crystalgraphics.gl.material.parse.CgShaderParser;
import com.crystalgraphics.gl.shader.CgShaderFactory;
import com.crystalgraphics.util.io.CgIO;
import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared shader asset compiled from a {@code .shader} file. Owned by {@link CgMaterialShaderRegistry}.
 *
 * <p>Multiple {@link CgMaterial} instances may reference the same {@code CgMaterialShader} and
 * will share compiled GL programs. Per-instance state (property values, UBO) lives on each
 * {@code CgMaterial}.</p>
 *
 * <p>{@link #getRevisionNumber()} increments on each successful {@link #recompile()}; materials
 * detect recompiles via revision comparison on every {@code bind()} call.</p>
 *
 * <h3>Pass program cache</h3>
 * <p>Each {@code Pass { }} block compiled during {@link #recompile()} gets its own entry in the
 * flat {@link ProgramKey} → {@code CgShader} program cache. A {@code ProgramKey} identifies a
 * program by both its pass name <em>and</em> the active keyword set. Call
 * {@link #getOrCompile(String, Set)} to retrieve or compile a specific combination. The
 * {@link #recompile()} method resets the entire cache and immediately compiles the no-keyword
 * variant of every pass declared in the source.</p>
 *
 * <h3>Shadow auto-generation</h3>
 * <p>If the material has no explicit {@code ShadowCaster} pass, is not transparent, and
 * {@code castShadows} is true, {@link #recompile()} automatically generates a shadow-caster
 * program from the first Forward pass. It is stored under
 * {@code ProgramKey("ShadowCaster", emptySet)} like any other pass — no separate field.</p>
 *
 * <h3>Lifecycle</h3>
 * <p>Created exclusively by {@link CgMaterialShaderRegistry#getOrCreate(String)}. Deleted by
 * {@link CgMaterialShaderRegistry#deleteAll()} on context destruction. Do not call
 * {@link #delete()} directly — the registry owns the lifecycle.</p>
 */
public final class CgMaterialShader {

    private static final Logger LOGGER = LogManager.getLogger("CgMaterialShader");

    // ── Nested key type ───────────────────────────────────────────────────────

    /**
     * Immutable flat cache key for a compiled pass-variant program.
     *
     * <p>Identifies a program by both the pass name <em>and</em> the active keyword set.
     * The keyword set uses set-based equality — order does not matter.
     * Two keys with {@code ("Forward", {"A","B"})} and {@code ("Forward", {"B","A"})} are
     * equal and share the same cache entry.</p>
     *
     * @param passName  authored or auto-assigned pass name (e.g. {@code "Pass0"}, {@code "ShadowCaster"})
     * @param keywords  always an unmodifiable, insertion-ordered copy
     */
    @Desugar
    record ProgramKey(String passName, Set<String> keywords) {
        ProgramKey(String passName, Set<String> keywords) {
            this.passName = passName;
            this.keywords = Collections.unmodifiableSet(new LinkedHashSet<>(keywords));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ProgramKey)) return false;
            ProgramKey other = (ProgramKey) o;
            return passName.equals(other.passName) && keywords.equals(other.keywords);
        }

        @Override
        public int hashCode() {
            return 31 * passName.hashCode() + keywords.hashCode();
        }
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    /** Resource path to the {@code .shader} file. */
    private final String resourcePath;

    /** Parse result from the last successful compile; {@code null} until first successful compile. 
     * -- GETTER --
     * Returns the last successful parse result; 
     *  before first successful compile. 
     */
    @Getter
    private CgParsedShader lastParsed;

    /**
     * Flat pass × keywords program cache. Key = (passName, keywords set).
     * Each entry is a fully linked and wired GL program.
     * Cleared and rebuilt from scratch on hot-reload.
     */
    private final Map<ProgramKey, CgShader> programCache = new LinkedHashMap<>();

    /**
     * Template UBO created from the parsed properties for GLSL block emission and post-link wiring.
     * Not used for value storage — per-material UBOs handle that.
     * {@code null} for sampler-only shaders (no non-sampler properties).
     */
    private CgUniformBuffer matPropsUbo;

    /**
     * Material-level RenderType tag (e.g. {@code "Opaque"}, {@code "Transparent"}).
     * Extracted from the top-level {@code Tags { "RenderType" = "..." }} block.
     * -- GETTER --
     * Returns the RenderType tag value from the last successful compile (default {@code "Opaque"}).
     */
    @Getter
    private String renderType = "Opaque";

    /**
     * Numeric render queue priority extracted from {@code Queue = "..."} (default 2000 = Geometry).
     * -- GETTER --
     * Returns the numeric render queue priority from the last successful compile (default 2000).
     */
    @Getter
    private int renderQueue = 2000;

    /**
     * User-attached SSBO/TBO and UBO buffers for GLSL auto-injection.
     * Mirrors the attach API on {@code CgMaterial}.
     */
    private final List<CgAttachedBuffer> attachedBuffers = new ArrayList<>();

    /**
     * Whether the shader needs a full recompile on the next {@code bind()} call.
     * Set by {@link #markDirty()}; cleared at the start of {@link #recompile()}.
     * -- GETTER --
     * Returns {@code true} if this shader needs a recompile on the next {@code bind()} call.
     */
    @Getter
    private boolean dirty;

    /** Whether {@link #delete()} has been called. */
    private boolean deleted;

    /**
     * Monotonically increasing counter — incremented on each successful {@link #recompile()}.
     * Starts at 0; first successful compile sets it to 1.
     * {@code CgMaterial} stores {@code lastKnownRevision} and calls {@code onShaderRecompiled()}
     * when it diverges from this value.
     * -- GETTER --
     * Returns the monotonically increasing revision counter.
     * Incremented on each successful {@link #recompile()}.
     * {@link CgMaterial} uses this to detect when a hot-reload has occurred.
     */
    @Getter
    private int revisionNumber = 0;

    // ── Constructor (package-private — use CgMaterialShaderRegistry) ─────────

    private CgMaterialShader(String resourcePath) {
        this.resourcePath = resourcePath;
    }

    /**
     * Creates a new {@code CgMaterialShader} for the given resource path, marked dirty
     * so it will compile on first use.
     * Called exclusively by {@link CgMaterialShaderRegistry#getOrCreate(String)}.
     */
    static CgMaterialShader create(String resourcePath) {
        CgMaterialShader s = new CgMaterialShader(resourcePath);
        s.markDirty();
        return s;
    }

    // ── Compile pipeline ──────────────────────────────────────────────────────

    /**
     * Compiles (or recompiles) this shader asset from {@link #resourcePath}.
     *
     * <p>On first call (empty cache): throws on any failure — never returns
     * with a broken state. On subsequent calls (hot-reload): logs errors and keeps the
     * old programs running on failure.</p>
     *
     * <p>No-op when {@code resourcePath} is null (programmatic / shader-graph shaders).</p>
     *
     * <p>On success: clears the program cache, compiles the no-keyword variant of
     * every declared pass immediately, then increments {@link #revisionNumber} so
     * materials detect the change on the next {@code bind()} call.
     * Keyword variants are compiled lazily via {@link #getOrCompile(String, Set)}.</p>
     *
     * <h3>Shadow auto-generation</h3>
     * <p>When no explicit {@code ShadowCaster} pass is authored, the shader renders
     * in queue &lt; {@link CgRenderQueue#TRANSPARENT}, and {@code castShadows == true},
     * a shadow-caster program is auto-generated from the first Forward pass and cached
     * under {@code ProgramKey("ShadowCaster", emptySet)}.</p>
     *
     * <h3>Depth auto-generation</h3>
     * <p>When no explicit {@code Depth} pass is authored and the shader renders in queue
     * &lt; {@link CgRenderQueue#TRANSPARENT}, a depth-prepass program is auto-generated
     * from the first Forward pass and cached under {@code ProgramKey("Depth", emptySet)}.
     * Unlike shadow auto-gen there is no {@code castShadows} gate — every opaque material
     * needs a correct depth variant. Failure is non-fatal: a warning is logged and the
     * engine depth shader fallback is used instead.</p>
     */
    public void recompile() {
        dirty = false;
        if (resourcePath == null) return;

        boolean isFirst = programCache.isEmpty();

        // ── Step 1: Load source ────────────────────────────────────────────────
        String source;
        try {
            source = CgIO.loadSource(resourcePath);
        } catch (Exception e) {
            if (isFirst) throw new IllegalArgumentException("Could not load shader source from: " + resourcePath, e);
            LOGGER.error("Reload failed for '" + resourcePath + "': could not load source — " + e.getMessage());
            return;
        }
        if (source == null || source.isEmpty()) {
            if (isFirst) throw new IllegalArgumentException("Could not load shader source from: " + resourcePath);
            LOGGER.error("Reload failed for '{}': empty source", resourcePath);
            return;
        }

        // ── Step 2: Parse ──────────────────────────────────────────────────────
        CgParsedShader parsed;
        try {
            parsed = CgShaderParser.parse(source, resourcePath);
        } catch (CgShaderParseException e) {
            if (isFirst) throw e;
            LOGGER.error("Reload failed for '" + resourcePath + "': parse error — " + e.getMessage());
            return;
        }

        // ── Step 2b: Attach engine buffers declared via #pragma cg_use ─────────
        // Before compilation, deliberately. These buffers inject GLSL declarations, so attaching
        // them after a compile would be too late — that ordering is exactly what made a shader read
        // an undeclared QUAD_DATA when something forced an early recompile. Doing it here means a
        // declared buffer is wired for every compile of this shader, including the first.
        //
        // Unknown tokens were already rejected at parse time, so the provider is non-null. attach()
        // dedupes by macro name, so a caller that also attached manually is harmless.
        for (String token : parsed.engineBuffers()) {
            CgEngineBufferRegistry.Provider provider = CgEngineBufferRegistry.get(token);
            attach(provider.buffer().get(), provider.macroName());
        }

        // ── Step 3: Capability check ───────────────────────────────────────────
        CgCapabilities.ShaderBufferPath bufferPath = CgCapabilities.detect().shaderBufferPath();
        if (bufferPath == CgCapabilities.ShaderBufferPath.NONE) {
            if (isFirst) throw new UnsupportedOperationException("GL 3.3+ required for CrystalShader materials");
            LOGGER.error("Reload failed for '{}': GL 3.3+ not available", resourcePath);
            return;
        }

        // ── Step 4: Build template UBO for GLSL emission ───────────────────────
        CgMaterialProperties tempProps = new CgMaterialProperties(parsed.properties());
        CgUniformBuffer newMatPropsUbo = null;
        if (tempProps.hasUboProps()) {
            newMatPropsUbo = new CgUniformBuffer(CgMaterial.MATERIAL_PROPERTIES_BLOCK,
                    tempProps.buildUboFormat(), CgBindingPoints.MATERIAL_PROPERTIES_UBO);
        }

        // ── Step 5: Compile all declared passes (no-keyword variant) ──────────
        // Collect newly compiled shaders so we can delete them on partial failure.
        List<CgShader> newShaders = new ArrayList<>();
        Map<ProgramKey, CgShader> newCache = new LinkedHashMap<>();

        for (CgParsedPass pass : parsed.passes()) {
            CgMaterialShaderCompiler.CompiledSource compiled;
            try {
                compiled = CgMaterialShaderCompiler.compile(parsed, pass, attachedBuffers,
                        newMatPropsUbo, CgMaterialShaderCompiler.CompileConfig.DEFAULT);
            } catch (CgPreprocessorException e) {
                // Abort entire recompile — clean up shaders compiled so far
                for (CgShader s : newShaders) s.delete();
                if (newMatPropsUbo != null) newMatPropsUbo.delete();
                if (isFirst) throw e;
                LOGGER.error("Reload failed for '{}' pass '{}': buffer injection error — {}",
                        resourcePath, pass.name(), e.getMessage());
                return;
            }

            if (System.getProperty("crystalgraphics.material.dumpGlsl") != null) {
                LOGGER.info("=== CgMaterialShader GLSL dump for '" + resourcePath + "' pass '" + pass.name() + "' ===");
                LOGGER.info("--- VERTEX ---\n{}", compiled.vertexSource());
                LOGGER.info("--- FRAGMENT ---\n{}", compiled.fragmentSource());
                LOGGER.info("=== end GLSL dump ===");
            }

            String processedVert = new CgShaderPreprocessor().process(compiled.vertexSource(), resourcePath);
            String processedFrag = new CgShaderPreprocessor().process(compiled.fragmentSource(), resourcePath);

            CgShader newShader = CgShaderFactory.fromSource(processedVert, processedFrag, compiled.vertexFormat());
            if (!newShader.isCompiled()) {
                String err = newShader.getLastCompileError();
                newShader.delete();
                // Abort entire recompile — clean up shaders compiled so far
                for (CgShader s : newShaders) s.delete();
                if (newMatPropsUbo != null) newMatPropsUbo.delete();
                if (isFirst) {
                    LOGGER.error("CgMaterialShader.recompile() failed for '{}' pass '{}': {}",
                            resourcePath, pass.name(), err);
                } else {
                    LOGGER.error("Reload failed for '{}' pass '{}': compile error — {}",
                            resourcePath, pass.name(), err);
                }
                return;
            }

            newShaders.add(newShader);
            newCache.put(new ProgramKey(pass.name(), Collections.emptySet()), newShader);
        }

        // ── Step 6: Shadow auto-generation ────────────────────────────────────────
        if (!attemptShadowAutoGen(parsed, newMatPropsUbo, newCache, newShaders, isFirst)) return;

        // ── Step 6.5: Depth auto-generation ───────────────────────────────────────
        if (!attemptDepthAutoGen(parsed, newMatPropsUbo, newCache, newShaders, isFirst)) return;

        // ── Step 7: On hot-reload, delete all existing variant programs ────────
        if (!isFirst) {
            for (CgShader s : programCache.values()) s.delete();
            programCache.clear();
        }

        // ── Step 8: Commit new state ───────────────────────────────────────────
        programCache.putAll(newCache);

        if (this.matPropsUbo != null) this.matPropsUbo.delete();
        this.matPropsUbo = newMatPropsUbo;
        this.lastParsed = parsed;
        this.renderQueue = parsed.renderQueue();
        this.renderType = parsed.renderType();

        // ── Step 9: Wire all newly compiled programs ───────────────────────────
        for (Map.Entry<ProgramKey, CgShader> entry : newCache.entrySet())
            wireShader(entry.getValue());

        // Increment revision — materials detect this on next bind()
        revisionNumber++;

        if (!isFirst) {
            LOGGER.info("Reloaded '{}'", resourcePath);
        }
    }

    /**
     * Returns the compiled {@link CgShader} for the given pass name and active keyword set,
     * compiling and caching it on the first access.
     *
     * <p>The returned shader is fully linked and wired for the pipeline buffers and the
     * template material properties UBO. Callers ({@link CgMaterial}) must additionally
     * wire any per-instance UBOs after receiving the shader.</p>
     *
     * <p>Must be called after at least one successful {@link #recompile()}.</p>
     *
     * @param passName       the pass to compile ({@link CgParsedPass#name()})
     * @param activeKeywords active feature-flag keyword names (set-equal keys share a cache entry)
     * @return a fully compiled and wired shader; {@code null} on link failure
     */
    public CgShader getOrCompile(String passName, Set<String> activeKeywords) {
        ProgramKey key = new ProgramKey(passName, activeKeywords);
        CgShader cached = programCache.get(key);
        if (cached != null) return cached;

        if (lastParsed == null) {
            LOGGER.error("Cannot compile keyword variant: shader '{}' has not been successfully compiled yet — " +
                    "call recompile() first", resourcePath);
            return null;
        }

        // Find the pass by name in the last parsed result
        CgParsedPass pass = lastParsed.getPassByName(passName);
        if (pass == null) {
            LOGGER.error("Cannot compile variant: pass '" + passName + "' not found in shader '" + resourcePath + "'");
            return null;
        }

        CgMaterialShaderCompiler.CompiledSource compiled = CgMaterialShaderCompiler.compile(
                lastParsed, pass, attachedBuffers, matPropsUbo,
                new CgMaterialShaderCompiler.CompileConfig(activeKeywords));

        String processedVert = new CgShaderPreprocessor().process(compiled.vertexSource(), resourcePath);
        String processedFrag = new CgShaderPreprocessor().process(compiled.fragmentSource(), resourcePath);

        CgShader newShader = CgShaderFactory.fromSource(processedVert, processedFrag, compiled.vertexFormat());
        if (!newShader.isCompiled()) {
            String err = newShader.getLastCompileError();
            newShader.delete();
            LOGGER.error("Failed to compile keyword variant for '" + resourcePath + "' pass '" + passName + "': " + err);
            return null;
        }

        wireShader(newShader);
        programCache.put(key, newShader);
        return newShader;
    }

    /**
     * Convenience: finds the first Forward-lit pass and returns or compiles the keyword variant
     * for that pass.
     *
     * <p>SHADOW and DEPTH variants always use an empty keyword set via
     * {@link #getOrCompile(String, Set)} directly — keywords apply to Forward passes only.</p>
     *
     * @param activeKeywords active feature-flag keyword names
     * @return compiled shader; {@code null} if no Forward pass exists or on link failure
     */
    public CgShader getOrCompileForwardPass(Set<String> activeKeywords) {
        if (lastParsed == null) return null;
        CgParsedPass forwardPass = lastParsed.getPassByLightMode(CgRenderPassVariant.FORWARD.lightModeName());
        if (forwardPass == null) {
            LOGGER.error("'{}': no Forward pass found — cannot compile forward variant", resourcePath);
            return null;
        }
        return getOrCompile(forwardPass.name(), activeKeywords);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    /**
     * Returns the render state for the named pass from the last successful compile.
     * Falls back to {@link CgRenderState#DEFAULT} when the pass is not found.
     *
     * @param passName authored or auto-assigned pass name to look up
     * @return render state for the pass; never {@code null}
     */
    public CgRenderState getRenderState(String passName) {
        if (lastParsed == null) return CgRenderState.DEFAULT;
        CgParsedPass pass = lastParsed.getPassByName(passName);
        return pass != null ? pass.renderState() : CgRenderState.DEFAULT;
    }

    /**
     * Returns {@code true} when the last successful parse includes a pass with the given name.
     *
     * @param passName authored or auto-assigned pass name to check
     */
    public boolean hasParsedPass(String passName) {
        return lastParsed != null && lastParsed.getPassByName(passName) != null;
    }

    /**
     * Returns {@code true} when a compiled GL program exists in the cache for the given pass name
     * with an empty keyword set. Does not trigger compilation.
     *
     * @param passName authored or auto-assigned pass name to check
     */
    public boolean hasCompiledPass(String passName) {
        return programCache.containsKey(new ProgramKey(passName, Collections.emptySet()));
    }

    /** Returns an unmodifiable view of the attached SSBO/TBO and UBO buffers. */
    public List<CgAttachedBuffer> getAttachedBuffers() {
        return Collections.unmodifiableList(attachedBuffers);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Marks this shader dirty so it will be fully recompiled on the next {@code bind()} call.
     * No-op if already deleted.
     */
    public void markDirty() {
        if (!deleted) dirty = true;
    }

    /**
     * Deletes all cached GL shader programs and the template UBO. Idempotent.
     * Called by {@link CgMaterialShaderRegistry#deleteAll()} on context destruction.
     * Do NOT call directly — the registry owns lifecycle.
     */
    public void delete() {
        if (!deleted) {
            deleted = true;
            for (CgShader s : programCache.values()) s.delete();
            programCache.clear();
            if (matPropsUbo != null) {
                matPropsUbo.delete();
                matPropsUbo = null;
            }
        }
    }

    // ── Buffer attachment API ─────────────────────────────────────────────────

    /**
     * Attaches a user-defined SSBO/TBO buffer to this shader for GLSL auto-injection.
     *
     * <p>On the next compile, the buffer's GLSL struct and either an SSBO block or a TBO
     * sampler + fetch function are injected into both vertex and fragment shader source.
     * Access it in GLSL via {@code macroName(n).fieldName}.</p>
     *
     * <p><strong>Ownership warning</strong>: this shader asset may be shared across multiple
     * {@code CgMaterial} instances. Attaching affects all of them — attach before materials
     * are created, or coordinate carefully.</p>
     *
     * @param buffer    the buffer to attach; format must use {@code STD430}
     * @param macroName uppercase GLSL-style identifier, e.g. {@code "FONT_METRICS"}
     * @return {@code this} for fluent chaining
     * @apiNote Never throws — validation failures are logged as warnings and the call becomes a no-op.
     */
    public CgMaterialShader attach(CgShaderBuffer buffer, String macroName) {
        CgAttachedBuffer ab;
        try {
            ab = CgAttachedBuffer.of(buffer, macroName);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("attach() skipped for macro '" + macroName + "': " + e.getMessage());
            return this;
        }
        for (CgAttachedBuffer existing : attachedBuffers) {
            if (existing.isUbo()) continue;
            if (existing.getMacroName().equals(macroName)) {
                LOGGER.warn("attach() skipped: macro name \"{}\" is already attached to this shader.", macroName);
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
    public CgMaterialShader detach(String macroName) {
        if (attachedBuffers.removeIf(ab -> !ab.isUbo() && ab.getMacroName().equals(macroName))) {
            markDirty();
        }
        return this;
    }

    /**
     * Detaches the SSBO/TBO buffer by reference; no-op if not attached.
     *
     * @return {@code this} for fluent chaining
     */
    public CgMaterialShader detach(CgShaderBuffer buffer) {
        if (attachedBuffers.removeIf(ab -> ab.getBuffer() == buffer)) {
            markDirty();
        }
        return this;
    }

    /**
     * Attaches a UBO to this shader for GLSL flat-block auto-injection.
     *
     * <p>On the next compile, a {@code layout(std140) uniform BlockName { ... };} block is
     * injected into both vertex and fragment shader source.</p>
     *
     * @param buffer UBO to attach; format must use {@code STD140}
     * @return {@code this} for fluent chaining
     * @apiNote Never throws — validation failures are logged as warnings and the call becomes a no-op.
     */
    public CgMaterialShader attach(CgUniformBuffer buffer) {
        CgAttachedBuffer ab;
        try {
            ab = CgAttachedBuffer.of(buffer);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("attach(CgUniformBuffer) skipped for '{}': {}",
                    buffer == null ? "null" : buffer.getName(), e.getMessage());
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
    public CgMaterialShader detachUbo(String blockName) {
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
    public CgMaterialShader detach(CgUniformBuffer buffer) {
        if (attachedBuffers.removeIf(ab -> ab.isUbo() && ab.getBuffer() == buffer)) {
            markDirty();
        }
        return this;
    }
    
    private void wireShader(CgShader shader) {
        shader.bind();
        wireShaderBuffers(shader);
        wireShaderSamplers(shader);
        shader.unbind();
    }

    private void wireShaderBuffers(CgShader shader) {
        CgRenderPipeline pipeline = CgRenderPipeline.getInstance();
        pipeline.frameBuffer().wireShader(shader);
        pipeline.objectBuffer().wireShader(shader);
        if (matPropsUbo != null) matPropsUbo.wireShader(shader);
        for (CgAttachedBuffer ab : attachedBuffers) ab.getBuffer().wireShader(shader);
    }

    private void wireShaderSamplers(CgShader shader) {
        int loc = shader.getUniformLocation(CgBindingPoints.DEPTH_TEXTURE_UNIFORM);
        if (loc >= 0) shader.getProgram().setUniform1i(loc, CgBindingPoints.DEPTH_TEXTURE_UNIT);
    }

    /**
     * ── Step 6: Shadow auto-generation ──────────────────────────────────────────
     *
     * <p>Conditions: {@code castShadows=true}, opaque queue, and no explicit ShadowCaster pass.
     * When all conditions are met, compiles a shadow-caster program from the first Forward pass
     * and stores it under {@code ProgramKey("ShadowCaster", emptySet)}.</p>
     *
     * @return {@code true} to proceed with the rest of recompile(); {@code false} to abort
     *         (only on buffer injection error — newShaders and newMatPropsUbo already cleaned up)
     */
    private boolean attemptShadowAutoGen(CgParsedShader parsed, CgUniformBuffer newMatPropsUbo,
                                         Map<ProgramKey, CgShader> newCache, List<CgShader> newShaders,
                                         boolean isFirst) {
        boolean hasExplicitShadow = parsed.getPassByLightMode(CgRenderPassVariant.SHADOW.lightModeName()) != null;
        boolean isOpaque = parsed.renderQueue() < CgRenderQueue.TRANSPARENT_THRESHOLD;

        if (!parsed.castShadows() || !isOpaque || hasExplicitShadow) return true;

        CgParsedPass forwardPass = parsed.getPassByLightMode(CgRenderPassVariant.FORWARD.lightModeName());
        if (forwardPass == null) {
            LOGGER.warn("'{}': castShadows=true but no Forward pass found — shadow auto-gen skipped.",
                    resourcePath);
            return true;
        }

        CgMaterialShaderCompiler.CompiledSource shadowCompiled;
        try {
            shadowCompiled = CgMaterialShaderCompiler.compileShadowAutoGen(parsed, forwardPass,
                    attachedBuffers, newMatPropsUbo, CgMaterialShaderCompiler.CompileConfig.DEFAULT);
        } catch (CgPreprocessorException e) {
            // Non-fatal for hot-reload; fatal for first compile
            for (CgShader s : newShaders) s.delete();
            if (newMatPropsUbo != null) newMatPropsUbo.delete();
            if (isFirst) throw e;
            LOGGER.error("Reload failed for '{}' shadow auto-gen: buffer injection error — {}",
                    resourcePath, e.getMessage());
            return false;
        }

        String shadowVert = new CgShaderPreprocessor().process(shadowCompiled.vertexSource(), resourcePath);
        String shadowFrag = new CgShaderPreprocessor().process(shadowCompiled.fragmentSource(), resourcePath);

        CgShader shadowShader = CgShaderFactory.fromSource(shadowVert, shadowFrag, shadowCompiled.vertexFormat());
        if (!shadowShader.isCompiled()) {
            String err = shadowShader.getLastCompileError();
            shadowShader.delete();
            // Shadow auto-gen failure: non-fatal — log and continue without shadow pass
            LOGGER.error("'" + resourcePath + "' shadow auto-gen failed (continuing without shadow pass): " + err);
        } else {
            newShaders.add(shadowShader);
            newCache.put(new ProgramKey(CgRenderPassVariant.SHADOW.lightModeName(), Collections.emptySet()),
                    shadowShader);
        }
        return true;
    }

    /**
     * ── Step 6.5: Depth auto-generation ─────────────────────────────────────────
     *
     * <p>Conditions: opaque queue and no explicit Depth pass. No {@code castShadows} gate —
     * every opaque material needs a correct depth variant.
     * When all conditions are met, compiles a depth-prepass program from the first Forward pass
     * and stores it under {@code ProgramKey("Depth", emptySet)}.</p>
     *
     * <p>Failure is non-fatal regardless of first/hot-reload: logs an error and continues
     * without a depth variant (the renderer falls back to the engine depth shader).</p>
     *
     * @return {@code true} to proceed with the rest of recompile(); {@code false} to abort
     *         (only on buffer injection error — newShaders and newMatPropsUbo already cleaned up)
     */
    private boolean attemptDepthAutoGen(CgParsedShader parsed, CgUniformBuffer newMatPropsUbo,
                                         Map<ProgramKey, CgShader> newCache, List<CgShader> newShaders,
                                         boolean isFirst) {
        boolean hasExplicitDepth = parsed.getPassByLightMode(CgRenderPassVariant.DEPTH.lightModeName()) != null;
        boolean isOpaque = parsed.renderQueue() < CgRenderQueue.TRANSPARENT_THRESHOLD;

        if (!isOpaque || hasExplicitDepth) return true;

        CgParsedPass forwardPass = parsed.getPassByLightMode(CgRenderPassVariant.FORWARD.lightModeName());
        if (forwardPass == null) {
            LOGGER.warn("'{}': opaque material has no Forward pass — depth auto-gen skipped.", resourcePath);
            return true;
        }

        CgMaterialShaderCompiler.CompiledSource depthCompiled;
        try {
            depthCompiled = CgMaterialShaderCompiler.compileDepthAutoGen(parsed, forwardPass,
                    attachedBuffers, newMatPropsUbo, CgMaterialShaderCompiler.CompileConfig.DEFAULT);
        } catch (CgPreprocessorException e) {
            for (CgShader s : newShaders) s.delete();
            if (newMatPropsUbo != null) newMatPropsUbo.delete();
            if (isFirst) throw e;
            LOGGER.error("Reload failed for '{}' depth auto-gen: buffer injection error — {}",
                    resourcePath, e.getMessage());
            return false;
        }

        String depthVert = new CgShaderPreprocessor().process(depthCompiled.vertexSource(), resourcePath);
        String depthFrag = new CgShaderPreprocessor().process(depthCompiled.fragmentSource(), resourcePath);

        CgShader depthShader = CgShaderFactory.fromSource(depthVert, depthFrag, depthCompiled.vertexFormat());
        if (!depthShader.isCompiled()) {
            String err = depthShader.getLastCompileError();
            depthShader.delete();
            LOGGER.error("'" + resourcePath + "' depth auto-gen failed (continuing without depth variant): " + err);
        } else {
            newShaders.add(depthShader);
            newCache.put(new ProgramKey(CgRenderPassVariant.DEPTH.lightModeName(),
                    Collections.emptySet()), depthShader);
        }
        return true;
    }
}
