package com.crystalgraphics.audio;

/**
 * A room's reverberation, wet only: what the {@code reverb} submix runs over the sounds sent to it. A port of
 * Jezar's Freeverb (public domain, 2000): eight parallel combs into four allpasses per channel, the right channel's
 * delays spread a little longer.
 *
 * <pre>{@code
 * CgReverb room = new CgReverb(48000).room(0.8f).damp(0.3f);
 * audio.submix("reverb").effect(room);
 * }</pre>
 *
 * <ul>
 *   <li>Wet only: the dry sound reaches the listener through its own submix, and this adds the room.</li>
 *   <li>Made for one sample rate; its delay lines are sized once, here.</li>
 *   <li>Stereo; any other channel count passes through untouched.</li>
 * </ul>
 */
public final class CgReverb implements CgSubmixEffect {

    private static final int[] COMBS = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
    private static final int[] ALLPASSES = {556, 441, 341, 225};
    private static final int SPREAD = 23;
    private static final float FIXED_GAIN = 0.015f, SCALE_ROOM = 0.28f, OFFSET_ROOM = 0.7f, SCALE_DAMP = 0.4f;
    private static final float SCALE_WET = 3f, ALLPASS_FEEDBACK = 0.5f;

    private final float[][] comb = new float[16][], allpass = new float[8][];
    private final int[] combAt = new int[16], allpassAt = new int[8];
    private final float[] combStore = new float[16];
    private final int sampleRate;
    private volatile float room = 0.5f, damp = 0.5f, wet = 1f / SCALE_WET, width = 1f;

    public CgReverb(int sampleRate) {
        this.sampleRate = sampleRate;
        // Freeverb's tunings are at 44.1 kHz.
        double k = sampleRate / 44100.0;
        for (int i = 0; i < 8; i++) {
            comb[i] = new float[(int) (COMBS[i] * k)];
            comb[i + 8] = new float[(int) ((COMBS[i] + SPREAD) * k)];
        }
        for (int i = 0; i < 4; i++) {
            allpass[i] = new float[(int) (ALLPASSES[i] * k)];
            allpass[i + 4] = new float[(int) ((ALLPASSES[i] + SPREAD) * k)];
        }
    }

    /** The room's size, 0 to 1: how long the tail rings. */
    public CgReverb room(float size) {
        room = size;
        return this;
    }

    /** How fast the highs die, 0 to 1. */
    public CgReverb damp(float amount) {
        damp = amount;
        return this;
    }

    /** Its output level, 0 to 1. */
    public CgReverb wet(float level) {
        wet = level;
        return this;
    }

    /** Stereo width, 0 (mono) to 1. */
    public CgReverb width(float w) {
        width = w;
        return this;
    }

    @Override
    public void process(float[] buffer, int frames, int channels, int rate) {
        if (channels != 2 || rate != sampleRate) return;
        float feedback = room * SCALE_ROOM + OFFSET_ROOM, damp1 = damp * SCALE_DAMP, damp2 = 1 - damp1;
        float w = wet * SCALE_WET, wet1 = w * (width / 2 + 0.5f), wet2 = w * ((1 - width) / 2);
        for (int f = 0; f < frames; f++) {
            float in = (buffer[f * 2] + buffer[f * 2 + 1]) * FIXED_GAIN;
            float left = 0, right = 0;
            for (int c = 0; c < 8; c++) {
                left += comb(c, in, feedback, damp1, damp2);
                right += comb(c + 8, in, feedback, damp1, damp2);
            }
            for (int a = 0; a < 4; a++) {
                left = allpass(a, left);
                right = allpass(a + 4, right);
            }
            buffer[f * 2] = left * wet1 + right * wet2;
            buffer[f * 2 + 1] = right * wet1 + left * wet2;
        }
    }

    private float comb(int c, float in, float feedback, float damp1, float damp2) {
        float[] line = comb[c];
        int at = combAt[c];
        float out = line[at];
        combStore[c] = out * damp2 + combStore[c] * damp1;
        line[at] = in + combStore[c] * feedback;
        combAt[c] = at + 1 == line.length ? 0 : at + 1;
        return out;
    }

    private float allpass(int a, float in) {
        float[] line = allpass[a];
        int at = allpassAt[a];
        float delayed = line[at];
        line[at] = in + delayed * ALLPASS_FEEDBACK;
        allpassAt[a] = at + 1 == line.length ? 0 : at + 1;
        return delayed - in;
    }
}
