package com.crystalgraphics.render.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialManifest;
import com.crystalgraphics.api.material.CgMaterialPermutation;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.render.CgPrecache;
import com.crystalgraphics.render.CgWarmBudget;
import com.github.bsideup.jabel.Desugar;

/**
 * The world renderer's program list: every material a manifest or a caller asked to be warm, expanded through
 * {@link CgWorldRenderer#prepare(CgMaterial, boolean, Settings)} for the settings in force. A settings change (the HDR
 * scene, merged emission, half size) re-warms the whole list under the new settings before the renderer applies them,
 * so the first frame after a toggle compiles nothing. Unreal's PSO precache, by material and vertex factory
 * ({@code UMaterialInstance::PrecachePSOs}, {@code Engine/Private/Materials/MaterialInstance.cpp:2918}). Render thread.
 *
 * <pre>{@code
 * CgWorldPrograms.Warming move = CgWorldRenderer.get().programs().warm(manifest);   // at READY
 * if (move.ready()) play();                                // a frame's budget at a time, CgWarmBudget
 * CgWorldRenderer.get().programs().add(material, false);  // a material of one's own, warm from now on
 * }</pre>
 *
 * <ul>
 *   <li>A toggle waits at most {@link #MAX_WAIT_FRAMES} frames for the list, then applies anyway: a shader that never
 *       builds does not hold a setting back.</li>
 *   <li>The list only grows: a material in it is re-warmed on every change of settings.</li>
 * </ul>
 */
public final class CgWorldPrograms {

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
    interface Preparer {
        boolean prepare(CgMaterial material, boolean half, boolean skinned, Settings settings);
    }

    /** A manifest's materials being warmed: {@link #ready()} once every one is built under the settings in force. */
    public final class Warming {
        private final CgMaterial[] materials;

        private Warming(CgMaterial[] materials) {
            this.materials = materials;
        }

        /** Whether every material it asked for is built, under the settings in force. */
        public boolean ready() {
            for (CgMaterial m : materials) if (pending.containsKey(m)) return false;
            return true;
        }
    }

    private final Preparer preparer;
    private final List<CgMaterial> list = new ArrayList<>();
    private final Map<CgMaterial, Boolean> halves = new IdentityHashMap<>();
    /** Materials a skinned mesh draws: their skinned forms are warmed too. */
    private final Map<CgMaterial, Boolean> skinned = new IdentityHashMap<>();
    /** Materials not yet built under {@link #warmedFor}. */
    private final Map<CgMaterial, Boolean> pending = new IdentityHashMap<>();
    private final Map<CgMaterialManifest.Use, CgMaterial> probes = new HashMap<>();
    private Settings warmedFor;
    private int waited;

    CgWorldPrograms(Preparer preparer) {
        this.preparer = preparer;
    }

    /**
     * Warms {@code manifest}'s permutations: a material per use, made once and shared by every manifest naming it,
     * since programs are the shader's and its keywords'.
     */
    public Warming warm(CgMaterialManifest manifest) {
        CgMaterial[] made = new CgMaterial[manifest.uses().size()];
        int i = 0;
        for (CgMaterialManifest.Use use : manifest.uses()) {
            CgMaterial m = probes.get(use);
            if (m == null) {
                m = material(use);
                probes.put(use, m);
            }
            add(m, use.half());
            made[i++] = m;
        }
        return new Warming(made);
    }

    private static CgMaterial material(CgMaterialManifest.Use use) {
        CgMaterialPermutation p = use.permutation();
        CgMaterial m = CgMaterial.newInstance(p.shader(), p.state());
        for (String k : p.keywords()) m.enableKeyword(k);
        if (use.kind() != null && m.declaredKeywords().contains(use.kind())) m.enableKeyword(use.kind());
        return m;
    }

    /** Keeps {@code material} warm from now on, its half-size passes too where {@code half}. */
    public void add(CgMaterial material, boolean half) {
        add(material, half, false);
    }

