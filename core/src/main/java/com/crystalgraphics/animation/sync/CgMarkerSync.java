package com.crystalgraphics.animation.sync;

import java.util.List;

import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgSyncMarker;

/**
 * A clip's time moved through its sync markers: Unreal's marker sync on {@code UAnimSequence} (AnimSequence.cpp
 * :3373-4442) and {@code UAnimSequenceBase}'s tick by markers (AnimSequenceBase.cpp:597-642). A leader advances and
 * records the markers it passes and where it lands between two; a follower passes the same markers in its own clip and
 * lands at the same fraction between the same names, so feet meet the ground together whatever each clip's length.
 *
 * <pre>{@code
 * time = CgMarkerSync.tickByMarkerAsLeader(walk, record, ctx, time, rate * dt, true);   // writes ctx.start/end, passed
 * time = CgMarkerSync.tickByMarkerAsFollower(run, record2, ctx, time2, ctx.leaderDelta(), true);
 * }</pre>
 *
 * <ul>
 *   <li>Only the context's valid markers (the group's) count; others are stepped over.</li>
 *   <li>Mirrored marker names are not read: no player mirrors its markers yet.</li>
 *   <li>Static and allocation free; the context's arrays grow once.</li>
 * </ul>
 */
public final class CgMarkerSync {

    /** A marker index meaning the clip's start or end ({@code MarkerIndexSpecialValues::AnimationBoundary}). */
    public static final int BOUNDARY = -1;
    /** A marker index not yet found ({@code MarkerIndexSpecialValues::Uninitialized}). */
    public static final int UNINITIALIZED = -2;

    private CgMarkerSync() {
    }

    private static List<CgSyncMarker> markers(CgAnimSequence clip) {
        return clip.settings().markers();
    }

    /**
     * {@code TickByMarkerAsLeader}: {@code record} found if it is not, the start position written, {@code time} advanced by
     * {@code move} through the markers, the end position written. Answers the new time; the time it started from (after
     * its markers were found) is {@link CgAnimTickContext#previousTime}.
     */
    public static float tickByMarkerAsLeader(CgAnimSequence clip, CgMarkerTickRecord record, CgAnimTickContext ctx, float time,
                                             float move, boolean looping) {
        if (!record.valid(looping)) {
            if (ctx.start().valid()) time = indicesForPosition(clip, ctx.start(), looping, record, time);
            else indicesForTime(clip, time, looping, ctx, record);
        }
        position(clip, record.previous, record.next, time, ctx.start());
        ctx.previousTime(time);
        time = advanceAsLeader(clip, looping, move, ctx, time, record);
        position(clip, record.previous, record.next, time, ctx.end());
        return time;
    }

    /** {@code TickByMarkerAsFollower}: {@code record} found from the leader's start if it is not, then advanced after it. */
    public static float tickByMarkerAsFollower(CgAnimSequence clip, CgMarkerTickRecord record, CgAnimTickContext ctx, float time,
                                               float move, boolean looping) {
        if (!record.valid(looping)) time = indicesForPosition(clip, ctx.start(), looping, record, time);
        ctx.previousTime(time);
        return advanceAsFollower(clip, ctx, move, looping, time, record);
    }

    /** {@code GetMarkerIndicesForTime}: the valid markers either side of {@code time} into {@code out}. */
    public static void indicesForTime(CgAnimSequence clip, float time, boolean looping, CgAnimTickContext ctx, CgMarkerTickRecord out) {
        List<CgSyncMarker> markers = markers(clip);
        float length = clip.duration();
        out.previous = BOUNDARY;
        out.timeToPrevious = -time;
        out.next = BOUNDARY;
        out.timeToNext = length - time;
        for (int loop = looping ? -1 : 0, end = looping ? 2 : 1; loop < end; loop++) {
            float offset = loop * length;
            for (int i = 0; i < markers.size(); i++) {
                CgSyncMarker m = markers.get(i);
                if (!ctx.isValid(m.name())) continue;
                float at = m.time() + offset;
                if (at < time) {
                    out.previous = i;
                    out.timeToPrevious = at - time;
                } else {
                    out.next = i;
                    out.timeToNext = at - time;
                    break;
                }
            }
            if (out.next != BOUNDARY) break;
        }
    }

