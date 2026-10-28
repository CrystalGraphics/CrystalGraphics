package com.crystalgraphics.net.object;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One input's prediction: everything a client shows at once for it, accepted or refused together. Mirrors GAS's
 * {@code FPredictionKey}: side effects register what undoes them ({@code NewRejectedDelegate}) and what keeps them
 * ({@code NewCaughtUpDelegate}), and the server's one verdict runs one list or the other.
 *
 * <pre>{@code
 * // the owner's client, on an input
 * CgPredictionKey key = CgPredictionKey.open();
 * CgCuePlaying beam = cues.predict(MYMOD_CUE_BEAM, at, key);       // shown now, asked of the server
 * montage.jumpToSection("fire", key);                             // jumped now, undone if refused
 * }</pre>
 *
 * <pre>{@code
 * // anything else a prediction did, undone the same way
 * key.onRefused(() -> hud.uncharge());
 * }</pre>
 *
 * <ul>
 *   <li>The verdict is the key's first networked object's ({@link CgNetObject#within}): the server accepts or refuses
 *       that, and every other thing under the key follows it.</li>
 *   <li>A key with no networked object is never answered: it holds until {@link #refuse} or {@link #accept}.</li>
 *   <li>Registering after the verdict runs the callback at once if it matches. Client thread.</li>
 * </ul>
 */
public final class CgPredictionKey {

    /** Where a key stands. */
    public enum State { OPEN, ACCEPTED, REFUSED }

    private static int next;

    private final int id;
    private final List<Runnable> refused = new ArrayList<>(2), accepted = new ArrayList<>(2);
    private State state = State.OPEN;
    /** Whether a networked object already carries the verdict. */
    boolean answered;

    private CgPredictionKey(int id) {
        this.id = id;
    }

    /** A new key for one input. */
    public static CgPredictionKey open() {
        return new CgPredictionKey(++next);
    }

    /** {@code rollback} runs if the key is refused: what undoes a predicted side effect. */
    public CgPredictionKey onRefused(Runnable rollback) {
        Objects.requireNonNull(rollback, "rollback");
        if (state == State.REFUSED) rollback.run();
        else if (state == State.OPEN) refused.add(rollback);
        return this;
    }

    /** {@code keep} runs once the key is accepted: the server made it real. */
    public CgPredictionKey onAccepted(Runnable keep) {
        Objects.requireNonNull(keep, "keep");
        if (state == State.ACCEPTED) keep.run();
        else if (state == State.OPEN) accepted.add(keep);
        return this;
    }

    /** The server refused it, or it timed out: every rollback runs, in the order registered. */
    public void refuse() {
        settle(State.REFUSED, refused);
    }

    /** The server accepted it. */
    public void accept() {
        settle(State.ACCEPTED, accepted);
    }

    public State state() {
        return state;
    }

    /** Its number on this client, for logs. */
    public int id() {
        return id;
    }

    private void settle(State to, List<Runnable> run) {
        if (state != State.OPEN) return;
        state = to;
        List<Runnable> now = new ArrayList<>(run);
        refused.clear();
        accepted.clear();
        for (Runnable r : now) r.run();
    }

    @Override
    public String toString() {
        return "CgPredictionKey[" + id + " " + state + "]";
    }
}
