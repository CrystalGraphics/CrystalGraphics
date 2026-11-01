package com.crystalgraphics.vfx;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshLods;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.asset.load.CgAssetFollower;
import com.crystalgraphics.asset.load.CgAssetRef;
import com.crystalgraphics.asset.load.CgPreload;
import com.crystalgraphics.collision.CgCollisionWorld;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.gl.material.CgMaterialShader;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.CgWarmBudget;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.render.graph.CgRehearsal;
import com.crystalgraphics.frame.CgFrameBoundary;
import com.crystalgraphics.render.stage.CgHostEnvironment;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgPipelineList;
import com.crystalgraphics.render.world.CgPipelinePrecache;
import com.crystalgraphics.render.world.CgSortLayer;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.scalability.CgVfxBudget;
import com.crystalgraphics.vfx.scalability.CgVfxScalability;
import com.crystalgraphics.vfx.particle.CgVfxAir;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEventListener;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.gpu.draw.CgVfxParticleLights;
import com.crystalgraphics.vfx.particle.gpu.draw.CgVfxRange;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxRecord;
import com.crystalgraphics.vfx.particle.renderer.CgVfxMeshRendererProperties;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRendererProperties;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRibbonRendererProperties;
import com.crystalgraphics.vfx.particle.ribbon.CgVfxRibbonGeneration;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxShape;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxShapeCoalescing;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.path.CgVfxPathTexture;
import com.crystalgraphics.vfx.render.CgVfxRibbons;
import com.crystalgraphics.vfx.render.CgVfxSortGroup;
import com.crystalgraphics.vfx.render.CgVfxTube;
import com.crystalgraphics.vfx.system.CgVfxBehaviour;
import com.crystalgraphics.vfx.system.CgVfxSystemDefinition;
import com.crystalgraphics.vfx.system.CgVfxSystemInstance;
import com.crystalgraphics.vfx.system.CgVfxSystemInstancePool;
import com.crystalgraphics.render.world.CgWorldVoxelWindow;
import com.crystalgraphics.world.CgAttachment;
import com.crystalgraphics.world.CgEntityQueries;
import com.crystalgraphics.world.CgWorldQueries;
import com.crystalgraphics.vfx.particle.gpu.CgVfxDrawLayout;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Quaternionf;

