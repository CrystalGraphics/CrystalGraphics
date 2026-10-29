package com.crystalgraphics.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/** Subtitles queued per sound, stepped through their cues by the sound's own time, the highest priority shown. */
public class CgSubtitlesTest {

    /** Seconds each sound has played; absent, it stopped. */
    private final Map<Long, Double> played = new HashMap<>();

    private void at(CgSubtitles s, long id, double seconds) {
        played.put(id, seconds);
        s.update(i -> played.getOrDefault(i, Double.NaN));
    }

    /** Each cue shows from its time to the next, and the last until the sound ends. */
    @Test
    public void cuesFollowTheSoundToItsEnd() {
        CgSubtitles s = new CgSubtitles();
        s.queue(1, CgSubtitles.DEFAULT_PRIORITY, 3, List.of(new CgSubtitles.Cue("Kame...", 0), new CgSubtitles.Cue("...hameha!", 2)));
        at(s, 1, 0.5);
        assertEquals("Kame...", s.text());
        at(s, 1, 2.1);
        assertEquals("...hameha!", s.text());
        at(s, 1, 3.1);
        assertNull("the blank at the sound's end", s.text());
        assertEquals(0, s.count());
    }

    /** A sound joined late, or one a hitch carried far, reads the cue it is at; a cue past its length is clamped. */
    @Test
    public void lateAndClampedCues() {
        CgSubtitles s = new CgSubtitles();
        s.queue(1, CgSubtitles.DEFAULT_PRIORITY, 4, List.of(new CgSubtitles.Cue("a", 0), new CgSubtitles.Cue("b", 1),
                new CgSubtitles.Cue("c", 9)));
        at(s, 1, 2);
        assertEquals("b", s.text());
        at(s, 1, 3.99);
        assertEquals("b", s.text());
        at(s, 1, 4);
        assertNull("c clamped to the end, where the blank is", s.text());
    }

    /** The highest priority shows; equal ones show the latest; a stopped sound's line goes. */
    @Test
    public void priorityThenLatestAndDroppedOnStop() {
        CgSubtitles s = new CgSubtitles();
        s.queue(1, "Explosion", 5);
        s.queue(2, 50f, 5, List.of(new CgSubtitles.Cue("Line", 0)));
        s.queue(3, "Thunder", 5);
        played.put(1L, 0.1);
        played.put(2L, 0.1);
        at(s, 3, 0.1);
        assertEquals("Thunder", s.text());
        assertEquals(3, s.id());
        played.remove(3L);
        at(s, 1, 0.2);
        assertEquals("Explosion", s.text());
        s.queue(4, 0f, 5, List.of(new CgSubtitles.Cue("never", 0)));
        assertEquals("priority 0 queues nothing", 2, s.count());
    }
}
