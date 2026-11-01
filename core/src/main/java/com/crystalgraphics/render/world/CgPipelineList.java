package com.crystalgraphics.render.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialManifest;
import com.crystalgraphics.api.material.CgMaterialPermutation;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.render.CgWarmBudget;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPipeline;
import com.github.bsideup.jabel.Desugar;

/**
 * Every pipeline the engine keeps built: what a {@link CgPipelinePrecache} collector or request names. A material is
 * kept in every form the world renderer draws it with ({@link CgWorldRenderer#precache}), for the settings in force; a
 * kernel's program once. A settings change (the HDR scene, merged emission, half size) re-warms every material under
 * the new settings before the renderer applies them, so the first frame after a toggle compiles nothing. Unreal's PSO
 * precache, by material and vertex factory ({@code UMaterialInstance::PrecachePSOs}, MaterialInstance.cpp:2918).
 *
 * <pre>{@code
 * CgPipelinePrecache.request(pipelines -> {
 *     pipelines.add(BEAM, true);                 // a material, its half-size passes too
 *     pipelines.add(GOKU_BODY, false, true);     // a material a skinned mesh draws
 *     pipelines.add("mymod:shaders/rune.shader", "GLOW");
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
     * @param hdrScene       the transparent stage draws into the linear HDR scene
     * @param mergeEmission  a foldable glow is drawn in its colour's draw (where the device allows)
     * @param joins          runs of draws join into multi-draws: each pipeline's multi-draw form too
     * @param halfResolution a half-size draw is drawn at half size, with its passes
     */
    @Desugar
    public record Settings(boolean hdrScene, boolean mergeEmission, boolean joins, boolean halfResolution) {
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
     * Keeps {@code shader}'s program for {@code keywords} built: a pass of a renderer's own whose keywords follow a
     * setting (bloom's {@code CHEAP} by the tier, the composite's form by the HDR scene). A program is the shader's and
     * its keywords', so a probe material builds the pass's own.
     */
    public void add(String shader, String... keywords) {
        String key = shader + String.join(",", keywords);
        CgMaterial m = variants.get(key);
        if (m == null) {
            m = CgMaterial.newInstance(shader);
            for (String k : keywords) m.enableKeyword(k);
            variants.put(key, m);
        }
        add(m, false);
    }

    /**
     * Keeps {@code shader}'s own pass built for {@code keywords}, as a full-screen pass draws it: its Forward pipeline
     * alone, none of the world's forms (a post pass, bloom, the composite). A probe material builds the pass's program.
     *
     * <pre>{@code
     * pipelines.addPass("crystalgraphics:shaders/post/composite.shader", "BLOOM");
     * }</pre>
     */
    public void addPass(String shader, String... keywords) {
        String key = shader + String.join(",", keywords);
        CgMaterial m = passProbes.get(key);
        if (m == null) {
            m = CgMaterial.newInstance(shader);
            for (String k : keywords) m.enableKeyword(k);
            passProbes.put(key, m);
        }
        addPass(m);
    }

    /** Keeps {@code material}'s own Forward pass built as it is, none of the world's forms: a material drawn over the screen. */
    public void addPass(CgMaterial material) {
        if (capturing != null) capturing.passes.add(material);
        if (passes.containsKey(material)) return;
        passes.put(material, Boolean.TRUE);
        passesUnbuilt.add(material);
    }

    private final Map<String, CgMaterial> passProbes = new HashMap<>();
    private final Map<CgMaterial, Boolean> passes = new IdentityHashMap<>();
    /** Passes not yet built. */
    private final Set<CgMaterial> passesUnbuilt = Collections.newSetFromMap(new LinkedHashMap<>());

    /** Its program committed, then its Forward pipeline built: a pass's program follows no setting. */
    private static boolean passBuilt(CgMaterial material) {
        if (!material.precache()) return false;
        CgPipeline p = material.pipeline(CgInstanceKind.OBJECT);
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

    /** Polls the unbuilt kernels and passes, each started if nothing has; whether all are built. */
    private boolean pollKernels() {
        for (Iterator<CgKernel> it = unbuilt.iterator(); it.hasNext(); ) {
            if (it.next().isReady()) it.remove();
        }
        for (Iterator<CgMaterial> it = passesUnbuilt.iterator(); it.hasNext(); ) {
            if (passBuilt(it.next())) it.remove();
        }
        return unbuilt.isEmpty() && passesUnbuilt.isEmpty();
    }

    /**
     * Requests everything on the list under {@code settings} and waits until it is built or {@code timeoutNanos} pass:
     * the graphics start's, so the engine's own passes compile in parallel before the first frame rather than one by one
     * at their draws (Unreal's global PSO collectors, waited for while loading). Answers how many are still pending.
     * Render thread, before {@code CgWarmBudget.start()}: nothing here is budgeted.
     */
    int await(Settings settings, long timeoutNanos) {
        CgPipelinePrecache.collect(this);
        warmedFor = settings;
        for (CgMaterial m : list) pending.put(m, Boolean.TRUE);
        long end = System.nanoTime() + timeoutNanos;
        while (true) {
            for (int i = 0; i < list.size(); i++) {
                CgMaterial m = list.get(i);
                if (pending.containsKey(m) && precacher.precache(m, halves.get(m), skinned.containsKey(m), settings)) pending.remove(m);
            }
            boolean kernelsBuilt = pollKernels();
            if (pending.isEmpty() && kernelsBuilt || System.nanoTime() > end) break;
            LockSupport.parkNanos(200_000L);
        }
        return pending();
    }

    /** How many materials, passes and kernels are not yet built under the settings in force. */
    int pending() {
        return pending.size() + unbuilt.size() + passesUnbuilt.size();
    }

    /** How many materials, passes and kernels it keeps built. */
    int size() {
        return list.size() + kernels.size() + passes.size();
    }

    /**
     * Once a frame: polls what is pending under {@code requested}, re-warming the whole list when it differs from what
     * was warmed for. Answers whether {@code requested} may apply: the list is built under it, or the wait is spent.
     */
    boolean frame(Settings requested, Settings applied) {
        CgPipelinePrecache.collect(this);
        if ((!unbuilt.isEmpty() || !passesUnbuilt.isEmpty()) && CgWarmBudget.allows()) pollKernels();
        if (!requested.equals(warmedFor)) {
            warmedFor = requested;
            for (CgMaterial m : list) pending.put(m, Boolean.TRUE);
        }
        if (!pending.isEmpty()) {
            for (int i = 0; i < list.size(); i++) {
                CgMaterial m = list.get(i);
                if (pending.containsKey(m) && precacher.precache(m, halves.get(m), skinned.containsKey(m), requested)) pending.remove(m);
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
