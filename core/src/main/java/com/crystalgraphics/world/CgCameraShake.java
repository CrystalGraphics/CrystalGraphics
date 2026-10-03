package com.crystalgraphics.world;

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
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * Shakes the host's camera, and kicks its field of view, for every effect at once, on the trauma model: an impact adds
 * trauma 0..1, felt fully within an inner radius of it and not at all past an outer one; trauma decays at a fixed rate;
 * the camera turns and moves by trauma squared through smooth noise, a slow lurch under a fast rattle. So hits stack and
 * settle on their own, and a small one barely registers while a big one rocks the view. A punch is the hit's direction:
 * the camera shoved away from a point, or along a direction, springing back past rest. Scaled by the player's Screen Effects and FOV Effects, then by
 * CrystalGraphics' own comfort settings ({@link CgGraphicsSettings#SHAKE}, {@link CgGraphicsSettings#FOV_KICK}), which
 * can only lower it.
 *
 * <pre>{@code
 * CgCameraShake.shake(x, y, z, 1f, 12f, 60f);   // a blast: full trauma within 12 blocks, none past 60
 * CgCameraShake.kick(0.12f, 0.3f);              // the field of view widened 12% at once, easing back over 0.3 s
 * CgCameraShake.kick(x, y, z, 0.12f, 0.3f, 12f, 60f);   // the same, felt by distance like a shake
 * CgCameraShake.punch(x, y, z, 1f, 12f, 60f);   // shoved away from the blast, felt by distance
 * CgCameraShake.tremor(x, y, z, 0.8f, 4f, 12f, 60f);   // then the ground shaking on, tapering over 4 s
 * CgCameraShake.punch(x, y, z, -aimX, -aimY, -aimZ, 0.6f, 3f, 30f);   // a recoil, back along the aim
 *
 * // a held tremor: set its level every tick while it lasts
 * CgCameraShake.Rumble charge = CgCameraShake.rumble().radii(4f, 24f);
 * charge.at(x, y, z).level(0.3f * progress);
 * charge.close();                               // or stop setting it: it lapses within a quarter second
 * }</pre>
 *
 * <ul>
 *   <li>Trauma 1 turns the camera up to about ten degrees and moves it up to a quarter of a block; trauma 0.3 is a
 *       tenth of that. A punch of 1 turns it five degrees and shoves it a third of a block at its peak.</li>
 *   <li>Where the camera stands is taken on the frame after the call, so an impact may be added from any tick.</li>
 *   <li>It does nothing where the host fills no camera slot: the harness. Render thread only; a shake allocates
 *       nothing.</li>
 * </ul>
 */
public final class CgCameraShake {

    /** Trauma lost a second: a full shake settles in a little over a second. */
    public static final float DECAY = CgShakeModel.DECAY;

    private static final float YAW = 10f, PITCH = 10f, ROLL = 7f, SHIFT = 0.25f;
    /** Noise cycles a second, and the slow band's share: a lurch under a tremble. */
    private static final float LURCH = 4f, RATTLE = 16f, LURCH_SHARE = 0.6f;
    /** A punch of 1 at its peak: degrees, and blocks. */
    private static final float PUNCH_ANGLE = 5f, PUNCH_SHIFT = 0.35f;
    private static final int KICKS = 8, PUNCHES = 8;

    private static final CgShakeModel MODEL = new CgShakeModel(CgFrameClock::seconds);
    private static final int KICK_STRIDE = 8;
    // Per kick: start time, duration, amount, x, y, z, inner, outer; an outer radius of 0 is felt everywhere.
    private static final double[] KICK = new double[KICKS * KICK_STRIDE];
    private static int kicks;
    private static final int PUNCH_STRIDE = 10;
    // Per punch: start time, strength, x, y, z, direction (NaN: away from the point), inner, outer.
    private static final double[] PUNCH = new double[PUNCHES * PUNCH_STRIDE];
    private static int punches;
    private static boolean installed, moving;
    private static double lastFrame = Double.NaN;
    private static final Vector3d CAMERA = new Vector3d();
    private static final Vector3f ORIGIN = new Vector3f(), FORWARD = new Vector3f(), RIGHT = new Vector3f();