    /** {@code GetMarkerSyncPositionFromMarkerIndicies}: the place between markers {@code previous} and {@code next} at {@code time}. */
    public static CgMarkerSyncPosition position(CgAnimSequence clip, int previous, int next, float time, CgMarkerSyncPosition out) {
        List<CgSyncMarker> markers = markers(clip);
        float length = clip.duration(), prevTime, nextTime;
        String prevName = null, nextName = null;
        if (previous != BOUNDARY && previous >= 0 && previous < markers.size()) {
            prevTime = markers.get(previous).time();
            prevName = markers.get(previous).name();
        } else {
            prevTime = 0f;
        }
        if (next != BOUNDARY && next >= 0 && next < markers.size()) {
            nextTime = markers.get(next).time();
            nextName = markers.get(next).name();
        } else {
            nextTime = length;
        }
        if (prevTime > nextTime) {
            prevTime = prevTime > time ? prevTime - length : prevTime;
            nextTime = nextTime < time ? nextTime + length : nextTime;
        } else if (prevTime > time) {
            time += length;
        }
        if (prevTime == nextTime) prevTime -= length;
        return out.set(prevName, nextName, (time - prevTime) / (nextTime - prevTime));
    }

    /** {@code GetCurrentTimeFromMarkers}: the time {@code position} of the way from marker to marker; the record's distances set. */
    public static float timeFromMarkers(CgAnimSequence clip, CgMarkerTickRecord record, float position) {
        List<CgSyncMarker> markers = markers(clip);
        float length = clip.duration();
        float prevTime = record.previous != BOUNDARY ? markers.get(record.previous).time() : 0f;
        float nextTime = record.next != BOUNDARY ? markers.get(record.next).time() : length;
        if (prevTime >= nextTime) prevTime -= length;
        float time = prevTime + position * (nextTime - prevTime);
        record.timeToPrevious = prevTime - time;
        record.timeToNext = nextTime - time;
        if (time < 0f) time += length;
        return Math.max(0f, Math.min(length, time));
    }

    /**
     * {@code GetMarkerIndicesForPosition}: the markers named by {@code position} nearest {@code time}, into {@code record};
     * answers the time there. A clip that does not loop (a transition's) stays where it is.
     */
    public static float indicesForPosition(CgAnimSequence clip, CgMarkerSyncPosition position, boolean looping, CgMarkerTickRecord record,
                                           float time) {
        List<CgSyncMarker> markers = markers(clip);
        int n = markers.size();
        float length = clip.duration();
        if (!looping || position.previous == null && position.next == null) {
            record.previous = BOUNDARY;
            record.next = BOUNDARY;
            for (int i = 0; i < n; i++) {
                CgSyncMarker m = markers.get(i);
                if (time > m.time() && m.name().equals(position.previous)) {
                    record.previous = i;
                    record.timeToPrevious = m.time() - time;
                } else if (time < m.time() && m.name().equals(position.next)) {
                    record.next = i;
                    record.timeToNext = m.time() - time;
                    break;
                }
            }
            return time;
        }
        if (position.previous == null) {
            record.previous = BOUNDARY;
            for (int i = 0; i < n; i++) {
                if (markers.get(i).name().equals(position.next)) {
                    record.next = i;
                    return timeFromMarkers(clip, record, position.position);
                }
            }
            throw new IllegalStateException(clip.name() + " lacks sync marker " + position.next);
        }
        if (position.next == null) {
            record.next = BOUNDARY;
            for (int i = n - 1; i >= 0; i--) {
                if (markers.get(i).name().equals(position.previous)) {
                    record.previous = i;
                    return timeFromMarkers(clip, record, position.position);
                }
            }
            throw new IllegalStateException(clip.name() + " lacks sync marker " + position.previous);
        }
        float best = Float.MAX_VALUE, input = time;
        for (int p = 0; p < n; p++) {
            CgSyncMarker prev = markers.get(p);
            if (!prev.name().equals(position.previous)) continue;
            int end = looping ? n + p + 1 : n;
            for (int count = p + 1; count < end; count++) {
                int q = count % n;
                if (!markers.get(q).name().equals(position.next)) continue;
                float nextTime = markers.get(q).time();
                boolean looped = false;
                if (nextTime < prev.time()) {
                    nextTime += length;
                    looped = true;
                }
                float candidate = prev.time() + position.position * (nextTime - prev.time());
                float diff = Math.abs(candidate - input);
                if (diff < best) {
                    best = diff;
                    record.previous = p;
                    record.next = q;
                    time = timeFromMarkers(clip, record, position.position);
                } else if (looped) {
                    // Past the end: the same place near the start is the other candidate
                    diff = Math.abs(candidate - length - input);
                    if (diff < best) {
                        best = diff;
                        record.previous = p;
                        record.next = q;
                        time = timeFromMarkers(clip, record, position.position);
                    }
                }
                break;
            }
            if (record.previous == UNINITIALIZED) {
                // No next marker: the nearest previous one before the time, and the end
                float d = time - prev.time();
                int use = p + 1;
                while (d > 0f && use < n) d = time - markers.get(use++).time();
                record.previous = use - 1;
                record.next = BOUNDARY;
                return timeFromMarkers(clip, record, position.position);
            }
        }
        if (record.previous == UNINITIALIZED || record.next == UNINITIALIZED) {
            throw new IllegalStateException(clip.name() + " has no " + position.previous + " then " + position.next);
        }
        return time;
    }

