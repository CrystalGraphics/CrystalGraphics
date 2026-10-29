package com.crystalgraphics.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.platform.audio.CgAudioOutput;
import com.crystalgraphics.platform.audio.CgAudioStream;

/** The mixer rendered headless into an array, and the engine's thread and queue on an output that records. */
public class CgAudioMixerTest {

    private static final int RATE = 48000, FRAMES = 480;

    private static float peak(float[] buffer) {
        float p = 0;
        for (float s : buffer) p = Math.max(p, Math.abs(s));
        return p;
    }

    /** A 1 kHz tone at half scale: its peak, its period in samples, both channels alike. */
    @Test
    public void aToneRendersAtItsPitchAndLevel() {
        CgAudioMixer mixer = new CgAudioMixer(RATE, 2, FRAMES, 4);
        assertTrue(mixer.start(new CgAudioTone(1000, 0.5f, 0), 1f, true));
        float[] out = new float[FRAMES * 2];
        mixer.render(out);
        assertEquals(0.5f, peak(out), 1e-3);
        int crossings = 0;
        for (int f = 1; f < FRAMES; f++) {
            if (out[(f - 1) * 2] < 0 && out[f * 2] >= 0) crossings++;
            assertEquals(out[f * 2], out[f * 2 + 1], 0);
        }
        assertEquals("ten periods in 10 ms", 10, crossings, 1);
    }

    /** The main gain scales the sum; a voice past the clamp stays within [-1, 1]. */
    @Test
    public void theMainGainScalesAndTheSumIsClamped() {
        CgAudioMixer mixer = new CgAudioMixer(RATE, 2, FRAMES, 4);
        mixer.start(new CgAudioTone(500, 0.8f, 0), 1f, true);
        mixer.start(new CgAudioTone(500, 0.8f, 0), 1f, true);
        float[] out = new float[FRAMES * 2];
        mixer.render(out);
        assertEquals("two in phase, clamped", 1f, peak(out), 0);
        mixer.main(0.25f);
        mixer.render(out);
        assertEquals(0.4f, peak(out), 1e-3);
    }

    /** Paused, a voice that pauses with the game is silent and holds; one that does not plays on. */
    @Test
    public void aPauseHoldsTheVoicesThatPauseWithTheGame() {
        CgAudioMixer mixer = new CgAudioMixer(RATE, 2, FRAMES, 4);
        CgAudioTone world = new CgAudioTone(1000, 0.5f, 0.02);
        mixer.start(world, 1f, true);
        mixer.paused(true);
        float[] out = new float[FRAMES * 2];
        for (int i = 0; i < 5; i++) mixer.render(out);
        assertEquals(0f, peak(out), 0);
        mixer.start(new CgAudioTone(1000, 0.3f, 0), 1f, false);
        mixer.render(out);
        assertEquals("the menu's plays on", 0.3f, peak(out), 1e-3);
        mixer.paused(false);
        mixer.render(out);
        mixer.render(out);
        assertEquals("the world's 20 ms resumed whole, then freed", 1, mixer.voices());
    }

    /** A finished sound frees its voice; past the limit a start is refused and counted. */
    @Test
    public void voicesAreFreedAndLimited() {
        CgAudioMixer mixer = new CgAudioMixer(RATE, 2, FRAMES, 2);
        assertTrue(mixer.start(new CgAudioTone(1000, 0.1f, 0.005), 1f, true));
        assertTrue(mixer.start(new CgAudioTone(1000, 0.1f, 0), 1f, true));
        assertFalse(mixer.start(new CgAudioTone(1000, 0.1f, 0), 1f, true));
        assertEquals(1, mixer.refused());
        mixer.render(new float[FRAMES * 2]);
        assertEquals("the 5 ms tone ended within its buffer", 1, mixer.voices());
        assertEquals(FRAMES, mixer.rendered());
    }

    /** The engine's thread opens the stream, renders what the frame queued, and stops with its world. */
    @Test
    public void theEnginePlaysWhatTheFrameQueuesOnItsOwnThread() throws InterruptedException {
        List<Float> peaks = new ArrayList<>();
        CgAudioOutput recording = format -> new CgAudioStream() {
            int queued;
            long last = System.nanoTime();

            @Override public int free() {
                // Plays at the format's rate: a buffer every buffer's seconds.
                long now = System.nanoTime();
                int played = (int) ((now - last) / (long) (format.bufferSeconds() * 1e9));
                if (played > 0) {
                    queued = Math.max(0, queued - played);
                    last = now;
                }
                return format.buffers() - queued;
            }
            @Override public boolean write(float[] interleaved) {
                queued++;
                synchronized (peaks) {
                    peaks.add(peak(interleaved));
                }
                return true;
            }
            @Override public int underruns() { return 0; }
            @Override public double latency() { return queued * format.bufferSeconds(); }
            @Override public void close() { }
        };
        CgWorld world = new CgWorld();
        CgAudioEngine audio = world.add(new CgAudioEngine(recording));
        assertTrue(audio.active());
        CgAudioTone tone = new CgAudioTone(440, 0.5f, 0);
        audio.play(tone, 0.5f);
        long until = System.currentTimeMillis() + 3000;
        while (audio.buffers() < 20 && System.currentTimeMillis() < until) Thread.sleep(5);
        assertTrue("buffers written: " + audio.buffers(), audio.buffers() >= 20);
        float loudest = 0;
        synchronized (peaks) {
            for (float p : peaks) loudest = Math.max(loudest, p);
        }
        assertEquals("the tone at its gain", 0.25f, loudest, 1e-3);
        assertEquals(1, audio.opens());
        world.close();
        assertFalse(audio.active());
    }
}
