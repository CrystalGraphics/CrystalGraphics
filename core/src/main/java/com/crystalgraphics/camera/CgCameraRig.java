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
 *         .parameter("fov", Math.toRadians(45))         // a float: an angle is in radians
 *         .parameter("boom", 0, 1.5, -4)                // a vector, in blocks along the host's axes
 *         .parameter("rotation", turn);                 // a turn
 * context.director(CgCameraDirector.single(skill));
 * }</pre>
 *
 * Set every frame, by index, which allocates nothing:
 *
 * <pre>{@code
 * int boom = skill.indexOf("boom");                     // once
 * skill.set(boom, 0, 1.5, -4 - pull);                   // each frame
 * }</pre>
 *
 * <ul>
 *   <li>A node kind with no evaluator fails here, naming it: a rig never plays a node as something else.</li>
 *   <li>A parameter it does not declare throws; a value set holds until set again.</li>
 *   <li>Values are in host units: blocks, seconds, radians. The node turns an angle into the pose's degrees.</li>
 *   <li>One instance a viewer: its nodes keep state from frame to frame.</li>
 * </ul>
 */
public final class CgCameraRig {

    private static final int FLOAT = 0, BOOL = 1, VEC3 = 2, ROTATION = 3;

    private final CgCameraRigDefinition definition;
    private final Map<String, Integer> indices = new HashMap<>();
    private final String[] names;
    private final int[] types;
    private final double[] numbers;
    private final Vector3d[] vectors;
    private final Quaternionf[] turns;
    private final CgCameraNode[] nodes;

    public CgCameraRig(CgCameraRigDefinition definition) {
        this.definition = Objects.requireNonNull(definition, "definition");
        CgUnits units = definition.units();
        List<CgCameraRigDefinition.Parameter> params = definition.parameters();
        int n = params.size();
        names = new String[n];
        types = new int[n];
        numbers = new double[n];
        vectors = new Vector3d[n];
        turns = new Quaternionf[n];
        for (int i = 0; i < n; i++) {
            CgCameraRigDefinition.Parameter p = params.get(i);
            names[i] = p.name();
            indices.put(p.name(), i);
            Object value = host(units, p.defaultValue(), p.type(), CgQuantity.bySpecName(p.quantity()));
            switch (p.type()) {
                case "rotation" -> {
                    types[i] = ROTATION;
                    turns[i] = (Quaternionf) value;
                }
                case "vec3", "position" -> {
                    types[i] = VEC3;
                    vectors[i] = (Vector3d) value;
                }
                case "bool" -> {
                    types[i] = BOOL;
                    numbers[i] = Boolean.TRUE.equals(value) ? 1 : 0;
                }
                default -> numbers[i] = (Double) value;
            }
        }
        List<CgCameraRigDefinition.Node> list = definition.nodes();
        nodes = new CgCameraNode[list.size()];
        for (int i = 0; i < nodes.length; i++) nodes[i] = CgCameraNodes.build(list.get(i), this);
    }

    public CgCameraRigDefinition definition() {
        return definition;
    }

    public CgCameraRig parameter(String name, double value) {
        set(require(name), value);
        return this;
    }

    public CgCameraRig parameter(String name, double x, double y, double z) {
        set(require(name), x, y, z);
        return this;
    }

    public CgCameraRig parameter(String name, Quaternionfc turn) {
        set(require(name), turn);
        return this;
    }

    /** {@code rig.<name>}'s value now: a {@code Double}, a {@code Boolean}, a {@link Vector3d} or a {@link Quaternionf}. */
    public Object parameter(String name) {
        int i = require(name);
        return switch (types[i]) {
            case ROTATION -> turns[i];
            case VEC3 -> vectors[i];
            case BOOL -> numbers[i] != 0;
            default -> numbers[i];
        };
    }

    /** {@code rig.<name>}'s index, for the setters by index; -1 for a name the rig does not declare. */
    public int indexOf(String name) {
        Integer i = indices.get(name);
        return i == null ? -1 : i;
    }

    /** How many parameters it declares: the indices are {@code 0} to this, less one. */
    public int parameters() {
        return names.length;
    }

    public String parameterName(int index) {
        return names[index];
    }

    /** Whether parameter {@code index} is a vector ({@code vec3}) or a turn ({@code rotation}), not a number. */
    public boolean isVector(int index) {
        return types[index] == VEC3;
    }

    public boolean isRotation(int index) {
        return types[index] == ROTATION;
    }

    /** A float or bool parameter (a bool: non-zero is true). */
    public void set(int index, double value) {
        if (types[index] == VEC3 || types[index] == ROTATION) throw new IllegalArgumentException(where(index) + " is not a number");
        numbers[index] = value;
    }

    public void set(int index, double x, double y, double z) {
        if (types[index] != VEC3) throw new IllegalArgumentException(where(index) + " is not a vector");
        vectors[index].set(x, y, z);
    }

    public void set(int index, Quaternionfc turn) {
        if (types[index] != ROTATION) throw new IllegalArgumentException(where(index) + " is not a rotation");
        turns[index].set(turn);
    }

    private String where(int index) {
        return definition.id() + ": rig." + names[index];
    }

    private int require(String name) {
        int i = indexOf(name);
        if (i < 0) throw new IllegalArgumentException(definition.id() + " has no parameter " + name);
        return i;
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
            int i = indexOf(bind.substring(4));
            if (i < 0) throw new IllegalArgumentException(where + ": binds " + bind + ", which the rig does not declare");
            return new Value(null, i, remap(where, m.get("remap")));
        }
        return new Value(host(definition.units(), written, type, quantity), -1, null);
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
        private final int parameter;
        @Nullable
        private final double[] remap;

        Value(@Nullable Object constant, int parameter, @Nullable double[] remap) {
            this.constant = constant;
            this.parameter = parameter;
            this.remap = remap;
        }

        public double number() {
            double x;
            if (parameter >= 0) x = numbers[parameter];
            else x = constant instanceof Number n ? n.doubleValue() : constant instanceof Boolean b && b ? 1 : 0;
            if (remap == null) return x;
            double span = remap[1] - remap[0];
            return remap[2] + (span == 0 ? 0 : (x - remap[0]) / span) * (remap[3] - remap[2]);
        }

        public boolean bool() {
            if (parameter < 0 && constant instanceof Boolean b) return b;
            return number() != 0;
        }

        public Vector3d vector(Vector3d out) {
            Object v = parameter >= 0 ? vectors[parameter] : constant;
            return v instanceof Vector3d d ? out.set(d) : out.zero();
        }

        public Quaternionf rotation(Quaternionf out) {
            Object v = parameter >= 0 ? turns[parameter] : constant;
            return v instanceof Quaternionf q ? out.set(q) : out.identity();
        }
    }
}
