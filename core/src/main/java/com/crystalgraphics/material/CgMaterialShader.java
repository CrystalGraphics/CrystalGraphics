package com.crystalgraphics.material;

import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.render.draw.CgMaterialArena;
import com.crystalgraphics.render.CgWarmBudget;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialState;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.api.shader.CgPreprocessorException;
import com.crystalgraphics.api.shader.CgShaderPreprocessor;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.material.CgMaterialShaderMap.ProgramKey;
import com.crystalgraphics.material.CgMaterialShaderMap.Sources;
import com.crystalgraphics.material.parse.CgMaterialShaderCompiler;
import com.crystalgraphics.material.parse.CgParsedPass;
import com.crystalgraphics.material.parse.CgParsedShader;
import com.crystalgraphics.material.parse.CgShaderParseException;
import com.crystalgraphics.material.parse.CgShaderParser;
import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.job.CgJobLane;
import com.crystalgraphics.mc.CgAssetReloader;
import com.crystalgraphics.mc.CgReloadable;
import com.crystalgraphics.platform.rhi.shader.CgGlslSources;
import com.crystalgraphics.util.io.CgIO;

import javax.annotation.Nullable;
import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import com.crystalgraphics.render.shader.CgBindingModel;

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
 * <h3>The asset and its programs</h3>
 * <p>This is the asset: its source, its parse, what the source reads, hot reload. The programs compiled from one
 * parse are its {@link CgMaterialShaderMap}, which a recompile replaces whole: every pass under no keywords is
 * compiled by the recompile, and each keyword variant the first time {@link #getOrCompile(String, Set)} or
 * {@link #precacheVariant} asks for it.</p>
 *
 * <h3>Lifecycle</h3>
 * <p>Created exclusively by {@link CgMaterialShaderRegistry#getOrCreate(String)}. Deleted by
 * {@link CgMaterialShaderRegistry#deleteAll()} on context destruction. Do not call
 * {@link #delete()} directly — the registry owns the lifecycle.</p>
 */
public final class CgMaterialShader implements CgReloadable {

    private static final Logger LOGGER = LogManager.getLogger("CgMaterialShader");
    private static final CgVar.Bool DUMP_GLSL = CgVar.bool("crystalgraphics.material.dumpGlsl", false, CgVar.DEV,
            "Log every material pass's generated vertex and fragment GLSL as it compiles.");
    private static final CgVar.Bool SOURCES_AHEAD = CgVar.bool("crystalgraphics.material.sourcesAhead", true, CgVar.DEV,
            "A submitted recompile reads, parses and generates its GLSL on a worker first, so the frame that submits "
                    + "only starts its compile; false does all of it on that frame.");
    private static final int COMPILE_WAITS = CgTrace.name("material.compile-waits");
    private static final CgVar.Bool LOG_COMPILE_WAITS = CgVar.bool("crystalgraphics.material.logCompileWaits", false,
            CgVar.DEV, "Log each program a draw had to wait for (material.compile-waits), by shader and pass.");
    private static long compileWaits;

    /**
     * How many times this session a program was asked for before it was built, so its thread compiled it on the spot
     * or waited for its compile: what a material's first draw costs unless something precached it. A precached material's
     * draws add none. Also the trace counter {@code material.compile-waits}.
     *
     * <pre>{@code
     * long before = CgMaterialShader.compileWaits();
     * // ... the frames that should compile nothing ...
     * assert CgMaterialShader.compileWaits() == before;
     * }</pre>
     */
    public static long compileWaits() {
        return compileWaits;
    }


    /** Counts a program a thread had to compile or wait for. */
    static void waited(String label, Object what) {
        if (LOG_COMPILE_WAITS.get()) LOGGER.warn("compile wait: {} {}", label, what);
        compileWaits++;
        CgTrace.add(CgChannels.GL, COMPILE_WAITS, 1);
    }

    /** Whether sources are made on a worker before the frame submits them. */
    static boolean sourcesAhead() {
        return SOURCES_AHEAD.get() && !DUMP_GLSL.get();
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    /** Resource path to the {@code .shader} file. For a generated shader this is a synthetic label
     * ({@code "generated:<hash>"}) rather than anything {@code CgIO} can load — it exists so log lines
     * and parse errors still name something a human can find. */
    @Getter
    private final String resourcePath;

    /**
     * The {@code .shader} source held in memory, for a shader that was <b>generated rather than
     * loaded</b> — a node graph compiles to text, and there is no file behind it.
     *
     * <p>Null for the ordinary resource-backed case, which is the discriminator {@link #isGenerated()}
     * reads. Holding the source rather than a supplier is deliberate: {@link #recompile()} runs again on
     * every hot reload and every keyword variant, and a supplier would let the source change underneath
     * a shader whose identity is supposed to BE that source.</p>
     */
    private final String generatedSource;

    /**
     * The blend and clip its programs are compiled under ({@link CgMaterialState#changesProgram}): applied to every
     * parse, so each is a shader asset of its own beside the shader's. {@link CgMaterialState#SHADER} for the shader's.
     */
    @Getter
    private final CgMaterialState state;

    /** Whether this shader's source came from memory rather than from a resource. */
    public boolean isGenerated() {
        return generatedSource != null;
    }

    /** Parse result from the last successful compile; {@code null} until first successful compile. 
     * -- GETTER --
     * Returns the last successful parse result; 
     *  before first successful compile. 
     */
    @Getter
    private volatile CgParsedShader lastParsed;

    /** {@link #ensureParsed()} found the source unreadable or unparseable; cleared by {@link #markDirty()}. */
    private boolean parseFailed;

    /** The text {@link #lastParsed} was parsed from. */
    private String parsedSource;
    /** Whether the parsed source or anything it includes samples the scene depth; see {@link #readsSceneDepth}. */
    private volatile boolean sceneDepth = true;
    /** Whether it samples the scene colour; see {@link #readsSceneColor}. */
    private volatile boolean sceneColor = true;
    /** Whether it or anything it includes discards; see {@link #discards}. */
    private volatile boolean discards = true;


    /** The programs compiled from the last committed parse; null until a compile has succeeded. */
    @Nullable
    private CgMaterialShaderMap map;
    /** Where its materials' values live ({@link CgParsedShader#valuesInArena}), for the block size it was made for. */
    @Nullable
    private CgMaterialArena arena;
    private boolean warnedUncompiled;

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
     * Whether the shader needs a full recompile on the next {@code bind()} call.
     * Set by {@link #markDirty()}; cleared at the start of {@link #recompile()}.
     * -- GETTER --
     * Returns {@code true} if this shader needs a recompile on the next {@code bind()} call.
     */
    @Getter
    private boolean dirty;

    /**
     * The source of the last SUCCESSFUL compile, {@code #include} expanded — what an unchanged
     * reload is recognised by. Null until one has succeeded. @see #recompile()
     */
    private String lastCompiledSource;

    /**
     * Set when the last {@link #recompile()} attempt failed; cleared by {@link #markDirty()} and by
     * a successful recompile.
     *
     * <p>This is negative caching, and it exists because there was none. {@code CgMaterial}'s two
     * lazy-compile guards ask "is this state still missing?", not "have we already tried?" — so a
     * shader that cannot compile was re-read, re-parsed, re-generated and re-{@code glCompileShader}-ed
     * for <em>every draw of every frame</em>. One broken rounded rect produced 3044 identical error
     * lines in about a second and a 900KB log, which buried the one line that mattered.</p>
     *
     * <p>{@link #markDirty()} clearing it is the part that keeps hot-reload working: without that, a
     * shader that failed once could never be fixed without restarting the game.</p>
     */
    private boolean compileFailed;

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

    private CgMaterialShader(String resourcePath, String generatedSource, CgMaterialState state) {
        this.resourcePath = resourcePath;
        this.generatedSource = generatedSource;
        this.state = state;
    }

    /**
     * Creates a new {@code CgMaterialShader} for the given resource path, marked dirty
     * so it will compile on first use.
     * Called exclusively by {@link CgMaterialShaderRegistry#getOrCreate(String, CgMaterialState)}.
     */
    static CgMaterialShader create(String resourcePath) {
        return create(resourcePath, CgMaterialState.SHADER);
    }

    static CgMaterialShader create(String resourcePath, CgMaterialState state) {
        CgMaterialShader s = new CgMaterialShader(resourcePath, null, state);
        s.markDirty();
        return s;
    }

    /**
     * Creates a shader from source held in memory — what a node graph compiles to.
     *
     * <p>{@code label} is only ever a name: it appears in log lines and is handed to the parser so a
     * syntax error has something to point at. It is not a path and nothing tries to load it.</p>
     *
     * <p>Called exclusively by {@link CgMaterialShaderRegistry#getOrCreateGenerated(String, CgMaterialState)}.</p>
     */
    static CgMaterialShader createGenerated(String label, String source, CgMaterialState state) {
        CgMaterialShader s = new CgMaterialShader(label, source, state);
        s.markDirty();
        return s;
    }

    // ── Compile pipeline ──────────────────────────────────────────────────────

    /**
     * Whether the shader declares {@code #pragma cg_use token}, so reads the fields that engine buffer gives a record
     * meaning: a quad's {@code custom2} is a shape index only to a shader that uses {@code shape}. False for a shader
     * that cannot be parsed.
     */
    public boolean usesEngineBuffer(String token) {
        CgParsedShader parsed = ensureParsed();
        return parsed != null && parsed.engineBuffers().contains(token);
    }

    /**
     * The parse, made now if no compile has made one yet: what a recorder needs to name a pipeline and capture a
     * material's bindings, without the driver. CPU only, and safe on any thread.
     *
     * <pre>{@code
     * CgParsedShader parsed = shader.ensureParsed();
     * if (parsed == null) return;               // unreadable or unparseable: the log says which
     * List<String> features = parsed.featureNames();
     * }</pre>
     *
     * <p>Null after a failure until {@link #markDirty()}, so a broken file is read once, not once per draw.</p>
     */
    @Nullable
    /**
     * The arena its materials' values of {@code floats} floats live in: made at the first ask, and again when a reload
     * changed the block's size. Any thread.
     */
    public synchronized CgMaterialArena arena(int floats) {
        CgMaterialArena a = arena;
        if (a == null || a.stride() != CgMaterialArena.stride(floats)) arena = a = new CgMaterialArena(resourcePath, floats);
        return a;
    }

    public CgParsedShader ensureParsed() {
        CgParsedShader parsed = lastParsed;
        if (parsed != null) return parsed;
        synchronized (this) {
            if (lastParsed != null || parseFailed || deleted) return lastParsed;
        }
        // Read and parsed outside the lock: a worker parsing ahead must not hold up the render thread's compile.
        String source;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "material.loadSource")) {
            source = isGenerated() ? generatedSource : CgIO.loadSource(resourcePath);
        } catch (Exception e) {
            source = null;
        }
        String failure = null;
        if (source == null || source.isEmpty()) {
            failure = "could not load its source";
        } else {
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "material.parse")) {
                parsed = CgShaderParser.parse(source, resourcePath).withState(state, resourcePath);
                requireMargin(parsed, source);
                requireUnits(parsed);
            } catch (CgShaderParseException e) {
                failure = e.getMessage();
            }
        }
        synchronized (this) {
            if (lastParsed != null || deleted) return lastParsed;
            if (failure != null) {
                parseFailed = true;
                LOGGER.error("Cannot parse '{}': {}", resourcePath, failure);
                return null;
            }
            this.renderQueue = parsed.renderQueue();
            this.renderType = parsed.renderType();
            this.sceneDepth = sceneDepthIn(source);
            this.sceneColor = sceneColorIn(source);
            this.discards = discardsIn(source);
            this.parsedSource = source;
            this.lastParsed = parsed;
            return parsed;
        }
    }

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
     * <h3>Depth auto-generation</h3>
     * <p>When no explicit {@code Depth} pass is authored and the shader renders in queue
     * &lt; {@link CgRenderQueue#TRANSPARENT}, a depth-prepass program is auto-generated
     * from the first Forward pass and cached under {@code ProgramKey("Depth", emptySet)}.
     * Every opaque material needs a correct depth variant. Failure is non-fatal: a warning is logged and the
     * engine depth shader fallback is used instead.</p>
     */
    public void recompile() {
        if (!deferring) waited(resourcePath, "every pass");
        // Recorded even when the compile fails: fixing the include that broke it must find this shader again.
        CgIO.Reads recorded = CgIO.recordReads();
        try {
            compile();
        } finally {
            recorded.close();
            // Its own file too: ensureParsed may have read it before this compile.
            Set<String> reads = new HashSet<>(recorded.paths());
            if (!isGenerated() && resourcePath != null) reads.add(resourcePath);
            if (!deleted) CgAssetReloader.track(this, reads);
        }
    }

    /** Marks it dirty: {@link CgAssetReloader} calls this when a file its last compile read changes. */
    @Override
    public void refresh() {
        markDirty();
    }

    private void compile() {
        dirty = false;
        discardPending();
        if (resourcePath == null) return;
        Ahead ahead = takeAhead();
        try {
            compileWith(ahead);
        } finally {
            if (ahead != null) ahead.release();
        }
    }

    /** {@link #compile}, taking what a worker already made where it matches. */
    private void compileWith(@Nullable Ahead ahead) {

        // Read BEFORE the latch below overwrites it: the unchanged-source skip may only stand on a
        // compile that actually succeeded.
        boolean previousCompileFailed = compileFailed;

        // Pessimistic latch: every early return below this line is a failure path. Set here and
        // cleared once at the end rather than at each return, so a new failure branch cannot
        // silently forget to latch and reopen the retry storm. See #compileFailed.
        compileFailed = true;

        boolean isFirst = map == null;

        // ── Step 1: Load source ────────────────────────────────────────────────
        // A generated shader IS its source, so there is nothing to load and nothing that can fail here.
        // Everything downstream — parse, compile, cache, keyword variants, shadow and depth auto-gen —
        // is identical either way, which is the point: a graph-compiled shader is not a second kind of
        // material, it is the same kind that arrived by a different route.
        String source;
        if (ahead != null) {
            source = ahead.landed.source();
            for (String read : ahead.reads()) CgIO.noteRead(read);
        } else if (isGenerated()) {
            source = generatedSource;
        } else try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "material.load")) {
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

        // ── Step 1b: An unchanged source is not a recompile ────────────────────
        // A resource reload marks EVERY material dirty, and Minecraft reloads once at startup as a
        // matter of course -- so every UI shader rebuilt every keyword variant against the driver for
        // a file that had not moved, and said "Reloaded" about each one.
        //
        // Compared with #include EXPANDED, or editing a lib would stop reloading the shaders that
        // include it -- and catching that is what the reload is for. The expanded text is kept rather
        // than a hash of it: a few KB per shader, against a hash collision presenting as "F3+T stopped
        // working on this file", which is not a bug anybody would find twice.
        String expanded;
        if (ahead != null) {
            expanded = ahead.landed.expanded();
        } else try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "material.expandCompare")) {
            expanded = expandedForComparison(source);
        }
        if (!isFirst && !previousCompileFailed && expanded != null && expanded.equals(lastCompiledSource)) {
            compileFailed = false;
            return;
        }

        // ── Step 2: Parse ──────────────────────────────────────────────────────
        // The same text keeps its parse, so a pipeline named before the first compile -- keyed on the parse's
        // render states -- is the one that draws after it.
        CgParsedShader parsed;
        synchronized (this) {   // with ensureParsed, which a recorder may be running
            parsed = lastParsed;
            if ((parsed == null || !source.equals(parsedSource)) && ahead != null && ahead.landed.parsed() != null) {
                parsed = ahead.landed.parsed();
            } else if (parsed == null || !source.equals(parsedSource)) {
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "material.parse")) {
                    parsed = CgShaderParser.parse(source, resourcePath).withState(state, resourcePath);
                    requireMargin(parsed, source);
                    requireUnits(parsed);
                } catch (CgShaderParseException e) {
                    if (isFirst) throw e;
                    LOGGER.error("Reload failed for '" + resourcePath + "': parse error — " + e.getMessage());
                    return;
                }
            }
            // ── Step 2a: commit the pure-parse products immediately (see below) ──
            this.renderQueue = parsed.renderQueue();
            this.renderType = parsed.renderType();
            if (!source.equals(parsedSource)) {
                this.sceneDepth = sceneDepthIn(source);
                this.sceneColor = sceneColorIn(source);
                this.discards = discardsIn(source);
            }
            this.parsedSource = source;
            this.lastParsed = parsed;   // last: a reader that sees it sees the three above
        }

        // ── Step 2a, why ───────────────────────────────────────────────────────
        // These are CPU-side facts about the source text — the declared feature list, the queue, the
        // render type — with no GL dependency whatsoever. Publishing them here, rather than with the
        // compiled programs, decouples "we understand this shader" from "the driver accepted its GLSL":
        // a failed GLSL compile must not hide the declared keywords behind an empty list.

        // ── Step 3: Capability check ───────────────────────────────────────────
        CgCapabilities.ShaderBufferPath bufferPath = CgCapabilities.detect().shaderBufferPath();
        if (bufferPath == CgCapabilities.ShaderBufferPath.NONE) {
            if (isFirst) throw new UnsupportedOperationException("GL 3.3+ required for CrystalShader materials");
            LOGGER.error("Reload failed for '{}': GL 3.3+ not available", resourcePath);
            return;
        }

        // ── Step 4: The new map, its template block and engine buffers ─────────
        CgMaterialShaderMap next = ahead != null ? ahead.takeMap(parsed) : null;
        if (next == null) try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "material.templateUbo")) {
            next = CgMaterialShaderMap.of(resourcePath, parsed);
        }

        // ── Step 5: Compile all declared passes (no-keyword variant) ──────────
        for (CgParsedPass pass : parsed.passes()) {
            Sources made = ahead != null ? ahead.sources(next, pass) : null;
            if (made == null) {
                CgMaterialShaderCompiler.CompiledSource compiled;
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "material.codegen")) {
                    compiled = CgMaterialShaderCompiler.compile(parsed, pass, next.buffers, next.block,
                            CgMaterialShaderCompiler.CompileConfig.DEFAULT);
                } catch (CgPreprocessorException e) {
                    next.delete();
                    if (isFirst) throw e;
                    LOGGER.error("Reload failed for '{}' pass '{}': buffer injection error — {}",
                            resourcePath, pass.name(), e.getMessage());
                    return;
                }
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "material.preprocess")) {
                    made = prepare(compiled, resourcePath);
                }
            }

            if (DUMP_GLSL.get()) {
                LOGGER.info("=== CgMaterialShader GLSL dump for '" + resourcePath + "' pass '" + pass.name() + "' ===");
                LOGGER.info("--- VERTEX ---\n{}", made.compiled().vertexSource());
                LOGGER.info("--- FRAGMENT ---\n{}", made.compiled().fragmentSource());
                LOGGER.info("=== end GLSL dump ===");
            }

            CgShader program = CgMaterialShaderMap.build(resourcePath, made, pass.name(), deferring);
            if (!deferring && !program.isCompiled()) {
                String err = program.getLastCompileError();
                program.delete();
                next.delete();
                // The compiler's own text: its line number maps back through lineOwners to the node that emitted it.
                failed((isFirst ? "Compile failed for '" : "Reload failed for '") + resourcePath
                        + "' pass '" + pass.name() + "': " + err);
                return;
            }
            next.programs.put(new ProgramKey(pass.name(), Collections.emptySet()), program);
        }

        // ── Step 6: Depth auto-generation, waiting for the first pass that asks in a forward-only compile ──
        CgShader generated = null;
        if (!forwardOnly) try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "material.depthAutoGen")) {
            generated = next.autoGenDepth(deferring);
        } catch (CgPreprocessorException e) {
            next.delete();
            if (isFirst) throw e;
            LOGGER.error("Reload failed for '{}' depth auto-gen: buffer injection error — {}", resourcePath, e.getMessage());
            return;
        }

        if (deferring) {
            pendingCommit = new PendingCommit(next, expanded, isFirst, generated, forwardOnly);
            return;
        }
        commit(next, expanded, isFirst, forwardOnly);
    }

    /** Set for the length of a forward-only {@link #submitRecompile}. */
    private boolean forwardOnly;

    /** Step 7: the new map's programs replace the old map. */
    private void commit(CgMaterialShaderMap next, String expanded, boolean isFirst, boolean skippedAutoGen) {
        next.autoGenSkipped = skippedAutoGen;
        if (map != null) map.delete();
        map = next;
        // lastParsed / renderQueue / renderType were committed in Step 2a — they are parse products,
        // not compile products, and must survive a GLSL failure.
        compileFailed = false;
        lastCompileError = null;
        lastCompiledSource = expanded;
        // Materials detect this on their next bind().
        revisionNumber++;
        if (!isFirst) LOGGER.info("Reloaded '{}'", resourcePath);
    }

    /**
     * {@link #recompile}, returning before anything is compiled: every program is submitted, and
     * {@link #pollPending} commits them once all are done. What was compiled before keeps serving until then.
     *
     * <pre>{@code
     * shader.submitRecompile();
     * ... later frames ...
     * if (shader.pollPending()) draw();   // committed, or failed with lastCompileError set
     * }</pre>
     *
     * <p>Anything that asks for a program first waits for the pending one ({@link #awaitPending}), so a caller
     * that binds early pays today's cost rather than drawing half a commit.</p>
     *
     * <p>The sources are made on a worker first: the first calls start it and return with the shader still dirty, and
     * the call that finds them made submits. Poll it while {@link #isDirty()}, as a warm-up does.</p>
     */
    public void submitRecompile() {
        submitRecompile(false);
    }

    /**
     * {@link #submitRecompile()}; with {@code forwardOnly}, the shadow and depth auto-generation wait for the first
     * pass that asks for one, for a caller that only ever binds the Forward pass.
     */
    public void submitRecompile(boolean forwardOnly) {
        if (!sourcesReady()) {
            carryOn(forwardOnly);
            return;
        }
        carried = false;
        deferring = true;
        this.forwardOnly = forwardOnly;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "material.submitRecompile")) {
            recompile();
        } finally {
            deferring = false;
            this.forwardOnly = false;
        }
    }

    /**
     * Commits a {@link #submitRecompile} once every program in it has compiled.
     *
     * @return {@code true} when nothing is pending any more: committed, failed, or never submitted
     */
    public boolean pollPending() {
        PendingCommit pending = pendingCommit;
        if (pending == null) return true;
        for (CgShader shader : pending.map().programs.values()) {
            if (!shader.isReady()) return false;
        }
        pendingCommit = null;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "material.commit")) {
            finish(pending);
        }
        return true;
    }

    /** Whether a {@link #submitRecompile} is waiting to be committed. Commits nothing. */
    public boolean isPending() {
        return pendingCommit != null;
    }

    /** Commits whatever {@link #submitRecompile} left pending, waiting for its compiles if it must. */
    public void awaitPending() {
        PendingCommit pending = pendingCommit;
        if (pending == null) return;
        pendingCommit = null;
        for (CgShader shader : pending.map().programs.values()) {
            if (!shader.isReady()) {
                waited(resourcePath, "pending commit");
                break;
            }
        }
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "material.awaitPending")) {
            finish(pending);
        }
    }


    /** Verifies each submitted program; a failed pass fails the recompile, a failed auto-generated one is dropped. */
    private void finish(PendingCommit pending) {
        CgMaterialShaderMap next = pending.map();
        for (Map.Entry<ProgramKey, CgShader> entry : new HashMap<>(next.programs).entrySet()) {
            CgShader shader = entry.getValue();
            if (shader.isCompiled()) continue;
            String err = shader.getLastCompileError();
            if (shader == pending.generated()) {
                next.drop(entry.getKey());
                LOGGER.error("'" + resourcePath + "' " + entry.getKey().passName()
                        + " auto-gen failed (continuing without it): " + err);
                continue;
            }
            next.delete();
            failed((pending.isFirst() ? "Compile failed for '" : "Reload failed for '") + resourcePath
                    + "' pass '" + entry.getKey().passName() + "': " + err);
            return;
        }
        commit(next, pending.expanded(), pending.isFirst(), pending.forwardOnly());
    }

    /** Drops a pending submit that a newer compile supersedes. */
    private void discardPending() {
        PendingCommit pending = pendingCommit;
        if (pending == null) return;
        pendingCommit = null;
        pending.map().delete();
    }

    /** Set for the length of a {@link #submitRecompile}. */
    private boolean deferring;

    /** What a {@link #submitRecompile} commits once its programs are done. */
    @Nullable
    private PendingCommit pendingCommit;

    private record PendingCommit(CgMaterialShaderMap map, String expanded, boolean isFirst,
                                 @Nullable CgShader generated, boolean forwardOnly) {
    }


    /**
     * This source with every {@code #include} pulled in, or null when it cannot be expanded.
     *
     * <p>Null is not a failure here — it means "compare nothing", so the compile runs and reports the
     * unresolvable include itself, with a message and a path this method has no business duplicating.</p>
     */
    private String expandedForComparison(String source) {
        if (isGenerated()) return source;
        try {
            return new CgShaderPreprocessor().process(source, resourcePath);
        } catch (RuntimeException cannotExpand) {
            return null;
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
        awaitPending();
        CgMaterialShaderMap current = map;
        if (current == null) {
            // A failed compile was logged; asked before any compile, said once.
            if (!compileFailed && !warnedUncompiled) {
                warnedUncompiled = true;
                LOGGER.error("Cannot compile variant: shader '{}' has not been compiled yet", resourcePath);
            }
            return null;
        }
        return current.get(passName, activeKeywords);
    }


    /**
     * Starts compiling {@code passName} (null for the Forward pass) with {@code activeKeywords} without waiting for the
     * driver, and says whether {@link #getOrCompile} would now return it without compiling or waiting: true once it is
     * built, or has failed. Polled frame to frame by a caller warming what it will draw; a generated pass is built by
     * the commit, never here. Render thread, once the shader has compiled.
     */
    public boolean precacheVariant(@Nullable String passName, Set<String> activeKeywords) {
        if (isDirty() || !pollPending()) return false;
        CgMaterialShaderMap current = map;
        if (current == null) return true;
        if (passName == null) {
            CgParsedPass forward = current.parsed.getPassByLightMode(CgRenderPassVariant.FORWARD.lightModeName());
            if (forward == null) return true;
            passName = forward.name();
        }
        return current.precache(passName, activeKeywords);
    }


    /**
     * Why the variant of {@code passName} under {@code keywords} failed to compile since the last recompile, the
     * driver's or shaderc's log; null when it compiled or was never tried. What a tool reports beside the log.
     *
     * <pre>{@code
     * if (shader.getOrCompile("Pass0", Set.of("POINT_LIT")) == null) report(shader.variantError("Pass0", Set.of("POINT_LIT")));
     * }</pre>
     */
    @Nullable
    public String variantError(String passName, Set<String> keywords) {
        CgMaterialShaderMap current = map;
        return current == null ? null : current.variantError(passName, keywords);
    }


    /**
     * Convenience: finds the first Forward-lit pass and returns or compiles the keyword variant
     * for that pass.
     *
     * <p>DEPTH variants always use an empty keyword set via
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
        awaitPending();
        return map != null && map.hasCompiled(passName);
    }

    /**
     * The buffers of the engine tokens the committed parse names ({@code #pragma cg_use}): what a capture binds beside
     * the material's own. Empty until a compile has succeeded.
     */
    public List<CgAttachedBuffer> engineBuffers() {
        CgMaterialShaderMap current = map;
        return current == null ? Collections.emptyList() : current.buffers;
    }


    // ── Sources made ahead, on a worker ─────────────────────────────────────────

    /** Submits waiting on their worker, carried on once a frame by {@link #advanceSubmits}. Render thread. */
    private static final ArrayList<CgMaterialShader> CARRIED = new ArrayList<>();

    /** In {@link #CARRIED}, to submit forward-only or not. */
    private boolean carried, carriedForwardOnly;

    private void carryOn(boolean forwardOnly) {
        carriedForwardOnly = forwardOnly;
        if (carried) return;
        carried = true;
        CARRIED.add(this);
    }

    /**
     * Carries each submit whose sources a worker is making one hop on, and submits those made, inside the frame's
     * {@link CgWarmBudget}: a caller that asked once ({@code precache()} on a loading screen) gets its programs
     * submitted without asking again. Once a frame, from {@code tickFrame}; allocates nothing.
     */
    public static void advanceSubmits() {
        for (int i = CARRIED.size() - 1; i >= 0; i--) {
            CgMaterialShader shader = CARRIED.get(i);
            if (shader.carried && !shader.deleted && shader.dirty && CgWarmBudget.allows()) {
                long start = System.nanoTime();
                try {
                    shader.submitRecompile(shader.carriedForwardOnly);
                } finally {
                    CgWarmBudget.spend(System.nanoTime() - start);
                }
            }
            if (!shader.carried || shader.deleted || !shader.dirty) {
                shader.carried = false;
                CARRIED.remove(i);
            }
        }
    }

    /** Bumped by {@link #markDirty()}: sources a worker started before it read files that have changed since. */
    private int generation;

    /** The sources a worker is making for the next {@link #submitRecompile}, or has made. */
    @Nullable
    private Ahead ahead;

    /** What a worker reads before anything is generated: the source, expanded for the unchanged check, parsed. */
    private record Front(String source, @Nullable String expanded, @Nullable CgParsedShader parsed, Set<String> reads) {
    }

    /** Every pass's sources by name, and what preprocessing them read. */
    private record Passes(Map<String, Sources> sources, Set<String> reads) {
    }

    /**
     * A recompile's CPU work made off the frame, in two hops: the worker reads and parses; the frame attaches the
     * engine buffers and makes the template block; the worker generates and preprocesses every pass. A compile takes
     * each part only where it was made from what it would use itself, and makes the rest as before.
     */
    private static final class Ahead {
        final int generation;
        final CompletableFuture<Front> front;
        Front landed;
        /** The map the passes are generated for; the compile's once {@link #takeMap} hands it over. */
        CgMaterialShaderMap map;
        boolean taken;
        CompletableFuture<Passes> passes;

        Ahead(int generation, CompletableFuture<Front> front) {
            this.generation = generation;
            this.front = front;
        }

        /** The map made for this parse, now the compile's; null where it was made for another. */
        @Nullable
        CgMaterialShaderMap takeMap(CgParsedShader parsed) {
            if (map == null || map.parsed != parsed) return null;
            taken = true;
            return map;
        }

        /** A pass's sources, where they were generated for this map; null otherwise. */
        @Nullable
        Sources sources(CgMaterialShaderMap next, CgParsedPass pass) {
            if (passes == null || map != next) return null;
            if (!passes.isDone() || passes.isCompletedExceptionally()) return null;
            return passes.join().sources().get(pass.name());
        }

        /** Every file the worker read for this compile: the reload index tracks them as if the frame had read them. */
        Set<String> reads() {
            if (passes == null || !passes.isDone() || passes.isCompletedExceptionally()) return landed.reads();
            Set<String> all = new HashSet<>(landed.reads());
            all.addAll(passes.join().reads());
            return all;
        }

        void release() {
            if (map != null && !taken) map.delete();
            map = null;
        }
    }


    /**
     * Moves the sources a worker makes one hop on, and says whether a submit may go ahead: true once they are made,
     * once making them failed (the compile then makes them itself, and reports the failure as it always has), or
     * where nothing is made ahead.
     */
    private boolean sourcesReady() {
        if (!SOURCES_AHEAD.get() || DUMP_GLSL.get() || resourcePath == null) return true;
        Ahead a = ahead;
        if (a != null && a.generation != generation) {
            a.release();
            ahead = a = null;
        }
        if (a == null) {
            ahead = startAhead();
            return false;
        }
        if (a.passes != null) return a.passes.isDone();
        if (!a.front.isDone()) return false;
        if (a.front.isCompletedExceptionally()) return true;
        startPasses(a, a.front.join());
        return false;
    }

    /** The first hop, on a worker: the source read, expanded and parsed, recording what it read. */
    private Ahead startAhead() {
        CgCapabilities.detect();   // probed here, so the worker reads the cache
        String generated = isGenerated() ? generatedSource : null;
        String path = resourcePath;
        String known;
        synchronized (this) {
            known = lastParsed != null ? parsedSource : null;
        }
        return new Ahead(generation, CompletableFuture.supplyAsync(() -> {
            try (CgIO.Reads reads = CgIO.recordReads()) {
                String source = generated != null ? generated : CgIO.loadSource(path);
                if (source == null || source.isEmpty()) throw new IllegalStateException("no source: " + path);
                String expanded = expandedForComparison(source);
                CgParsedShader parsed = null;
                if (!source.equals(known)) {
                    parsed = CgShaderParser.parse(source, path).withState(state, path);
                    requireMargin(parsed, source);
                    requireUnits(parsed);
                }
                return new Front(source, expanded, parsed, new HashSet<>(reads.paths()));
            }
        }, CgJob.executor(CgJobLane.LATENCY)));
    }

    /** Between the hops, on the frame: the map made (its block and buffers are GPU objects), then every pass on a worker. */
    private void startPasses(Ahead a, Front front) {
        a.landed = front;
        CgParsedShader parsed;
        synchronized (this) {
            parsed = front.parsed() != null ? front.parsed() : lastParsed;
        }
        if (parsed == null) {
            a.passes = CompletableFuture.completedFuture(new Passes(Collections.emptyMap(), Collections.emptySet()));
            return;
        }
        CgMaterialShaderMap made = a.map = CgMaterialShaderMap.of(resourcePath, parsed);
        String path = resourcePath;
        a.passes = CompletableFuture.supplyAsync(() -> {
            try (CgIO.Reads reads = CgIO.recordReads()) {
                Map<String, Sources> sources = new HashMap<>();
                for (CgParsedPass pass : parsed.passes()) {
                    sources.put(pass.name(), prepare(CgMaterialShaderCompiler.compile(parsed, pass, made.buffers,
                            made.block, CgMaterialShaderCompiler.CompileConfig.DEFAULT), path));
                }
                return new Passes(sources, new HashSet<>(reads.paths()));
            }
        }, CgJob.executor(CgJobLane.LATENCY));
    }


    /** The sources made for this compile, or null where none are or they are stale; the caller releases them. */
    @Nullable
    private Ahead takeAhead() {
        Ahead a = ahead;
        ahead = null;
        if (a == null) return null;
        if (a.generation != generation || a.landed == null) {
            a.release();
            return null;
        }
        return a;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Marks this shader dirty so it will be fully recompiled on the next {@code bind()} call.
     * No-op if already deleted.
     */
    public void markDirty() {
        if (!deleted) {
            dirty = true;
            generation++;
            // Clear the failure latch — a hot-reload is exactly the event that might fix it, and a
            // latch that survived one would make a broken shader unrecoverable without a restart.
            //
            // THE REASON IS NOT CLEARED WITH IT. Marking dirty says "try again", not "the last attempt
            // succeeded", and until a new attempt supersedes it the previous verdict is still the truth.
            // Clearing it here lost the message while the shader was still broken: the graph editor reads
            // it only after a compile, so once it went null it stayed null and the Problems panel dropped
            // an error that was still live. Only recompile() clears it, on the path where it is earned.
            compileFailed = false;
            parseFailed = false;
        }
    }

    /**
     * Returns {@code true} when the last {@link #recompile()} attempt failed and nothing has marked
     * this shader dirty since.
     *
     * <p>Callers that lazily trigger a compile must consult this, or they re-attempt a known-failing
     * compile on every call. See {@link #compileFailed}.</p>
     */
    /**
     * What the last failed compile said, or null when the last attempt succeeded.
     *
     * <h3>A boolean was not enough, and the graph is why</h3>
     *
     * <p>{@link #hasCompileFailed()} tells a caller to stop retrying, which is all the engine needed: a
     * shader that will not compile is a bug in a shipped asset, and the log line is for whoever fixes it.
     * A <b>generated</b> shader is different. Its source was produced from a node graph by a user who
     * never typed a line of it, and the driver's message — with a line number that maps back through
     * {@code lineOwners} to the node that emitted it — is the only thing that turns "the preview is blank"
     * into somewhere to go and look.</p>
     *
     * <p>Recorded rather than only logged for that reason. It is one string on a failure path, and the
     * alternative is that the one consumer who can act on it has to read the log.</p>
     */
    @Nullable
    public String lastCompileError() {
        return lastCompileError;
    }

    @Nullable
    private String lastCompileError;

    /** Latches a failure and its reason together, so the two cannot disagree. @see #lastCompileError() */
    private void failed(String reason) {
        compileFailed = true;
        lastCompileError = reason;
        LOGGER.error(reason);
    }

    public boolean hasCompileFailed() {
        return compileFailed;
    }

    /**
     * Deletes all cached GL shader programs and the template UBO. Idempotent.
     * Called by {@link CgMaterialShaderRegistry#deleteAll()} on context destruction.
     * Do NOT call directly — the registry owns lifecycle.
     */
    public void delete() {
        if (!deleted) {
            deleted = true;
            CgAssetReloader.untrack(this);
            discardPending();
            if (map != null) map.delete();
            map = null;
        }
    }

    // ── What it reads of the scene ──────────────────────────────────────────────

    /**
     * Whether {@code program}, one of this shader's, samples {@code cg_DepthBuffer}: read from the compiled program's
     * layout ({@link CgShader#sceneReads}), so a keyword variant answers for itself. {@link #readsSceneDepth()} for a
     * program that did not compile.
     *
     * <pre>{@code
     * CgShader program = shader.getOrCompile("Pass0", keywords);
     * if (program != null && shader.readsSceneDepth(program)) copies |= CgTargetCopy.DEPTH;
     * }</pre>
     */
    public boolean readsSceneDepth(CgShader program) {
        return program.isCompiled() ? (program.sceneReads() & CgShader.READS_DEPTH) != 0 : readsSceneDepth();
    }

    /** As {@link #readsSceneDepth(CgShader)}, for {@code cg_SceneColor}. */
    public boolean readsSceneColor(CgShader program) {
        return program.isCompiled() ? (program.sceneReads() & CgShader.READS_COLOR) != 0 : readsSceneColor();
    }

    /**
     * Whether the source may sample {@code cg_DepthBuffer}, directly or through {@code CG_SCENE_EYE_DEPTH}, in its own
     * text, a file it includes or any keyword's code; true until parsed. What a caller with no linked program goes by:
     * a program answers exactly through {@link #readsSceneDepth(CgShader)}.
     */
    public boolean readsSceneDepth() {
        return parsedSource == null || sceneDepth;
    }

    private boolean sceneDepthIn(String source) {
        return new CgShaderPreprocessor().mentions(source, resourcePath, "cg_DepthBuffer", "CG_SCENE_EYE_DEPTH",
                "cg_DepthFade");
    }

    /** As {@link #readsSceneDepth()}, for {@code cg_SceneColor} or {@code CG_SCENE_COLOR}. */
    public boolean readsSceneColor() {
        return parsedSource == null || sceneColor;
    }

    /**
     * How far past its geometry it samples {@code cg_SceneColor}, a share of the target's height: its
     * {@code "SceneColorMargin"} tag. NaN until parsed and for a shader that does not read it.
     */
    public float sceneColorMargin() {
        CgParsedShader parsed = lastParsed;
        return parsed == null ? Float.NaN : parsed.sceneColorMargin();
    }

    /** A shader reading {@code cg_SceneColor} states how far past its geometry it samples, or does not parse. */
    private void requireMargin(CgParsedShader parsed, String source) {
        if (Float.isNaN(parsed.sceneColorMargin()) && sceneColorIn(source)) {
            throw new CgShaderParseException("[" + resourcePath + "] samples the scene's colour without a SceneColorMargin: "
                    + "the share of the target's height it samples past its geometry, " + (parsed.domain() != null
                    ? "e.g. Material { SceneColorMargin 0.05 } (0.001 for its own pixel alone)"
                    : "in the top-level Tags, e.g. Tags { \"RenderType\" = \"Transparent\" \"SceneColorMargin\" = \"0.05\" }"));
        }
    }

    /** A shader binds at most {@link CgBindingModel#MATERIAL_TEXTURES} textures: its set's room before a kernel's buffers. */
    private void requireUnits(CgParsedShader parsed) {
        int samplers = 0;
        for (CgMaterialProperty property : parsed.properties()) {
            if (property.getType().isSampler()) samplers++;
        }
        if (samplers > CgBindingModel.MATERIAL_TEXTURES) {
            throw new CgShaderParseException("[" + resourcePath + "] declares " + samplers + " samplers; a material binds "
                    + "at most " + CgBindingModel.MATERIAL_TEXTURES);
        }
    }

    private boolean sceneColorIn(String source) {
        return new CgShaderPreprocessor().mentions(source, resourcePath, "cg_SceneColor", "CG_SCENE_COLOR");
    }

    /**
     * Whether the parsed source or a file it includes names {@code discard} or {@code cg_Clip}, used or not; true
     * until parsed. False means every fragment it shades is written.
     */
    public boolean discards() {
        return parsedSource == null || discards;
    }

    private boolean discardsIn(String source) {
        return new CgShaderPreprocessor().mentions(source, resourcePath, "discard", "cg_Clip");
    }

    // ── The source a compiler takes ────────────────────────────────────────────────

    /** A pass's generated GLSL preprocessed, as a compiler takes it. Any thread. */
    static Sources prepare(CgMaterialShaderCompiler.CompiledSource compiled, String path) {
        return new Sources(compiled,
                CgGlslSources.ascii(new CgShaderPreprocessor().process(compiled.vertexSource(), path)),
                CgGlslSources.ascii(new CgShaderPreprocessor().process(compiled.fragmentSource(), path)));
    }
}
