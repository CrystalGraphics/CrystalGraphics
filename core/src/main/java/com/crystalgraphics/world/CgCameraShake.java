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
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * Shakes the host's camera, and kicks its field of view, for every effect at once, on the trauma model: an impact adds
 * trauma 0..1, felt fully within an inner radius of it and not at all past an outer one; trauma decays at a fixed rate;
 * the camera turns and moves by trauma squared through smooth noise. So hits stack and settle on their own, and a small
 * one barely registers while a big one rocks the view. Scaled by the player's Screen Effects and FOV Effects, then by
 * CrystalGraphics' own comfort settings ({@link CgGraphicsSettings#SHAKE}, {@link CgGraphicsSettings#FOV_KICK}), which
 * can only lower it.
 *
 * <pre>{@code
 * CgCameraShake.shake(x, y, z, 1f, 12f, 60f);   // a blast: full trauma within 12 blocks, none past 60
 * CgCameraShake.kick(0.12f, 0.3f);              // the field of view widened 12% at once, easing back over 0.3 s
 * CgCameraShake.kick(x, y, z, 0.12f, 0.3f, 12f, 60f);   // the same, felt by distance like a shake
 *
 * // a held tremor: set its level every tick while it lasts
 * CgCameraShake.Rumble charge = CgCameraShake.rumble().radii(4f, 24f);
 * charge.at(x, y, z).level(0.3f * progress);
 * charge.close();                               // or stop setting it: it lapses within a quarter second
 * }</pre>
 *
 * <ul>
 *   <li>Trauma 1 turns the camera up to about three degrees and moves it a few hundredths of a block; trauma 0.3 is a
 *       tenth of that.</li>
 *   <li>Where the camera stands is taken on the frame after the call, so an impact may be added from any tick.</li>
 *   <li>It does nothing where the host fills no camera slot: the harness. Render thread only; a shake allocates
 *       nothing.</li>
 * </ul>
 */
public final class CgCameraShake {

    /** Trauma lost a second: a full shake settles in a little over a second. */
    public static final float DECAY = CgShakeModel.DECAY;

    private static final float YAW = 3f, PITCH = 3f, ROLL = 2.5f, SHIFT = 0.05f;
    /** Noise cycles a second: a tremble, not a wobble. */
    private static final float FREQUENCY = 14f;
    private static final int KICKS = 8;

    private static final CgShakeModel MODEL = new CgShakeModel(CgFrameClock::seconds);
    private static final int KICK_STRIDE = 8;
    // Per kick: start time, duration, amount, x, y, z, inner, outer; an outer radius of 0 is felt everywhere.
    private static final double[] KICK = new double[KICKS * KICK_STRIDE];
    private static int kicks;
    private static boolean installed, moving;
    private static double lastFrame = Double.NaN;
    private static final Vector3d CAMERA = new Vector3d();
    private static final Vector3f ORIGIN = new Vector3f();

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
        if (MODEL.still() && kicks == 0 && !moving) return;
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
        float s = shake * screen, t = (float) (now * FREQUENCY);
        CgPlatform.get(CgHostCamera.SERVICE).offset(
                SHIFT * s * CgShakeModel.noise(t, 3), SHIFT * s * CgShakeModel.noise(t, 4), SHIFT * s * CgShakeModel.noise(t, 5),
                YAW * s * CgShakeModel.noise(t, 0), PITCH * s * CgShakeModel.noise(t, 1), ROLL * s * CgShakeModel.noise(t * 0.7f, 2),
                1f + fov * fovEffects);
        // One more frame of zeros once everything has settled, so the host lets the camera go.
        moving = shake > 0f || kicks > 0;
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
