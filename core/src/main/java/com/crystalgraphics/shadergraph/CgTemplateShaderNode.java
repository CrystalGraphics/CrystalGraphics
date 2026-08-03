package com.crystalgraphics.shadergraph;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@link CgShaderNode} whose body is a <b>template string</b> — the declarative case, and the one most
 * of the library is made of.
 *
 * <pre>{@code
 * CgTemplateShaderNode.of("cg:math/multiply").label("Multiply")
 *         .in("A", CgShaderType.DYNAMIC, "0.0")
 *         .in("B", CgShaderType.DYNAMIC, "1.0")
 *         .out("Out", CgShaderType.DYNAMIC)
 *         .body("{Out} = {A} * {B};")
 *         .build();
 * }</pre>
 *
 * <h3>Substitution, and deliberately nothing else</h3>
 * <p>Three tokens, and that is the whole language:</p>
 * <ul>
 *   <li>{@code {PortId}} — the variable name or literal that port resolves to</li>
 *   <li>{@code {type:PortId}} — the resolved GLSL type name, which lets one template serve every width
 *       of a dynamic node</li>
 *   <li>{@code {@PropertyId}} — the value of a {@link CgShaderNodeProperty}, for a constant nothing can
 *       be wired into</li>
 * </ul>
 *
 * <p><b>It must not grow conditionals.</b> The moment a template can branch it is a programming language
 * with no debugger, no line numbers and no tests — and the escape hatch already exists twice over: a node
 * that needs real logic implements {@link CgShaderNode} directly, and a node that needs complex GLSL
 * calls a function from the stdlib and declares the {@link #includes() include}. Unity's Custom Function
 * node draws the line in the same place, and its one token ({@code $precision}) is substitution too.</p>
 */
public final class CgTemplateShaderNode implements CgShaderNode {

    private final String id;
    private final String label;
    private final List<CgShaderPort> ports;
    private final String body;
    /** The {@code forPreview} form, or null when the ordinary body works in both. */
    private final String previewBody;
    private final List<CgShaderNodeProperty> properties;
    /** {@code "<propertyId>=<option>"} (optionally {@code "#preview"}) → the body for that variant. */
    private final Map<String, String> variantBodies;
    private final Set<String> includes;
    private final CgShaderDomain domain;
    private final CgPreviewGeometry previewGeometry;
    private final boolean showsPreview;
    private final boolean animated;

    private CgTemplateShaderNode(String id, String label, List<CgShaderPort> ports,
                                 String body, String previewBody, Set<String> includes,
                                 CgShaderDomain domain, CgPreviewGeometry previewGeometry,
                                 boolean showsPreview, boolean animated,
                                 List<CgShaderNodeProperty> properties,
                                 Map<String, String> variantBodies) {
        this.properties = List.copyOf(properties);
        this.variantBodies = Map.copyOf(variantBodies);
        this.id = id;
        this.label = label;
        this.ports = List.copyOf(ports);
        this.body = body;
        this.previewBody = previewBody;
        this.includes = Set.copyOf(includes);
        this.domain = domain;
        this.previewGeometry = previewGeometry;
        this.showsPreview = showsPreview;
        this.animated = animated;
    }

    @Override public String id() { return id; }
    @Override public String label() { return label; }
    @Override public List<CgShaderPort> ports() { return ports; }
    @Override public Set<String> includes() { return includes; }
    @Override public CgShaderDomain domain() { return domain; }
    @Override public CgPreviewGeometry previewGeometry() { return previewGeometry; }
    @Override public boolean showsPreview() { return showsPreview; }
    @Override public boolean isAnimated() { return animated; }
    @Override public boolean hasPreviewForm() { return previewBody != null; }
    @Override public List<CgShaderNodeProperty> properties() { return properties; }

    /** The raw template, before substitution — for a test, or for "show me what this node emits". */
    public String bodyTemplate() {
        return body;
    }

    /**
     * Which body to emit: the variant for the chosen property option, in its preview form if there is
     * one, falling back to the plain body.
     *
     * <p>Keyed {@code "<propertyId>=<option>"} and, for the preview form, that plus {@code "#preview"}.
     * Flat rather than nested because a node has at most a couple of properties, and a map of maps buys
     * nothing but indirection at this size.</p>
     */
    private String selectBody(CgNodeCodeContext ctx) {
        for (CgShaderNodeProperty property : properties) {
            String chosen = ctx.property(property.id());
            if (chosen == null) continue;
            String key = property.id() + "=" + chosen;
            if (ctx.forPreview()) {
                String preview = variantBodies.get(key + "#preview");
                if (preview != null) return preview;
            }
            String variant = variantBodies.get(key);
            if (variant != null) return variant;
        }
        return ctx.forPreview() && previewBody != null ? previewBody : body;
    }

    @Override
    public String generateCode(CgNodeCodeContext ctx) {
        // Godot's p_for_preview, and the reason it is threaded through at all. A node reading a vertex
        // attribute cannot run in the fragment stage, which is where a preview evaluates everything — so
        // it declares a second form reading the preview's own varying instead. Same node, same ports,
        // same type resolution; only the one line that fetches the value differs.
        String body = selectBody(ctx);

        StringBuilder out = new StringBuilder(body.length() + 32);
        int i = 0;
        while (i < body.length()) {
            char c = body.charAt(i);
            if (c != '{') {
                out.append(c);
                i++;
                continue;
            }
            int close = body.indexOf('}', i);
            if (close < 0) {
                // An unterminated placeholder is a broken node definition, and emitting it verbatim
                // would produce GLSL whose error points at the driver rather than at the author.
                throw new IllegalStateException("Unterminated '{' in body of node " + id + ": " + body);
            }
            out.append(resolve(body.substring(i + 1, close), ctx));
            i = close + 1;
        }
        return out.toString();
    }

    private String resolve(String token, CgNodeCodeContext ctx) {
        if (token.startsWith("type:")) {
            return ctx.type(token.substring(5)).glsl();
        }
        // {@id} — the VALUE of a property, as opposed to a port. This is what lets a node hold a literal
        // nothing can be wired to: a Color's swatch has no input port in Unity either, because a colour
        // you pick and a colour another node computes are different things and only one of them is a
        // connection.
        if (token.startsWith("@")) {
            String propertyId = token.substring(1);
            String value = ctx.property(propertyId);
            if (value != null) return value;
            throw new IllegalStateException("Node " + id + " references unknown property '" + propertyId
                    + "' — it declares " + properties.stream().map(CgShaderNodeProperty::id).toList());
        }
        // Inputs before outputs: a port id is unique across the node, so at most one of these matches,
        // and asking in this order means the common case costs one lookup.
        String input = ctx.inputs().get(token);
        if (input != null) return input;
        String output = ctx.outputs().get(token);
        if (output != null) return output;
        throw new IllegalStateException("Node " + id + " references unknown port '" + token
                + "' — it declares " + ports.stream().map(CgShaderPort::id).toList());
    }

    // ── Building ────────────────────────────────────────────────────────────

    public static Builder of(String id) {
        return new Builder(id);
    }

    public static final class Builder {

        private final String id;
        private String label;
        private final List<CgShaderPort> ports = new ArrayList<>();
        private String body = "";
        private String previewBody;
        private final List<CgShaderNodeProperty> properties = new ArrayList<>();
        private final Map<String, String> variantBodies = new LinkedHashMap<>();
        private final Set<String> includes = new LinkedHashSet<>();
        private CgShaderDomain domain = CgShaderDomain.ANY;
        private CgPreviewGeometry previewGeometry = CgPreviewGeometry.INHERIT;
        private boolean showsPreview = true;
        private boolean animated = false;

        private Builder(String id) {
            if (id == null || id.isEmpty()) throw new IllegalArgumentException("Node id must not be empty");
            this.id = id;
            this.label = id;
        }

        public Builder label(String value) {
            this.label = value == null || value.isEmpty() ? id : value;
            return this;
        }

        public Builder in(String portId, CgShaderType type, @Nullable String defaultExpression) {
            ports.add(CgShaderPort.input(portId, type, defaultExpression));
            return this;
        }

        /**
         * As {@link #in(String, CgShaderType, String)}, but the editor offers no inline field for it.
         *
         * <p>Distinct from {@link CgShaderPort#engineDefault}: that is for a default that is not a value
         * the user owns at all ({@code cg_Position}). This is still an ordinary literal the compiler is
         * free to substitute — it is only the WIDGET that is wrong for it, because {@code CgShaderType}
         * alone cannot say "this vec4 is a colour" vs "this vec4 is four unrelated channels": {@code
         * Split.In} is the latter, and the colour swatch {@code widgetKindFor(VEC4)} gives every other
         * vec4 port implies a meaning this one does not have.</p>
         */
        public Builder inNoInlineEditor(String portId, CgShaderType type, String defaultExpression) {
            ports.add(new CgShaderPort(portId, type, CgShaderPort.Direction.INPUT, defaultExpression, false));
            return this;
        }

        /**
         * An input whose default, when unconnected, is another node's output rather than a literal —
         * see {@link CgShaderPort#implicitDefault} for why this exists and what it changes about
         * compilation.
         *
         * @param sourceNode deferred on purpose — see {@link CgShaderPort#implicitSource}'s own doc
         */
        public Builder inWithImplicitDefault(String portId, CgShaderType type,
                java.util.function.Supplier<CgShaderNode> sourceNode, String sourceOutputPort) {
            ports.add(CgShaderPort.implicitDefault(portId, type, sourceNode, sourceOutputPort));
            return this;
        }

        public Builder out(String portId, CgShaderType type) {
            ports.add(CgShaderPort.output(portId, type));
            return this;
        }

        public Builder body(String glsl) {
            this.body = glsl == null ? "" : glsl;
            return this;
        }

        /** @see CgShaderNode#includes() */
        public Builder include(String resourcePath) {
            includes.add(resourcePath);
            return this;
        }

        /** @see CgShaderNode#domain() */
        public Builder domain(CgShaderDomain value) {
            this.domain = value == null ? CgShaderDomain.ANY : value;
            return this;
        }

        /**
         * The body to emit for a <b>preview</b> compile, when the ordinary one cannot run there.
         *
         * <p>Needed by exactly the nodes that read a vertex attribute: {@code cg_Position} and friends
         * do not exist in the fragment stage, which is where a preview evaluates everything. The preview
         * shader provides {@code i.uv}, {@code i.objectPos} and {@code i.normal} as varyings, so the
         * preview form reads those instead.</p>
         *
         * <p>Setting this also makes the node <b>previewable despite a VERTEX domain</b> — without it,
         * the preview emitter refuses such a node rather than emitting GLSL the driver will reject.</p>
         */
        public Builder previewBody(String glsl) {
            this.previewBody = glsl;
            return this;
        }

        /**
         * Declares a compile-time choice — a dropdown on the node.
         *
         * @see CgShaderNodeProperty
         */
        public Builder property(CgShaderNodeProperty property) {
            properties.add(property);
            return this;
        }

        /**
         * The body to emit when {@code propertyId} is set to {@code option}.
         *
         * <p>Falls back to {@link #body} for any option with no variant declared, so a property only has
         * to spell out the cases that genuinely differ.</p>
         */
        public Builder bodyFor(String propertyId, String option, String glsl) {
            variantBodies.put(propertyId + "=" + option, glsl);
            return this;
        }

        /**
         * The {@code forPreview} body for one option — needed when that variant reads a vertex attribute.
         *
         * @see #previewBody
         */
        public Builder previewBodyFor(String propertyId, String option, String glsl) {
            variantBodies.put(propertyId + "=" + option + "#preview", glsl);
            return this;
        }

        /**
         * Suppresses this node's preview thumbnail.
         *
         * @see CgShaderNode#showsPreview()
         */
        public Builder noPreview() {
            this.showsPreview = false;
            return this;
        }

        /** @see CgShaderNode#previewGeometry() */
        public Builder previewGeometry(CgPreviewGeometry value) {
            this.previewGeometry = value == null ? CgPreviewGeometry.INHERIT : value;
            return this;
        }

        /** @see CgShaderNode#isAnimated() */
        public Builder animated() {
            this.animated = true;
            return this;
        }

        public CgTemplateShaderNode build() {
            if (ports.stream().noneMatch(CgShaderPort::isOutput)) {
                // A node with no output can never be reached from the master node, so it would emit
                // nothing and read as a node that silently does not work.
                throw new IllegalArgumentException("Node " + id + " declares no output port");
            }
            return new CgTemplateShaderNode(id, label, ports, body, previewBody, includes, domain,
                    previewGeometry, showsPreview, animated, properties, variantBodies);
        }
    }
}
