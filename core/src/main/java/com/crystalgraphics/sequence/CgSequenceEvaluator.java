package com.crystalgraphics.sequence;

import javax.annotation.Nullable;

/**
 * The work of one track kind, run by the {@link CgSequencer} over the active entries of that kind across every playing
 * sequence: Unreal's entity systems, one a kind of work, each iterating all instances at once. The built-in state
 * kinds ({@code transform}, {@code visibility}, {@code parameter}, {@code material}, {@code property}, {@code global})
 * have one; a system that plays a kind of its own registers one.
 *
 * <pre>{@code
 * final class Flash implements CgSequenceEvaluator {
 *     @Override public String kind() { return "fade"; }
 *
 *     @Override public void bind(CgSequencePlayer player, CgSequenceTrack track, CgSequenceSection section) {
 *         player.data(track, section, new FadeState());              // once, as the entry enters
 *     }
 *
 *     @Override public void evaluate(CgSequencePlayer player, CgSequenceTrack track, CgSequenceSection section, float tick) {
 *         FadeState state = (FadeState) player.data(track, section);
 *         state.amount = section.channel("amount").eval(tick);
 *     }
 * }
 *
 * world.subsystem(CgSequencer.class).register(new Flash());
 * }</pre>
 *
 * <ul>
 *   <li>Register before a sequence of that kind plays: a kind with no evaluator is skipped, its entries never bound.</li>
 *   <li>{@code section} is null for a track that evaluates over all time ({@link CgSequenceTrack.Population#WHOLE_TRACK}).</li>
 *   <li>{@code evaluate} runs every frame an entry is active, on the frame thread: allocate nothing in it.</li>
 *   <li>{@code tick} is the sequence's time at its tick resolution, a sub-frame allowed.</li>
 * </ul>
 */
public interface CgSequenceEvaluator {

    /** The track kind it evaluates. */
    String kind();

    /** Called once, the first time an entry of its kind becomes active in a player: resolve what it writes. */
    default void bind(CgSequencePlayer player, CgSequenceTrack track, @Nullable CgSequenceSection section) {
    }

    /** Evaluates an active entry at {@code tick}. */
    void evaluate(CgSequencePlayer player, CgSequenceTrack track, @Nullable CgSequenceSection section, float tick);

    /** After the frame's blend: Unreal's post-evaluation phase, where queued work runs. */
    default void postEvaluation() {
    }

    /** {@code player} was stopped and leaves: end what it began. */
    default void stopped(CgSequencePlayer player) {
    }
}
