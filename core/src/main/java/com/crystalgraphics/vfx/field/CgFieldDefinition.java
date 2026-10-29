package com.crystalgraphics.vfx.field;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.object.value.CgQuantity;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.util.io.CgIO;
import com.crystalgraphics.vfx.field.CgFieldNode.Kind;
import com.crystalgraphics.vfx.field.CgFieldNode.Output;
import com.crystalgraphics.vfx.particle.CgVfxField;

/**
 * A {@code field} asset compiled: a {@link Grid} of vectors (Unreal's {@code UVectorFieldStatic}) or a {@link Graph}
 * of force nodes (Chaos's Physics Fields). Shared by every placement; a placement is {@link CgFieldPlacement}.
 *
 * <pre>{@code
 * CgFieldDefinition field = (CgFieldDefinition) deps.definition(CgFieldKinds.FIELD, "mymod:rasengan_pull");
 * if (field instanceof CgFieldDefinition.Graph graph) graph.bounds();     // where it acts, about its placement
 * }</pre>
 *
 * <p>A graph built in Java, exactly what its file loads:</p>
 * <pre>{@code
 * CgFieldDefinition.Graph pull = CgFieldDefinition.graph(CgFieldDefinition.Target.LINEAR_FORCE)
 *         .node("v", "radial_vector", "magnitude", -5)
 *         .node("r", "radial_falloff", "radius", 3, "default", 0)
 *         .node("out", "operator", "op", "multiply", "left", "v", "right", "r")
 *         .build();                                                          // the root is the last node
 * Map<String, Object> tree = pull.write();                                  // fields/rasengan_pull.yaml
 * }</pre>
 *
 * <ul>
 *   <li>Every value is in the host's units: lengths in blocks, and the root's value in its target's unit, folded into
 *       its magnitude as it loads.</li>
 *   <li>A graph of more than 16 nodes, a node reading one after it, an unknown kind or target, and a root whose value
 *       its target cannot take fail at load, naming the path.</li>
 * </ul>
 */
public sealed interface CgFieldDefinition permits CgFieldDefinition.Grid, CgFieldDefinition.Graph {

    /** The most nodes a graph holds: the evaluator's registers. */
    int MAX_NODES = 16;

    /** The field kind's compiler: a grid or a graph by its {@code kind}. */
    CgAssetCompiler<CgFieldDefinition> COMPILER = (asset, deps) -> compile(asset, deps.units());

    /** Where it can act about its placement. */
    CgFieldBounds bounds();

    private static CgFieldDefinition compile(CgAssetData asset, CgUnits units) {
        Map<Object, Object> tree = asset.resolved();
        return "grid".equals(tree.get("kind")) ? Grid.compile(asset.id(), tree, units) : Graph.read(asset.id(), tree, units);
    }

    /** A graph of {@code target}, built node by node. */
    static GraphBuilder graph(Target target) {
        return new GraphBuilder(target);
    }

    /**
     * What a graph's root does to a particle: Chaos's targets and the format's own. A mod registers more.
     *
     * <pre>{@code
     * CgFieldDefinition.Target.register(new CgFieldDefinition.Target("mymod:heat", CgFieldNode.Output.SCALAR, CgQuantity.RATE));
     * }</pre>
     *
     * @param name     its name in a file
     * @param output   the value it takes
     * @param quantity what its value measures, which the root converts by
     */
    record Target(String name, Output output, CgQuantity quantity) {

        private static final Map<String, Target> TARGETS = new ConcurrentHashMap<>();

        public static final Target LINEAR_FORCE = register(new Target("linear_force", Output.VECTOR, CgQuantity.ACCELERATION));
        public static final Target LINEAR_VELOCITY = register(new Target("linear_velocity", Output.VECTOR, CgQuantity.SPEED));
        public static final Target KILL = register(new Target("kill", Output.SCALAR, CgQuantity.NONE));
        public static final Target ANGULAR_VELOCITY = register(new Target("angular_velocity", Output.VECTOR, CgQuantity.ANGULAR_SPEED));
        /** Turned about the value's direction through the field's origin, its length an angular speed. */
        public static final Target ORBIT = register(new Target("orbit", Output.VECTOR, CgQuantity.ANGULAR_SPEED));
        public static final Target SPEED_SCALE = register(new Target("speed_scale", Output.SCALAR, CgQuantity.NONE));
        /** Added to its scale per axis, a rate. */
        public static final Target SCALE = register(new Target("scale", Output.VECTOR, CgQuantity.RATE));
        /** Applied to the stage, not to particles. */
        public static final Target STRAIN = register(new Target("strain", Output.SCALAR, CgQuantity.NONE));

