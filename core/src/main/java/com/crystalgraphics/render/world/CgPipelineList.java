package com.crystalgraphics.render.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialManifest;
import com.crystalgraphics.api.material.CgMaterialPermutation;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.material.parse.CgPermutationDomain;

import com.crystalgraphics.render.CgWarmBudget;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.github.bsideup.jabel.Desugar;

/**
 * Every pipeline the engine keeps built: what a {@link CgPipelinePrecache} collector or request names. A material is
 * kept in every form the world renderer draws it with ({@link CgWorldRenderer#precache}), for the settings in force; a
 * kernel's program once. A settings change (merged emission, half size) re-warms every material under
 * the new settings before the renderer applies them, so the first frame after a toggle compiles nothing. Unreal's PSO
 * precache, by material and vertex factory ({@code UMaterialInstance::PrecachePSOs}, MaterialInstance.cpp:2918).
 *
 * <pre>{@code
 * CgPipelinePrecache.request(pipelines -> {
 *     pipelines.add(BEAM, true);                 // a material, its half-size passes too
 *     pipelines.add(GOKU_BODY, false, true);     // a material a skinned mesh draws
 *     pipelines.add("mymod:shaders/rune.shader");   // every combination of its permutation domain
 *     pipelines.add(manifest);                   // an asset bundle's permutations
 *     pipelines.add(stepKernel);
 *     pipelines.kernels(MyOps::precache);         // every kernel it precaches
 * });
 * }</pre>
 *
 * <ul>
 *   <li>Reached only through {@link CgPipelinePrecache}'s collectors and requests. Render thread.</li>
 *   <li>The list only grows: an entry is kept built for the session.</li>
 *   <li>A toggle waits at most {@link #MAX_WAIT_FRAMES} frames for the list, then applies anyway: a shader that never
 *       builds does not hold a setting back.</li>
 * </ul>
 */
public final class CgPipelineList {

    /** The most frames a settings change waits for the list to warm under it. */
    public static final int MAX_WAIT_FRAMES = 300;

    /**
     * What decides which of a material's pipelines the world renderer draws.
     *
     * @param mergeEmission  a foldable glow is drawn in its colour's draw
     * @param halfResolution a half-size draw is drawn at half size, with its passes
     */
    @Desugar
    public record Settings(boolean mergeEmission, boolean halfResolution) {
    }

    /** Starts or polls a material's pipelines under settings; whether all are built. */
    @FunctionalInterface
    interface Precacher {
        boolean precache(CgMaterial material, boolean half, boolean skinned, Settings settings);
    }

    private final Precacher precacher;
    private final Supplier<Settings> requested;
    private final List<CgMaterial> list = new ArrayList<>();
    private final Map<CgMaterial, Boolean> halves = new IdentityHashMap<>();
    /** Materials a skinned mesh draws: their skinned forms are warmed too. */
    private final Map<CgMaterial, Boolean> skinned = new IdentityHashMap<>();
    /** Materials not yet built under {@link #warmedFor}. */
    private final Map<CgMaterial, Boolean> pending = new IdentityHashMap<>();
    private final Map<CgMaterialManifest.Use, CgMaterial> probes = new HashMap<>();
    private final Map<String, CgMaterial> variants = new HashMap<>();
    private final List<CgKernel> kernels = new ArrayList<>();
    /** Kernels not yet built. */
    private final Set<CgKernel> unbuilt = Collections.newSetFromMap(new LinkedHashMap<>());
    private Settings warmedFor;
    private int waited;
    /** Where the last frame's poll of pending materials stopped. */
    private int cursor = -1;
    /** The request whose collector is adding, which records what it names. */
    private CgPipelinePrecache.Request capturing;

    CgPipelineList(Precacher precacher, Supplier<Settings> requested) {
        this.precacher = precacher;
        this.requested = requested;
    }

    /** Keeps {@code material} built, its half-size passes too where {@code half}. */
    public void add(CgMaterial material, boolean half) {
        add(material, half, false);
    }

    /**
     * Keeps {@code material} built, as a skinned mesh draws it too where {@code skinnedMesh}: every form in its skinned
     * vertex factory as well.
     */
    public void add(CgMaterial material, boolean half, boolean skinnedMesh) {
        if (capturing != null) capturing.materials.add(material);
        Boolean had = halves.get(material);
        if (had == null) list.add(material);
        boolean wasSkinned = skinned.containsKey(material);
        if (had == null || (half && !had) || (skinnedMesh && !wasSkinned)) {
            halves.put(material, had != null && had || half);
            if (skinnedMesh) skinned.put(material, Boolean.TRUE);
            pending.put(material, Boolean.TRUE);
        }
    }

