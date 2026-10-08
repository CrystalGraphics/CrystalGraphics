package com.crystalgraphics.vfx.camera;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgHostCamera;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.stage.CgHostEnvironment;
import com.crystalgraphics.render.stage.CgHostFrame;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.settings.CgGraphicsSettings;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * Plays every {@link CgCameraShake} at once into the host's camera: waits for each to arrive, sums trauma, punches,
 * tremors and kicks against the camera's position each frame, and hands the host one offset. Scaled by the player's
 * Screen Effects and FOV Effects, then by CrystalGraphics' comfort settings, which can only lower it.
 */
final class CgShakeRuntime {

    private static final float YAW = 10f, PITCH = 10f, ROLL = 7f, SHIFT = 0.25f;
    /** Noise cycles a second, and the slow band's share: a lurch under a tremble. */
    private static final float LURCH = 4f, RATTLE = 16f, LURCH_SHARE = 0.6f;
    /** A punch of 1 at its peak: degrees, and blocks. */
    private static final float PUNCH_ANGLE = 5f, PUNCH_SHIFT = 0.35f;
    private static final int WAITING = 16, KICKS = 8, PUNCHES = 8;

    static final CgShakeModel MODEL = new CgShakeModel(CgFrameClock::seconds);

    // Per waiting play: start time, x, y, z, direction (NaN: away from the point), scale.
    private static final int WAIT_STRIDE = 8;
    private static final double[] WAIT = new double[WAITING * WAIT_STRIDE];
    private static final CgCameraShake[] WAIT_SHAKE = new CgCameraShake[WAITING];
    private static int waiting;
    // Per kick: start time, duration, amount, x, y, z, inner, outer.
    private static final int KICK_STRIDE = 8;
    private static final double[] KICK = new double[KICKS * KICK_STRIDE];
    private static int kicks;
    // Per punch: start time, strength, x, y, z, direction (NaN: away from the point), inner, outer.
    private static final int PUNCH_STRIDE = 10;
    private static final double[] PUNCH = new double[PUNCHES * PUNCH_STRIDE];
    private static int punches;

    private static boolean installed, moving;
    static boolean enabled = true;
    private static double lastFrame = Double.NaN, cx, cy, cz;
    private static final Vector3f ORIGIN = new Vector3f(), FORWARD = new Vector3f(), RIGHT = new Vector3f();

    private CgShakeRuntime() {
    }

    static boolean installed() {
        return installed;
    }

    static void install() {
        if (installed) return;
        installed = true;
        CgRenderStage.WORLD_OPAQUE.register(Integer.MIN_VALUE, CgShakeRuntime::frame);
    }

    /** Starts {@code shake} at the point, felt at once or when its front reaches the camera. */
    static void play(CgCameraShake shake, double x, double y, double z, double dx, double dy, double dz, float scale) {
        install();
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        boolean away = Double.isNaN(dx) || length < 1e-6;
        if (away) dx = Double.NaN;
        else { dx /= length; dy /= length; dz /= length; }
        if (shake.arrival <= 0f) {
            fire(shake, x, y, z, dx, dy, dz, scale);
            return;
        }
        int k = waiting < WAITING ? waiting++ : 0, i = k * WAIT_STRIDE;
        WAIT_SHAKE[k] = shake;
        WAIT[i] = CgFrameClock.seconds();
        WAIT[i + 1] = x;
        WAIT[i + 2] = y;
        WAIT[i + 3] = z;
        WAIT[i + 4] = dx;
        WAIT[i + 5] = dy;
        WAIT[i + 6] = dz;
        WAIT[i + 7] = scale;
    }