    /**
     * Keeps {@code material} warm from now on as a skinned mesh draws it too where {@code skinnedMesh}: every form in
     * its skinned vertex factory as well.
     *
     * <pre>{@code
     * programs.add(cellTone, false, true);   // Goku's body: skinned
     * }</pre>
     */
    public void add(CgMaterial material, boolean half, boolean skinnedMesh) {
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
     * Keeps {@code shader}'s program for {@code keywords} warm: a pass of the renderer's own whose keywords follow a
     * setting (bloom's {@code CHEAP} by the tier, the composite's form by the HDR scene), so changing the setting compiles
     * nothing. A program is the shader's and its keywords', so a probe material warms the pass's own.
     *
     * <pre>{@code
     * programs.add("crystalgraphics:shaders/post/bloom/down.shader", "CHEAP");
     * }</pre>
     */
    public void add(String shader, String... keywords) {
        String key = shader + String.join(",", keywords);
        if (variants.containsKey(key)) return;
        CgMaterial m = CgMaterial.newInstance(shader);
        for (String k : keywords) m.enableKeyword(k);
        variants.put(key, m);
        add(m, false);
    }

    private final Map<String, CgMaterial> variants = new HashMap<>();

    /**
     * Keeps {@code kernel}'s program built from now on: a dispatch of the engine's own, or a mod's, that would otherwise
     * compile on the frame of its first dispatch. A kernel's program follows no setting, so it is built once.
     */
    public void add(CgKernel kernel) {
        if (kernels.contains(kernel)) return;
        kernels.add(kernel);
        unbuilt.add(kernel);
    }

    /** Every kernel {@code prepare} starts, kept built: the kernels behind an op (a sort, Range), named by its preparer. */
    public void kernels(Runnable prepare) {
        for (CgKernel k : CgKernel.collect(prepare)) add(k);
    }

    private final List<CgKernel> kernels = new ArrayList<>();
    /** Kernels not yet built. */
    private final List<CgKernel> unbuilt = new ArrayList<>();

    /** Polls the unbuilt kernels, each started if nothing has; whether all are built. */
    private boolean pollKernels() {
        for (int i = unbuilt.size() - 1; i >= 0; i--) {
            if (unbuilt.get(i).isReady()) unbuilt.remove(i);
        }
        return unbuilt.isEmpty();
    }

    /**
     * Requests everything on the list under {@code settings} and waits until it is built or {@code timeoutNanos} pass:
     * the graphics start's, so the engine's own passes compile in parallel before the first frame rather than one by one
     * at their draws (Unreal's global PSO collectors, waited for while loading). Answers how many are still pending.
     * Render thread, before {@code CgWarmBudget.start()}: nothing here is budgeted.
     */
    int await(Settings settings, long timeoutNanos) {
        CgPrecache.collect(this);
        warmedFor = settings;
        for (CgMaterial m : list) pending.put(m, Boolean.TRUE);
        long end = System.nanoTime() + timeoutNanos;
        while (true) {
            for (int i = 0; i < list.size(); i++) {
                CgMaterial m = list.get(i);
                if (pending.containsKey(m) && preparer.prepare(m, halves.get(m), skinned.containsKey(m), settings)) pending.remove(m);
            }
            boolean kernelsBuilt = pollKernels();
            if (pending.isEmpty() && kernelsBuilt || System.nanoTime() > end) break;
            LockSupport.parkNanos(200_000L);
        }
        return pending();
    }

    /** How many materials and kernels are not yet built under the settings in force. */
    public int pending() {
        return pending.size() + unbuilt.size();
    }

    /** How many materials and kernels it keeps built. */
    public int size() {
        return list.size() + kernels.size();
    }

    /**
     * Once a frame: polls what is pending under {@code requested}, re-warming the whole list when it differs from what
     * was warmed for. Answers whether {@code requested} may apply: the list is built under it, or the wait is spent.
     */
    boolean frame(Settings requested, Settings applied) {
        CgPrecache.collect(this);
        if (!unbuilt.isEmpty() && CgWarmBudget.allows()) pollKernels();
        if (!requested.equals(warmedFor)) {
            warmedFor = requested;
            for (CgMaterial m : list) pending.put(m, Boolean.TRUE);
        }
        if (!pending.isEmpty()) {
            for (int i = 0; i < list.size(); i++) {
                CgMaterial m = list.get(i);
                if (pending.containsKey(m) && preparer.prepare(m, halves.get(m), skinned.containsKey(m), requested)) pending.remove(m);
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
