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
 * Started on a sample: Quartz's quantized start, the voice beginning part way through the buffer it is due in.
 *
 * <pre>{@code
 * mixer.start(click, 1f, true, mixer.rendered() + 1000);   // 1000 frames after the next buffer starts
 * }</pre>
 *
 * <ul>
 *   <li>One thread renders it: the audio thread, or a test's. {@link CgAudioEngine} posts to it from the frame.</li>
 *   <li>A voice that pauses with the game is silent and holds its place while {@link #paused(boolean)}.</li>
 *   <li>A start past the voice limit is refused and counted ({@link #refused()}).</li>
 *   <li>A start due before the next buffer plays at once and is counted late ({@link #late()}).</li>
 *   <li>A voice waiting for its frame that pauses with the game waits longer while paused: its due frame moves on.</li>
 *   <li>Allocates nothing once made.</li>
 * </ul>
 */
public final class CgAudioMixer {

    private final int sampleRate, channels, frames;
    private final CgAudioSource[] voices;
    private final float[] gains;
    private final boolean[] pausing;
    /** Each voice's submix, by index into {@link #submixes}. */
    private final int[] route;
    /** Parents before children: processed from the last, each into its parent. */
    private CgSubmix[] submixes;
    /** Each voice's due frame; NOW for one that is playing. */
    private final long[] due;
    private static final long NOW = Long.MIN_VALUE;
    private float main = 1f;
    private boolean paused;
    private int refused, late;
    private long rendered;

    public CgAudioMixer(int sampleRate, int channels, int frames, int maxVoices) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.frames = frames;
        voices = new CgAudioSource[maxVoices];
        gains = new float[maxVoices];
        pausing = new boolean[maxVoices];
        due = new long[maxVoices];
        route = new int[maxVoices];
        submixes(new CgSubmix[] {new CgSubmix("main", null, 0)});
    }

    /** Plays {@code source} at {@code gain} from the next buffer; false where every voice is taken. */
    public boolean start(CgAudioSource source, float gain, boolean pausesWithGame) {
        return start(source, gain, pausesWithGame, NOW);
    }

    /**
     * Its submix tree, parents before children, each one's index its place here. Before it renders; a test's or the
     * engine's.
     */
    public void submixes(CgSubmix[] tree) {
        for (int i = 0; i < tree.length; i++) {
            if (tree[i].index != i) throw new IllegalArgumentException(tree[i] + " is not at its index " + tree[i].index);
            tree[i].buffer = new float[frames * channels];
        }
        submixes = tree;
    }

    /** Plays {@code source} at {@code gain} from mixer frame {@code frame} ({@link #rendered()} counts them). */
    public boolean start(CgAudioSource source, float gain, boolean pausesWithGame, long frame) {
        return start(source, gain, pausesWithGame, frame, 0);
    }

    /** As {@link #start(CgAudioSource, float, boolean, long)}, into submix {@code submix}. */
    public boolean start(CgAudioSource source, float gain, boolean pausesWithGame, long frame, int submix) {
        for (int i = 0; i < voices.length; i++) {
            if (voices[i] != null) continue;
            voices[i] = source;
            gains[i] = gain;
            pausing[i] = pausesWithGame;
            if (frame != NOW && frame < rendered) {
                late++;
                frame = NOW;
            }
            due[i] = frame;
            route[i] = submix;
            return true;
        }
        refused++;
        return false;
    }

    public void stop(CgAudioSource source) {
        for (int i = 0; i < voices.length; i++) if (voices[i] == source) free(i);
    }

    private void free(int i) {
        CgAudioSource v = voices[i];
        voices[i] = null;
        v.ended();
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
        int n = frames * channels;
        for (CgSubmix s : submixes) Arrays.fill(s.buffer, 0, n, 0f);
        for (int i = 0; i < voices.length; i++) {
            CgAudioSource v = voices[i];
            if (v == null) continue;
            if (paused && pausing[i]) {
                if (due[i] != NOW) due[i] += frames;
                continue;
            }
            int offset = 0;
            if (due[i] != NOW) {
                // TickInternal: due within this buffer, it starts at its frame there; later, it waits.
                if (due[i] >= rendered + frames) continue;
                offset = (int) Math.max(0, due[i] - rendered);
                due[i] = NOW;
            }
            if (!v.render(submixes[route[i]].buffer, offset, frames - offset, channels, sampleRate, gains[i])) free(i);
        }
        // Leaf to root: each submix's chain on its buffer, then into its parent; the root into the output.
        Arrays.fill(out, 0, n, 0f);
        for (int k = submixes.length - 1; k >= 0; k--) {
            CgSubmix s = submixes[k];
            for (CgSubmixEffect e : s.chain()) e.process(s.buffer, frames, channels, sampleRate);
            float[] into = s.parent() == null ? out : s.parent().buffer;
            float v = s.volume();
            for (int i = 0; i < n; i++) into[i] += s.buffer[i] * v;
        }
        for (int i = 0; i < n; i++) {
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

    /** Starts due before the buffer they reached the mixer in, played at once. */
    public int late() {
        return late;
    }

    /** Frames rendered since it was made. */
    public long rendered() {
        return rendered;
    }
}
