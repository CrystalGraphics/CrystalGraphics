package com.crystalgraphics.animation.sync;

/**
 * Where a player stands among its clip's sync markers: the marker behind and the one ahead, by index, and the seconds
 * to each. Unreal's {@code FMarkerTickRecord}; each player keeps one between frames.
 *
 * <ul>
 *   <li>An index is a marker's, {@link CgMarkerSync#BOUNDARY} the clip's start or end, {@link CgMarkerSync#UNINITIALIZED}
 *       not yet found.</li>
 *   <li>Times are signed from the player's time: the marker behind is at a negative time.</li>
 * </ul>
 */
public final class CgMarkerTickRecord {

    public int previous = CgMarkerSync.UNINITIALIZED, next = CgMarkerSync.UNINITIALIZED;
    public float timeToPrevious, timeToNext;

    /** Forgotten: found again from the player's time at the next tick. */
    public void reset() {
        previous = next = CgMarkerSync.UNINITIALIZED;
    }

    /** {@code IsValid}: both markers known; a looping clip's boundary does not count. */
    public boolean valid(boolean looping) {
        int threshold = looping ? CgMarkerSync.BOUNDARY : CgMarkerSync.UNINITIALIZED;
        return previous > threshold && next > threshold;
    }

    public void set(CgMarkerTickRecord from) {
        previous = from.previous;
        next = from.next;
        timeToPrevious = from.timeToPrevious;
        timeToNext = from.timeToNext;
    }
}
