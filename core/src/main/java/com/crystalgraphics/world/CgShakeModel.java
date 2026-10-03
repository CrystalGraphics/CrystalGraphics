package com.crystalgraphics.world;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

/**
 * The trauma model behind {@link CgCameraShake} (Eiserloh, "Juicing Your Cameras With Math"): impacts add trauma 0..1,
 * scaled by where the camera stands from them, trauma decays at a fixed rate, held rumbles add to it while held, and the
 * camera moves by trauma squared. Pure state and maths, so it is tested without a host.
 */
final class CgShakeModel {

    /** Trauma lost a second. */
    static final float DECAY = 0.8f;
    /** Seconds a rumble lasts with nobody setting its level: an effect that dies without closing it lets it go. */
    static final double RUMBLE_EXPIRES = 0.25;
    private static final int MAX_PENDING = 32, PENDING_STRIDE = 7;

    private final DoubleSupplier clock;
    private final double[] pending = new double[MAX_PENDING * PENDING_STRIDE];
    private final List<CgCameraShake.Rumble> rumbles = new ArrayList<>();
    private int pendingCount;
    private float trauma;

    CgShakeModel(DoubleSupplier clock) {
        this.clock = clock;
    }

    double now() {
        return clock.getAsDouble();
    }

    /** An impact: weighed against the camera on the next step, since only a frame knows where the camera is. */
    void add(double x, double y, double z, float amount, float inner, float outer, float exponent) {
        if (pendingCount == MAX_PENDING) return;
        int i = pendingCount++ * PENDING_STRIDE;
        pending[i] = x;
        pending[i + 1] = y;
        pending[i + 2] = z;
        pending[i + 3] = amount;
        pending[i + 4] = inner;
        pending[i + 5] = outer;
        pending[i + 6] = exponent;
    }

    void hold(CgCameraShake.Rumble rumble) {
        if (!rumbles.contains(rumble)) rumbles.add(rumble);
    }

    /**
     * Advances {@code dt} seconds with the camera at {@code (cx, cy, cz)}: decays, takes in the impacts since the last
     * step and sums the rumbles. Answers the shake, the total trauma squared.
     */
    float step(float dt, double cx, double cy, double cz) {
        trauma = Math.max(0f, trauma - DECAY * dt);
        for (int k = 0; k < pendingCount; k++) {
            int i = k * PENDING_STRIDE;
            double d = distance(pending[i] - cx, pending[i + 1] - cy, pending[i + 2] - cz);
            trauma += (float) pending[i + 3] * falloff(d, (float) pending[i + 4], (float) pending[i + 5], (float) pending[i + 6]);
        }
        pendingCount = 0;
        trauma = Math.min(trauma, 1f);
        double now = now();
        float held = 0f;
        for (int k = rumbles.size() - 1; k >= 0; k--) {
            CgCameraShake.Rumble r = rumbles.get(k);
            if (r.closed || now - r.touched > RUMBLE_EXPIRES) {
                rumbles.remove(k);
                continue;
            }
            held += r.level * falloff(distance(r.x - cx, r.y - cy, r.z - cz), r.inner, r.outer, 1f);
        }
        float total = Math.min(trauma + held, 1f);
        return total * total;
    }

    /** The decaying trauma, without the rumbles. */
    float trauma() {
        return trauma;
    }

    /** Nothing to shake now or later. */
    boolean still() {
        return trauma == 0f && pendingCount == 0 && rumbles.isEmpty();
    }

    /** 1 within {@code inner}, 0 beyond {@code outer}, eased between by {@code exponent}. */
    static float falloff(double distance, float inner, float outer, float exponent) {
        if (distance <= inner) return 1f;
        if (distance >= outer) return 0f;
        return (float) Math.pow(1.0 - (distance - inner) / (outer - inner), exponent);
    }

    /** Smooth gradient noise in about -1..1 along {@code t}, a different curve per {@code channel}. */
    static float noise(float t, int channel) {
        int i = (int) Math.floor(t);
        float f = t - i;
        float g0 = gradient(i, channel), g1 = gradient(i + 1, channel);
        float u = f * f * f * (f * (f * 6f - 15f) + 10f);
        return 2f * ((1f - u) * g0 * f + u * g1 * (f - 1f));
    }

    private static float gradient(int i, int channel) {
        int h = i * 0x9E3779B1 ^ channel * 0x85EBCA77;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        return ((h >>> 8) * (1f / (1 << 24))) * 2f - 1f;
    }

    private static double distance(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }
}
