package com.crystalgraphics.vfx.camera;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

/**
 * The trauma model behind {@link CgShakeRuntime} (Eiserloh, "Juicing Your Cameras With Math"): impacts add trauma 0..1,
 * scaled by where the camera stands from them, trauma decays at a fixed rate, held rumbles add to it while held, and the
 * camera moves by trauma squared. Pure state and maths, so it is tested without a host.
 */
final class CgShakeModel {

    /** Trauma lost a second. */
    static final float DECAY = 0.8f;
    /** Seconds a rumble lasts with nobody setting its level: an effect that dies without closing it lets it go. */
    static final double RUMBLE_EXPIRES = 0.25;
    private static final int MAX_PENDING = 32, PENDING_STRIDE = 7, MAX_TREMORS = 16, TREMOR_STRIDE = 8;

    private final DoubleSupplier clock;
    private final double[] pending = new double[MAX_PENDING * PENDING_STRIDE];
    private final List<CgCameraShake.Held> rumbles = new ArrayList<>();
    private int pendingCount;
    // Per tremor: start, seconds, trauma, x, y, z, inner, outer.
    private final double[] tremors = new double[MAX_TREMORS * TREMOR_STRIDE];
    private int tremorCount;
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

    /** Trauma held from now, tapering linearly to none over {@code seconds}: a tail that outlives whatever started it. */
    void tremor(double x, double y, double z, float amount, float seconds, float inner, float outer) {
        int i = (tremorCount < MAX_TREMORS ? tremorCount++ : 0) * TREMOR_STRIDE;
        tremors[i] = now();
        tremors[i + 1] = Math.max(seconds, 1e-3f);
        tremors[i + 2] = amount;
        tremors[i + 3] = x;
        tremors[i + 4] = y;
        tremors[i + 5] = z;
        tremors[i + 6] = inner;
        tremors[i + 7] = outer;
    }

    void hold(CgCameraShake.Held rumble) {
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
            CgCameraShake.Held r = rumbles.get(k);
            if (r.closed || now - r.touched > RUMBLE_EXPIRES) {
                rumbles.remove(k);
                continue;
            }
            held += r.level * falloff(distance(r.x - cx, r.y - cy, r.z - cz), r.inner, r.outer, 1f);
        }
        for (int k = tremorCount - 1; k >= 0; k--) {
            int i = k * TREMOR_STRIDE;
            double u = (now - tremors[i]) / tremors[i + 1];
            if (u >= 1.0) {
                int last = --tremorCount;
                if (k != last) System.arraycopy(tremors, last * TREMOR_STRIDE, tremors, i, TREMOR_STRIDE);
                continue;
            }
            double d = distance(tremors[i + 3] - cx, tremors[i + 4] - cy, tremors[i + 5] - cz);
            held += (float) (tremors[i + 2] * (1.0 - u)) * falloff(d, (float) tremors[i + 6], (float) tremors[i + 7], 1f);
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
        return trauma == 0f && pendingCount == 0 && tremorCount == 0 && rumbles.isEmpty();
    }

    /** 1 within {@code inner}, 0 beyond {@code outer}, eased between by {@code exponent}. */
    static float falloff(double distance, float inner, float outer, float exponent) {
        if (distance <= inner) return 1f;
        if (distance >= outer) return 0f;
        return (float) Math.pow(1.0 - (distance - inner) / (outer - inner), exponent);
    }

    /** Seconds a punch lasts: by then its spring has settled. */
    static final float PUNCH_SECONDS = 0.7f;
    private static final float PUNCH_DAMPING = 0.45f, PUNCH_OMEGA = 2f * (float) Math.PI * 3f;
    private static final float PUNCH_OMEGA_D = PUNCH_OMEGA * (float) Math.sqrt(1.0 - PUNCH_DAMPING * PUNCH_DAMPING);
    private static final float PUNCH_PEAK = punchRaw((float) Math.atan2(Math.sqrt(1.0 - PUNCH_DAMPING * PUNCH_DAMPING),
            PUNCH_DAMPING) / PUNCH_OMEGA_D);

    /**
     * A punch's displacement {@code t} seconds in: an underdamped spring struck at 0, reaching 1 within a fifteenth of a
     * second, swinging back past rest by a fifth, settled by {@link #PUNCH_SECONDS}.
     */
    static float punch(float t) {
        return t <= 0f || t >= PUNCH_SECONDS ? 0f : punchRaw(t) / PUNCH_PEAK;
    }

    private static float punchRaw(float t) {
        return (float) (Math.exp(-PUNCH_DAMPING * PUNCH_OMEGA * t) * Math.sin(PUNCH_OMEGA_D * t));
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
