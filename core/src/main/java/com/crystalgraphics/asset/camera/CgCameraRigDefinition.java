package com.crystalgraphics.asset.camera;

import com.crystalgraphics.asset.kind.CgCameraKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.camera.CgCameraBlend;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.value.CgUnits;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

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
    public static final CgAssetCompiler<CgCameraRigDefinition> COMPILER = (asset, deps) -> compile(asset, deps.units());

    /** A node: its id, kind, registered type, own fields, and the index of its parent in {@link #nodes()}, or -1. */
    public record Node(String id, String kind, CgType type, Map<Object, Object> fields, int parent) {
    }

    /** A rig parameter ({@code rig.<name>}): its value type, its quantity, and its default as written, or null. */
    public record Parameter(String name, String type, String quantity, Object defaultValue) {
    }

    /**
     * An enter or exit transition ({@code UCameraRigTransition}): the blend it plays and, for an exit, where to
     * ({@code host}, or null for whatever is below). Its conditions must all hold; with none it always applies.
     */
    public record Transition(CgCameraBlend blend, @Nullable String to) {
    }

    private final String id;
    private final List<Node> nodes;
    private final List<Parameter> parameters;
    private final List<String> tags, requires;
    private final String initialOrientation, onMissing;
    private final CgUnits units;
    private final List<Transition> enter, exit;

    private CgCameraRigDefinition(String id, List<Node> nodes, List<Parameter> parameters, List<String> tags,
                                  List<String> requires, String initialOrientation, String onMissing, CgUnits units,
                                  List<Transition> enter, List<Transition> exit) {
        this.id = id;
        this.units = units;
        this.enter = enter;
        this.exit = exit;
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

    /** {@code asset}'s definition in the host's units; throws on a node kind no registry holds. */
    public static CgCameraRigDefinition compile(CgAssetData asset) {
        return compile(asset, CgUnits.HOST);
    }

    /** {@code asset}'s definition, its values written in {@code units}; throws on a construct it does not play. */
    public static CgCameraRigDefinition compile(CgAssetData asset, CgUnits units) {
        Map<Object, Object> tree = asset.resolved();
        List<Node> nodes = new ArrayList<>();
        flatten(asset.id(), (List<?>) tree.get("root"), -1, nodes);
        List<Parameter> parameters = new ArrayList<>();
        if (tree.get("parameters") instanceof Map<?, ?> declared) {
            for (Map.Entry<?, ?> e : declared.entrySet()) {
                Map<?, ?> p = (Map<?, ?>) e.getValue();
                parameters.add(new Parameter(String.valueOf(e.getKey()), String.valueOf(p.get("type")),
                        text(p.get("quantity"), "none"), p.get("default")));
            }
        }
        return new CgCameraRigDefinition(asset.id(), nodes, parameters, strings(tree.get("tags")),
                strings(tree.get("requires")), text(tree.get("initial_orientation"), "none"),
                text(tree.get("on_missing"), "refuse"), units,
                transitions(asset.id(), "enter_transitions", tree.get("enter_transitions")),
                transitions(asset.id(), "exit_transitions", tree.get("exit_transitions")));
    }

    private static List<Transition> transitions(String rig, String field, Object written) {
        if (!(written instanceof List<?> list)) return List.of();
        List<Transition> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Map<?, ?> t = (Map<?, ?>) list.get(i);
            String at = rig + ": " + field + "[" + i + "]";
            if (t.get("conditions") instanceof List<?> c && !c.isEmpty()) {
                throw new IllegalArgumentException(at + ": transition conditions are not played yet");
            }
            try {
                out.add(new Transition(CgCameraBlend.of(t.get("blend")), t.get("to") == null ? null : String.valueOf(t.get("to"))));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(at + ".blend: " + e.getMessage(), e);
            }
        }
        return Collections.unmodifiableList(out);
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

    /** The units its values are written in: what a rig converts them from. */
    public CgUnits units() {
        return units;
    }

    /** The transition it enters by: the first whose conditions hold, or a pop with none. */
    public List<Transition> enterTransitions() {
        return enter;
    }

    /** The transition it leaves by, to what is below it or to {@code host}. */
    public List<Transition> exitTransitions() {
        return exit;
    }

    /** {@code refuse} unless given: what a host lacking a requirement does. */
    public String onMissing() {
        return onMissing;
    }
}
