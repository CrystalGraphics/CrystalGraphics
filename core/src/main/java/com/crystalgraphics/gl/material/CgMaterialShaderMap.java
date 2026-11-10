package com.crystalgraphics.gl.material;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.material.CgAttachedBuffer;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.gl.shader.CgShader;
import com.crystalgraphics.gl.buffer.shader.CgEngineBufferRegistry;
import com.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import com.crystalgraphics.gl.buffer.shader.CgUniformBuffer;
import com.crystalgraphics.gl.material.parse.CgMaterialShaderCompiler;
import com.crystalgraphics.gl.material.parse.CgParsedPass;
import com.crystalgraphics.gl.material.parse.CgParsedShader;
import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.job.CgJobLane;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.io.CgIO;
import com.crystalgraphics.util.trace.CgChannels;
import com.github.bsideup.jabel.Desugar;

import javax.annotation.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The programs compiled from one parse of a {@link CgMaterialShader}: each pass under each keyword set, compiled the
 * first time it is asked for or ahead of that ({@link #precache}), with the template block and engine buffers they were
 * generated against. What it is made from never changes, so a recompile makes a new map and swaps it in whole, and
 * nothing in one is ever stale. Unreal's {@code FMaterialShaderMap}. Render thread, except where a method says.
 */
final class CgMaterialShaderMap {

    private static final Logger LOGGER = LogManager.getLogger("CgMaterialShader");

    /** A program's pass and keyword set; set-equal keyword sets share an entry. */
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

    /** One pass's generated GLSL, and the same preprocessed: what a {@link CgShader} compiles. */
    @Desugar
    record Sources(CgMaterialShaderCompiler.CompiledSource compiled, String vertex, String fragment) {
    }

    /** A variant's sources a worker is making, and the files it read making them. */
    @Desugar
    private record VariantAhead(Set<String> reads, CompletableFuture<Sources> sources) {
    }

    private static final String DEPTH = CgRenderPassVariant.DEPTH.lightModeName();
    private static final ProgramKey DEPTH_KEY = new ProgramKey(DEPTH, Collections.emptySet());

    /** The shader's resource path, or a generated shader's label: what log lines and program labels name. */
    final String label;
    final CgParsedShader parsed;
    /** The properties block the GLSL is generated against; null for a shader with no non-sampler property. */
    @Nullable
    final CgUniformBuffer block;
    /** The engine tokens the parse names ({@code #pragma cg_use}), declared for every program in it. */
    final List<CgAttachedBuffer> buffers;

    /** Every program kept, by pass and keywords. */
    final Map<ProgramKey, CgShader> programs = new LinkedHashMap<>();
    /** The depth pass waits for the first pass that asks for it: the map came from a forward-only compile. */
    boolean autoGenSkipped;

    /** Variants {@link #precache} submitted, not yet kept. */
    private final Map<ProgramKey, CgShader> pendingVariants = new HashMap<>();
    /** Variants that failed, with why: never tried again in this map. */
    private final Map<ProgramKey, String> failedVariants = new HashMap<>();
    /** Variants whose sources a worker is making for {@link #precache}. */
    private final Map<ProgramKey, VariantAhead> variantsAhead = new HashMap<>();

    /**
     * The last programs {@link #get} found, by the identity of the pass name and keyword set a pipeline passes each
     * frame, so a hit builds no {@link ProgramKey}. Valid while {@link #version} is the one it was found at.
     */
    private static final int HITS = 4;
    private final String[] hitPass = new String[HITS];
    private final Object[] hitKeywords = new Object[HITS];
    private final CgShader[] hitShader = new CgShader[HITS];
    private final int[] hitVersion = new int[HITS];
    private int hitNext;
    /** Bumped whenever {@link #programs} changes. */
    private int version;

    private CgMaterialShaderMap(String label, CgParsedShader parsed, @Nullable CgUniformBuffer block,
                                List<CgAttachedBuffer> buffers) {
        this.label = label;
        this.parsed = parsed;
        this.block = block;
        this.buffers = buffers;
    }

    /** An empty map for this parse, its template block and engine buffers made now. Render thread: both are GPU objects. */
    static CgMaterialShaderMap of(String label, CgParsedShader parsed) {
        CgMaterialProperties properties = new CgMaterialProperties(parsed.properties());
        CgUniformBuffer block = properties.hasUboProps() ? new CgUniformBuffer(CgMaterial.MATERIAL_PROPERTIES_BLOCK,
                properties.buildUboFormat(), CgBindingPoints.MATERIAL_PROPERTIES_UBO) : null;
        return new CgMaterialShaderMap(label, parsed, block, engineBuffers(parsed));
    }

    /** The buffers of the engine tokens a parse names. Unknown tokens were refused by the parse. */
    private static List<CgAttachedBuffer> engineBuffers(CgParsedShader parsed) {
        List<CgAttachedBuffer> buffers = new ArrayList<>();
        for (String token : parsed.engineBuffers()) {
            CgEngineBufferRegistry.Provider provider = CgEngineBufferRegistry.get(token);
            CgShaderBuffer buffer = provider.buffer().get();
            boolean held = false;
            for (CgAttachedBuffer existing : buffers) held |= existing.getBuffer() == buffer;
            if (held) continue;
            buffers.add(buffer instanceof CgUniformBuffer ubo ? CgAttachedBuffer.of(ubo)
                    : CgAttachedBuffer.of(buffer, provider.macroName()));
        }
        return Collections.unmodifiableList(buffers);
    }

    /** The pass a shader that does not author one gets generated from its Forward pass. */
    static boolean isAutoGenerated(String passName) {
        return passName.equals(DEPTH);
    }

    /** A program from preprocessed sources: compiling on a worker when {@code defer}, compiled now otherwise. */
    static CgShader build(String label, Sources sources, String pass, boolean defer) {
        CgTrace.marker(CgChannels.GL, "material.compile", label);   // which program a frame compiled
        String name = label + " " + pass;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, defer ? "material.submit" : "material.compileNow")) {
            return defer ? CgShader.submit(sources.vertex(), sources.fragment(), sources.compiled().vertexFormat(), name)
                    : CgShader.compile(sources.vertex(), sources.fragment(), sources.compiled().vertexFormat(), name);
        }
    }

    /**
     * The program for a pass under these keywords, compiled now if nothing made it yet: null, logged, when the pass
     * does not exist or it fails, and null again without a retry until the next map.
     */
    @Nullable
    CgShader get(String passName, Set<String> keywords) {
        for (int i = 0; i < HITS; i++) {
            if (hitPass[i] == passName && hitKeywords[i] == keywords && hitVersion[i] == version) return hitShader[i];
        }
        ProgramKey key = new ProgramKey(passName, keywords);
        CgShader cached = programs.get(key);
        if (cached != null) return remember(passName, keywords, cached);
        CgShader submitted = pendingVariants.remove(key);
        if (submitted != null) {
            if (!submitted.isReady()) CgMaterialShader.waited(label, key);
            return keep(key, submitted);
        }
        if (isAutoGenerated(passName)) {
            ensureAutoGen();
            cached = programs.get(key);
            if (cached != null) return cached;
        }
        if (failedVariants.containsKey(key)) return remember(passName, keywords, null);
        if (!has(passName)) {
            LOGGER.error("Cannot compile variant: pass '" + passName + "' not found in shader '" + label + "'");
            return remember(passName, keywords, null);
        }
        CgMaterialShader.waited(label, key);
        return keep(key, build(label, CgMaterialShader.prepare(source(key), label), key.toString(), false));
    }

    /**
     * Starts compiling a variant without waiting for it, its sources made on a worker first, and says whether
     * {@link #get} would now answer it without compiling or waiting: true once it is kept, or has failed. Polled frame
     * to frame; a generated pass is made by the compile that made the map, never here.
     */
    boolean precache(String passName, Set<String> keywords) {
        ProgramKey key = new ProgramKey(passName, keywords);
        if (programs.containsKey(key) || failedVariants.containsKey(key)) return true;
        CgShader submitted = pendingVariants.get(key);
        if (submitted != null) {
            if (!submitted.isReady()) return false;
            keep(key, pendingVariants.remove(key));
            return true;
        }
        if (isAutoGenerated(passName) && !programs.containsKey(DEPTH_KEY)) return true;
        if (!has(passName)) {
            failedVariants.put(key, "pass '" + passName + "' has no source for this variant");
            return true;
        }
        if (CgMaterialShader.sourcesAhead()) {
            VariantAhead ahead = variantsAhead.get(key);
            if (ahead == null) {
                variantsAhead.put(key, startVariant(key));
                return false;
            }
            if (!ahead.sources().isDone()) return false;
            variantsAhead.remove(key);
            if (!ahead.sources().isCompletedExceptionally()) {
                for (String read : ahead.reads()) CgIO.noteRead(read);
                submit(key, ahead.sources().join());
                return false;
            }
            // It failed: made here, which reports what failed.
        }
        submit(key, CgMaterialShader.prepare(source(key), label));
        return false;
    }

    /** Whether the pass under no keywords is kept. Builds a depth pass a forward-only compile left out. */
    boolean hasCompiled(String passName) {
        if (isAutoGenerated(passName)) ensureAutoGen();
        return programs.containsKey(new ProgramKey(passName, Collections.emptySet()));
    }

    /** Why a variant failed, the compiler's log; null when it compiled or was never tried. */
    @Nullable
    String variantError(String passName, Set<String> keywords) {
        return failedVariants.get(new ProgramKey(passName, keywords));
    }

    /**
     * The depth pass an opaque shader leaves unauthored, generated from its Forward pass and kept under no keywords;
     * null where none is wanted, or where it was compiled now and failed (logged: the engine's depth shader draws
     * instead). Throws the generator's {@code CgPreprocessorException}.
     */
    @Nullable
    CgShader autoGenDepth(boolean defer) {
        if (parsed.renderQueue() >= CgRenderQueue.TRANSPARENT_THRESHOLD || parsed.getPassByLightMode(DEPTH) != null) {
            return null;
        }
        CgParsedPass forward = parsed.getPassByLightMode(CgRenderPassVariant.FORWARD.lightModeName());
        if (forward == null) {
            LOGGER.warn("'{}': opaque material has no Forward pass — depth auto-gen skipped.", label);
            return null;
        }
        CgMaterialShaderCompiler.CompiledSource compiled = CgMaterialShaderCompiler.compileDepthAutoGen(parsed, forward,
                buffers, block, CgMaterialShaderCompiler.CompileConfig.DEFAULT);
        CgShader depth = build(label, CgMaterialShader.prepare(compiled, label), DEPTH, defer);
        if (!defer && !depth.isCompiled()) {
            String err = depth.getLastCompileError();
            depth.delete();
            LOGGER.error("'" + label + "' depth auto-gen failed (continuing without depth variant): " + err);
            return null;
        }
        programs.put(DEPTH_KEY, depth);
        version++;
        return depth;
    }

    /** Drops a program the compile that made the map found failed. */
    void drop(ProgramKey key) {
        CgShader program = programs.remove(key);
        if (program != null) program.delete();
        version++;
    }

    /** Deletes every program, submitted or kept, and the template block. */
    void delete() {
        for (CgShader s : programs.values()) s.delete();
        programs.clear();
        for (CgShader s : pendingVariants.values()) s.delete();
        pendingVariants.clear();
        variantsAhead.clear();
        failedVariants.clear();
        version++;
        if (block != null) block.delete();
    }

    /** Builds the depth pass a forward-only compile left out, the first time a pass asks for one. */
    private void ensureAutoGen() {
        if (!autoGenSkipped) return;
        autoGenSkipped = false;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "material.lateAutoGen")) {
            autoGenDepth(false);
        } catch (RuntimeException e) {
            LOGGER.error("'{}' depth auto-gen failed: {}", label, e.getMessage());
        }
    }

    /** Whether the parse has the pass, or it is the depth pass the compile generated. */
    private boolean has(String passName) {
        return parsed.getPassByName(passName) != null || isAutoGenerated(passName) && programs.containsKey(DEPTH_KEY);
    }

    /** A variant's generated GLSL. Reads only what the map was made from, so safe on a worker. */
    private CgMaterialShaderCompiler.CompiledSource source(ProgramKey key) {
        CgMaterialShaderCompiler.CompileConfig config = new CgMaterialShaderCompiler.CompileConfig(key.keywords());
        CgParsedPass pass = parsed.getPassByName(key.passName());
        if (pass != null) return CgMaterialShaderCompiler.compile(parsed, pass, buffers, block, config);
        CgParsedPass forward = parsed.getPassByLightMode(CgRenderPassVariant.FORWARD.lightModeName());
        return CgMaterialShaderCompiler.compileDepthAutoGen(parsed, forward, buffers, block, config);
    }

    private VariantAhead startVariant(ProgramKey key) {
        Set<String> reads = Collections.synchronizedSet(new HashSet<>());
        return new VariantAhead(reads, CompletableFuture.supplyAsync(() -> {
            try (CgIO.Reads recorded = CgIO.recordReads()) {
                Sources made = CgMaterialShader.prepare(source(key), label);
                reads.addAll(recorded.paths());
                return made;
            }
        }, CgJob.executor(CgJobLane.LATENCY)));
    }

    private void submit(ProgramKey key, Sources sources) {
        pendingVariants.put(key, build(label, sources, key.toString(), true));
    }

    /** A variant's program, compiled (waiting for its compile if it must) and kept; null, logged, if it failed. */
    @Nullable
    private CgShader keep(ProgramKey key, CgShader program) {
        if (!program.isCompiled()) {
            String err = program.getLastCompileError();
            program.delete();
            failedVariants.put(key, String.valueOf(err));
            LOGGER.error("Failed to compile keyword variant for '" + label + "' pass '" + key.passName() + "': " + err);
            return null;
        }
        programs.put(key, program);
        version++;
        return program;
    }

    /** Notes a lookup's answer, a failure's null too, until the map next changes; answers {@code shader}. */
    @Nullable
    private CgShader remember(String passName, Set<String> keywords, @Nullable CgShader shader) {
        int i = hitNext;
        hitNext = (hitNext + 1) % HITS;
        hitPass[i] = passName;
        hitKeywords[i] = keywords;
        hitShader[i] = shader;
        hitVersion[i] = version;
        return shader;
    }
}
