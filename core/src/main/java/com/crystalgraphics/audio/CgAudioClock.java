package com.crystalgraphics.audio;

import java.util.function.LongSupplier;

/**
 * The frame's audio time, what a sound's start is scheduled on: Quartz's clock as the game thread sees it. It
 * advances by each frame's step, so a sequence's time maps to audio time the same way on every frame whatever the
 * frame rate. Within a dead band of the mixer's write head it is left alone, since the head moves a buffer at a time;
 * past it, it is drawn gently back, so it never drifts from what is rendered.
 *
 * <pre>{@code
 * CgAudioClock clock = audio.clock();
 * audio.play(click, CgSoundClass.SFX, 1f, 1f, false, clock.now());         // as soon as it can, on its own sample
 * audio.play(click, CgSoundClass.SFX, 1f, 1f, false, clock.now() + 0.5);   // half a second later, to the sample
 * audio.play(click, CgSoundClass.SFX, 1f, 1f, false,
 *         clock.now() - sequencer.heardSecondsAgo());                      // where a sequence's event fell this frame
 * }</pre>
 *
 * <ul>
 *   <li>Frame thread only. {@link #now()} is this frame's, valid from the frame's {@code BEGIN}.</li>
 *   <li>A time maps to the mixer frame {@link #frameAt}, a lead past it, so a start lands on its sample ahead of what
 *       is rendered. An event heard at a frame's end fell up to a frame earlier: starts stay exact for frames shorter
 *       than the lead less the dead band (64 ms on the engine's), and a longer frame's play at once, counted late.</li>
 *   <li>A clock more than twice its dead band off the write head (a stream opened and filled, a stall) snaps back to
 *       it.</li>
 * </ul>
 */
public final class CgAudioClock {

    /** The share of its distance past the dead band it closes each frame. */
    private static final double SLEW = 0.01;

    private final int sampleRate;
    private final long leadFrames;
    private final double deadband;
    private final LongSupplier written;
    private double now = Double.NaN;

    /**
     * On a mixer at {@code sampleRate} whose frames written so far {@code written} answers: a start {@code leadFrames}
     * after its time, the clock left alone within {@code deadbandFrames} of the write head.
     */
    public CgAudioClock(int sampleRate, long leadFrames, long deadbandFrames, LongSupplier written) {
        this.sampleRate = sampleRate;
        this.leadFrames = leadFrames;
        this.deadband = deadbandFrames / (double) sampleRate;
        this.written = written;
    }

    /** Once a frame, by its step in seconds. */
    public void advance(double dt) {
        double head = written.getAsLong() / (double) sampleRate;
        if (Double.isNaN(now) || Math.abs(now + dt - head) > 2 * deadband) {
            now = head;
            return;
        }
        now += dt;
        double off = head - now;
        if (Math.abs(off) > deadband) now += (off - Math.copySign(deadband, off)) * SLEW;
    }

    /** This frame's audio time, in seconds. */
    public double now() {
        return Double.isNaN(now) ? 0 : now;
    }

    /** The mixer frame audio time {@code seconds} starts on. */
    public long frameAt(double seconds) {
        return Math.round(seconds * sampleRate) + leadFrames;
    }

    public int sampleRate() {
        return sampleRate;
    }
}
