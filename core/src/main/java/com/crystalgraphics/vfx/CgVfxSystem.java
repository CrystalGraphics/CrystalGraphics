package com.crystalgraphics.vfx;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshLods;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.gl.buffer.shader.CgParticleBuffer;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.platform.CgPlatform;
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

import java.util.ArrayList;
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

    private static Simulation simulation =
            "cpu".equalsIgnoreCase(System.getProperty("crystalgraphics.vfx.sim")) ? Simulation.CPU : Simulation.GPU;

    /** The simulation chosen for every system: what a HUD shows, built or not. */
    public static Simulation simulation() {
        return simulation;
    }

    /** Chooses where every system simulates, from its next update. */
    public static void simulation(Simulation chosen) {
        simulation = chosen;
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
        return particleStep;
    }

    /** Sets {@link #particleStep()} for every system, from its next update; at least 1. */
    public static void particleStep(int ticks) {
        particleStep = Math.max(1, ticks);
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

    /** Sets {@link #skipped()} for every system, from its next submit. */
    public static void skip(String... parts) {
        skipped = parts.clone();
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
    private static int particleStep = Math.max(1, Integer.getInteger("crystalgraphics.vfx.particleStep", 2));
    private static volatile String[] skipped = System.getProperty("crystalgraphics.vfx.skip", "").isEmpty()
            ? new String[0] : System.getProperty("crystalgraphics.vfx.skip").split(",");
    /** Fewer records than this are written on the render thread alone. */
    private static final int PARALLEL_RECORDS = 4096;
    private static final int MAX_TICKS = 12;
    /** GPU steps a pool may hold unrecorded, half a second at 60 Hz, before every effect waits for the world to draw. */
    private static final int MAX_QUEUED_STEPS = 30;
    /** Wall time an update may spend catching up before it drops what it still owes. */
    private static final long SIM_BUDGET_NANOS =
            (long) (Double.parseDouble(System.getProperty("crystalgraphics.vfx.simBudgetMs", "12")) * 1_000_000L);
    private static final String PATH_SAMPLER = "_FxPath";

    // Measurement switches (docs/DEBUG_FLAGS.md): each is what one saving on the beams' GPU time would be worth.
    /** Volume layers on a 12 x 24 sphere instead of 48 x 96. */
    private static final boolean COARSE_VOLUMES = Boolean.getBoolean("crystalgraphics.vfx.coarseVolumes");
    /** Distortion layers after every effect's draws, so they share one copy of the target. */
    private static final boolean SHARED_DISTORTION = Boolean.getBoolean("crystalgraphics.vfx.sharedDistortion");
    private static final CgSortLayer DISTORTION = CgSortLayer.after("crystalgraphics:vfx-distortion", CgSortLayer.EFFECTS);

    private final List<CgVfxEffect> effects = new ArrayList<>();
    private final List<CgVfxMomentListener> momentListeners = new ArrayList<>();
    private final List<CgVfxEventListener> eventListeners = new ArrayList<>();
    private final CgVfxPathTexture paths = new CgVfxPathTexture();
    private final CgVfxTube tube = new CgVfxTube();
    private final CgVfxFrame frame = new CgVfxFrame(this);
    private final IdentityHashMap<CgVfxLayer, CgMaterial> materials = new IdentityHashMap<>();
    private final List<CgMaterial> unbound = new ArrayList<>();
    /** Materials compiling ahead of their first draw, so a layer that appears late does not stall its frame. */
    private final List<CgMaterial> warming = new ArrayList<>();
    private CgTexture2D boundTexture;
    // The tube is this system's own; the ribbons, sphere and quads are shared.
    private CgMesh tubeMesh, ribbonMesh, sphereMesh, quadMesh, volumeMesh;
    /** The emitters drawn this frame through the particle buffer, in the order their records go into it. */
    private final List<CgVfxEmitterInstance> particleEmitters = new ArrayList<>();
    private int particleRecords;
    private final CgVfxAir air = new CgVfxAir();
    private final CgVfxWorkers workers = new CgVfxWorkers();
    /** The effects that queued emitter ticks this tick: what the workers run. */
    private final List<CgVfxEffect> emitting = new ArrayList<>();
    private final CgVfxWorkers.Job tickEach = i -> {
        try (CgTrace.Zone ignored = CgTrace.zone(CgVfxTrace.CHANNEL, EFFECT_EMITTERS_ZONE)) {
            emitting.get(i).tickEmitters();
        }
    };
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
        effect.system = this;
        effects.add(effect);
        return effect;
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
        stepsOnGpu = simulation == Simulation.GPU;
        if (Double.isNaN(clock)) {
            clock = seconds;
            return;
        }
        owed = Math.max(0f, owed + (float) (seconds - clock) * pace(world));
        clock = seconds;
        // WORLD_OPAQUE records the GPU's steps. While it does not fire, every effect holds, the CPU's included, as
        // Niagara and VFX Graph pause what is not drawn: dropped steps would leave particles alive in a slot the CPU
        // closes as finished.
        if (gpuSteps.queuedSteps() >= MAX_QUEUED_STEPS) {
            owed = 0f;
            CgVfxTrace.count(HELD, 1);
            return;
        }
        int ticks = 0, step = particleStep;
        long start = System.nanoTime();
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
                    for (int i = 0; i < effects.size(); i++) {
                        CgVfxEffect effect = effects.get(i);
                        if (effect.state() != CgVfxEffect.State.DEAD) {
                            try (CgTrace.Zone stepping = CgTrace.zone(CgVfxTrace.CHANNEL, EFFECT_TICK_ZONE)) {
                                effect.step(TICK);
                            }
                        }
                        if (effect.hasEmitterTicks()) emitting.add(effect);
                    }
                    try (CgTrace.Zone run = CgTrace.zone(CgVfxTrace.CHANNEL, EMITTERS_ZONE)) {
                        try (CgTrace.Zone ticking = CgTrace.zone(CgVfxTrace.CHANNEL, WORKERS_ZONE)) {
                            workers.run(emitting.size(), tickEach);
                        }
                        try (CgTrace.Zone admitting = CgTrace.zone(CgVfxTrace.CHANNEL, ADMIT_ZONE)) {
                            for (int i = 0; i < emitting.size(); i++) {
                                emitting.get(i).admitScheduled(gpuSteps);
                                emitting.get(i).deliverRows(eventListeners);
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
                overBudget = System.nanoTime() - start >= SIM_BUDGET_NANOS;
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
        for (int i = effects.size() - 1; i >= 0; i--) {
            if (effects.get(i).state() == CgVfxEffect.State.DEAD) effects.remove(i);
        }
    }

    /**
     * Whether an emitter instance starting this update is stepped on the GPU: read once an update, so the workers see
     * one answer. An instance already stepping keeps its path.
     */
    boolean stepsOnGpu() {
        return stepsOnGpu;
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

    /** Draws every playing effect into {@code world}, interpolated between the last two ticks. Render thread. */
    public void submit(CgWorldRenderer world) {
        if (effects.isEmpty()) return;
        if (tubeMesh == null) {
            tubeMesh = CgVfxTube.mesh();
            ribbonMesh = CgVfxRibbons.mesh();
            sphereMesh = CgMeshShapes.sphere(48, 96);
            // 12 x 24 faces come within cos(15°) cos(7.5°) of the centre: at radius 1.05 they still enclose the unit sphere.
            volumeMesh = COARSE_VOLUMES
                    ? CgMesh.build(CgVertexFormat.SPATIAL, m -> CgMeshShapes.sphere(m, 12, 24, 1.05f)) : sphereMesh;
            quadMesh = CgVfxQuads.mesh();
        }
        try (CgTrace.Zone ignored = CgTrace.zone(CgVfxTrace.CHANNEL, SUBMIT_ZONE)) {
            try (CgTrace.Zone warming = CgTrace.zone(CgVfxTrace.CHANNEL, WARM_ZONE)) {
                warm(world);
            }
            float alpha = Math.min(owed / TICK, 1f);
            // Particles hold their last two steps, a step apart: drawn one step behind, as the rest is a tick behind.
            frame.begin(world, alpha, Math.min((sinceParticleTick + alpha) / drawnStep, 1f));
            gpuSteps.frame(frame.particleAlpha(), frame.particleAlpha() * particleDt);
            paths.begin();
            for (int i = 0; i < effects.size(); i++) {
                try (CgTrace.Zone effect = CgTrace.zone(CgVfxTrace.CHANNEL, EFFECT_ZONE)) {
                    effects.get(i).submit(frame);
                }
            }
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
                // Below compute each kernel is several lowered programs; built on the first burst, they stall it.
                effect.gpuPrepared = true;
                if (!rangePrepared) {
                    rangePrepared = true;
                    CgVfxRange.prepare();
                }
                List<CgVfxEmitter> emitters = effect.look().emitters();
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
                    if (readsWorld(emitter)) CgVfxVoxelWindow.get().use();
                }
            }
            if (effect.warmed) continue;
            effect.warmed = true;
            List<CgVfxEmitter> looked = effect.look().emitters();
            for (int k = 0; k < looked.size(); k++) {
                // The particle sphere's levels are built on first ask: here, not in the first burst's frame.
                if (looked.get(k).renderer() == CgVfxEmitter.Renderer.MESHES) particleSphere();
            }
            List<CgVfxLayer> layers = effect.look().layers();
            for (int k = 0; k < layers.size(); k++) {
                CgMaterial material = material(layers.get(k));
                if (!warming.contains(material)) warming.add(material);
            }
        }
        // Every pass the world draws a material with (depth, emissive, distortion, the joined form), not Forward alone.
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
        for (int i = 0; i < eventListeners.size(); i++) CgVfxParticlePool.stopListening(eventListeners.get(i));
        eventListeners.clear();
        gpuSteps.clear();
        paths.delete();
        if (tubeMesh != null) tubeMesh.release();
        if (volumeMesh != null && volumeMesh != sphereMesh) volumeMesh.release();
        tubeMesh = null;
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
        return SHARED_DISTORTION && layer.order() == CgVfxLayer.ORDER_DISTORTION ? DISTORTION : CgSortLayer.EFFECTS;
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
            unbound.add(material);
        }
        return material;
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
