package com.crystalgraphics.animation.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import com.crystalgraphics.animation.graph.CgAnimNodeKind;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.asset.tree.CgAssetException;

/**
 * When a transition may be taken, or a conduit entered: Unreal's transition rule graph, as a tree of comparisons over
 * the graph's variables and the state machine's own clocks. Immutable.
 *
 * <pre>{@code
 * CgAnimRule moving = CgAnimRule.variable(SPEED, CgAnimRule.Op.GREATER, 0.1f);
 * CgAnimRule landed = CgAnimRule.all(CgAnimRule.isTrue(GROUNDED), CgAnimRule.stateTime(CgAnimRule.Op.GREATER_EQUAL, 0.2f));
 * CgAnimRule ending = CgAnimRule.timeRemaining(CgAnimRule.Op.LESS_EQUAL, 0.15f);   // the state's clip nearly done
 * CgAnimRule jump = CgAnimRule.event("jump");                                      // anim.requestTransitionEvent("jump", 0.5)
 * }</pre>
 *
 * <ul>
 *   <li>{@code time_remaining} and its fraction read the state's relevant player: its highest weighted clip, as Unreal's
 *       {@code GetRelevantAnimTimeRemaining}. A state with none reads as a very long time left.</li>
 *   <li>{@link #always()} is an empty {@code all}; a rule never true is {@code not(always())}.</li>
 *   <li>An {@code event} rule marks the event for its transition; taking the transition consumes it.</li>
 * </ul>
 */
public abstract class CgAnimRule {

    /** A comparison: the file's operator. */
    public enum Op {
        LESS("<"), LESS_EQUAL("<="), GREATER(">"), GREATER_EQUAL(">="), EQUAL("=="), NOT_EQUAL("!=");

        final String symbol;

        Op(String symbol) {
            this.symbol = symbol;
        }

        boolean test(float a, float b) {
            return switch (this) {
                case LESS -> a < b;
                case LESS_EQUAL -> a <= b;
                case GREATER -> a > b;
                case GREATER_EQUAL -> a >= b;
                case EQUAL -> a == b;
                case NOT_EQUAL -> a != b;
            };
        }

        /**
         * The operator written {@code symbol}.
         *
         * @throws IllegalArgumentException for any other
         */
        public static Op of(String symbol) {
            for (Op op : values()) if (op.symbol.equals(symbol)) return op;
            throw new IllegalArgumentException(symbol + " is no comparison: <, <=, >, >=, == or !=");
        }
    }

    /** What a rule reads, from the state machine testing it. */
    public interface Context {

        /** A variable's worker value, as a float. */
        float get(CgAnimVariable<?> variable);

        /** Seconds left in the state's relevant clip. */
        float timeRemaining();

        /** The same, as a fraction of the clip. */
        float timeRemainingFraction();

        /** Seconds since the state was entered. */
        float stateTime();

        /** Whether event {@code name} is queued for this transition; marks it. */
        boolean event(String name);
    }

    CgAnimRule() {
    }

    /** Whether it holds now. */
    public abstract boolean test(Context c);

    /** Each variable it reads, for the graph's check. */
    abstract void reads(Consumer<CgAnimVariable<?>> each);

    /** Its data form. */
    abstract Map<String, Object> toTree();

    /**
     * A rule's data form at {@code at}: {@code all}, {@code any}, {@code not}, {@code {variable, op, value}},
     * {@code {time_remaining | time_remaining_fraction | state_time: <op>, value}} or {@code {event}}.
     *
     * @throws CgAssetException a form it is not, naming {@code at}
     */
    public static CgAnimRule read(CgAnimNodeKind.Context c, String at, Object tree) {
        if (!(tree instanceof Map<?, ?> m) || m.isEmpty()) throw new CgAssetException(at + ": " + tree + " is no rule");
        try {
            if (m.get("all") instanceof List<?> l) return group("all", list(c, at + ".all", l));
            if (m.get("any") instanceof List<?> l) return group("any", list(c, at + ".any", l));
            if (m.containsKey("not")) return not(read(c, at + ".not", m.get("not")));
            if (m.get("event") instanceof String name) return event(name);
            Object value = m.get("value");
            if (m.get("variable") instanceof String name) {
                CgAnimVariable<?> v = c.variable(at + ".variable", name);
                Op op = Op.of(c.string(at, m, "op"));
                float f = value instanceof Boolean bool ? (bool ? 1f : 0f) : c.number(at + ".value", value);
                return variable(v, op, f);
            }
            for (String key : List.of("time_remaining", "time_remaining_fraction", "state_time")) {
                if (m.get(key) instanceof String symbol) return clock(key, Op.of(symbol), c.number(at + ".value", value));
            }
        } catch (IllegalArgumentException e) {
            throw new CgAssetException(at + ": " + e.getMessage());
        }
        throw new CgAssetException(at + ": " + m.keySet() + " is no rule");
    }

