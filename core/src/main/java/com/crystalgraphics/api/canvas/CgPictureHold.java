package com.crystalgraphics.api.canvas;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

/**
 * Whether a picture just drawn replaces the one on show: only once it is complete, so a zoom step's text arrives whole
 * rather than with holes. Chromium's pending tree, activated once its raster is done, with a deadline of ours. A kept
 * layer and a recorder's frame each keep one, for the picture another composites.
 *
 * <pre>{@code
 * boolean complete = canvas.degradedDraws() == degradedAtOpen;
 * if (hold.shows(complete, shown != null)) swapIn(drawn);   // else the shown picture stands
 * }</pre>
 *
 * <ul>
 *   <li>The first picture always shows: there is nothing to hold.</li>
 *   <li>After {@value #DEADLINE} incomplete pictures in a row one shows anyway: holes beat a frozen picture.</li>
 *   <li>An incomplete picture must be drawn again next frame, shown or not; the caller sees to that.</li>
 * </ul>
 */
final class CgPictureHold {

    /** Incomplete pictures in a row after which one shows anyway. */
    static final int DEADLINE = 3;

    private final String shownComplete, shownDegraded, heldIncomplete;
    private int held;

    /** Counts {@code <what>.shown-complete}, {@code .shown-degraded} and {@code .held-incomplete}. */
    CgPictureHold(String what) {
        shownComplete = what + ".shown-complete";
        shownDegraded = what + ".shown-degraded";
        heldIncomplete = what + ".held-incomplete";
    }

    /** Whether the picture just drawn, {@code complete} or not, is shown in place of the last, if {@code anyShown}. */
    boolean shows(boolean complete, boolean anyShown) {
        if (complete || !anyShown || ++held >= DEADLINE) {
            CgTrace.add(CgChannels.MISC, complete ? shownComplete : shownDegraded, 1);
            held = 0;
            return true;
        }
        CgTrace.add(CgChannels.MISC, heldIncomplete, 1);
        return false;
    }

    /** Forgets the incomplete pictures held: nothing is on show any more. */
    void reset() {
        held = 0;
    }
}
