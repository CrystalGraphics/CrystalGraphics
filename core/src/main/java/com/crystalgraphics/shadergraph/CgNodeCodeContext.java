package com.crystalgraphics.shadergraph;

import java.util.Map;

/**
 * What a node is handed when it emits code: resolved names, resolved types, and nothing else.
 *
 * <p>Deliberately not the graph. A node that could see its neighbours could namespace wrongly, could
 * depend on emission order, and could not be tested on its own — so it is given the finished answers
 * instead. This is Godot's {@code generate_code} parameter list, as a record.</p>
 *
 * @param inputs   port id → the GLSL expression to read, already cast to the port's resolved type.
 *                 An unconnected input is its default literal, so a node never branches on connectedness
 * @param outputs  port id → the variable name to assign. The compiler has already declared these
 * @param types    port id → the type it resolved to, which matters only for a node whose ports are
 *                 {@link CgShaderType#DYNAMIC} and which therefore has to name its own type
 * @param forPreview whether this is a preview compile — Godot's {@code p_for_preview}. A node may emit
 *                 something cheaper, and the master node emits <em>this node's</em> value as the colour
 *                 rather than the graph's real output
 */
public record CgNodeCodeContext(Map<String, String> inputs,
                                Map<String, String> outputs,
                                Map<String, CgShaderType> types,
                                boolean forPreview) {

    /** The expression for an input port. */
    public String in(String portId) {
        String expression = inputs.get(portId);
        if (expression == null) {
            throw new IllegalArgumentException("No input named '" + portId + "' — the node declared "
                    + inputs.keySet());
        }
        return expression;
    }

    /** The variable to assign for an output port. */
    public String out(String portId) {
        String name = outputs.get(portId);
        if (name == null) {
            throw new IllegalArgumentException("No output named '" + portId + "' — the node declared "
                    + outputs.keySet());
        }
        return name;
    }

    /** The resolved type of a port, for a node that needs to name it. */
    public CgShaderType type(String portId) {
        CgShaderType type = types.get(portId);
        if (type == null) throw new IllegalArgumentException("No port named '" + portId + "'");
        return type;
    }
}
