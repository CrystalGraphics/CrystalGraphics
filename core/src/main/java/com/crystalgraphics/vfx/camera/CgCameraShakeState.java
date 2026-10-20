package com.crystalgraphics.vfx.camera;

/**
 * A shake's blend in and out over its duration, Unreal's {@code FCameraShakeState}
 * (Engine/Source/Runtime/Engine/Private/Camera/CameraShakeBase.cpp): a linear weight up over {@code blendIn}, down over
 * {@code blendOut} before the duration ends or after {@link #stop}, the pattern's sample times it.
 *
 * <pre>{@code
 * CgCameraShakeState state = new CgCameraShakeState(0.2f, 0.5f, 2f);   // blend in, blend out, duration (0: endless)
 * state.start();
 * float weight = state.update(dt);                                     // 0 once it has ended
 * if (!state.playing()) remove(shake);
 * }</pre>
 *
 * <ul>
 *   <li>A duration of 0 or less never ends by itself: {@link #stop} blends it out, or ends it at once with no blend out.</li>
 *   <li>Unreal's restart that picks up a previous state's blend out is not ported.</li>
 * </ul>
 */
public final class CgCameraShakeState {

    private final float blendIn, blendOut, duration;
    private final boolean hasBlendIn, hasBlendOut;
    private float elapsed, blendInTime, blendOutTime;
    private boolean blendingIn, blendingOut, playing;

    public CgCameraShakeState(float blendIn, float blendOut, float duration) {
        this.blendIn = blendIn;
        this.blendOut = blendOut;
        this.duration = duration;
        hasBlendIn = blendIn > 0f;
        hasBlendOut = blendOut > 0f;
    }

    /** Starts playing from its beginning. */
    public CgCameraShakeState start() {
        elapsed = blendInTime = blendOutTime = 0f;
        blendingIn = hasBlendIn;
        blendingOut = false;
        playing = true;
        return this;
    }

    /** Advances {@code dt} seconds; the weight to scale the pattern's sample by, 0 once it has ended. */
    public float update(float dt) {
        float weight = 1f;
        elapsed += dt;
        if (blendingIn) blendInTime += dt;
        if (blendingOut) blendOutTime += dt;
        if (duration > 0f) {
            if (elapsed < 0f || elapsed >= duration) {
                playing = false;
                return 0f;
            }
            float remaining = duration - elapsed;
            if (hasBlendOut && !blendingOut && remaining < blendOut) {
                blendingOut = true;
                blendOutTime = blendOut - remaining;
            }
        }
        if (hasBlendIn && blendingIn) {
            if (blendInTime < blendIn) {
                weight *= blendInTime / blendIn;
            } else {
                blendingIn = false;
                blendInTime = blendIn;
            }
        }
        if (hasBlendOut && blendingOut) {
            if (blendOutTime < blendOut) {
                weight *= 1f - blendOutTime / blendOut;
            } else {
                blendingOut = false;
                blendOutTime = blendOut;
                playing = false;
                return 0f;
            }
        }
        return weight;
    }

    /** From the beginning straight to {@code time}: Unreal's scrub. */
    public float scrub(float time) {
        start();
        return update(time);
    }

    /** Blends out from now, or ends at once when asked or when it has no blend out. */
    public void stop(boolean immediately) {
        if (immediately || !hasBlendOut) {
            playing = false;
        } else if (!blendingOut) {
            blendingOut = true;
            blendOutTime = 0f;
        }
    }

    public boolean playing() {
        return playing;
    }
}
