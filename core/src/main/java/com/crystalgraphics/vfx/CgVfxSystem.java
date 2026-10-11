package com.crystalgraphics.vfx;

import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshLods;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.gl.buffer.shader.CgParticleBuffer;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgEntityQuery;
import com.crystalgraphics.platform.service.CgWorldQuery;
import com.crystalgraphics.render.stage.CgHostEnvironment;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.render.world.CgSortLayer;
import com.crystalgraphics.render.world.CgWorldLight;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.particle.CgVfxAir;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.CgVfxParticleSet;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEventListener;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.gpu.draw.CgVfxRange;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import com.crystalgraphics.vfx.path.CgVfxPathTexture;
import com.crystalgraphics.vfx.render.CgVfxQuads;
import com.crystalgraphics.vfx.render.CgVfxRibbons;
import com.crystalgraphics.vfx.render.CgVfxTube;
import com.crystalgraphics.vfx.world.CgVfxVoxelWindow;
import com.crystalgraphics.world.CgEntityAttachments;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Plays {@link CgVfxEffect}s: simulates them on a fixed tick, draws them into a world each frame, and owns what they
 * share on the GPU (the path texture, the meshes, a material per {@link CgVfxLayer}).
 *
 * <pre>{@code
 * CgVfxSystem vfx = new CgVfxSystem();
 * CgEnergyWave wave = vfx.play(new CgEnergyWave(CgEnergyWave.kamehameha(), x, y, z));
 * // every frame, before the world stages record (a CgWorldRenderer.onFrame listener, or ahead of a harness's fire):
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
 *   <li>Each tick steps every effect in order, then runs their particle emitters on several threads, one effect a
 *       thread ({@code -Dcrystalgraphics.vfx.threads}, one per core by default).</li>
 *   <li>{@link #submit} is render thread, and must run every frame an effect draws: the path texture and the particle
 *       buffer hold only the last upload.</li>
 *   <li>Every mesh the package draws is made here, so a change to how meshes are made is one edit.</li>
 *   <li>{@link #air} is the wind every effect's particles move through; set it once, or change it while playing.</li>
 *   <li>It applies the player's settings ({@link CgGraphicsSettings}) to every effect: particle density and the
 *       quality tier, read once an update. Its clock stops while the game is paused or {@code /tick freeze} holds the
 *       world, and runs at the world's tick rate.</li>
 * </ul>
 */
public final class CgVfxSystem {

    /**
     * Where every system's particles are simulated and prepared for drawing: today's Java path, or the GPU's.
     * The GPU's by default; {@code -Dcrystalgraphics.vfx.sim=cpu} picks the Java path at launch, and
     * {@link #simulation(Simulation)} switches it live.
     *
     * <pre>{@code
     * CgVfxSystem.simulation(CgVfxSystem.Simulation.GPU);   // every system, from the next update
     * }</pre>
     *
     * <ul>
     *   <li>A switch reaches emitter instances that start after it; one already playing finishes where it started.</li>
     * </ul>
     */
    public enum Simulation {
        CPU, GPU
    }

    private static final CgVar.Choice<Simulation> SIMULATION = CgVar.choice("crystalgraphics.vfx.sim", Simulation.GPU,
            CgVar.DEV, "Where effects' particles simulate; V switches it in any harness scene with a 3D camera. cpu is the"
                    + " Java path, kept as the comparison until it is removed.");
    private static final CgVar.Int PARTICLE_STEP = CgVar.integer("crystalgraphics.vfx.particleStep", 2, 1, 64, CgVar.DEV,
            "Ticks a particle step spans: 2 steps particles at 60 Hz while effects tick at 120, 1 every tick.");
    private static final CgVar.Text SKIP = CgVar.text("crystalgraphics.vfx.skip", "", CgVar.DEV,
            "Comma-separated: layers whose shader path contains any of these draw nothing, to see what a frame's GPU"
                    + " time is spent on, one profile per group (body_light,haze).");
    private static final CgVar.Num SIM_BUDGET_MS = CgVar.number("crystalgraphics.vfx.simBudgetMs", 12f, 0f, 1000f, CgVar.DEV,
            "Wall milliseconds an update may spend catching up ticks before it drops the rest and effects slow down.");
    // Measurement switches: each is what one saving on the beams' GPU time would be worth.
    private static final CgVar.Bool COARSE_VOLUMES = CgVar.bool("crystalgraphics.vfx.coarseVolumes", false,
            CgVar.DEV | CgVar.RESTART, "Measurement: volume layers on a 12 x 24 sphere instead of 48 x 96.");
    private static final CgVar.Bool SHARED_DISTORTION = CgVar.bool("crystalgraphics.vfx.sharedDistortion", false, CgVar.DEV,
            "Measurement: distortion layers after every effect's draws, so they share one copy of the target.");

    /** The simulation chosen for every system: what a HUD shows, built or not. */
    public static Simulation simulation() {
        return SIMULATION.get();
    }

    /** Chooses where every system simulates, from its next update: a person's switch, outranking {@code -D}. */
    public static void simulation(Simulation chosen) {
        SIMULATION.set(CgVar.Source.CONSOLE, chosen);
    }

    /**
     * Ticks a particle step spans in every system: particles move every {@code particleStep()}th tick, by that many
     * ticks' time. 2 by default, so 60 Hz while effects tick at 120; {@code -Dcrystalgraphics.vfx.particleStep} sets it
     * at launch and {@link #particleStep(int)} live.
     *
     * <pre>{@code
     * CgVfxSystem.particleStep(1);   // every tick, 120 Hz: what the CPU path did before
     * }</pre>
     */
    public static int particleStep() {
        return PARTICLE_STEP.get();
    }

    /** Sets {@link #particleStep()} for every system, from its next update; at least 1. A person's switch, outranking {@code -D}. */
    public static void particleStep(int ticks) {
        PARTICLE_STEP.set(CgVar.Source.CONSOLE, ticks);
    }

    /**
     * Layers whose shader path contains any of these draw nothing, in every system: to see or time a frame without
     * them. {@code -Dcrystalgraphics.vfx.skip=haze,body_light} sets it at launch and {@link #skip(String...)} live.
     *
     * <pre>{@code
     * CgVfxSystem.skip("body_light", "orb_light");  // the beams' light pools off
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

    private static final int TICK_ZONE = CgTrace.name("vfx.tick"), SUBMIT_ZONE = CgTrace.name("vfx.submit"),
            WARM_ZONE = CgTrace.name("vfx.warm"), EFFECT_ZONE = CgTrace.name("vfx.effect.submit"),
            PATHS_ZONE = CgTrace.name("vfx.paths.upload"), PARTICLES_ZONE = CgTrace.name("vfx.particles.write"),
            TICKS = CgTrace.name("vfx.ticks"), CAPPED = CgTrace.name("vfx.ticks.capped"), HELD = CgTrace.name("vfx.ticks.held"),
            EMITTERS_ZONE = CgTrace.name("vfx.emitters"), EFFECT_TICK_ZONE = CgTrace.name("vfx.effect.tick"),
            WORKERS_ZONE = CgTrace.name("vfx.emitters.workers"), EFFECT_EMITTERS_ZONE = CgTrace.name("vfx.effect.emitters"), ADMIT_ZONE = CgTrace.name("vfx.emitters.admit"),
            GPU_STEPS_ZONE = CgTrace.name("vfx.gpu.steps"),
            EFFECTS = CgTrace.name("vfx.effects"), PARTICLES_WRITTEN = CgTrace.name("vfx.particles.written"),
            FILL_ZONE = CgTrace.name("vfx.particles.fill"), LIGHT_ZONE = CgTrace.name("vfx.particles.light"),
            UPLOAD_ZONE = CgTrace.name("vfx.particles.upload");

    /** Seconds of one simulation step. */
    public static final float TICK = 1f / 120f;
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
    /** Fewer records than this are written on the render thread alone. */
    private static final int PARALLEL_RECORDS = 4096;
    /**
     * Fewer CPU-stepped particles than this tick on the render thread alone: a GPU-stepped emitter's tick is a few
     * stores, and workers woken for it could take the render thread's core on a loaded machine (33 ms, mc-perf-notes).
     */
    private static final int PARALLEL_PARTICLES = 4096;
    /** Or this many effects with emitters due: about a millisecond of GPU-stepped ticks (0.25 us each). */
    private static final int PARALLEL_EFFECTS = 4096;
    private static final int MAX_TICKS = 12;
    /** GPU steps a pool may hold unrecorded, half a second at 60 Hz, before every effect waits for the world to draw. */
    private static final int MAX_QUEUED_STEPS = 30;
    private static final String PATH_SAMPLER = "_FxPath";

    private static final CgSortLayer DISTORTION = CgSortLayer.after("crystalgraphics:vfx-distortion", CgSortLayer.EFFECTS);

    private static final List<CgVfxEventListener> NO_LISTENERS = Collections.emptyList();

    private final List<CgVfxEffect> effects = new ArrayList<>();
    private final List<CgVfxMomentListener> momentListeners = new ArrayList<>();
    private final List<CgVfxEventListener> eventListeners = new ArrayList<>();
    private final CgVfxPathTexture paths = new CgVfxPathTexture();
    private final CgVfxTube tube = new CgVfxTube();
    private final CgVfxFrame frame = new CgVfxFrame(this);
    private final IdentityHashMap<CgVfxLayer, CgMaterial> materials = new IdentityHashMap<>();
    /** Each layer's material's batch key: the order the materials were made in. */
    private final IdentityHashMap<CgVfxLayer, Integer> materialKeys = new IdentityHashMap<>();
    private final List<CgMaterial> unbound = new ArrayList<>();
    /** Materials compiling ahead of their first draw, so a layer that appears late does not stall its frame. */
    private final List<CgMaterial> warming = new ArrayList<>();
    private CgTexture2D boundTexture;
    // The tube is this system's own; the ribbons, sphere and quads are shared.
    private CgMesh tubeMesh, closedTubeMesh, ribbonMesh, sphereMesh, quadMesh, volumeMesh;
    /** The emitters drawn this frame through the particle buffer, in the order their records go into it. */
    private final List<CgVfxEmitterInstance> particleEmitters = new ArrayList<>();
    private int particleRecords;
    private final CgVfxAir air = new CgVfxAir();
    private final CgVfxWorkers workers = new CgVfxWorkers();
    /** The effects that queued emitter ticks this tick: what the workers run. */
    private final List<CgVfxEffect> emitting = new ArrayList<>();
    private final CgVfxWorkers.Job tickEach = i -> {
        CgVfxEffect effect = emitting.get(i);
        try (CgTrace.Zone ignored = CgTrace.zone(CgVfxTrace.CHANNEL, EFFECT_EMITTERS_ZONE)) {
            effect.tickEmitters();
        } catch (RuntimeException e) {
            effect.failure = e;
        }
    };
    /** The owners whose effects it has played, for their counters. */
    private final List<CgVfxOwner> owners = new ArrayList<>();
    private final double[] pose = new double[CgEntityQuery.POSE_LENGTH], point = new double[3];
    private float timeScale = 1f;
    private final CgVfxWorkers.Job writeEach = this::writeRecords;
    private final CgVfxGpuSteps gpuSteps = new CgVfxGpuSteps();
    private boolean stepsOnGpu, rangePrepared;
    /** This frame's particle records, filled by {@link #writeEach}; each emitter's first record in {@link #bases}. */
    private float[] records = new float[0];
    private int[] bases = new int[0];
    private float writeAlpha;
    private boolean writeLit;
    /** Ticks since the particles' last step, and how many ticks that step spanned. */
    private int tickCount, sinceParticleTick, drawnStep = 1;
    private boolean particleTick = true;
    /** Seconds the particles' last step moved them by: what a frame draws between. */
    private float particleDt = TICK;
    private double clock = Double.NaN;
    private float owed, simulated;
    private float density = 1f;
    private CgQuality quality = CgQuality.HIGH;

    public <E extends CgVfxEffect> E play(E effect) {
        if (effect.system != null) throw new IllegalStateException("already played: " + effect);
        effect.system = this;
        effects.add(effect);
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
     * Multiplies the pace of everything this system plays: 0.25 is slow motion, 0 holds it. For tools and showcases; the
     * world's pause and tick rate apply on top.
     *
     * <pre>{@code
     * CgVfx.world().timeScale(0.25f);
     * }</pre>
     */
    public CgVfxSystem timeScale(float scale) {
        timeScale = Math.max(0f, scale);
        return this;
    }

    public float timeScale() {
        return timeScale;
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

    void moment(CgVfxEffect effect, String name, double x, double y, double z, float radius) {
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
        CgHostEnvironment world = CgRenderStage.WORLD_OPAQUE.host().environment();
        readSettings(world);
        stepsOnGpu = SIMULATION.get() == Simulation.GPU;
        follow();
        if (Double.isNaN(clock)) {
            clock = seconds;
            return;
        }
        owed = Math.max(0f, owed + (float) (seconds - clock) * pace(world) * timeScale);
        clock = seconds;
        // WORLD_OPAQUE records the GPU's steps. While it does not fire, every effect holds, the CPU's included, as
        // Niagara and VFX Graph pause what is not drawn: dropped steps would leave particles alive in a slot the CPU
        // closes as finished.
        if (gpuSteps.queuedSteps() >= MAX_QUEUED_STEPS) {
            owed = 0f;
            CgVfxTrace.count(HELD, 1);
            return;
        }
        startLate();
        int ticks = 0, step = PARTICLE_STEP.get();
        long budget = (long) (SIM_BUDGET_MS.get() * 1_000_000L), start = System.nanoTime();
        boolean overBudget = false;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, "vfx.sim")) {
            while (owed >= TICK && ticks < MAX_TICKS && !overBudget) {
                try (CgTrace.Zone tick = CgTrace.zone(CgVfxTrace.CHANNEL, TICK_ZONE)) {
                    air.tick(simulated);
                    particleTick = tickCount++ % step == 0;
                    if (particleTick) {
                        particleDt = TICK * step;
                        drawnStep = step;
                    }
                    int cpuParticles = 0;
                    for (int i = 0; i < effects.size(); i++) {
                        CgVfxEffect effect = effects.get(i);
                        if (effect.state() != CgVfxEffect.State.DEAD) {
                            int zone = effect.owner != null ? effect.owner.tickZone : EFFECT_TICK_ZONE;
                            try (CgTrace.Zone stepping = CgTrace.zone(CgVfxTrace.CHANNEL, zone)) {
                                effect.step(TICK);
                            } catch (RuntimeException e) {
                                fail(effect, e);
                            }
                        }
                        if (effect.hasEmitterTicks()) {
                            emitting.add(effect);
                            cpuParticles += effect.dueCpuParticles();
                        }
                    }
                    try (CgTrace.Zone run = CgTrace.zone(CgVfxTrace.CHANNEL, EMITTERS_ZONE)) {
                        if (cpuParticles >= PARALLEL_PARTICLES || emitting.size() >= PARALLEL_EFFECTS) {
                            try (CgTrace.Zone ticking = CgTrace.zone(CgVfxTrace.CHANNEL, WORKERS_ZONE)) {
                                workers.run(emitting.size(), tickEach);
                            }
                        } else {
                            for (int i = 0; i < emitting.size(); i++) tickEach.run(i);
                        }
                        try (CgTrace.Zone admitting = CgTrace.zone(CgVfxTrace.CHANNEL, ADMIT_ZONE)) {
                            for (int i = 0; i < emitting.size(); i++) {
                                CgVfxEffect effect = emitting.get(i);
                                if (effect.failure != null) {
                                    fail(effect, effect.failure);
                                    effect.failure = null;
                                }
                                // A failed effect's instances already scheduled still take their slots, and coast.
                                effect.admitScheduled(gpuSteps);
                                effect.deliverRows(eventListeners);
                            }
                        }
                    } finally {
                        emitting.clear();
                    }
                    if (particleTick) {
                        try (CgTrace.Zone steps = CgTrace.zone(CgVfxTrace.CHANNEL, GPU_STEPS_ZONE)) {
                            gpuSteps.step(particleDt, air);
                        }
                    }
                    sinceParticleTick = particleTick ? 0 : sinceParticleTick + 1;
                }
                simulated += TICK;
                owed -= TICK;
                ticks++;
                overBudget = System.nanoTime() - start >= budget;
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
            CgVfxEffect effect = effects.get(i);
            if (effect.state() == CgVfxEffect.State.DEAD) {
                // Killed, its particles go with it; one that died on its own lets them finish.
                if (effect.killed) effect.killSlots(gpuSteps);
                if (effect.owner != null) effect.owner.release(effect);
                continue;
            }
            if (kept != i) effects.set(kept, effect);
            kept++;
        }
        for (int i = effects.size() - 1; i >= kept; i--) effects.remove(i);
    }

    /** Every owner it lists lets go of its effects at once. */
    private void releaseOwners() {
        for (int i = 0; i < owners.size(); i++) owners.get(i).releaseAll();
    }

    /** Kills {@code effect} over what it threw, logged under its owner; the rest play on. */
    private static void fail(CgVfxEffect effect, RuntimeException e) {
        LOGGER.error("[CgVfxSystem] {} played by {} threw, and is killed", effect.getClass().getName(),
                effect.owner != null ? effect.owner : "its system", e);
        effect.kill();
    }

    /**
     * Moves each effect following an entity to the entity's attachment point as drawn this frame; stops one whose entity
     * is gone, where it was last.
     */
    private void follow() {
        CgEntityQuery entities = null;
        float partialTick = 0f;
        for (int i = 0; i < effects.size(); i++) {
            CgVfxEffect effect = effects.get(i);
            if (effect.followEntity < 0 || effect.state() == CgVfxEffect.State.DEAD) continue;
            if (entities == null) {
                entities = CgPlatform.get(CgEntityQuery.SERVICE);
                partialTick = CgRenderStage.WORLD_OPAQUE.host().partialTick();
            }
            if (entities.pose(effect.followEntity, partialTick, pose)) {
                CgEntityAttachments.point(effect.followPoint, pose, point);
                effect.moveTo(point[0], point[1], point[2]);
            } else {
                effect.followEntity = -1;
                effect.stop();
            }
        }
    }

    /** Steps each effect played with a start age to it, ahead of this update's ticks. */
    private void startLate() {
        for (int i = 0; i < effects.size(); i++) {
            CgVfxEffect effect = effects.get(i);
            if (effect.startAge <= 0f) continue;
            float target = effect.startAge;
            effect.startAge = 0f;
            if (effect.state() == CgVfxEffect.State.DEAD) continue;
            boolean wasTick = particleTick;
            float wasDt = particleDt;
            effect.seeking = true;
            try {
                fastForward(effect, target);
            } catch (RuntimeException e) {
                fail(effect, e);
            } finally {
                effect.seeking = false;
                gpuSteps.endPrewarm();
                particleTick = wasTick;
                particleDt = wasDt;
            }
        }
    }

    /**
     * Steps {@code effect} alone through {@code target} seconds on its fixed tick. On the GPU its emitters keep their
     * books throughout and take slots only for the last {@link #PREWARM_SECONDS}, stepped in at most
     * {@link #PREWARM_STEPS} steps while every other slot holds still: older particles would be dead by now.
     */
    private void fastForward(CgVfxEffect effect, float target) {
        float stepped = Math.min(target, MAX_START_SECONDS);
        int ticks = Math.round(stepped / TICK), windowTicks = Math.round(Math.min(stepped, PREWARM_SECONDS) / TICK);
        int windowFrom = ticks - windowTicks;
        boolean gpu = stepsOnGpu;
        int particleStep = PARTICLE_STEP.get();
        int coarse = gpu ? Math.max(particleStep, (windowTicks + PREWARM_STEPS - 1) / PREWARM_STEPS) : particleStep;
        for (int t = 0; t < ticks && effect.state() != CgVfxEffect.State.DEAD; t++) {
            boolean inWindow = t >= windowFrom;
            if (gpu && t == windowFrom) {
                gpuSteps.beginPrewarm();
                effect.admitScheduled(gpuSteps);
            }
            int step = inWindow ? coarse : particleStep;
            particleTick = (inWindow ? t - windowFrom : t) % step == 0;
            if (particleTick) particleDt = TICK * step;
            effect.step(TICK);
            if (effect.hasEmitterTicks()) {
                effect.tickEmitters();
                if (effect.failure != null) throw effect.failure;
                // Before the window its instances keep their books alone: admitted at its start.
                if (inWindow || !gpu) effect.admitScheduled(gpuSteps);
                effect.deliverRows(NO_LISTENERS);
            }
            if (gpu && inWindow && particleTick) gpuSteps.prewarmStep(particleDt, air);
        }
        if (target > stepped) effect.age = target;
    }

    /**
     * Whether an emitter instance starting this update is stepped on the GPU: read once an update, so the workers see
     * one answer. An instance already stepping keeps its path.
     */
    boolean stepsOnGpu() {
        return stepsOnGpu;
    }

    /** Its GPU queue: the pools' owner. */
    CgVfxGpuSteps gpuSteps() {
        return gpuSteps;
    }

    /** {@code emitter}'s slot in a GPU pool, or null when it holds none: stepped on the CPU, or every particle dead. */
    CgVfxGpuSteps.Tenant gpuTenant(CgVfxEmitterInstance emitter) {
        return gpuSteps.of(emitter);
    }

    /** Whether this tick moves particles: one in {@link #particleStep()}. */
    boolean particleTick() {
        return particleTick;
    }

    /** Seconds this tick's particle step moves particles by. */
    float particleDt() {
        return particleDt;
    }

    /** The share of {@code emitter}'s particles to spawn: the player's density, halved again at Low for an optional one. */
    public float spawnShare(CgVfxEmitter emitter) {
        return emitter.optional() && quality == CgQuality.LOW ? density * 0.5f : density;
    }

    /** The quality tier this update draws at. */
    public CgQuality quality() {
        return quality;
    }

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
     * The particles drawn in the latest frame, every system's: those the CPU path submitted, and those Range kept to draw
     * from the GPU pools after culling, which arrive a frame or two late. For a readout, never a decision; the first ask
     * starts the GPU count's small readback. Render thread.
     *
     * <pre>{@code
     * hud.line("Particles: " + CgVfxSystem.particlesDrawn());
     * }</pre>
     */
    public static int particlesDrawn() {
        return cpuDrawn + CgVfxRange.visibleCount();
    }

    /**
     * The GPU pools' particles drawn into {@code framebuffer} in the latest frame: a viewport's own, not the world's. For a
     * readout, never a decision; a frame or two late, as {@link #particlesDrawn()}. Render thread.
     */
    public static int particlesDrawn(int framebuffer) {
        return CgVfxRange.visibleCount(framebuffer);
    }

    /** The CPU path's particles drawn in {@link #drawnFrame}, every system's; reset by its first submit. */
    private static int cpuDrawn;
    private static long drawnFrame = -1;

    /** Draws every playing effect into {@code world}, interpolated between the last two ticks. Render thread. */
    public void submit(CgWorldRenderer world) {
        if (CgFrameRing.frame() != drawnFrame) {
            drawnFrame = CgFrameRing.frame();
            cpuDrawn = 0;
        }
        if (effects.isEmpty()) return;
        if (tubeMesh == null) {
            tubeMesh = CgVfxTube.mesh(false);
            closedTubeMesh = CgVfxTube.mesh(true);
            ribbonMesh = CgVfxRibbons.mesh();
            sphereMesh = CgMeshShapes.sphere(48, 96);
            // 12 x 24 faces come within cos(15°) cos(7.5°) of the centre: at radius 1.05 they still enclose the unit sphere.
            volumeMesh = COARSE_VOLUMES.get()
                    ? CgMesh.build(CgVertexFormat.SPATIAL, m -> CgMeshShapes.sphere(m, 12, 24, 1.05f)) : sphereMesh;
            quadMesh = CgVfxQuads.mesh();
        }
        try (CgTrace.Zone ignored = CgTrace.zone(CgVfxTrace.CHANNEL, SUBMIT_ZONE)) {
            try (CgTrace.Zone warming = CgTrace.zone(CgVfxTrace.CHANNEL, WARM_ZONE)) {
                warm(world);
            }
            float alpha = Math.min(owed / TICK, 1f);
            // Particles hold their last two steps, a step apart: drawn one step behind, as the rest is a tick behind.
            double gx = 0, gy = 0, gz = 0;
            for (int i = 0; i < effects.size(); i++) {
                CgVfxEffect effect = effects.get(i);
                gx += effect.originX;
                gy += effect.originY;
                gz += effect.originZ;
            }
            int n = effects.size();
            frame.begin(world, alpha, Math.min((sinceParticleTick + alpha) / drawnStep, 1f), gx / n, gy / n, gz / n);
            gpuSteps.frame(frame.particleAlpha(), frame.particleAlpha() * particleDt);
            paths.begin();
            for (int i = 0; i < effects.size(); i++) {
                CgVfxEffect effect = effects.get(i);
                // Killed since the update: cleared by its owner, or failed.
                if (effect.state() == CgVfxEffect.State.DEAD) continue;
                int zone = effect.owner != null ? effect.owner.submitZone : EFFECT_ZONE;
                try (CgTrace.Zone submitting = CgTrace.zone(CgVfxTrace.CHANNEL, zone)) {
                    effect.submit(frame);
                } catch (RuntimeException e) {
                    fail(effect, e);
                }
            }
            frame.end();
            cpuDrawn += frame.cpuDrawn();
            try (CgTrace.Zone upload = CgTrace.zone(CgVfxTrace.CHANNEL, PATHS_ZONE)) {
                paths.upload();
                bindPaths();
            }
            try (CgTrace.Zone write = CgTrace.zone(CgVfxTrace.CHANNEL, PARTICLES_ZONE)) {
                writeParticles(frame.particleAlpha());
            }
        }
    }

    /**
     * Where {@code emitter}'s records start in this frame's particle buffer, adding them on its first draw this frame.
     * Its draws read {@code [base, base + count)}.
     */
    int particleBase(CgVfxEmitterInstance emitter) {
        int base = 0;
        for (int i = 0; i < particleEmitters.size(); i++) {
            if (particleEmitters.get(i) == emitter) return base;
            base += particleEmitters.get(i).particles().count();
        }
        particleEmitters.add(emitter);
        particleRecords += emitter.particles().count();
        return base;
    }

    /** Every particle drawn this frame into the buffer, once, in the order their bases were handed out. */
    private void writeParticles(float alpha) {
        int total = particleRecords, emitters = particleEmitters.size();
        if (total > 0) {
            CgVfxTrace.count(PARTICLES_WRITTEN, total);
            // Touches CgParticleBuffer on the render thread first: it makes its buffer when first touched.
            int floats = total * CgParticleBuffer.RECORD_FLOATS;
            if (records.length < floats) records = new float[Math.max(floats, records.length + records.length / 2)];
            if (bases.length < emitters) bases = new int[Math.max(emitters, bases.length * 2)];
            for (int k = 0, base = 0; k < emitters; k++) {
                bases[k] = base;
                base += particleEmitters.get(k).particles().count();
            }
            writeAlpha = alpha;
            // The host's light is read on the render thread alone; with no level every record is fully lit.
            writeLit = CgPlatform.get(CgWorldQuery.SERVICE).levelEpoch() != 0;
            try (CgTrace.Zone fill = CgTrace.zone(CgVfxTrace.CHANNEL, FILL_ZONE)) {
                if (total >= PARALLEL_RECORDS) {
                    workers.run(emitters, writeEach);
                } else {
                    for (int k = 0; k < emitters; k++) writeRecords(k);
                }
            }
            if (writeLit) {
                try (CgTrace.Zone lit = CgTrace.zone(CgVfxTrace.CHANNEL, LIGHT_ZONE)) {
                    for (int k = 0; k < emitters; k++) {
                        CgVfxEmitterInstance emitter = particleEmitters.get(k);
                        CgVfxParticleSet p = emitter.particles();
                        for (int i = 0; i < p.count(); i++) {
                            int light = CgWorldLight.at(emitter.originX() + p.x(i, alpha), emitter.originY() + p.y(i, alpha),
                                    emitter.originZ() + p.z(i, alpha));
                            CgParticleBuffer.light(records, bases[k] + i, light);
                        }
                    }
                }
            }
            try (CgTrace.Zone upload = CgTrace.zone(CgVfxTrace.CHANNEL, UPLOAD_ZONE)) {
                CgParticleBuffer.upload(records, total);
            }
        }
        particleEmitters.clear();
        particleRecords = 0;
    }

    /** Emitter {@code k}'s records into {@link #records}, from {@link #bases}{@code [k]}: one of {@link #writeEach}'s jobs. */
    private void writeRecords(int k) {
        CgVfxEmitterInstance emitter = particleEmitters.get(k);
        CgVfxEmitter def = emitter.emitter();
        CgVfxParticleSet p = emitter.particles();
        float alpha = writeAlpha, ahead = alpha * particleDt;
        int light = writeLit ? 0 : CgWorldLight.FULL, base = bases[k];
        for (int i = 0; i < p.count(); i++) {
            float t = p.progress(i);
            CgParticleBuffer.write(records, base + i, p.x(i, alpha), p.y(i, alpha), p.z(i, alpha), p.size[i] * def.sizeAt(t),
                    p.vx[i], p.vy[i], p.vz[i], t, p.seed[i], p.spin[i] + p.spinRate[i] * ahead, p.heat[i], def.opacityAt(t),
                    light);
        }
    }

    /**
     * Starts compiling every material a newly playing effect's look can draw, and polls what is still compiling: an
     * effect's last layers (a blast, its cloud) appear seconds after it starts, and compiling them then stalls that
     * frame.
     */
    private void warm(CgWorldRenderer world) {
        for (int i = 0; i < effects.size(); i++) {
            CgVfxEffect effect = effects.get(i);
            if (stepsOnGpu && !effect.gpuPrepared) {
                effect.gpuPrepared = true;
                prepareGpu(effect.look(), true);
            }
            if (effect.warmed) continue;
            effect.warmed = true;
            prepareDraws(effect.look());
        }
        pollWarming(world);
    }

    /**
     * Warms what {@code look} simulates and draws before any effect of it plays: its GPU kernels, slot meshes and
     * materials' programs, so its first play stalls no frame. On the render thread, ahead of time: a loading screen's
     * work. Programs still compiling are polled while this system submits.
     *
     * <pre>{@code
     * CgVfxSystem vfx = new CgVfxSystem();
     * vfx.prepare(CgEnergyWave.kamehameha());   // at init
     * // ...later, at once:
     * vfx.play(new CgEnergyWave(CgEnergyWave.kamehameha(), x, y, z));
     * }</pre>
     */
    public void prepare(CgVfxLook look) {
        prepareGpu(look, false);
        prepareDraws(look);
        pollWarming(CgWorldRenderer.get());
    }

    /**
     * Whether every program the looks {@link #prepare}d or played draw with is built, polling those still compiling. Hold
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
        return warming.isEmpty();
    }

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
        for (int i = 0; i < effects.size(); i++) effects.get(i).kill();
        effects.clear();
        releaseOwners();
        // Drops the pools, not just their slots: a slot reopened over live particles hands them to its next owner.
        gpuSteps.clear();
        clock = Double.NaN;
        owed = 0f;
    }

    /** {@code look}'s kernels and slot meshes; the voxel window's too when {@code useWindow}, which starts it filling. */
    private void prepareGpu(CgVfxLook look, boolean useWindow) {
        // Below compute each kernel is several lowered programs; built on the first burst, they stall it.
        if (!rangePrepared) {
            rangePrepared = true;
            CgVfxRange.prepare();
        }
        List<CgVfxEmitter> emitters = look.emitters();
        for (int k = 0; k < emitters.size(); k++) {
            CgVfxEmitter emitter = emitters.get(k);
            CgVfxParticlePool.prepare(emitter);
            // Each slot's draw mesh, built and placed on its first ask: here, not in the first burst's frame.
            CgVfxFrame.slotMesh(emitter.renderer(), emitter.peakAlive());
            for (int e = 0; e < emitter.events().size(); e++) {
                CgVfxGpuEmitter child = emitter.events().get(e).child();
                if (child == null) continue;
                CgVfxParticlePool.prepare(child);
                CgVfxFrame.slotMesh(child.renderer(), emitter.peakChildren(e));
            }
            // The window's first use starts its kernels and its filling: at play, not on the first landing.
            if (useWindow && readsWorld(emitter)) CgVfxVoxelWindow.get().use();
        }
    }

    /** Queues {@code look}'s materials for {@link #pollWarming}, and builds the particle sphere if it draws one. */
    private void prepareDraws(CgVfxLook look) {
        List<CgVfxEmitter> looked = look.emitters();
        for (int k = 0; k < looked.size(); k++) {
            // The particle sphere's levels are built on first ask: here, not in the first burst's frame.
            if (looked.get(k).renderer() == CgVfxEmitter.Renderer.MESHES) particleSphere();
        }
        List<CgVfxLayer> layers = look.layers();
        for (int k = 0; k < layers.size(); k++) {
            CgMaterial material = material(layers.get(k));
            if (!warming.contains(material)) warming.add(material);
        }
    }

    /** Every pass the world draws a material with (depth, emissive, distortion, the joined form), not Forward alone. */
    private void pollWarming(CgWorldRenderer world) {
        for (int i = warming.size() - 1; i >= 0; i--) {
            if (world.prepare(warming.get(i))) warming.remove(i);
        }
    }

    private static boolean readsWorld(CgVfxEmitter emitter) {
        for (int i = 0; i < emitter.modules().size(); i++) {
            if (emitter.modules().get(i).worldInputs().length > 0) return true;
        }
        return false;
    }

    public List<CgVfxEffect> effects() {
        return effects;
    }

    /** Releases its meshes and frees the path texture; materials belong to the material registry. */
    public void delete() {
        effects.clear();
        releaseOwners();
        for (int i = 0; i < eventListeners.size(); i++) CgVfxParticlePool.stopListening(eventListeners.get(i));
        eventListeners.clear();
        gpuSteps.clear();
        paths.delete();
        if (tubeMesh != null) tubeMesh.release();
        if (closedTubeMesh != null) closedTubeMesh.release();
        if (volumeMesh != null && volumeMesh != sphereMesh) volumeMesh.release();
        tubeMesh = null;
        closedTubeMesh = null;
        ribbonMesh = null;
        sphereMesh = null;
        volumeMesh = null;
        quadMesh = null;
        boundTexture = null;
        unbound.addAll(materials.values());
        warming.clear();
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

    /** A {@link CgVfxLayer#isClosed() closed} layer's chunk mesh. */
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

    /** What a volume layer's chunks are drawn on: the sphere, or with {@link #COARSE_VOLUMES} a coarse one round it. */
    CgMesh volumeMesh() {
        return volumeMesh;
    }

    /**
     * The sort layer an effect's draw of {@code layer} goes in: {@link CgSortLayer#EFFECTS}, or with
     * {@code -Dcrystalgraphics.vfx.sharedDistortion=true} every distortion layer after all of them.
     */
    public static CgSortLayer sortLayer(CgVfxLayer layer) {
        return SHARED_DISTORTION.get() && layer.order() == CgVfxLayer.ORDER_DISTORTION ? DISTORTION : CgSortLayer.EFFECTS;
    }

    /**
     * {@code draw} placed as a draw of {@code layer} in the effect at {@code (ox, oy, oz)}: its sort layer, the effect's
     * group and the layer's order, and a sharp layer ({@link CgVfxLayer#ORDER_SURFACE} and up) after the distortion
     * apply, so no haze bends it.
     */
    public static CgWorldRenderer.Draw place(CgWorldRenderer.Draw draw, CgVfxLayer layer, double ox, double oy, double oz) {
        draw.layer(sortLayer(layer)).group(ox, oy, oz).order(layer.order());
        return layer.order() >= CgVfxLayer.ORDER_SURFACE ? draw.afterDistortion() : draw;
    }

    CgMesh ribbonMesh() {
        return ribbonMesh;
    }

    CgMesh quadMesh() {
        return quadMesh;
    }

    CgMaterial material(CgVfxLayer layer) {
        CgMaterial material = materials.get(layer);
        if (material == null) {
            material = CgMaterial.newInstance(layer.shader());
            if (layer.properties() != null) material.applyProperties(layer.properties());
            materials.put(layer, material);
            materialKeys.put(layer, materialKeys.size() & 0xFFFF);
            unbound.add(material);
        }
        return material;
    }

    /** {@code layer}'s material's batch key: draws given one sort together ({@code CgWorldRenderer.Draw.batchKey}). */
    int materialKey(CgVfxLayer layer) {
        material(layer);
        return materialKeys.get(layer);
    }

    /** Points every material at the path texture: once each, and again if the texture was made anew. */
    private void bindPaths() {
        CgTexture2D texture = paths.texture();
        if (texture == null) return;
        if (texture != boundTexture) {
            boundTexture = texture;
            unbound.clear();
            unbound.addAll(materials.values());
        }
        for (int i = 0; i < unbound.size(); i++) {
            unbound.get(i).applyProperties(b -> b.sampler(PATH_SAMPLER, 0, texture));
        }
        unbound.clear();
    }
}
