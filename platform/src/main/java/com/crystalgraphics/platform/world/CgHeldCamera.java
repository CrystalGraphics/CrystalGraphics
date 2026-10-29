package com.crystalgraphics.platform.world;

/**
 * A host's held camera: the last pose {@link CgHostCamera#hold} gave and when, so each era's host keeps one state and
 * one watchdog. A host forwards {@code hold} and {@code release} here and asks {@link #live()} at its camera hook, once
 * it has fired {@link CgWorldBegin}: true while held, and false once a hold has gone unrefreshed, which is when the host
 * gives the camera back.
 *
 * <pre>{@code
 * private final CgHeldCamera held = new CgHeldCamera();
 *
 * public void hold(double x, double y, double z, float yaw, float pitch, float roll, float fov, int flags) {
 *     if (held.hold(x, y, z, yaw, pitch, roll, fov, flags)) take();   // true the first frame: save what it replaces
 * }
 * public void release() {
 *     if (held.release()) give();                                     // true once: restore what it saved
 * }
 * // the camera hook, after CgWorldBegin.fire
 * if (held.live()) apply(); else if (held.expired()) give();
 * }</pre>
 *
 * <ul>
 *   <li>The watchdog is time, judged only at a camera hook right after {@link CgWorldBegin}: core refreshes a hold
 *       every frame it renders, paused or not, so a hold {@link #TIMEOUT_NANOS} old there means core stopped (a stalled
 *       or crashed play) and the player is never stranded.</li>
 *   <li>Never ask {@link #live()} from a tick: after a slow frame the game runs several ticks with no frame between,
 *       and a hold would end that core is still refreshing. A tick reads {@link #held()}.</li>
 *   <li>Render thread only. Allocates nothing.</li>
 * </ul>
 */
public final class CgHeldCamera {

    /** How long a hold lasts unrefreshed: four frames at 16 fps. */
    public static final long TIMEOUT_NANOS = 250_000_000L;

    public double x, y, z;
    public float yaw, pitch, roll, fov;
    public int flags;
    private boolean held, expired;
    private long last;

    /** Core's pose for this frame; true when it takes the camera, the first frame of a hold. */
    public boolean hold(double x, double y, double z, float yaw, float pitch, float roll, float fov, int flags) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.roll = roll;
        this.fov = fov;
        this.flags = flags;
        last = System.nanoTime();
        boolean taking = !held;
        held = true;
        expired = false;
        return taking;
    }

    /** Core gave the camera back; true when it was held, so the host restores once. */
    public boolean release() {
        boolean was = held;
        held = false;
        return was;
    }

    /** Whether the camera is held now; a hold gone stale ends here, and {@link #expired()} says so once. */
    public boolean live() {
        if (held && System.nanoTime() - last > TIMEOUT_NANOS) {
            held = false;
            expired = true;
        }
        return held;
    }

    /** Whether {@link #live()} just ended a stale hold: true once, for the host to give the camera back. */
    public boolean expired() {
        boolean was = expired;
        expired = false;
        return was;
    }

    /** Whether a hold is on, without the watchdog: for a host's other hooks between camera hooks (the tick). */
    public boolean held() {
        return held;
    }

    public boolean hidesHud() {
        return (flags & CgHostCamera.HIDE_HUD) != 0;
    }

    public boolean hidesPlayer() {
        return (flags & CgHostCamera.HIDE_PLAYER) != 0;
    }
}
