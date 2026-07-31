package com.crystalgraphics.shadergraph;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
 * <p>{@code {PortId}} is replaced by a variable name or a literal; {@code {type:PortId}} by the resolved
 * GLSL type name, which is what lets one template serve every width of a dynamic node. That is the whole
 * language.</p>
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
    private final Set<String> includes;
    private final CgShaderDomain domain;

    private CgTemplateShaderNode(String id, String label, List<CgShaderPort> ports,
                                 String body, Set<String> includes, CgShaderDomain domain) {
        this.id = id;
        this.label = label;
        this.ports = List.copyOf(ports);
        this.body = body;
        this.includes = Set.copyOf(includes);
        this.domain = domain;
    }

    @Override public String id() { return id; }
    @Override public String label() { return label; }
    @Override public List<CgShaderPort> ports() { return ports; }
    @Override public Set<String> includes() { return includes; }
    @Override public CgShaderDomain domain() { return domain; }

    /** The raw template, before substitution — for a test, or for "show me what this node emits". */
    public String bodyTemplate() {
        return body;
    }

    @Override
    public String generateCode(CgNodeCodeContext ctx) {
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
        private final Set<String> includes = new LinkedHashSet<>();
        private CgShaderDomain domain = CgShaderDomain.ANY;

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

        public CgTemplateShaderNode build() {
            if (ports.stream().noneMatch(CgShaderPort::isOutput)) {
                // A node with no output can never be reached from the master node, so it would emit
                // nothing and read as a node that silently does not work.
                throw new IllegalArgumentException("Node " + id + " declares no output port");
            }
            return new CgTemplateShaderNode(id, label, ports, body, includes, domain);
        }
    }
}
