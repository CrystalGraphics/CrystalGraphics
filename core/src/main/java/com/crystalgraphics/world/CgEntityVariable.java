package com.crystalgraphics.world;

import java.util.Objects;

import com.crystalgraphics.animation.graph.CgAnimVariable;

/**
 * One value of an entity's {@link CgEntityState}, named for a graph to read: {@code entity.speed},
 * {@code entity.grounded}. A graph binds one of its own variables to it, and every frame the entity's actor sets that
 * variable from it. The engine's are {@link CgEntityVariables}' constants.
 *
 * <pre>{@code
 * CgAnimGraph.builder("mymod:hero", skeleton)
 *         .variables(SPEED, ON_GROUND)
 *         .entity(SPEED, CgEntityVariables.SPEED)          // its own variable, fed by a standard one
 *         .entity(ON_GROUND, CgEntityVariables.GROUNDED)
 *         ...
 * }</pre>
 *
 * @param name   what a graph's file names it by, {@code entity.speed}
 * @param type   what it holds: a graph variable of the same type takes it, and a float takes any
 * @param reader its value now, as a float: a bool 0 or 1
 */
public record CgEntityVariable(String name, CgAnimVariable.Type type, Reader reader) {

    /** Reads a value off an entity's state; allocates nothing. */
    @FunctionalInterface
    public interface Reader {
        float read(CgEntityState state);
    }

    public CgEntityVariable {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(reader, "reader");
    }

    /** Whether a graph variable of {@code type} may take it: the same type, or a float, which takes any as a number. */
    public boolean feeds(CgAnimVariable.Type type) {
        return type == this.type || type == CgAnimVariable.Type.FLOAT;
    }
}
