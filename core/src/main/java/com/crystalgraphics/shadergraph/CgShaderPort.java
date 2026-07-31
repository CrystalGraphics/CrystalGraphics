package com.crystalgraphics.shadergraph;

import javax.annotation.Nullable;

/**
 * One input or output on a {@link CgShaderNode} — the port's <b>identity and type</b>, never its widget.
 *
 * @param id        the name the template substitutes and an edge points at. Must be a legal GLSL
 *                  identifier fragment, since it ends up in the emitted variable name
 * @param type      the GLSL type, or {@link CgShaderType#DYNAMIC} to resolve from what is connected
 * @param direction which side of the node this sits on
 * @param defaultExpression the GLSL literal used when an <b>input</b> is left unconnected — the value
 *                  the editor's inline field collects. Null means the node cannot function without a
 *                  connection, which the compiler reports rather than substituting a zero
 */
public record CgShaderPort(String id, CgShaderType type, Direction direction,
                           @Nullable String defaultExpression) {

    public enum Direction { INPUT, OUTPUT }

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
        return new CgShaderPort(id, type, Direction.INPUT, defaultExpression);
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
