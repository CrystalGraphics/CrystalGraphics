package com.crystalgraphics.audio;

import java.nio.ByteBuffer;

import javax.annotation.Nullable;

/**
 * A loaded sound: Unreal's {@code USoundWave} as played. A short one is decoded once and kept as 16-bit PCM; a long one
 * keeps its compressed bytes and is streamed, decoded ahead of the voice playing it. Shared by every voice that plays
 * it.
 *
 * <pre>{@code
 * CgSound boom = audio.sounds().load("mymod:sounds/boom.ogg");          // short: resident
 * CgSound theme = audio.sounds().load("mymod:sounds/theme.ogg", true);  // streamed
 * audio.play(boom, 1f);
 * }</pre>
 *
 * <ul>
 *   <li>Immutable once loaded; any thread may hold it.</li>
 *   <li>Its rate is the file's: a voice resamples it to the output's.</li>
 * </ul>
 */
public final class CgSound {

    private final String id;
    private final int channels, sampleRate;
    private final long frames;
    @Nullable
    private final short[] pcm;
    @Nullable
    private final ByteBuffer ogg;

    CgSound(String id, int channels, int sampleRate, long frames, @Nullable short[] pcm, @Nullable ByteBuffer ogg) {
        this.id = id;
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.frames = frames;
        this.pcm = pcm;
        this.ogg = ogg;
    }

    public String id() {
        return id;
    }

    public int channels() {
        return channels;
    }

    public int sampleRate() {
        return sampleRate;
    }

    /** Its length in frames, at its own rate. */
    public long frames() {
        return frames;
    }

    public double seconds() {
        return frames / (double) sampleRate;
    }

    /** Whether it streams from its compressed bytes, rather than playing from memory. */
    public boolean streamed() {
        return ogg != null;
    }

    /** Its interleaved 16-bit samples, resident; null where it streams. */
    @Nullable
    short[] pcm() {
        return pcm;
    }

    /** Its compressed bytes, where it streams. */
    @Nullable
    ByteBuffer ogg() {
        return ogg;
    }

    @Override
    public String toString() {
        return "CgSound[" + id + (streamed() ? ", streamed" : "") + "]";
    }
}
