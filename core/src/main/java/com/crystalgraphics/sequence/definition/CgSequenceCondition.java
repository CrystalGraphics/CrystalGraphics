package com.crystalgraphics.sequence.definition;

import java.util.List;
import java.util.Map;

/**
 * A section's {@code condition}, compiled: a predicate over presentation inputs, {@code all}, {@code any} and
 * {@code not} over leaves of a registered kind ({@code sequence.condition.CgConditionKinds}). A client evaluates it
 * once, as play binds its section; a dedicated server never does.
 *
 * <pre>{@code
 * // condition: { all: [ { kind: crystalgraphics:character, role: caster, is: [Goku] },
 * //                     { not: { kind: crystalgraphics:quality, is: [low] } } ] }
 * // condition: { not: { kind: crystalgraphics:variant, role: caster, is: any } }   // the base form: no variant at all
 * boolean holds = condition.test(leaf -> reader.read(leaf));   // each leaf answered by its kind's reader
 * }</pre>
 *
 * <ul>
 *   <li>A leaf's role is its index in the definition's roles; -1 where the kind reads no role ({@code quality}).</li>
 *   <li>{@code is} keeps its values as written: the kind's reader says what they mean.</li>
 * </ul>
 */
public sealed interface CgSequenceCondition {

    /** Answers a leaf: whether its kind's reading of its role is one of its values. */
    @FunctionalInterface
    interface Reader {
        boolean holds(Leaf leaf);
    }

    /** Whether it holds, its leaves answered by {@code reader}. */
    boolean test(Reader reader);

    /** Every one holds. */
    record All(List<CgSequenceCondition> of) implements CgSequenceCondition {
        @Override
        public boolean test(Reader reader) {
            for (CgSequenceCondition c : of) if (!c.test(reader)) return false;
            return true;
        }
    }

    /** At least one holds. */
    record Any(List<CgSequenceCondition> of) implements CgSequenceCondition {
        @Override
        public boolean test(Reader reader) {
            for (CgSequenceCondition c : of) if (c.test(reader)) return true;
            return false;
        }
    }

    /** It does not hold. */
    record Not(CgSequenceCondition of) implements CgSequenceCondition {
        @Override
        public boolean test(Reader reader) {
            return !of.test(reader);
        }
    }

    /**
     * One reading: {@code kind} ({@code crystalgraphics:surface}, a mod's own) of the role at {@code role}, one of
     * {@code is}, or with {@code anyValue} ({@code is: any}) any value at all, {@code is} empty; {@code fields} holds any
     * other keys its kind reads.
     */
    record Leaf(String kind, int role, List<?> is, boolean anyValue, Map<?, ?> fields) implements CgSequenceCondition {
        @Override
        public boolean test(Reader reader) {
            return reader.holds(this);
        }
    }
}
