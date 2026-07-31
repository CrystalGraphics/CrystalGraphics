package com.crystalgraphics.shadergraph;

import java.util.List;

/**
 * A choice on a node that changes the GLSL it emits — Unity's {@code Space} dropdown, and everything
 * shaped like it.
 *
 * <h3>A property is not a port, and the difference is load-bearing</h3>
 * <p>A port carries a <b>value at runtime</b>: it can be connected, it has a type, and the compiler
 * resolves it. A property is a <b>compile-time choice</b> that selects which code the node emits at all.
 * {@code Position} in object space and {@code Position} in world space are not the same expression with
 * a different input — they are different expressions, and no amount of wiring turns one into the other.</p>
 *
 * <p>That is why this is a separate concept rather than an extra input with a default: an input would
 * imply it could be driven by another node, which would mean branching in the shader for a decision that
 * is known before compiling. Unity and Godot both keep the two apart for the same reason.</p>
 *
 * <h3>Enumerated only, deliberately</h3>
 * <p>Every property that selects a code variant has a finite option list, because the node has to carry
 * a body for each. A free-text property would be a value, and values are what ports are for.</p>
 *
 * @param id           the key a document stores this under, and what {@code generateCode} reads
 * @param label        what the editor shows above the dropdown
 * @param options      the permitted values, in menu order; the first is the default when unset
 * @param defaultValue the option used when a document says nothing
 */
public record CgShaderNodeProperty(String id, String label, CgShaderType type, List<String> options,
                                   String defaultValue) {

    /**
     * What a property is for, in two flavours — told apart by whether {@link #options} is empty.
     *
     * <ul>
     *   <li><b>Enumerated</b> ({@code options} non-empty) — <em>selects a code variant</em>. {@code Space}
     *       picks which body is emitted; the value never appears in GLSL.</li>
     *   <li><b>Value-carrying</b> ({@code options} empty) — <em>is</em> GLSL, read by the body through
     *       <code>{@id}</code>, and {@link #type} says what type it is.</li>
     * </ul>
     *
     * <h4>No widget kind here, deliberately</h4>
     * <p>This is the type the <b>compiler</b> understands. Whether a {@code VEC4} is edited with a swatch,
     * four number boxes or a hex field is CrystalGUI's decision entirely — it maps this onto its own
     * {@code NodeField.Kind}. Carrying a UI enum on this side would be the same taxonomy maintained
     * twice, and the two would drift the first time either side gained a case.</p>
     */
    public CgShaderNodeProperty {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("A node property needs an id");
        }
        options = List.copyOf(options == null ? List.of() : options);
        if (type == null) type = CgShaderType.DYNAMIC;
        if (label == null) label = id;
        boolean enumerated = !options.isEmpty();
        if (enumerated && (defaultValue == null || !options.contains(defaultValue))) {
            defaultValue = options.get(0);
        }
        if (defaultValue == null) defaultValue = "";
        if (!enumerated && defaultValue.isEmpty()) {
            // A value property with nothing to emit would substitute an empty string into the body and
            // produce GLSL like `x = ;` — a syntax error blamed on generated code.
            throw new IllegalArgumentException("Value property '" + id + "' needs a default literal");
        }
    }

    /** Whether this selects a variant (rather than carrying a value). */
    public boolean isEnumerated() {
        return !options.isEmpty();
    }

    /** An enumerated property whose default is its first option. */
    public static CgShaderNodeProperty of(String id, String label, String... options) {
        return new CgShaderNodeProperty(id, label, CgShaderType.DYNAMIC, List.of(options), null);
    }

    /** A typed value the node's body reads through <code>{@id}</code>. */
    public static CgShaderNodeProperty value(String id, String label, CgShaderType type,
                                             String defaultLiteral) {
        return new CgShaderNodeProperty(id, label, type, List.of(), defaultLiteral);
    }

    /**
     * Whether {@code value} is usable.
     *
     * <p>Only an enumerated property can reject one: any text is a legal literal as far as this layer is
     * concerned, and validating GLSL here would mean parsing GLSL, which this project deliberately does
     * not do.</p>
     */
    public boolean accepts(String value) {
        return !isEnumerated() ? value != null : value != null && options.contains(value);
    }

    /** {@code value} if usable, otherwise the default — a stored document may be stale. */
    public String resolve(String value) {
        return accepts(value) ? value : defaultValue;
    }
}