    private CgCameraShake() {
    }

    /** An impact at the point: {@code trauma} 0..1 within {@code inner} blocks of it, fading linearly to none at {@code outer}. */
    public static void shake(double x, double y, double z, float trauma, float inner, float outer) {
        shake(x, y, z, trauma, inner, outer, 1f);
    }

    /** As {@link #shake(double, double, double, float, float, float)}, the fade between the radii raised to {@code exponent}. */
    public static void shake(double x, double y, double z, float trauma, float inner, float outer, float exponent) {
        install();
        MODEL.add(x, y, z, trauma, inner, Math.max(outer, inner + 1e-3f), exponent);
    }

    /**
     * Trauma held from now and tapering to none over {@code seconds}, felt fully within {@code inner} blocks of the point
     * and not at all past {@code outer}: the ground still shaking after a blast, with nothing to keep it going.
     */
    public static void tremor(double x, double y, double z, float trauma, float seconds, float inner, float outer) {
        install();
        MODEL.tremor(x, y, z, trauma, seconds, inner, Math.max(outer, inner + 1e-3f));
    }

    /** A held tremor: trauma added while its level is set, every tick, until it is closed or left alone. */
    public static Rumble rumble() {
        install();
        return new Rumble(MODEL);
    }

    /** The field of view widened by {@code amount} (0.1 is 10%) at once, easing back over {@code seconds}. */
    public static void kick(float amount, float seconds) {
        kick(0.0, 0.0, 0.0, amount, seconds, 0f, 0f);
    }

    /** As {@link #kick(float, float)}, felt fully within {@code inner} blocks of the point and not at all past {@code outer}. */
    public static void kick(double x, double y, double z, float amount, float seconds, float inner, float outer) {
        install();
        int i = (kicks < KICKS ? kicks++ : 0) * KICK_STRIDE;
        KICK[i] = CgFrameClock.seconds();
        KICK[i + 1] = Math.max(seconds, 1e-3f);
        KICK[i + 2] = amount;
        KICK[i + 3] = x;
        KICK[i + 4] = y;
        KICK[i + 5] = z;
        KICK[i + 6] = inner;
        KICK[i + 7] = outer > 0f ? Math.max(outer, inner + 1e-3f) : 0f;
    }

    /** The camera shoved away from the point by {@code strength} (1 at full) within {@code inner} blocks, none past {@code outer}. */
    public static void punch(double x, double y, double z, float strength, float inner, float outer) {
        punch(x, y, z, Double.NaN, 0.0, 0.0, strength, inner, outer);
    }

    /** As {@link #punch(double, double, double, float, float, float)}, shoved along {@code (dx, dy, dz)} instead. */
    public static void punch(double x, double y, double z, double dx, double dy, double dz, float strength, float inner, float outer) {
        install();
        int i = (punches < PUNCHES ? punches++ : 0) * PUNCH_STRIDE;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        boolean away = Double.isNaN(dx) || length < 1e-6;
        PUNCH[i] = CgFrameClock.seconds();
        PUNCH[i + 1] = strength;
        PUNCH[i + 2] = x;
        PUNCH[i + 3] = y;
        PUNCH[i + 4] = z;
        PUNCH[i + 5] = away ? Double.NaN : dx / length;
        PUNCH[i + 6] = away ? 0.0 : dy / length;
        PUNCH[i + 7] = away ? 0.0 : dz / length;
        PUNCH[i + 8] = inner;
        PUNCH[i + 9] = Math.max(outer, inner + 1e-3f);
    }

    /** Whether anything has shaken: from then this owns the host camera's offset, and writes it every frame. */
    public static boolean active() {
        return installed;
    }

