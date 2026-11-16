package com.crystalgraphics.world;

import java.util.Locale;
import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.animation.graph.CgAnimVariable;

/**
 * One value an entity has, named for a graph to read: the engine's ({@code entity.speed}, {@code entity.grounded}, read
 * off its {@link CgEntityState}) or a mod's ({@code mymod:ki}, read off the host's entity). A graph binds one of its own
 * variables to it, and every frame the entity's actor sets that variable from it. The engine's are
 * {@link CgEntityVariables}' constants; a mod's are {@link CgEntityVariables#register}ed.
 *
 * <pre>{@code
 * CgAnimGraph.builder("mymod:hero", skeleton)
 *         .variables(SPEED, ON_GROUND, KI)
 *         .entity(SPEED, CgEntityVariables.SPEED)          // its own variable, fed by a standard one
 *         .entity(ON_GROUND, CgEntityVariables.GROUNDED)
 *         .entity(KI, Mana.KI)                             // a mod's
 *         ...
 * }</pre>
 */
public final class CgEntityVariable {

    /**
     * Reads its value now, as a float (a bool 0 or 1): from the entity's state, or from the host's own entity (the
     * loader's, null where this side has none). Allocates nothing.
     */
    @FunctionalInterface
    public interface Reader {
        float read(CgEntityState state, @Nullable Object entity);
    }

    private final String name;
    private final CgAnimVariable.Type type;
    private final Reader reader;
    /** Its index among the replicated variables; -1 for one read on each side. */
    int replicatedIndex = -1;

    CgEntityVariable(String name, CgAnimVariable.Type type, Reader reader) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    /** What a graph's file names it by: {@code entity.speed}, {@code mymod:ki}. */
    public String name() {
        return name;
    }

    /** What it holds: a graph variable of the same type takes it, and a float takes any. */
    public CgAnimVariable.Type type() {
        return type;
    }

    public Reader reader() {
        return reader;
    }

    /**
     * Has the server read it and send its value to every client that sees the entity, which reads that instead of its
     * own: for a value only the server knows (a mod's mana kept server-side). Said once, as it is registered.
     *
     * <pre>{@code
     * public static final CgEntityVariable KI = CgEntityVariables.register("mymod:ki", FLOAT,
     *         (state, entity) -> Mana.ki(entity)).replicated();
     * }</pre>
     *
     * <ul>
     *   <li>A client reads 0 until the server's first value arrives.</li>
     *   <li>Sent when it changes, once a tick, for entities an actor animates on the server.</li>
     * </ul>
     */
    public CgEntityVariable replicated() {
        CgEntityVariables.replicate(this);
        return this;
    }

    /** Whether the server sends its value: {@link #replicated()}. */
    public boolean isReplicated() {
        return replicatedIndex >= 0;
    }

    /** Whether a graph variable of {@code type} may take it: the same type, or a float, which takes any as a number. */
    public boolean feeds(CgAnimVariable.Type type) {
        return type == this.type || type == CgAnimVariable.Type.FLOAT;
    }

    @Override
    public String toString() {
        return name + ":" + type.name().toLowerCase(Locale.ROOT);
    }
}