    /** {@code AdvanceMarkerForwards}: from {@code index}, the next marker named {@code name}, or the boundary. */
    static int advanceForwards(List<CgSyncMarker> markers, int index, String name, boolean looping) {
        int n = markers.size(), left = n;
        while (!markers.get(index).name().equals(name) && --left >= 0) {
            index++;
            if (index == n && !looping) break;
            index %= n;
        }
        return index >= 0 && index < n && markers.get(index).name().equals(name) ? index : BOUNDARY;
    }

    /** {@code AdvanceMarkerBackwards}: from {@code index}, the previous marker named {@code name}, or the boundary. */
    static int advanceBackwards(List<CgSyncMarker> markers, int index, String name, boolean looping) {
        int n = markers.size(), left = n, counter = n - 1 - index;
        while (!markers.get(index).name().equals(name) && --left >= 0) {
            if (index == 0 && !looping) break;
            counter = (counter + 1) % n;
            index = n - 1 - counter;
        }
        return index >= 0 && index < n && markers.get(index).name().equals(name) ? index : BOUNDARY;
    }

    private static boolean matches(List<CgSyncMarker> markers, int index, String name) {
        return index == BOUNDARY || markers.get(index).name().equals(name);
    }

    /** {@code ValidateCurrentPosition}: the record moved onto {@code position}'s names, and the time with it where both are found. */
    static float validate(CgAnimSequence clip, CgMarkerSyncPosition position, boolean forwards, boolean looping, float time,
                          CgMarkerTickRecord record) {
        List<CgSyncMarker> markers = markers(clip);
        int n = markers.size();
        if (forwards) {
            if (!matches(markers, record.previous, position.previous)) {
                record.previous = advanceForwards(markers, record.previous, position.previous, looping);
                record.next = record.previous + 1;
                if (record.next >= n) record.next = looping ? record.next % n : BOUNDARY;
            }
            if (!matches(markers, record.next, position.next)) {
                record.next = advanceForwards(markers, record.next, position.next, looping);
            }
        } else {
            if (!matches(markers, record.next, position.next)) {
                record.next = advanceBackwards(markers, record.next, position.next, looping);
                record.previous = record.next == BOUNDARY || record.next == 0 && looping ? n - 1 : record.next - 1;
            }
            if (!matches(markers, record.previous, position.previous)) {
                record.previous = advanceBackwards(markers, record.previous, position.previous, looping);
            }
        }
        if (record.previous != BOUNDARY && record.next != BOUNDARY) time = timeFromMarkers(clip, record, position.position);
        return time;
    }

