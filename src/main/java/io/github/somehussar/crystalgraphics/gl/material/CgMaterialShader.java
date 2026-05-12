package io.github.somehussar.crystalgraphics.gl.material;

import com.github.bsideup.jabel.Desugar;
import io.github.somehussar.crystalgraphics.api.CgBindingPoints;
import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.material.CgAttachedBuffer;
import io.github.somehussar.crystalgraphics.api.material.CgMaterial;
import io.github.somehussar.crystalgraphics.api.material.CgMaterialPipeline;
import io.github.somehussar.crystalgraphics.api.shader.CgPreprocessorException;
import io.github.somehussar.crystalgraphics.api.shader.CgShader;
import io.github.somehussar.crystalgraphics.api.shader.CgShaderPreprocessor;
import io.github.somehussar.crystalgraphics.api.state.CgRenderState;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import io.github.somehussar.crystalgraphics.gl.buffer.shader.CgUniformBuffer;
import io.github.somehussar.crystalgraphics.gl.material.parse.CgMaterialShaderCompiler;
import io.github.somehussar.crystalgraphics.gl.material.parse.CgParsedShader;
import io.github.somehussar.crystalgraphics.gl.material.parse.CgShaderParseException;
import io.github.somehussar.crystalgraphics.gl.material.parse.CgShaderParser;
import io.github.somehussar.crystalgraphics.gl.shader.CgShaderFactory;
import io.github.somehussar.crystalgraphics.util.io.CgIO;
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
 * will share compiled GL programs. Per-instance state (property values, UBO) lives on each {@code CgMaterial}.</p>
 *
 * <p>{@link #getRevisionNumber()} increments on each successful {@link #recompile()}; materials
 * detect recompiles via revision comparison on every {@code bind()} call.</p>
 *
 * <h3>Keyword program cache</h3>
 * <p>Each active-keyword set is compiled lazily on first use and cached in
 * {@link VariantKey} → {@code CgShader} pairs. Call {@link #getOrCompile(Set)}
 * to retrieve or compile a specific combination. The {@link #recompile()} method resets the
 * entire cache and immediately compiles the no-keyword variant.</p>
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
     * Immutable cache key for a compiled keyword variant.
     *
     * <p>Equality is set-based for {@code keywords} — order does not matter.
     * Two keys with {@code {"A","B"}} and {@code {"B","A"}} are equal and share the same
     * cache entry.</p>
     * @param keywords  always unmodifiable LinkedHashSet
     */
    @Desugar
    record VariantKey(Set<String> keywords) {
        VariantKey(Set<String> keywords) {
            this.keywords = Collections.unmodifiableSet(new LinkedHashSet<>(keywords));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof VariantKey)) return false;
            VariantKey other = (VariantKey) o;
            return keywords.equals(other.keywords);
        }
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    /** Resource path to the {@code .shader} file. */
    private final String resourcePath;

    /** Parse result from the last successful compile; {@code null} until first successful compile. */
    private CgParsedShader lastParsed;

    /**
     * Per-(variant, keywords) program cache. Each entry is a fully linked and wired GL program.
     * Cleared and rebuilt from scratch on hot-reload.
     */
    private final Map<VariantKey, CgShader> programCache = new LinkedHashMap<>();

    /**
     * The most recently compiled-or-returned shader from {@link #getOrCompile} or
     * {@link #recompile()}. Updated on every successful compile. {@code null} until
     * first successful compile.
     */
    private CgShader lastBound;

    /**
     * Template UBO created from the parsed properties for GLSL block emission and post-link wiring.
     * Not used for value storage — per-material UBOs handle that.
     * {@code null} for sampler-only shaders (no non-sampler properties).
     */
    private CgUniformBuffer matPropsUbo;

    /**
     * Render state extracted from the parsed {@code RenderState { }} block.
     * -- GETTER --
     * Returns the render state extracted from the last successful compile.
     */
    @Getter
    private CgRenderState renderState = CgRenderState.DEFAULT;

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
     * <p>On success: clears the program cache, compiles the
     * no-keyword variant immediately, then increments
     * {@link #revisionNumber} so materials detect the change on the next {@code bind()} call.
     * Keyword variants are compiled lazily via {@link #getOrCompile}.</p>
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
            LOGGER.error("Reload failed for '{}': could not load source — {}", resourcePath, e.getMessage());
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
            LOGGER.error("Reload failed for '{}': parse error — {}", resourcePath, e.getMessage());
            return;
        }

        this.lastParsed = parsed;
        this.renderState = parsed.renderState();
        this.renderQueue = parsed.renderQueue();

        // ── Step 3: Capability check ───────────────────────────────────────────
        CgCapabilities.ShaderBufferPath path = CgCapabilities.detect().shaderBufferPath();
        if (path == CgCapabilities.ShaderBufferPath.NONE) {
            if (isFirst) throw new UnsupportedOperationException("GL 3.3+ required for CrystalShader materials");
            LOGGER.error("Reload failed for '{}': GL 3.3+ not available", resourcePath);
            return;
        }

        // ── Step 4: Build template UBO for GLSL emission ───────────────────────
        CgMaterialProperties tempProps = new CgMaterialProperties(parsed.properties());
        if (tempProps.hasUboProps()) {
            if (matPropsUbo == null) {
                matPropsUbo = new CgUniformBuffer(CgMaterial.MATERIAL_PROPERTIES_BLOCK, tempProps.buildUboFormat(), 
                        CgBindingPoints.MATERIAL_PROPERTIES_UBO);
            } else {
                matPropsUbo.resetFormat(tempProps.buildUboFormat());
            }
        } else {
            if (matPropsUbo != null) {
                matPropsUbo.delete();
                matPropsUbo = null;
            }
        }

        // ── Step 5: GLSL compile + preprocess (STANDARD variant) ──────────────
        CgMaterialShaderCompiler.CompiledSource compiled;
        try {
            compiled = CgMaterialShaderCompiler.compile(parsed, attachedBuffers, matPropsUbo,
                    CgMaterialShaderCompiler.CompileConfig.DEFAULT);
        } catch (CgPreprocessorException e) {
            if (isFirst) throw e;
            LOGGER.error("Reload failed for '{}': buffer injection error — {}", resourcePath, e.getMessage());
            return;
        }
        String processedVert = new CgShaderPreprocessor().process(compiled.vertexSource(), resourcePath);
        String processedFrag = new CgShaderPreprocessor().process(compiled.fragmentSource(), resourcePath);

        if (System.getProperty("crystalgraphics.material.dumpGlsl") != null) {
            LOGGER.info("=== CgMaterialShader GLSL dump for '{}' ===", resourcePath);
            LOGGER.info("--- VERTEX ---\n{}", processedVert);
            LOGGER.info("--- FRAGMENT ---\n{}", processedFrag);
            LOGGER.info("=== end GLSL dump ===");
        }

        // ── Step 6: GL link ────────────────────────────────────────────────────
        CgShader newShader = CgShaderFactory.fromSource(processedVert, processedFrag, CgVertexFormat.SPATIAL);
        if (!newShader.isCompiled()) {
            String err = newShader.getLastCompileError();
            newShader.delete();
            if (isFirst) {
                LOGGER.error("CgMaterialShader.recompile() failed for '{}': {}", resourcePath, err);
            } else {
                LOGGER.error("Reload failed for '{}': compile error — {}", resourcePath, err);
            }
            return;
        }

        // ── Step 6b: On hot-reload, delete all existing variant programs ───────
        if (!isFirst) {
            for (CgShader s : programCache.values()) s.delete();
            programCache.clear();
            lastBound = null;
        }

        // ── Step 7: wireShader — bind program indices for pipeline buffers ─────
        CgMaterialPipeline pipeline = CgMaterialPipeline.getInstance();
        newShader.bind();

        pipeline.frameBuffer().wireShader(newShader);
        pipeline.objectBuffer().wireShader(newShader);

        if (matPropsUbo != null) matPropsUbo.wireShader(newShader);

        for (CgAttachedBuffer ab : attachedBuffers)
            ab.getBuffer().wireShader(newShader);

        newShader.unbind();

        // ── Store no-keyword variant in cache ─────────────────────────────────
        VariantKey standardKey = new VariantKey(Collections.emptySet());
        programCache.put(standardKey, newShader);
        lastBound = newShader;

        // ── Increment revision — materials detect this on next bind() ──────────
        revisionNumber++;

        if (!isFirst) {
            LOGGER.info("Reloaded '{}'", resourcePath);
        }
    }

    /**
     * Returns the compiled {@link CgShader} for the given active keyword set,
     * compiling and caching it on the first access.
     *
     * <p>The returned shader is fully linked and wired for the pipeline buffers and the
     * template material properties UBO. Callers ({@link CgMaterial}) must additionally
     * wire any per-instance UBOs after receiving the shader.</p>
     *
     * <p>Must be called after at least one successful {@link #recompile()}.</p>
     *
     * @param activeKeywords active feature-flag keyword names (set-equal keys share a cache entry)
     * @return a fully compiled and wired shader; never {@code null}
     * @throws IllegalStateException    if {@link #recompile()} has not completed successfully
     * @throws CgPreprocessorException  if buffer injection fails
     * @throws UnsupportedOperationException if GL 3.3+ is not available
     */
    public CgShader getOrCompile(Set<String> activeKeywords) {
        VariantKey key = new VariantKey(activeKeywords);
        CgShader cached = programCache.get(key);
        if (cached != null) {
            lastBound = cached;
            return cached;
        }

        if (lastParsed == null) {
            LOGGER.error("Cannot compile keyword variant: shader '{}' has not been successfully compiled yet — " +
                            "call recompile() first", resourcePath);
        }

        CgMaterialShaderCompiler.CompiledSource compiled = CgMaterialShaderCompiler.compile(lastParsed,
                attachedBuffers, matPropsUbo, new CgMaterialShaderCompiler.CompileConfig(activeKeywords));

        String processedVert = new CgShaderPreprocessor().process(compiled.vertexSource(), resourcePath);
        String processedFrag = new CgShaderPreprocessor().process(compiled.fragmentSource(), resourcePath);


        CgShader newShader = CgShaderFactory.fromSource(processedVert, processedFrag, CgVertexFormat.SPATIAL);
        if (!newShader.isCompiled()) {
            String err = newShader.getLastCompileError();
            newShader.delete();
            LOGGER.error("Failed to compile keyword variant for '{}': {}", resourcePath, err);
            return null;
        }

        CgMaterialPipeline pipeline = CgMaterialPipeline.getInstance();
        newShader.bind();
        pipeline.frameBuffer().wireShader(newShader);
        pipeline.objectBuffer().wireShader(newShader);
        if (matPropsUbo != null) matPropsUbo.wireShader(newShader);
        for (CgAttachedBuffer ab : attachedBuffers) ab.getBuffer().wireShader(newShader);
        newShader.unbind();

        programCache.put(key, newShader);
        lastBound = newShader;
        return newShader;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    /**
     * Returns the most recently compiled or returned shader program from
     * {@link #getOrCompile} or {@link #recompile()}.
     * {@code null} before the first successful compile.
     */
    public CgShader getShader() {
        return lastBound;
    }

    /** Returns the last successful parse result; {@code null} before first successful compile. */
    public CgParsedShader getParsed() {
        return lastParsed;
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
            lastBound = null;
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
            LOGGER.warn("attach() skipped for macro '{}': {}", macroName, e.getMessage());
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
}
