package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.vfx.look.CgVfxSlot;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWorldInput;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWords;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;
import com.crystalgraphics.vfx.particle.spawn.CgVfxSpawnBurst;
import com.crystalgraphics.vfx.particle.spawn.CgVfxSpawnPerUnit;
import com.crystalgraphics.vfx.particle.spawn.CgVfxSpawnRate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What one kind of particle is and how it behaves: when and how many spawn, how each starts, the {@link CgVfxModule}
 * stack its physics is built from, and what changes over its life. Immutable and shared: every effect playing it makes
 * its own {@link CgVfxEmitterInstance}. Units are blocks and seconds. The analogue of a Niagara emitter.
 *
 * <pre>{@code
 * static final CgVfxEmitter EMBERS = CgVfxEmitter.builder("embers")
 *         .state(CgVfxEmitterState.ONCE).capacity(400).burst(0f, 360)       // once: unset, it loops for ever
 *         .shape(CgSphere.of(0.5)).launch(-0.1f, 1f, 0.6f).speed(6f, 18f)   // up-biased, 6..18 blocks a second
 *         .life(4f, 7f).size(0.05f, 0.25f, 2f).heat(1f)
 *         .module(new CgVfxModule.Gravity(9.8f))
 *         .module(new CgVfxModule.Drag(4f, 0f))
 *         .module(new CgVfxModule.Buoyancy(16f, 0.9f))
 *         .module(new CgVfxModule.Turbulence(8f, 0.12f, 0.6f))
 *         .module(new CgVfxModule.Wind(1f))
 *         .opacity(CgKeyframes.start(0f, 0f).to(0.05f, 1f, CgEasings.LINEAR).to(1f, 0f, CgEasings.OUT_QUAD).build())
 *         .build();
 *
 * // a variant that changes only what differs
 * CgVfxEmitter sparse = EMBERS.toBuilder().capacity(120).burst(0f, 100).build();
 * }</pre>
 *
 * Where particles are born and which way they fly:
 *
 * <pre>{@code
 * CgVfxEmitter.builder("spray").shape(CgCone.of(3, Math.toRadians(25))).speed(4f, 8f)   // a muzzle's spray
 * CgVfxEmitter.builder("ring").shape(CgRing.of(2.8, 3, 0)).edge().ordered(32)            // 32 evenly round a ring
 * CgVfxEmitter.builder("dome").shape(CgSphere.of(2)).surface().hemisphere()              // on a dome, outward
 * CgVfxEmitter.builder("sheet").shape(CgBox.of(2, 0.05, 2)).direction(0, 1, 0, 10f)      // up, within 10 degrees
 * CgVfxEmitter.builder("wake").capacity(600).ratePerBlock(6f).inherit(0.4f)              // six a block the source moves
 * CgVfxEmitter.builder("glow").mesh("mymod:models/sword.obj")                            // off a sword's surface
 * CgVfxEmitter.builder("aura").entity().surface().local()                                // off whoever its effect follows
 * }</pre>
 *
 * Its colour and its size on each axis, multiplying what its layers draw:
 *
 * <pre>{@code
 * CgVfxEmitter.builder("sparks").color(0xFFFFD27A, 0xFFFF5A1F)                           // gold to red-orange
 *         .intensityOverLife(CgKeyframes.start(0f, 4f).to(0.2f, 1f, CgEasings.OUT_QUAD).build())
 * CgVfxEmitter.builder("streak").renderer(MESHES).size(0.4f, 0.4f).scale(1f, 1f, 6f)     // a mesh six times as long
 * }</pre>
 *
 * <ul>
 *   <li>{@link Builder#capacity} bounds the live particles; a spawn into a full set is dropped.</li>
 *   <li>A source that moves within a step spreads that step's rate along its path, each particle stepped for the part of
 *       the step after its birth: a fast emitter leaves an even line, never clumps a step apart.</li>
 *   <li>The player's density setting thins every emitter; one marked {@link Builder#optional} spawns half again at the
 *       Low quality tier.</li>
 *   <li>Particles live in the world: an effect that moves ({@code follow}) spawns them where it is now, and those already
 *       spawned stay where they are. One marked {@link Builder#local} rides the effect instead, particles and all.</li>
 * </ul>
 */
public final class CgVfxEmitter implements CgVfxGpuEmitter {

    /** How a renderer draws the particles of this emitter. */
    public enum Renderer {
        /** A camera-facing quad per particle, stretched along its motion if the layer asks: specks, sparkles. */
        QUADS,
        /** A mesh per particle, each its own sorted draw: billows. */
        MESHES,
        /** A stroke per particle, an arc round the source through the particle: ink streaks. */
        ARCS
    }

    final String name;
    private final CgVfxSlot slot;
    final Renderer renderer;
    final boolean optional, local;
    final CgVfxEmitterState state;
    final int capacity;
    /** Its bursts, by time. */
    final CgVfxSpawnBurst[] bursts;
    final CgVfxSpawnRate rate;
    final CgVfxSpawnPerUnit perUnit;
    final float sweep, upMin, upMax, upBias, speedMin, speedMax, lifeMin, lifeMax;
    final CgVfxSpawnShape spawn;
    /** The most blocks a step may move and still spread its spawns and give its velocity; its velocity's share. */
    final float maxStep, inherit;
    /** Each particle's last positions its pool keeps for a {@code trails()} layer; 0 for none. */
    final int trailPoints;
    /** The density each particle draws into its grid's four channels each frame; null for none. */
    final float[] rasterized;
    /** The sweep as spawn k's front, {@code frontA k - frontB k^2} blocks, k held at its peak: what both paths read. */
    final float frontA, frontB;
    final float sizeMin, sizeMax, sizeSkew, spinMin, spinMax, heat;
    final List<CgVfxModule> modules;
    final List<CgVfxEvent> events;
    final CgKeyframes sizeOverLife, opacityOverLife;
    /** Each axis's scale over life, then the colour's channels and intensity over life. */
    final CgKeyframes scaleX, scaleY, scaleZ, red, green, blue, intensity;
    /** Its start colour's two ends (rgb times intensity, alpha) and its start scale's two ends (xyz). */
    final float[] colorFrom, colorTo, scaleFrom, scaleTo;
    /** Its largest axis times its size curve's peak at the curve row's samples: what the GPU path draws at most. */
    private final float sizePeak;
    private final boolean feedsGas, splatsGrid;
    /** {@link #scaled} copies, one per scale asked for. */
    private final Map<Float, CgVfxEmitter> scaledCopies = new ConcurrentHashMap<>();
    private static final CgKeyframes ONE = CgKeyframes.constant(1f);
    private static final float[] WHITE = {1f, 1f, 1f, 1f}, UNIT = {1f, 1f, 1f};

    private CgVfxEmitter(Builder b) {
        name = b.name;
        slot = CgVfxSlot.particles(b.name);
        renderer = b.renderer;
        optional = b.optional;
        local = b.local;
        state = b.state;
        capacity = b.capacity;
        bursts = b.bursts.toArray(new CgVfxSpawnBurst[0]);
        // Stable: two at one time fire in the order given.
        Arrays.sort(bursts, (x, y) -> Float.compare(x.time(), y.time()));
        rate = b.rate;
        spawn = b.spawn.build(b.name);
        perUnit = b.perUnit;
        maxStep = b.maxStep;
        inherit = b.inherit;
        trailPoints = b.trailPoints;
        rasterized = b.rasterized != null ? b.rasterized.clone() : null;
        sweep = b.sweep;
        // Spawn k is born k / rate into its one loop of T: the front, sweep (t - t^2 / 2T), in k.
        float span = b.state.durationMax(), perSecond = b.rate.perSecond();
        frontA = sweep != 0f ? sweep / perSecond : 0f;
        frontB = sweep != 0f ? sweep / (2f * span * perSecond * perSecond) : 0f;
        upMin = b.upMin;
        upMax = b.upMax;
        upBias = b.upBias;
        speedMin = b.speedMin;
        speedMax = b.speedMax;
        lifeMin = b.lifeMin;
        lifeMax = b.lifeMax;
        sizeMin = b.sizeMin;
        sizeMax = b.sizeMax;
        sizeSkew = b.sizeSkew;
        spinMin = b.spinMin;
        spinMax = b.spinMax;
        heat = b.heat;
        modules = Collections.unmodifiableList(new ArrayList<>(b.modules));
        boolean feeds = false, splats = false;
        for (CgVfxModule m : modules) {
            feeds |= Arrays.asList(m.worldInputs()).contains(CgVfxWorldInput.GAS_FEED);
            splats |= Arrays.asList(m.worldInputs()).contains(CgVfxWorldInput.GRID_SPLAT);
        }
        feedsGas = feeds;
        splatsGrid = splats;
        events = Collections.unmodifiableList(new ArrayList<>(b.events));
        sizeOverLife = b.sizeOverLife;
        opacityOverLife = b.opacityOverLife;
        scaleX = b.scaleX;
        scaleY = b.scaleY;
        scaleZ = b.scaleZ;
        red = b.red;
        green = b.green;
        blue = b.blue;
        intensity = b.intensity;
        colorFrom = b.colorFrom;
        colorTo = b.colorTo;
        scaleFrom = b.scaleFrom;
        scaleTo = b.scaleTo;
        float start = 0f;
        for (int a = 0; a < 3; a++) start = Math.max(start, Math.max(Math.abs(scaleFrom[a]), Math.abs(scaleTo[a])));
        float peak = 0f;
        for (int i = 0; i < CgVfxGpuEmitter.CURVE_TEXELS; i++) {
            float p = (float) i / (CgVfxGpuEmitter.CURVE_TEXELS - 1);
            float axis = Math.max(Math.abs(scaleX.at(p)), Math.max(Math.abs(scaleY.at(p)), Math.abs(scaleZ.at(p))));
            peak = Math.max(peak, sizeAt(p) * axis);
        }
        sizePeak = peak * start;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * This emitter at {@code scale} times its size, on the same clock: where particles start, how fast they fly, how big
     * they are, how far apart a rate per block spaces them, and each module's lengths and forces
     * ({@link CgVfxModule#scaled}), so the same burst plays smaller or larger. Event children scale with it. One copy per
     * scale, kept: an effect may ask at every start.
     *
     * <pre>{@code
     * // a kit authored for a 9.6-block blast, at 3.6 blocks
     * CgVfxEmitterInstance billows = new CgVfxEmitterInstance(CgVfxExplosion.BILLOWS.scaled(3.6f / 9.6f), seed);
     * }</pre>
     *
     * The copy keeps the name, so a look still finds it by name.
     */
    public CgVfxEmitter scaled(float scale) {
        if (scale == 1f) return this;
        return scaledCopies.computeIfAbsent(scale, s -> {
            Builder b = toBuilder();
            b.spawn = new CgVfxSpawnShape.Builder(spawn.scaled(s, name));
            b.perUnit = perUnit.scaled(s);
            b.maxStep *= s;
            b.sweep *= s;
            b.speedMin *= s;
            b.speedMax *= s;
            b.sizeMin *= s;
            b.sizeMax *= s;
            b.modules.replaceAll(m -> m.scaled(s));
            b.events.replaceAll(e -> e.child() == null ? e : e.spawn(((CgVfxEmitter) e.child()).scaled(s), e.count()));
            return b.build();
        });
    }

    public String name() {
        return name;
    }

    /** The slot a look's layers draw these particles on: {@code CgVfxSlot.particles(name())}. */
    public CgVfxSlot slot() {
        return slot;
    }

    public Renderer renderer() {
        return renderer;
    }

    public int capacity() {
        return capacity;
    }

    /** Whether the Low quality tier halves it ({@link Builder#optional}). */
    public boolean optional() {
        return optional;
    }

    /** Whether its particles ride the effect rather than stay in the world ({@link Builder#local}). */
    public boolean local() {
        return local;
    }

    /** Its life cycle: loops, their duration and delay, and its inactive response ({@link Builder#state}). */
    public CgVfxEmitterState state() {
        return state;
    }

    public CgVfxSpawnRate rate() {
        return rate;
    }

    /** Its bursts, by time. A copy. */
    public CgVfxSpawnBurst[] bursts() {
        return bursts.clone();
    }

    public CgVfxSpawnPerUnit perUnit() {
        return perUnit;
    }

    @Override
    public List<CgVfxModule> modules() {
        return modules;
    }

    @Override
    public List<CgVfxEvent> events() {
        return events;
    }

    @Override
    public int trailPoints() {
        return trailPoints;
    }

    @Override
    public float[] rasterized() {
        return rasterized;
    }

    @Override
    public boolean spawnsOnMesh() {
        return spawn.kind() == CgVfxSpawnShape.MESH;
    }

    /** Whether a module of it pours into its instance's gas ({@link CgVfxModule.Feed}). */
    public boolean feedsGas() {
        return feedsGas;
    }

    /** Whether a module of it splats into its instance's grid ({@link CgVfxModule.Splat}). */
    public boolean splatsGrid() {
        return splatsGrid;
    }

    @Override
    public void writeSpawn(CgVfxWords out) {
        out.vec4(speedMin, speedMax, lifeMin, lifeMax)
           .vec4(sizeMin, sizeMax, sizeSkew, heat)
           .vec4(spinMin, spinMax, inherit, 0f)
           .vec4(frontA, frontB, 0f, 0f);
        spawn.write(out, upMin, upMax, upBias);
    }

    /** How far out spawn {@code k}'s front has swept, in blocks: 0 without {@link Builder#sweep}. */
    float front(int k) {
        if (frontB == 0f) return 0f;
        float held = Math.min(k, frontA / (2f * frontB));
        return frontA * held - frontB * held * held;
    }

    @Override
    public void writeCurves(float[] out, int at, int texels) {
        int tint = at + 4 * texels, starts = at + 8 * texels;
        for (int i = 0; i < texels; i++) {
            float progress = texels > 1 ? (float) i / (texels - 1) : 0f, k = intensity.at(progress);
            out[at + 4 * i] = scaleX.at(progress);
            out[at + 4 * i + 1] = scaleY.at(progress);
            out[at + 4 * i + 2] = scaleZ.at(progress);
            out[at + 4 * i + 3] = sizeAt(progress);
            out[tint + 4 * i] = red.at(progress) * k;
            out[tint + 4 * i + 1] = green.at(progress) * k;
            out[tint + 4 * i + 2] = blue.at(progress) * k;
            out[tint + 4 * i + 3] = opacityAt(progress);
        }
        System.arraycopy(colorFrom, 0, out, starts, 4);
        System.arraycopy(colorTo, 0, out, starts + 4, 4);
        System.arraycopy(scaleFrom, 0, out, starts + 8, 3);
        out[starts + 11] = 0f;
        System.arraycopy(scaleTo, 0, out, starts + 12, 3);
        out[starts + 15] = 0f;
    }

    /** How much of its starting size a particle is at {@code progress} 0..1 through its life. */
    public float sizeAt(float progress) {
        return sizeOverLife.at(progress);
    }

    /** How opaque a particle is at {@code progress} 0..1 through its life. */
    public float opacityAt(float progress) {
        return opacityOverLife.at(progress);
    }

    /**
     * The most particles one instance can have alive at once, at full share: what a GPU pool sizes its slot by. Every
     * burst within any span of its longest life, plus its rate over that span, and never past its {@link #capacity}, which
     * the instance's books hold it to.
     *
     * <pre>{@code
     * int slot = pool.open(EMBERS, EMBERS.peakAlive());
     * }</pre>
     */
    public int peakAlive() {
        return Math.min(capacity, uncappedPeak());
    }

    /** The most alive at once if nothing held it to its capacity: unbounded for a rate per block. */
    int uncappedPeak() {
        // A particle can outlast its life by its last step: a tenth of a second covers any particle step.
        return perUnit.perBlock() > 0f ? Integer.MAX_VALUE : spawnsWithin(lifeMax + 0.1f);
    }

    /**
     * The most children event {@code event} (its index in {@link #events()}) of one instance can have alive at once:
     * what its child slot is sized by.
     *
     * <pre>{@code
     * int childSlot = childPool.open(DUST, DEBRIS.peakChildren(0));
     * }</pre>
     */
    public int peakChildren(int event) {
        CgVfxEvent e = events.get(event);
        CgVfxEmitter child = (CgVfxEmitter) e.child();
        if (child == null) return 0;
        if (e.repeats()) {
            // Each parent that can have fired within a child's life fires at most this often in it: a rate by its period,
            // a collision at most once a 60 Hz step, and neither past its firings. An upper bound; never drops a child.
            float within = child.lifeMax + 0.1f;
            double often = Math.ceil(e.trigger() == CgVfxEvent.Trigger.RATE ? within / e.age() : within * 60f);
            int per = (int) Math.min(e.firings(), often);
            return spawnsWithin(lifeMax + within) * per * e.count();
        }
        // A parent fires once, this long after its birth at most; its children then live up to the child's longest life.
        float spread = switch (e.trigger()) {
            case AGE -> 0f;
            case DEATH -> lifeMax - lifeMin;
            case LANDING, COLLISION, RATE -> lifeMax;
        };
        return spawnsWithin(spread + child.lifeMax + 0.1f) * e.count();
    }

    /** The most spawn candidates within any {@code span} seconds. */
    private int spawnsWithin(float span) {
        float duration = state.durationMax();
        int loops = state.loops();
        long fromBursts = 0;
        if (loops == 1) {
            // One loop: the most its bursts put inside any window of the span.
            for (CgVfxSpawnBurst from : bursts) {
                long alive = 0;
                for (CgVfxSpawnBurst b : bursts) {
                    if (b.time() >= from.time() && b.time() < from.time() + span && b.time() <= duration) alive += b.countMax();
                }
                fromBursts = Math.max(fromBursts, alive);
            }
        } else {
            long perLoop = 0;
            for (CgVfxSpawnBurst b : bursts) if (b.time() <= duration) perLoop += b.countMax();
            // Every loop that can begin within the span, the one under way at its start too.
            fromBursts = perLoop * Math.min(loops, (long) Math.ceil(span / state.shortestPeriod()) + 1);
        }
        float active = loops == Integer.MAX_VALUE ? Float.POSITIVE_INFINITY : duration * loops;
        float peak = rate.peak(duration);
        long fromRate = peak > 0f ? (long) Math.ceil(peak * Math.min(span, active)) + 2 : 0;
        return (int) Math.min(Integer.MAX_VALUE, fromBursts + fromRate);
    }

    /**
     * How far from its source one of its particles can get within its life, in blocks: its spawn radius, its launch
     * speed, and what its modules can add pulling one way the whole time, {@code windSpeed} being the most the system's
     * wind blows ({@link CgVfxAir#maxSpeed()}). What a look drawn about the source, an arc, is culled by.
     *
     * <pre>{@code
     * float reach = SPARKS.reach(system.air().maxSpeed()) + SPARKS.largestSize() * stretch;
     * }</pre>
     */
    public float reach(float windSpeed) {
        float pull = 0f;
        boolean wind = false;
        for (int i = 0; i < modules.size(); i++) {
            pull += modules.get(i).pull(heat);
            wind |= modules.get(i) instanceof CgVfxModule.Wind;
        }
        float swept = sweep != 0f ? 0.5f * Math.abs(sweep) * state.durationMax() : 0f;
        return spawn.extent() + swept + speedMax * lifeMax + 0.5f * pull * lifeMax * lifeMax + (wind ? windSpeed * lifeMax : 0f);
    }

    /** The largest a particle draws, before a look's own scale: its largest size times its size curve's peak. */
    public float largestSize() {
        return sizeMax * sizePeak;
    }

    /** Whether its loops spawn by time or distance, not by bursts alone. */
    boolean spawnsContinuously() {
        return rate.perSecond() > 0f || perUnit.perBlock() > 0f;
    }

    public static final class Builder {

        private String name;
        private Renderer renderer = Renderer.QUADS;
        private boolean optional, local;
        private CgVfxEmitterState state = CgVfxEmitterState.DEFAULT;
        private int capacity = 256;
        private final List<CgVfxSpawnBurst> bursts = new ArrayList<>();
        private CgVfxSpawnRate rate = CgVfxSpawnRate.NONE;
        private CgVfxSpawnPerUnit perUnit = CgVfxSpawnPerUnit.NONE;
        private CgVfxSpawnShape.Builder spawn = new CgVfxSpawnShape.Builder();
        private float sweep, inherit, maxStep = 8f;
        private int trailPoints;
        private float[] rasterized;
        private float upMin = -1f, upMax = 1f, upBias = 1f;
        private float speedMin, speedMax;
        private float lifeMin = 1f, lifeMax = 1f;
        private float sizeMin = 1f, sizeMax = 1f, sizeSkew = 1f;
        private float spinMin, spinMax;
        private float heat;
        private final List<CgVfxModule> modules = new ArrayList<>();
        private final List<CgVfxEvent> events = new ArrayList<>();
        private CgKeyframes sizeOverLife = CgKeyframes.constant(1f);
        private CgKeyframes opacityOverLife = CgKeyframes.start(0f, 1f).to(0.8f, 1f, CgEasings.LINEAR)
                .to(1f, 0f, CgEasings.OUT_QUAD).build();
        private CgKeyframes scaleX = ONE, scaleY = ONE, scaleZ = ONE, red = ONE, green = ONE, blue = ONE, intensity = ONE;
        private float[] colorFrom = WHITE, colorTo = WHITE, scaleFrom = UNIT, scaleTo = UNIT;

        private Builder(String name) {
            this.name = name;
        }

        private Builder(CgVfxEmitter e) {
            name = e.name;
            renderer = e.renderer;
            optional = e.optional;
            local = e.local;
            state = e.state;
            capacity = e.capacity;
            bursts.addAll(Arrays.asList(e.bursts));
            rate = e.rate;
            spawn = new CgVfxSpawnShape.Builder(e.spawn);
            perUnit = e.perUnit;
            maxStep = e.maxStep;
            inherit = e.inherit;
            trailPoints = e.trailPoints;
            rasterized = e.rasterized;
            sweep = e.sweep;
            upMin = e.upMin;
            upMax = e.upMax;
            upBias = e.upBias;
            speedMin = e.speedMin;
            speedMax = e.speedMax;
            lifeMin = e.lifeMin;
            lifeMax = e.lifeMax;
            sizeMin = e.sizeMin;
            sizeMax = e.sizeMax;
            sizeSkew = e.sizeSkew;
            spinMin = e.spinMin;
            spinMax = e.spinMax;
            heat = e.heat;
            modules.addAll(e.modules);
            events.addAll(e.events);
            sizeOverLife = e.sizeOverLife;
            opacityOverLife = e.opacityOverLife;
            scaleX = e.scaleX;
            scaleY = e.scaleY;
            scaleZ = e.scaleZ;
            red = e.red;
            green = e.green;
            blue = e.blue;
            intensity = e.intensity;
            colorFrom = e.colorFrom;
            colorTo = e.colorTo;
            scaleFrom = e.scaleFrom;
            scaleTo = e.scaleTo;
        }

        /** Renames it: a look holds one emitter per name, and replaces by it. */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder renderer(Renderer renderer) {
            this.renderer = renderer;
            return this;
        }

        /** Detail the effect reads as whole without: the Low quality tier spawns half of it. */
        public Builder optional() {
            this.optional = true;
            return this;
        }

        /**
         * Rides the effect: when it moves ({@code follow}), its particles move with it, as an aura's sparkles orbit the
         * player. Unmarked, they stay in the world where they spawned, as sparks a moving hand throws off. Unity's
         * simulation space and Niagara's Local Space; an event's children take their parent's.
         *
         * <pre>{@code
         * CgVfxEmitter.builder("auraSparkles").local().rate(40f).shape(CgShell.of(0.6f, 0.9f))...
         * }</pre>
         */
        public Builder local() {
            this.local = true;
            return this;
        }

        /**
         * Its life cycle: how long a loop lasts, how many run, the wait before each, and what its live particles do once
         * it stops. Niagara's Emitter State. {@link CgVfxEmitterState#DEFAULT} unless given: loops for ever, so an
         * emitter that bursts once says {@link CgVfxEmitterState#ONCE}.
         *
         * <pre>{@code
         * CgVfxEmitter.builder("sparks").state(CgVfxEmitterState.ONCE).burst(0f, 120)...
         * CgVfxEmitter.builder("smoke").state(CgVfxEmitterState.once(1.6f)).rate(400f)...       // 1.6 s of it
         * CgVfxEmitter.builder("core").state(CgVfxEmitterState.infinite(2.9f)).burst(0f, 1)...  // renewed every 2.9 s
         * }</pre>
         */
        public Builder state(CgVfxEmitterState state) {
            this.state = Objects.requireNonNull(state, "state");
            return this;
        }

        /**
         * Live particles at most, 256 unless given: a spawn that would pass it is dropped. The slot it plays in is sized by
         * the least of this and what its bursts and rate can have alive ({@link CgVfxEmitter#peakAlive}).
         */
        public Builder capacity(int capacity) {
            this.capacity = capacity;
            return this;
        }

        /**
         * {@code count} particles at once, {@code time} seconds into each loop ({@link CgVfxSpawnBurst}). Unless its
         * {@link #state} is once, it fires again every loop.
         */
        public Builder burst(float time, int count) {
            return burst(CgVfxSpawnBurst.at(time, count));
        }

        /**
         * A burst: a count range rolled each loop, a probability.
         *
         * <pre>{@code
         * .burst(CgVfxSpawnBurst.at(0.25f, 8, 16).probability(0.5f))
         * }</pre>
         */
        public Builder burst(CgVfxSpawnBurst burst) {
            bursts.add(Objects.requireNonNull(burst, "burst"));
            return this;
        }

        /**
         * {@code perSecond} particles a second for as long as each loop runs ({@link CgVfxSpawnRate}): an aura, a fire.
         * Under the default {@link #state} that is until the instance is {@link CgVfxEmitterInstance#stop stopped}; give
         * it a {@link #capacity}.
         *
         * <pre>{@code
         * CgVfxEmitter.builder("embers").capacity(400).rate(60f)...                                  // until stopped
         * CgVfxEmitter.builder("smoke").state(CgVfxEmitterState.once(1.6f)).rate(400f)...           // for 1.6 s
         * }</pre>
         */
        public Builder rate(float perSecond) {
            return rate(CgVfxSpawnRate.of(perSecond));
        }

        /**
         * {@code perSecond} particles a second times {@code scale} at each second of the loop: a rate that swells and
         * thins, or one that starts part-way through (a step: two keys at one time).
         *
         * <pre>{@code
         * .rate(300f, CgKeyframes.start(0.15f, 0f).to(0.15f, 1f, CgEasings.LINEAR).build())   // from 0.15 s in
         * }</pre>
         */
        public Builder rate(float perSecond, CgKeyframes scale) {
            return rate(new CgVfxSpawnRate(perSecond, scale));
        }

        public Builder rate(CgVfxSpawnRate rate) {
            this.rate = Objects.requireNonNull(rate, "rate");
            return this;
        }

        /**
         * Where particles are born: filling {@code shape}, about the source. A point unless given.
         *
         * <pre>{@code
         * .shape(CgSphere.of(1.2))                     // anywhere in a ball
         * .shape(CgShell.of(9, 11))                    // between two spheres: a cloud rolling out from an edge
         * .shape(CgCone.of(3, Math.toRadians(25)))     // a spray along +Z; pointing(...) turns it
         * .shape(CgRing.of(2.8, 3, 0)).edge()          // on a circle on the ground
         * .shape(CgCapsule.between(0, 0, 0, 0, 0, 12, 0.3)).edge()   // along a line, 12 blocks forward
         * }</pre>
         *
         * <table>
         *   <tr><th>Shape</th><th>Its axis</th><th>{@link #edge}</th></tr>
         *   <tr><td>{@code CgSphere}, {@code CgShell}</td><td>+Y</td><td>its equator</td></tr>
         *   <tr><td>{@code CgBox}</td><td>+Y</td><td>its twelve edges</td></tr>
         *   <tr><td>{@code CgCylinder}, {@code CgRing}</td><td>+Y</td><td>its outer circles</td></tr>
         *   <tr><td>{@code CgCone}</td><td>+Z, from its apex</td><td>its rim</td></tr>
         *   <tr><td>{@code CgCapsule}</td><td>along its segment</td><td>the segment</td></tr>
         * </table>
         *
         * <ul>
         *   <li>Uniform throughout: a ball fills by volume, a surface by area, an edge by length.</li>
         *   <li>A shell's or ring's front, a plane, a compound or a hull throws: none has an inside to be born in.</li>
         * </ul>
         */
        public Builder shape(CgShape shape) {
            this.spawn.shape(shape, name);
            return this;
        }

        /**
         * Born on {@code mesh}'s surface, by area, and launched along its normal there unless told otherwise: a blade
         * glowing, a rune burning off a model. Turned from its +Y by {@link #pointing} and moved by {@link #at}, as a
         * shape is.
         *
         * <pre>{@code
         * .mesh(SWORD).rate(80f).speed(0.2f, 0.6f)                                  // glowing off the blade
         * .mesh(CgProceduralMesh.revolve(CgProceduralMesh.sphere(2f, 0f, 2f)).build())   // a dome of your own making
         * }</pre>
         *
         * <ul>
         *   <li>Read once, at its first spawn: an edit after that is not seen. Triangles, float positions.</li>
         *   <li>Its surface or {@link #vertices()}: {@link #edge()}, {@link #hemisphere()} and {@link #arc} throw.</li>
         * </ul>
         */
        public Builder mesh(CgMesh mesh) {
            if (mesh == null) throw new IllegalArgumentException(name + ": mesh(null)");
            spawn.mesh(mesh, null);
            return this;
        }

        /** As {@link #mesh(CgMesh)}, an OBJ or glTF file loaded at its first spawn: a server naming it loads nothing. */
        public Builder mesh(String path) {
            if (path == null) throw new IllegalArgumentException(name + ": mesh(null)");
            spawn.mesh(null, path);
            return this;
        }

        /**
         * Born at the {@link #mesh}'s vertices instead of across its surface, each launched along its normal: one at
         * random, or with {@link #ordered} spawn {@code k} at the {@code k mod n}-th of {@code n} spread through them
         * in their order.
         *
         * <pre>{@code
         * .mesh("mymod:models/rune.obj").vertices().ordered(64).burst(0f, 64)   // one at each of 64 of its vertices
         * }</pre>
         */
        public Builder vertices() {
            spawn.placement = CgVfxSpawnShape.VERTICES;
            return this;
        }

        /**
         * Born in the box of the entity its effect follows ({@code CgVfxEffect.follow}), as it stands each step, and
         * launched out of its middle: an aura round whoever casts it, embers off a burning mob. {@link #surface} and
         * {@link #edge} put them on its faces or edges.
         *
         * <pre>{@code
         * CgVfxEmitter.builder("aura").entity().surface().rate(60f).speed(0.1f, 0.4f).local()
         * }</pre>
         *
         * <ul>
         *   <li>An effect following nothing has no box: they are born at the source.</li>
         *   <li>The box is axis aligned where the entity stands: {@link #pointing}, {@link #at}, {@link #arc} and
         *       {@link #ordered} throw.</li>
         * </ul>
         */
        public Builder entity() {
            spawn.entity();
            return this;
        }

        /** Born on the shape's surface, by area, rather than filling it. */
        public Builder surface() {
            spawn.placement = CgVfxSpawnShape.SURFACE;
            return this;
        }

        /** Born on the shape's edge ({@link #shape}'s table), by length: a ring's circle, a capsule's segment. */
        public Builder edge() {
            spawn.placement = CgVfxSpawnShape.EDGE;
            return this;
        }

        /** Only the half of the shape above its middle along its axis: a dome, a half pillar. */
        public Builder hemisphere() {
            spawn.hemisphere = true;
            return this;
        }

        /** Only {@code degrees} of the way round its axis from its +X: a crescent of a ring. Not on a box. */
        public Builder arc(float degrees) {
            if (!(degrees > 0f && degrees <= 360f)) throw new IllegalArgumentException(name + ": an arc of " + degrees);
            spawn.arc = (float) Math.toRadians(degrees);
            return this;
        }

        /**
         * Spawn {@code k} at the {@code k mod n}-th of {@code n} even places round the shape's axis (along a capsule's
         * edge), rather than at random: the evenly spaced rings anime effects are made of (Effekseer's ordered spawning).
         * A partial {@link #arc} puts one at each end. Not on a box.
         *
         * <pre>{@code
         * .shape(CgRing.of(3, 3, 0)).edge().ordered(24).burst(0f, 24)   // 24 evenly round, once
         * }</pre>
         */
        public Builder ordered(int n) {
            if (n < 1) throw new IllegalArgumentException(name + ": ordered(" + n + ")");
            spawn.ordered = n;
            return this;
        }

        /** Turns the shape so its axis ({@link #shape}'s table) points along {@code (x, y, z)}: a cone aimed up, a ring stood on its edge. */
        public Builder pointing(float x, float y, float z) {
            spawn.pointing(x, y, z, name);
            return this;
        }

        /** Moves the shape {@code (x, y, z)} blocks from the source, after it is turned. */
        public Builder at(float x, float y, float z) {
            spawn.atX = x;
            spawn.atY = y;
            spawn.atZ = z;
            return this;
        }

        /** Launched away from the shape's middle: its centre, or a cylinder's or capsule's axis. The default. */
        public Builder outward() {
            spawn.mode = CgVfxSpawnShape.OUTWARD;
            return this;
        }

        /** Launched toward the shape's middle: a gathering, an implosion. */
        public Builder inward() {
            spawn.mode = CgVfxSpawnShape.INWARD;
            return this;
        }

        /**
         * Launched along {@code (x, y, z)}, in the effect's frame, within {@code spreadDegrees} of it, wherever it is born.
         * An event's children take the event's normal as up.
         */
        public Builder direction(float x, float y, float z, float spreadDegrees) {
            spawn.direction(x, y, z, spreadDegrees, name);
            return this;
        }

        /**
         * The shape runs outward at {@code speed} blocks a second from its loop's start, easing to a stop at its end, so
         * particles are born on a front sweeping out: a shock front lifting dust off the ground as it passes. It covers
         * {@code speed} times the loop's duration, halved; a size curve of {@code OUT_QUAD} over it follows it.
         *
         * <pre>{@code
         * CgVfxEmitter.builder("skirt").state(CgVfxEmitterState.once(1.6f)).rate(400f).shape(CgShell.of(9f, 9.5f))
         *         .launch(0f, 0.15f, 1f).sweep(46f)              // 9 blocks out at the start, 46 at 1.6 s
         *         ...
         * }</pre>
         *
         * <ul>
         *   <li>One loop of a finite duration, a constant rate and no burst: {@link #build} throws otherwise.</li>
         *   <li>An event's children spawn where the event fired, never on the front.</li>
         * </ul>
         */
        public Builder sweep(float speed) {
            sweep = speed;
            return this;
        }

        /**
         * Launched at any heading with an upward share from {@code upMin} to {@code upMax}, skewed up by a bias under 1.
         * On a point or a sphere it is also where they are born, along that direction: a ball that only fills its lower
         * half, debris thrown up and out.
         *
         * <pre>{@code
         * .shape(CgSphere.of(3)).launch(0.05f, 1f, 0.7f)    // born and flying in the upper half, mostly up
         * }</pre>
         */
        public Builder launch(float upMin, float upMax, float bias) {
            this.upMin = upMin;
            this.upMax = upMax;
            this.upBias = bias;
            spawn.mode = CgVfxSpawnShape.LAUNCH;
            return this;
        }

        /**
         * {@code perBlock} particles for each block its source moves while each loop runs, beside its {@link #rate}: a
         * wake, a trail of footsteps ({@link CgVfxSpawnPerUnit}). A step moving farther than {@link #maxStep} is a
         * teleport and spawns nothing by distance. How fast its source travels is unbounded, so its {@link #capacity} is
         * what holds it: give it one.
         *
         * <pre>{@code
         * CgVfxEmitter.builder("wake").capacity(600).ratePerBlock(6f).inherit(0.4f)...
         * wake.moveTo(x, y, z);   // each tick: the books count how far it went
         * }</pre>
         */
        public Builder ratePerBlock(float perBlock) {
            this.perUnit = new CgVfxSpawnPerUnit(perBlock);
            return this;
        }

        /** The farthest a source may move in one step and still spawn by distance and give its velocity: 8 blocks unless given. */
        public Builder maxStep(float blocks) {
            this.maxStep = blocks;
            return this;
        }

        /** Each particle starts with {@code share} of its source's velocity, as an event's children take their parent's. */
        public Builder inherit(float share) {
            this.inherit = share;
            return this;
        }

        /**
         * Keeps each particle's last {@code points} positions, one a particle step (60 a second), so a layer with
         * {@code trails()} on it draws a trail behind every particle: sparks streaking along curved paths, embers swirling.
         * 2 to 64; a trail lasts {@code points - 1} steps.
         *
         * <pre>{@code
         * CgVfxEmitter.builder("embers").capacity(400).trail(16)...   // a quarter of a second behind each
         * CgVfxLayer.of("crystalgraphics:shaders/vfx/particle/streaks.shader").on(EMBERS).trails().size(0.04f)
         * }</pre>
         */
        public Builder trail(int points) {
            if (points < 2 || points > 64) throw new IllegalArgumentException("trail(" + points + "): 2 to 64 points");
            this.trailPoints = points;
            return this;
        }

        /**
         * Draws its particles into their instance's grid each frame ({@code CgVfxEmitterInstance.inGrid}): each a soft
         * ball as wide as it is drawn, holding this density at its middle in the grid's four channels, scaled by its
         * opacity over life. The grid's rebuilt channels ({@code CgVfxGrid3D.channel(c)}) hold only this frame's balls, so
         * a volume drawn from them is where the particles are drawn, as a sprite is: Niagara's rasterization grid.
         *
         * <pre>{@code
         * CgVfxEmitter.builder("smoke").capacity(6000).rate(1500f).size(0.6f, 0.6f)...rasterize(0.3f, 0f, 0f, 0f).build();
         * new CgVfxGrid3D(effect).place(0f, 5f, 0f).channel(0);   // channel x rebuilt each frame from them
         * }</pre>
         *
         * <ul>
         *   <li>A particle under a cell wide is drawn a cell wide: give it a size of a few cells.</li>
         *   <li>Into a channel that fades ({@code channel(c, fade, spread)}) it draws nothing: that is {@code Splat}'s.</li>
         * </ul>
         */
        public Builder rasterize(float x, float y, float z, float w) {
            this.rasterized = new float[] {x, y, z, w};
            return this;
        }

        /** Launch speed, blocks a second. */
        public Builder speed(float min, float max) {
            speedMin = min;
            speedMax = max;
            return this;
        }

        /** Seconds a particle lives. */
        public Builder life(float min, float max) {
            lifeMin = min;
            lifeMax = max;
            return this;
        }

        /** Starting size in blocks, evenly between. */
        public Builder size(float min, float max) {
            return size(min, max, 1f);
        }

        /** Starting size in blocks; a {@code skew} over 1 makes most small and a few large. */
        public Builder size(float min, float max, float skew) {
            sizeMin = min;
            sizeMax = max;
            sizeSkew = skew;
            return this;
        }

        /** Spin rate in radians a second, either way. */
        public Builder spin(float min, float max) {
            spinMin = min;
            spinMax = max;
            return this;
        }

        /** Heat at birth, 0..1, which {@link CgVfxModule.Buoyancy} turns into lift. */
        public Builder heat(float heat) {
            this.heat = heat;
            return this;
        }

        public Builder module(CgVfxModule module) {
            modules.add(module);
            return this;
        }

        public Builder clearModules() {
            modules.clear();
            return this;
        }

        /**
         * Reacts to what its particles do: children spawned where one lands, dies or reaches an age, rows to the CPU, or
         * both ({@link CgVfxEvent}).
         *
         * <pre>{@code
         * .event(CgVfxEvent.onLanding().spawn(DUST, 3).inherit(0.2f))   // debris raising dust
         * .event(CgVfxEvent.onDeath().readback(16))                     // a hiss for each ember that dies
         * }</pre>
         *
         * <ul>
         *   <li>At most {@link CgVfxEvent#MAX_EVENTS}, in the order given: an event's index is its place here.</li>
         *   <li>A child definition may report rows but not spawn children of its own.</li>
         *   <li>A child spawns only from its parent's events: its own bursts and rate are ignored, so one definition can
         *       play alone and as a child.</li>
         * </ul>
         */
        public Builder event(CgVfxEvent event) {
            if (events.size() == CgVfxEvent.MAX_EVENTS) {
                throw new IllegalArgumentException(name + " has " + CgVfxEvent.MAX_EVENTS + " events already");
            }
            if (event.child() != null && !(event.child() instanceof CgVfxEmitter)) {
                // Its instance books the child's spawns from its numbers.
                throw new IllegalArgumentException(name + "'s child " + event.child().name() + " is not a CgVfxEmitter");
            }
            events.add(event);
            return this;
        }

        /** Size over life, as a share of the starting size; 1 throughout unless given. */
        public Builder size(CgKeyframes overLife) {
            sizeOverLife = overLife;
            return this;
        }

        /** Opacity over life; full until 80% of its life, then easing out, unless given. */
        public Builder opacity(CgKeyframes overLife) {
            opacityOverLife = overLife;
            return this;
        }

        /**
         * Its start colour, which multiplies the colours of every layer drawing it: Niagara's Initialize Particle colour.
         * White unless given. An {@code 0xAARRGGBB} colour as shaders author colour; its alpha multiplies the opacity.
         *
         * <pre>{@code
         * .color(0xFFFF8A3D)                     // orange embers on a white-hot spark shader
         * .color(0xFF66D9FF, 3f)                 // past white: rgb times 3, which blooms
         * }</pre>
         */
        public Builder color(int argb) {
            return color(argb, 1f);
        }

        /** Its start colour, {@code argb}'s rgb times {@code intensity}. */
        public Builder color(int argb, float intensity) {
            return color(argb, intensity, argb, intensity);
        }

        /**
         * A start colour between two, each channel rolled on its own a particle: Niagara's non-uniform range. Each end's
         * alpha bounds its opacity multiplier.
         *
         * <pre>{@code
         * .color(0xFFFFD27A, 0xFFFF5A1F)                   // gold to red-orange sparks: green and blue vary apart
         * .color(0xFFFFFFFF, 4f, 0xFF3A7BFF, 1.5f)         // white-hot to blue, each with its own intensity
         * }</pre>
         */
        public Builder color(int a, int b) {
            return color(a, 1f, b, 1f);
        }

        /** A start colour mixed between {@code a} times {@code intensityA} and {@code b} times {@code intensityB}. */
        public Builder color(int a, float intensityA, int b, float intensityB) {
            colorFrom = rgba(a, intensityA);
            colorTo = rgba(b, intensityB);
            return this;
        }

        /**
         * Its colour over life, each channel multiplying the start colour's: Niagara's Scale Color. 1 throughout unless
         * given; alpha is {@link #opacity(CgKeyframes)}.
         *
         * <pre>{@code
         * // white-hot, cooling through orange to a dull red
         * .colorOverLife(CgKeyframes.constant(1f),
         *         CgKeyframes.start(0f, 1f).to(0.4f, 0.55f, CgEasings.LINEAR).to(1f, 0.15f, CgEasings.LINEAR).build(),
         *         CgKeyframes.start(0f, 1f).to(0.3f, 0.2f, CgEasings.LINEAR).to(1f, 0.05f, CgEasings.LINEAR).build())
         * }</pre>
         */
        public Builder colorOverLife(CgKeyframes red, CgKeyframes green, CgKeyframes blue) {
            this.red = red;
            this.green = green;
            this.blue = blue;
            return this;
        }

        /**
         * Its colour's intensity over life, multiplying its rgb: a flash at birth fading to its plain colour, HDR. 1
         * throughout unless given.
         *
         * <pre>{@code
         * .intensityOverLife(CgKeyframes.start(0f, 6f).to(0.15f, 1f, CgEasings.OUT_QUAD).build())
         * }</pre>
         */
        public Builder intensityOverLife(CgKeyframes overLife) {
            intensity = overLife;
            return this;
        }

        /**
         * Its start scale on each axis, times its {@link #size(float, float) size}: Niagara's non-uniform sprite size and
         * mesh scale. A quad takes x across and y up; a mesh all three in its own axes. 1 unless given.
         *
         * <pre>{@code
         * .size(0.3f, 0.5f).scale(1f, 1f, 6f)             // a mesh stretched six times along z: a beam's streak
         * .size(0.1f, 0.2f).scale(0.5f, 2f, 1f)           // tall thin quads: rain
         * }</pre>
         */
        public Builder scale(float x, float y, float z) {
            return scale(x, y, z, x, y, z);
        }

        /**
         * A start scale between two, each axis rolled on its own a particle: Niagara's non-uniform range. For one roll
         * scaling every axis together, keep the scale fixed and give {@link #size(float, float)} the range.
         *
         * <pre>{@code
         * .scale(1f, 1f, 4f, 1.2f, 1.2f, 9f)               // streaks 4 to 9 times their width
         * }</pre>
         */
        public Builder scale(float x0, float y0, float z0, float x1, float y1, float z1) {
            scaleFrom = new float[]{x0, y0, z0};
            scaleTo = new float[]{x1, y1, z1};
            return this;
        }

        /**
         * Its scale on each axis over life, times its start scale: Niagara's Scale Mesh Size and Scale Sprite Size. 1
         * throughout unless given; {@link #size(CgKeyframes)} scales all three.
         *
         * <pre>{@code
         * // a shock ring flattening as it spreads
         * CgKeyframes wide = CgKeyframes.start(0f, 1f).to(1f, 3f, CgEasings.OUT_QUAD).build();
         * .scaleOverLife(wide, CgKeyframes.start(0f, 1f).to(1f, 0.2f, CgEasings.LINEAR).build(), wide)
         * }</pre>
         */
        public Builder scaleOverLife(CgKeyframes x, CgKeyframes y, CgKeyframes z) {
            scaleX = x;
            scaleY = y;
            scaleZ = z;
            return this;
        }

        private static float[] rgba(int argb, float intensity) {
            return new float[]{(argb >> 16 & 0xFF) / 255f * intensity, (argb >> 8 & 0xFF) / 255f * intensity,
                    (argb & 0xFF) / 255f * intensity, (argb >>> 24) / 255f};
        }

        public CgVfxEmitter build() {
            if (sweep != 0f && (!bursts.isEmpty() || rate.perSecond() <= 0f || state.loops() != 1
                    || state.durationMin() != state.durationMax()
                    || !(state.durationMax() > 0f && state.durationMax() < Float.POSITIVE_INFINITY))) {
                throw new IllegalStateException(name
                        + " sweeps its shape, which needs one loop of a fixed duration, a rate and no bursts");
            }
            if (state.loops() > 1 && !(state.shortestPeriod() > 0f)) {
                throw new IllegalStateException(name + " loops in no time: give its loops a duration or a delay");
            }
            return new CgVfxEmitter(this);
        }
    }
}