    /** Every part of {@code shake}, felt from now. */
    private static void fire(CgCameraShake shake, double x, double y, double z, double dx, double dy, double dz, float scale) {
        float inner = shake.inner * scale, outer = Math.max(shake.outer * scale, inner + 1e-3f);
        double now = CgFrameClock.seconds();
        if (shake.trauma > 0f) MODEL.add(x, y, z, shake.trauma, inner, outer, shake.falloff);
        if (shake.tremor > 0f) MODEL.tremor(x, y, z, shake.tremor, shake.tremorSeconds, inner, outer);
        if (shake.punch > 0f) {
            int i = (punches < PUNCHES ? punches++ : 0) * PUNCH_STRIDE;
            PUNCH[i] = now;
            PUNCH[i + 1] = shake.punch;
            PUNCH[i + 2] = x;
            PUNCH[i + 3] = y;
            PUNCH[i + 4] = z;
            PUNCH[i + 5] = dx;
            PUNCH[i + 6] = dy;
            PUNCH[i + 7] = dz;
            PUNCH[i + 8] = inner;
            PUNCH[i + 9] = outer;
        }
        if (shake.kick != 0f) {
            int i = (kicks < KICKS ? kicks++ : 0) * KICK_STRIDE;
            KICK[i] = now;
            KICK[i + 1] = Math.max(shake.kickSeconds, 1e-3f);
            KICK[i + 2] = shake.kick;
            KICK[i + 3] = x;
            KICK[i + 4] = y;
            KICK[i + 5] = z;
            KICK[i + 6] = inner;
            KICK[i + 7] = outer;
        }
    }

    /** The view's position plus wherever its matrix puts the eye: nothing more in a camera-relative host. */
    private static void eye(CgHostView view) {
        view.view().originAffine(ORIGIN);
        cx = view.x() + ORIGIN.x;
        cy = view.y() + ORIGIN.y;
        cz = view.z() + ORIGIN.z;
    }

    /** Steps every shake against this frame's camera and hands the offset to the host for its next frame. */
    private static void frame(CgStageFrame stage) {
        double now = CgFrameClock.seconds();
        float dt = Double.isNaN(lastFrame) ? 0f : (float) Math.max(0.0, Math.min(now - lastFrame, 0.1));
        lastFrame = now;
        if (MODEL.still() && waiting == 0 && kicks == 0 && punches == 0 && !moving) return;
        CgHostFrame host = stage.host();
        CgHostEnvironment world = host.environment();
        eye(host.view());
        arrive(now);
        float shake = MODEL.step(dt, cx, cy, cz);
        float screen = enabled ? (Float.isNaN(world.screenEffects()) ? 1f : world.screenEffects()) * CgGraphicsSettings.SHAKE.get() : 0f;
        float fovEffects = enabled ? (Float.isNaN(world.fovEffects()) ? 1f : world.fovEffects()) * CgGraphicsSettings.FOV_KICK.get() : 0f;
        float fov = kicks(now);
        float s = shake * screen, t = (float) now;
        float px = 0f, py = 0f, pz = 0f, pYaw = 0f, pPitch = 0f, pRoll = 0f;
        if (punches > 0) {
            Matrix4fc view = host.view().view();
            view.positiveZ(FORWARD).negate();
            view.positiveX(RIGHT);
            for (int k = punches - 1; k >= 0; k--) {
                int i = k * PUNCH_STRIDE;
                float since = (float) (now - PUNCH[i]);
                if (since >= CgShakeModel.PUNCH_SECONDS) {
                    int last = --punches;
                    if (k != last) System.arraycopy(PUNCH, last * PUNCH_STRIDE, PUNCH, i, PUNCH_STRIDE);
                    continue;
                }
                double ox = cx - PUNCH[i + 2], oy = cy - PUNCH[i + 3], oz = cz - PUNCH[i + 4];
                double d = Math.sqrt(ox * ox + oy * oy + oz * oz);
                double dx = PUNCH[i + 5], dy = PUNCH[i + 6], dz = PUNCH[i + 7];
                if (Double.isNaN(dx)) {
                    // Away from the point; straight back when the camera stands on it.
                    boolean on = d < 1e-3;
                    dx = on ? -FORWARD.x : ox / d;
                    dy = on ? -FORWARD.y : oy / d;
                    dz = on ? -FORWARD.z : oz / d;
                }
                float a = (float) PUNCH[i + 1] * CgShakeModel.falloff(d, (float) PUNCH[i + 8], (float) PUNCH[i + 9], 1f)
                        * CgShakeModel.punch(since) * screen;
                float ahead = (float) (dx * FORWARD.x + dy * FORWARD.y + dz * FORWARD.z);
                float side = (float) (dx * RIGHT.x + dy * RIGHT.y + dz * RIGHT.z);
                px += (float) dx * a * PUNCH_SHIFT;
                py += (float) dy * a * PUNCH_SHIFT;
                pz += (float) dz * a * PUNCH_SHIFT;
                // Shoved back, the head tips up (pitch is down-positive); shoved sideways, it turns and tilts with it.
                pPitch += ahead * a * PUNCH_ANGLE;
                pYaw += side * a * PUNCH_ANGLE;
                pRoll += side * a * PUNCH_ANGLE * 0.6f;
            }
        }
        CgPlatform.get(CgHostCamera.SERVICE).offset(
                px + SHIFT * s * bands(t, 3), py + SHIFT * s * bands(t, 4), pz + SHIFT * s * bands(t, 5),
                pYaw + YAW * s * bands(t, 0), pPitch + PITCH * s * bands(t, 1), pRoll + ROLL * s * bands(t * 0.7f, 2),
                1f + fov * fovEffects);
        // One more frame of zeros once everything has settled, so the host lets the camera go.
        moving = shake > 0f || kicks > 0 || punches > 0;
    }

