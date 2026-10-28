package com.crystalgraphics.camera;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3d;

import com.crystalgraphics.asset.camera.CgCameraRigDefinition;
import com.crystalgraphics.camera.node.CgCameraNodes;
import com.crystalgraphics.object.CgProperty;
import com.crystalgraphics.object.CgValueType;
import com.crystalgraphics.object.value.CgQuantity;
import com.crystalgraphics.object.value.CgUnits;

/**
 * A rig running for one viewer: its definition's nodes built into evaluators, and its parameters ({@code rig.<name>})
 * in the host's units. Mirrors Gameplay Cameras' camera rig evaluator: a context's director hands it to the camera
 * system, which blends it in.
 *
 * <pre>{@code
 * CgCameraRig skill = new CgCameraRig(definition)
 *         .parameter("fov", 45)                         // a float
 *         .parameter("boom", 0, 1.5, -4)                // a vector, in blocks along the host's axes
 *         .parameter("rotation", turn);                 // a turn
 * context.director(CgCameraDirector.single(skill));
 * }</pre>
 *
 * <ul>
 *   <li>A node kind with no evaluator fails here, naming it: a rig never plays a node as something else.</li>
 *   <li>A parameter it does not declare throws; a value set holds until set again.</li>
 *   <li>One instance a viewer: its nodes keep state from frame to frame.</li>
 * </ul>
 */
public final class CgCameraRig {

    private final CgCameraRigDefinition definition;
    private final Map<String, Object> parameters = new HashMap<>();
    private final Map<String, String> types = new HashMap<>();
    private final CgCameraNode[] nodes;

    public CgCameraRig(CgCameraRigDefinition definition) {
        this.definition = Objects.requireNonNull(definition, "definition");
        CgUnits units = definition.units();
        for (CgCameraRigDefinition.Parameter p : definition.parameters()) {
            types.put(p.name(), p.type());
            parameters.put(p.name(), host(units, p.defaultValue(), p.type(), CgQuantity.bySpecName(p.quantity())));
        }
        List<CgCameraRigDefinition.Node> list = definition.nodes();
        nodes = new CgCameraNode[list.size()];
        for (int i = 0; i < nodes.length; i++) nodes[i] = CgCameraNodes.build(list.get(i), this);
    }

    public CgCameraRigDefinition definition() {
        return definition;
    }

    public CgCameraRig parameter(String name, double value) {
        return put(name, value);
    }

    public CgCameraRig parameter(String name, double x, double y, double z) {
        Object v = parameters.get(require(name));
        if (v instanceof Vector3d to) to.set(x, y, z);
        else throw new IllegalArgumentException(definition.id() + ": rig." + name + " is not a vector");
        return this;
    }

    public CgCameraRig parameter(String name, Quaternionfc turn) {
        Object v = parameters.get(require(name));
        if (v instanceof Quaternionf to) to.set(turn);
        else throw new IllegalArgumentException(definition.id() + ": rig." + name + " is not a rotation");
        return this;
    }

    /** {@code rig.<name>}'s value now: a {@code Double}, a {@code Boolean}, a {@link Vector3d} or a {@link Quaternionf}. */
    public Object parameter(String name) {
        return parameters.get(require(name));
    }

    private CgCameraRig put(String name, Object value) {
        require(name);
        parameters.put(name, value);
        return this;
    }

    private String require(String name) {
        if (!types.containsKey(name)) throw new IllegalArgumentException(definition.id() + " has no parameter " + name);
        return name;
    }

    /** Runs every node in order on {@code pose}. */
    void run(Run run, CgCameraPose pose) {
        for (CgCameraNode n : nodes) n.run(run, pose);
    }

    /**
     * {@code node}'s field {@code field} as its evaluator reads it each frame: a constant in host units, or a parameter
     * by {@code { bind: rig.<name> }}, remapped by its {@code remap: { in, out }}. Null for a field not written.
     */
    @Nullable
    public Value value(CgCameraRigDefinition.Node node, String field) {
        Object written = node.fields().get(field);
        if (written == null) return null;
        String where = definition.id() + ": " + node.id() + "." + field;
        CgProperty p = node.type().property(field);
        CgQuantity q = p == null ? CgQuantity.NONE : p.quantity();
        String type = p == null ? "float" : p.type() == CgValueType.ROTATION ? "rotation"
                : p.type() == CgValueType.VEC3 || p.type() == CgValueType.POSITION ? "vec3"
                : p.type() == CgValueType.BOOL ? "bool" : "float";
        return valueOf(where, written, type, q);
    }

