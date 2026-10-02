package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;

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
 * </ul>
 */
public final class CgVfxEmitter {

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
    final int capacity;
    final float[] burstTimes;
    final int[] burstCounts;
    final float rate, rateFrom, rateUntil;
    final float shapeRadius, upMin, upMax, upBias, speedMin, speedMax, lifeMin, lifeMax;
    final float sizeMin, sizeMax, sizeSkew, spinMin, spinMax, heat;
    final List<CgVfxModule> modules;
    final CgKeyframes sizeOverLife, opacityOverLife;

    private CgVfxEmitter(Builder b) {
        name = b.name;
        layer = b.layer;
        renderer = b.renderer;
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
        shapeRadius = b.shapeRadius;
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
        sizeOverLife = b.sizeOverLife;
        opacityOverLife = b.opacityOverLife;
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

    public List<CgVfxModule> modules() {
        return modules;
    }

    /** How much of its starting size a particle is at {@code progress} 0..1 through its life. */
    public float sizeAt(float progress) {
        return sizeOverLife.at(progress);
    }

    /** How opaque a particle is at {@code progress} 0..1 through its life. */
    public float opacityAt(float progress) {
        return opacityOverLife.at(progress);
    }

    /** Seconds from the instance's start after which this emitter spawns nothing more. */
    float lastSpawn() {
        float last = rate > 0f ? rateUntil : 0f;
        for (float t : burstTimes) last = Math.max(last, t);
        return last;
    }

    public static final class Builder {

        private final String name;
        private String layer;
        private Renderer renderer = Renderer.QUADS;
        private int capacity = 256;
        private final List<Float> burstTimes = new ArrayList<>();
        private final List<Integer> burstCounts = new ArrayList<>();
        private float rate, rateFrom, rateUntil;
        private float shapeRadius;
        private float upMin = -1f, upMax = 1f, upBias = 1f;
        private float speedMin, speedMax;
        private float lifeMin = 1f, lifeMax = 1f;
        private float sizeMin = 1f, sizeMax = 1f, sizeSkew = 1f;
        private float spinMin, spinMax;
        private float heat;
        private final List<CgVfxModule> modules = new ArrayList<>();
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
            capacity = e.capacity;
            for (int i = 0; i < e.burstTimes.length; i++) {
                burstTimes.add(e.burstTimes[i]);
                burstCounts.add(e.burstCounts[i]);
            }
            rate = e.rate;
            rateFrom = e.rateFrom;
            rateUntil = e.rateUntil;
            shapeRadius = e.shapeRadius;
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
            sizeOverLife = e.sizeOverLife;
            opacityOverLife = e.opacityOverLife;
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

        /** {@code perSecond} particles a second, from {@code from} to {@code until} seconds after the start. */
        public Builder rate(float perSecond, float from, float until) {
            rate = perSecond;
            rateFrom = from;
            rateUntil = until;
            return this;
        }

        /** Particles start within this many blocks of the source, along their launch direction. */
        public Builder shape(float radius) {
            shapeRadius = radius;
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
            return new CgVfxEmitter(this);
        }
    }
}