    /**
     * Keeps {@code shader} built in every form the world draws it, for every combination of its permutation domain
     * ({@code #pragma cg_permutation}: the keywords its renderer switches), each with {@code keywords} added. A program is
     * the shader's and its keywords', so a probe material builds each.
     *
     * <pre>{@code
     * pipelines.add("crystalgraphics:shaders/canvas/text.shader");            // MSDF_MODE x WORLD_LABEL: four programs
     * pipelines.add("mymod:shaders/rune.shader", "GLOW");              // its domain's combinations, each with GLOW
     * }</pre>
     */
    public void add(String shader, String... keywords) {
        forEachVariant(variants, shader, keywords, m -> add(m, false));
    }

    /**
     * Keeps {@code shader}'s own pass built as a full-screen pass draws it, its Forward pipeline alone and none of the
     * world's forms (a post pass, bloom, the composite): for every combination of its permutation domain, each with
     * {@code keywords} added.
     *
     * <pre>{@code
     * pipelines.addPass("crystalgraphics:shaders/post/composite.shader");               // FADE x BLOOM
     * pipelines.addPass("crystalgraphics:shaders/post/composite_copy.shader", "GRADE"); // a look's own, over each
     * }</pre>
     */
    public void addPass(String shader, String... keywords) {
        addPass(shader, CgInstanceKind.OBJECT, keywords);
    }

    /**
     * As {@link #addPass(String, String...)}, for a shader drawn on {@code kind}'s instances: a screen-space quad or curve
     * material (a UI's), drawn through {@code CgQuadBatch} or {@code CgCurveBatch}.
     *
     * <pre>{@code
     * pipelines.addPass("mymod:shaders/gui_panel.shader", CgInstanceKind.QUAD);        // its domain's combinations
     * pipelines.addPass("mymod:shaders/gui_wire.shader", CgInstanceKind.CURVE);
     * }</pre>
     */
    public void addPass(String shader, CgInstanceKind kind, String... keywords) {
        forEachVariant(passProbes, shader, keywords, m -> addPass(m, kind));
    }

    /** A probe material per keyword set of {@code shader}'s domain with {@code keywords} added, made once each. */
    private static void forEachVariant(Map<String, CgMaterial> probes, String shader, String[] keywords,
                                       Consumer<CgMaterial> sink) {
        CgMaterial base = probe(probes, shader, Arrays.asList(keywords));
        CgPermutationDomain domain = base.permutations();
        if (domain.dimensions().isEmpty()) {
            sink.accept(base);
            return;
        }
        domain.forEach(Arrays.asList(keywords), set -> sink.accept(probe(probes, shader, set)));
    }

    private static CgMaterial probe(Map<String, CgMaterial> probes, String shader, List<String> keywords) {
        String[] sorted = keywords.toArray(new String[0]);
        Arrays.sort(sorted);
        String key = shader + '|' + String.join(",", sorted);
        CgMaterial m = probes.get(key);
        if (m == null) {
            m = CgMaterial.newInstance(shader);
            for (String k : sorted) m.enableKeyword(k);
            probes.put(key, m);
        }
        return m;
    }

    /** Keeps {@code material}'s own Forward pass built as it is, none of the world's forms: a material drawn over the screen. */
    public void addPass(CgMaterial material) {
        addPass(material, CgInstanceKind.OBJECT);
    }

    /** As {@link #addPass(CgMaterial)}, for a material drawn on {@code kind}'s instances. */
    public void addPass(CgMaterial material, CgInstanceKind kind) {
        if (capturing != null) capturing.passes.add(material);
        if (passes.containsKey(material)) return;
        passes.put(material, kind);
        passesUnbuilt.add(material);
    }

    private final Map<String, CgMaterial> passProbes = new HashMap<>();
    /** Each pass and the instances it is drawn on. */
    private final Map<CgMaterial, CgInstanceKind> passes = new IdentityHashMap<>();
    /** Passes not yet built. */
    private final Set<CgMaterial> passesUnbuilt = Collections.newSetFromMap(new LinkedHashMap<>());

    /** Its program committed, then its Forward pipeline built: a pass's program follows no setting. */
    private boolean passBuilt(CgMaterial material) {
        if (!material.precache()) return false;
        CgPipeline p = material.pipeline(passes.get(material));
        return p == null || p.precache();
    }