        /** Registers {@code target} under its name. At init. */
        public static Target register(Target target) {
            if (TARGETS.putIfAbsent(target.name, target) != null) throw new IllegalArgumentException("a field target " + target.name + " is registered already");
            return target;
        }

        /** The target registered as {@code name}, or null. */
        public static @Nullable Target of(String name) {
            return TARGETS.get(name);
        }
    }

    /**
     * A grid field: Unreal's {@code VectorFieldStatic}, its vectors in the host's units and axes over its box, which a
     * {@code vector_field} module places and scales. Its data is Unreal's FGA text: the grid's size, its box's two
     * corners, then a vector a texel, x fastest.
     *
     * <pre>{@code
     * // fields/swirl.yaml: kind: grid, size: [16, 16, 16], bounds: [[-50, -50, 0], [50, 50, 100]], data: { path: mymod:fields/swirl.fga }
     * CgFieldDefinition.Grid grid = (CgFieldDefinition.Grid) deps.definition(CgFieldKinds.FIELD, "mymod:swirl");
     * new CgVfxModule.VectorField(grid.field(), CgBox.of(grid.half()[0], grid.half()[1], grid.half()[2]), grid.intensity(), 1f);
     * }</pre>
     *
     * <ul>
     *   <li>Its axes move through the asset's units as its vectors do: a field of Unreal's is turned onto the host's y up.</li>
     * </ul>
     *
     * @param field     its vectors, not tiling: a module tiles it
     * @param centre    its box's centre, in blocks
     * @param half      its box's half extents
     * @param intensity what its vectors are scaled by ({@code Intensity})
     */
    record Grid(CgVfxField field, float[] centre, float[] half, float intensity) implements CgFieldDefinition {

        @Override
        public CgFieldBounds bounds() {
            return new CgFieldBounds(centre[0] - half[0], centre[1] - half[1], centre[2] - half[2],
                    centre[0] + half[0], centre[1] + half[1], centre[2] + half[2]);
        }

        static Grid compile(String id, Map<Object, Object> tree, CgUnits units) {
            if (!(tree.get("data") instanceof Map<?, ?> data) || !(data.get("path") instanceof String path)) {
                throw new IllegalArgumentException(id + ": a grid field names its data, { path }");
            }
            String text = CgIO.loadSource(path);
            if (text == null) throw new IllegalArgumentException(id + ": its data " + path + " is missing");
            float[] numbers = numbers(id, text);
            int[] size = {(int) numbers[0], (int) numbers[1], (int) numbers[2]};
            if (numbers.length != 9 + 3 * size[0] * size[1] * size[2]) {
                throw new IllegalArgumentException(id + ": " + path + " holds " + (numbers.length - 9) + " numbers for a "
                        + size[0] + "x" + size[1] + "x" + size[2] + " grid");
            }
            List<?> bounds = tree.get("bounds") instanceof List<?> b ? b
                    : List.of(List.of(numbers[3], numbers[4], numbers[5]), List.of(numbers[6], numbers[7], numbers[8]));
            float[] lo = host(units, bounds.get(0)), hi = host(units, bounds.get(1));
            float[] centre = new float[3], half = new float[3];
            for (int a = 0; a < 3; a++) {
                centre[a] = (lo[a] + hi[a]) / 2f;
                half[a] = Math.abs(hi[a] - lo[a]) / 2f;
            }
            int[] axes = units.axes();
            int[] hostSize = new int[3];
            for (int a = 0; a < 3; a++) hostSize[a] = size[Math.abs(axes[a]) - 1];
            int[] at = new int[3];
            CgVfxField field = CgVfxField.of(hostSize[0], hostSize[1], hostSize[2], false, (x, y, z, out) -> {
                int[] h = {x, y, z};
                for (int a = 0; a < 3; a++) {
                    int from = Math.abs(axes[a]) - 1;
                    at[from] = axes[a] > 0 ? h[a] : size[from] - 1 - h[a];
                }
                int i = 9 + 3 * ((at[2] * size[1] + at[1]) * size[0] + at[0]);
                List<?> v = (List<?>) units.toHost(List.of(numbers[i], numbers[i + 1], numbers[i + 2]), CgQuantity.SPEED);
                for (int c = 0; c < 3; c++) out[c] = ((Number) v.get(c)).floatValue();
            });
            float intensity = tree.get("intensity") instanceof Number n ? n.floatValue() : 1f;
            return new Grid(field, centre, half, intensity);
        }

