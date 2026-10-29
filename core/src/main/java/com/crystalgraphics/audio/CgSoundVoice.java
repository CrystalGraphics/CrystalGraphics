package com.crystalgraphics.audio;

/**
 * A resident sound playing: its 16-bit samples resampled linearly to the output's rate, as Unreal's source manager
 * reads a source's frames. Mono plays on every channel.
 *
 * <pre>{@code
 * CgAudioSource v = audio.play(click, 1f);    // the engine makes one for a resident sound
 * audio.stop(v);
 * }</pre>
 *
 * <ul>
 *   <li>One voice, one playthrough: make another to play the sound again.</li>
 *   <li>Looping, it wraps to its start; else it ends at its last frame.</li>
 * </ul>
 */
public final class CgSoundVoice implements CgAudioSource {

    private final short[] pcm;
    private final int channels;
    private final long frames;
    private final double step;
    private final boolean loop;
    private double pos;

    public CgSoundVoice(CgSound sound, int outputRate, float pitch, boolean loop) {
        if (sound.streamed()) throw new IllegalArgumentException(sound + " streams: play it through a CgSoundStream");
        this.pcm = sound.pcm();
        this.channels = sound.channels();
        this.frames = sound.frames();
        this.step = sound.sampleRate() / (double) outputRate * pitch;
        this.loop = loop;
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