    /**
     * Keeps {@code manifest}'s permutations built: a material per use, made once and shared by every manifest naming it,
     * since programs are the shader's and its keywords'.
     */
    public void add(CgMaterialManifest manifest) {
        for (CgMaterialManifest.Use use : manifest.uses()) {
            CgMaterial m = probes.get(use);
            if (m == null) {
                m = material(use);
                probes.put(use, m);
            }
            add(m, use.half());
        }
    }

    private static CgMaterial material(CgMaterialManifest.Use use) {
        CgMaterialPermutation p = use.permutation();
        CgMaterial m = CgMaterial.newInstance(p.shader(), p.state());
        for (String k : p.keywords()) m.enableKeyword(k);
        if (use.kind() != null && m.declaredKeywords().contains(use.kind())) m.enableKeyword(use.kind());
        return m;
    }

    /**
     * Keeps {@code kernel}'s program built: a dispatch that would otherwise compile on the frame of its first dispatch.
     * A kernel's program follows no setting, so it is built once.
     */
    public void add(CgKernel kernel) {
        if (capturing != null) capturing.kernels.add(kernel);
        if (kernels.contains(kernel)) return;
        kernels.add(kernel);
        unbuilt.add(kernel);
    }

    /** Every kernel {@code precache} starts, kept built: the kernels behind an op (a sort, Range, a pool), named by its precacher. */
    public void kernels(Runnable precache) {
        for (CgKernel k : CgKernel.collect(precache)) add(k);
    }

    private static final int COLLECT_ZONE = CgTrace.name("pipeline.collect"), READY_ZONE = CgTrace.name("pipeline.ready"),
            POLL_ZONE = CgTrace.name("pipeline.poll");

    /** Requests with steps still to collect, in the order they started. */
    private final List<CgPipelinePrecache.Request> collecting = new ArrayList<>();

    /** Keeps {@code request} and collects what of it this frame's budget allows. */
    void start(CgPipelinePrecache.Request request) {
        if (advance(request)) return;
        collecting.add(request);
    }

