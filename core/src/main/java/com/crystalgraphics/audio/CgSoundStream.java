package com.crystalgraphics.audio;

import com.crystalgraphics.platform.audio.CgAudioDecoder;

/**
 * A streamed sound playing: a ring of decoded frames the engine's decode thread keeps ahead of the voice, resampled
 * linearly to the output's rate. Unreal's real-time source: buffers decoded on an audio task while the voice plays the
 * ones before, never on the game thread.
 *
 * <pre>{@code
 * CgAudioSource theme = audio.play(sounds.load("mymod:sounds/theme.ogg", true), 1f);
 * }</pre>
 *
 * <ul>
 *   <li>Its first quarter second is decoded as it is made, on the caller's thread, so it starts on its sample.</li>
 *   <li>A ring that runs dry plays silence where it has no frames, and counts it ({@link #starved()}).</li>
 *   <li>Looping, the decode thread seeks it back to its start at its end.</li>
 * </ul>
 */
public final class CgSoundStream implements CgAudioSource {

    /** The ring's length, in seconds of the sound. */
    static final double RING_SECONDS = 1.0;
    /** Decoded as it is made: what plays while the decode thread takes over. */
    private static final double PRIME_SECONDS = 0.25;

    private final CgAudioDecoder.Stream decoder;
    private final int channels;
    private final int capacity;
    private final float[] ring;
    private final double step;
    private final boolean loop;
    /** Frames decoded so far, written by the decode thread; the voice's place, by the audio thread. */
    private volatile long written;
    private volatile boolean ended, done;
    /** The voice's frame, published each buffer for the decode thread. */
    private volatile long consumed;
    private double pos;
    private int starved;

    CgSoundStream(CgSound sound, CgAudioDecoder decoder, int outputRate, float pitch, boolean loop) {
        this.decoder = decoder.open(sound.ogg().duplicate());
        if (this.decoder == null) throw new IllegalStateException(sound + ": its bytes no longer decode");
        channels = this.decoder.channels();
        capacity = (int) (RING_SECONDS * this.decoder.sampleRate());
        ring = new float[capacity * channels];
        step = this.decoder.sampleRate() / (double) outputRate * pitch;
        this.loop = loop;
        fill((int) (PRIME_SECONDS * this.decoder.sampleRate()));
    }

    /** The decode thread's: decodes into the ring as far as the voice allows, at most {@code limit} frames. */
    void fill(int limit) {
        if (ended || done) return;
        long free = capacity - (written - consumed) - 2;
        int want = (int) Math.min(free, limit);
        while (want > 0) {
            int at = (int) (written % capacity);
            int n = decoder.read(ring, at, Math.min(want, capacity - at));
            if (n <= 0) {
                if (!loop) {
                    ended = true;
                    return;
                }
                decoder.seek(0);
                continue;
            }
            written += n;
            want -= n;
        }
    }

    /** Whether the decode thread should come back to it. */
    boolean needs() {
        return !ended && !done && capacity - (written - consumed) > capacity / 2;
    }

    /** Decodes no more: the voice plays out what its ring holds, then ends. */
    void stopDecoding() {
        ended = true;
    }

    boolean done() {
        return done;
    }

    void close() {
        decoder.close();
    }

    @Override
    public boolean render(float[] out, int offset, int count, int outChannels, int sampleRate, float gain) {
        long have = written;
        boolean dry = false;
        for (int f = offset; f < offset + count; f++) {
            long i = (long) pos;
            if (i + 1 >= have) {
                if (ended) return false;
                dry = true;
                break;
            }
            float t = (float) (pos - i);
            int a = (int) (i % capacity) * channels, b = (int) ((i + 1) % capacity) * channels;
            for (int c = 0; c < outChannels; c++) {
                int sc = c % channels;
                out[f * outChannels + c] += (ring[a + sc] + (ring[b + sc] - ring[a + sc]) * t) * gain;
            }
            pos += step;
        }
        consumed = (long) pos;
        if (dry) starved++;
        return true;
    }

    @Override
    public void ended() {
        done = true;
    }

    /** Buffers in which its ring ran dry. */
    public int starved() {
        return starved;
    }

    /** Seconds of it played. */
    public double played() {
        return consumed / (double) decoder.sampleRate();
    }
}