    /** Fires each waiting play whose front has reached the camera, or has run its course. */
    private static void arrive(double now) {
        for (int k = waiting - 1; k >= 0; k--) {
            int i = k * WAIT_STRIDE;
            CgCameraShake shake = WAIT_SHAKE[k];
            double since = now - WAIT[i];
            float scale = (float) WAIT[i + 7];
            double front = shake.reach * scale * shake.ease.ease(Math.min(since / shake.arrival, 1.0));
            double ox = cx - WAIT[i + 1], oy = cy - WAIT[i + 2], oz = cz - WAIT[i + 3];
            if (since < shake.arrival && ox * ox + oy * oy + oz * oz > front * front) continue;
            fire(shake, WAIT[i + 1], WAIT[i + 2], WAIT[i + 3], WAIT[i + 4], WAIT[i + 5], WAIT[i + 6], scale);
            int last = --waiting;
            if (k != last) {
                System.arraycopy(WAIT, last * WAIT_STRIDE, WAIT, i, WAIT_STRIDE);
                WAIT_SHAKE[k] = WAIT_SHAKE[last];
            }
            WAIT_SHAKE[last] = null;
        }
    }

    /** The field of view's widening now, every kick summed by distance, each easing back over its seconds. */
    private static float kicks(double now) {
        float fov = 0f;
        for (int k = kicks - 1; k >= 0; k--) {
            int i = k * KICK_STRIDE;
            double t = (now - KICK[i]) / KICK[i + 1];
            if (t >= 1.0) {
                int last = --kicks;
                if (k != last) System.arraycopy(KICK, last * KICK_STRIDE, KICK, i, KICK_STRIDE);
                continue;
            }
            double ox = KICK[i + 3] - cx, oy = KICK[i + 4] - cy, oz = KICK[i + 5] - cz;
            float felt = CgShakeModel.falloff(Math.sqrt(ox * ox + oy * oy + oz * oz), (float) KICK[i + 6], (float) KICK[i + 7], 1f);
            fov += (float) (KICK[i + 2] * felt * (1.0 - t) * (1.0 - t));
        }
        return fov;
    }

    /** The shake's noise {@code t} seconds in: a slow lurch under a fast rattle. */
    private static float bands(float t, int channel) {
        return LURCH_SHARE * CgShakeModel.noise(t * LURCH, channel)
                + (1f - LURCH_SHARE) * CgShakeModel.noise(t * RATTLE, channel + 8);
    }
}
