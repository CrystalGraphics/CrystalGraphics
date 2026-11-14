package com.crystalgraphics.animation.state;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import com.crystalgraphics.animation.blend.CgAlphaBlend;
import com.crystalgraphics.animation.graph.CgAnimGraphInstance;
import com.crystalgraphics.animation.graph.CgAnimNode;
import com.crystalgraphics.animation.graph.CgAnimNodeKind;
import com.crystalgraphics.animation.graph.CgAnimNodeWriter;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.animation.graph.CgSequencePlayerNode;
import com.crystalgraphics.api.animation.CgAnimPose;
import com.crystalgraphics.asset.tree.CgAssetException;

/**
 * Runs a {@link CgAnimStateMachine} in a graph: Unreal's {@code FAnimNode_StateMachine} (AnimNode_StateMachine.cpp). Each
 * frame it takes the transitions whose rules hold, crossfades between the states it left and entered, and updates and
 * evaluates only the states that show.
 *
 * <pre>{@code
 * CgAnimGraph.builder("mymod:hero", skeleton).variables(SPEED, GROUNDED)
 *         .output(CgSlotNode.of(CgAnimGraph.DEFAULT_SLOT, CgAnimStateMachineNode.of(LOCOMOTION)))
 *         .build();
 *
 * CgAnimStateMachineNode.Machine m = machineNode.in(anim.graph());   // its state in one instance, for a tool or a test
 * m.currentState();                                              // "run"
 * }</pre>
 *
 * <ul>
 *   <li>Transitions stack: a transition taken while another crossfades blends over that one's result, oldest first, as
 *       Unreal's; a newer one finishing ends the older.</li>
 *   <li>A crossfade into a state that already shows is shortened by the blend's inverse at its remaining share
 *       ({@code CalculateInverseAlpha}).</li>
 *   <li>Its states' players form their own scope ({@link CgAnimGraphInstance#beginPlayerScope}): an automatic rule and
 *       {@code time_remaining} read the state's highest weighted clip from its last update.</li>
 *   <li>Allocates nothing per frame; a transition event allocates when it is requested.</li>
 * </ul>
 */
public final class CgAnimStateMachineNode extends CgAnimNode {

    /** Unreal's {@code MaxTransitionsRequests}: events queued at most, the oldest dropped. */
    private static final int MAX_EVENTS = 32;

    private final CgAnimStateMachine machine;

    private CgAnimStateMachineNode(CgAnimStateMachine machine) {
        this.machine = Objects.requireNonNull(machine, "machine");
    }

    /**
     * The {@code state_machine} kind's reader: {@code {kind: state_machine, machine: <name>}}, the machine under the
     * graph's {@code machines}. Registered with {@code CgAnimGraphCodec.registerKind}.
     */
    public static final CgAnimNodeKind KIND = CgAnimStateMachineNode::read;

