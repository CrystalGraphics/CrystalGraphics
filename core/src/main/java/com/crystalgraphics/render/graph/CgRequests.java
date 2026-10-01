package com.crystalgraphics.render.graph;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * The requests recorded for one result across a recorder's frames -- a compile asked again each frame until one
 * answers -- and whether any of them has. Any answer will do: the frames that execute run behind the ones being
 * recorded, so the newest request is usually still pending while an older one has answered, and a frame replaced
 * before it executes never answers at all.
 *
 * <pre>{@code
 * if (compiles.failed()) throw new IllegalStateException(compiles.failure());
 * if (!compiles.done()) {
 *     compiles.add(recording.compile(pipeline));   // again next frame, until one answers
 *     return null;
 * }
 * compiles.clear();                                // ready: forget them before asking for something else
 * }</pre>
 *
 * <ul>
 *   <li>Every request must be for the same result: a new source is a {@link #clear()} first.</li>
 *   <li>Keeps the newest {@link #KEPT}. A recorder adds one a frame, so a request older than that belongs to a frame
 *       long executed or replaced.</li>
 *   <li>Watching the newest request alone never sees an answer while recording stays ahead of execution: the
 *       shader-graph previews held their last material for good that way.</li>
 * </ul>
 */
public final class CgRequests {

    /** How many of the newest requests are kept. */
    public static final int KEPT = 16;

    private final CgRequest[] requests = new CgRequest[KEPT];
    private int next, count;

    /** Keeps {@code request}, dropping the oldest once {@link #KEPT} are held. */
    public void add(CgRequest request) {
        requests[next] = request;
        next = (next + 1) % KEPT;
        count = Math.min(count + 1, KEPT);
    }

    /** Whether any kept request has answered. */
    public boolean done() {
        for (int i = 0; i < count; i++) if (requests[i].done()) return true;
        return false;
    }

    /** Whether any kept request failed: the result will not come, however often it is asked for. */
    public boolean failed() {
        return failure() != null;
    }

    /** The first kept failure's reason, or null. */
    @Nullable
    public String failure() {
        for (int i = 0; i < count; i++) {
            if (requests[i].failed()) {
                String reason = requests[i].failure();
                return reason != null ? reason : "failed";
            }
        }
        return null;
    }

    /** Whether nothing has been asked since the last {@link #clear()}. */
    public boolean isEmpty() {
        return count == 0;
    }

    public void clear() {
        Arrays.fill(requests, null);
        next = 0;
        count = 0;
    }
}
