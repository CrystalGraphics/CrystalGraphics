package com.crystalgraphics.animation.state;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

/**
 * A state machine: states, the transitions between them, and where it starts. Unreal's
 * {@code FBakedAnimationStateMachine}: immutable, shared by every graph that plays it through a
 * {@link CgStateMachineNode}.
 *
 * <pre>{@code
 * static final CgStateMachine LOCOMOTION = CgStateMachine.builder("locomotion")
 *         .state(CgAnimState.of("idle", CgSequencePlayerNode.of(IDLE).loop()))
 *         .state(CgAnimState.of("run", CgSequencePlayerNode.of(RUN).loop()))
 *         .state(CgAnimState.of("land", CgSequencePlayerNode.of(LAND)).alwaysResetOnEntry())
 *         .transition(CgAnimTransition.of("idle", "run", CgAnimRule.variable(SPEED, CgAnimRule.Op.GREATER, 0.1f)))
 *         .transition(CgAnimTransition.of("run", "idle", CgAnimRule.variable(SPEED, CgAnimRule.Op.LESS_EQUAL, 0.1f)))
 *         .transition(CgAnimTransition.automatic("land", "idle").crossfade(0.1f))
 *         .build();                                        // starts in its first state, "idle"
 *
 * CgAnimGraph hero = CgAnimGraph.builder("mymod:hero", skeleton).variables(SPEED)
 *         .output(CgSlotNode.of(CgAnimGraph.DEFAULT_SLOT, CgStateMachineNode.of(LOCOMOTION)))
 *         .build();
 * }</pre>
 *
 * <ul>
 *   <li>It starts in {@link Builder#initial}, its first state by default, which must not be a conduit.</li>
 *   <li>Up to {@link Builder#maxTransitionsPerFrame} (3) transitions are taken in one frame, each from the state the last
 *       entered.</li>
 *   <li>On its first frame the transitions it takes are entered at once, not crossfaded
 *       ({@code bSkipFirstUpdateTransition}).</li>
 *   <li>A transition naming a state the machine lacks fails when it builds.</li>
 * </ul>
 */
public final class CgStateMachine {

    private final String name;
    private final List<CgAnimState> states;
    private final List<CgAnimTransition> transitions;
    private final int initial, maxTransitionsPerFrame;
    private final boolean skipFirstUpdateTransition, reinitializeOnBecomingRelevant;
    /** Each state's exits: transition indices, by priority then as made. */
    private final int[][] exits;
    private final int[] from, to;

    private CgStateMachine(Builder b) {
        this.name = b.name;
        this.states = List.copyOf(b.states);
        this.transitions = List.copyOf(b.transitions);
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < states.size(); i++) {
            if (index.put(states.get(i).name(), i) != null) throw new IllegalArgumentException(name + ": state " + states.get(i).name() + " twice");
        }
        if (states.isEmpty()) throw new IllegalArgumentException(name + " has no state");
        Integer start = b.initial == null ? Integer.valueOf(0) : index.get(b.initial);
        if (start == null) throw new IllegalArgumentException(name + ": initial state " + b.initial + " is not one of its states");
        if (states.get(start).conduit()) throw new IllegalArgumentException(name + ": initial state " + states.get(start).name() + " is a conduit");
        this.initial = start;
        this.maxTransitionsPerFrame = b.maxTransitionsPerFrame;
        this.skipFirstUpdateTransition = b.skipFirstUpdateTransition;
        this.reinitializeOnBecomingRelevant = b.reinitializeOnBecomingRelevant;
        from = new int[transitions.size()];
        to = new int[transitions.size()];
        List<List<Integer>> out = new ArrayList<>();
        for (int i = 0; i < states.size(); i++) out.add(new ArrayList<>());
        for (int t = 0; t < transitions.size(); t++) {
            CgAnimTransition tr = transitions.get(t);
            Integer f = index.get(tr.from()), d = index.get(tr.to());
            if (f == null || d == null) {
                throw new IllegalArgumentException(name + ": transition " + tr.from() + " -> " + tr.to() + " names "
                        + (f == null ? tr.from() : tr.to()) + ", not one of its states");
            }
            from[t] = f;
            to[t] = d;
            out.get(f).add(t);
        }
        exits = new int[states.size()][];
        for (int s = 0; s < exits.length; s++) {
            List<Integer> e = out.get(s);
            e.sort(Comparator.comparingInt(t -> transitions.get(t).priority()));   // stable: as made among equals
            exits[s] = e.stream().mapToInt(Integer::intValue).toArray();
        }
    }

    /** A machine named {@code name}, built state by state. */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    public List<CgAnimState> states() {
        return states;
    }

    public List<CgAnimTransition> transitions() {
        return transitions;
    }

    /** State {@code name}'s index, or -1. */
    public int state(String name) {
        for (int i = 0; i < states.size(); i++) if (states.get(i).name().equals(name)) return i;
        return -1;
    }

    public int initial() {
        return initial;
    }

    public int maxTransitionsPerFrame() {
        return maxTransitionsPerFrame;
    }

    public boolean skipFirstUpdateTransition() {
        return skipFirstUpdateTransition;
    }

    public boolean reinitializeOnBecomingRelevant() {
        return reinitializeOnBecomingRelevant;
    }

    /** State {@code state}'s exits, transition indices in the order tried. */
    int[] exits(int state) {
        return exits[state];
    }

    int from(int transition) {
        return from[transition];
    }

    int to(int transition) {
        return to[transition];
    }

    @Override
    public String toString() {
        return "CgStateMachine(" + name + ")";
    }

    /** Builds a {@link CgStateMachine}. */
    public static final class Builder {

        private final String name;
        private final List<CgAnimState> states = new ArrayList<>();
        private final List<CgAnimTransition> transitions = new ArrayList<>();
        @Nullable
        private String initial;
        private int maxTransitionsPerFrame = 3;
        private boolean skipFirstUpdateTransition = true, reinitializeOnBecomingRelevant = true;

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        public Builder state(CgAnimState state) {
            states.add(Objects.requireNonNull(state, "state"));
            return this;
        }

        public Builder transition(CgAnimTransition transition) {
            transitions.add(Objects.requireNonNull(transition, "transition"));
            return this;
        }

        /** Where it starts; its first state by default. */
        public Builder initial(String state) {
            this.initial = state;
            return this;
        }

        /** How many transitions one frame may take, through as many states: 3, as Unreal's. */
        public Builder maxTransitionsPerFrame(int max) {
            if (max < 1) throw new IllegalArgumentException(name + ": max transitions per frame " + max);
            this.maxTransitionsPerFrame = max;
            return this;
        }

        /** Whether its first frame enters at once what its transitions reach (true, as Unreal's) or crossfades into it. */
        public Builder skipFirstUpdateTransition(boolean skip) {
            this.skipFirstUpdateTransition = skip;
            return this;
        }

        /** Whether it starts over when it is updated again after a frame it was not (true, as Unreal's). */
        public Builder reinitializeOnBecomingRelevant(boolean reinitialize) {
            this.reinitializeOnBecomingRelevant = reinitialize;
            return this;
        }

        /**
         * @throws IllegalArgumentException for no state, a state named twice, an initial state that is missing or a
         *                                  conduit, or a transition naming a state the machine lacks
         */
        public CgStateMachine build() {
            return new CgStateMachine(this);
        }
    }
}