        private static float[] host(CgUnits units, Object corner) {
            List<?> v = (List<?>) units.toHost(corner, CgQuantity.POSITION);
            return new float[]{((Number) v.get(0)).floatValue(), ((Number) v.get(1)).floatValue(), ((Number) v.get(2)).floatValue()};
        }

        /** FGA's comma-separated numbers. */
        private static float[] numbers(String id, String text) {
            List<Float> out = new ArrayList<>();
            for (String s : text.split("[,\\s]+")) {
                if (s.isEmpty()) continue;
                try {
                    out.add(Float.parseFloat(s));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(id + ": its data holds '" + s + "', not a number");
                }
            }
            float[] a = new float[out.size()];
            for (int i = 0; i < a.length; i++) a[i] = out.get(i);
            if (a.length < 9) throw new IllegalArgumentException(id + ": its data has no FGA header");
            return a;
        }
    }

    /**
     * A graph field compiled: the nodes its root reads, in an order where each reads only those before it, the root
     * last, as Unreal's physics field manager flattens a command ({@code FillSetupDatas}).
     *
     * @param target       what its root's value does
     * @param nodes        its nodes, the root last; in host units, the root's value in its target's
     * @param magnitudeVar each particle reads its value times {@code 1 + rand * magnitudeVar}
     * @param bounds       where it acts about its placement: {@link CgFieldBounds#INFINITE} where it acts everywhere
     */
    record Graph(Target target, List<CgFieldNode> nodes, float magnitudeVar, CgFieldBounds bounds) implements CgFieldDefinition {

        public Graph {
            nodes = List.copyOf(nodes);
        }

        /** Its root. */
        public CgFieldNode root() {
            return nodes.get(nodes.size() - 1);
        }

        /** The graph {@code tree} holds (a field asset's, or one written inline), read in {@code units}, failing by {@code path}. */
        public static Graph read(String path, Map<?, ?> tree, CgUnits units) {
            Target target = tree.get("target") instanceof String t ? Target.of(t) : null;
            if (target == null) throw new IllegalArgumentException(path + ".target: " + tree.get("target") + " is no field target");
            if (!(tree.get("nodes") instanceof List<?> written) || written.isEmpty()) throw new IllegalArgumentException(path + ".nodes: a graph has nodes");
            if (written.size() > MAX_NODES) {
                throw new IllegalArgumentException(path + ".nodes: " + written.size() + " nodes, past the " + MAX_NODES + " a graph holds");
            }
            int n = written.size();
            Map<String, Integer> index = new HashMap<>();
            Kind[] kinds = new Kind[n];
            int[][] inputs = new int[n][];
            String[] ids = new String[n];
            for (int i = 0; i < n; i++) {
                Map<?, ?> node = (Map<?, ?>) written.get(i);
                ids[i] = String.valueOf(node.get("id"));
                String at = path + ".nodes." + ids[i];
                kinds[i] = Kind.of(String.valueOf(node.get("kind")));
                if (kinds[i] == null) throw new IllegalArgumentException(at + ": no field node kind " + node.get("kind"));
                inputs[i] = new int[kinds[i].inputNames().size()];
                for (int k = 0; k < inputs[i].length; k++) {
                    String name = kinds[i].inputNames().get(k);
                    Object read = node.get(name);
                    Integer from = read == null ? Integer.valueOf(-1) : index.get(String.valueOf(read));
                    if (from == null) throw new IllegalArgumentException(at + "." + name + ": " + read + " is not a node before it");
                    inputs[i][k] = from;
                }
                if (index.put(ids[i], i) != null) throw new IllegalArgumentException(at + ": a second node " + ids[i]);
            }
            Integer root = index.get(String.valueOf(tree.get("root")));
            if (root == null) throw new IllegalArgumentException(path + ".root: " + tree.get("root") + " is no node of it");
            // The root's subtree alone, in file order: each still reads only nodes before it.
            boolean[] reached = new boolean[n];
            int[] readers = new int[n];
            reach(root, inputs, reached);
            int[] place = new int[n];
            int kept = 0;
            for (int i = 0; i < n; i++) place[i] = reached[i] ? kept++ : -1;
            for (int i = 0; i < n; i++) {
                if (!reached[i]) continue;
                for (int from : inputs[i]) if (from >= 0) readers[from]++;
            }
            float factor = ((Number) units.toHost(1.0, target.quantity())).floatValue();
            List<CgFieldNode> nodes = new ArrayList<>(kept);
            Output[] outputs = new Output[kept];
            CgFieldBounds[] bounds = new CgFieldBounds[kept];
            int scaled = factor == 1f ? -1 : root;
            for (int i = 0; i < n; i++) {
                if (!reached[i]) continue;
                int[] in = new int[inputs[i].length];
                for (int k = 0; k < in.length; k++) in[k] = inputs[i][k] < 0 ? -1 : place[inputs[i][k]];
                CgFieldNode node = CgFieldNode.read(ids[i], kinds[i], (Map<?, ?>) written.get(i), units, in, path + ".nodes." + ids[i]);
                nodes.add(node);
            }
            // The target's unit folds into the root: its value is linear in its scaled numbers.
            while (scaled >= 0) {
                CgFieldNode node = nodes.get(place[scaled]);
                for (String p : node.kind().scales()) node.params()[node.kind().offset(p)] *= factor;
                if (node.kind().through() == null) break;
                int next = inputs[scaled][node.kind().inputNames().indexOf(node.kind().through())];
                if (next >= 0 && readers[next] > 1) {
                    throw new IllegalArgumentException(path + ".nodes." + ids[next] + ": read twice under the root, so its unit cannot fold yet");
                }
                scaled = next;
            }
            for (int i = 0; i < kept; i++) {
                CgFieldNode node = nodes.get(i);
                Output[] o = new Output[node.inputs().length];
                CgFieldBounds[] b = new CgFieldBounds[node.inputs().length];
                for (int k = 0; k < o.length; k++) {
                    int from = node.inputs()[k];
                    o[k] = from < 0 ? null : outputs[from];
                    b[k] = from < 0 ? CgFieldBounds.INFINITE : bounds[from];
                }
                outputs[i] = node.kind().output().output(node, o);
                bounds[i] = node.kind().bounds().bounds(node, b);
            }
            if (outputs[kept - 1] != target.output()) {
                throw new IllegalArgumentException(path + ".root: " + ids[root] + " gives a " + outputs[kept - 1].name().toLowerCase()
                        + ", which " + target.name() + " cannot take");
            }
            float var = tree.get("magnitude_var") instanceof Number v ? v.floatValue() : 0f;
            return new Graph(target, nodes, var, bounds[kept - 1]);
        }

