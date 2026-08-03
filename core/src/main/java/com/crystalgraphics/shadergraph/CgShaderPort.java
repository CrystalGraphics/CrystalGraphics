package com.crystalgraphics.shadergraph;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * One input or output on a {@link CgShaderNode} — the port's <b>identity and type</b>, never its widget.
 *
 * @param id        the name the template substitutes and an edge points at. Must be a legal GLSL
 *                  identifier fragment, since it ends up in the emitted variable name
 * @param type      the GLSL type, or {@link CgShaderType#DYNAMIC} to resolve from what is connected
 * @param direction which side of the node this sits on
 * @param defaultExpression the GLSL used when an <b>input</b> is left unconnected. Null means the node
 *                  cannot function without a connection, which the compiler reports rather than
 *                  substituting a zero — UNLESS {@link #implicitSource} is set, in which case null here
 *                  is the normal case; see that field
 * @param defaultIsLiteral whether that default is a value a user may edit (so the editor offers an inline
 *                  field) or an engine expression like {@code cg_Position} (so it does not) — see
 *                  {@link #engineDefault}
 * @param implicitSource for a port whose "default" is not a value at all but another NODE — see
 *                  {@link #implicitDefault}. A {@link Supplier} rather than a direct
 *                  {@link CgShaderNode} reference because {@code CgBuiltinShaderNodes}' own fields
 *                  initialize top to bottom, and a port declared earlier in that class cannot reference
 *                  a {@code static final} declared later; deferring the lookup past class-init time
 *                  removes the ordering dependency entirely, at the cost of one indirection this is
 *                  never on a hot path. <b>Write the lambda body as {@code ClassName.FIELD}, not the
 *                  bare {@code FIELD}</b> — JLS 8.3.3's illegal-forward-reference check is a textual,
 *                  same-class rule that still fires on a bare simple name sitting inside a lambda
 *                  literal, even though the lambda body plainly runs after class-init finishes; a
 *                  qualified name is not a "simple name" and is exempt.
 * @param implicitSourcePort the output port of {@link #implicitSource} this input reads
 */
public record CgShaderPort(String id, CgShaderType type, Direction direction,
                           @Nullable String defaultExpression, boolean defaultIsLiteral,
                           @Nullable Supplier<CgShaderNode> implicitSource,
                           @Nullable String implicitSourcePort) {

    public enum Direction { INPUT, OUTPUT }

    /**
     * A port whose default is a plain literal the user may edit — the ordinary case.
     *
     * @see #engineDefault
     */
    public CgShaderPort(String id, CgShaderType type, Direction direction,
                        @Nullable String defaultExpression) {
        this(id, type, direction, defaultExpression, true, null, null);
    }

    /** As the 5-arg canonical constructor, with no implicit source — every existing caller. */
    public CgShaderPort(String id, CgShaderType type, Direction direction,
                        @Nullable String defaultExpression, boolean defaultIsLiteral) {
        this(id, type, direction, defaultExpression, defaultIsLiteral, null, null);
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
        if (direction == Direction.OUTPUT && implicitSource != null) {
            throw new IllegalArgumentException("Output port " + id + " must not declare an implicit source");
        }
        if (implicitSource != null && defaultExpression != null) {
            // Two answers to "what fills this when unconnected" is a port that cannot be described —
            // the compiler would have to pick one silently, and whichever it picked would look like the
            // other is dead code.
            throw new IllegalArgumentException("Port " + id
                    + " declares both a literal default and an implicit source");
        }
        if (implicitSource != null && implicitSourcePort == null) {
            throw new IllegalArgumentException("Port " + id + " declares an implicit source with no port");
        }
    }

    public static CgShaderPort input(String id, CgShaderType type, @Nullable String defaultExpression) {
        return new CgShaderPort(id, type, Direction.INPUT, defaultExpression, true, null, null);
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
        return new CgShaderPort(id, type, Direction.INPUT, expression, false, null, null);
    }

    /**
     * An input whose default, when left unconnected, is not a value the compiler can substitute
     * verbatim — it is what CONNECTING it to another node's output would produce, resolved lazily by
     * {@link CgGraphCompiler} the same way an explicit wire is.
     *
     * <h3>Why this exists — Unity's own convention, ported</h3>
     * <p>A {@code UV}-typed slot left unconnected in Unity Shader Graph is not zero, and is not a fixed
     * number at all: it behaves exactly as if the {@code UV} node were wired into it, which is why
     * leaving {@code Polar Coordinates}' own {@code UV} input untouched still shows the familiar
     * red/green gradient rather than one flat colour. A literal default (this record's
     * {@link #defaultExpression}) cannot express that — it is one fixed GLSL snippet, valid in exactly
     * one stage, whereas "the current UV" is spelled differently in a vertex body
     * ({@code cg_TexCoord0}), a preview fragment body ({@code i.uv}), and a real compiled material's own
     * per-graph varying struct. Wiring in the ACTUAL {@code UV} node — which already emits the right
     * form for each of those three contexts via its own {@code body}/{@code previewBody} — is the only
     * answer that is correct in all three without a fourth, redundant code path.</p>
     *
     * @param sourceNode the node type to implicitly instantiate — deferred; see the field doc on why
     * @param sourceOutputPort the output port of {@code sourceNode} this input reads
     */
    public static CgShaderPort implicitDefault(String id, CgShaderType type,
                                               Supplier<CgShaderNode> sourceNode, String sourceOutputPort) {
        return new CgShaderPort(id, type, Direction.INPUT, null, false, sourceNode, sourceOutputPort);
    }

    public static CgShaderPort output(String id, CgShaderType type) {
        return new CgShaderPort(id, type, Direction.OUTPUT, null, true, null, null);
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

    public boolean hasImplicitSource() {
        return implicitSource != null;
    }
}
