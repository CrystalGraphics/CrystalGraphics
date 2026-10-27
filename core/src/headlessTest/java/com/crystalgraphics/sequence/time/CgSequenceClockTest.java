package com.crystalgraphics.sequence.time;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.crystalgraphics.sequence.CgEventChannel;
import com.crystalgraphics.sequence.CgSequenceDefinition;

public class CgSequenceClockTest {

    private static final CgFrameRate TICKS = new CgFrameRate(24000, 1), DISPLAY = new CgFrameRate(30, 1);
    /** Events a sequence of 2 s carries: one inside the first 200 ms frame's successor, one on a frame boundary. */
    private static final int[] KEYS = {0, 4800, 6000, 23999, 48000};

    @Test
    public void ratesConvertAsUnrealDoes() {
        assertEquals(new CgFrameRate(30000, 1001), CgFrameRate.of(29.97));
        assertEquals(new CgFrameRate(24000, 1001), CgFrameRate.of(23.976));
        assertEquals(new CgFrameRate(24000, 1), CgFrameRate.of(24000));
        assertThrows(IllegalArgumentException.class, () -> CgFrameRate.of(23.5));

        assertEquals(CgFrameTime.of(6), DISPLAY.asFrameTime(0.2));
        long t = DISPLAY.asFrameTime(0.21);   // 6.3 frames
        assertEquals(6, CgFrameTime.frame(t));
        assertEquals(0.3f, CgFrameTime.subFrame(t), 1e-6f);
        // Whole frames through integers: 6.3 frames at 30 a second is tick 5040, give or take the float's last bit.
        assertEquals(5040, CgFrameTime.frame(CgFrameRate.convert(t, DISPLAY, TICKS)));
        assertEquals(CgFrameTime.of(24024), CgFrameRate.convert(CgFrameTime.of(30), CgFrameRate.of(29.97), TICKS));
        assertEquals(0.2, TICKS.asSeconds(CgFrameTime.of(4800)), 0.0);

        assertEquals(CgFrameTime.of(4801), CgFrameTime.add(CgFrameTime.of(4800, 0.25f), CgFrameTime.of(0, 0.75f)));
        assertEquals(4801, CgFrameTime.ceil(CgFrameTime.of(4800, 0.25f)));
        assertTrue(CgFrameTime.of(4800, 0.25f) > CgFrameTime.of(4800));
        assertTrue(CgFrameTime.of(-1, 0.5f) < CgFrameTime.of(0));
    }

    @Test
    public void anEventInsideALongFrameFiresOnce() {
        CgSequencePlaybackPosition clock = new CgSequencePlaybackPosition(DISPLAY, TICKS, CgSequenceDefinition.Evaluation.SUB_FRAMES, 0);
        CgEventChannel hits = channel();
        List<Integer> fired = new ArrayList<>();
        clock.playToSeconds(0.2).sweep(hits, (tick, name) -> fired.add(tick));   // [0, 4800]: the start and the boundary
        assertEquals(List.of(0, 4800), fired);
        clock.playToSeconds(0.4).sweep(hits, (tick, name) -> fired.add(tick));   // (4800, 9600]: 6000, inside the frame
        assertEquals(List.of(0, 4800, 6000), fired);
        clock.playToSeconds(0.4).sweep(hits, (tick, name) -> fired.add(tick));   // no time passed: nothing again
        assertEquals(List.of(0, 4800, 6000), fired);
    }

    @Test
    public void everyFrameRateFiresTheSameTicks() {
        List<Integer> fixed = play(1.0 / 60.0), at30 = play(1.0 / 30.0), at144 = play(1.0 / 144.0), at5 = play(0.2);
        assertEquals(List.of(0, 4800, 6000, 23999, 48000), fixed);
        assertEquals(fixed, at30);
        assertEquals(fixed, at144);
        assertEquals(fixed, at5);
    }