    /**
     * Collects {@code request}'s steps in order while the warm budget lasts, once its worker phase is done; whether every
     * step is collected. A step that throws is logged and skipped.
     */
    private boolean advance(CgPipelinePrecache.Request request) {
        if (request.ahead != null) {
            if (!request.ahead.isDone()) return false;
            request.ahead = null;
        }
        while (!request.steps.isEmpty() && CgWarmBudget.allows()) {
            CgPipelinePrecache.Collector step = request.steps.poll();
            long start = System.nanoTime();
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, COLLECT_ZONE)) {
                collect(step, request);
            } catch (RuntimeException e) {
                CgPipelinePrecache.LOGGER.error("precache {}: a step failed and is skipped", request.name, e);
            }
            CgWarmBudget.spend(System.nanoTime() - start);
        }
        return request.steps.isEmpty();
    }

    /** Advances every request still collecting, oldest first. */
    private void advanceAll() {
        for (int i = 0; i < collecting.size(); ) {
            if (advance(collecting.get(i))) collecting.remove(i);
            else i++;
        }
    }

    /** Runs {@code collector} into the list, recording what it names in {@code into} where given. */
    void collect(CgPipelinePrecache.Collector collector, CgPipelinePrecache.Request into) {
        CgPipelinePrecache.Request outer = capturing;
        capturing = into;
        try {
            collector.collect(this);
        } finally {
            capturing = outer;
        }
    }

    /** Whether every entry {@code request} named is built under the settings in force, polling those still building. */
    boolean ready(CgPipelinePrecache.Request request) {
        if (!request.collected()) {
            if (!advance(request)) return false;
            collecting.remove(request);
        }
        // Asked from anywhere (a loading screen, a scene): built as the world's draws take them, as frame() builds
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, READY_ZONE)) {
            return polled(request);
        }
    }

    /** {@link #ready}'s poll of what {@code request} named, once collected. */
    private boolean polled(CgPipelinePrecache.Request request) {
        Settings settings = warmedFor != null ? warmedFor : requested.get();
        boolean ready = true;
        for (int i = 0; i < request.materials.size(); i++) {
            CgMaterial m = request.materials.get(i);
            if (!pending.containsKey(m)) continue;
            if (precacher.precache(m, halves.get(m), skinned.containsKey(m), settings)) pending.remove(m);
            else ready = false;
        }
        for (int i = 0; i < request.kernels.size(); i++) {
            CgKernel k = request.kernels.get(i);
            if (!unbuilt.contains(k)) continue;
            if (k.isReady()) unbuilt.remove(k);
            else ready = false;
        }
        for (int i = 0; i < request.passes.size(); i++) {
            CgMaterial m = request.passes.get(i);
            if (!passesUnbuilt.contains(m)) continue;
            if (passBuilt(m)) passesUnbuilt.remove(m);
            else ready = false;
        }
        return ready;
    }

    /**
     * Polls the unbuilt kernels and passes, each started if nothing has, while the warm budget lasts: finishing a linked
     * program costs up to 11 ms on NVIDIA, and a boot finds dozens linked at once. Whether all are built.
     */
    private boolean pollKernels() {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, POLL_ZONE)) {
            return pollUnbuilt();
        }
    }

    private boolean pollUnbuilt() {
        for (Iterator<CgKernel> it = unbuilt.iterator(); it.hasNext() && CgWarmBudget.allows(); ) {
            long start = System.nanoTime();
            if (it.next().isReady()) it.remove();
            CgWarmBudget.spend(System.nanoTime() - start);
        }
        for (Iterator<CgMaterial> it = passesUnbuilt.iterator(); it.hasNext() && CgWarmBudget.allows(); ) {
            long start = System.nanoTime();
            if (passBuilt(it.next())) it.remove();
            CgWarmBudget.spend(System.nanoTime() - start);
        }
        return unbuilt.isEmpty() && passesUnbuilt.isEmpty();
    }

    /** How many materials, passes and kernels are not yet built under the settings in force, and requests still collecting. */
    int pending() {
        return pending.size() + unbuilt.size() + passesUnbuilt.size() + collecting.size();
    }

    /** How many materials, passes and kernels it keeps built. */
    int size() {
        return list.size() + kernels.size() + passes.size();
    }

    /** Pending passes and materials the warm log names a frame, by their pipelines. */
    private static final int LOGGED_NAMES = 4;

    /**
     * What the list still waits on, for {@code crystalgraphics.warmBudget.log}: each request collecting, its steps left,
     * and the first pending passes and materials.
     */
    private void logPending() {
        StringBuilder line = new StringBuilder("[warm] pipelines: ").append(pending.size()).append(" materials, ")
                .append(unbuilt.size()).append(" kernels, ").append(passesUnbuilt.size()).append(" passes; collecting");
        for (CgPipelinePrecache.Request r : collecting) {
            line.append(' ').append(r.name).append(" (").append(r.steps.size()).append(" steps")
                    .append(r.ahead != null && !r.ahead.isDone() ? ", worker" : "").append(')');
        }
        int named = 0;
        for (CgMaterial m : passesUnbuilt) {
            if (named++ == LOGGED_NAMES) break;
            line.append(named == 1 ? "; passes " : ", ").append(m.pipeline(passes.get(m)));
        }
        named = 0;
        for (CgMaterial m : pending.keySet()) {
            if (named++ == LOGGED_NAMES) break;
            line.append(named == 1 ? "; materials " : ", ").append(m.pipeline(CgInstanceKind.OBJECT));
        }
        CgPipelinePrecache.LOGGER.info(line.toString());
    }

    /**
     * Once a frame: polls what is pending under {@code requested}, re-warming the whole list when it differs from what
     * was warmed for. Answers whether {@code requested} may apply: the list is built under it, or the wait is spent.
     */
    boolean frame(Settings requested, Settings applied) {
        if (CgWarmBudget.logging()) logPending();
        CgPipelinePrecache.collect(this);
        if (!collecting.isEmpty()) advanceAll();
        if ((!unbuilt.isEmpty() || !passesUnbuilt.isEmpty()) && CgWarmBudget.allows()) pollKernels();
        if (!requested.equals(warmedFor)) {
            warmedFor = requested;
            for (CgMaterial m : list) pending.put(m, Boolean.TRUE);
        }
        if (!pending.isEmpty()) {
            // Polled while the warm budget lasts, from where the last frame stopped: a kit's hundreds of materials
            // polled whole each frame held a loading frame 100 ms and more
            int n = list.size();
            for (int k = 0; k < n && CgWarmBudget.allows(); k++) {
                CgMaterial m = list.get(cursor = (cursor + 1) % n);
                if (!pending.containsKey(m)) continue;
                long start = System.nanoTime();
                if (precacher.precache(m, halves.get(m), skinned.containsKey(m), requested)) pending.remove(m);
                CgWarmBudget.spend(System.nanoTime() - start);
            }
        }
        if (requested.equals(applied)) {
            waited = 0;
            return true;
        }
        if (pending.isEmpty() || ++waited > MAX_WAIT_FRAMES) {
            waited = 0;
            return true;
        }
        return false;
    }
}
