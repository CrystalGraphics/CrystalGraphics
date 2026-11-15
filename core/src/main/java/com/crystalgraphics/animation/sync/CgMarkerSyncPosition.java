package com.crystalgraphics.animation.sync;

import java.util.Objects;

import javax.annotation.Nullable;

/**
 * A place in a step cycle, independent of any clip: between the marker named {@link #previous} and the one named
 * {@link #next}, {@link #position} of the way (0 at the first, 1 at the second). Unreal's {@code FMarkerSyncAnimPosition};
 * a follower finds the same place in its own clip.
 *
 * <p>A null name is the clip's start (behind) or end (ahead). Mutable and reused: a tick allocates none.</p>
 */
public final class CgMarkerSyncPosition {

    @Nullable
    public String previous, next;
    public float position;

    public CgMarkerSyncPosition set(@Nullable String previous, @Nullable String next, float position) {
        this.previous = previous;
        this.next = next;
        this.position = position;
        return this;
    }

    public CgMarkerSyncPosition set(CgMarkerSyncPosition from) {
        return set(from.previous, from.next, from.position);
    }

    public CgMarkerSyncPosition clear() {
        return set(null, null, 0f);
    }

    /** {@code IsValid}: both markers named. */
    public boolean valid() {
        return previous != null && next != null;
    }

    public boolean same(CgMarkerSyncPosition o) {
        return Objects.equals(previous, o.previous) && Objects.equals(next, o.next) && position == o.position;
    }

    @Override
    public String toString() {
        return "[" + previous + ", " + next + "] " + position;
    }
}
