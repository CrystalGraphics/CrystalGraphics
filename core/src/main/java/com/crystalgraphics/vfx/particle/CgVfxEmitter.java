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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What one kind of particle is and how it behaves: when and how many spawn, how each starts, the {@link CgVfxModule}
 * stack its physics is built from, and what changes over its life. Immutable and shared: every effect playing it makes
 * its own {@link CgVfxEmitterInstance}. Units are blocks and seconds. The analogue of a Niagara emitter.
 *
 * <pre>{@code
 * static final CgVfxEmitter EMBERS = CgVfxEmitter.builder("embers")
 *         .capacity(400).burst(0f, 360)
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
    final int capacity;
    final float[] burstTimes;
    final int[] burstCounts;
    final float rate, rateFrom, rateUntil;
    final float sweep, upMin, upMax, upBias, speedMin, speedMax, lifeMin, lifeMax;
    final CgVfxSpawnShape spawn;
    /** Spawns a block the source moves and when, the most blocks a step may move and still count, its velocity's share. */
    final float perBlock, blockFrom, blockUntil, maxStep, inherit;
    /** Each particle's last positions its pool keeps for a {@code trails()} layer; 0 for none. */
    final int trailPoints;
    /** The sweep as spawn k's front, {@code frontA k - frontB k^2} blocks, k held at its peak: what both paths read. */
    final float frontA, frontB;
    final float sizeMin, sizeMax, sizeSkew, spinMin, spinMax, heat;
    final List<CgVfxModule> modules;
    final List<CgVfxEvent> events;
    final CgKeyframes sizeOverLife, opacityOverLife;
    /** The size curve's peak at the curve row's samples: what the GPU path draws at most. */
    private final float sizePeak;
    private final boolean feedsGas, splatsGrid;
    /** {@link #scaled} copies, one per scale asked for. */
    private final Map<Float, CgVfxEmitter> scaledCopies = new ConcurrentHashMap<>();

    private CgVfxEmitter(Builder b) {
        name = b.name;
        slot = CgVfxSlot.particles(b.name);
        renderer = b.renderer;
        optional = b.optional;
        local = b.local;
        capacity = b.capacity;
        burstTimes = new float[b.burstTimes.size()];
        burstCounts = new int[b.burstCounts.size()];
        for (int i = 0; i < burstTimes.length; i++) {
            burstTimes[i] = b.burstTimes.get(i);
            burstCounts[i] = b.burstCounts.get(i);
        }
        rate = b.rate;
        rateFrom = b.rateFrom;
        rateUntil = b.rateUntil;
        spawn = b.spawn.build(b.name);
        perBlock = b.perBlock;
        blockFrom = b.blockFrom;
        blockUntil = b.blockUntil;
        maxStep = b.maxStep;
        inherit = b.inherit;
        trailPoints = b.trailPoints;
        sweep = b.sweep;
        // Spawn k is born k / rate into the rate's span T: the front, sweep (t - t^2 / 2T), in k.
        float span = b.rateUntil - b.rateFrom;
        frontA = sweep != 0f ? sweep / b.rate : 0f;
        frontB = sweep != 0f ? sweep / (2f * span * b.rate * b.rate) : 0f;
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
        float peak = 0f;
        for (int i = 0; i < CgVfxGpuEmitter.CURVE_TEXELS; i++) {
            peak = Math.max(peak, sizeAt((float) i / (CgVfxGpuEmitter.CURVE_TEXELS - 1)));
        }
        sizePeak = peak;
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
            b.perBlock /= s;
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
        for (int i = 0; i < texels; i++) {
            float progress = texels > 1 ? (float) i / (texels - 1) : 0f;
            out[at + 2 * i] = sizeAt(progress);
            out[at + 2 * i + 1] = opacityAt(progress);
        }
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
        return perBlock > 0f ? Integer.MAX_VALUE : spawnsWithin(lifeMax + 0.1f);
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
        int bursts = 0;
        for (float from : burstTimes) {
            int alive = 0;
            for (int j = 0; j < burstTimes.length; j++) {
                if (burstTimes[j] >= from && burstTimes[j] < from + span) alive += burstCounts[j];
            }
            bursts = Math.max(bursts, alive);
        }
        int fromRate = rate > 0f ? (int) Math.ceil(rate * Math.min(span, rateUntil - rateFrom)) + 2 : 0;
        return bursts + fromRate;
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
        float swept = sweep != 0f ? 0.5f * Math.abs(sweep) * (rateUntil - rateFrom) : 0f;
        return spawn.extent() + swept + speedMax * lifeMax + 0.5f * pull * lifeMax * lifeMax + (wind ? windSpeed * lifeMax : 0f);
    }

    /** The largest a particle draws, before a look's own scale: its largest size times its size curve's peak. */
    public float largestSize() {
        return sizeMax * sizePeak;
    }

    /** Seconds from the instance's start after which this emitter spawns nothing more. */
    float lastSpawn() {
        float last = Math.max(rate > 0f ? rateUntil : 0f, perBlock > 0f ? blockUntil : 0f);
        for (float t : burstTimes) last = Math.max(last, t);
        return last;
    }

    public static final class Builder {

        private String name;
        private Renderer renderer = Renderer.QUADS;
        private boolean optional, local;
        private int capacity = 256;
        private final List<Float> burstTimes = new ArrayList<>();
        private final List<Integer> burstCounts = new ArrayList<>();
        private float rate, rateFrom, rateUntil;
        private CgVfxSpawnShape.Builder spawn = new CgVfxSpawnShape.Builder();
        private float sweep, perBlock, blockFrom, blockUntil, inherit, maxStep = 8f;
        private int trailPoints;
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

        private Builder(String name) {
            this.name = name;
        }

        private Builder(CgVfxEmitter e) {
            name = e.name;
            renderer = e.renderer;
            optional = e.optional;
            local = e.local;
            capacity = e.capacity;
            for (int i = 0; i < e.burstTimes.length; i++) {
                burstTimes.add(e.burstTimes[i]);
                burstCounts.add(e.burstCounts[i]);
            }
            rate = e.rate;
            rateFrom = e.rateFrom;
            rateUntil = e.rateUntil;
            spawn = new CgVfxSpawnShape.Builder(e.spawn);
            perBlock = e.perBlock;
            blockFrom = e.blockFrom;
            blockUntil = e.blockUntil;
            maxStep = e.maxStep;
            inherit = e.inherit;
            trailPoints = e.trailPoints;
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
         * CgVfxEmitter.builder("auraSparkles").local().rate(40f, 0f, Float.POSITIVE_INFINITY).shape(CgShell.of(0.6f, 0.9f))...
         * }</pre>
         */
        public Builder local() {
            this.local = true;
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

        /** {@code count} particles at once, {@code time} seconds after the instance starts. */
        public Builder burst(float time, int count) {
            burstTimes.add(time);
            burstCounts.add(count);
            return this;
        }

        /**
         * {@code perSecond} particles a second from the start until the instance is {@link CgVfxEmitterInstance#stop
         * stopped}: an aura, a fire, a wake. Give it a {@link #capacity}.
         *
         * <pre>{@code
         * CgVfxEmitter.builder("embers").capacity(400).rate(60f)...
         * }</pre>
         */
        public Builder rate(float perSecond) {
            return rate(perSecond, 0f, Float.POSITIVE_INFINITY);
        }

        /**
         * {@code perSecond} particles a second, from {@code from} to {@code until} seconds after the start. An
         * {@code until} of {@code Float.POSITIVE_INFINITY} spawns until the instance is
         * {@link CgVfxEmitterInstance#stop stopped}, and give it a {@link #capacity}.
         */
        public Builder rate(float perSecond, float from, float until) {
            rate = perSecond;
            rateFrom = from;
            rateUntil = until;
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
         * The shape runs outward at {@code speed} blocks a second from the rate's start, easing to a stop at its end, so
         * particles are born on a front sweeping out: a shock front lifting dust off the ground as it passes. It covers
         * {@code speed} times the rate's span, halved; a size curve of {@code OUT_QUAD} over that span follows it.
         *
         * <pre>{@code
         * CgVfxEmitter.builder("skirt").rate(400f, 0f, 1.6f).shape(CgShell.of(9f, 9.5f)).launch(0f, 0.15f, 1f)
         *         .sweep(46f)                                    // 9 blocks out at the start, 46 at 1.6 s
         *         ...
         * }</pre>
         *
         * <ul>
         *   <li>Rate spawns only, so a sweeping emitter takes no burst: {@link #build} throws.</li>
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
         * {@code perBlock} particles for each block its source moves, from the start until it is
         * {@link CgVfxEmitterInstance#stop stopped}, beside its {@link #rate}: a wake, a trail of footsteps. A step moving
         * farther than {@link #maxStep} is a teleport and spawns nothing by distance. How fast its source travels is
         * unbounded, so its {@link #capacity} is what holds it: give it one.
         *
         * <pre>{@code
         * CgVfxEmitter.builder("wake").capacity(600).ratePerBlock(6f).inherit(0.4f)...
         * wake.moveTo(x, y, z);   // each tick: the books count how far it went
         * }</pre>
         */
        public Builder ratePerBlock(float perBlock) {
            return ratePerBlock(perBlock, 0f, Float.POSITIVE_INFINITY);
        }

        /** {@code perBlock} particles for each block its source moves from {@code from} to {@code until} seconds after its start. */
        public Builder ratePerBlock(float perBlock, float from, float until) {
            this.perBlock = perBlock;
            blockFrom = from;
            blockUntil = until;
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

        public CgVfxEmitter build() {
            if (sweep != 0f && (!burstTimes.isEmpty() || rate <= 0f || rateUntil <= rateFrom)) {
                throw new IllegalStateException(name + " sweeps its shape, which needs a rate span and no bursts");
            }
            return new CgVfxEmitter(this);
        }
    }
}
