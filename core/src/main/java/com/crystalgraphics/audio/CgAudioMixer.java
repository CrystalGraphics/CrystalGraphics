package com.crystalgraphics.audio;

import java.util.Arrays;

/**
 * Renders the engine's sound, a buffer at a time: every voice summed, the main gain applied, clamped to [-1, 1].
 * Unreal's {@code FMixerDevice::OnProcessAudioStream} into {@code FOutputBuffer::MixNextBuffer}. Plain Java over float
 * buffers, so a test renders it headless into an array.
 *
 * <pre>{@code
 * CgAudioMixer mixer = new CgAudioMixer(48000, 2, 512, 64);
 * mixer.start(new CgAudioTone(440, 0.5f, 1), 1f, true);
 * float[] buffer = new float[512 * 2];
 * mixer.render(buffer);                                  // 512 interleaved stereo frames
 * }</pre>
 *
 * <ul>
 *   <li>One thread renders it: the audio thread, or a test's. {@link CgAudioEngine} posts to it from the frame.</li>
 *   <li>A voice that pauses with the game is silent and holds its place while {@link #paused(boolean)}.</li>
 *   <li>A start past the voice limit is refused and counted ({@link #refused()}).</li>
 *   <li>Allocates nothing once made.</li>
 * </ul>
 */
public final class CgAudioMixer {

    private final int sampleRate, channels, frames;
    private final CgAudioSource[] voices;
    private final float[] gains;
    private final boolean[] pausing;
    private float main = 1f;
    private boolean paused;
    private int refused;
    private long rendered;

    public CgAudioMixer(int sampleRate, int channels, int frames, int maxVoices) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.frames = frames;
        voices = new CgAudioSource[maxVoices];
        gains = new float[maxVoices];
        pausing = new boolean[maxVoices];
    }

    /** Plays {@code source} at {@code gain}; false where every voice is taken. */
    public boolean start(CgAudioSource source, float gain, boolean pausesWithGame) {
        for (int i = 0; i < voices.length; i++) {
            if (voices[i] != null) continue;
            voices[i] = source;
            gains[i] = gain;
            pausing[i] = pausesWithGame;
            return true;
        }
        refused++;
        return false;
    }

    public void stop(CgAudioSource source) {
        for (int i = 0; i < voices.length; i++) if (voices[i] == source) voices[i] = null;
    }

    public void gain(CgAudioSource source, float gain) {
        for (int i = 0; i < voices.length; i++) if (voices[i] == source) gains[i] = gain;
    }

    /** The main submix's gain: the host's master slider. */
    public void main(float gain) {
        main = gain;
    }

    public void paused(boolean paused) {
        this.paused = paused;
    }

    /** Fills {@code out} with the next buffer: its format's frames × channels floats. */
    public void render(float[] out) {
        Arrays.fill(out, 0, frames * channels, 0f);
        for (int i = 0; i < voices.length; i++) {
            CgAudioSource v = voices[i];
            if (v == null || paused && pausing[i]) continue;
            if (!v.render(out, frames, channels, sampleRate, gains[i])) voices[i] = null;
        }
        for (int i = 0, n = frames * channels; i < n; i++) {
            float s = out[i] * main;
            out[i] = s > 1f ? 1f : s < -1f ? -1f : s;
        }
        rendered += frames;
    }

    /** Voices playing now. */
    public int voices() {
        int n = 0;
        for (CgAudioSource v : voices) if (v != null) n++;
        return n;
    }

    /** Starts refused at the voice limit. */
    public int refused() {
        return refused;
    }

    /** Frames rendered since it was made. */
    public long rendered() {
        return rendered;
    }
}