    private static List<CgAnimRule> list(CgAnimNodeKind.Context c, String at, List<?> l) {
        List<CgAnimRule> out = new ArrayList<>(l.size());
        for (int i = 0; i < l.size(); i++) out.add(read(c, at + "[" + i + "]", l.get(i)));
        return out;
    }

    // ── leaves ───────────────────────────────────────────────────────────────────────────

    /** {@code variable} compared with {@code value}; a bool reads 1 or 0. */
    public static CgAnimRule variable(CgAnimVariable<?> variable, Op op, float value) {
        Objects.requireNonNull(variable, "variable");
        Objects.requireNonNull(op, "op");
        return new CgAnimRule() {
            @Override
            public boolean test(Context c) {
                return op.test(c.get(variable), value);
            }

            @Override
            void reads(Consumer<CgAnimVariable<?>> each) {
                each.accept(variable);
            }

            @Override
            Map<String, Object> toTree() {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("variable", variable.name());
                m.put("op", op.symbol);
                m.put("value", variable.type() == CgAnimVariable.Type.BOOL ? (Object) (value != 0f)
                        : variable.type() == CgAnimVariable.Type.INT ? (Object) (int) value : (Object) (double) value);
                return m;
            }
        };
    }

    /** A bool variable that holds. */
    public static CgAnimRule isTrue(CgAnimVariable<Boolean> variable) {
        return variable(variable, Op.EQUAL, 1f);
    }

    /** Seconds left in the state's relevant clip, compared. */
    public static CgAnimRule timeRemaining(Op op, float seconds) {
        return clock("time_remaining", op, seconds);
    }

    /** The fraction of the state's relevant clip left, compared. */
    public static CgAnimRule timeRemainingFraction(Op op, float fraction) {
        return clock("time_remaining_fraction", op, fraction);
    }

    /** Seconds in the state, compared. */
    public static CgAnimRule stateTime(Op op, float seconds) {
        return clock("state_time", op, seconds);
    }

    private static CgAnimRule clock(String key, Op op, float value) {
        Objects.requireNonNull(op, "op");
        return new CgAnimRule() {
            @Override
            public boolean test(Context c) {
                float now = switch (key) {
                    case "time_remaining" -> c.timeRemaining();
                    case "time_remaining_fraction" -> c.timeRemainingFraction();
                    default -> c.stateTime();
                };
                return op.test(now, value);
            }

            @Override
            void reads(Consumer<CgAnimVariable<?>> each) {
            }

            @Override
            Map<String, Object> toTree() {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put(key, op.symbol);
                m.put("value", (double) value);
                return m;
            }
        };
    }

    /** A transition event queued on the machine ({@code CgAnimInstance.requestTransitionEvent}). */
    public static CgAnimRule event(String name) {
        Objects.requireNonNull(name, "name");
        return new CgAnimRule() {
            @Override
            public boolean test(Context c) {
                return c.event(name);
            }

            @Override
            void reads(Consumer<CgAnimVariable<?>> each) {
            }

            @Override
            Map<String, Object> toTree() {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("event", name);
                return m;
            }
        };
    }

    // ── combinations ─────────────────────────────────────────────────────────────────────

    /** Every one of {@code rules}; true for none. */
    public static CgAnimRule all(CgAnimRule... rules) {
        return group("all", List.of(rules));
    }

    /** At least one of {@code rules}. */
    public static CgAnimRule any(CgAnimRule... rules) {
        return group("any", List.of(rules));
    }

    /** Always true: a transition taken as soon as its state is current, as an empty rule graph returning true. */
    public static CgAnimRule always() {
        return all();
    }

    static CgAnimRule group(String key, List<CgAnimRule> rules) {
        CgAnimRule[] r = rules.toArray(new CgAnimRule[0]);
        boolean all = key.equals("all");
        return new CgAnimRule() {
            @Override
            public boolean test(Context c) {
                for (CgAnimRule rule : r) if (rule.test(c) != all) return !all;
                return all;
            }

            @Override
            void reads(Consumer<CgAnimVariable<?>> each) {
                for (CgAnimRule rule : r) rule.reads(each);
            }

            @Override
            Map<String, Object> toTree() {
                List<Object> list = new ArrayList<>(r.length);
                for (CgAnimRule rule : r) list.add(rule.toTree());
                Map<String, Object> m = new LinkedHashMap<>();
                m.put(key, list);
                return m;
            }
        };
    }

    /** {@code rule} not holding: Unreal's {@code bDesiredTransitionReturnValue} false. */
    public static CgAnimRule not(CgAnimRule rule) {
        Objects.requireNonNull(rule, "rule");
        return new CgAnimRule() {
            @Override
            public boolean test(Context c) {
                return !rule.test(c);
            }

            @Override
            void reads(Consumer<CgAnimVariable<?>> each) {
                rule.reads(each);
            }

            @Override
            Map<String, Object> toTree() {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("not", rule.toTree());
                return m;
            }
        };
    }
}