    @Test
    public void aSeekLandsWherePlayingDoes() {
        CgSequencePlaybackPosition played = new CgSequencePlaybackPosition(DISPLAY, TICKS, CgSequenceDefinition.Evaluation.SUB_FRAMES, 0);
        CgEventChannel a = channel();
        for (double s = 0; s < 0.9; s += 1.0 / 60.0) played.playToSeconds(s).sweep(a, (tick, name) -> { });
        CgSequenceEvaluationRange atPlay = played.playToSeconds(0.9);
        atPlay.sweep(a, (tick, name) -> { });

        CgSequencePlaybackPosition sought = new CgSequencePlaybackPosition(DISPLAY, TICKS, CgSequenceDefinition.Evaluation.SUB_FRAMES, 0);
        CgEventChannel b = channel();
        CgSequenceEvaluationRange jump = sought.jumpToSeconds(0.9);
        assertTrue(jump.jumped());
        assertEquals(atPlay.current(), jump.current());
        b.place(CgFrameTime.frame(jump.current()));

        // From here both play on and fire the same keys.
        List<Integer> fromPlay = new ArrayList<>(), fromSeek = new ArrayList<>();
        played.playToSeconds(2.0).sweep(a, (tick, name) -> fromPlay.add(tick));
        sought.playToSeconds(2.0).sweep(b, (tick, name) -> fromSeek.add(tick));
        assertEquals(List.of(23999, 48000), fromPlay);
        assertEquals(fromPlay, fromSeek);
    }

    @Test
    public void frameLockedStepsWholeDisplayFrames() {
        CgSequencePlaybackPosition clock = new CgSequencePlaybackPosition(DISPLAY, TICKS, CgSequenceDefinition.Evaluation.FRAME_LOCKED, 0);
        CgSequenceEvaluationRange r = clock.playToSeconds(0.21);
        assertEquals(CgFrameTime.of(4800), r.upper());
        assertEquals(CgFrameTime.of(6), clock.current());
    }

    @Test
    public void playingBackwardsCoversTheSpanBehind() {
        CgSequencePlaybackPosition clock = new CgSequencePlaybackPosition(DISPLAY, TICKS, CgSequenceDefinition.Evaluation.SUB_FRAMES, 0);
        clock.playToSeconds(0.4);
        CgSequenceEvaluationRange r = clock.playToSeconds(0.1);
        assertEquals(CgSequenceEvaluationRange.Direction.BACKWARDS, r.direction());
        assertEquals(CgFrameTime.of(2400), r.lower());
        assertTrue(r.lowerInclusive());
        assertFalse("the end it left is not evaluated again", r.upperInclusive());
        assertEquals(2400, r.keyFrom());
        assertEquals(9600, r.keyEnd());
    }

    @Test
    public void aStartInsideTheRangeCountsFromThere() {
        // A playback range starting at tick 12000 (half a second): its first update starts there.
        CgSequencePlaybackPosition clock = new CgSequencePlaybackPosition(DISPLAY, TICKS, CgSequenceDefinition.Evaluation.SUB_FRAMES, 12000);
        assertEquals(CgFrameTime.of(15), clock.startInput());
        CgSequenceEvaluationRange r = clock.playToSeconds(0.1);
        assertEquals(CgFrameTime.of(12000), r.lower());
        assertEquals(CgFrameTime.of(14400), r.upper());
    }

    private static List<Integer> play(double delta) {
        CgSequencePlaybackPosition clock = new CgSequencePlaybackPosition(DISPLAY, TICKS, CgSequenceDefinition.Evaluation.SUB_FRAMES, 0);
        CgEventChannel hits = channel();
        List<Integer> fired = new ArrayList<>();
        int steps = (int) Math.ceil(2.0 / delta);
        for (int i = 0; i <= steps; i++) clock.playToSeconds(Math.min(2.0, i * delta)).sweep(hits, (tick, name) -> fired.add(tick));
        return fired;
    }

    private static CgEventChannel channel() {
        String[] names = new String[KEYS.length];
        for (int i = 0; i < names.length; i++) names[i] = "k" + KEYS[i];
        return new CgEventChannel(KEYS, names, true, false);
    }
}
