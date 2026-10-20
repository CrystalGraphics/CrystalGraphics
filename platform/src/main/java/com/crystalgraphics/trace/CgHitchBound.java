package com.crystalgraphics.trace;

/**
 * Which side of the frame the time went to, after Unreal's {@code EFrameHitchType}: the larger of the frame thread's CPU
 * time and the GPU time, a tie going to the frame thread; {@link #UNKNOWN} when the frame has neither.
 */
public enum CgHitchBound {
    FRAME_THREAD,
    GPU,
    UNKNOWN;

    /** The word the report prints. */
    public String label() {
        return this == FRAME_THREAD ? "frame thread" : name().toLowerCase(java.util.Locale.ROOT);
    }
}
