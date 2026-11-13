package com.crystalgraphics.render.draw;

import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.material.CgShader;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.material.CgMaterialShader;
import com.crystalgraphics.material.parse.CgParsedPass;
import com.crystalgraphics.material.parse.CgParsedShader;
import com.crystalgraphics.material.parse.CgPermutationDomain;

import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipeline;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineCache;
import com.crystalgraphics.platform.rhi.shader.CgGlslCompiler;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;
import com.crystalgraphics.render.CgWarmBudget;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.variable.CgVar;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything that ends a batch and is not data: a shader's pass and keyword variant, its forms
 * ({@link CgPipelineForm}), the render state that pass declares, and the kind of instance it reads. A key, interned to
 * an {@code int} so a batch compares integers, and made with no GL at all: the program behind it is resolved on the
 * render thread when a batch draws with it. A draw's bases travel in the draw ({@code CgMeshStore}), so one pipeline
 * draws a mesh alone or joined into a multi-draw.
 *
 * <pre>{@code
 * CgPipeline p = material.pipeline(CgInstanceKind.QUAD);       // on any thread; compiles nothing
 * CgPipeline prepass = p.as(CgPipelineForm.DEPTH);              // a form of it, or null where it has none
 *
 * // at execution, on the render thread:
 * if (p.bind()) {                                               // compiles the variant on first use
 *     CgMeshStore.get().draw(mesh, instances, first);
 * }
 * }</pre>
 *
 * <p>A caller that must not wait on the driver asks {@link #precache()} first, frame by frame, and draws once it
 * answers true.</p>
 *
 * <ul>
 *   <li>The render state's unset domains are the pass's: an executor applies its pass's state, then this one's.</li>
 *   <li>A shader reloaded in place keeps its pipelines: {@link #program()} follows the shader's revision.</li>
 *   <li>Lives for the session. Interning is thread-safe; {@link #program()}, {@link #bind()} and
 *       {@link #precache()} are render thread only.</li>
 * </ul>
 */
public final class CgPipeline {

    private static final Logger LOGGER = LogManager.getLogger("CgPipeline");
    private static final int SUBMIT_ZONE = CgTrace.name("pipeline.precache.submit"),
            POLL_ZONE = CgTrace.name("pipeline.precache.poll"), VARIANT_ZONE = CgTrace.name("pipeline.precache.variant");
    private static final int PRECACHED = CgTrace.name("pipeline.precached"), MISSED = CgTrace.name("pipeline.missed"),
            TOO_LATE = CgTrace.name("pipeline.too-late"), HITCHES = CgTrace.name("pipeline.hitches");
    private static final CgVar.Bool LOG_MISSES = CgVar.bool("crystalgraphics.pipeline.logMisses", false, CgVar.DEV,
            "Log each pipeline a draw found unbuilt (pipeline.missed, pipeline.too-late), with its shader, pass and keywords.");
    /** Unreal's {@code r.PSO.RuntimeCreationHitchThreshold}. */
    private static final long HITCH_NANOS = 20_000_000L;
    private static long missed, tooLate, hitches;
    private static final ConcurrentHashMap<Key, CgPipeline> INTERNED = new ConcurrentHashMap<>();
    private static final Object REGISTERING = new Object();
    private static volatile CgPipeline[] byId = new CgPipeline[64];
    private static int count;
    /** {@link #device}'s pipelines, on the device {@link #deviceCacheOf}. */
    private static CgPipelineCache deviceCache;
    private static CgRHI deviceCacheOf;

    private final int id;
    private final CgMaterialShader shader;
    private final CgRenderPassVariant pass;
    /** The Forward pass it draws by name, or null for its variant's own pass. */
    @Nullable
    private final String passName;
    private final Set<String> keywords;
    private final CgRenderState state;
    private final CgInstanceKind kind;
    /** Its {@link CgPipelineForm}s, a bit each. */
    private final int forms;
    /** What the program is compiled with: the pass's keywords, and its forms'. */
    private final Set<String> compiled;
    /** {@link #as} answers, by form. */
    private final CgPipeline[] twins = new CgPipeline[CgPipelineForm.ALL.length];
    /** {@link #slotWrites()}, for the shader revision {@link #slotsRevision}. */
    private int slots, slotsRevision = -1;

    @Nullable
    private CgShader program;
    private int programRevision = -1;
    /** The shader revision {@link #precache} last answered true at: a built pipeline spends no warming budget. */
    private int builtRevision = -1;
    /** {@link #device}'s last answer and what it was asked: a draw asks again with the same pass. */
    private CgGraphicsPipeline deviceLast;
    private CgRenderState deviceLastPass;
    private CgGraphicsPipelineDesc.Topology deviceLastTopology;
    private List<CgFormat> deviceLastColors;
    private CgFormat deviceLastDepth;
    private int deviceLastSamples;
    private boolean deviceLastReversed;
    private CgShader deviceLastProgram;
    private CgRHI deviceLastOf;
    /** Whether a miss of a shader with a permutation domain was logged: once a pipeline. */
    private boolean domainWarned;

    /**
     * A shader declaring a permutation domain owns its precache: a miss means nobody added the shader, or the draw binds
     * a set its domain does not span. Logged once, whatever {@code logMisses} says.
     */
    private void warnOutsideDomain() {
        if (domainWarned) return;
        CgParsedShader parsed = shader.ensureParsed();
        CgPermutationDomain domain = parsed == null ? CgPermutationDomain.EMPTY : parsed.permutations();
        if (domain.dimensions().isEmpty()) return;
        domainWarned = true;
        for (List<String> d : domain.dimensions()) {
            int on = 0;
            for (String k : d) if (keywords.contains(k)) on++;
            if (on > 1) {
                LOGGER.error("pipeline {}: binds {} together, outside its switched keywords {}", this, keywords,
                        String.join("|", d));
                return;
            }
        }
        LOGGER.warn("pipeline {}: missed at its draw; its shader switches keywords, so add the shader to a "
                + "precache collector (CgPipelineList.add or addPass)", this);
    }

    /** The shader revision {@link #precache} was last asked at: a draw finding it unbuilt is too late, not missed. */
    private int requestedRevision = -1;

    /** {@link #withState} answers, by state identity. */
    private CgPipeline[] derived = new CgPipeline[2];
    private CgRenderState[] derivedStates = new CgRenderState[2];
    private int derivedCount;

    private CgPipeline(int id, Key key) {
        this.id = id;
        this.shader = key.shader;
        this.pass = key.pass;
        this.passName = key.passName;
        this.keywords = key.keywords;
        this.state = key.state;
        this.kind = key.kind;
        this.forms = key.forms;
        Set<String> passKeywords = takesKeywords(pass) ? keywords : Collections.emptySet();
        if (forms != 0) {
            Set<String> more = new TreeSet<>(passKeywords);
            for (CgPipelineForm form : CgPipelineForm.ALL) if (has(form)) more.add(form.keyword);
            passKeywords = Collections.unmodifiableSet(more);
        }
        this.compiled = passKeywords;
    }

    /**
     * The pipeline for this variant, state and kind, made on first use.
     *
     * @param keywords the enabled keywords; ignored for any pass but
     *                 {@link CgRenderPassVariant#FORWARD}, {@link CgRenderPassVariant#EMISSIVE} and
     *                 {@link CgRenderPassVariant#DISTORTION}
     */
    public static CgPipeline of(CgMaterialShader shader, CgRenderPassVariant pass, Set<String> keywords,
                                CgRenderState state, CgInstanceKind kind) {
        Set<String> variant = takesKeywords(pass) && !keywords.isEmpty()
                ? Collections.unmodifiableSet(new TreeSet<>(keywords))
                : Collections.emptySet();
        return INTERNED.computeIfAbsent(new Key(shader, pass, null, variant, state, kind, 0), CgPipeline::register);
    }

    /**
     * The pipeline for the Forward pass named {@code passName}, its keywords, state and kind: one pass of a material
     * drawing several in turn (a multi-pass post material), where {@link #of} reaches the first Forward pass alone.
     */
    public static CgPipeline pass(CgMaterialShader shader, String passName, Set<String> keywords, CgRenderState state,
                                  CgInstanceKind kind) {
        Set<String> variant = keywords.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(new TreeSet<>(keywords));
        return INTERNED.computeIfAbsent(new Key(shader, CgRenderPassVariant.FORWARD, Objects.requireNonNull(passName, "passName"),
                variant, state, kind, 0), CgPipeline::register);
    }

    /**
     * This pipeline in {@code form} as well as the forms it has, made on first use; null where the form does not apply
     * ({@link CgPipelineForm} says when). {@code this} where it has it already. Any thread.
     *
     * <pre>{@code
     * CgPipeline folded = material.pipeline(CgInstanceKind.OBJECT).as(CgPipelineForm.FOLD);
     * if (folded != null) chunks.draw(folded, bindings, mesh);              // colour and glow, into the scene
     * }</pre>
     */
    @Nullable
    public CgPipeline as(CgPipelineForm form) {
        int bit = form.bit();
        if ((forms & bit) != 0) return this;
        CgPipeline made = twins[form.ordinal()];
        if (made != null) return made;
        if ((forms & form.excludes) != 0 || form.forwardOnly && pass != CgRenderPassVariant.FORWARD) return null;
        if (form.forwardOnly) {
            CgParsedShader parsed = shader.ensureParsed();
            if (parsed == null || !form.admits(parsed, state)) return null;
            if (form.singleOutput) {
                CgParsedPass own = ownPass(parsed);
                if (own == null || own.fragOutput().isMrt()) return null;
            }
        }
        made = INTERNED.computeIfAbsent(new Key(shader, pass, passName, keywords, form.state(state), kind,
                (forms | bit) & ~form.drops), CgPipeline::register);
        twins[form.ordinal()] = made;
        return made;
    }

    /** Whether it draws in {@code form}. */
    public boolean has(CgPipelineForm form) {
        return (forms & form.bit()) != 0;
    }

    /** The passes a material's keywords reach: the ones a material authors for the frame it draws into. */
    private static boolean takesKeywords(CgRenderPassVariant pass) {
        return pass == CgRenderPassVariant.FORWARD || pass == CgRenderPassVariant.EMISSIVE
                || pass == CgRenderPassVariant.DISTORTION;
    }

    /**
     * The colour attachments its program writes, a bit each: slot 0 always, slot 1 for {@link CgPipelineForm#VOLUME},
     * {@link CgPipelineForm#NORMALS} and {@link CgPipelineForm#LAYER}, and an MRT output's {@code : RTn} slots. A pass drawing into more than one masks every other slot off for it, so an
     * unwritten output never lands. Render thread.
     */
    public int slotWrites() {
        int revision = shader.getRevisionNumber();
        if (slotsRevision == revision) return slots;
        int bits = 1;
        if (has(CgPipelineForm.VOLUME) || has(CgPipelineForm.NORMALS) || has(CgPipelineForm.LAYER)) bits |= 2;
        CgParsedShader parsed = shader.ensureParsed();
        CgParsedPass own = ownPass(parsed);
        if (own != null && own.fragOutput().isMrt()) {
            bits = 0;
            for (int location : own.fragOutput().locations()) bits |= 1 << location;
        }
        slots = bits;
        slotsRevision = revision;
        return bits;
    }

    private static CgPipeline register(Key key) {
        synchronized (REGISTERING) {
            CgPipeline[] table = byId;
            if (count == table.length) table = Arrays.copyOf(table, count * 2);
            CgPipeline pipeline = new CgPipeline(count, key);
            table[count++] = pipeline;
            byId = table;
            return pipeline;
        }
    }

    /**
     * This pipeline under another render state: what a caller that overrides a slot the shader declares asks
     * for — the text renderer's depth, which {@code text.shader} cannot express per draw.
     */
    public CgPipeline withState(CgRenderState other) {
        if (other == state) return this;
        synchronized (this) {
            for (int i = 0; i < derivedCount; i++) if (derivedStates[i] == other) return derived[i];
        }
        CgPipeline variant = INTERNED.computeIfAbsent(new Key(shader, pass, passName, keywords, other, kind, forms),
                CgPipeline::register);
        synchronized (this) {
            if (derivedCount == derived.length) {
                derived = Arrays.copyOf(derived, derivedCount * 2);
                derivedStates = Arrays.copyOf(derivedStates, derivedCount * 2);
            }
            derived[derivedCount] = variant;
            derivedStates[derivedCount++] = other;
        }
        return variant;
    }

    /** The pipeline {@link #id()} names. */
    public static CgPipeline byId(int id) {
        return byId[id];
    }

    public int id() {
        return id;
    }

    public CgMaterialShader shader() {
        return shader;
    }

    /**
     * Whether its shader reads a recording's tables (palette, clips, shapes) through the {@code quad}, {@code curve},
     * {@code clip} or {@code shape}: whatever kind draws it, its pass binds them.
     */
    public boolean readsTables() {
        CgParsedShader parsed = shader.ensureParsed();
        if (parsed == null) return false;
        if (parsed != tablesParse) {
            boolean reads = false;
            for (String token : parsed.engineBuffers()) {
                reads |= token.equals("quad") || token.equals("curve") || token.equals("clip") || token.equals("shape");
            }
            tables = reads;
            tablesParse = parsed;
        }
        return tables;
    }

    /** {@link #readsTables()}, for the parse it was read from. */
    @Nullable
    private volatile CgParsedShader tablesParse;
    private volatile boolean tables;

    /**
     * Whether its draws sample {@code cg_DepthBuffer}: its linked program does (its source may, before it links), or the
     * compiler's discard behind the scene does, which an Emissive, Distortion, cover or overdraw pipeline has whatever
     * its source names.
     */
    public boolean readsSceneDepth() {
        CgShader linked = resolved() ? program : null;
        return (linked != null ? shader.readsSceneDepth(linked) : shader.readsSceneDepth())
                || pass == CgRenderPassVariant.EMISSIVE || pass == CgRenderPassVariant.DISTORTION
                || has(CgPipelineForm.COVER) || has(CgPipelineForm.OVERDRAW);
    }

    /** Whether its draws sample {@code cg_SceneColor}: its linked program does, or its source may before it links. */
    public boolean readsSceneColor() {
        CgShader linked = resolved() ? program : null;
        return linked != null ? shader.readsSceneColor(linked) : shader.readsSceneColor();
    }

    public CgRenderPassVariant pass() {
        return pass;
    }

    /** The parsed pass it draws: by its name where it has one, else its variant's. */
    @Nullable
    private CgParsedPass ownPass(@Nullable CgParsedShader parsed) {
        if (parsed == null) return null;
        return passName != null ? parsed.getPassByName(passName) : parsed.getPassByLightMode(pass.lightModeName());
    }

    public Set<String> keywords() {
        return keywords;
    }

    public CgRenderState state() {
        return state;
    }

    public CgInstanceKind kind() {
        return kind;
    }

    /**
     * Starts this pipeline's program compiling without waiting, the shader's first and then this pass, keyword set
     * and forms of it, and says whether {@link #program()} would now return without compiling or waiting
     * on the driver. False on a frame that starts one, or once the frame's {@link CgWarmBudget} is spent; poll it frame
     * to frame.
     */
    public boolean precache() {
        if (builtRevision == shader.getRevisionNumber() && !shader.isDirty()) return true;
        builtRevision = -1;
        requestedRevision = shader.getRevisionNumber();
        if (!CgWarmBudget.allows()) return false;
        long start = System.nanoTime();
        boolean ready;
        try {
            ready = startOrPoll();
        } finally {
            CgWarmBudget.spend(System.nanoTime() - start);
        }
        if (ready) builtRevision = shader.getRevisionNumber();
        return ready;
    }

    /** Whether {@link #precache()} last answered true for the shader as it is now: asks and starts nothing. */
    public boolean isBuilt() {
        return builtRevision == shader.getRevisionNumber() && !shader.isDirty();
    }

    private boolean startOrPoll() {
        if (shader.isDirty()) {
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, SUBMIT_ZONE)) {
                shader.submitRecompile(pass == CgRenderPassVariant.FORWARD);
            }
            return false;
        }
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, POLL_ZONE)) {
            if (!shader.pollPending()) return false;
        }
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, VARIANT_ZONE)) {
            if (!shader.precacheVariant(passName != null ? passName : pass == CgRenderPassVariant.FORWARD ? null
                    : pass.lightModeName(), compiled)) return false;
        }
        return devicePrepared();
    }

    /**
     * Whether the device has built what every pipeline of this program shares (GL's linked program, started on its link
     * worker here), for the vertex stage its draws take: so a precached draw never waits on a link.
     */
    private boolean devicePrepared() {
        if (!CgRHI.installed()) return true;
        CgShader linked = program();
        if (linked == null || !linked.isCompiled()) return true;   // its draw reports the failure
        CgRHI rhi = CgRHI.get();
        return rhi.prepareProgram(CgDeviceStates.layout(rhi, linked.explicit()),
                linked.deviceModule(rhi, CgShaderModule.Stage.VERTEX),
                linked.deviceModule(rhi, CgShaderModule.Stage.FRAGMENT));
    }

    /**
     * The compiled program, compiling it on first use or after a reload; null when the shader does not compile or
     * has no such pass.
     */
    @Nullable
    public CgShader program() {
        return resolved() ? program : resolve();
    }

    /** Whether {@link #program()} answers its kept program, building and waiting for nothing. */
    private boolean resolved() {
        return program != null && programRevision == shader.getRevisionNumber() && !shader.isDirty() && !shader.isPending();
    }

    /** The program looked up or built at the draw, classified as Unreal classifies a PSO its draw asks for. */
    @Nullable
    private CgShader resolve() {
        int before = shader.getRevisionNumber();
        boolean dirty = shader.isDirty();
        boolean precached = builtRevision == before && !dirty;
        boolean asked = requestedRevision == before;
        long waitsBefore = CgMaterialShader.compileWaits();
        long start = System.nanoTime();
        shader.awaitPending();
        if (shader.isDirty()) shader.recompile();
        int revision = shader.getRevisionNumber();
        if (program == null || programRevision != revision) {
            program = passName != null ? shader.getOrCompile(passName, compiled)
                    : pass == CgRenderPassVariant.FORWARD ? shader.getOrCompileForwardPass(compiled)
                    : shader.getOrCompile(pass.lightModeName(), compiled);
            programRevision = revision;
            if (program != null) startRecorded(program);
        }
        long took = System.nanoTime() - start;
        // Built by another pipeline of the same program (a state of its own, a sibling) is built all the same.
        if (precached || !dirty && CgMaterialShader.compileWaits() == waitsBefore) {
            CgTrace.add(CgChannels.GL, PRECACHED, 1);
        } else {
            if (asked) tooLate++;
            else missed++;
            CgTrace.add(CgChannels.GL, asked ? TOO_LATE : MISSED, 1);
            if (LOG_MISSES.get()) LOGGER.warn("pipeline {}: {} at its draw, {} ms", asked ? "too late" : "missed", this, took / 1_000_000);
            else if (!asked) warnOutsideDomain();
        }
        if (took > HITCH_NANOS) {
            hitches++;
            CgTrace.add(CgChannels.GL, HITCHES, 1);
        }
        return program;
    }

    /**
     * How many pipelines a draw found unbuilt this session, never asked for ({@link #precache()}) or asked for and not
     * yet built: Unreal's Missed and TooLate. Each built at its draw. Also the trace counters {@code pipeline.missed}
     * and {@code pipeline.too-late}, beside {@code pipeline.precached}.
     *
     * <pre>{@code
     * long before = CgPipeline.misses();
     * // ... a scene's first frames, everything it draws precached ...
     * assert CgPipeline.misses() == before;
     * }</pre>
     */
    public static long misses() {
        return missed + tooLate;
    }

    /** How many of {@link #misses()} were never asked for. */
    public static long missedUnasked() {
        return missed;
    }

    /** How many times a draw's program took over 20 ms to resolve ({@code pipeline.hitches}): Unreal's runtime-creation hitch. */
    public static long hitches() {
        return hitches;
    }

    /**
     * This pipeline as {@code CgRHI.get()}'s graphics pipeline into targets of these formats: its program as written, no
     * vertex input (its vertex stage pulls the mesh, {@code CgMeshStore}), its render state over {@code passState}, the
     * outputs it does not write ({@link #slotWrites()}) masked off. Built once per description; null when the program
     * does not compile. Render thread.
     *
     * <pre>{@code
     * CgGraphicsPipeline p = pipeline.device(passState, CgMeshStore.topology(mesh), true,
     *         List.of(CgFormat.RGBA16_FLOAT),
     *         CgFormat.DEPTH32_FLOAT, 1);
     * if (p != null) pass.setPipeline(p);
     * }</pre>
     *
     * <ul>
     *   <li>The stencil reference is the pass's to set, not the pipeline's.</li>
     * </ul>
     *
     * @param reversed whether the target's depth runs far to near ({@code CgDepthConvention}): false mirrors the
     *                 compare, authored as nearer-passes, for a host's standard depth
     * @param depth null for a pass with no depth target
     * @throws com.crystalgraphics.platform.rhi.shader.CgShaderModule.CompileException where its sources leave a resource
     *         unbound
     */
    @Nullable
    public CgGraphicsPipeline device(CgRenderState passState, CgGraphicsPipelineDesc.Topology topology,
                                     boolean reversed, List<CgFormat> colors, @Nullable CgFormat depth, int samples) {
        CgShader linked = program();
        if (linked == null) return null;
        CgRHI rhi = CgRHI.get();
        if (deviceLast != null && deviceLastOf == rhi && deviceLastProgram == linked && deviceLastPass == passState
                && deviceLastTopology == topology && deviceLastDepth == depth && deviceLastSamples == samples
                && deviceLastReversed == reversed && sameFormats(deviceLastColors, colors)) {
            return deviceLast;
        }
        String label = toString();
        CgRenderState s = CgDeviceStates.over(passState, state);
        CgGraphicsPipelineDesc.Blend blend = CgDeviceStates.blend(s.getBlend());
        int writes = slotWrites();
        List<CgGraphicsPipelineDesc.ColorTarget> targets = new ArrayList<>(colors.size());
        for (int i = 0; i < colors.size(); i++) {
            int mask = (writes & 1 << i) == 0 ? 0 : CgDeviceStates.writeMask(s.getColorMasks(), i);
            CgGraphicsPipelineDesc.Blend own = i == 1 && s.getSecondBlend() != null ? CgDeviceStates.blend(s.getSecondBlend()) : blend;
            targets.add(new CgGraphicsPipelineDesc.ColorTarget(colors.get(i), own, mask));
        }
        CgGraphicsPipelineDesc desc = new CgGraphicsPipelineDesc(label, CgDeviceStates.layout(rhi, linked.explicit()),
                linked.deviceModule(rhi, CgShaderModule.Stage.VERTEX),
                linked.deviceModule(rhi, CgShaderModule.Stage.FRAGMENT),
                List.of(), topology,
                CgDeviceStates.raster(s.getCull()), CgDeviceStates.depthStencil(s.getDepth(), s.getStencil(), reversed), targets,
                depth, samples);
        deviceLast = deviceCache(rhi).get(desc, linked.deviceKey());
        deviceLastOf = rhi;
        deviceLastProgram = linked;
        deviceLastPass = passState;
        deviceLastTopology = topology;
        deviceLastColors = List.copyOf(colors);
        deviceLastDepth = depth;
        deviceLastSamples = samples;
        deviceLastReversed = reversed;
        return deviceLast;
    }

    private static CgPipelineCache deviceCache(CgRHI rhi) {
        if (deviceCacheOf != rhi) {
            deviceCache = new CgPipelineCache(rhi);
            deviceCacheOf = rhi;
        }
        return deviceCache;
    }

    /**
     * Starts every device pipeline recorded for {@code linked} building on the cache's workers, once a device: as its
     * program becomes the pipeline's (a precache, inside the loading screen), as Unreal's PSO cache starts a program's
     * recorded pipelines as it loads. Its draws then take them ({@link CgPipelineCache#get}). Nothing without a device
     * that builds off the render thread.
     */
    private void startRecorded(CgShader linked) {
        if (!CgRHI.installed() || !CgGlslCompiler.installed()) return;
        CgRHI rhi = CgRHI.get();
        if (!rhi.createsOnAnyThread() || linked.deviceKey() == 0 || !linked.startsOn(rhi)) return;
        String label = toString();
        CgPipelineCache cache = deviceCache(rhi);
        cache.adopt(cache.startRecorded(linked.deviceKey(), recipe -> recipe.desc(label,
                CgDeviceStates.layout(rhi, linked.explicit()),
                linked.deviceModule(rhi, CgShaderModule.Stage.VERTEX),
                linked.deviceModule(rhi, CgShaderModule.Stage.FRAGMENT))));
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("CgPipeline#").append(id).append('(').append(shader.getResourcePath())
                .append(' ').append(passName != null ? passName : pass).append(' ').append(keywords).append(' ').append(kind);
        for (CgPipelineForm form : CgPipelineForm.ALL) if (has(form)) sb.append(' ').append(form);
        return sb.append(')').toString();
    }

    /** A shader and a render state by identity, since neither has value equality worth trusting. */
    private record Key(CgMaterialShader shader, CgRenderPassVariant pass, @Nullable String passName, Set<String> keywords,
                       CgRenderState state, CgInstanceKind kind, int forms) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.shader == shader && k.pass == pass && Objects.equals(k.passName, passName)
                    && k.keywords.equals(keywords)
                    && k.state == state && k.kind == kind && k.forms == forms;
        }

        @Override
        public int hashCode() {
            int h = System.identityHashCode(shader);
            h = h * 31 + pass.ordinal();
            h = h * 31 + Objects.hashCode(passName);
            h = h * 31 + keywords.hashCode();
            h = h * 31 + System.identityHashCode(state);
            h = h * 31 + kind.ordinal();
            return h * 31 + forms;
        }
    }

    /** {@code a.equals(b)} without the iterator {@code List.equals} makes: asked every batch. */
    private static boolean sameFormats(List<CgFormat> a, List<CgFormat> b) {
        int n = a.size();
        if (n != b.size()) return false;
        for (int i = 0; i < n; i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }
}
