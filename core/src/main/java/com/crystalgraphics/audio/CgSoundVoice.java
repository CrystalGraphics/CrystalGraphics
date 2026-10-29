package com.crystalgraphics.audio;

/**
 * A resident sound playing: its 16-bit samples resampled linearly to the output's rate, as Unreal's source manager
 * reads a source's frames. Mono plays on every channel.
 *
 * <pre>{@code
 * long id = audio.play(click, CgSoundClass.SFX, 1f);   // the engine gives a resident sound a pooled one
 * audio.activeSounds().stop(id);
 * }</pre>
 *
 * <ul>
 *   <li>One voice, one playthrough; the engine pools them, {@link #reset} making one play again once
 *       {@link #idle()}.</li>
 *   <li>Looping, it wraps to its start; else it ends at its last frame.</li>
 * </ul>
 */
public final class CgSoundVoice implements CgAudioSource {

    private short[] pcm;
    private int channels;
    private long frames;
    private double step;
    private boolean loop;
    private double pos;
    /** Off the audio thread's hands: never started, or ended there. */
    private volatile boolean idle = true;

    /** An idle voice, for a pool. */
    public CgSoundVoice() {
    }

    public CgSoundVoice(CgSound sound, int outputRate, float pitch, boolean loop) {
        reset(sound, outputRate, pitch, loop, 0);
    }

    /** Plays {@code sound} from {@code offset} seconds in. Only while {@link #idle()}. */
    public CgSoundVoice reset(CgSound sound, int outputRate, float pitch, boolean loop, double offset) {
        if (sound.streamed()) throw new IllegalArgumentException(sound + " streams: play it through a CgSoundStream");
        this.pcm = sound.pcm();
        this.channels = sound.channels();
        this.frames = sound.frames();
        this.step = sound.sampleRate() / (double) outputRate * pitch;
        this.loop = loop;
        this.pos = offset * sound.sampleRate();
        idle = false;
        return this;
    }

    /** Whether the audio thread is done with it: never started, or ended. */
    public boolean idle() {
        return idle;
    }

    @Override
    public void ended() {
        idle = true;
    }

    /** Its place, in the sound's frames. */
    public double position() {
        return pos;
    }

    @Override
    public boolean render(float[] out, int offset, int count, int outChannels, int sampleRate, float gain) {
        float scale = gain / 32768f;
        for (int f = offset; f < offset + count; f++) {
            long i = (long) pos;
            if (i + 1 >= frames) {
                if (!loop || frames < 2) return false;
                pos -= frames - 1;
                i = (long) pos;
            }
            float t = (float) (pos - i);
            int a = (int) i * channels, b = a + channels;
            for (int c = 0; c < outChannels; c++) {
                int sc = c % channels;
                out[f * outChannels + c] += (pcm[a + sc] + (pcm[b + sc] - pcm[a + sc]) * t) * scale;
            }
            pos += step;
        }
        return true;
    }
}
