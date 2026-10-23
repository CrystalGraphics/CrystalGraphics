package com.crystalgraphics.vfx;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshLods;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.asset.load.CgAssetFollower;
import com.crystalgraphics.asset.load.CgAssetRef;
import com.crystalgraphics.collision.CgCollisionWorld;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.job.CgJobLane;
import com.crystalgraphics.gl.material.CgMaterialShader;
import java.util.concurrent.CompletableFuture;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.render.CgWarmBudget;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.render.graph.CgRehearsal;
import com.crystalgraphics.frame.CgFrameBoundary;
import com.crystalgraphics.render.stage.CgHostEnvironment;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgSortLayer;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.particle.CgVfxAir;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEventListener;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.gpu.draw.CgVfxRange;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
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
import com.crystalgraphics.world.CgEntityAttachments;
import com.crystalgraphics.world.CgEntityQueries;
import com.crystalgraphics.world.CgWorldQueries;
import com.crystalgraphics.vfx.particle.gpu.CgVfxDrawLayout;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Quaternionf;

import javax.annotation.Nullable;
import com.crystalgraphics.vfx.particle.spawn.CgVfxSpawnFrom;

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
    private static final CgVar.Num CPU_BUDGET_MS = CgVar.number("crystalgraphics.vfx.cpuBudgetMs", 4f, 0.1f, 1000f, CgVar.DEV,
            "CPU milliseconds a frame a system's updates and draws may take before its scalability shrinks every distance and cap.");
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
    private static final int TICK_ZONE = CgTrace.name("vfx.tick"), SUBMIT_ZONE = CgTrace.name("vfx.submit"),
            WARM_ZONE = CgTrace.name("vfx.warm"), EFFECT_ZONE = CgTrace.name("vfx.effect.submit"),
            PATHS_ZONE = CgTrace.name("vfx.paths.upload"), MATERIALS_ZONE = CgTrace.name("vfx.prepare.materials"),
            PROGRAMS_ZONE = CgTrace.name("vfx.prepare.programs"), EMITTER_ZONE = CgTrace.name("vfx.prepare.emitter"),
            CULLED = CgTrace.name("vfx.culled"), ASLEEP = CgTrace.name("vfx.asleep"), PRESSURE = CgTrace.name("vfx.pressure-percent"),
            TICKS = CgTrace.name("vfx.ticks"), CAPPED = CgTrace.name("vfx.ticks.capped"), HELD = CgTrace.name("vfx.ticks.held"),
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
    /** Materials compiling ahead of their first draw, so a layer that appears late does not stall its frame. */
    private final List<CgMaterial> warming = new ArrayList<>();
    /** Those of them drawn at half size, whose half-size passes warm too. */
    private final Set<CgMaterial> halved = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Emitters {@link #prepare} queued whose kernels and slot meshes are still to start, one a step under the budget. */
    private final List<CgVfxEmitter> gpuWarming = new ArrayList<>();
    /** Each warming emitter's feed tier in the look it came from: one past its source's for a reader of particles. */
    private final Map<CgVfxEmitter, Integer> gpuWarmingTier = new IdentityHashMap<>();
    /** Each emitter it prepared and its feed tier: warmed again when coalescing moves it to another shape. */
    private final Map<CgVfxEmitter, Integer> preparedTier = new WeakHashMap<>();
    /** Emitters whose kernels failed to prepare: logged once and skipped, as Niagara disables an emitter that fails. */
    private final Set<CgVfxEmitter> unprepared = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Looks {@link #prepare} queued whose materials are still to make, a placed layer a step under the budget. */
    private final List<CgVfxLook> drawWarming = new ArrayList<>();
    /** The first look of {@link #drawWarming}'s next placed layer; its count means its clips are next. */
    private int drawPlaced;
    /** What a worker reads and generates for the looks {@link #prepare}d, which the queues wait for; null when none. */
    private CompletableFuture<Void> loading;
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
    private final IntConsumer bookEach = i -> batchSimulation[i].book(batchFrom[i], BOOK_BATCH.get());

    /** The owners whose effects it has played, for their counters. */
    private final List<CgVfxOwner> owners = new ArrayList<>();
    private final double[] pose = new double[CgEntityQuery.POSE_LENGTH], point = new double[3];
    private final Quaternionf turn = new Quaternionf();
    /** The world whose time dilation scales every effect's time; null for a system that runs on real pace alone. */
    private CgWorld world;
    private final CgVfxGpuSteps gpuSteps = new CgVfxGpuSteps();
    private boolean rangePrepared;
    /** The next of {@link CgVfxRange#PREPARE_STEPS} the warm-up runs. */
    private int rangeStep;
    /** Looks whose kernels and slot meshes, and whose materials, this system has queued: held weakly. */
    private final Set<CgVfxLook> gpuLooks = Collections.newSetFromMap(new WeakHashMap<>()),
            drawLooks = Collections.newSetFromMap(new WeakHashMap<>());
    private double clock = Double.NaN;
    private float owed, simulated;
    private float density = 1f;
    private CgQuality quality = CgQuality.HIGH;
    private final CgVfxCulling culling = new CgVfxCulling();
    /** Load on its budgets, smoothed: 1 at rest, above while over; and the CPU nanoseconds this frame's update took. */
    private float pressure = 1f;
    private long updateNanos, submitNanos;

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
            updateNanos = System.nanoTime() - began;
            CgTrace.allocationEnd(CgVfxTrace.CHANNEL, UPDATE_ALLOCATION, mark);
        }
    }

    private void updateTimed(double seconds) {
        CgHostEnvironment world = CgRenderStage.WORLD_OPAQUE.host().environment();
        readSettings(world);
        follow();
        resolveRates();
        if (Double.isNaN(clock)) {
            clock = seconds;
            return;
        }
        float passed = (float) (seconds - clock) * pace(world) * (this.world == null ? 1f : this.world.timeDilation());
        owed = Math.max(0f, owed + passed);
        clock = seconds;
        cull(passed);
        // WORLD_OPAQUE records the GPU's one step a frame. While it has not, every effect holds, as Niagara and VFX
        // Graph pause what is not drawn: a second step would need recording, and a dropped one would leave particles
        // alive in a slot its books close as finished.
        if (gpuSteps.queuedSteps() > 0) {
            owed = 0f;
            CgVfxTrace.count(HELD, 1);
            return;
        }
        adoptDefinitions();
        startLate();
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
                        if (effect.state() != CgVfxEffect.State.DEAD && !effect.asleep && effect.timeRate > 0f) {
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

    /** Each effect's pace this update: its character's clock, else 1. */
    private void resolveRates() {
        float partialTick = Float.NaN;
        for (int i = 0; i < effects.size(); i++) {
            CgVfxEffect<?> effect = effects.get(i);
            if (effect.followingId() < 0 && !effect.hasClock()) continue;
            if (Float.isNaN(partialTick)) partialTick = CgRenderStage.WORLD_OPAQUE.host().partialTick();
            effect.resolveRate(partialTick);
        }
    }

    /**
     * Moves each effect following an entity to the entity's attachment point as drawn this frame; stops one whose entity
     * is gone, where it was last.
     */
    private void follow() {
        CgEntityQuery entities = null;
        float partialTick = 0f;
        for (int i = 0; i < effects.size(); i++) {
            CgVfxEffect<?> effect = effects.get(i);
            if (effect.followEntity < 0 || effect.state() == CgVfxEffect.State.DEAD) continue;
            if (entities == null) {
                entities = CgEntityQueries.client();
                partialTick = CgRenderStage.WORLD_OPAQUE.host().partialTick();
            }
            if (entities.pose(effect.followEntity, partialTick, pose)) {
                CgEntityAttachments.point(effect.followEntity, effect.followPoint, partialTick, pose, point);
                effect.moveToFollowed(point[0], point[1], point[2]);
                effect.turnToFollowed(CgEntityAttachments.rotation(effect.followEntity, effect.followPoint, partialTick, pose, turn));
                double half = pose[CgEntityQuery.HEIGHT] * 0.5;
                effect.followBoxX = pose[CgEntityQuery.X];
                effect.followBoxY = pose[CgEntityQuery.Y] + half;
                effect.followBoxZ = pose[CgEntityQuery.Z];
                effect.followHalfX = effect.followHalfZ = (float) (pose[CgEntityQuery.WIDTH] * 0.5);
                effect.followHalfY = (float) half;
                if (effect.followPose == null) effect.followPose = new double[CgEntityQuery.POSE_LENGTH];
                System.arraycopy(pose, 0, effect.followPose, 0, CgEntityQuery.POSE_LENGTH);
                effect.followTick = partialTick;
            } else {
                effect.followEntity = -1;
                effect.followHalfX = effect.followHalfY = effect.followHalfZ = 0f;
                effect.stop();
            }
        }
    }

    /**
     * Its scalability pass, {@code passed} simulated seconds after the last: each effect culled, lowered or woken against
     * the world's latest camera, and what is culled put to sleep, stopped or killed as its scalability says.
     */
    private void cull(float passed) {
        pressure += (load() - pressure) * 0.1f;
        CgTrace.counter(CgVfxTrace.CHANNEL, PRESSURE, Math.round(pressure * 100f));
        culling.view(CgRenderStage.WORLD_OPAQUE.host().view());
        culling.run(effects, passed, CgEntityQueries.client().localPlayer(), reach());
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

    /** A play the camera is past its scalability's distance from never starts, or starts asleep. */
    private void precull(CgVfxEffect<?> effect) {
        if (effect.authoritative() || !culling.viewed()) return;
        CgVfxScalability s = effect.scalability();
        if (!s.culls() || culling.distanceTo(effect.originX, effect.originY, effect.originZ) <= s.maxDistance * reach()) return;
        CgVfxTrace.count(CULLED, 1);
        if (s.reaction() == CgVfxScalability.Reaction.SLEEP) {
            effect.asleep = true;
            effect.wanted = false;
        } else {
            effect.culled(true);
        }
    }

    /**
     * Its load: the CPU its last update and draw took against {@code cpuBudgetMs}. Never the GPU's time, as Niagara's
     * budget is the game thread's: a GPU cost no spawn count removes (a device's fixed overhead) would starve every effect.
     */
    private float load() {
        return Math.max(1f, (updateNanos + submitNanos) / 1.0e6f / CPU_BUDGET_MS.get());
    }

    /** How far every distance and cap reaches under the pressure: 1 at rest, a quarter at the most. */
    float reach() {
        return Math.max(0.25f, Math.min(1f, 1f / pressure));
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
        if (effects.isEmpty()) {
            submitNanos = 0L;
            return;
        }
        long began = System.nanoTime(), mark = CgTrace.allocationBegin(CgVfxTrace.CHANNEL);
        try {
            submitTimed(world);
        } finally {
            submitNanos = System.nanoTime() - began;
            CgTrace.allocationEnd(CgVfxTrace.CHANNEL, SUBMIT_ALLOCATION, mark);
        }
    }

    private void submitTimed(CgWorldRenderer world) {
        if (tubeMesh == null) {
            tubeMesh = CgVfxTube.mesh(false);
            closedTubeMesh = CgVfxTube.mesh(true);
            ribbonMesh = CgVfxRibbons.mesh();
            sphereMesh = CgMeshShapes.sphere(48, 96);
            // 12 x 24 faces come within cos(15 deg) cos(7.5 deg) of the centre: at radius 1.05 they still enclose the unit sphere.
            volumeMesh = COARSE_VOLUMES.get()
                    ? CgMesh.build(CgVertexFormat.SPATIAL, m -> CgMeshShapes.sphere(m, 12, 24, 1.05f)) : sphereMesh;
        }
        try (CgTrace.Zone ignored = CgTrace.zone(CgVfxTrace.CHANNEL, SUBMIT_ZONE)) {
            try (CgTrace.Zone warming = CgTrace.zone(CgVfxTrace.CHANNEL, WARM_ZONE)) {
                warm(world);
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
            frame.begin(world, alpha, gx / n, gy / n, gz / n);
            paths.begin();
            for (int i = 0; i < effects.size(); i++) {
                CgVfxEffect<?> effect = effects.get(i);
                // Killed since the update: cleared by its owner, or failed.
                if (effect.state() == CgVfxEffect.State.DEAD || effect.asleep) continue;
                int zone = effect.owner != null ? effect.owner.submitZone : EFFECT_ZONE;
                try (CgTrace.Zone submitting = CgTrace.zone(CgVfxTrace.CHANNEL, zone)) {
                    effect.clearBounds();
                    effect.submit(frame);
                } catch (RuntimeException e) {
                    fail(effect, e);
                }
            }
            frame.particles();
            frame.end();
            materials.upload();
            try (CgTrace.Zone upload = CgTrace.zone(CgVfxTrace.CHANNEL, PATHS_ZONE)) {
                paths.upload();
                bindPaths();
            }
        }
    }

    /**
     * Starts compiling every material a newly playing effect's look can draw, and polls what is still compiling: an
     * effect's last layers (a blast, its cloud) appear seconds after it starts, and compiling them then stalls that
     * frame.
     */
    private void warm(CgWorldRenderer world) {
        for (int i = 0; i < effects.size(); i++) {
            CgVfxEffect<?> effect = effects.get(i);
            if (!effect.gpuPrepared) {
                effect.gpuPrepared = true;
                // A look this system prepared already only starts the window: a cast prepares nothing twice.
                if (gpuLooks.add(effect.look())) prepareGpu(effect.look(), true);
                else useWindow(effect.look());
            }
            if (effect.warmed) continue;
            effect.warmed = true;
            if (drawLooks.add(effect.look())) prepareDraws(effect.look());
        }
        pollWarming(world);
    }

    /**
     * Warms what {@code look} simulates and draws before any effect of it plays: its GPU kernels, slot meshes and
     * materials' programs, so its first play stalls no frame. On the render thread, ahead of time. The work is queued
     * and done over the next frames within {@link CgWarmBudget}, polled while this system submits or {@link #warmed}
     * is asked; a look played before then builds what is left on its first frame.
     *
     * <pre>{@code
     * CgVfxSystem vfx = new CgVfxSystem();
     * vfx.prepare(CgEnergyWave.kamehameha());   // at init
     * // ...later, at once:
     * vfx.play(new CgEnergyWave(CgEnergyWave.kamehameha(), x, y, z));
     * }</pre>
     *
     * <p>A move made of several effects prepares every look it plays at once, then waits on {@link #warmed}:</p>
     *
     * <pre>{@code
     * vfx.prepare(SPIRAL, SHOCKWAVE, FLASH);
     * }</pre>
     */
    public void prepare(CgVfxLook... looks) {
        List<CgVfxLook> fresh = new ArrayList<>();
        List<CgMaterialShader> shaders = new ArrayList<>();
        // Its emitters join the loaded shapes first, so they warm as they will play: raised where coalescing raises them.
        Map<CgVfxEmitter, Integer> tiers = new LinkedHashMap<>();
        for (CgVfxLook look : looks) {
            if (gpuLooks.contains(look)) continue;
            for (CgVfxEmitter e : look.emitters()) tiers.putIfAbsent(e, feedTier(look, e));
        }
        for (CgVfxGpuEmitter moved : CgVfxShapeCoalescing.loaded().include(tiers)) {
            if (moved instanceof CgVfxEmitter e && preparedTier.containsKey(e) && !gpuWarming.contains(e)) {
                gpuWarming.add(e);
                gpuWarmingTier.put(e, preparedTier.get(e));
            }
        }
        for (CgVfxLook look : looks) {
            // Prepared here, its play only starts the window: its pools are sized once.
            if (!gpuLooks.add(look)) continue;
            List<CgVfxEmitter> emitters = look.emitters();
            for (int k = 0; k < emitters.size(); k++) {
                if (!gpuWarming.contains(emitters.get(k))) {
                    gpuWarming.add(emitters.get(k));
                    gpuWarmingTier.put(emitters.get(k), tiers.get(emitters.get(k)));
                    preparedTier.put(emitters.get(k), tiers.get(emitters.get(k)));
                }
                // The window fills only once played, but its kernels are the look's to build now.
                if (readsWorld(emitters.get(k))) CgWorldVoxelWindow.get().prepare();
            }
            drawWarming.add(look);
            materials.shaders(look, shaders);
            fresh.add(look);
        }
        if (!fresh.isEmpty()) {
            CompletableFuture<Void> next = CompletableFuture.runAsync(() -> loadAhead(fresh, shaders),
                    CgJob.executor(CgJobLane.LATENCY));
            CompletableFuture<Void> before = loading;
            loading = before == null ? next : CompletableFuture.allOf(before, next);
        }
        pollWarming(CgWorldRenderer.get());
    }

    /**
     * What preparing {@code looks} reads and generates, made on a worker so the queued steps find it cached: their
     * shaders parsed, Range's and the ops' kernel files and each emitter's Step kernel (its children's at their tiers),
     * the particle sphere. A failure here is left for the step that hits it, which reports it as it always has.
     */
    private static void loadAhead(List<CgVfxLook> looks, List<CgMaterialShader> shaders) {
        for (CgMaterialShader shader : shaders) shader.ensureParsed();
        CgVfxRange.loadAhead();
        for (CgVfxLook look : looks) {
            for (CgVfxEmitter emitter : look.emitters()) {
                loadAhead(emitter, 0);
                if (emitter.drawLayout() == CgVfxDrawLayout.MESHES) CgMeshShapes.sphereLods();
            }
        }
    }

    private static void loadAhead(CgVfxEmitter emitter, int tier) {
        CgVfxParticlePool.loadAhead(emitter, tier);
        for (int e = 0; e < emitter.events().size(); e++) {
            CgVfxEmitter child = (CgVfxEmitter) emitter.events().get(e).child();
            if (child != null) loadAhead(child, tier + 1);
        }
    }

    /**
     * Whether every program the looks {@link #prepare}d or played draw with is built, polling those still compiling, and
     * the world renderer's own list with them (the post stack's variants a look's glow and haze draw through). Hold
     * a scene's first submit on it and its first frames wait on no compile. Render thread.
     *
     * <pre>{@code
     * vfx.prepare(look);                  // at install
     * if (!vfx.warmed()) return;          // each frame, until it answers true
     * vfx.play(...);
     * }</pre>
     */
    public boolean warmed() {
        pollWarming(CgWorldRenderer.get());
        if (rehearsalFailure != null) throw new IllegalStateException("the rehearsal failed", rehearsalFailure);
        if (rehearsal != null && rehearsal.done()) endRehearsal();
        return loading == null && drawWarming.isEmpty() && warming.isEmpty() && gpuWarming.isEmpty() && rehearsal == null
                && CgWorldRenderer.get().programs().pending() == 0;
    }

    /**
     * Rehearses a move before its first cast, as {@link #prepare} warms its programs: {@code cast} plays the move into a
     * hidden system of its own, which is updated, submitted, its pools stepped and the world stages recorded and
     * executed each pass into a hidden target, until the JIT has settled ({@link CgRehearsal}). It runs at the frame boundary, a few
     * passes a frame under {@link CgWarmBudget}, once the programs {@link #prepare}d are built; {@link #warmed} answers
     * true when it is over.
     *
     * <pre>{@code
     * vfx.prepare(BEAM, BLAST);
     * vfx.rehearse(hidden -> hidden.play(CgEnergyWave.instance(BEAM, x, y, z)));   // again each time its effects end
     * if (!vfx.warmed()) return;                                              // each frame, then cast
     * }</pre>
     *
     * <ul>
     *   <li>{@code cast} plays what the real cast will, with its definitions: the JIT inlines for the types it sees.</li>
     *   <li>A pass that throws fails the rehearsal, and {@link #warmed} throws it.</li>
     *   <li>One rehearsal at a time a system. Render thread.</li>
     * </ul>
     */
    public CgRehearsal rehearse(Consumer<CgVfxSystem> cast) {
        if (rehearsal != null) throw new IllegalStateException("a rehearsal is already running");
        CgVfxSystem hidden = new CgVfxSystem();
        CgVfxParticlePool.hide(hidden.gpuSteps);
        double[] clock = {0.0};
        rehearsal = CgRehearsal.of(pass -> {
            CgVfxParticlePool.rehearsing(true);
            CgVfxRange.rehearsing(true);
            try {
                if (hidden.effects.isEmpty()) cast.accept(hidden);
                hidden.update(clock[0] += REHEARSAL_STEP);
                hidden.submit(CgWorldRenderer.get());
                CgVfxParticlePool.recordRehearsed(pass.recording());
                pass.execute();
                CgRenderStage.WORLD_OPAQUE.rehearse();
                CgRenderStage.WORLD_TRANSPARENT.rehearse();
            } finally {
                CgVfxParticlePool.rehearsing(false);
                CgVfxRange.rehearsing(false);
            }
        });
        rehearsed = hidden;
        rehearsalStep = CgFrameBoundary.add(CgFrameBoundary.Phase.DRAIN, "crystalgraphics:vfx-rehearsal", this::stepRehearsal);
        return rehearsal;
    }

    /** The boundary's step: once the programs are built, a frame's passes. */
    private void stepRehearsal() {
        if (rehearsal == null || rehearsal.done() || rehearsalFailure != null) return;
        if (loading != null || !drawWarming.isEmpty() || !warming.isEmpty() || !gpuWarming.isEmpty()) return;
        try {
            rehearsal.step();
        } catch (RuntimeException e) {
            rehearsalFailure = e;
        }
    }

    private void endRehearsal() {
        rehearsalStep.close();
        rehearsed.delete();
        rehearsal = null;
        rehearsed = null;
        rehearsalStep = null;
    }

    /** A rehearsal's clock step: a frame at 60 a second. */
    private static final double REHEARSAL_STEP = 1.0 / 60.0;
    @Nullable
    private CgRehearsal rehearsal;
    @Nullable
    private CgVfxSystem rehearsed;
    @Nullable
    private CgFrameBoundary.Function rehearsalStep;
    @Nullable
    private RuntimeException rehearsalFailure;

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

    /** {@code look}'s kernels and slot meshes; the voxel window's too when {@code useWindow}, which starts it filling. */
    private void prepareGpu(CgVfxLook look, boolean useWindow) {
        prepareRange();
        List<CgVfxEmitter> emitters = look.emitters();
        for (int k = 0; k < emitters.size(); k++) {
            CgVfxEmitter emitter = emitters.get(k);
            prepareEmitter(emitter, feedTier(look, emitter));
        }
        if (useWindow) useWindow(look);
    }

    /** The window's first use starts its kernels and its filling: at play, not on the first landing. */
    private static void useWindow(CgVfxLook look) {
        List<CgVfxEmitter> emitters = look.emitters();
        for (int k = 0; k < emitters.size(); k++) {
            if (readsWorld(emitters.get(k))) CgWorldVoxelWindow.get().use();
        }
    }

    /** Range and Place's kernels: below compute each is several lowered programs, which would stall the first burst. */
    private void prepareRange() {
        if (rangePrepared) return;
        rangePrepared = true;
        CgVfxRange.prepare();
    }

    /**
     * {@code emitter}'s Step kernels and slot meshes at feed {@code tier}, its children's and theirs too, each at its
     * own: built here, not in the first burst's frame. One that fails is logged once and skipped, never thrown out of the
     * submit that warms it: its effect then fails alone when it plays.
     */
    private void prepareEmitter(CgVfxEmitter emitter, int tier) {
        if (unprepared.contains(emitter)) return;
        try {
            CgVfxParticlePool.prepare(emitter, tier);
            CgVfxParticlePool.of(gpuSteps, emitter, tier).expect(emitter, emitter.peakAlive());
            CgVfxFrame.slotMesh(emitter.drawLayout(), emitter.peakAlive());
            prepareChildren(emitter, -1, tier + 1);
        } catch (RuntimeException e) {
            unprepared.add(emitter);
            LOGGER.error("[vfx] {} failed to prepare and is skipped", emitter.name(), e);
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

    private void prepareChildren(CgVfxEmitter parent, int fedAlive, int tier) {
        for (int e = 0; e < parent.events().size(); e++) {
            CgVfxEmitter child = (CgVfxEmitter) parent.events().get(e).child();
            if (child == null) continue;
            int alive = parent.peakChildren(e, fedAlive);
            CgVfxParticlePool.prepare(child, tier);
            CgVfxParticlePool.of(gpuSteps, child, tier).expect(child, alive);
            CgVfxFrame.slotMesh(child.drawLayout(), alive);
            prepareChildren(child, alive, tier + 1);
        }
    }

    /** Queues {@code look}'s materials for {@link #pollWarming}, and builds the particle sphere if it draws one. */
    private void prepareDraws(CgVfxLook look) {
        List<CgVfxEmitter> looked = look.emitters();
        for (int k = 0; k < looked.size(); k++) {
            // The particle sphere's levels are built on first ask: here, not in the first burst's frame.
            if (looked.get(k).drawLayout() == CgVfxDrawLayout.MESHES) particleSphere();
        }
        try (CgTrace.Zone ignored = CgTrace.zone(CgVfxTrace.CHANNEL, MATERIALS_ZONE)) {
            materials.prepare(look, warming, halved);
        }
        // Kept warm by the world renderer too, so a settings change (the HDR scene, half size) re-warms them first.
        CgWorldRenderer world = CgWorldRenderer.get();
        try (CgTrace.Zone ignored = CgTrace.zone(CgVfxTrace.CHANNEL, PROGRAMS_ZONE)) {
            for (int i = 0; i < warming.size(); i++) world.programs().add(warming.get(i), halved.contains(warming.get(i)));
        }
    }

    /**
     * The queued emitters, one a step while the frame's {@link CgWarmBudget} lasts; then every pass the world draws each
     * warming material with (depth, emissive, distortion, the joined form), not Forward alone.
     */
    private void pollWarming(CgWorldRenderer world) {
        if (loading != null) {
            if (!loading.isDone()) return;
            loading = null;
        }
        while (!drawWarming.isEmpty() && CgWarmBudget.allows()) {
            long start = System.nanoTime();
            CgVfxLook look = drawWarming.get(0);
            int before = warming.size();
            try (CgTrace.Zone ignored = CgTrace.zone(CgVfxTrace.CHANNEL, MATERIALS_ZONE)) {
                if (drawPlaced < look.placed().size()) {
                    materials.prepare(look, drawPlaced++, warming, halved);
                } else {
                    materials.prepareParticles(look, warming);
                    drawWarming.remove(0);
                    drawPlaced = 0;
                }
            }
            // Kept warm by the world renderer too, so a settings change (the HDR scene, half size) re-warms them first.
            for (int i = before; i < warming.size(); i++) world.programs().add(warming.get(i), halved.contains(warming.get(i)));
            CgWarmBudget.spend(System.nanoTime() - start);
        }
        while (drawWarming.isEmpty() && !gpuWarming.isEmpty() && CgWarmBudget.allows()) {
            long start = System.nanoTime();
            try (CgTrace.Zone ignored = CgTrace.zone(CgVfxTrace.CHANNEL, EMITTER_ZONE)) {
                if (!rangePrepared) {
                    CgVfxRange.PREPARE_STEPS.get(rangeStep++).run();
                    rangePrepared = rangeStep == CgVfxRange.PREPARE_STEPS.size();
                }
                else {
                    CgVfxEmitter next = gpuWarming.remove(0);
                    Integer tier = gpuWarmingTier.remove(next);
                    prepareEmitter(next, tier != null ? tier : 0);
                }
            }
            CgWarmBudget.spend(System.nanoTime() - start);
        }
        for (int i = warming.size() - 1; i >= 0; i--) {
            CgMaterial material = warming.get(i);
            if (world.prepare(material, halved.contains(material))) {
                warming.remove(i);
                halved.remove(material);
            }
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

    /** Releases its meshes and frees the path texture; materials belong to the material registry. */
    public void delete() {
        if (rehearsal != null) endRehearsal();
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
        gpuWarming.clear();
        gpuWarmingTier.clear();
        drawWarming.clear();
        drawPlaced = 0;
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
