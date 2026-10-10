package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWords;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What one kind of particle is and how it behaves: when and how many spawn, how each starts, the {@link CgVfxModule}
 * stack its physics is built from, and what changes over its life. Immutable and shared: every effect playing it makes
 * its own {@link CgVfxEmitterInstance}. Units are blocks and seconds. The analogue of a Niagara emitter.
 *
 * <pre>{@code
 * static final CgVfxEmitter EMBERS = CgVfxEmitter.builder("embers")
 *         .capacity(400).burst(0f, 360)
 *         .launch(-0.1f, 1f, 0.6f).speed(6f, 18f)          // up-biased, 6..18 blocks a second
 *         .life(4f, 7f).size(0.05f, 0.25f, 2f).heat(1f)
 *         .module(new CgVfxModule.Gravity(9.8f))
 *         .module(new CgVfxModule.Drag(4f, 0f))
 *         .module(new CgVfxModule.Buoyancy(16f, 0.9f))
 *         .module(new CgVfxModule.Turbulence(8f, 0.12f, 0.6f))
 *         .module(new CgVfxModule.Wind(1f))
 *         .opacity(CgKeyframes.start(0f, 0f).to(0.05f, 1f, CgEasings.LINEAR).to(1f, 0f, CgEasings.OUT_QUAD).build())
 *         .layer("sparkles")
 *         .build();
 *
 * // a variant that changes only what differs
 * CgVfxEmitter sparse = EMBERS.toBuilder().capacity(120).burst(0f, 100).build();
 * }</pre>
 *
 * <ul>
 *   <li>{@link Builder#capacity} bounds the live particles; a spawn into a full set is dropped.</li>
 *   <li>The launch direction is the vertical share {@code up} in [{@code upMin}, {@code upMax}], skewed toward
 *       {@code upMax} by a bias under 1, at any heading.</li>
 *   <li>The player's density setting thins every emitter; one marked {@link Builder#optional} spawns half again at the
 *       Low quality tier.</li>
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

    final String name, layer;
    final Renderer renderer;
    final boolean optional;
    final int capacity;
    final float[] burstTimes;
    final int[] burstCounts;
    final float rate, rateFrom, rateUntil;
    final float shapeInner, shapeRadius, sweep, upMin, upMax, upBias, speedMin, speedMax, lifeMin, lifeMax;
    /** The sweep as spawn k's front, {@code frontA k - frontB k^2} blocks, k held at its peak: what both paths read. */
    final float frontA, frontB;
    final float sizeMin, sizeMax, sizeSkew, spinMin, spinMax, heat;
    final List<CgVfxModule> modules;
    final List<CgVfxEvent> events;
    final CgKeyframes sizeOverLife, opacityOverLife;
    /** The size curve's peak at the curve row's samples: what the GPU path draws at most. */
    private final float sizePeak;

    private CgVfxEmitter(Builder b) {
        name = b.name;
        layer = b.layer;
        renderer = b.renderer;
        optional = b.optional;
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
        shapeInner = b.shapeInner;
        shapeRadius = b.shapeRadius;
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

    public String name() {
        return name;
    }

    /** The slot of the look's layer that draws these particles. */
    public String layer() {
        return layer;
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

    @Override
    public List<CgVfxModule> modules() {
        return modules;
    }

    @Override
    public List<CgVfxEvent> events() {
        return events;
    }

    @Override
    public void writeSpawn(CgVfxWords out) {
        out.vec4(shapeRadius, upMin, upMax, upBias)
           .vec4(speedMin, speedMax, lifeMin, lifeMax)
           .vec4(sizeMin, sizeMax, sizeSkew, heat)
           .vec4(spinMin, spinMax, shapeInner, 0f)
           .vec4(frontA, frontB, 0f, 0f);
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
     * The most particles one instance can have alive at once, at full share: what a GPU pool sizes its slot by, since a
     * pool never drops a spawn. Every burst within any span of its longest life, plus its rate over that span.
     *
     * <pre>{@code
     * int slot = pool.open(EMBERS, EMBERS.peakAlive());
     * }</pre>
     */
    public int peakAlive() {
        // A particle can outlast its life by its last step: a tenth of a second covers any particle step.
        return spawnsWithin(lifeMax + 0.1f);
    }

    /**
     * The most children event {@code event} (its index in {@link #events()}) of one instance can have alive at once:
     * what its child slot is sized by, and its CPU set.
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
        return shapeRadius + swept + speedMax * lifeMax + 0.5f * pull * lifeMax * lifeMax + (wind ? windSpeed * lifeMax : 0f);
    }

    /** The largest a particle draws, before a look's own scale: its largest size times its size curve's peak. */
    public float largestSize() {
        return sizeMax * sizePeak;
    }

    /** Seconds from the instance's start after which this emitter spawns nothing more. */
    float lastSpawn() {
        float last = rate > 0f ? rateUntil : 0f;
        for (float t : burstTimes) last = Math.max(last, t);
        return last;
    }

    public static final class Builder {

        private String name;
        private String layer;
        private Renderer renderer = Renderer.QUADS;
        private boolean optional;
        private int capacity = 256;
        private final List<Float> burstTimes = new ArrayList<>();
        private final List<Integer> burstCounts = new ArrayList<>();
        private float rate, rateFrom, rateUntil;
        private float shapeInner, shapeRadius, sweep;
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
            this.layer = name;
        }

        private Builder(CgVfxEmitter e) {
            name = e.name;
            layer = e.layer;
            renderer = e.renderer;
            optional = e.optional;
            capacity = e.capacity;
            for (int i = 0; i < e.burstTimes.length; i++) {
                burstTimes.add(e.burstTimes[i]);
                burstCounts.add(e.burstCounts[i]);
            }
            rate = e.rate;
            rateFrom = e.rateFrom;
            rateUntil = e.rateUntil;
            shapeInner = e.shapeInner;
            shapeRadius = e.shapeRadius;
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

        /** The slot of the look's layer that draws these particles; the emitter's name unless given. */
        public Builder layer(String slot) {
            layer = slot;
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

        /** Live particles at most. */
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

        /** Particles start within this many blocks of the source, along their launch direction. */
        public Builder shape(float radius) {
            return shape(0f, radius);
        }

        /**
         * Particles start between {@code inner} and {@code outer} blocks from the source, along their launch direction:
         * a shell, as a cloud rolling out from the edge of something already there.
         */
        public Builder shape(float inner, float outer) {
            shapeInner = inner;
            shapeRadius = outer;
            return this;
        }

        /**
         * The shape runs outward at {@code speed} blocks a second from the rate's start, easing to a stop at its end, so
         * particles are born on a front sweeping out: a shock front lifting dust off the ground as it passes. It covers
         * {@code speed} times the rate's span, halved; a size curve of {@code OUT_QUAD} over that span follows it.
         *
         * <pre>{@code
         * CgVfxEmitter.builder("skirt").rate(400f, 0f, 1.6f).shape(9f, 9.5f).launch(0f, 0.15f, 1f)
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

        /** The launch direction's vertical share, from {@code upMin} to {@code upMax}, skewed up by a bias under 1. */
        public Builder launch(float upMin, float upMax, float bias) {
            this.upMin = upMin;
            this.upMax = upMax;
            this.upBias = bias;
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
         * both ({@link CgVfxEvent}). On both simulations.
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
                // The CPU path runs the child too, from its numbers and modules.
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
