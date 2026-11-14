package com.crystalgraphics.animation.graph;

import java.util.Locale;
import java.util.Objects;

/**
 * A value a graph reads, set by the game each frame: how fast the character moves, whether it is on the ground. Unreal's
 * anim blueprint variables, which {@code NativeUpdateAnimation} sets and the graph's nodes and rules read. Declare them
 * once, as constants, and name them in the graph; the same constant sets them.
 *
 * <pre>{@code
 * static final CgAnimVariable<Float>   SPEED    = CgAnimVariable.floating("speed");
 * static final CgAnimVariable<Boolean> GROUNDED = CgAnimVariable.bool("grounded", true);
 * static final CgAnimVariable<Integer> GEAR     = CgAnimVariable.integer("gear");
 *
 * CgAnimGraph graph = CgAnimGraph.builder("mymod:hero", skeleton).variables(SPEED, GROUNDED, GEAR)...build();
 * anim.onUpdate((a, dt) -> a.set(SPEED, speed).set(GROUNDED, onGround));   // the game thread, each frame
 * }</pre>
 *
 * <ul>
 *   <li>Variables are matched by name: a graph built from a file declares {@code speed}, and the constant named
 *       {@code speed} sets it.</li>
 *   <li>Setting one the instance's graph does not declare throws, naming it; a graph's file naming one it does not
 *       declare fails at load.</li>
 *   <li>Every value is held as a float: an integer exactly up to 2²⁴, a bool as 0 or 1.</li>
 * </ul>
 *
 * @param <T> its type: {@link Float}, {@link Boolean} or {@link Integer}
 */
public final class CgAnimVariable<T> {

    /** What a variable holds. */
    public enum Type {
        FLOAT, BOOL, INT
    }

    private final String name;
    private final Type type;
    private final float defaultValue;

    private CgAnimVariable(String name, Type type, float defaultValue) {
        this.name = Objects.requireNonNull(name, "name");
        if (name.isEmpty()) throw new IllegalArgumentException("a variable has a name");
        this.type = type;
        this.defaultValue = defaultValue;
    }

    /** A float, 0 until set. */
    public static CgAnimVariable<Float> floating(String name) {
        return floating(name, 0f);
    }

    public static CgAnimVariable<Float> floating(String name, float defaultValue) {
        return new CgAnimVariable<>(name, Type.FLOAT, defaultValue);
    }

    /** A bool, false until set. */
    public static CgAnimVariable<Boolean> bool(String name) {
        return bool(name, false);
    }

    public static CgAnimVariable<Boolean> bool(String name, boolean defaultValue) {
        return new CgAnimVariable<>(name, Type.BOOL, defaultValue ? 1f : 0f);
    }

    /** An integer, 0 until set. */
    public static CgAnimVariable<Integer> integer(String name) {
        return integer(name, 0);
    }

    public static CgAnimVariable<Integer> integer(String name, int defaultValue) {
        return new CgAnimVariable<>(name, Type.INT, defaultValue);
    }

    /** A variable read from a file's declaration. */
    static CgAnimVariable<?> of(String name, Type type, float defaultValue) {
        return new CgAnimVariable<>(name, type, defaultValue);
    }

    public String name() {
        return name;
    }

    public Type type() {
        return type;
    }

    /** Its value before anything sets it, as a float. */
    public float defaultValue() {
        return defaultValue;
    }

    /** Its index in {@code graph}, -1 where undeclared or of another type: a lookup by name, allocating nothing. */
    int indexIn(CgAnimGraph graph) {
        int at = graph.variable(name);
        return at >= 0 && graph.variables().get(at).type() == type ? at : -1;
    }


    @Override
    public String toString() {
        return name + ":" + type.name().toLowerCase(Locale.ROOT);
    }
}
