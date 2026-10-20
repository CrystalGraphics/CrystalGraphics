package com.crystalgraphics.asset.camera;

import com.crystalgraphics.asset.kind.CgCameraKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.object.CgType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A camera rig asset compiled ({@code cameras/rigs/}: Gameplay Cameras' {@code UCameraRigAsset}): its node tree
 * flattened in evaluation order, each node's kind resolved through the {@link CgCameraKinds#NODES} registry, and its
 * parameters, tags and host requirements. The evaluators that run the nodes are the camera system's.
 *
 * <pre>{@code
 * CgCameraRigDefinition.register();                                    // at init: the rig kind compiles to these
 * CgCameraRigDefinition rig = (CgCameraRigDefinition) handle.definition();
 * for (CgCameraRigDefinition.Node n : rig.nodes()) build(n.type(), n.fields());   // parents before children
 * }</pre>
 *
 * <ul>
 *   <li>A node whose {@code node} names no registered kind fails the compile, naming it and the asset: an unknown
 *       kind never reaches a frame.</li>
 *   <li>A mod's kind joins {@code NODES} before {@code CgTypes.freeze()}.</li>
 * </ul>
 */
public final class CgCameraRigDefinition {

    /** The rig kind's compiler. */
    public static final CgAssetCompiler<CgCameraRigDefinition> COMPILER = (asset, deps) -> compile(asset);

    /** A node: its id, kind, registered type, own fields, and the index of its parent in {@link #nodes()}, or -1. */
    public record Node(String id, String kind, CgType type, Map<Object, Object> fields, int parent) {
    }

    /** A rig parameter ({@code rig.<name>}): its value type and its default, or null. */
    public record Parameter(String name, String type, Object defaultValue) {
    }

    private final String id;
    private final List<Node> nodes;
    private final List<Parameter> parameters;
    private final List<String> tags, requires;
    private final String initialOrientation, onMissing;

    private CgCameraRigDefinition(String id, List<Node> nodes, List<Parameter> parameters, List<String> tags,
                                  List<String> requires, String initialOrientation, String onMissing) {
        this.id = id;
        this.nodes = Collections.unmodifiableList(nodes);
        this.parameters = Collections.unmodifiableList(parameters);
        this.tags = tags;
        this.requires = requires;
        this.initialOrientation = initialOrientation;
        this.onMissing = onMissing;
    }

    /** Registers {@link #COMPILER} as the rig kind's. */
    public static void register() {
        CgAssetCompiler.register(CgCameraKinds.CAMERA_RIG, COMPILER);
    }

    /** {@code asset}'s definition; throws on a node kind no registry holds. */
    public static CgCameraRigDefinition compile(CgAssetData asset) {
        Map<Object, Object> tree = asset.resolved();
        List<Node> nodes = new ArrayList<>();
        flatten(asset.id(), (List<?>) tree.get("root"), -1, nodes);
        List<Parameter> parameters = new ArrayList<>();
        if (tree.get("parameters") instanceof Map<?, ?> declared) {
            for (Map.Entry<?, ?> e : declared.entrySet()) {
                Map<?, ?> p = (Map<?, ?>) e.getValue();
                parameters.add(new Parameter(String.valueOf(e.getKey()), String.valueOf(p.get("type")), p.get("default")));
            }
        }
        return new CgCameraRigDefinition(asset.id(), nodes, parameters, strings(tree.get("tags")),
                strings(tree.get("requires")), text(tree.get("initial_orientation"), "none"),
                text(tree.get("on_missing"), "refuse"));
    }

    private static void flatten(String rig, List<?> list, int parent, List<Node> out) {
        if (list == null) return;
        for (Object o : list) {
            @SuppressWarnings("unchecked")
            Map<Object, Object> fields = (Map<Object, Object>) o;
            String kind = String.valueOf(fields.get("node"));
            CgType type = CgCameraKinds.NODES.member(kind);
            if (type == null) {
                throw new IllegalArgumentException(rig + ": camera node " + fields.get("id") + " is of kind '" + kind
                        + "', which no registry holds; known: " + CgCameraKinds.NODES.members().keySet());
            }
            int at = out.size();
            out.add(new Node(String.valueOf(fields.get("id")), kind, type, fields, parent));
            if (fields.get("children") instanceof List<?> children) flatten(rig, children, at, out);
        }
    }

    private static List<String> strings(Object list) {
        if (!(list instanceof List<?> l)) return List.of();
        List<String> out = new ArrayList<>(l.size());
        for (Object o : l) out.add(String.valueOf(o));
        return Collections.unmodifiableList(out);
    }

    private static String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    public String id() {
        return id;
    }

    /** Every node, parents before their children, in the order the rig evaluates them. */
    public List<Node> nodes() {
        return nodes;
    }

    public List<Parameter> parameters() {
        return parameters;
    }

    public List<String> tags() {
        return tags;
    }

    /** What the host must apply ({@code roll}, ...); see {@link #onMissing()}. */
    public List<String> requires() {
        return requires;
    }

    /** {@code none} unless given: how its first pose is seeded. */
    public String initialOrientation() {
        return initialOrientation;
    }

    /** {@code refuse} unless given: what a host lacking a requirement does. */
    public String onMissing() {
        return onMissing;
    }
}