    /**
     * Where the world's camera stands as of its latest frame, in absolute coordinates: what a shake's distance is
     * measured from. Render thread only.
     */
    public static Vector3d camera(Vector3d out) {
        return eye(CgRenderStage.WORLD_OPAQUE.host().view(), out);
    }

    /** The view's position plus wherever its matrix puts the eye: nothing more in a camera-relative host. */
    private static Vector3d eye(CgHostView view, Vector3d out) {
        view.view().originAffine(ORIGIN);
        return out.set(view.x() + ORIGIN.x, view.y() + ORIGIN.y, view.z() + ORIGIN.z);
    }

    /** The decaying trauma now, 0..1, without the rumbles. */
    public static float trauma() {
        return MODEL.trauma();
    }

    private static void install() {
        if (installed) return;
        installed = true;
        CgRenderStage.WORLD_OPAQUE.register(Integer.MIN_VALUE, CgCameraShake::frame);
    }

    /** Steps the trauma against this frame's camera and hands the offset to the host for its next frame. */
    private static void frame(CgStageFrame stage) {
        double now = CgFrameClock.seconds();
        float dt = Double.isNaN(lastFrame) ? 0f : (float) Math.max(0.0, Math.min(now - lastFrame, 0.1));
        lastFrame = now;
        if (MODEL.still() && kicks == 0 && punches == 0 && !moving) return;
        CgHostFrame host = stage.host();
        CgHostEnvironment world = host.environment();
        eye(host.view(), CAMERA);
        double cx = CAMERA.x, cy = CAMERA.y, cz = CAMERA.z;
        float shake = MODEL.step(dt, cx, cy, cz);
        float fov = 0f;
        for (int k = kicks - 1; k >= 0; k--) {
            int i = k * KICK_STRIDE;
            double t = (now - KICK[i]) / KICK[i + 1];
            if (t >= 1.0) {
                int last = --kicks;
                if (k != last) System.arraycopy(KICK, last * KICK_STRIDE, KICK, i, KICK_STRIDE);
                continue;
            }
            float felt = KICK[i + 7] == 0.0 ? 1f : CgShakeModel.falloff(Math.sqrt((KICK[i + 3] - cx) * (KICK[i + 3] - cx)
                    + (KICK[i + 4] - cy) * (KICK[i + 4] - cy) + (KICK[i + 5] - cz) * (KICK[i + 5] - cz)),
                    (float) KICK[i + 6], (float) KICK[i + 7], 1f);
            fov += (float) (KICK[i + 2] * felt * (1.0 - t) * (1.0 - t));
        }
        float screen = (Float.isNaN(world.screenEffects()) ? 1f : world.screenEffects()) * CgGraphicsSettings.SHAKE.get();
        float fovEffects = (Float.isNaN(world.fovEffects()) ? 1f : world.fovEffects()) * CgGraphicsSettings.FOV_KICK.get();
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

    /** The shake's noise {@code t} seconds in: a slow lurch under a fast rattle. */
    private static float bands(float t, int channel) {
        return LURCH_SHARE * CgShakeModel.noise(t * LURCH, channel)
                + (1f - LURCH_SHARE) * CgShakeModel.noise(t * RATTLE, channel + 8);
    }

    /** A held tremor from {@link #rumble()}: a place, radii, and a level its owner sets every tick. */
    public static final class Rumble {

        private final CgShakeModel model;
        double x, y, z, touched;
        float level, inner = 4f, outer = 24f;
        boolean closed;

        Rumble(CgShakeModel model) {
            this.model = model;
        }

        public Rumble at(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        /** Full within {@code inner} blocks, none past {@code outer}. */
        public Rumble radii(float inner, float outer) {
            this.inner = inner;
            this.outer = Math.max(outer, inner + 1e-3f);
            return this;
        }

        /** The trauma it adds while held, 0..1. Call it every tick: unset for a quarter second, it lapses. */
        public Rumble level(float level) {
            this.level = level;
            this.touched = model.now();
            this.closed = false;
            model.hold(this);
            return this;
        }

        /** Stops it now. */
        public void close() {
            closed = true;
        }
    }
}
