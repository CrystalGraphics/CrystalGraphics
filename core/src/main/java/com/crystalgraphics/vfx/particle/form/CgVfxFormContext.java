package com.crystalgraphics.vfx.particle.form;

import java.util.HashMap;
import java.util.Map;

/**
 * What a compiled input is evaluated against: one particle's lookups and its instance's parameters. Reused, set field
 * by field, so evaluating allocates nothing.
 *
 * <pre>{@code
 * CgVfxFormContext ctx = new CgVfxFormContext().seed(7).particle(12).age(0.25f);
 * ctx.parameter("user.radius", 2f);
 * input.eval(ctx, out);
 * }</pre>
 */
public final class CgVfxFormContext {

    int seed, particle, loopIndex;
    float age, particleAge, loop, speed, length;
    private final Map<String, float[]> parameters = new HashMap<>();

    /** The instance's seed. */
    public CgVfxFormContext seed(int seed) {
        this.seed = seed;
        return this;
    }

    /** The particle's spawn index: its id. */
    public CgVfxFormContext particle(int particle) {
        this.particle = particle;
        return this;
    }

    /** Its age as a share of its life, 0 to 1. */
    public CgVfxFormContext age(float age) {
        this.age = age;
        return this;
    }

    /** Its age in seconds. */
    public CgVfxFormContext particleAge(float seconds) {
        this.particleAge = seconds;
        return this;
    }

    /** The emitter loop's seconds, and which loop: what a per-loop roll keys on. */
    public CgVfxFormContext loop(float seconds, int index) {
        this.loop = seconds;
        this.loopIndex = index;
        return this;
    }

    public CgVfxFormContext speed(float speed) {
        this.speed = speed;
        return this;
    }

    /** A trail point's distance from its head. */
    public CgVfxFormContext length(float length) {
        this.length = length;
        return this;
    }

    /** A bound parameter's value ({@code user.radius}); unset reads as the binding's default. */
    public CgVfxFormContext parameter(String path, float... value) {
        parameters.put(path, value);
        return this;
    }

    float[] parameter(String path) {
        return parameters.get(path);
    }
}
