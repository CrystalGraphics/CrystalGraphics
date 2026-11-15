package com.crystalgraphics.animation.sync;

import java.util.Arrays;

/**
 * What a player's tick reads and writes: Unreal's {@code FAnimAssetTickContext} with its {@code FMarkerTickContext}. The
 * group's leader ticks first and writes where it went (its length ratio, its marker positions, the markers it passed);
 * the followers then read it. One per {@link CgAnimSync}, reset for each group: a tick allocates nothing once warm.
 *
 * <pre>{@code
 * // a player's tick, as a sequence player's
 * if (ctx.leader()) {
 *     ctx.leaderDelta(rate * ctx.delta());
 *     ...
 *     ctx.ratio(time / length);
 * } else {
 *     time = ctx.ratio() * length;          // no markers: the leader's fraction of its clip
 * }
 * }</pre>
 */
public final class CgAnimTickContext {

    private float delta, leaderDelta, previousRatio, ratio, previousTime;
    private boolean leader, markerPositionValid, single, resync;
    private String[] valid = new String[4];
    private int validCount;
    private final CgMarkerSyncPosition start = new CgMarkerSyncPosition(), end = new CgMarkerSyncPosition();
    private String[] passedNames = new String[4];
    private float[] passedDeltas = new float[4];
    private int passed;

    /** Ready for a group's leader: {@code validMarkers} the group's ({@code count} of them), none for length sync. */
    void begin(float delta, boolean single, String[] validMarkers, int count) {
        this.delta = delta;
        this.single = single;
        leaderDelta = previousRatio = ratio = previousTime = 0f;
        leader = true;
        resync = false;
        if (valid.length < count) valid = new String[Math.max(count, valid.length * 2)];
        if (count > 0) System.arraycopy(validMarkers, 0, valid, 0, count);
        validCount = count;
        markerPositionValid = count > 0;
        start.clear();
        end.clear();
        passed = 0;
    }

    /** This frame's seconds. */
    public float delta() {
        return delta;
    }

    /** Whether the player ticking leads its group (or plays ungrouped). */
    public boolean leader() {
        return leader;
    }

    void toFollower() {
        leader = false;
    }

    /** Whether the player ticking is its group's only member ({@code IsSingleAnimationContext}). */
    public boolean single() {
        return single;
    }

    /** The leader's move this tick, seconds of its clip: what a marker follower advances by. */
    public float leaderDelta() {
        return leaderDelta;
    }

    public void leaderDelta(float d) {
        leaderDelta = d;
    }

    /** The leader's fraction of its clip before its tick. */
    public float previousRatio() {
        return previousRatio;
    }

    public void previousRatio(float r) {
        previousRatio = r;
    }

    /** The leader's fraction of its clip after its tick: where a length-synced follower goes. */
    public float ratio() {
        return ratio;
    }

    public void ratio(float r) {
        ratio = r;
    }

    void ratios(float previous, float now) {
        previousRatio = previous;
        ratio = now;
    }

    /** {@code CanUseMarkerPosition}: whether the group syncs by markers this tick. */
    public boolean canUseMarkerPosition() {
        return markerPositionValid;
    }

    void invalidateMarkerSync() {
        markerPositionValid = false;
    }

    /** {@code ShouldResyncToSyncGroup}: a player rejoining jumps to the group's ratio. */
    public boolean resync() {
        return resync;
    }

    void resync(boolean r) {
        resync = r;
    }

    /** The group's marker names, alphabetical: those a tick may stop at. */
    public int validCount() {
        return validCount;
    }

    public String valid(int i) {
        return valid[i];
    }

    public boolean isValid(String name) {
        for (int i = 0; i < validCount; i++) if (valid[i].equals(name)) return true;
        return false;
    }

    /** The leader's place before its tick; a follower starts its search from it. */
    public CgMarkerSyncPosition start() {
        return start;
    }

    /** The leader's place after its tick; a follower lands on it. */
    public CgMarkerSyncPosition end() {
        return end;
    }

    /** The time a by-marker tick started from, after its markers were found ({@code OutPreviousTime}). */
    public float previousTime() {
        return previousTime;
    }

    public void previousTime(float t) {
        previousTime = t;
    }

    // ── markers passed ───────────────────────────────────────────────────────────────────

    /** {@code MarkersPassedThisTick}: how many. */
    public int passedCount() {
        return passed;
    }

    public String passedName(int i) {
        return passedNames[i];
    }

    /** How much of the move was left when the marker was passed, seconds ({@code DeltaTimeWhenPassed}). */
    public float passedDelta(int i) {
        return passedDeltas[i];
    }

    public void pass(String name, float deltaWhenPassed) {
        if (passed == passedNames.length) {
            passedNames = Arrays.copyOf(passedNames, passed * 2);
            passedDeltas = Arrays.copyOf(passedDeltas, passed * 2);
        }
        passedNames[passed] = name;
        passedDeltas[passed++] = deltaWhenPassed;
    }

    void clearPassed() {
        passed = 0;
    }

    /**
     * {@code FAnimationRuntime::AdvanceTime} (AnimationRuntime.cpp:1562-1600): {@code time} moved by {@code move},
     * wrapped by {@code fmod} when looping, else clamped to {@code length}.
     */
    public static float advanceTime(boolean looping, float move, float time, float length) {
        float next = time + move;
        if (next < 0f || next > length) {
            if (looping) {
                if (length != 0f) {
                    next %= length;
                    if (next < 0f) next += length;
                } else {
                    next = 0f;
                }
            } else {
                next = Math.max(0f, Math.min(length, next));
            }
        }
        return next;
    }
}