        private static void reach(int i, int[][] inputs, boolean[] reached) {
            if (reached[i]) return;
            reached[i] = true;
            for (int from : inputs[i]) if (from >= 0) reach(from, inputs, reached);
        }

        /** Its tree, inline or as a field asset's, in host units: what {@link #read} reads back to this graph. */
        public Map<String, Object> write() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("kind", "graph");
            out.put("target", target.name());
            out.put("root", root().id());
            if (magnitudeVar != 0f) out.put("magnitude_var", (double) magnitudeVar);
            List<Object> written = new ArrayList<>(nodes.size());
            for (CgFieldNode node : nodes) written.add(node.write(nodes));
            out.put("nodes", written);
            return out;
        }
    }

    /** Builds a {@link Graph} as its file writes it, in host units. */
    final class GraphBuilder {
        private final Map<String, Object> tree = new LinkedHashMap<>();
        private final List<Object> nodes = new ArrayList<>();

        private GraphBuilder(Target target) {
            tree.put("kind", "graph");
            tree.put("target", target.name());
        }

        /** A node of {@code kind}, its fields as name and value in turn; a field left out takes its default. */
        public GraphBuilder node(String id, String kind, Object... fields) {
            if (fields.length % 2 != 0) throw new IllegalArgumentException(id + ": fields come as name and value");
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", id);
            node.put("kind", kind);
            Kind k = Kind.of(kind);
            if (k == null) throw new IllegalArgumentException(id + ": no field node kind " + kind);
            for (int i = 0; i < fields.length; i += 2) {
                String name = String.valueOf(fields[i]);
                if (k.type().property(name) == null) throw new IllegalArgumentException(id + ": " + kind + " has no " + name);
                node.put(name, fields[i + 1]);
            }
            nodes.add(node);
            return this;
        }

        /** Each particle reads its value times {@code 1 + rand * var}. */
        public GraphBuilder magnitudeVar(float var) {
            tree.put("magnitude_var", (double) var);
            return this;
        }

        /** The graph, its root the last node. */
        public Graph build() {
            tree.put("root", ((Map<?, ?>) nodes.get(nodes.size() - 1)).get("id"));
            tree.put("nodes", nodes);
            return Graph.read("graph", tree, CgUnits.HOST);
        }
    }
}
