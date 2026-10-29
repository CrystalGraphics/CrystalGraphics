package com.crystalgraphics.audio;

/**
 * What a voice plays: samples rendered on the audio thread, a buffer at a time. Unreal's source buffer, as a voice of
 * {@code FMixerSourceManager} pulls it.
 *
 * <pre>{@code
 * final class Click implements CgAudioSource {
 *     private int left = 480;                                 // 10 ms at 48 kHz
 *     public boolean render(float[] out, int offset, int frames, int channels, int sampleRate, float gain) {
 *         for (int f = offset; f < offset + frames && left > 0; f++, left--) {
 *             for (int c = 0; c < channels; c++) out[f * channels + c] += gain;
 *         }
 *         return left > 0;
 *     }
 * }
 * engine.play(new Click(), 0.5f);
 * }</pre>
 *
 * <ul>
 *   <li>Add into {@code out}, never overwrite it: other voices are already in it.</li>
 *   <li>Start at frame {@code offset} of the buffer: a sound due part way through a buffer starts there, on its
 *       sample.</li>
 *   <li>Audio thread only, and allocate nothing.</li>
 *   <li>Answer false once finished: its voice is freed after this buffer.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgAudioSource {

    /**
     * Adds {@code frames} interleaved frames of {@code channels} into {@code out} from frame {@code offset}, at
     * {@code gain}; false once done.
     */
    boolean render(float[] out, int offset, int frames, int channels, int sampleRate, float gain);

    /** On the audio thread: its pitch scaled by {@code scale} from the next buffer, a doppler shift. Ignored by default. */
    default void pitch(float scale) {
    }

    /** On the audio thread, once its voice is freed: finished, or stopped. */
    default void ended() {
    }
}