    private static CgAnimNode read(CgAnimNodeKind.Context c, String at, Map<?, ?> node) {
        String name = c.string(at, node, "machine");
        Map<?, ?> m = c.section("machines", name);
        if (m == null) throw new CgAssetException(at + ".machine: " + name + " is no machine of the graph");
        String file = at.contains(": ") ? at.substring(0, at.indexOf(": ")) : at;
        String mat = file + ": machines." + name;
        CgAnimStateMachine.Builder b = CgAnimStateMachine.builder(name);
        if (!(m.get("states") instanceof Map<?, ?> states) || states.isEmpty()) throw new CgAssetException(mat + ".states: none");
        try {
            for (Map.Entry<?, ?> e : states.entrySet()) {
                String sat = mat + ".states." + e.getKey();
                if (!(e.getValue() instanceof Map<?, ?> s)) throw new CgAssetException(sat + ": not a state");
                CgAnimState state = s.containsKey("entry_rule")
                        ? CgAnimState.conduit(String.valueOf(e.getKey()), CgAnimRule.read(c, sat + ".entry_rule", s.get("entry_rule")))
                        : CgAnimState.of(String.valueOf(e.getKey()), c.pose(c.string(sat, s, "pose")));
                if (Boolean.TRUE.equals(s.get("always_reset_on_entry"))) state = state.alwaysResetOnEntry();
                b.state(state);
            }
            if (m.get("transitions") instanceof List<?> transitions) {
                for (int i = 0; i < transitions.size(); i++) b.transition(transition(c, mat + ".transitions[" + i + "]", (Map<?, ?>) transitions.get(i)));
            }
            if (m.get("initial") instanceof String initial) b.initial(initial);
            if (m.get("max_transitions_per_frame") instanceof Number max) b.maxTransitionsPerFrame(max.intValue());
            if (Boolean.FALSE.equals(m.get("skip_first_update_transition"))) b.skipFirstUpdateTransition(false);
            if (Boolean.FALSE.equals(m.get("reinitialize_on_becoming_relevant"))) b.reinitializeOnBecomingRelevant(false);
            return of(b.build());
        } catch (IllegalArgumentException e) {
            throw new CgAssetException(mat + ": " + e.getMessage());
        }
    }

    private static CgAnimTransition transition(CgAnimNodeKind.Context c, String at, Map<?, ?> t) {
        String from = c.string(at, t, "from"), to = c.string(at, t, "to");
        CgAnimTransition tr = Boolean.TRUE.equals(t.get("automatic")) ? CgAnimTransition.automatic(from, to)
                : CgAnimTransition.of(from, to, CgAnimRule.read(c, at + ".rule", t.get("rule")));
        if (t.containsKey("crossfade")) tr = tr.crossfade(c.number(at + ".crossfade", t.get("crossfade")));
        if (t.get("blend") instanceof String blend) tr = tr.blend(CgAlphaBlend.of(blend));
        Object logic = t.get("logic");
        if ("inertialization".equals(logic)) tr = tr.inertialize();
        else if (logic != null && !"standard".equals(logic)) throw new CgAssetException(at + ".logic: " + logic + " is not played: standard or inertialization");
        if (t.get("priority") instanceof Number p) tr = tr.priority(p.intValue());
        if (t.containsKey("trigger_time")) tr = tr.triggerTime(c.number(at + ".trigger_time", t.get("trigger_time")));
        if (Boolean.FALSE.equals(t.get("overshoot_correction"))) tr = tr.overshootCorrection(false);
        if (t.containsKey("min_reentry")) tr = tr.minReentry(c.number(at + ".min_reentry", t.get("min_reentry")));
        if (Boolean.TRUE.equals(t.get("inertialize_self"))) tr = tr.inertializeSelf();
        return tr;
    }

    /** A node running {@code machine}. */
    public static CgAnimStateMachineNode of(CgAnimStateMachine machine) {
        return new CgAnimStateMachineNode(machine);
    }

    public CgAnimStateMachine machine() {
        return machine;
    }

    /** Its state in {@code g}. */
    public Machine in(CgAnimGraphInstance g) {
        return (Machine) g.runtime(this);
    }

    /**
     * Queues transition event {@code name} on every state machine of {@code g} for {@code timeToLive} seconds of the
     * instance's clock: Unreal's {@code RequestTransitionEvent}. On the game thread, as variables are set.
     *
     * @param unique  {@code false}: the first transition to take it consumes it (Unreal's Shared); {@code true}: each
     *                transition may take it once (Unique)
     * @param replace how a request meets one of the same name already queued
     */
    public static void requestTransitionEvent(CgAnimGraphInstance g, String name, double timeToLive, boolean unique, Replace replace) {
        g.forEachRuntime(r -> {
            if (r instanceof Machine m) m.request(name, timeToLive, unique, replace);
        });
    }

    /** Drops every queued event named {@code name}, on every state machine of {@code g}. */
    public static void clearTransitionEvents(CgAnimGraphInstance g, String name) {
        g.forEachRuntime(r -> {
            if (r instanceof Machine m) m.clear(name);
        });
    }

