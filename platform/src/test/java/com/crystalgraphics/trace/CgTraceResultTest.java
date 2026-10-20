package com.crystalgraphics.trace;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The result file: what one bench run leaves behind for {@code bench.py} to compare.
 *
 * <h3>The format, version 1</h3>
 *
 * <p>JSON, written by hand (the platform has no JSON library), two-space indent, one line per measure, keys in the
 * order of the golden file {@code result-golden.json} (this test's resource, and {@code bench.py}'s gate reads the same
 * bytes), counters sorted by name, {@code \n} line ends and a final newline. <b>Every time is integer nanoseconds and
 * every size integer bytes</b>: no float is written, so a result is byte-stable and two builds' files diff cleanly.</p>
 * <ul>
 *   <li>{@code version}: first key; {@link CgTraceResult#VERSION}. A reader that finds another number refuses the file
 *       by name rather than guess;</li>
 *   <li>{@code scene}, {@code cell}, {@code commit}, {@code targetNanos}, {@code frames}: what ran, the matrix cell the
 *       caller names, the build, the frame-time target that defined the hitches, and how many frames are measured;</li>
 *   <li>{@code measures}: {@code wall}, {@code cpu}, {@code gpu}, {@code allocatedBytes} (the frame thread's, per
 *       frame) and {@code directBytes}, each {@code {count, median, p99, p99.9, max, sum}} over the frames that have
 *       the figure, by {@link CgHitches#percentile} (median is permille 500). A measure no frame has is
 *       {@code null}: absent is not zero;</li>
 *   <li>{@code hitches}: {@code count} (by {@link CgHitches#find}), {@code ours}, and {@code unexplained};</li>
 *   <li>{@code gcCollections} and {@code gcPauseMillis}: the sums over the frames;</li>
 *   <li>{@code jvm}: {@code compilations}, {@code deoptimisations}, {@code safepoints}, {@code classLoads}, each the
 *       sum of the per-frame counts, {@code null} where the source cannot see the kind;</li>
 *   <li>{@code counters}: every counter by name, {@code sum} over frames, {@code max} of a frame's sum and the
 *       number of {@code frames} that wrote it.</li>
 * </ul>
 *
 * <h3>The API this fixes</h3>
 * <ul>
 *   <li>{@code CgTraceResult.VERSION = 1};</li>
 *   <li>{@code CgTraceResult.toJson(snapshot, scene, cell, commit, targetNanos)} → the file's text. {@code scene},
 *       {@code cell} and {@code commit} must be non-empty and are JSON-escaped; a snapshot with no frames throws
 *       {@code IllegalStateException} whose message contains {@code "no frames"}.</li>
 * </ul>
 */
public class CgTraceResultTest {

    private static final long MS = 1_000_000L;
    private static final long T0 = 1_000_000_000L;

    @Test
    public void aKnownSnapshotWritesExactlyTheGoldenFile() throws IOException {
        assertEquals(golden(), CgTraceResult.toJson(fixture(), "fixture-scene", "fixture-cell", "0123abc", 10 * MS));
    }

    @Test
    public void theVersionIsTheFirstKeyAndIsOne() {
        assertEquals(1, CgTraceResult.VERSION);
        String text = CgTraceResult.toJson(fixture(), "s", "c", "x", 10 * MS);
        assertTrue(text, text.startsWith("{\n  \"version\": 1,\n"));
    }

    @Test
    public void writingTwiceGivesTheSameBytes() {
        assertEquals(CgTraceResult.toJson(fixture(), "s", "c", "x", 10 * MS),
                CgTraceResult.toJson(fixture(), "s", "c", "x", 10 * MS));
    }

    @Test
    public void aSnapshotWithNoFramesIsRefusedByName() {
        try {
            CgTraceResult.toJson(CgTraceSnapshot.of(List.of(), List.of(), List.of(), List.of(), List.of()), "s", "c", "x", 10 * MS);
            throw new AssertionError("an empty run must not produce a result");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("no frames"));
        }
    }

    @Test
    public void namesAreRequiredAndEscaped() {
        for (String bad : new String[] {"", null}) {
            try {
                CgTraceResult.toJson(fixture(), bad, "c", "x", 10 * MS);
                throw new AssertionError("a result with no scene must be refused");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("scene"));
            }
        }
        String text = CgTraceResult.toJson(fixture(), "a\"b\\c", "c", "x", 10 * MS);
        assertTrue(text, text.contains("\"scene\": \"a\\\"b\\\\c\""));
    }

    // ── the fixture: 40 frames of 10 ms, frame 30 at 50 ms, 4 ms of cpu (45 ms in frame 30), no gpu ──

    private static CgTraceSnapshot fixture() {
        List<CgFrameRecord> frames = new ArrayList<>();
        long at = T0;
        for (int i = 0; i < 40; i++) {
            long wall = i == 30 ? 50 * MS : 10 * MS;
            long cpu = i == 30 ? 45 * MS : 4 * MS;
            long allocated = i == 30 ? 3_000_000L : 2048L;
            frames.add(new CgFrameRecord(i, at, at + wall, cpu, CgFrameRecord.ABSENT, 0L, 0, 0, 0L, allocated,
                    CgFrameRecord.ABSENT));
            at += wall;
        }
        return CgTraceSnapshot.of(frames, List.of(),
                List.of(new CgTraceSnapshot.CounterView("material.compile-waits", 35, 2)), List.of(), List.of());
    }

    private static String golden() throws IOException {
        try (InputStream in = CgTraceResultTest.class.getResourceAsStream("result-golden.json")) {
            if (in == null) throw new AssertionError("result-golden.json is missing beside this test");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
