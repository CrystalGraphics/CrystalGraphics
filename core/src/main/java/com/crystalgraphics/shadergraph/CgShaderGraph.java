package com.crystalgraphics.shadergraph;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The compiler's input: node instances, the edges between them, and which instance is the output.
 *
 * <h3>Why this exists when the editor already has a graph document</h3>
 * <p>{@code GraphDocument} lives in CrystalGUI, and <b>CrystalGraphics must not depend on it</b> — the
 * dependency runs the other way and always has. So the editor maps its document onto this, and the
 * compiler never learns what a widget, a port colour or an undo stack is. It also means a server, or a
 * build step, can compile a shader with no UI library present at all.</p>
 *
 * <p>Deliberately thinner than the editor's document: no positions, no properties the compiler cannot
 * use, no ids beyond what namespacing needs. Everything here is something the emitter reads.</p>
 */
public final class CgShaderGraph {

    /** One placed node: an instance id, its type, and the literal values on its unconnected inputs. */
    public record Instance(String id, CgShaderNode type, Map<String, String> inputValues) {
        public Instance {
            if (id == null || id.isEmpty()) throw new IllegalArgumentException("Instance id must not be empty");
            if (type == null) throw new IllegalArgumentException("Instance " + id + " has no type");
            inputValues = Map.copyOf(inputValues == null ? Map.of() : inputValues);
        }

        public static Instance of(String id, CgShaderNode type) {
            return new Instance(id, type, Map.of());
        }

        /** The literal on an unconnected input, falling back to the port's declared default. */
        @Nullable
        public String valueFor(String portId) {
            String explicit = inputValues.get(portId);
            if (explicit != null) return explicit;
            CgShaderPort port = type.port(portId);
            return port == null ? null : port.defaultExpression();
        }
    }

    /** An edge, named the same way the document names one. */
    public record Link(String fromNode, String fromPort, String toNode, String toPort) {
    }

    private final Map<String, Instance> instances = new LinkedHashMap<>();
    private final List<Link> links = new ArrayList<>();
    private String outputId;

    public CgShaderGraph add(Instance instance) {
        if (instances.putIfAbsent(instance.id(), instance) != null) {
            throw new IllegalArgumentException("Duplicate instance id: " + instance.id());
        }
        return this;
    }

    public CgShaderGraph link(String fromNode, String fromPort, String toNode, String toPort) {
        links.add(new Link(fromNode, fromPort, toNode, toPort));
        return this;
    }

    /** Which instance's value becomes the shader's result. */
    public CgShaderGraph output(String instanceId) {
        this.outputId = instanceId;
        return this;
    }

    @Nullable
    public String outputId() {
        return outputId;
    }

    @Nullable
    public Instance instance(String id) {
        return instances.get(id);
    }

    public java.util.Collection<Instance> instances() {
        return Collections.unmodifiableCollection(instances.values());
    }

    public List<Link> links() {
        return Collections.unmodifiableList(links);
    }

    /** The edge feeding {@code (node, port)}, or null — an input takes at most one. */
    @Nullable
    public Link linkInto(String nodeId, String portId) {
        for (Link link : links) {
            if (link.toNode().equals(nodeId) && link.toPort().equals(portId)) return link;
        }
        return null;
    }

    // ── Ordering ────────────────────────────────────────────────────────────

    /**
     * The instances feeding {@code rootId}, in dependency order, with everything unreachable dropped.
     *
     * <p><b>Rooted rather than whole-graph</b>, and that single parameter is what makes previews free:
     * compiling a preview is compiling this graph with a different root, not a second compiler. Godot
     * reaches the same place with its {@code p_for_preview} flag.</p>
     *
     * <p>Nodes that reach nothing are not emitted at all — a disconnected subgraph the user is still
     * building must not become dead GLSL the driver has to strip.</p>
     *
     * @throws IllegalStateException on a cycle, naming a node in it. The editor refuses cycles at
     *         connect time, so reaching this means the graph did not come from the editor — a paste, a
     *         file, or a bug — and guessing would emit GLSL that never terminates
     */
    public List<Instance> orderedFrom(String rootId) {
        Instance root = instances.get(rootId);
        if (root == null) return List.of();

        Map<String, List<String>> dependencies = new HashMap<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.push(rootId);
        Set<String> reachable = new HashSet<>();

        while (!pending.isEmpty()) {
            String id = pending.pop();
            if (!reachable.add(id)) continue;
            Instance instance = instances.get(id);
            if (instance == null) continue;
            List<String> feeders = new ArrayList<>();
            for (CgShaderPort port : instance.type().inputs()) {
                Link link = linkInto(id, port.id());
                if (link == null) continue;
                feeders.add(link.fromNode());
                pending.push(link.fromNode());
            }
            dependencies.put(id, feeders);
        }

        List<Instance> ordered = new ArrayList<>(reachable.size());
        Set<String> done = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (String id : reachable) visit(id, dependencies, done, visiting, ordered);
        return ordered;
    }

    private void visit(String id, Map<String, List<String>> dependencies,
                       Set<String> done, Set<String> visiting, List<Instance> out) {
        if (done.contains(id)) return;
        if (!visiting.add(id)) {
            throw new IllegalStateException("Cycle in shader graph through node '" + id
                    + "' — a graph with a cycle cannot be topologically ordered and would emit GLSL "
                    + "that never terminates");
        }
        for (String dependency : dependencies.getOrDefault(id, List.of())) {
            visit(dependency, dependencies, done, visiting, out);
        }
        visiting.remove(id);
        done.add(id);
        Instance instance = instances.get(id);
        if (instance != null) out.add(instance);
    }
}
