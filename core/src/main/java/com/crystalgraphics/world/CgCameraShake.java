package com.crystalgraphics.world;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgHostCamera;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.stage.CgHostEnvironment;
import com.crystalgraphics.render.stage.CgHostFrame;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgStageFrame;

/**
 * Shakes the host's camera, and kicks its field of view, for every effect at once: each adds an impulse, and once a frame
 * they are summed into the one offset {@link CgHostCamera} applies. A shake is felt less the farther the camera is from
 * where it started, and scaled by the player's accessibility settings for screen and field-of-view effects.
 *
 * <pre>{@code
 * CgCameraShake.shake(x, y, z, 1f, 0.8f, 40f);   // a blast: full strength, 0.8 s, felt out to 40 blocks
 * CgCameraShake.kick(0.12f, 0.25f);              // the field of view widened 12% at once, easing back over 0.25 s
 * }</pre>
 *
 * <ul>
 *   <li>Strength 1 turns the camera up to about two degrees and moves it a few hundredths of a block.</li>
 *   <li>It does nothing where the host fills no camera slot: the harness. Render thread only; allocates nothing.</li>
 *   <li>At most {@link #MAX} impulses at once; past that the weakest is replaced.</li>
 * </ul>
 */
public final class CgCameraShake {

    public static final int MAX = 16;

    private static final float DEGREES = 2f, BLOCKS = 0.04f;

    // Per impulse: start time, duration, strength, reach, x, y, z, phase; a reach of 0 is an FOV kick.
    private static final double[] IMPULSES = new double[MAX * 8];
    private static int count;
    private static boolean installed, moving;

    private CgCameraShake() {
    }

    /** A shake from the point: {@code strength} at the point, gone at {@code reach} blocks, over {@code seconds}. */
    public static void shake(double x, double y, double z, float strength, float seconds, float reach) {
        add(strength, seconds, Math.max(reach, 1e-3f), x, y, z);
    }

    /** The field of view widened by {@code amount} (0.1 is 10%) at once, easing back over {@code seconds}. */
    public static void kick(float amount, float seconds) {
        add(amount, seconds, 0f, 0.0, 0.0, 0.0);
    }

    private static void add(float strength, float seconds, float reach, double x, double y, double z) {
        install();
        double now = CgFrameClock.seconds();
        int at = count < MAX ? count++ : weakest(now);
        int i = at * 8;
        IMPULSES[i] = now;
        IMPULSES[i + 1] = Math.max(seconds, 1e-3f);
        IMPULSES[i + 2] = strength;
        IMPULSES[i + 3] = reach;
        IMPULSES[i + 4] = x;
        IMPULSES[i + 5] = y;
        IMPULSES[i + 6] = z;
        IMPULSES[i + 7] = (x * 12.9898 + z * 78.233 + now * 3.7) % 100.0;
    }

    private static int weakest(double now) {
        int best = 0;
        double least = Double.MAX_VALUE;
        for (int k = 0; k < MAX; k++) {
            double left = IMPULSES[k * 8 + 2] * envelope(now, k);
            if (left < least) {
                least = left;
                best = k;
            }
        }
        return best;
    }

    private static double envelope(double now, int k) {
        double t = (now - IMPULSES[k * 8]) / IMPULSES[k * 8 + 1];
        return t >= 1.0 ? 0.0 : (1.0 - t) * (1.0 - t);
    }

    private static void install() {
        if (installed) return;
        installed = true;
        CgRenderStage.WORLD_OPAQUE.register(Integer.MIN_VALUE, CgCameraShake::frame);
    }

    /** Sums the live impulses against this frame's camera and hands the result to the host for its next frame. */
    private static void frame(CgStageFrame stage) {
        if (count == 0 && !moving) return;
        CgHostFrame host = stage.host();
        CgHostEnvironment world = host.environment();
        double now = CgFrameClock.seconds();
        double cx = host.view().x(), cy = host.view().y(), cz = host.view().z();
        float screen = Float.isNaN(world.screenEffects()) ? 1f : world.screenEffects();
        float fovEffects = Float.isNaN(world.fovEffects()) ? 1f : world.fovEffects();
        double yaw = 0, pitch = 0, roll = 0, dx = 0, dy = 0, dz = 0, fov = 0;
        for (int k = count - 1; k >= 0; k--) {
            int i = k * 8;
            double e = envelope(now, k);
            if (e <= 0.0) {
                remove(k);
                continue;
            }
            double strength = IMPULSES[i + 2] * e, reach = IMPULSES[i + 3];
            if (reach == 0.0) {
                fov += strength;
                continue;
            }
            double ox = IMPULSES[i + 4] - cx, oy = IMPULSES[i + 5] - cy, oz = IMPULSES[i + 6] - cz;
            double felt = 1.0 - Math.min(Math.sqrt(ox * ox + oy * oy + oz * oz) / reach, 1.0);
            double a = strength * felt * felt, t = (now - IMPULSES[i]) + IMPULSES[i + 7];
            // Incommensurate sines: an irregular tremble that never repeats within a shake.
            yaw += a * (Math.sin(t * 37.0) + 0.5 * Math.sin(t * 61.0));
            pitch += a * (Math.sin(t * 43.0 + 1.3) + 0.5 * Math.sin(t * 71.0));
            roll += a * 0.6 * Math.sin(t * 29.0 + 2.1);
            dx += a * Math.sin(t * 53.0 + 0.7);
            dy += a * Math.sin(t * 47.0 + 2.9);
            dz += a * Math.sin(t * 59.0 + 4.1);
        }
        float s = screen * DEGREES, b = screen * BLOCKS;
        CgPlatform.get(CgHostCamera.SERVICE).offset((float) dx * b, (float) dy * b, (float) dz * b,
                (float) yaw * s, (float) pitch * s, (float) roll * s, 1f + (float) fov * fovEffects);
        // One more frame of zeros once the last impulse ends, so the host lets the camera go.
        moving = count > 0;
    }

    private static void remove(int k) {
        int last = --count;
        if (k != last) System.arraycopy(IMPULSES, last * 8, IMPULSES, k * 8, 8);
    }
}
