package com.crystalgraphics.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.sequence.CgSequenceTarget;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.sequence.event.CgSequenceEventListener;

/**
 * The audio clock: a sequence's events, heard a frame at a time, start their sounds on their own samples, rendered
 * headless at uneven frame rates.
 */
public class CgAudioClockTest {

    private static final int RATE = 48000, FRAMES = 512, QUEUE = 8 * FRAMES, LEAD = QUEUE;
    /** 60000 ticks a second: a click every 2000 ticks is one every 1/30 s, 1600 samples apart. */
    private static final int TICKS = 60000, EVERY = 2000, CLICKS = 59, APART = 1600;

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    /** One sample of full scale on its first frame. */
    private static final class Click implements CgAudioSource {
        @Override
        public boolean render(float[] out, int offset, int frames, int channels, int sampleRate, float gain) {
            out[offset * channels] += gain;
            return false;
        }
    }

    private static final CgSequenceTarget CASTER = new CgSequenceTarget() {
        @Override public int slot(String name) { return 0; }
        @Override public void set(int slot, float[] v, int mask) { }
        @Override public void get(int slot, float[] out) { }
    };

    private static CgSequenceDefinition clicks() {
        return clicks("");
    }

    /** Clicks every 2000 ticks for two seconds; {@code sections} adds its montage sections. */
    private static CgSequenceDefinition clicks(String sections) {
        StringBuilder keys = new StringBuilder();
        for (int k = 1; k <= CLICKS; k++) keys.append(k > 1 ? ", " : "").append("[").append(k * EVERY).append(", click]");
        String yaml = "_v: 1\ntick_resolution: " + TICKS + "\ndisplay_rate: 30\nrange: [0, " + TICKS * 2 + "]\n" + sections
                + "roles:\n  - { id: r1, name: caster, kind: caster }\n"
                + "tracks:\n  - { id: t1, kind: event, side: ours, binding: { role: caster }, sections: [ { id: a, keys: [" + keys + "] } ] }\n";
        CgAssetCompiler.Dependencies none = (kind, ref) -> null;
        return CgSequenceDefinition.COMPILER.compile(CgAssetData.of("test:clicks", CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(yaml), null), none);
    }

    /** Every click's sample in a headless render of the clicks played at frame steps from {@code random}. */
    private static List<Long> render(Random random, double minStep, double maxStep) {
        return render(clicks(), random, minStep, maxStep);
    }

    private static List<Long> render(CgSequenceDefinition clicks, Random random, double minStep, double maxStep) {
        CgSequencer sequencer = new CgWorld().add(new CgSequencer());
        CgAudioMixer mixer = new CgAudioMixer(RATE, 1, FRAMES, 256);
        long[] written = {0};
        CgAudioClock clock = new CgAudioClock(RATE, LEAD, 2L * FRAMES, () -> written[0]);
        sequencer.listen(CgSequenceEventListener.Side.OURS, (player, phase, event, args) ->
                mixer.start(new Click(), 1f, false, clock.frameAt(clock.now() - sequencer.heardSecondsAgo())));
        float[] buffer = new float[FRAMES];
        List<Long> at = new ArrayList<>();
        double elapsed = 0;
        clock.advance(0);
        sequencer.play(clicks, Map.of("caster", CASTER));
        while (elapsed < 3) {
            double dt = minStep + random.nextDouble() * (maxStep - minStep);
            elapsed += dt;
            // The audio thread's part, run in real time beside the frames: whole buffers until its queue is full ahead.
            while (mixer.rendered() + FRAMES <= elapsed * RATE + QUEUE) {
                long start = mixer.rendered();
                mixer.render(buffer);
                written[0] = mixer.rendered();
                for (int f = 0; f < FRAMES; f++) if (buffer[f] > 0.5f) at.add(start + f);
            }
            clock.advance(dt);
            sequencer.evaluate((float) dt);
        }
        assertEquals("no start reached the mixer late", 0, mixer.late());
        return at;
    }

    /** Clicks on every tick, at frame rates from 300 to 16 a second, land 1600 samples apart to the sample. */
    @Test
    public void aSequencesClicksLandOnTheirSamplesAtAnyFrameRate() {
        double[][] rates = {{1 / 300.0, 1 / 240.0}, {1 / 61.0, 1 / 59.0}, {1 / 120.0, 1 / 16.0}, {1 / 20.0, 1 / 16.0}};
        for (double[] r : rates) {
            for (long seed = 1; seed <= 3; seed++) {
                List<Long> at = render(new Random(seed), r[0], r[1]);
                String where = "steps " + Math.round(1 / r[1]) + "-" + Math.round(1 / r[0]) + " fps, seed " + seed;
                assertEquals(where + ": every click", CLICKS, at.size());
                for (int k = 1; k < at.size(); k++) {
                    long off = at.get(k) - at.get(0) - (long) k * APART;
                    assertTrue(where + ": click " + k + " off by " + off + " samples", Math.abs(off) <= 1);
                }
            }
        }
    }

    /**
     * A montage looping its second half: a section's end hands play on part way through a frame, and every click still
     * lands on the same 1600-sample grid as the first, to the sample.
     */
    @Test
    public void clicksStayOnTheirSamplesAcrossALoopingSection() {
        String sections = "composite_sections:\n  - { id: s1, name: Default, range: [0, 60000], next: s2 }\n"
                + "  - { id: s2, name: Loop, range: [60000, 120000], next: s2 }\n";
        for (long seed = 1; seed <= 3; seed++) {
            List<Long> at = render(clicks(sections), new Random(seed), 1 / 90.0, 1 / 25.0);
            assertTrue("clicks past the first loop: " + at.size(), at.size() > CLICKS);
            for (int k = 1; k < at.size(); k++) {
                long d = at.get(k) - at.get(0);
                long off = d - Math.round(d / (double) APART) * APART;
                assertTrue("seed " + seed + ": click " + k + " off the grid by " + off + " samples", Math.abs(off) <= 1);
            }
        }
    }

    /** A start due before the buffer it reaches plays at once and is counted late; one far ahead waits for its frame. */
    @Test
    public void aStartWaitsForItsFrameAndALateOneIsCounted() {
        CgAudioMixer mixer = new CgAudioMixer(RATE, 1, FRAMES, 4);
        float[] buffer = new float[FRAMES];
        mixer.render(buffer);
        mixer.start(new Click(), 1f, false, FRAMES + 700);
        mixer.render(buffer);
        assertEquals("not yet", 0f, buffer[188], 0);
        mixer.render(buffer);
        assertEquals("700 frames after the second buffer began", 1f, buffer[700 - FRAMES], 0);
        mixer.start(new Click(), 1f, false, 10);
        assertEquals(1, mixer.late());
    }
}
