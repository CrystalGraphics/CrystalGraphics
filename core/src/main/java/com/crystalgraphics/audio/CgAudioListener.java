package com.crystalgraphics.audio;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.crystalgraphics.camera.CgCameraPose;
import com.crystalgraphics.render.stage.CgHostView;

/**
 * Where the sounds are heard from: a position, a facing and a velocity, in the host's units and axes. Unreal's
 * {@code FListener}. The engine moves it each frame to the camera's final pose, or to the host's view where no camera
 * system runs, so a camera cut moves it on its frame.
 *
 * <pre>{@code
 * CgAudioListener ears = audio.listener();
 * float[] lr = new float[2];
 * ears.pan(x, y, z, lr);                         // the left and right gains of a sound at (x, y, z)
 * }</pre>
 *
 * A test or a host without a camera system places it by hand:
 *
 * <pre>{@code
 * ears.set(0, 64, 0, 0, 0, 1, 0, 1, 0, dt);     // at (0, 64, 0) looking +z, up +y
 * }</pre>
 *
 * <ul>
 *   <li>Frame thread only.</li>
 *   <li>Its velocity is its move over the frame's {@code dt}: a cut is a jump, so it reads as fast for that frame.
 *       Only doppler reads it, and doppler is opt-in.</li>
 * </ul>
 */
public final class CgAudioListener {

    /** Unreal's stereo speakers: front right at 30°, front left at 330°. */
    private static final double RIGHT_SPEAKER = 30, LEFT_SPEAKER = 330;

    private double x, y, z, vx, vy, vz;
    private double fx = 1, fy, fz, ux, uy = 1, uz, rx, ry, rz = 1;
    private boolean placed;
    private final Vector3f axis = new Vector3f();

    /** Moves it to {@code pose}: its turn of +x is the facing, of +y the up. */
    public CgAudioListener set(CgCameraPose pose, double dt) {
        Quaternionf q = pose.rotation();
        q.transform(axis.set(1, 0, 0));
        double nfx = axis.x, nfy = axis.y, nfz = axis.z;
        q.transform(axis.set(0, 1, 0));
        return set(pose.location().x, pose.location().y, pose.location().z, nfx, nfy, nfz, axis.x, axis.y, axis.z, dt);
    }

    /** Moves it to where the host drew {@code view} from: forward is the view's -z, up its +y. */
    public CgAudioListener set(CgHostView view, double dt) {
        view.view().positiveZ(axis);
        double nfx = -axis.x, nfy = -axis.y, nfz = -axis.z;
        view.view().positiveY(axis);
        return set(view.x(), view.y(), view.z(), nfx, nfy, nfz, axis.x, axis.y, axis.z, dt);
    }

    /** Moves it to {@code (px, py, pz)}, facing {@code f} with up {@code u}, both unit length. */
    public CgAudioListener set(double px, double py, double pz, double ffx, double ffy, double ffz,
            double uux, double uuy, double uuz, double dt) {
        if (placed && dt > 0) {
            vx = (px - x) / dt;
            vy = (py - y) / dt;
            vz = (pz - z) / dt;
        } else {
            vx = vy = vz = 0;
        }
        x = px;
        y = py;
        z = pz;
        fx = ffx;
        fy = ffy;
        fz = ffz;
        ux = uux;
        uy = uuy;
        uz = uuz;
        // Right-handed axes: right is forward x up.
        rx = fy * uz - fz * uy;
        ry = fz * ux - fx * uz;
        rz = fx * uy - fy * ux;
        placed = true;
        return this;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public double vx() {
        return vx;
    }

    public double vy() {
        return vy;
    }

    public double vz() {
        return vz;
    }

    /**
     * The azimuth of a sound at {@code (sx, sy, sz)}: 0 ahead, 90 to the right, 180 behind, in [0, 360)
     * ({@code GetAzimuth}'s absolute azimuth).
     */
    public double azimuth(double sx, double sy, double sz) {
        double dx = sx - x, dy = sy - y, dz = sz - z;
        double ahead = dx * fx + dy * fy + dz * fz, right = dx * rx + dy * ry + dz * rz;
        if (ahead == 0 && right == 0) return 0;
        double a = Math.toDegrees(Math.atan2(right, ahead));
        return a < 0 ? a + 360 : a;
    }

    /**
     * Writes into {@code lr} the left and right gains of a sound at {@code (sx, sy, sz)}: equal power between the two
     * speakers around it ({@code Get3DChannelMap}). Behind is panned as between the speakers the long way round.
     */
    public void pan(double sx, double sy, double sz, float[] lr) {
        double az = azimuth(sx, sy, sz);
        double fraction;
        boolean towardRight;
        if (az <= RIGHT_SPEAKER) {
            fraction = (az + 360 - LEFT_SPEAKER) / (RIGHT_SPEAKER + 360 - LEFT_SPEAKER);
            towardRight = true;
        } else if (az <= LEFT_SPEAKER) {
            fraction = (az - RIGHT_SPEAKER) / (LEFT_SPEAKER - RIGHT_SPEAKER);
            towardRight = false;
        } else {
            fraction = (az - LEFT_SPEAKER) / (RIGHT_SPEAKER + 360 - LEFT_SPEAKER);
            towardRight = true;
        }
        float next = (float) Math.min(1, Math.max(0, Math.sin(fraction * 0.5 * Math.PI)));
        float prev = (float) Math.min(1, Math.max(0, Math.cos(fraction * 0.5 * Math.PI)));
        lr[0] = towardRight ? prev : next;
        lr[1] = towardRight ? next : prev;
    }

    /**
     * The doppler pitch of a sound at {@code (sx, sy, sz)} moving at {@code (svx, svy, svz)} blocks a second, at
     * {@code intensity} ({@code GetDopplerPitchMultiplier}).
     */
    public float doppler(double sx, double sy, double sz, double svx, double svy, double svz, float intensity) {
        if (intensity == 0) return 1f;
        double dx = x - sx, dy = y - sy, dz = z - sz;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6) return 1f;
        dx /= len;
        dy /= len;
        dz /= len;
        double sourceToward = svx * dx + svy * dy + svz * dz, listenerAway = vx * dx + vy * dy + vz * dz;
        double inv = 1 - (sourceToward - listenerAway) / CgSoundAttenuation.SPEED_OF_SOUND;
        double scale = 1 / Math.max(inv, 0.05);
        return (float) ((scale - 1) * intensity + 1);
    }
}