    /** {@code AdvanceMarkerPhaseAsLeader}: {@code time} moved by {@code move}, stopping at each valid marker and recording it. */
    static float advanceAsLeader(CgAnimSequence clip, boolean looping, float move, CgAnimTickContext ctx, float time,
                                 CgMarkerTickRecord record) {
        List<CgSyncMarker> markers = markers(clip);
        int n = markers.size();
        float length = clip.duration();
        time = Math.max(0f, Math.min(length, time));
        if (move >= 0f) {
            while (true) {
                if (record.next == BOUNDARY) {
                    float before = time;
                    time = Math.min(time + move, length);
                    record.timeToNext = length - time;
                    record.timeToPrevious -= time - before;
                    break;
                }
                CgSyncMarker next = markers.get(record.next);
                if (move > record.timeToNext) {
                    time = next.time();
                    move -= record.timeToNext;
                    record.previous = record.next;
                    record.timeToPrevious = 0f;
                    ctx.pass(next.name(), move);
                    float offset = 0f;
                    do {
                        record.next++;
                        if (record.next >= n) {
                            if (!looping) {
                                record.next = BOUNDARY;
                                break;
                            }
                            record.next = 0;
                            offset = length;
                        }
                    } while (!ctx.isValid(markers.get(record.next).name()));
                    if (record.next != BOUNDARY) record.timeToNext = offset + markers.get(record.next).time() - time;
                } else {
                    time = (time + move) % length;
                    if (time < 0f) time += length;
                    record.timeToNext -= move;
                    record.timeToPrevious -= move;
                    break;
                }
            }
        } else {
            while (true) {
                if (record.previous == BOUNDARY) {
                    float before = time;
                    time = Math.max(time + move, 0f);
                    record.timeToPrevious = time;
                    record.timeToNext -= time - before;
                    break;
                }
                CgSyncMarker prev = markers.get(record.previous);
                if (move < record.timeToPrevious) {
                    time = prev.time();
                    move -= record.timeToPrevious;
                    record.next = record.previous;
                    record.timeToNext = 0f;
                    ctx.pass(prev.name(), move);
                    float offset = 0f;
                    do {
                        record.previous--;
                        if (record.previous < 0) {
                            if (!looping) {
                                record.previous = BOUNDARY;
                                break;
                            }
                            record.previous = n - 1;
                            offset -= length;
                        }
                    } while (!ctx.isValid(markers.get(record.previous).name()));
                    if (record.previous != BOUNDARY) record.timeToPrevious = offset + markers.get(record.previous).time() - time;
                } else {
                    time = (time + move) % length;
                    if (time < 0f) time += length;
                    record.timeToPrevious -= move;
                    record.timeToNext -= move;
                    break;
                }
            }
        }
        return time;
    }

    /** {@code AdvanceMarkerPhaseAsFollower}: through the markers the leader passed, onto its end position. */
    static float advanceAsFollower(CgAnimSequence clip, CgAnimTickContext ctx, float move, boolean looping, float time,
                                   CgMarkerTickRecord record) {
        List<CgSyncMarker> markers = markers(clip);
        float length = clip.duration();
        boolean forwards = move >= 0f;
        time = validate(clip, ctx.start(), forwards, looping, time, record);
        CgMarkerSyncPosition end = ctx.end();
        if (forwards) {
            int i = 0;
            do {
                if (record.next == BOUNDARY) {
                    time = Math.min(time + move, length);
                    break;
                } else if (i < ctx.passedCount()) {
                    record.previous = record.next;
                    record.next = advanceForwards(markers, record.next, ctx.passedName(i), looping);
                    if (record.next == BOUNDARY) move = ctx.passedDelta(i);
                    i++;
                }
            } while (i < ctx.passedCount());
            if (end.next == null) record.next = BOUNDARY;
            if (record.next != BOUNDARY && ctx.passedCount() > 0) {
                record.previous = record.next;
                record.next = advanceForwards(markers, record.next, end.next, looping);
            }
        } else {
            int i = 0;
            do {
                if (record.previous == BOUNDARY) {
                    time = Math.max(time + move, 0f);
                    break;
                } else if (i < ctx.passedCount()) {
                    record.next = record.previous;
                    record.previous = advanceBackwards(markers, record.previous, ctx.passedName(i), looping);
                    if (record.previous == BOUNDARY) move = ctx.passedDelta(i);
                    i++;
                }
            } while (i < ctx.passedCount());
            if (end.previous == null) record.previous = BOUNDARY;
            if (record.previous != BOUNDARY && ctx.passedCount() > 0) {
                record.next = record.previous;
                record.previous = advanceBackwards(markers, record.previous, end.previous, looping);
            }
        }
        if (record.previous != BOUNDARY && record.next != BOUNDARY) time = timeFromMarkers(clip, record, end.position);
        return time;
    }
}