import javax.annotation.Nullable;
import com.crystalgraphics.vfx.particle.spawn.CgVfxSpawnFrom;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Plays {@link CgVfxEffect}s: simulates them on a fixed tick, draws them into a world each frame, and owns what they
 * share on the GPU (the path texture, the meshes, a material per material definition).
 *
 * <pre>{@code
 * CgVfxSystem vfx = new CgVfxSystem();
 * CgEnergyWave wave = vfx.play(new CgEnergyWave(CgEnergyWave.kamehameha(), x, y, z));
 * // every frame, before the world stages record (a tick function in CgWorld.client().schedule(), or ahead of a harness's fire):
 * vfx.update(seconds);
 * vfx.submit(CgWorldRenderer.get());
 * // once, when the context goes:
 * vfx.delete();
 * }</pre>
 *
 * <ul>
 *   <li>{@link #update} runs {@link #TICK}-second steps, as many as the clock owes, at most {@value #MAX_TICKS} a
 *       call and none past {@code -Dcrystalgraphics.vfx.simBudgetMs} (12) of catching up, so a hitch or more particles
 *       than the CPU keeps up with slows effects down rather than stalling the frame. It touches no GPU state.</li>
 *   <li>GPU particles step when {@code WORLD_OPAQUE} records: while the world is not drawn every effect holds, once half
 *       a second of steps waits ({@code vfx.ticks.held}).</li>
 *   <li>Each tick steps every effect in order, then books their particle emitters for the GPU, on several threads
 *       once many effects have emitters due ({@link CgJob#forEach}; {@code -Dcrystalgraphics.job.forEachThreads}).</li>
 *   <li>{@link #submit} is render thread, and must run every frame an effect draws: the path texture holds only the
 *       last upload.</li>
 *   <li>Every mesh the package draws is made here, so a change to how meshes are made is one edit.</li>
 *   <li>{@link #air} is the wind every effect's particles move through; set it once, or change it while playing.</li>
 *   <li>It applies the player's settings ({@link CgGraphicsSettings}) to every effect: particle density and the
 *       quality tier, read once an update. Its clock stops while the game is paused or {@code /tick freeze} holds the
 *       world, and runs at the world's tick rate.</li>
 * </ul>
 */
public final class CgVfxSystem {

    private static final CgVar.Text SKIP = CgVar.text("crystalgraphics.vfx.skip", "", CgVar.DEV,
            "Comma-separated: layers whose shader path (light/<id> for a slot's light) contains any of these draw nothing,"
                    + " to see what a frame's GPU time is spent on, one profile per group (light/,haze).");
    private static final CgVar.Num SIM_BUDGET_MS = CgVar.number("crystalgraphics.vfx.simBudgetMs", 12f, 0f, 1000f, CgVar.DEV,
            "Wall milliseconds an update may spend catching up ticks before it drops the rest and effects slow down.");
    // Measurement switches: each is what one saving on the beams' GPU time would be worth.
    private static final CgVar.Bool COARSE_VOLUMES = CgVar.bool("crystalgraphics.vfx.coarseVolumes", false,
            CgVar.DEV | CgVar.RESTART, "Measurement: volume layers on a 12 x 24 sphere instead of 48 x 96.");
    private static final CgVar.Bool SHARED_DISTORTION = CgVar.bool("crystalgraphics.vfx.sharedDistortion", false, CgVar.DEV,
            "Measurement: distortion layers after every effect's draws, so they share one copy of the target.");

    /**
     * Layers whose shader path ({@code light/<id>} for a slot's light) contains any of these draw nothing, in every
     * system: to see or time a frame without them. {@code -Dcrystalgraphics.vfx.skip=haze,light/} sets it at launch and
     * {@link #skip(String...)} live.
     *
     * <pre>{@code
     * CgVfxSystem.skip("light/");                   // every slot's light off
     * CgVfxSystem.skip();                           // everything back
     * }</pre>
     */
    public static String[] skipped() {
        return skipped.clone();
    }

    /** Sets {@link #skipped()} for every system, from its next submit. A person's switch, outranking {@code -D}. */
    public static void skip(String... parts) {
        SKIP.set(CgVar.Source.CONSOLE, String.join(",", parts));
    }

    private static String[] parts(String list) {
        return list.isEmpty() ? new String[0] : list.split(",");
    }

    static String[] skippedParts() {
        return skipped;
    }

    /** What {@link #update} and {@link #submit} allocate on their thread, reported per frame under these groups. */
    private static final int UPDATE_ALLOCATION = CgTrace.name("vfx.update"), SUBMIT_ALLOCATION = CgTrace.name("vfx.submit");
    private static final int SCALABILITY_ZONE = CgTrace.name("vfx.scalability"), ADOPT_ZONE = CgTrace.name("vfx.adopt");
    private static final int TICK_ZONE = CgTrace.name("vfx.tick"), SUBMIT_ZONE = CgTrace.name("vfx.submit"),
            WARM_ZONE = CgTrace.name("vfx.warm"), EFFECT_ZONE = CgTrace.name("vfx.effect.submit"),
            PATHS_ZONE = CgTrace.name("vfx.paths.upload"), PARTICLES_ZONE = CgTrace.name("vfx.frame.particles"),
            FRAME_END_ZONE = CgTrace.name("vfx.frame.end"), ROWS_ZONE = CgTrace.name("vfx.rows.upload"), MATERIALS_ZONE = CgTrace.name("vfx.precache.materials"),
            EMITTER_ZONE = CgTrace.name("vfx.precache.emitter"), MESHES_ZONE = CgTrace.name("vfx.meshes"),
            ALLOCATE_ZONE = CgTrace.name("vfx.warm.allocate"), POLL_ZONE = CgTrace.name("vfx.warm.poll"),
            CULLED = CgTrace.name("vfx.culled"), ASLEEP = CgTrace.name("vfx.asleep"),
            TICKS = CgTrace.name("vfx.ticks"), CAPPED = CgTrace.name("vfx.ticks.capped"), HELD = CgTrace.name("vfx.ticks.held"),
            EFFECTS_HELD = CgTrace.name("vfx.effects-held"),
            EMITTERS_ZONE = CgTrace.name("vfx.emitters"), EFFECT_TICK_ZONE = CgTrace.name("vfx.effect.tick"),
            WORKERS_ZONE = CgTrace.name("vfx.emitters.workers"), ADMIT_ZONE = CgTrace.name("vfx.emitters.admit"),
            GPU_STEPS_ZONE = CgTrace.name("vfx.gpu.steps"),
            EFFECTS = CgTrace.name("vfx.effects");

    /** Seconds of one simulation step. */
    public static final float TICK = 1f / 120f;
    /** Seconds a game tick lasts: what a hitbox's time counts ({@link CgVfxEffect#hitbox}). */
    public static final float GAME_TICK = 0.05f;
    /** The most of an effect's life {@link CgVfxEffect#startAt} steps through; past it the age jumps. */
    public static final float MAX_START_SECONDS = 30f;
    /** The most of an effect's last seconds before its start age whose particles a prewarm makes. */
    public static final float PREWARM_SECONDS = 2f;
    /** GPU steps a prewarm takes at most, each a dispatch per pool: the window is stepped this coarsely. */
    private static final int PREWARM_STEPS = 16;
    private static final Logger LOGGER = LogManager.getLogger("CgVfxSystem");
    private static volatile String[] skipped = parts(SKIP.get());

    static {
        SKIP.onChange(list -> skipped = parts(list));
    }
    /**
     * Fewer effects with emitters due than this book them on the render thread alone (0.25 us each): workers woken
     * for less could take the render thread's core on a loaded machine (33 ms, mc-perf-notes).
     */
    private static final CgVar.Int PARALLEL_BOOKS = CgVar.integer("crystalgraphics.vfx.parallelBooks", 4096, 1,
            Integer.MAX_VALUE, CgVar.DEV, "Effects with emitters due a tick past which their books run on the workers,"
                    + " in batches of one simulation's effects; fewer book on the render thread alone.");
    /**
     * Effects a worker books in one task: Niagara cuts each simulation's instances into batches of
     * {@code fx.Niagara.SystemSimulation.TickBatchSize} (NiagaraSystemSimulation.cpp:104). A book is ~0.25 us, so a
     * batch this size is ~64 us, long enough to be worth waking a worker for.
     */
    private static final CgVar.Int BOOK_BATCH = CgVar.integer("crystalgraphics.vfx.bookBatch", 256, 1,
            Integer.MAX_VALUE, CgVar.DEV, "Effects of one simulation a worker books in one task.");
    private static final int MAX_TICKS = 12;
    private static final String PATH_SAMPLER = "_FxPath";

    private static final CgSortLayer DISTORTION = CgSortLayer.after("crystalgraphics:vfx-distortion", CgSortLayer.EFFECTS);

    private final List<CgVfxEffect<?>> effects = new ArrayList<>();
    /** Each definition's and ref's pool of idle instances ({@link #spawn}). */
    private final Map<Object, CgVfxSystemInstancePool> instancePools = new IdentityHashMap<>();
    /** Pooled effects that died, each back to its pool once its slots have closed. */
    private final List<CgVfxEffect<?>> reclaiming = new ArrayList<>();
    private final List<CgVfxMomentListener> momentListeners = new ArrayList<>();
    private final List<CgVfxEventListener> eventListeners = new ArrayList<>();
    private final CgVfxPathTexture paths = new CgVfxPathTexture();
    private final CgVfxTube tube = new CgVfxTube();
    /** Every layer's resolved bindings and materials, and the frame's rows. */
    private final CgVfxMaterials materials = new CgVfxMaterials();
    private final CgVfxFrame frame = new CgVfxFrame(this, materials);
    /** How many of {@link #materials}' materials have the path texture bound. */
    private int pathsBound;
    /** The materials a draw step just named, scratch for {@link #addWarming}. */
    private final List<CgMaterial> warming = new ArrayList<>();
    /** Those of them drawn at half size, whose half-size passes are requested too. */
    private final Set<CgMaterial> halved = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Each look's precache request, and each re-warm a coalesce asked, until built. */
    private final List<CgPipelinePrecache.Request> lookRequests = new ArrayList<>();
    /** Each look's meshes read ahead, until read. */
    private final List<CgPreload.Request> preloads = new ArrayList<>();
    /** Each emitter it precached and its feed tier: warmed again when coalescing moves it to another shape. */
    private final Map<CgVfxEmitter, Integer> precachedTier = new WeakHashMap<>();
    /** Emitters whose kernels failed to precache: logged once and skipped, as Niagara disables an emitter that fails. */
    private final Set<CgVfxEmitter> unprecached = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Pools a precache expected into, allocated once every look's request is built. */
    private final Set<CgVfxParticlePool> expected = Collections.newSetFromMap(new IdentityHashMap<>());
    private CgTexture2D boundTexture;
    // The tube is this system's own; the ribbons, sphere and quads are shared.
    private CgMesh tubeMesh, closedTubeMesh, ribbonMesh, sphereMesh, volumeMesh;
    private final CgVfxAir air = new CgVfxAir();
    /** The effects that queued emitter ticks this tick, in play order: the order they are admitted in. */
    private final List<CgVfxEffect<?>> emitting = new ArrayList<>();
    /** One simulation per definition playing ({@link CgVfxEffect#definitionKey}), by identity. */
    private final Map<Object, CgVfxSystemSimulation> simulations = new IdentityHashMap<>();
    /** The simulations with books due this tick. */
    private final List<CgVfxSystemSimulation> booking = new ArrayList<>();
    /** This tick's batches, parallel: the simulation, and the first of its due effects the batch books. */
    private CgVfxSystemSimulation[] batchSimulation = new CgVfxSystemSimulation[8];
    private int[] batchFrom = new int[8];
    /** The thread splitting the books: what a batch takes elsewhere is the workers' time. */
    private Thread booker;
    private final IntConsumer bookEach = i -> {
        long began = System.nanoTime();
        batchSimulation[i].book(batchFrom[i], BOOK_BATCH.get());
        if (Thread.currentThread() != booker) CgVfxBudget.WORKERS.add(System.nanoTime() - began);
    };

    /** The owners whose effects it has played, for their counters. */
    private final List<CgVfxOwner> owners = new ArrayList<>();
    private final double[] point = new double[3], box = new double[6];
    private final Quaternionf turn = new Quaternionf();
    /** The world whose time dilation scales every effect's time; null for a system that runs on real pace alone. */
    private CgWorld world;
    private final CgVfxGpuSteps gpuSteps = new CgVfxGpuSteps();
    /** Whether a look's request has named Range's kernels: once a system. */
    private boolean rangeRequested;
    /** Looks this system has requested: held weakly. */
    private final Set<CgVfxLook> precachedLooks = Collections.newSetFromMap(new WeakHashMap<>());
    private double clock = Double.NaN;
    private float owed, simulated;
    private float density = 1f;
    private CgQuality quality = CgQuality.HIGH;
    private final CgVfxCulling culling = new CgVfxCulling();
    /** The frame {@link #scalability} last ran for, and whether its clock had started then. */
    private double scaledAt = Double.NaN;
    private boolean stepping;
    /** The budget's adjusted usage as of its last scalability pass. */
    private float usage;

    /**
     * {@code definition} played at {@code (x, y, z)} from its pool: an idle instance started again, or a new one, handed
     * back to the pool once it completes and its particles are gone. Unreal's {@code SpawnSystemAtLocation} with
     * {@code AutoRelease} pooling. Render thread.
     *
     * <pre>{@code
     * vfx.spawn(SPARK_REF, hitX, hitY, hitZ).ground(floorY);   // as play(new CgVfxSystemInstance<>(SPARK_REF, ...))
     * }</pre>
     *
     * Hold nothing of what it answers past its completion: the next spawn may take it again.
     */
    public <B extends CgVfxBehaviour> CgVfxSystemInstance<B> spawn(CgVfxSystemDefinition definition, double x, double y, double z) {
        return play(pool(definition).<B>acquire(x, y, z));
    }

    /** As {@link #spawn(CgVfxSystemDefinition, double, double, double)}, its instances following {@code ref}. */
    public <B extends CgVfxBehaviour> CgVfxSystemInstance<B> spawn(CgAssetRef<CgVfxSystemDefinition> ref, double x, double y, double z) {
        return play(pool(ref).<B>acquire(x, y, z));
    }

    /** {@code definition}'s pool on this system, made at the first ask: what {@link #spawn} takes from. */
    public CgVfxSystemInstancePool pool(CgVfxSystemDefinition definition) {
        synchronized (instancePools) {
            CgVfxSystemInstancePool pool = instancePools.get(definition);
            if (pool == null) instancePools.put(definition, pool = new CgVfxSystemInstancePool(definition));
            return pool;
        }
    }

    /** {@code ref}'s pool on this system, made at the first ask; a READY asset primes it. */
    public CgVfxSystemInstancePool pool(CgAssetRef<CgVfxSystemDefinition> ref) {
        synchronized (instancePools) {
            CgVfxSystemInstancePool pool = instancePools.get(ref);
            if (pool == null) instancePools.put(ref, pool = new CgVfxSystemInstancePool(ref));
            return pool;
        }
    }

    public <E extends CgVfxEffect<?>> E play(E effect) {
        if (effect.system != null) throw new IllegalStateException("already played: " + effect);
        effect.system = this;
        effects.add(effect);
        joinSimulation(effect);
        precull(effect);
        CgVfxOwner owner = effect.owner;
        if (owner != null) {
            if (owner.listedIn != this) {
                owner.listedIn = this;
                owners.add(owner);
            }
            owner.adopt(effect);
        }
        return effect;
    }

    /**
     * Runs this system on {@code world}'s clock: its {@link CgWorld#timeDilation} scales everything it plays, on top of
     * the host's pause and tick rate.
     *
     * <pre>{@code
     * CgVfx.world().world(preview);
     * preview.timeDilation(0.25f);   // slow motion for everything on the system
     * }</pre>
     */
    public CgVfxSystem world(CgWorld world) {
        this.world = world;
        return this;
    }

    /** Hears every effect's named moments from now on: the visual debugging hook ({@link CgVfxMomentListener}). */
    public void onMoment(CgVfxMomentListener listener) {
        momentListeners.add(listener);
    }

    /**
     * Hears the rows of every event marked {@code readback}, from either simulation: decals, sounds, gameplay hooks
     * ({@link CgVfxEventListener}). Render thread; the GPU path's rows arrive a few frames after their step.
     *
     * <pre>{@code
     * vfx.onEvents((definition, event, rows) -> {
     *     for (int i = 0; i < rows.count(); i++) sounds.play(HISS, rows.x(i), rows.y(i), rows.z(i));
     * });
     * }</pre>
     */
    public void onEvents(CgVfxEventListener listener) {
        eventListeners.add(listener);
        CgVfxParticlePool.listen(listener);
    }

    boolean hasMomentListeners() {
        return !momentListeners.isEmpty();
    }

    void moment(CgVfxEffect<?> effect, String name, double x, double y, double z, float radius) {
        for (int i = 0; i < momentListeners.size(); i++) momentListeners.get(i).moment(effect, name, x, y, z, radius);
    }

    /** The air the particles of every effect move through. */
    public CgVfxAir air() {
        return air;
    }

    /**
     * Advances every effect to {@code seconds} on the clock the caller keeps, at the world's pace: not at all while it is
     * paused or frozen, slower or faster under {@code /tick rate}.
     */
    public void update(double seconds) {
        long began = System.nanoTime(), mark = CgTrace.allocationBegin(CgVfxTrace.CHANNEL);
        try {
            updateTimed(seconds);
        } finally {
            CgVfxBudget.FRAME.add(System.nanoTime() - began);
            CgTrace.allocationEnd(CgVfxTrace.CHANNEL, UPDATE_ALLOCATION, mark);
        }
    }

    /**
     * Re-reads every followed point as late as the frame allows, after the effects ticked and before they draw: an
     * entity's freshest mark, a posed mesh's socket. What an effect draws at its origin then sits on the point as it is
     * drawn this frame (Unreal's late update), while its logic ticked against the point before. The client world's
     * system runs it in {@code LATE_ATTACH}; a system of the caller's own calls it between {@code update} and
     * {@code submit}.
     */
    public void lateAttach() {
        follow();
    }

    /**
     * Its scalability pass for the frame at {@code seconds}: the settings read, followed points placed, the clock
     * advanced, and every effect culled, lowered or woken against the view. The client world's system runs it in
     * {@code SCALABILITY}, before any effect ticks; {@link #update} runs it itself where nothing did. Once per frame.
     */
    public void scalability(double seconds) {
        if (seconds == scaledAt) return;
        scaledAt = seconds;
        CgHostEnvironment world = CgRenderStage.WORLD_OPAQUE.host().environment();
        readSettings(world);
        follow();
        resolveRates();
        if (Double.isNaN(clock)) {
            clock = seconds;
            stepping = false;
            return;
        }
        float passed = (float) (seconds - clock) * pace(world) * (this.world == null ? 1f : this.world.timeDilation());
        owed = Math.max(0f, owed + passed);
        clock = seconds;
        cull(passed);
        stepping = true;
    }

    private void updateTimed(double seconds) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgVfxTrace.CHANNEL, SCALABILITY_ZONE)) {
            scalability(seconds);
        }
        if (!stepping) return;
        // WORLD_OPAQUE records the GPU's one step a frame. While it has not, every effect holds, as Niagara and VFX
        // Graph pause what is not drawn: a second step would need recording, and a dropped one would leave particles
        // alive in a slot its books close as finished.
        if (gpuSteps.queuedSteps() > 0) {
            owed = 0f;
            CgVfxTrace.count(HELD, 1);
            return;
        }
        try (CgTrace.Zone ignored = CgTrace.zone(CgVfxTrace.CHANNEL, ADOPT_ZONE)) {
            adoptDefinitions();
            startLate();
        }
        int ticks = 0;
        long budget = (long) (SIM_BUDGET_MS.get() * 1_000_000L), start = System.nanoTime();
        boolean overBudget = false;
        // one batch: every effect's queries and hitboxes this update share what they learn of the entities
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, "vfx.sim");
             CgCollisionWorld.Batch batch = CgCollisionWorld.batch()) {
            while (owed >= TICK && ticks < MAX_TICKS && !overBudget) {
                try (CgTrace.Zone tick = CgTrace.zone(CgVfxTrace.CHANNEL, TICK_ZONE)) {
                    air.tick(simulated);
                    for (int i = 0; i < effects.size(); i++) {
                        CgVfxEffect<?> effect = effects.get(i);
                        if (effect.state() != CgVfxEffect.State.DEAD && !effect.asleep && effect.timeRate > 0f
                                && activated(effect)) {
                            int zone = effect.owner != null ? effect.owner.tickZone : EFFECT_TICK_ZONE;
                            try (CgTrace.Zone stepping = CgTrace.zone(CgVfxTrace.CHANNEL, zone)) {
                                effect.step(TICK * effect.timeRate);
                            } catch (RuntimeException e) {
                                fail(effect, e);
                            }
                        }
                        if (effect.hasEmitterTicks()) {
                            emitting.add(effect);
                            effect.simulation.due(effect, booking);
                        }
                    }
                    try (CgTrace.Zone run = CgTrace.zone(CgVfxTrace.CHANNEL, EMITTERS_ZONE)) {
                        if (emitting.size() >= PARALLEL_BOOKS.get()) {
                            int batches = batchesOf(booking);
                            if (batches > 1) {
                                try (CgTrace.Zone ticking = CgTrace.zone(CgVfxTrace.CHANNEL, WORKERS_ZONE)) {
                                    booker = Thread.currentThread();
                                    CgJob.forEach(batches, bookEach);
                                }
                            } else {
                                bookAll();
                            }
                        } else {
                            bookAll();
                        }
                        try (CgTrace.Zone admitting = CgTrace.zone(CgVfxTrace.CHANNEL, ADMIT_ZONE)) {
                            for (int i = 0; i < emitting.size(); i++) {
                                CgVfxEffect<?> effect = emitting.get(i);
                                if (effect.failure != null) {
                                    fail(effect, effect.failure);
                                    effect.failure = null;
                                }
                                // A failed effect's instances already scheduled still take their slots, and coast.
                                effect.admitScheduled(gpuSteps);
                            }
                        }
                    } finally {
                        emitting.clear();
                        for (int i = 0; i < booking.size(); i++) booking.get(i).clearDue();
                        booking.clear();
                    }
                    gpuSteps.book(ticks * TICK);
                }
                simulated += TICK;
                owed -= TICK;
                ticks++;
                overBudget = System.nanoTime() - start >= budget;
            }
        }
        if (ticks > 0) {
            // One GPU step over the update's particle time; each tick's births at their offsets within it.
            try (CgTrace.Zone steps = CgTrace.zone(CgVfxTrace.CHANNEL, GPU_STEPS_ZONE)) {
                gpuSteps.step(ticks * TICK, air);
            }
        }
        CgVfxTrace.count(TICKS, ticks);
        CgTrace.counter(CgVfxTrace.CHANNEL, EFFECTS, effects.size());
        // Stopped short of the clock: drop the debt, so a frame that cannot keep up slows effects down rather than
        // owing more each frame than the last.
        if (owed >= TICK) {
            CgVfxTrace.count(CAPPED, 1);
            owed = Math.min(owed, TICK);
        }
        removeDead();
        for (int i = 0; i < owners.size(); i++) {
            CgTrace.counter(CgVfxTrace.CHANNEL, owners.get(i).effectsCounter, owners.get(i).playing());
        }
    }

    /** One pass, keeping the survivors' order: many die in one tick when a volley ends. */
    private void removeDead() {
        int kept = 0;
        for (int i = 0; i < effects.size(); i++) {
            CgVfxEffect<?> effect = effects.get(i);
            if (effect.state() == CgVfxEffect.State.DEAD) {
                // Killed, its particles go with it; one that died on its own lets them finish.
                if (effect.killed) effect.killSlots(gpuSteps);
                if (effect.owner != null) effect.owner.release(effect);
                effect.leaveGroup();
                leaveSimulation(effect);
                if (effect.pooled()) reclaiming.add(effect);
                continue;
            }
            if (kept != i) effects.set(kept, effect);
            kept++;
        }
        for (int i = effects.size() - 1; i >= kept; i--) effects.remove(i);
        reclaim(false);
    }

    /**
     * Hands each dead pooled effect back to its pool once its slots have closed, or every one at once ({@code all}),
     * as Unreal's pool waits for a component to complete before {@code Reclaim}.
     */
    private void reclaim(boolean all) {
        for (int i = reclaiming.size() - 1; i >= 0; i--) {
            CgVfxEffect<?> effect = reclaiming.get(i);
            if (!all && effect.holdsSlots(gpuSteps)) continue;
            int last = reclaiming.size() - 1;
            reclaiming.set(i, reclaiming.get(last));
            reclaiming.remove(last);
            effect.system = null;
            effect.reclaim();
        }
    }

    /** Its simulation is dropped with its last effect. */
    private void bookAll() {
        for (int i = 0; i < booking.size(); i++) booking.get(i).book(0, Integer.MAX_VALUE);
    }

    /** Cuts every queued simulation into batches of {@link #BOOK_BATCH} effects; fills the batch arrays. */
    private int batchesOf(List<CgVfxSystemSimulation> queued) {
        int n = 0;
        int size = BOOK_BATCH.get();
        for (int i = 0; i < queued.size(); i++) {
            CgVfxSystemSimulation simulation = queued.get(i);
            for (int from = 0; from < simulation.dueCount(); from += size) {
                if (n == batchSimulation.length) {
                    batchSimulation = Arrays.copyOf(batchSimulation, n * 2);
                    batchFrom = Arrays.copyOf(batchFrom, n * 2);
                }
                batchSimulation[n] = simulation;
                batchFrom[n++] = from;
            }
        }
        return n;
    }

    private void joinSimulation(CgVfxEffect<?> effect) {
        Object key = effect.definitionKey();
        CgVfxSystemSimulation simulation = simulations.get(key);
        if (simulation == null) simulations.put(key, simulation = new CgVfxSystemSimulation(key));
        simulation.add(effect);
        effect.simulation = simulation;
    }

    /** Its simulation goes with its last effect, unless that one is pooled: its next play would make it again. */
    private void leaveSimulation(CgVfxEffect<?> effect) {
        if (effect.simulation.remove(effect) && !effect.pooled()) simulations.remove(effect.simulation.key());
    }

    /**
     * Effects whose asset published a newer definition take it ({@link CgVfxEffect#adopt}), each into its new
     * definition's simulation; then every slot whose instance took a newer emitter moves to that emitter's numbers.
     */
    private void adoptDefinitions() {
        boolean values = false;
        for (int i = 0; i < effects.size(); i++) {
            CgVfxEffect<?> effect = effects.get(i);
            if (effect.state() == CgVfxEffect.State.DEAD) continue;
            CgAssetFollower.Adoption adoption;
            try {
                adoption = effect.adopt();
            } catch (RuntimeException e) {
                fail(effect, e);
                continue;
            }
            if (adoption == CgAssetFollower.Adoption.NONE) continue;
            values |= adoption == CgAssetFollower.Adoption.VALUE;
            if (effect.definitionKey() != effect.simulation.key()) {
                leaveSimulation(effect);
                joinSimulation(effect);
            }
        }
        if (values) gpuSteps.rebind();
    }

    /** Every owner it lists lets go of its effects at once. */
    private void releaseOwners() {
        for (int i = 0; i < owners.size(); i++) owners.get(i).releaseAll();
    }

    /** Kills {@code effect} over what it threw, logged under its owner; the rest play on. */
    private static void fail(CgVfxEffect<?> effect, RuntimeException e) {
        LOGGER.error("[CgVfxSystem] {} played by {} threw, and is killed", effect.getClass().getName(),
                effect.owner != null ? effect.owner : "its system", e);
        effect.kill();
    }

    /** Each effect's pace this update: its character's clock, else 1, times its own dilation. */
    private void resolveRates() {
        float partialTick = Float.NaN;
        for (int i = 0; i < effects.size(); i++) {
            CgVfxEffect<?> effect = effects.get(i);
            if (effect.followingId() < 0 && !effect.hasClock() && !effect.dilated()) continue;
            if (Float.isNaN(partialTick)) partialTick = CgRenderStage.WORLD_OPAQUE.host().partialTick();
            effect.resolveRate(partialTick);
        }
    }

    /**
     * Places each effect on what it rides as it stands this frame (an entity's point as drawn, a mesh's socket as posed,
     * a sequence's locator); stops one whose attachment is gone, where it was last.
     */
    private void follow() {
        for (int i = 0; i < effects.size(); i++) {
            CgVfxEffect<?> effect = effects.get(i);
            if (effect.state() == CgVfxEffect.State.DEAD) continue;
            effect.bindFollow(CgEntityQueries.client());
            CgAttachment attachment = effect.followAttachment;
            if (attachment == null) continue;
            // What moves it (a mesh posed in POSE, a sequence in SEQUENCES) has run this frame, before EFFECTS.
            if (!attachment.place(point, turn)) {
                effect.lostFollow();
                continue;
            }
            effect.moveToFollowed(point[0], point[1], point[2]);
            effect.turnToFollowed(turn);
            if (attachment.bounds(box)) {
                effect.followBoxX = box[0];
                effect.followBoxY = box[1];
                effect.followBoxZ = box[2];
                effect.followHalfX = (float) box[3];
                effect.followHalfY = (float) box[4];
                effect.followHalfZ = (float) box[5];
            }
        }
    }

    /**
     * Its scalability pass, {@code passed} simulated seconds after the last: each effect culled, lowered or woken against
     * the world's latest camera, and what is culled put to sleep, stopped or killed as its scalability says.
     */
    private void cull(float passed) {
        CgVfxBudget budget = CgVfxBudget.get();
        budget.tick(quality);
        usage = budget.worstAdjustedUsage();
        culling.view(CgRenderStage.WORLD_OPAQUE.host().view());
        culling.run(effects, passed, CgEntityQueries.client().localPlayer(), usage);
        int asleep = 0;
        for (int i = 0; i < effects.size(); i++) {
            CgVfxEffect<?> effect = effects.get(i);
            if (effect.state() == CgVfxEffect.State.DEAD) continue;
            if (effect.asleep) {
                if (effect.wanted) {
                    effect.wake();
                    continue;
                }
                effect.sleptFor += passed;
                if (effect.sleptFor > CgVfxScalability.MAX_SLEEP_SECONDS) effect.kill();
                asleep++;
                continue;
            }
            if (effect.wanted) continue;
            CgVfxTrace.count(CULLED, 1);
            switch (effect.scalability().reaction()) {
                case SLEEP -> {
                    effect.sleep(gpuSteps);
                    asleep++;
                }
                case STOP -> effect.culled(false);
                case KILL -> effect.culled(true);
            }
        }
        CgTrace.counter(CgVfxTrace.CHANNEL, ASLEEP, asleep);
    }

    /**
     * A play the camera is past its scalability's distance from, or that its scalability culls at the budget's usage,
     * never starts, or starts asleep.
     */
    private void precull(CgVfxEffect<?> effect) {
        if (effect.authoritative() || !culling.viewed()) return;
        CgVfxScalability s = effect.scalability();
        if (!s.culls()) return;
        boolean far = culling.distanceTo(effect.originX, effect.originY, effect.originZ) > s.maxDistance() * s.distanceScale(usage);
        if (!far && usage < s.cullUsage()) return;
        // The local player's own are kept, as the scalability pass keeps them.
        int local = CgEntityQueries.client().localPlayer();
        if (local >= 0 && effect.followingId() == local) return;
        CgVfxTrace.count(CULLED, 1);
        if (s.reaction() == CgVfxScalability.Reaction.SLEEP) {
            effect.asleep = true;
            effect.wanted = false;
        } else {
            effect.culled(true);
        }
    }

    /** Steps each effect played with a start age to it, ahead of this update's ticks. */
    private void startLate() {
        for (int i = 0; i < effects.size(); i++) {
            CgVfxEffect<?> effect = effects.get(i);
            if (effect.startAge <= 0f || effect.asleep) continue;
            float target = effect.startAge;
            effect.startAge = 0f;
            if (effect.state() == CgVfxEffect.State.DEAD) continue;
            effect.seeking = true;
            try {
                fastForward(effect, target);
            } catch (RuntimeException e) {
                fail(effect, e);
            } finally {
                effect.seeking = false;
                gpuSteps.endPrewarm();
            }
        }
    }

    /**
     * Steps {@code effect} alone through {@code target} seconds on its fixed tick. On the GPU its emitters keep their
     * books throughout and take slots only for the last {@link #PREWARM_SECONDS}, stepped in at most
     * {@link #PREWARM_STEPS} steps while every other slot holds still: older particles would be dead by now.
     */
    private void fastForward(CgVfxEffect<?> effect, float target) {
        float from = effect.age, stepped = Math.min(target, MAX_START_SECONDS);
        int ticks = Math.round(stepped / TICK), windowTicks = Math.round(Math.min(stepped, PREWARM_SECONDS) / TICK);
        int windowFrom = ticks - windowTicks;
        int coarse = Math.max(1, (windowTicks + PREWARM_STEPS - 1) / PREWARM_STEPS), booked = 0;
        for (int t = 0; t < ticks && effect.state() != CgVfxEffect.State.DEAD; t++) {
            boolean inWindow = t >= windowFrom;
            if (t == windowFrom) {
                gpuSteps.beginPrewarm();
                effect.admitScheduled(gpuSteps);
            }
            effect.step(TICK);
            if (effect.hasEmitterTicks()) {
                effect.tickEmitters();
                if (effect.failure != null) throw effect.failure;
                // Before the window its instances keep their books alone: admitted at its start.
                if (inWindow) effect.admitScheduled(gpuSteps);
            }
            if (!inWindow) continue;
            // The window's ticks booked, then stepped in at most PREWARM_STEPS steps of coarse ticks each.
            gpuSteps.prewarmBook(booked * TICK);
            if (++booked == coarse || t == ticks - 1) {
                gpuSteps.prewarmStep(booked * TICK, air);
                booked = 0;
            }
        }
        if (target > stepped) effect.age = from + target;
    }

    /** {@code effect}'s simulation: one per definition playing. */
    CgVfxSystemSimulation simulation(CgVfxEffect<?> effect) {
        return effect.simulation;
    }

    /** How many simulations it runs: one per definition playing. */
    int simulations() {
        return simulations.size();
    }

    /** Its GPU queue: the pools' owner. */
    CgVfxGpuSteps gpuSteps() {
        return gpuSteps;
    }

    /** {@code emitter}'s slot in a GPU pool, or null when it holds none: stepped on the CPU, or every particle dead. */
    CgVfxGpuSteps.Tenant gpuTenant(CgVfxEmitterInstance emitter) {
        return gpuSteps.of(emitter);
    }

    /** The share of {@code emitter}'s particles to spawn: the player's density, halved again at Low for an optional one. */
    public float spawnShare(CgVfxEmitter emitter) {
        return spawnShare(emitter, 0);
    }

    /** As {@link #spawnShare(CgVfxEmitter)}, for an effect drawn {@code lowered} tiers below this update's. */
    float spawnShare(CgVfxEmitter emitter, int lowered) {
        return emitter.optional() && quality(lowered) == CgQuality.LOW ? density * 0.5f : density;
    }

    /** The quality tier this update draws at. */
    public CgQuality quality() {
        return quality;
    }

    /** The tier {@code lowered} below this update's, Low at the least. */
    CgQuality quality(int lowered) {
        return TIERS[Math.max(0, quality.ordinal() - lowered)];
    }

    private static final CgQuality[] TIERS = CgQuality.values();

    private void readSettings(CgHostEnvironment world) {
        density = CgGraphicsSettings.DENSITY.get();
        if (CgGraphicsSettings.FOLLOW_MINECRAFT_PARTICLES.get()) density *= world.particleShare();
        quality = CgGraphicsSettings.QUALITY.get();
    }

    /** Simulated seconds per real second for {@code world}: 0 while paused or frozen, the tick rate over 20 otherwise. */
    public static float pace(CgHostEnvironment world) {
        if (world.paused() || world.frozen()) return 0f;
        float rate = world.tickRate();
        return rate > 0f ? rate / 20f : 1f;
    }

    /**
     * The particles drawn in the latest frame, every system's: those Range kept to draw from the GPU pools after
     * culling, which arrive a frame or two late. For a readout, never a decision; the first ask starts the GPU count's
     * small readback. Render thread.
     *
     * <pre>{@code
     * hud.line("Particles: " + CgVfxSystem.particlesDrawn());
     * }</pre>
     */
    public static int particlesDrawn() {
        return CgVfxRange.visibleCount();
    }

    /**
     * The GPU pools' particles drawn into {@code framebuffer} in the latest frame: a viewport's own, not the world's. For a
     * readout, never a decision; a frame or two late, as {@link #particlesDrawn()}. Render thread.
     */
    public static int particlesDrawn(int framebuffer) {
        return CgVfxRange.visibleCount(framebuffer);
    }

    /** Draws every playing effect into {@code world}, interpolated between the last two ticks. Render thread. */
    public void submit(CgWorldRenderer world) {
        if (effects.isEmpty()) return;
        long began = System.nanoTime(), mark = CgTrace.allocationBegin(CgVfxTrace.CHANNEL);
        try {
            submitTimed(world);
        } finally {
            CgVfxBudget.FRAME.add(System.nanoTime() - began);
            CgTrace.allocationEnd(CgVfxTrace.CHANNEL, SUBMIT_ALLOCATION, mark);
        }
    }

    /** The meshes every slot draws on, built once: by {@link #precache}, else at the first submit with effects. */
    private void buildMeshes() {
        if (tubeMesh != null) return;
        try (CgTrace.Zone ignored = CgTrace.zone(CgVfxTrace.CHANNEL, MESHES_ZONE)) {
            tubeMesh = CgVfxTube.mesh(false);
            closedTubeMesh = CgVfxTube.mesh(true);
            ribbonMesh = CgVfxRibbons.mesh();
            sphereMesh = CgMeshShapes.sphere(48, 96);
            // 12 x 24 faces come within cos(15 deg) cos(7.5 deg) of the centre: at radius 1.05 they still enclose the unit sphere.
            volumeMesh = COARSE_VOLUMES.get()
                    ? CgMesh.build(CgVertexFormat.SPATIAL, m -> CgMeshShapes.sphere(m, 12, 24, 1.05f)) : sphereMesh;
        }
    }

    private void submitTimed(CgWorldRenderer world) {
        buildMeshes();
        try (CgTrace.Zone ignored = CgTrace.zone(CgVfxTrace.CHANNEL, SUBMIT_ZONE)) {
            try (CgTrace.Zone warming = CgTrace.zone(CgVfxTrace.CHANNEL, WARM_ZONE)) {
                warmPlaying();
            }
            float alpha = Math.min(owed / TICK, 1f);
            double gx = 0, gy = 0, gz = 0;
            for (int i = 0; i < effects.size(); i++) {
                CgVfxEffect<?> effect = effects.get(i);
                gx += effect.originX;
                gy += effect.originY;
                gz += effect.originZ;
            }
            int n = effects.size();
            materials.begin(CgFrameRing.frame());
            frame.begin(world, alpha, n, gx / n, gy / n, gz / n);
            paths.begin();
            for (int i = 0; i < effects.size(); i++) {
                CgVfxEffect<?> effect = effects.get(i);
                // Killed since the update: cleared by its owner, or failed.
                if (effect.state() == CgVfxEffect.State.DEAD || effect.asleep || !effect.activated) continue;
                int zone = effect.owner != null ? effect.owner.submitZone : EFFECT_ZONE;
                try (CgTrace.Zone submitting = CgTrace.zone(CgVfxTrace.CHANNEL, zone)) {
                    effect.clearBounds();
                    effect.submit(frame);
                } catch (RuntimeException e) {
                    fail(effect, e);
                }
            }
            try (CgTrace.Zone particles = CgTrace.zone(CgVfxTrace.CHANNEL, PARTICLES_ZONE)) {
                frame.particles();
            }
            try (CgTrace.Zone ending = CgTrace.zone(CgVfxTrace.CHANNEL, FRAME_END_ZONE)) {
                frame.end();
            }
            try (CgTrace.Zone rows = CgTrace.zone(CgVfxTrace.CHANNEL, ROWS_ZONE)) {
                materials.upload();
            }
            try (CgTrace.Zone upload = CgTrace.zone(CgVfxTrace.CHANNEL, PATHS_ZONE)) {
                paths.upload();
                bindPaths();
            }
        }
    }

    /**
     * Requests a newly playing effect's look if nothing precached it, and polls what is still warming: an effect's last
     * layers (a blast, its cloud) appear seconds after it starts, and compiling them then stalls that frame.
     */
    private void warmPlaying() {
        for (int i = 0; i < effects.size(); i++) {
            CgVfxEffect<?> effect = effects.get(i);
            if (effect.gpuPrecached) continue;
            effect.gpuPrecached = true;
            if (precachedLooks.add(effect.look())) {
                // Not in any precache: its kernels, storage and draws are requested now, its activation held for them.
                CgTrace.marker(CgVfxTrace.CHANNEL, "vfx.warm-late", effect.getClass().getSimpleName() + " " + effect.definitionKey());
                request(effect.look());
            }
            useWindow(effect.look());
        }
        pollWarming();
    }

    /**
     * Warms what {@code look} simulates and draws before any effect of it plays: its GPU kernels, slot meshes and
     * materials' programs, so its first play stalls no frame. On the render thread, ahead of time. The work is queued
     * and done over the next frames within {@link CgWarmBudget}, polled while this system submits or {@link #ready}
     * is asked; a look played before then builds what is left on its first frame.
     *
     * <pre>{@code
     * CgVfxSystem vfx = new CgVfxSystem();
     * vfx.precache(CgEnergyWave.kamehameha());   // at init
     * // ...later, at once:
     * vfx.play(new CgEnergyWave(CgEnergyWave.kamehameha(), x, y, z));
     * }</pre>
     *
     * <p>A move made of several effects precaches every look it plays at once, then waits on {@link #ready}:</p>
     *
     * <pre>{@code
     * vfx.precache(SPIRAL, SHOCKWAVE, FLASH);
     * }</pre>
     */
    public void precache(CgVfxLook... looks) {
        buildMeshes();
        // Its emitters join the loaded shapes first, so they warm as they will play: raised where coalescing raises them.
        Map<CgVfxEmitter, Integer> tiers = new LinkedHashMap<>();
        for (CgVfxLook look : looks) {
            if (precachedLooks.contains(look)) continue;
            for (CgVfxEmitter e : look.emitters()) tiers.putIfAbsent(e, feedTier(look, e));
        }
        for (CgVfxGpuEmitter moved : CgVfxShapeCoalescing.loaded().include(tiers)) {
            if (moved instanceof CgVfxEmitter e && precachedTier.containsKey(e)) {
                int tier = precachedTier.get(e);
                lookRequests.add(CgPipelinePrecache.steps("vfx " + e.name() + ", moved by coalescing")
                        .step(p -> emitterStep(p, e, tier)).start());
            }
        }
        for (CgVfxLook look : looks) {
            // Precached here, its play only starts the window: its pools are sized once.
            if (precachedLooks.add(look)) request(look);
        }
        pollWarming();
    }

    /**
     * {@code look}'s precache request: on a worker its shaders parsed and its Step sources generated, then a step for
     * each placed draw's materials, one for its clips' parts, Range's kernels in the first look a system requests, and
     * a step per emitter (its kernels, its pools expected, its slot mesh); and its meshes and textures read ahead.
     */
    private void request(CgVfxLook look) {
        List<CgMaterialShader> shaders = new ArrayList<>();
        materials.shaders(look, shaders);
        CgPipelinePrecache.Steps steps = CgPipelinePrecache.steps("vfx look").ahead(() -> loadAhead(look, shaders));
        for (int i = 0; i < look.placed().size(); i++) {
            int placed = i;
            steps.step(p -> {
                try (CgTrace.Zone ignored = CgTrace.zone(CgVfxTrace.CHANNEL, MATERIALS_ZONE)) {
                    materials.precache(look, placed, warming, halved);
                    addWarming(p);
                }
            });
        }
        steps.step(p -> {
            materials.precacheParticles(look, warming);
            addWarming(p);
        });
        if (!rangeRequested) {
            rangeRequested = true;
            for (Runnable range : CgVfxRange.PRECACHE_STEPS) steps.step(p -> p.kernels(range));
        }
        List<CgVfxEmitter> emitters = look.emitters();
        for (int k = 0; k < emitters.size(); k++) {
            CgVfxEmitter e = emitters.get(k);
            int tier = feedTier(look, e);
            precachedTier.put(e, tier);
            steps.step(p -> emitterStep(p, e, tier));
            // The window fills only once played, but its kernels are the look's to build now.
            if (readsWorld(e)) steps.step(p -> p.kernels(CgWorldVoxelWindow.get()::precache));
        }
        lookRequests.add(steps.start());
        preloads.add(CgPreload.request(l -> {
            meshes(look, l);
            textures(look, l);
        }));
    }

    /** An emitter's step: its kernels, children's included, named while it sizes its pools and slot meshes. */
    private void emitterStep(CgPipelineList pipelines, CgVfxEmitter emitter, int tier) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgVfxTrace.CHANNEL, EMITTER_ZONE)) {
            pipelines.kernels(() -> precacheEmitter(emitter, tier));
        }
    }

    /** The materials a draw step named, kept built by the precache: a settings change rebuilds them before it applies. */
    private void addWarming(CgPipelineList pipelines) {
        for (int i = 0; i < warming.size(); i++) pipelines.add(warming.get(i), halved.contains(warming.get(i)));
        warming.clear();
        halved.clear();
    }

    /**
     * What precaching {@code look} reads and generates, made on a worker so its steps find it cached: its shaders
     * parsed, Range's and the ops' kernel files and each emitter's Step kernel (its children's at their tiers). A failure
     * here is left for the step that hits it, which reports it as it always has.
     */
    private static void loadAhead(CgVfxLook look, List<CgMaterialShader> shaders) {
        for (CgMaterialShader shader : shaders) shader.ensureParsed();
        CgVfxRange.loadAhead();
        for (CgVfxEmitter emitter : look.emitters()) loadAhead(emitter, 0);
    }

    private static void loadAhead(CgVfxEmitter emitter, int tier) {
        CgVfxParticlePool.loadAhead(emitter, tier);
        for (int e = 0; e < emitter.events().size(); e++) {
            CgVfxEmitter child = (CgVfxEmitter) emitter.events().get(e).child();
            if (child != null) loadAhead(child, tier + 1);
        }
    }

    /**
     * {@code look}'s draws' textures, each the registry's for its binding's path, which a renderer's material samples:
     * decoded ahead, or its first draw decodes it on the render thread.
     */
    private static void textures(CgVfxLook look, CgPreload.Loads into) {
        for (CgVfxLook.Placed placed : look.placed()) {
            for (CgMaterialDefinition d : placed.definitions()) {
                for (CgMaterialDefinition.Texture t : d.textures().values()) into.texture(t.resolve());
            }
        }
    }

    /** The meshes {@code look}'s particles draw: the particle sphere's levels, or each mesh renderer's first mesh. */
    private static void meshes(CgVfxLook look, CgPreload.Loads into) {
        for (CgVfxEmitter emitter : look.emitters()) meshes(emitter, into);
    }

    private static void meshes(CgVfxEmitter emitter, CgPreload.Loads into) {
        if (emitter.drawLayout() == CgVfxDrawLayout.MESHES) into.read(CgMeshShapes::sphereLods);
        for (CgVfxRendererProperties renderer : emitter.renderers()) {
            if (!(renderer instanceof CgVfxMeshRendererProperties m)) continue;
            // CgVfxFrame's draw reads the same cache: the particle sphere where it names none, else its one mesh
            if (m.meshes().isEmpty()) into.read(CgMeshShapes::sphereLods);
            else into.mesh(m.meshes().get(0).get("mesh"));
        }
        for (int e = 0; e < emitter.events().size(); e++) {
            CgVfxEmitter child = (CgVfxEmitter) emitter.events().get(e).child();
            if (child != null) meshes(child, into);
        }
    }

    /**
     * Whether a renderer of {@code look} draws a material reading its system's bounds ({@code CG_OBJECT_POSITION},
     * {@code CG_OBJECT_RADIUS}): what a system with no fixed bounds is refused at load for. Render thread.
     */
    public boolean readsBounds(CgVfxLook look) {
        return materials.readsBounds(look);
    }

    /**
     * Whether every look {@link #precache}d or played is warm: its pipelines built (its request ready), its meshes read
     * and its pools' storage made, and no rehearsal running. Hold a scene's first submit on it and its first frames wait
     * on no compile. Render thread.
     *
     * <pre>{@code
     * vfx.precache(look);                  // at install
     * if (!vfx.ready()) return;            // each frame, until it answers true
     * vfx.play(...);
     * }</pre>
     */
    public boolean ready() {
        pollWarming();
        return warmed() && rehearsals.isEmpty();
    }

    /**
     * Whether every look {@link #precache}d or played is warm, as {@link #ready} but for rehearsals: what an asset's
     * precache waits on, before it asks for its own rehearsal. Render thread.
     */
    public boolean warm() {
        pollWarming();
        return warmed();
    }

    /** Whether every request and read is done and every expected pool allocated. */
    private boolean warmed() {
        return lookRequests.isEmpty() && preloads.isEmpty() && expected.isEmpty();
    }

    /**
     * Rehearses a move before its first cast, as {@link #precache} warms its programs: {@code cast} plays the move into a
     * hidden system of its own, which is updated, submitted, its pools stepped and the world stages recorded and
     * executed each pass into a hidden target, until the JIT has settled ({@link CgRehearsal}). It runs at the frame boundary, a few
     * passes a frame under {@link CgWarmBudget}, once the programs {@link #precache}d are built; {@link #ready} answers
     * true when it is over.
     *
     * <pre>{@code
     * vfx.precache(BEAM, BLAST);
     * vfx.rehearse(hidden -> hidden.play(CgEnergyWave.instance(BEAM, x, y, z)));   // again each time its effects end
     * if (!vfx.ready()) return;                                              // each frame, then cast
     * }</pre>
     *
     * <ul>
     *   <li>{@code cast} plays what the real cast will, with its definitions: the JIT inlines for the types it sees.</li>
     *   <li>One asked for while another runs waits behind it; one asked for before the last waiting one starts joins
     *       it, played beside its casts, and answers its {@link CgRehearsal}: a bundle's systems turning ready together
     *       rehearse as one.</li>
     *   <li>A pass that throws ends it ({@link CgRehearsal#failure()}, logged): first plays then warm as they play.</li>
     *   <li>Render thread.</li>
     * </ul>
     */
    public CgRehearsal rehearse(Consumer<CgVfxSystem> cast) {
        Rehearsal last = rehearsals.peekLast();
        if (last == null || last.hidden != null) rehearsals.addLast(last = new Rehearsal());
        last.casts.add(cast);
        if (rehearsalStep == null) {
            rehearsalStep = CgFrameBoundary.add(CgFrameBoundary.Phase.DRAIN, "crystalgraphics:vfx-rehearsal", this::stepRehearsal);
        }
        return last.rehearsal;
    }

    /** The boundary's step: once the programs are built, a frame's passes of the first rehearsal waiting. */
    private void stepRehearsal() {
        Rehearsal head = rehearsals.peekFirst();
        if (head == null) {
            rehearsalStep.close();
            rehearsalStep = null;
            return;
        }
        // Polled here too: before a world submits (the title screen), nothing else advances the warm-up.
        pollWarming();
        if (!warmed()) return;
        if (head.hidden == null) {
            head.start();
            LOGGER.info("[CgVfx] rehearsing {} cast(s), the precache done", head.casts.size());
        }
        if (!head.rehearsal.step()) return;
        RuntimeException failure = head.rehearsal.failure();
        if (failure != null) LOGGER.warn("[CgVfx] a rehearsal failed; its first plays warm as they play", failure);
        else LOGGER.info("[CgVfx] rehearsed {} cast(s) in {} passes", head.casts.size(), head.rehearsal.passes());
        rehearsals.pollFirst().end();
    }

    /** One rehearsal: its casts, played into a hidden system made when it starts, sharing what this one has warmed. */
    private final class Rehearsal {
        final List<Consumer<CgVfxSystem>> casts = new ArrayList<>();
        final CgRehearsal rehearsal = CgRehearsal.of(this::pass);
        @Nullable
        CgVfxSystem hidden;
        double clock;

        void start() {
            CgVfxSystem h = new CgVfxSystem();
            h.rehearsing = true;
            // What this system built and warmed, shared: a pass rebuilding the meshes or re-warming the looks was 12 ms.
            buildMeshes();
            h.tubeMesh = tubeMesh;
            h.closedTubeMesh = closedTubeMesh;
            h.ribbonMesh = ribbonMesh;
            h.sphereMesh = sphereMesh;
            h.volumeMesh = volumeMesh;
            h.precachedLooks.addAll(precachedLooks);
            h.readyLooks.addAll(readyLooks);
            CgVfxParticlePool.hide(h.gpuSteps);
            hidden = h;
        }

        void pass(CgRehearsal.Pass pass) {
            CgVfxSystem h = hidden;
            CgVfxParticlePool.rehearsing(true);
            CgVfxRange.rehearsing(true);
            try {
                if (h.effects.isEmpty()) {
                    for (int i = 0; i < casts.size(); i++) casts.get(i).accept(h);
                }
                h.update(clock += REHEARSAL_STEP);
                h.submit(CgWorldRenderer.get());
                CgVfxParticlePool.recordRehearsed(pass.recording());
                pass.execute();
                CgRenderStage.WORLD_OPAQUE.rehearse();
                CgRenderStage.WORLD_TRANSPARENT.rehearse();
            } finally {
                CgVfxParticlePool.rehearsing(false);
                CgVfxRange.rehearsing(false);
            }
        }

        void end() {
            if (hidden == null) return;
            // Its meshes are this system's: not the hidden one's to release.
            hidden.tubeMesh = hidden.closedTubeMesh = hidden.ribbonMesh = hidden.sphereMesh = hidden.volumeMesh = null;
            hidden.delete();
            hidden = null;
        }
    }

    /** A rehearsal's clock step: a frame at 60 a second. */
    private static final double REHEARSAL_STEP = 1.0 / 60.0;
    /** Rehearsals asked for, the running one first. */
    private final ArrayDeque<Rehearsal> rehearsals = new ArrayDeque<>();
    @Nullable
    private CgFrameBoundary.Function rehearsalStep;
    /** A rehearsal's hidden system: its effects open detached post volumes and play no camera shake. */
    boolean rehearsing;

    /** Stops every effect: none emits again, and what is in flight plays out before each dies. */
    public void stop() {
        for (int i = 0; i < effects.size(); i++) effects.get(i).stop();
    }

    /**
     * Ends every effect at once, its particles with it, keeping meshes and warmed programs: what plays next starts as
     * quickly as on a system never cleared. Its clock starts again at the next {@link #update}, so a system left
     * unupdated meanwhile does not fast-forward the time it missed. Between frames, on the render thread.
     */
    public void clear() {
        for (int i = 0; i < effects.size(); i++) {
            CgVfxEffect<?> effect = effects.get(i);
            effect.kill();
            effect.leaveGroup();
            if (effect.pooled()) reclaiming.add(effect);
        }
        effects.clear();
        simulations.clear();
        releaseOwners();
        // Drops the pools, not just their slots: a slot reopened over live particles hands them to its next owner.
        gpuSteps.clear();
        reclaim(true);
        frame.clear();
        clock = Double.NaN;
        owed = 0f;
    }

    /** The window's first use starts its kernels and its filling: at play, not on the first landing. */
    private static void useWindow(CgVfxLook look) {
        List<CgVfxEmitter> emitters = look.emitters();
        for (int k = 0; k < emitters.size(); k++) {
            if (readsWorld(emitters.get(k))) CgWorldVoxelWindow.get().use();
        }
    }

    /**
     * {@code emitter}'s Step kernels and slot meshes at feed {@code tier}, its children's and theirs too, each at its
     * own: built here, not in the first burst's frame. One that fails is logged once and skipped, never thrown out of the
     * submit that warms it: its effect then fails alone when it plays.
     */
    private void precacheEmitter(CgVfxEmitter emitter, int tier) {
        if (unprecached.contains(emitter)) return;
        try {
            CgVfxParticlePool.precache(emitter, tier);
            CgVfxParticlePool pool = CgVfxParticlePool.of(gpuSteps, emitter, tier);
            pool.expect(emitter, emitter.peakAlive());
            expected.add(pool);
            if (pool.record().has(CgVfxRecord.Tail.LIGHT)) CgVfxParticleLights.precache();
            precacheRibbons(emitter);
            CgVfxFrame.slotMesh(emitter.drawLayout(), emitter.peakAlive());
            precacheChildren(emitter, -1, tier + 1);
        } catch (RuntimeException e) {
            unprecached.add(emitter);
            LOGGER.error("[vfx] {} failed to precache and is skipped", emitter.name(), e);
        }
    }

    /**
     * The feed tier {@code emitter} plays at in {@code look}: 0, or one past its source's for an emitter born on another
     * emitter's particles, as {@code CgVfxGpuSteps.admit} steps a reader the tier after its source.
     */
    private static int feedTier(CgVfxLook look, CgVfxEmitter emitter) {
        List<CgVfxEmitter> emitters = look.emitters();
        int tier = 0;
        for (CgVfxEmitter e = emitter; e != null && e.spawnFrom() != null && !e.spawnFrom().itself()
                && tier < CgVfxShape.TIERS; tier++) {
            CgVfxSpawnFrom from = e.spawnFrom();
            e = from.sourceIndex() >= 0 && from.sourceIndex() < emitters.size() ? emitters.get(from.sourceIndex())
                    : look.emitter(from.source());
        }
        return tier;
    }

    /**
     * Whether {@code effect} may tick: its look's kernels are built, so its first step compiles and waits for nothing.
     * Until then it holds at age 0, drawing nothing, as Niagara delays a system's activation until its PSOs are
     * precached ({@code ENiagaraSystemPSOPrecachingDelayMode}, NiagaraScalabilitySettings.h:21). Its draws' programs are
     * the world renderer's to hold. Counted as {@code vfx.effects-held}.
     */
    private boolean activated(CgVfxEffect<?> effect) {
        if (effect.activated) return true;
        CgVfxLook look = effect.look();
        // A server's copy, or a system with no GL on its thread (a test), has no kernels to wait for.
        if (!effect.authoritative() && CgGL.mayIssueGl() && !readyLooks.contains(look)) {
            CgPipelinePrecache.Request kernels = lookKernels.computeIfAbsent(look, l -> CgPipelinePrecache.request(p -> {
                try {
                    p.kernels(() -> precacheKernels(l));
                } catch (RuntimeException e) {
                    // it fails at its own step, alone, as a look that will not precache does
                }
            }));
            if (!kernels.ready()) {
                CgVfxTrace.count(EFFECTS_HELD, 1);
                return false;
            }
            readyLooks.add(look);
        }
        effect.activated = true;
        return true;
    }

    /** Every kernel a look's emitters dispatch, precached: Range's, each pool's Step and Look, ribbons and sorts. */
    private static void precacheKernels(CgVfxLook look) {
        CgVfxRange.precache();
        List<CgVfxEmitter> emitters = look.emitters();
        for (int k = 0; k < emitters.size(); k++) precacheKernels(emitters.get(k), feedTier(look, emitters.get(k)));
    }

    private static void precacheKernels(CgVfxEmitter emitter, int tier) {
        CgVfxParticlePool.precache(emitter, tier);
        precacheRibbons(emitter);
        for (int e = 0; e < emitter.events().size(); e++) {
            CgVfxEmitter child = (CgVfxEmitter) emitter.events().get(e).child();
            if (child != null && tier + 1 < CgVfxShape.TIERS) precacheKernels(child, tier + 1);
        }
    }

    /** Looks whose kernels were found built, and each look's kernels, gathered once. */
    private final Set<CgVfxLook> readyLooks = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<CgVfxLook, CgPipelinePrecache.Request> lookKernels = new IdentityHashMap<>();

    /** The ribbon generation's kernels and sorts, for an emitter a ribbon renderer draws: else its first ribbon builds them. */
    private static void precacheRibbons(CgVfxEmitter emitter) {
        List<CgVfxRendererProperties> renderers = emitter.renderers();
        for (int i = 0; i < renderers.size(); i++) {
            if (renderers.get(i) instanceof CgVfxRibbonRendererProperties) {
                CgVfxRibbonGeneration.precache();
                return;
            }
        }
    }

    private void precacheChildren(CgVfxEmitter parent, int fedAlive, int tier) {
        for (int e = 0; e < parent.events().size(); e++) {
            CgVfxEmitter child = (CgVfxEmitter) parent.events().get(e).child();
            if (child == null) continue;
            int alive = parent.peakChildren(e, fedAlive);
            CgVfxParticlePool.precache(child, tier);
            CgVfxParticlePool pool = CgVfxParticlePool.of(gpuSteps, child, tier);
            pool.expect(child, alive);
            expected.add(pool);
            if (pool.record().has(CgVfxRecord.Tail.LIGHT)) CgVfxParticleLights.precache();
            precacheRibbons(child);
            CgVfxFrame.slotMesh(child.drawLayout(), alive);
            precacheChildren(child, alive, tier + 1);
        }
    }

    /**
     * Polls each look's request and read, its steps collected while the frame's {@link CgWarmBudget} lasts; then, once
     * every look's emitters have expected, their pools' storage under the same budget.
     */
    private void pollWarming() {
        try (CgTrace.Zone ignored = CgTrace.zone(CgVfxTrace.CHANNEL, POLL_ZONE)) {
            for (int i = lookRequests.size() - 1; i >= 0; i--) {
                if (lookRequests.get(i).ready()) lookRequests.remove(i);
            }
            for (int i = preloads.size() - 1; i >= 0; i--) {
                if (preloads.get(i).ready()) preloads.remove(i);
            }
        }
        if (lookRequests.isEmpty() && !expected.isEmpty() && CgWarmBudget.allows()) {
            long start = System.nanoTime();
            try (CgTrace.Zone ignored = CgTrace.zone(CgVfxTrace.CHANNEL, ALLOCATE_ZONE)) {
                // Every look's emitters have expected: their pools' storage made now, not by the first step.
                for (CgVfxParticlePool pool : expected) if (!pool.isReleased()) pool.allocate();
            }
            expected.clear();
            CgWarmBudget.spend(System.nanoTime() - start);
        }
    }

    private static boolean readsWorld(CgVfxEmitter emitter) {
        for (int i = 0; i < emitter.modules().size(); i++) {
            if (emitter.modules().get(i).dataInterfaces().length > 0) return true;
        }
        return false;
    }

    public List<CgVfxEffect<?>> effects() {
        return effects;
    }

    /**
     * The pool {@code instance}'s particles live in, for a probe reading them back beside {@link #slot}; null before its
     * first step and once its slot closes. Render thread.
     *
     * <pre>{@code
     * CgVfxParticlePool pool = vfx.pool(instance);
     * if (pool != null) recording.readback(pool.records(), 0, bytes, sink);   // its slot's: heat-id word's z == vfx.slot(instance)
     * }</pre>
     */
    @Nullable
    public CgVfxParticlePool pool(CgVfxEmitterInstance instance) {
        CgVfxGpuSteps.Tenant tenant = gpuSteps.of(instance);
        return tenant == null ? null : tenant.pool;
    }

    /** {@code instance}'s slot in {@link #pool}, or -1 when it holds none. Render thread. */
    public int slot(CgVfxEmitterInstance instance) {
        CgVfxGpuSteps.Tenant tenant = gpuSteps.of(instance);
        return tenant == null ? -1 : tenant.slot;
    }

    /** Releases its meshes and frees the path texture; materials belong to the material registry. */
    public void delete() {
        while (!rehearsals.isEmpty()) rehearsals.pollFirst().end();
        if (rehearsalStep != null) {
            rehearsalStep.close();
            rehearsalStep = null;
        }
        for (int i = 0; i < effects.size(); i++) effects.get(i).leaveGroup();
        effects.clear();
        reclaiming.clear();
        synchronized (instancePools) {
            instancePools.clear();
        }
        simulations.clear();
        releaseOwners();
        for (int i = 0; i < eventListeners.size(); i++) CgVfxParticlePool.stopListening(eventListeners.get(i));
        eventListeners.clear();
        gpuSteps.clear();
        frame.clear();
        paths.delete();
        if (tubeMesh != null) tubeMesh.release();
        if (closedTubeMesh != null) closedTubeMesh.release();
        if (volumeMesh != null && volumeMesh != sphereMesh) volumeMesh.release();
        tubeMesh = null;
        closedTubeMesh = null;
        ribbonMesh = null;
        sphereMesh = null;
        volumeMesh = null;
        boundTexture = null;
        pathsBound = 0;
        warming.clear();
        halved.clear();
        lookRequests.clear();
        preloads.clear();
    }

    CgVfxPathTexture paths() {
        return paths;
    }

    CgVfxTube tube() {
        return tube;
    }

    CgMesh tubeMesh() {
        return tubeMesh;
    }

    /** A closed tube's chunk mesh: a slot renderer whose archetype is tagged {@code "Tube" = "Closed"}. */
    CgMesh closedTubeMesh() {
        return closedTubeMesh;
    }

    /** What a particle mesh is drawn on: the sphere at levels, each kept until a coarser one strays half a pixel. */
    CgMeshLods particleSphere() {
        return CgMeshShapes.sphereLods();
    }

    CgMesh sphereMesh() {
        return sphereMesh;
    }

    /** What a gas is marched through: the unit cube, -0.5 to 0.5. */
    CgMesh cubeMesh() {
        return CgMeshShapes.cube();
    }

    /** What a volume layer's chunks are drawn on: the sphere, or with {@link #COARSE_VOLUMES} a coarse one round it. */
    CgMesh volumeMesh() {
        return volumeMesh;
    }

    /**
     * {@code draw} placed as a draw of {@code placed} in the effect at {@code (ox, oy, oz)}: its material's sort layer,
     * the effect's group and its order in its look, and a surface draw ({@link CgVfxLook.Placed#afterDistortion()})
     * after the distortion apply, so no haze of its own effect bends it.
     */
    public static CgWorldRenderer.Draw place(CgWorldRenderer.Draw draw, CgVfxLook.Placed placed, double ox, double oy,
                                             double oz) {
        CgMaterialDefinition material = placed.definition();
        CgSortLayer sortLayer = material == null ? CgSortLayer.EFFECTS : sortLayer(material.sortLayer(), placed.distorts());
        draw.layer(sortLayer).group(ox, oy, oz).order(placed.order());
        return placed.afterDistortion() ? draw.afterDistortion() : draw;
    }

    /** A material's {@code sort_layer} as the world renderer's, a distorting one after every effect where shared. */
    private static CgSortLayer sortLayer(String sortLayer, boolean distorts) {
        return switch (sortLayer) {
            case "background" -> CgSortLayer.BACKGROUND;
            case "default" -> SHARED_DISTORTION.get() && distorts ? DISTORTION : CgSortLayer.EFFECTS;
            case "effects" -> CgSortLayer.EFFECTS;
            case "overlay" -> CgSortLayer.OVERLAY;
            default -> throw new IllegalArgumentException("sort_layer " + sortLayer + " is not one of background, default,"
                    + " effects, overlay");
        };
    }

    /**
     * {@code draw} placed as {@code effect}'s draw of {@code placed}: in the effect's group at its origin, or in its
     * {@link CgVfxSortGroup} at the group's place with the group's rank for the layer.
     */
    public static CgWorldRenderer.Draw place(CgWorldRenderer.Draw draw, CgVfxLook.Placed placed, CgVfxEffect<?> effect) {
        CgVfxSortGroup group = effect.sortGroup();
        if (group == null) return place(draw, placed, effect.originX, effect.originY, effect.originZ);
        long frame = CgFrameRing.frame();
        place(draw, placed, group.x(frame), group.y(frame), group.z(frame));
        return draw.order(group.order(effect.look(), placed));
    }

    CgMesh ribbonMesh() {
        return ribbonMesh;
    }

    /** Points every material at the path texture: once each, and again if it was made anew. */
    private void bindPaths() {
        CgTexture2D path = paths.texture();
        if (path == null) return;
        if (path != boundTexture) {
            boundTexture = path;
            pathsBound = 0;
        }
        List<CgMaterial> all = materials.all();
        for (; pathsBound < all.size(); pathsBound++) {
            all.get(pathsBound).applyProperties(b -> b.sampler(PATH_SAMPLER, 0, path));
        }
    }
}
