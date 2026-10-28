package com.crystalgraphics.sequence.definition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * A sequence's evaluation field: all time cut into segments at every section boundary, each listing the entries
 * active over it, a section of a track, its overlaps already resolved. Unreal's {@code FMovieSceneEntityComponentField}
 * and the evaluation tree each track builds ({@code UMovieSceneTrack::UpdateEvaluationTree}). A player that crosses a
 * segment boundary re-reads its entries; inside a segment nothing enters or leaves.
 *
 * <pre>{@code
 * CgSequenceField field = definition.field();
 * int s = field.segmentAt(tick);
 * for (int k = 0; k < field.entryCount(s); k++) {
 *     int e = field.entry(s, k);
 *     CgSequenceTrack track = definition.tracks().get(field.track(e));
 *     int section = field.section(e);                 // -1: the whole track, read by its evaluator
 *     boolean warming = field.flags(e) == CgSequenceField.PRE_ROLL;
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Segment {@code s} covers {@code [start(s), start(s + 1))}; the first starts at {@link Integer#MIN_VALUE} and the
 *       last runs on forever.</li>
 *   <li>Muted tracks, inactive sections, empty ranges and triggered sections are never entries.</li>
 *   <li>An entry is one object across every segment it spans: compare entry ids to see what entered or left.</li>
 * </ul>
 */
public final class CgSequenceField {

    /** An entry's flags: its section running in its range, or before it or after it hidden. */
    public static final int NONE = 0, PRE_ROLL = 1, POST_ROLL = 2;

    private final int[] starts, offsets, entries, entryTrack, entrySection, entryFlags;

    private CgSequenceField(int[] starts, int[] offsets, int[] entries, int[] entryTrack, int[] entrySection, int[] entryFlags) {
        this.starts = starts;
        this.offsets = offsets;
        this.entries = entries;
        this.entryTrack = entryTrack;
        this.entrySection = entrySection;
        this.entryFlags = entryFlags;
    }

    /** How many segments. */
    public int segments() {
        return starts.length;
    }

    /** Segment {@code s}'s first tick. */
    public int start(int s) {
        return starts[s];
    }

    /** The tick after segment {@code s}'s last, or {@link Integer#MAX_VALUE} for the last segment. */
    public int end(int s) {
        return s + 1 < starts.length ? starts[s + 1] : Integer.MAX_VALUE;
    }

    /** The segment holding {@code tick}. */
    public int segmentAt(int tick) {
        int lo = 0, hi = starts.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (starts[mid] <= tick) lo = mid + 1;
            else hi = mid;
        }
        return lo - 1;
    }

    /** How many entries segment {@code s} holds. */
    public int entryCount(int s) {
        return offsets[s + 1] - offsets[s];
    }

    /** Segment {@code s}'s {@code k}th entry's id. */
    public int entry(int s, int k) {
        return entries[offsets[s] + k];
    }

    /** How many distinct entries. */
    public int entryIds() {
        return entryTrack.length;
    }

    /** Entry {@code e}'s track index. */
    public int track(int e) {
        return entryTrack[e];
    }

    /** Entry {@code e}'s section index in its track, or -1 for the whole track. */
    public int section(int e) {
        return entrySection[e];
    }

    /** Entry {@code e}'s {@link #NONE}, {@link #PRE_ROLL} or {@link #POST_ROLL}. */
    public int flags(int e) {
        return entryFlags[e];
    }

    /** {@code tracks}' field. */
    static CgSequenceField build(List<CgSequenceTrack> tracks) {
        // Every track's runs: a section (or the whole track) over [from, to) with its flags.
        List<int[]> runs = new ArrayList<>();
        TreeSet<Integer> cuts = new TreeSet<>();
        cuts.add(Integer.MIN_VALUE);
        for (CgSequenceTrack t : tracks) {
            if (t.muted()) continue;
            if (t.population() == CgSequenceTrack.Population.WHOLE_TRACK) {
                runs.add(new int[] {t.index(), -1, NONE, Integer.MIN_VALUE, Integer.MAX_VALUE});
            } else {
                populate(t, runs, cuts);
            }
            // UMovieSceneTrack::AddSectionPrePostRollRangesToTree: always, whatever else overlaps.
            for (CgSequenceSection s : t.sections()) {
                if (!placed(s)) continue;
                if (s.preRoll() > 0 && s.from() != CgSequenceSection.OPEN_START) {
                    run(runs, cuts, t.index(), s.index(), PRE_ROLL, s.from() - s.preRoll(), s.from());
                }
                if (s.postRoll() > 0 && s.to() != CgSequenceSection.OPEN_END) {
                    run(runs, cuts, t.index(), s.index(), POST_ROLL, s.to(), s.to() + s.postRoll());
                }
            }
        }
        int[] bounds = cuts.stream().mapToInt(Integer::intValue).toArray();
        Map<Long, Integer> ids = new HashMap<>();
        List<int[]> entryList = new ArrayList<>();
        List<List<Integer>> lists = new ArrayList<>(bounds.length);
        for (int i = 0; i < bounds.length; i++) lists.add(new ArrayList<>());
        for (int[] r : runs) {
            long key = (long) r[0] << 34 | (long) (r[1] + 1) << 2 | r[2];
            Integer id = ids.get(key);
            if (id == null) {
                id = entryList.size();
                ids.put(key, id);
                entryList.add(new int[] {r[0], r[1], r[2]});
            }
            for (int s = Arrays.binarySearch(bounds, r[3]); s < bounds.length && bounds[s] < r[4]; s++) lists.get(s).add(id);
        }
        // Merge neighbours that hold the same entries, as Unreal consolidates adjoining ranges.
        List<Integer> starts = new ArrayList<>();
        List<int[]> sets = new ArrayList<>();
        for (int s = 0; s < bounds.length; s++) {
            int[] set = lists.get(s).stream().mapToInt(Integer::intValue).sorted().distinct().toArray();
            if (!sets.isEmpty() && Arrays.equals(sets.get(sets.size() - 1), set)) continue;
            starts.add(bounds[s]);
            sets.add(set);
        }
        int[] offsets = new int[sets.size() + 1];
        for (int s = 0; s < sets.size(); s++) offsets[s + 1] = offsets[s] + sets.get(s).length;
        int[] flat = new int[offsets[sets.size()]];
        for (int s = 0; s < sets.size(); s++) System.arraycopy(sets.get(s), 0, flat, offsets[s], sets.get(s).length);
        int[] et = new int[entryList.size()], es = new int[entryList.size()], ef = new int[entryList.size()];
        for (int e = 0; e < et.length; e++) {
            et[e] = entryList.get(e)[0];
            es[e] = entryList.get(e)[1];
            ef[e] = entryList.get(e)[2];
        }
        return new CgSequenceField(starts.stream().mapToInt(Integer::intValue).toArray(), offsets, flat, et, es, ef);
    }

    /** A section placed on the timeline: active, non-empty, not started by an event. */
    private static boolean placed(CgSequenceSection s) {
        return s.active() && s.trigger() == null && s.from() < s.to();
    }

    /** One track's sections by its population: {@code FEvaluationTreePopulationRules}. */
    private static void populate(CgSequenceTrack t, List<int[]> runs, TreeSet<Integer> cuts) {
        List<CgSequenceSection> sorted = new ArrayList<>();
        for (CgSequenceSection s : t.sections()) if (placed(s)) sorted.add(s);
        if (t.population() == CgSequenceTrack.Population.BLENDED) {
            for (CgSequenceSection s : sorted) run(runs, cuts, t.index(), s.index(), NONE, s.from(), s.to());
            return;
        }
        // SortByOverlapPriorityAndRow: row ascending, then priority descending; ties in file order.
        sorted.sort((a, b) -> a.row() != b.row() ? Integer.compare(a.row(), b.row())
                : a.overlapPriority() != b.overlapPriority() ? Integer.compare(b.overlapPriority(), a.overlapPriority())
                : Integer.compare(a.index(), b.index()));
        TreeSet<Integer> local = new TreeSet<>();
        for (CgSequenceSection s : sorted) {
            local.add(s.from());
            local.add(s.to());
        }
        int[] b = local.stream().mapToInt(Integer::intValue).toArray();
        // Per elementary interval, the rows already taken by a section that does not blend.
        boolean[][] taken = new boolean[b.length][];
        for (CgSequenceSection s : sorted) {
            boolean blends = s.blend() != null;
            int i = Arrays.binarySearch(b, s.from()), runFrom = Integer.MIN_VALUE;
            boolean inRun = false;
            for (; i < b.length - 1 && b[i] < s.to(); i++) {
                boolean add = blends || !rowTaken(taken, i, s.row());
                if (add) {
                    if (!blends) markRow(taken, i, s.row());
                    if (!inRun) runFrom = b[i];
                    inRun = true;
                } else if (inRun) {
                    run(runs, cuts, t.index(), s.index(), NONE, runFrom, b[i]);
                    inRun = false;
                }
            }
            if (inRun) run(runs, cuts, t.index(), s.index(), NONE, runFrom, b[i]);
        }
    }

    private static boolean rowTaken(boolean[][] taken, int i, int row) {
        return taken[i] != null && row < taken[i].length && taken[i][row];
    }

    private static void markRow(boolean[][] taken, int i, int row) {
        if (taken[i] == null || taken[i].length <= row) taken[i] = taken[i] == null ? new boolean[row + 1] : Arrays.copyOf(taken[i], row + 1);
        taken[i][row] = true;
    }

    private static void run(List<int[]> runs, TreeSet<Integer> cuts, int track, int section, int flags, int from, int to) {
        if (from >= to) return;
        runs.add(new int[] {track, section, flags, from, to});
        cuts.add(from);
        if (to != Integer.MAX_VALUE) cuts.add(to);
    }
}
