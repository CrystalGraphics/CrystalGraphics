package com.crystalgraphics.audio;

/**
 * A sine tone, on every channel: the test signal that shows an output plays.
 *
 * <pre>{@code
 * engine.play(new CgAudioTone(440, 0.25f, 1.0), 1f);     // A4, a quarter of full scale, for a second
 * engine.play(new CgAudioTone(440, 0.25f, 0), 1f);       // until stopped
 * }</pre>
 *
 * <ul>
 *   <li>One instance a voice: it keeps its phase.</li>
 *   <li>A duration of 0 or less plays until stopped.</li>
 * </ul>
 */
public final class CgAudioTone implements CgAudioSource {

    private final double hertz;
    private final float amplitude;
    private final double seconds;
    private double phase;
    private long played;

    public CgAudioTone(double hertz, float amplitude, double seconds) {
        this.hertz = hertz;
        this.amplitude = amplitude;
        this.seconds = seconds;
    }

    @Override
    public boolean render(float[] out, int offset, int frames, int channels, int sampleRate, float gain) {
        long total = seconds > 0 ? (long) (seconds * sampleRate) : Long.MAX_VALUE;
        double step = 2 * Math.PI * hertz / sampleRate;
        for (int f = offset; f < offset + frames && played < total; f++, played++) {
            float s = (float) Math.sin(phase) * amplitude * gain;
            phase += step;
            if (phase > 2 * Math.PI) phase -= 2 * Math.PI;
            for (int c = 0; c < channels; c++) out[f * channels + c] += s;
        }
        return played < total;
    }
}
