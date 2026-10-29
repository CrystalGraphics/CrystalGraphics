package com.crystalgraphics.audio;

/**
 * A one-pole low-pass: what muffles a submix (a sound behind a wall, under water). Unreal's
 * {@code Audio::FOnePoleLPF}, per channel.
 *
 * <pre>{@code
 * CgLowPass muffle = new CgLowPass(800);
 * audio.submix("world").effect(muffle);
 * muffle.cutoff(20000);                       // open again: any thread, it takes the next buffer
 * }</pre>
 *
 * <ul>
 *   <li>The cutoff is in hertz; at or above half the output's rate it passes everything.</li>
 *   <li>Up to 8 channels.</li>
 * </ul>
 */
public final class CgLowPass implements CgSubmixEffect {

    private volatile float cutoff;
    private final float[] memory = new float[8];

    public CgLowPass(float cutoffHz) {
        cutoff = cutoffHz;
    }

    public CgLowPass cutoff(float hz) {
        cutoff = hz;
        return this;
    }

    public float cutoff() {
        return cutoff;
    }

    @Override
    public void process(float[] buffer, int frames, int channels, int sampleRate) {
        float hz = cutoff;
        if (hz >= sampleRate * 0.5f) return;
        // FOnePoleLPF: a = exp(-2 pi fc / fs), y = (1 - a) x + a y'
        float a = (float) Math.exp(-2 * Math.PI * hz / sampleRate);
        for (int c = 0; c < channels; c++) {
            float y = memory[c];
            for (int f = 0; f < frames; f++) {
                int i = f * channels + c;
                y = (1 - a) * buffer[i] + a * y;
                buffer[i] = y;
            }
            memory[c] = y;
        }
    }
}
