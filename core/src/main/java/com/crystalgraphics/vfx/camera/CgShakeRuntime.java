package com.crystalgraphics.vfx.camera;

import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.stage.CgHostEnvironment;
import com.crystalgraphics.render.stage.CgHostFrame;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.world.CgCamera;
import java.util.Arrays;
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

    private static boolean installed, scheduled, moving;
    static boolean enabled = true;
    private static double lastFrame = Double.NaN, cx, cy, cz;
    private static final Vector3f ORIGIN = new Vector3f(), FORWARD = new Vector3f(), RIGHT = new Vector3f(), UP = new Vector3f();

    private static final int PLAYS = 64;
    private static final CgShakePlay[] PLAY = new CgShakePlay[PLAYS];
    private static int plays;
    private static final float[] RESULT = new float[CgShakePattern.CHANNELS], SUM = new float[CgShakePattern.CHANNELS + 1],
            HOST = new float[6];

    private CgShakeRuntime() {
    }

    static boolean installed() {
        return installed;
    }

    static void install() {
        if (installed) return;
        installed = true;
        schedule();
    }

    /** Sums shakes in the client world's {@code SHAKE} from now on: after every effect ticked, before the host takes its camera. */
    static void schedule() {
        if (scheduled) return;
        scheduled = true;
        CgWorld.client().schedule().add(CgTickGroup.SHAKE, "crystalgraphics:shake", dt -> {
            if (installed) frame(CgRenderStage.WORLD_OPAQUE.host());
        }).whilePaused(true);
    }

    /** Plays {@code play} from this frame until it ends; past {@value #PLAYS} at once, the oldest is ended. */
    static void add(CgShakePlay play) {
        install();
        if (plays == PLAYS) {
            PLAY[0].stop(true);
            PLAY[0].live = false;
            PLAY[0].source().give(PLAY[0]);
            System.arraycopy(PLAY, 1, PLAY, 0, --plays);
        }
        PLAY[plays++] = play;
    }

    /** The play of {@code source} running, or null: a single-instance shake restarts it. */
    static CgShakePlay playing(CgShakeSource source) {
        for (int k = 0; k < plays; k++) if (PLAY[k].source() == source) return PLAY[k];
        return null;
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
    private static void frame(CgHostFrame host) {
        double now = CgFrameClock.seconds();
        float dt = Double.isNaN(lastFrame) ? 0f : (float) Math.max(0.0, Math.min(now - lastFrame, 0.1));
        lastFrame = now;
        if (MODEL.still() && waiting == 0 && kicks == 0 && punches == 0 && plays == 0 && !moving) return;
        CgHostEnvironment world = host.environment();
        eye(host.view());
        arrive(now);
        float shake = MODEL.step(dt, cx, cy, cz);
        float screen = enabled ? (Float.isNaN(world.screenEffects()) ? 1f : world.screenEffects()) * CgGraphicsSettings.SHAKE.get() : 0f;
        float fovEffects = enabled ? (Float.isNaN(world.fovEffects()) ? 1f : world.fovEffects()) * CgGraphicsSettings.FOV_KICK.get() : 0f;
        float fov = kicks(now);
        float s = shake * screen;
        float px = 0f, py = 0f, pz = 0f, pYaw = 0f, pPitch = 0f, pRoll = 0f;
        Matrix4fc view = host.view().view();
        view.positiveZ(FORWARD).negate();
        view.positiveX(RIGHT);
        view.positiveY(UP);
        float fovScale = 1f;
        if (plays > 0) {
            patterns(dt, screen);
            px += SUM[0];
            py += SUM[1];
            pz += SUM[2];
            pYaw += SUM[3];
            pPitch += SUM[4];
            pRoll += SUM[5];
            fovScale = fovScale(host.view().projection(), SUM[6]);
        }
        if (punches > 0) {
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
        CgCamera.offset(
                px + SHIFT * s * bands(now, 3), py + SHIFT * s * bands(now, 4), pz + SHIFT * s * bands(now, 5),
                pYaw + YAW * s * bands(now, 0), pPitch + PITCH * s * bands(now, 1), pRoll + ROLL * s * bands(now * 0.7, 2),
                (1f + fov * fovEffects) * fovScale);
        // One more frame of zeros once everything has settled, so the host lets the camera go.
        moving = shake > 0f || kicks > 0 || punches > 0 || plays > 0;
    }

    /** Steps every pattern play and sums its offset, in the host's terms, into {@code SUM}; the last channel the FOV change. */
    private static void patterns(float dt, float screen) {
        Arrays.fill(SUM, 0f);
        for (int k = 0; k < plays; k++) {
            CgShakePlay play = PLAY[k];
            if (!play.step(dt, RESULT)) {
                System.arraycopy(PLAY, k + 1, PLAY, k, --plays - k);
                PLAY[plays] = null;
                k--;
                continue;
            }
            float w = screen;
            if (play.outer > 0f) {
                double ox = cx - play.x, oy = cy - play.y, oz = cz - play.z;
                w *= CgShakeModel.falloff(Math.sqrt(ox * ox + oy * oy + oz * oz), play.inner, play.outer, play.falloff);
            }
            if (w == 0f) continue;
            CgShakePlay.toHost(RESULT, play.world, play.source().axes(), FORWARD, RIGHT, UP, HOST);
            for (int c = 0; c < 6; c++) SUM[c] += HOST[c] * w;
            SUM[6] += RESULT[CgShakePattern.FOV] * w;
        }
    }

    /** An FOV change in horizontal degrees as Unreal's, as a multiplier of the host's vertical FOV. */
    static float fovScale(Matrix4fc projection, float degrees) {
        if (degrees == 0f) return 1f;
        double h = 2 * Math.atan(1 / projection.m00()), v = 2 * Math.atan(1 / projection.m11());
        double turned = Math.max(Math.toRadians(1), Math.min(Math.toRadians(179), h + Math.toRadians(degrees)));
        return (float) (2 * Math.atan(Math.tan(turned / 2) * projection.m00() / projection.m11()) / v);
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
    private static float bands(double t, int channel) {
        return LURCH_SHARE * CgShakeModel.noise(t * LURCH, channel)
                + (1f - LURCH_SHARE) * CgShakeModel.noise(t * RATTLE, channel + 8);
    }
}