    /**
     * A value written at {@code where} inside a node (an attachment's weight), read as {@code type} ({@code float},
     * {@code bool}, {@code vec3}, {@code rotation}) in {@code quantity}: a constant, or a bound parameter.
     */
    public Value valueOf(String where, Object written, String type, CgQuantity quantity) {
        if (written instanceof Map<?, ?> m && m.get("bind") instanceof String bind) {
            if (!bind.startsWith("rig.")) throw new IllegalArgumentException(where + ": binds " + bind + ", not a rig.<name>");
            String name = bind.substring(4);
            if (!types.containsKey(name)) throw new IllegalArgumentException(where + ": binds rig." + name + ", which the rig does not declare");
            return new Value(null, name, remap(where, m.get("remap")));
        }
        return new Value(host(definition.units(), written, type, quantity), null, null);
    }

    @Nullable
    private static double[] remap(String where, @Nullable Object written) {
        if (written == null) return null;
        if (written instanceof Map<?, ?> m && m.get("in") instanceof List<?> in && m.get("out") instanceof List<?> out
                && in.size() == 2 && out.size() == 2) {
            return new double[] {num(in.get(0)), num(in.get(1)), num(out.get(0)), num(out.get(1))};
        }
        throw new IllegalArgumentException(where + ".remap: { in: [a, b], out: [c, d] }, not " + written);
    }

    private static double num(Object o) {
        return ((Number) o).doubleValue();
    }

    /** {@code written} in the host's units, as the type a node reads. */
    private static Object host(CgUnits units, @Nullable Object written, String type, CgQuantity q) {
        switch (type) {
            case "rotation":
                return units.rotationToHost(written, new Quaternionf());
            case "vec3":
            case "position": {
                Vector3d v = new Vector3d();
                if (written instanceof List<?> l && l.size() == 3) {
                    List<?> h = (List<?>) units.toHost(written, q);
                    v.set(num(h.get(0)), num(h.get(1)), num(h.get(2)));
                }
                return v;
            }
            case "bool":
                return Boolean.TRUE.equals(written);
            default:
                return written instanceof Number n ? ((Number) units.toHost(n, q)).doubleValue() : 0.0;
        }
    }

    @Override
    public String toString() {
        return "CgCameraRig[" + definition.id() + "]";
    }

    /** What a node is run with: the context it runs for and the frame's step on the viewer's clock. */
    public static final class Run {
        CgCameraContext context;
        float dt;
        boolean first;
        CgCameraRig rig;

        public CgCameraContext context() {
            return context;
        }

        /** Seconds since the last frame, on the viewer's clock. */
        public float dt() {
            return dt;
        }

        /** Whether this is its rig's first frame. */
        public boolean first() {
            return first;
        }
    }

    /** A node field's value as read each frame: a constant, or a parameter remapped. */
    public final class Value {
        @Nullable
        private final Object constant;
        @Nullable
        private final String parameter;
        @Nullable
        private final double[] remap;

        Value(@Nullable Object constant, @Nullable String parameter, @Nullable double[] remap) {
            this.constant = constant;
            this.parameter = parameter;
            this.remap = remap;
        }

        private Object raw() {
            return parameter == null ? constant : parameters.get(parameter);
        }

        public double number() {
            Object v = raw();
            double x = v instanceof Number n ? n.doubleValue() : v instanceof Boolean b && b ? 1 : 0;
            if (remap == null) return x;
            double span = remap[1] - remap[0];
            return remap[2] + (span == 0 ? 0 : (x - remap[0]) / span) * (remap[3] - remap[2]);
        }

        public boolean bool() {
            Object v = raw();
            return v instanceof Boolean b ? b : number() != 0;
        }

        public Vector3d vector(Vector3d out) {
            return raw() instanceof Vector3d v ? out.set(v) : out.zero();
        }

        public Quaternionf rotation(Quaternionf out) {
            return raw() instanceof Quaternionf q ? out.set(q) : out.identity();
        }
    }
}
