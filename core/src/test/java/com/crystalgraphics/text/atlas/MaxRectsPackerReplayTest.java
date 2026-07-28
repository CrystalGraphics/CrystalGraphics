package com.crystalgraphics.text.atlas;

import com.crystalgraphics.text.atlas.packing.MaxRectsPacker;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Replays a recorded glyph sequence through the packer and reports pages used.
 *
 * <p>Exists because harness runs cannot answer "did packing get worse". Distance-field generation
 * is asynchronous, so two runs of the same scene commit different numbers of glyphs in the same
 * window, and a partially-converged atlas is visually indistinguishable from a badly-packed one —
 * both show half-empty trailing pages. Comparing two such runs measures how far generation got,
 * not how well the packer packed.
 *
 * <p>This feeds a <em>fixed</em> sequence, taken from a real atlas dump, so the only variable is
 * the packer. The dump also records what the packer that produced it achieved, which gives a
 * baseline to compare against directly.
 */
public class MaxRectsPackerReplayTest {

    private static final String MANIFEST =
            "../../gl-debug-harness/harness-output/atlas/old/msdf-atlas-dump-80px-manifest.txt";

    private static final int PAGE = 1024;
    private static final int SPACING = 1;

    /** Baseline recorded in that dump: 11 pages for this glyph set. */
    private static final int RECORDED_PAGES = 11;

    @Test
    public void replayOfRecordedGlyphSequenceDoesNotUseMorePages() throws Exception {
        File manifest = new File(MANIFEST);
        if (!manifest.isFile()) {
            System.out.println("[replay] manifest absent, skipping: " + manifest.getAbsolutePath());
            return;
        }

        List<int[]> boxes = parseGlyphBoxes(manifest);
        assertTrue("manifest yielded no glyph boxes", boxes.size() > 0);

        List<MaxRectsPacker> pages = new ArrayList<>();
        pages.add(new MaxRectsPacker(PAGE, PAGE));
        int placed = 0;

        for (int[] box : boxes) {
            boolean done = false;
            // Oldest-first, matching CgGlyphAtlas: fill gaps in earlier pages before opening a new one.
            for (int i = 0; i < pages.size() && !done; i++) {
                MaxRectsPacker page = pages.get(i);
                if (!page.mayFit(box[0] + SPACING, box[1] + SPACING)) {
                    continue;
                }
                done = page.insert(box[0], box[1], SPACING, placed) != null;
            }
            if (!done) {
                MaxRectsPacker fresh = new MaxRectsPacker(PAGE, PAGE);
                assertTrue("a glyph that fits no page at all is a bug, not a packing outcome",
                        fresh.insert(box[0], box[1], SPACING, placed) != null);
                pages.add(fresh);
            }
            placed++;
        }

        double total = 0.0;
        System.out.printf(Locale.ROOT, "[replay] %d glyphs -> %d pages (recorded baseline: %d)%n",
                boxes.size(), pages.size(), RECORDED_PAGES);
        for (int i = 0; i < pages.size(); i++) {
            double u = pages.get(i).utilization() * 100.0;
            total += u;
            System.out.printf(Locale.ROOT, "[replay]   page %2d: %5d glyphs  %5.1f%%  free>=60px: %d%n",
                    i, pages.get(i).getPackedCount(), u, pages.get(i).countFreeRegionsFitting(60, 60));
        }
        System.out.printf(Locale.ROOT, "[replay] mean utilisation %.2f%%%n", total / pages.size());

        assertTrue("packer regressed: " + pages.size() + " pages for a glyph set recorded at "
                + RECORDED_PAGES, pages.size() <= RECORDED_PAGES);
    }

    /** Pulls every {@code size=WxH} out of the manifest, in the order glyphs were allocated. */
    private static List<int[]> parseGlyphBoxes(File manifest) throws Exception {
        String text = new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("size=(-?\\d+)x(-?\\d+)").matcher(text);
        List<int[]> boxes = new ArrayList<>();
        while (m.find()) {
            int w = Math.abs(Integer.parseInt(m.group(1)));
            int h = Math.abs(Integer.parseInt(m.group(2)));
            if (w > 0 && h > 0 && w <= PAGE && h <= PAGE) {
                boxes.add(new int[]{w, h});
            }
        }
        return boxes;
    }

    /**
     * Explores whether sorting each commit batch largest-first would pack tighter.
     *
     * <p>Offline packers sort by descending size and gain several points of density from it alone.
     * We cannot sort globally — glyphs arrive as they are generated — but {@code CgFontRegistry}
     * commits up to 256 per frame, so each <em>batch</em> could be sorted before insertion. This
     * measures what that would buy, against the same recorded sequence, before anyone writes the
     * production change.
     *
     * <p>Reports rather than asserts: it is an experiment, and the answer decides whether the
     * production change is worth making at all.
     */
    @Test
    public void reportsWhetherBatchSortingWouldPackTighter() throws Exception {
        File manifest = new File(MANIFEST);
        if (!manifest.isFile()) {
            System.out.println("[sortprobe] manifest absent, skipping");
            return;
        }
        List<int[]> boxes = parseGlyphBoxes(manifest);

        System.out.println("[sortprobe] batch  pages  meanUtil   vs unsorted");
        for (int batch : new int[]{1, 64, 256, 1024, Integer.MAX_VALUE}) {
            List<int[]> ordered = new ArrayList<>(boxes);
            if (batch > 1) {
                for (int start = 0; start < ordered.size(); start += batch) {
                    int end = Math.min(ordered.size(), start + batch);
                    List<int[]> slice = new ArrayList<>(ordered.subList(start, end));
                    // Largest area first, the standard offline heuristic.
                    slice.sort((a, b) -> Long.compare((long) b[0] * b[1], (long) a[0] * a[1]));
                    for (int k = start; k < end; k++) {
                        ordered.set(k, slice.get(k - start));
                    }
                }
            }
            int[] result = packAll(ordered);
            System.out.printf(Locale.ROOT, "[sortprobe] %5s  %5d  %7.2f%%%n",
                    batch == Integer.MAX_VALUE ? "all" : String.valueOf(batch),
                    result[0], result[1] / 100.0);
        }
    }

    /** Packs a sequence oldest-first across pages; returns {pageCount, meanUtilisation*100}. */
    private static int[] packAll(List<int[]> boxes) {
        List<MaxRectsPacker> pages = new ArrayList<>();
        pages.add(new MaxRectsPacker(PAGE, PAGE));
        int placed = 0;
        for (int[] box : boxes) {
            boolean done = false;
            for (int i = 0; i < pages.size() && !done; i++) {
                MaxRectsPacker page = pages.get(i);
                if (!page.mayFit(box[0] + SPACING, box[1] + SPACING)) continue;
                done = page.insert(box[0], box[1], SPACING, placed) != null;
            }
            if (!done) {
                MaxRectsPacker fresh = new MaxRectsPacker(PAGE, PAGE);
                fresh.insert(box[0], box[1], SPACING, placed);
                pages.add(fresh);
            }
            placed++;
        }
        double total = 0.0;
        for (MaxRectsPacker page : pages) total += page.utilization() * 100.0;
        return new int[]{pages.size(), (int) Math.round(total / pages.size() * 100)};
    }
}
