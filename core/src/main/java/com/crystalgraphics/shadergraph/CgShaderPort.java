package com.crystalgraphics.shadergraph;

import javax.annotation.Nullable;

/**
 * One input or output on a {@link CgShaderNode} — the port's <b>identity and type</b>, never its widget.
 *
 * @param id        the name the template substitutes and an edge points at. Must be a legal GLSL
 *                  identifier fragment, since it ends up in the emitted variable name
 * @param type      the GLSL type, or {@link CgShaderType#DYNAMIC} to resolve from what is connected
 * @param direction which side of the node this sits on
 * @param defaultExpression the GLSL used when an <b>input</b> is left unconnected. Null means the node
 *                  cannot function without a connection, which the compiler reports rather than
 *                  substituting a zero
 * @param defaultIsLiteral whether that default is a value a user may edit (so the editor offers an inline
 *                  field) or an engine expression like {@code cg_Position} (so it does not) — see
 *                  {@link #engineDefault}
 */
public record CgShaderPort(String id, CgShaderType type, Direction direction,
                           @Nullable String defaultExpression, boolean defaultIsLiteral) {

    public enum Direction { INPUT, OUTPUT }

    /**
     * A port whose default is a plain literal the user may edit — the ordinary case.
     *
     * @see #engineDefault
     */
    public CgShaderPort(String id, CgShaderType type, Direction direction,
                        @Nullable String defaultExpression) {
        this(id, type, direction, defaultExpression, true);
    }

    public CgShaderPort {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("Port id must not be empty");
        if (type == null) throw new IllegalArgumentException("Port " + id + " has no type");
        if (direction == null) throw new IllegalArgumentException("Port " + id + " has no direction");
        if (direction == Direction.OUTPUT && defaultExpression != null) {
            // An output is produced, never supplied. A default on one would be dead data that reads
            // like a fallback, which is worse than absent.
            throw new IllegalArgumentException("Output port " + id + " must not declare a default");
        }
    }

    public static CgShaderPort input(String id, CgShaderType type, @Nullable String defaultExpression) {
        return new CgShaderPort(id, type, Direction.INPUT, defaultExpression, true);
    }

    /**
     * An input whose default is an <b>engine expression</b> rather than a value — {@code cg_Position},
     * a builtin, anything referring to state the user does not own.
     *
     * <p>The distinction exists for one reason: it decides whether the editor offers a field. A literal
     * default is a value someone picks, so it gets one. An engine expression is not editable in any
     * meaningful sense — offering a text box invites typing GLSL into a node, and it renders as a cramped
     * field showing {@code cg_Positio…}, which states nothing the port's own name did not.</p>
     */
    public static CgShaderPort engineDefault(String id, CgShaderType type, String expression) {
        return new CgShaderPort(id, type, Direction.INPUT, expression, false);
    }

    public static CgShaderPort output(String id, CgShaderType type) {
        return new CgShaderPort(id, type, Direction.OUTPUT, null);
    }

    public boolean isInput() {
        return direction == Direction.INPUT;
    }

    public boolean isOutput() {
        return direction == Direction.OUTPUT;
    }

    public boolean isDynamic() {
        return type == CgShaderType.DYNAMIC;
    }
}