    /** A request meeting one of the same name: Unreal's {@code ETransitionRequestOverwriteMode}. */
    public enum Replace {
        /** Queued whether or not one of the name is. */
        APPEND,
        /** Dropped when one of the name is queued. */
        IGNORE,
        /** Replaces the queued one of the name. */
        OVERWRITE
    }

    @Override
    protected Runtime instantiate(CgAnimGraphInstance g) {
        for (CgAnimTransition t : machine.transitions()) {
            if ((t.inertialized() || t.inertializesSelf()) && !g.inertializerAbove()) {
                throw new IllegalArgumentException(machine.name() + ": transition " + t.from() + " -> " + t.to()
                        + " inertializes, and no inertialization node is above the machine");
            }
        }
        return new Machine(g);
    }

    @Override
    protected void children(Consumer<CgAnimNode> each) {
        for (CgAnimState s : machine.states()) if (s.pose() != null) each.accept(s.pose());
    }

    @Override
    protected void reads(Consumer<CgAnimVariable<?>> each) {
        for (CgAnimState s : machine.states()) if (s.entryRule() != null) s.entryRule().reads(each);
        for (CgAnimTransition t : machine.transitions()) if (t.rule() != null) t.rule().reads(each);
    }

    @Override
    protected void write(CgAnimNodeWriter out) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (machine.initial() != 0) m.put("initial", machine.states().get(machine.initial()).name());
        if (machine.maxTransitionsPerFrame() != 3) m.put("max_transitions_per_frame", machine.maxTransitionsPerFrame());
        if (!machine.skipFirstUpdateTransition()) m.put("skip_first_update_transition", false);
        if (!machine.reinitializeOnBecomingRelevant()) m.put("reinitialize_on_becoming_relevant", false);
        Map<String, Object> states = new LinkedHashMap<>();
        for (CgAnimState s : machine.states()) {
            Map<String, Object> st = new LinkedHashMap<>();
            if (s.conduit()) {
                st.put("entry_rule", s.entryRule().toTree());
            } else {
                st.put("pose", out.name(s.pose()));
                if (s.resetsOnEntry()) st.put("always_reset_on_entry", true);
            }
            states.put(s.name(), st);
        }
        m.put("states", states);
        List<Object> transitions = new ArrayList<>();
        for (CgAnimTransition t : machine.transitions()) {
            Map<String, Object> tr = new LinkedHashMap<>();
            tr.put("from", t.from());
            tr.put("to", t.to());
            if (t.automatic()) tr.put("automatic", true);
            else tr.put("rule", t.rule().toTree());
            if (t.crossfade() != 0.2f) tr.put("crossfade", (double) t.crossfade());
            if (t.blend() != CgAlphaBlend.HERMITE_CUBIC) tr.put("blend", t.blend().id());
            if (t.inertialized()) tr.put("logic", "inertialization");
            if (t.priority() != 1) tr.put("priority", t.priority());
            if (t.triggerTime() >= 0f) tr.put("trigger_time", (double) t.triggerTime());
            if (!t.overshootCorrection()) tr.put("overshoot_correction", false);
            if (t.minReentry() >= 0f) tr.put("min_reentry", (double) t.minReentry());
            if (t.inertializesSelf()) tr.put("inertialize_self", true);
            transitions.add(tr);
        }
        m.put("transitions", transitions);
        out.kind("state_machine").put("machine", machine.name()).section("machines", machine.name(), m);
    }

    @Override
    public String toString() {
        return "state_machine(" + machine.name() + ")";
    }

    /** One crossfade in flight: Unreal's {@code FAnimationActiveTransitionEntry} with its {@code FAlphaBlend}. */
    private static final class Entry {
        int next, previous;
        CgAnimTransition transition;
        float elapsed, alpha, crossfade, alphaLerp, remaining;
        boolean active, complete;

        void start(int next, float existingWeightOfNext, int previous, CgAnimTransition t, float adjustment) {
            this.next = next;
            this.previous = previous;
            this.transition = t;
            elapsed = 0f;
            alpha = 0f;
            active = true;
            crossfade = t.inertialized() ? 0f : Math.max(t.crossfade() - adjustment, 0f) * t.blend().inverse(1f - existingWeightOfNext);
            // FAlphaBlend from 0 over the crossfade (SetBlendTime, SetValueRange(0, 1))
            alphaLerp = 0f;
            remaining = crossfade;
            complete = crossfade <= 0f;
        }

        /** {@code Update}: answers whether it finished this frame. */
        boolean update(float dt) {
            if (!active) return false;
            elapsed += dt;
            if (!complete) {
                if (remaining > dt) {
                    alphaLerp += (1f - alphaLerp) / remaining * dt;
                    remaining -= dt;
                    complete = transition.blend().apply(alphaLerp) == transition.blend().apply(1f);   // IsComplete
                } else {
                    remaining = 0f;
                    alphaLerp = 1f;
                    complete = true;
                }
            }
            alpha = transition.blend().apply(crossfade > 0f ? elapsed / crossfade : 0f);
            if (complete) {
                active = false;
                return true;
            }
            return false;
        }
    }

    /** A queued transition event: Unreal's {@code FTransitionEvent}. */
    private static final class Event {
        final String name;
        final double created, timeToLive;
        final boolean unique;
        /** The transitions that took it. */
        int[] consumedBy = new int[2];
        int consumed;

        Event(String name, double created, double timeToLive, boolean unique) {
            this.name = name;
            this.created = created;
            this.timeToLive = timeToLive;
            this.unique = unique;
        }
    }

    /** A state machine's state in one graph instance. */
    public final class Machine extends Runtime implements CgAnimRule.Context {

        private final CgAnimGraphInstance g;
        private final Runtime[] poses;
        private final int n;
        private int current = -1, scope, checking = -1, testing = -1, searchStamp;
        private float elapsed;
        private boolean firstUpdate = true;
        private long lastFrame = -1, evaluationStamp;
        private final double[] lastEntry;
        private final boolean[] updated;
        private final int[] visited;
        private final CgSequencePlayerNode.PlayerRuntime[] relevant;
        private Entry[] active = new Entry[4];
        private boolean[] finished = new boolean[4];
        private int activeCount;
        private final CgAnimPose[] statePoses;
        private final long[] statePoseStamp;
        private final CgAnimPose[] pair = new CgAnimPose[2];
        private final float[] pairWeights = new float[2];
        private final List<Event> events = new ArrayList<>();
        // FindValidTransition's out parameter
        private int foundTarget, foundTransition;
        private float foundAdjustment;

        private Machine(CgAnimGraphInstance g) {
            this.g = g;
            n = machine.states().size();
            poses = new Runtime[n];
            for (int s = 0; s < n; s++) {
                CgAnimNode pose = machine.states().get(s).pose();
                if (pose != null) poses[s] = g.runtime(pose);
            }
            lastEntry = new double[n];
            updated = new boolean[n];
            visited = new int[n];
            relevant = new CgSequencePlayerNode.PlayerRuntime[n];
            statePoses = new CgAnimPose[n];
            statePoseStamp = new long[n];
            for (int i = 0; i < active.length; i++) active[i] = new Entry();
        }

        // ── what a tool reads ────────────────────────────────────────────────────────────

        /** The state it is in. */
        public String currentState() {
            return machine.states().get(current).name();
        }

        /** Seconds in the current state. */
        public float elapsed() {
            return elapsed;
        }

        /** State {@code name}'s weight in the pose, 0 to 1. */
        public float stateWeight(String name) {
            int s = machine.state(name);
            if (s < 0) throw new IllegalArgumentException(machine.name() + " has no state " + name);
            return stateWeight(s);
        }

        /** Crossfades in flight. */
        public int activeTransitions() {
            return activeCount;
        }

        // ── the runtime ──────────────────────────────────────────────────────────────────

        @Override
        public void initialize(CgAnimGraphInstance g) {
            elapsed = 0f;
            current = -1;
            activeCount = 0;
            events.clear();
            Arrays.fill(updated, false);
            Arrays.fill(relevant, null);
            // Never entered: no re-entry wait (Unreal's world time is far past its zeroed entry times)
            Arrays.fill(lastEntry, Double.NEGATIVE_INFINITY);
            setState(machine.initial(), false);
            firstUpdate = true;
        }

        @Override
        public void update(CgAnimGraphInstance g, float weight) {
            // Reinitialized when it becomes relevant again after a frame it was not updated
            if (!firstUpdate && machine.reinitializeOnBecomingRelevant() && lastFrame >= 0 && lastFrame != g.frame() - 1) {
                initialize(g);
            }
            lastFrame = g.frame();
            for (int i = events.size() - 1; i >= 0; i--) {
                Event e = events.get(i);
                if (g.seconds() - e.created >= e.timeToLive) events.remove(i);
            }
            int taken = 0;
            boolean found;
            do {
                found = false;
                searchStamp++;
                foundTarget = -1;
                if (find(current)) {
                    CgAnimTransition t = machine.transitions().get(foundTransition);
                    if (foundTarget != current || t.inertializesSelf()) {
                        transitionToState(t);
                        found = true;
                        taken++;
                    }
                }
            } while (found && taken < machine.maxTransitionsPerFrame());
            if (firstUpdate) {
                if (machine.skipFirstUpdateTransition()) activeCount = 0;
                firstUpdate = false;
            }

            Arrays.fill(updated, false);
            scope = g.beginPlayerScope();
            float dt = g.delta();
            if (activeCount > 0) {
                if (finished.length < activeCount) finished = new boolean[active.length];
                for (int i = 0; i < activeCount; i++) finished[i] = active[i].update(dt);
                for (int i = 0; i < activeCount; i++) if (!finished[i]) updateTransitionStates(active[i], weight);
                // A transition finishing ends every older one
                for (int i = activeCount - 1; i >= 0; i--) {
                    if (!active[i].active) {
                        remove(i + 1);
                        break;
                    }
                }
            }
            if (activeCount == 0 && poses[current] != null && !updated[current]) updateState(current, weight);
            g.endPlayerScope();
            elapsed += dt;
        }

        /** Removes the oldest {@code count} entries, keeping their objects for reuse. */
        private void remove(int count) {
            for (int i = 0; i < count; i++) {
                Entry e = active[0];
                System.arraycopy(active, 1, active, 0, active.length - 1);
                active[active.length - 1] = e;
            }
            activeCount -= count;
        }

        private void updateTransitionStates(Entry e, float weight) {
            if (e.transition.inertialized()) {
                updateState(e.next, weight);
            } else {
                updateState(e.previous, weight * stateWeight(e.previous));
                updateState(e.next, weight * stateWeight(e.next));
            }
        }

        /** Updates state {@code s} once this frame, and keeps its highest weighted player for its rules. */
        private void updateState(int s, float weight) {
            if (s < 0 || updated[s] || poses[s] == null) return;
            updated[s] = true;
            int before = g.samples();
            poses[s].update(g, weight);
            CgSequencePlayerNode.PlayerRuntime best = null;
            float bestWeight = 0f;
            for (int i = before; i < g.samples(); i++) {
                CgSequencePlayerNode.PlayerRuntime p = g.samplePlayer(i);
                if (p != null && g.sampleScope(i) == scope && g.sampleWeight(i) > bestWeight) {
                    best = p;
                    bestWeight = g.sampleWeight(i);
                }
            }
            if (best != null) relevant[s] = best;
        }

        /** {@code FindValidTransition} from state {@code s}: the first exit whose rule holds, through conduits. */
        private boolean find(int s) {
            if (visited[s] == searchStamp) return false;
            visited[s] = searchStamp;
            CgAnimState state = machine.states().get(s);
            if (state.conduit()) {
                checking = s;
                testing = -1;
                if (!state.entryRule().test(this)) return false;
            }
            for (int t : machine.exits(s)) {
                CgAnimTransition tr = machine.transitions().get(t);
                int next = machine.to(t);
                if (tr.minReentry() >= 0f && g.seconds() - lastEntry[next] < tr.minReentry()) continue;
                float adjustment = 0f;
                boolean holds;
                if (tr.automatic()) {
                    holds = false;
                    CgSequencePlayerNode.PlayerRuntime player = relevant[s];
                    if (player != null && player.clip() != null) {
                        float remaining = player.clip().duration() - player.time();
                        // Looped last frame: at its end (AnimNode_StateMachine.cpp:826-843)
                        if (player.loops() && remaining > 0f && player.wrapped()) remaining = 0f;
                        boolean intoConduit = machine.states().get(next).conduit();
                        float crossfade = intoConduit && tr.overshootCorrection() ? 0f : tr.crossfade();
                        float trigger = tr.triggerTime() >= 0f ? tr.triggerTime() : crossfade;
                        float over = trigger - remaining;
                        holds = over >= 0f;
                        if (tr.overshootCorrection()) adjustment = over;
                    }
                } else {
                    checking = s;
                    testing = t;
                    holds = tr.rule().test(this);
                }
                if (!holds) continue;
                if (machine.states().get(next).conduit()) {
                    if (find(next)) return true;
                } else {
                    foundTarget = next;
                    foundTransition = t;
                    foundAdjustment = adjustment;
                    return true;
                }
            }
            return false;
        }

        /** {@code TransitionToState}: an entry pushed, inertialization requested, the target entered. */
        private void transitionToState(CgAnimTransition t) {
            int previous = current, next = foundTarget;
            if (!machine.states().get(previous).conduit()) {
                float existing = stateWeight(next);
                if (activeCount == active.length) {
                    active = Arrays.copyOf(active, activeCount * 2);
                    for (int i = activeCount; i < active.length; i++) active[i] = new Entry();
                }
                active[activeCount++].start(next, existing, previous, t, foundAdjustment);
                if (t.inertialized() || (t.inertializesSelf() && existing > 0f && machine.states().get(next).resetsOnEntry())) {
                    CgAnimGraphInstance.Inertializer to = g.inertializer();
                    // Checked as the machine instantiated; a machine under a cached pose loses it here
                    if (to == null) throw new IllegalStateException(machine.name() + ": no inertialization node above the machine this frame");
                    to.requestInertialization(t.crossfade());
                }
            }
            for (int i = events.size() - 1; i >= 0; i--) {
                Event e = events.get(i);
                if (!e.unique && e.consumed > 0) events.remove(i);
            }
            setState(next, t.inertializesSelf());
        }

        /** {@code SetState}: entered, timed, and its pose restarted unless it already shows. */
        private void setState(int next, boolean allowReEntry) {
            if (next == current && !allowReEntry) return;
            boolean forceReset = machine.states().get(next).resetsOnEntry();
            boolean alreadyActive = stateWeight(next) > 0f;
            current = next;
            elapsed = 0f;
            lastEntry[current] = g.seconds();
            relevant[current] = null;
            if ((!alreadyActive || forceReset) && poses[next] != null) poses[next].initialize(g);
        }

        /** {@code GetStateWeight}. */
        private float stateWeight(int s) {
            if (activeCount == 0) return s == current ? 1f : 0f;
            float total = 0f;
            for (int i = 0; i < activeCount; i++) {
                Entry e = active[i];
                float source = 1f - e.alpha;
                if (i > 0) total *= source;
                else if (e.previous == s) total += source;
                if (e.next == s) total += e.alpha;
            }
            return Math.max(0f, Math.min(1f, total));
        }

        @Override
        public void evaluate(CgAnimGraphInstance g, CgAnimPose out) {
            if (activeCount == 0) {
                if (poses[current] != null) poses[current].evaluate(g, out);
                else out.rest();
                return;
            }
            evaluationStamp++;
            for (int i = 0; i < activeCount; i++) {
                Entry e = active[i];
                if (!e.active) continue;
                if (e.transition.inertialized()) {
                    out.set(state(e.next));
                } else if (i == 0) {
                    blend(state(e.previous), state(e.next), e.alpha, out);
                } else {
                    CgAnimPose so = g.borrow();
                    so.set(out);
                    blend(so, state(e.next), e.alpha, out);
                    g.giveBack(so);
                }
            }
        }

        private void blend(CgAnimPose from, CgAnimPose to, float alpha, CgAnimPose out) {
            pair[0] = from;
            pair[1] = to;
            pairWeights[0] = 1f - alpha;
            pairWeights[1] = alpha;
            out.blend(pair, pairWeights, 2);
            pair[0] = pair[1] = null;
        }

        /** {@code EvaluateState}: state {@code s}'s pose, evaluated once this frame. */
        private CgAnimPose state(int s) {
            if (statePoses[s] == null) statePoses[s] = new CgAnimPose(g.skeleton());
            if (statePoseStamp[s] != evaluationStamp) {
                statePoseStamp[s] = evaluationStamp;
                if (poses[s] != null) poses[s].evaluate(g, statePoses[s]);
                else statePoses[s].rest();
            }
            return statePoses[s];
        }

        // ── events, on the game thread ───────────────────────────────────────────────────

        void request(String name, double timeToLive, boolean unique, Replace replace) {
            if (timeToLive <= 0) return;
            int existing = -1;
            for (int i = 0; i < events.size(); i++) if (events.get(i).name.equals(name)) existing = existing < 0 ? i : existing;
            Event e = new Event(name, g.seconds(), timeToLive, unique);
            if (existing < 0 || replace == Replace.APPEND) {
                if (events.size() == MAX_EVENTS) events.remove(events.size() - 1);
                events.add(0, e);
            } else if (replace == Replace.OVERWRITE) {
                events.set(existing, e);
            }
        }

        void clear(String name) {
            events.removeIf(e -> e.name.equals(name));
        }

        // ── what rules read ──────────────────────────────────────────────────────────────

        @Override
        public float get(CgAnimVariable<?> variable) {
            return g.get(variable);
        }

        @Override
        public float timeRemaining() {
            CgSequencePlayerNode.PlayerRuntime p = relevant[checking];
            if (p == null || p.clip() == null) return Float.MAX_VALUE;
            float length = p.clip().duration();
            return p.rate() < 0f ? p.time() : length - p.time();
        }

        @Override
        public float timeRemainingFraction() {
            CgSequencePlayerNode.PlayerRuntime p = relevant[checking];
            if (p == null || p.clip() == null || p.clip().duration() <= 0f) return 1f;
            return timeRemaining() / p.clip().duration();
        }

        @Override
        public float stateTime() {
            return elapsed;
        }

        /** {@code QueryAndMarkTransitionEvent}: the newest event of the name this transition has not taken. */
        @Override
        public boolean event(String name) {
            for (int i = 0; i < events.size(); i++) {
                Event e = events.get(i);
                if (!e.name.equals(name) || taken(e, testing)) continue;
                if (e.consumed == e.consumedBy.length) e.consumedBy = Arrays.copyOf(e.consumedBy, e.consumed * 2);
                e.consumedBy[e.consumed++] = testing;
                return true;
            }
            return false;
        }

        private boolean taken(Event e, int transition) {
            for (int i = 0; i < e.consumed; i++) if (e.consumedBy[i] == transition) return true;
            return false;
        }
    }
}
