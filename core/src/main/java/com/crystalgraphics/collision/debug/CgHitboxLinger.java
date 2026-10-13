package com.crystalgraphics.collision.debug;


import com.crystalgraphics.collision.hitbox.CgHitbox;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;

/** Stopped hitboxes kept drawn a moment as their last tick tested them: a blast's front lives a few ticks. One thread. */
final class CgHitboxLinger {

    static final long HOLD_NANOS = 500_000_000L;
    private static final int MAX = 256;

    private CgHitbox[] held = new CgHitbox[16];
    private long[] until = new long[16];
    private int count;

    /** Moves each stopped hitbox out of {@code seen} into those held. */
    void take(Set<CgHitbox> seen, long now) {
        for (Iterator<CgHitbox> it = seen.iterator(); it.hasNext(); ) {
            CgHitbox hitbox = it.next();
            if (!hitbox.stopped()) continue;
            it.remove();
            if (count == MAX) continue;
            if (count == held.length) {
                held = Arrays.copyOf(held, count * 2);
                until = Arrays.copyOf(until, count * 2);
            }
            held[count] = hitbox;
            until[count++] = now + HOLD_NANOS;
        }
    }

    /** Lets go of those held long enough; how many remain, each read by {@link #get}. */
    int expire(long now) {
        int kept = 0;
        for (int i = 0; i < count; i++) {
            if (until[i] - now <= 0) continue;
            held[kept] = held[i];
            until[kept++] = until[i];
        }
        Arrays.fill(held, kept, count, null);
        count = kept;
        return count;
    }

    CgHitbox get(int i) {
        return held[i];
    }

    void clear() {
        if (count == 0) return;
        Arrays.fill(held, 0, count, null);
        count = 0;
    }
}
