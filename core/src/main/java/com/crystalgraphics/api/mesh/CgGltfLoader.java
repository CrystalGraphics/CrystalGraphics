package com.crystalgraphics.api.mesh;

import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgMorphTargets;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import de.javagl.jgltf.model.AccessorByteData;
import de.javagl.jgltf.model.AccessorData;
import de.javagl.jgltf.model.AccessorFloatData;
import de.javagl.jgltf.model.AccessorIntData;
import de.javagl.jgltf.model.AccessorModel;
import de.javagl.jgltf.model.AccessorShortData;
import de.javagl.jgltf.model.AnimationModel;
import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.MaterialModel;
import de.javagl.jgltf.model.MeshModel;
import de.javagl.jgltf.model.MeshPrimitiveModel;
import de.javagl.jgltf.model.NodeModel;
import de.javagl.jgltf.model.SkinModel;
import de.javagl.jgltf.model.io.GltfModelReader;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * glTF and GLB for {@link CgMeshLoader}, through {@code de.javagl:jgltf-model}: static meshes a submesh per primitive,
 * skinned meshes with their skeleton, morph targets and clips, and clips alone.
 *
 * <p>Follows Unreal's Interchange glTF importer: a skeletal mesh's skins appended with their joints matched by name
 * ({@code InterchangeGltfMesh.cpp}); clips baked to keys at a frame rate found from their length, each channel sampled
 * by Khronos' interpolation as Unreal evaluates it ({@code InterchangeGltfAnimation.cpp}); unnamed objects and morph
 * targets named as Unreal names them ({@code GLTFAsset.cpp}). Two departures: names are kept as written, since the
 * kits look bones up by them, and two bones of one name fail the load rather than one taking a hash.</p>
 */
final class CgGltfLoader {

    private static final int POINTS = 0, LINES = 1, LINE_LOOP = 2, LINE_STRIP = 3, TRIANGLES = 4, TRIANGLE_STRIP = 5,
            TRIANGLE_FAN = 6;

    /** Unreal's usual frame rates, tried in order; 30 where none fits ({@code FAnimationTimes}). */
    private static final double[] FRAME_RATES = {30, 24, 24000 / 1001.0, 25, 30000 / 1001.0, 48, 50, 60, 60000 / 1001.0,
            72, 90, 100, 120, 144, 165, 240, 480};
    private static final double DEFAULT_FRAME_RATE = 30;
    /** Unreal's {@code MINIMUM_ANIMATION_LENGTH}: a frame at its default sample rate. */
    private static final double MINIMUM_LENGTH = 1 / 30.0;

    private CgGltfLoader() {
    }

    /** {@code format} null: {@link CgVertexFormat#SPATIAL}. */
    static CgMeshLoader.Model read(InputStream in, CgVertexFormat format, boolean shared) throws IOException {
        GltfModel model = new GltfModelReader().readWithoutReferences(in);
        List<MeshPrimitiveModel> primitives = new ArrayList<>();
        for (MeshModel mesh : model.getMeshModels()) primitives.addAll(mesh.getMeshPrimitiveModels());
        if (primitives.isEmpty()) throw new IOException("no mesh primitives");

        CgMeshTopology topology = topologyOf(primitives);
        List<String> materials = new ArrayList<>();
        for (MeshPrimitiveModel p : primitives) materials.add(materialName(p));
        CgMesh mesh = CgMeshLoader.mesh(format != null ? format : CgVertexFormat.SPATIAL, shared, m -> {
            m.topology(topology);
            for (int i = 0; i < primitives.size(); i++) {
                if (i > 0) m.submesh();
                write(m, primitives.get(i), null);
            }
        });
        return new CgMeshLoader.Model(mesh, Collections.unmodifiableList(materials));
    }

    /**
     * Every skinned primitive in node order as a submesh of one {@link CgVertexFormat#skinned(int)} mesh, over a skeleton of
     * the skins' joints and their ancestors; the file's clips and morph targets with it.
     *
     * @param asset the file's name, for what Unreal names unnamed morph targets by
     */
    static CgSkeletalMesh readSkeletal(InputStream in, String asset, boolean shared) throws IOException {
        GltfModel model = new GltfModelReader().readWithoutReferences(in);
        List<NodeModel> nodes = model.getNodeModels();
        List<NodeModel> skinned = new ArrayList<>();
        for (NodeModel node : nodes) if (node.getSkinModel() != null && !node.getMeshModels().isEmpty()) skinned.add(node);
        if (skinned.isEmpty()) throw new IOException("no skinned mesh: no node has both a mesh and a skin");

        Bones bones = bones(model, skinned);
        Map<MeshModel, String[]> targetNames = morphTargetNames(model, asset);

        List<MeshPrimitiveModel> primitives = new ArrayList<>();
        List<int[]> jointBones = new ArrayList<>();
        List<String> materials = new ArrayList<>();
        Map<String, Integer> targets = new LinkedHashMap<>();
        Map<String, Float> defaults = new HashMap<>();
        List<int[]> primitiveTargets = new ArrayList<>();
        for (NodeModel node : skinned) {
            int[] map = jointMap(node.getSkinModel(), bones);
            for (MeshModel mesh : node.getMeshModels()) {
                String[] names = targetNames.get(mesh);
                float[] weights = node.getWeights() != null ? node.getWeights() : mesh.getWeights();
                int[] indexOf = new int[names.length];
                for (int t = 0; t < names.length; t++) {
                    Integer at = targets.putIfAbsent(names[t], targets.size());
                    indexOf[t] = at != null ? at : targets.size() - 1;
                    defaults.putIfAbsent(names[t], weights != null && t < weights.length ? weights[t] : 0f);
                }
                for (MeshPrimitiveModel p : mesh.getMeshPrimitiveModels()) {
                    if (p.getTargets().size() != names.length) {
                        throw new IOException("mesh " + mesh.getName() + ": a primitive has " + p.getTargets().size()
                                + " morph targets, its first " + names.length);
                    }
                    primitives.add(p);
                    jointBones.add(map);
                    materials.add(materialName(p));
                    primitiveTargets.add(indexOf);
                }
            }
        }
        CgMeshTopology topology = topologyOf(primitives);
        if (topology != CgMeshTopology.TRIANGLES) throw new IOException("a skinned mesh of " + topology);

        CgMesh mesh = CgMeshLoader.mesh(CgVertexFormat.skinned(bones.skeleton.boneCount()), shared, m -> {
            for (int i = 0; i < primitives.size(); i++) {
                if (i > 0) m.submesh();
                write(m, primitives.get(i), jointBones.get(i));
            }
        });

        CgSkeletalMesh.Builder built = CgSkeletalMesh.builder(mesh, bones.skeleton);
        Matrix4f inverse = new Matrix4f();
        float[] column = new float[16];
        boolean[] bound = new boolean[bones.skeleton.boneCount()];
        for (NodeModel node : skinned) {
            SkinModel skin = node.getSkinModel();
            for (int j = 0; j < skin.getJoints().size(); j++) {
                int bone = bones.of.get(skin.getJoints().get(j));
                if (bound[bone]) continue;
                bound[bone] = true;
                built.inverseBind(bone, skin.getInverseBindMatrices() == null ? inverse.identity()
                        : inverse.set(skin.getInverseBindMatrix(j, column)));
            }
        }
        for (int i = 0; i < materials.size(); i++) built.material(i, materials.get(i));

        if (!targets.isEmpty()) {
            CgMorphTargets.Builder morphs = CgMorphTargets.builder(mesh.vertexCount());
            for (String name : targets.keySet()) morphs.target(name, defaults.get(name));
            int first = 0;
            for (int i = 0; i < primitives.size(); i++) {
                MeshPrimitiveModel p = primitives.get(i);
                int count = p.getAttributes().get("POSITION").getCount();
                for (int t = 0; t < p.getTargets().size(); t++) {
                    AccessorModel dp = p.getTargets().get(t).get("POSITION"), dn = p.getTargets().get(t).get("NORMAL");
                    for (int v = 0; v < count; v++) {
                        morphs.delta(primitiveTargets.get(i)[t], first + v, value(dp, v, 0, 0f), value(dp, v, 1, 0f),
                                value(dp, v, 2, 0f), value(dn, v, 0, 0f), value(dn, v, 1, 0f), value(dn, v, 2, 0f));
                    }
                }
                first += count;
            }
            built.morphTargets(morphs.build());
        }

        for (CgAnimSequence s : sequences(model, asset, targetNames, bones.of)) built.sequence(s);
        return built.build();
    }

    /** Every clip in the file, its tracks named by the nodes they animate. */
    static List<CgAnimSequence> readSequences(InputStream in, String asset) throws IOException {
        GltfModel model = new GltfModelReader().readWithoutReferences(in);
        return sequences(model, asset, morphTargetNames(model, asset), null);
    }

    // ── Static and skinned primitives ─────────────────────────────────────────────

    private static CgMeshTopology topologyOf(List<MeshPrimitiveModel> primitives) throws IOException {
        if (primitives.isEmpty()) throw new IOException("no mesh primitives");
        CgMeshTopology topology = null;
        for (MeshPrimitiveModel p : primitives) {
            if (!p.getAttributes().containsKey("POSITION")) throw new IOException("a primitive has no POSITION");
            CgMeshTopology t = switch (p.getMode()) {
                case POINTS -> CgMeshTopology.POINTS;
                case LINES, LINE_LOOP, LINE_STRIP -> CgMeshTopology.LINES;
                case TRIANGLES, TRIANGLE_STRIP, TRIANGLE_FAN -> CgMeshTopology.TRIANGLES;
                default -> throw new IllegalArgumentException("glTF primitive mode " + p.getMode());
            };
            if (topology != null && t != topology) {
                throw new IllegalArgumentException("primitives of " + topology + " and " + t + " in one file");
            }
            topology = t;
        }
        return topology;
    }

    @Nullable
    private static String materialName(MeshPrimitiveModel p) {
        MaterialModel material = p.getMaterialModel();
        return material == null ? null : material.getName();
    }

    /**
     * One primitive's vertices and indices. {@code jointBones} maps the primitive's skin joints to bones, null for a
     * static primitive.
     */
    private static void write(CgMeshWriter m, MeshPrimitiveModel p, @Nullable int[] jointBones) {
        Map<String, AccessorModel> attributes = p.getAttributes();
        AccessorModel position = attributes.get("POSITION");
        AccessorModel uv = attributes.get("TEXCOORD_0"), normal = attributes.get("NORMAL"), color = attributes.get("COLOR_0");
        AccessorModel[] sets = {attributes.get("TEXCOORD_1"), attributes.get("TEXCOORD_2"), attributes.get("TEXCOORD_3")};
        AccessorModel joints = attributes.get("JOINTS_0"), weights = attributes.get("WEIGHTS_0");
        if (jointBones != null) {
            if (joints == null || weights == null) throw new IllegalArgumentException("a skinned primitive has no JOINTS_0 or WEIGHTS_0");
            if (attributes.containsKey("JOINTS_1")) {
                throw new IllegalArgumentException("a primitive with JOINTS_1: a skinned vertex follows at most 4 bones");
            }
        }
        float[] more = new float[sets.length * 2];
        int count = position.getCount();
        for (int v = 0; v < count; v++) {
            for (int s = 0; s < sets.length; s++) {
                more[s * 2] = sets[s] == null ? Float.NaN : value(sets[s], v, 0, 0f);
                more[s * 2 + 1] = value(sets[s], v, 1, 0f);
            }
            boolean n = normal != null;
            m.vertex().position(value(position, v, 0, 0f), value(position, v, 1, 0f), value(position, v, 2, 0f))
                    .uv(value(uv, v, 0, 0f), value(uv, v, 1, 0f))
                    .normal(n ? value(normal, v, 0, 0f) : 0f, n ? value(normal, v, 1, 1f) : 1f, n ? value(normal, v, 2, 0f) : 0f)
                    .color(value(color, v, 0, 1f), value(color, v, 1, 1f), value(color, v, 2, 1f), value(color, v, 3, 1f));
            for (int set = 1; set <= more.length / 2 && !Float.isNaN(more[set * 2 - 2]); set++) {
                m.uv(set, more[set * 2 - 2], more[set * 2 - 1]);
            }
            if (jointBones != null) {
                for (int i = 0; i < 4; i++) {
                    float w = value(weights, v, i, 0f);
                    if (w == 0f) continue;
                    int joint = integer(joints, v, i);
                    if (joint >= jointBones.length) {
                        throw new IllegalArgumentException("vertex " + v + " names joint " + joint + " of a skin of " + jointBones.length);
                    }
                    m.bone(jointBones[joint], w);
                }
            }
            m.end();
        }

        int[] indices = indices(p.getIndices(), count);
        int k = indices.length;
        switch (p.getMode()) {
            case TRIANGLE_STRIP -> {
                for (int i = 0; i + 2 < k; i++) {
                    if ((i & 1) == 0) m.triangle(indices[i], indices[i + 1], indices[i + 2]);
                    else m.triangle(indices[i + 1], indices[i], indices[i + 2]);
                }
            }
            case TRIANGLE_FAN -> {
                for (int i = 1; i + 1 < k; i++) m.triangle(indices[0], indices[i], indices[i + 1]);
            }
            case LINE_STRIP, LINE_LOOP -> {
                for (int i = 0; i + 1 < k; i++) m.line(indices[i], indices[i + 1]);
                if (p.getMode() == LINE_LOOP && k > 1) m.line(indices[k - 1], indices[0]);
            }
            default -> {
                for (int index : indices) m.index(index);
            }
        }
    }

    // ── The skeleton ──────────────────────────────────────────────────────────────

    /** A skeleton and which bone each of its nodes became. */
    private record Bones(CgSkeleton skeleton, Map<NodeModel, Integer> of) {
    }

    /**
     * The skins' joints and every ancestor of one, parents first: the scene's roots in node order, each node's children
     * in the order it lists them. One root, as Unreal's reference skeleton has.
     */
    private static Bones bones(GltfModel model, List<NodeModel> skinned) throws IOException {
        Map<NodeModel, Boolean> wanted = new IdentityHashMap<>();
        for (NodeModel node : skinned) {
            for (NodeModel joint : node.getSkinModel().getJoints()) {
                for (NodeModel n = joint; n != null && wanted.put(n, Boolean.TRUE) == null; n = n.getParent()) {
                    // every ancestor, once
                }
            }
        }
        List<NodeModel> order = new ArrayList<>();
        for (NodeModel node : model.getNodeModels()) if (node.getParent() == null) visit(node, wanted, order);
        Map<NodeModel, Integer> of = new IdentityHashMap<>();
        CgSkeleton.Builder skeleton = CgSkeleton.builder();
        Vector3f t = new Vector3f(), s = new Vector3f();
        Quaternionf r = new Quaternionf();
        for (NodeModel node : order) {
            Integer parent = node.getParent() == null ? null : of.get(node.getParent());
            if (parent == null && !of.isEmpty()) {
                throw new IOException("two root bones, " + order.get(0).getName() + " and " + node.getName()
                        + ": a skeleton has one");
            }
            rest(node, t, r, s);
            of.put(node, skeleton.next());
            skeleton.bone(nodeName(model, node), parent == null ? -1 : parent, t.x, t.y, t.z, r.x, r.y, r.z, r.w, s.x, s.y, s.z);
        }
        return new Bones(skeleton.build(), of);
    }

    private static void visit(NodeModel node, Map<NodeModel, Boolean> wanted, List<NodeModel> order) {
        if (!wanted.containsKey(node)) return;
        order.add(node);
        for (NodeModel child : node.getChildren()) visit(child, wanted, order);
    }

    /** A skin's joints as bones. */
    private static int[] jointMap(SkinModel skin, Bones bones) {
        int[] map = new int[skin.getJoints().size()];
        for (int j = 0; j < map.length; j++) map[j] = bones.of.get(skin.getJoints().get(j));
        return map;
    }

    /** A node's local transform: its translation, rotation and scale as written, or its matrix decomposed. */
    private static void rest(NodeModel node, Vector3f t, Quaternionf r, Vector3f s) {
        float[] matrix = node.getMatrix();
        if (matrix != null) {
            Matrix4f m = new Matrix4f().set(matrix);
            m.getTranslation(t);
            m.getScale(s);
            m.getNormalizedRotation(r);
            return;
        }
        float[] tr = node.getTranslation(), ro = node.getRotation(), sc = node.getScale();
        t.set(tr == null ? 0 : tr[0], tr == null ? 0 : tr[1], tr == null ? 0 : tr[2]);
        r.set(ro == null ? 0 : ro[0], ro == null ? 0 : ro[1], ro == null ? 0 : ro[2], ro == null ? 1 : ro[3]);
        s.set(sc == null ? 1 : sc[0], sc == null ? 1 : sc[1], sc == null ? 1 : sc[2]);
    }

    /** The node's name, or Unreal's for an unnamed one: {@code node_<index>}. */
    private static String nodeName(GltfModel model, NodeModel node) {
        String name = node.getName();
        return name != null && !name.isEmpty() ? name : "node_" + model.getNodeModels().indexOf(node);
    }

    // ── Morph targets ─────────────────────────────────────────────────────────────

    /**
     * Each mesh's targets by name, as Unreal names them ({@code FAsset::GenerateNames}): the mesh's
     * {@code extras.targetNames} where they are unique within it and across every mesh, else
     * {@code <asset>_mesh_<mesh>_<target>_MorphTarget} for every mesh.
     */
    private static Map<MeshModel, String[]> morphTargetNames(GltfModel model, String asset) {
        List<MeshModel> meshes = model.getMeshModels();
        Map<MeshModel, String[]> names = new IdentityHashMap<>();
        Set<String> across = new HashSet<>();
        int total = 0;
        for (MeshModel mesh : meshes) {
            String[] given = givenTargetNames(mesh);
            int count = mesh.getMeshPrimitiveModels().isEmpty() ? 0 : mesh.getMeshPrimitiveModels().get(0).getTargets().size();
            if (given != null && (given.length != count || new HashSet<>(List.of(given)).size() != given.length)) {
                given = null;
            }
            if (given != null) {
                Collections.addAll(across, given);
                total += given.length;
            }
            names.put(mesh, given);
        }
        boolean rebuild = across.size() != total;
        for (int i = 0; i < meshes.size(); i++) {
            MeshModel mesh = meshes.get(i);
            int count = mesh.getMeshPrimitiveModels().isEmpty() ? 0 : mesh.getMeshPrimitiveModels().get(0).getTargets().size();
            if (!rebuild && names.get(mesh) != null) continue;
            String[] generated = new String[count];
            for (int t = 0; t < count; t++) generated[t] = asset + "_mesh_" + i + "_" + t + "_MorphTarget";
            names.put(mesh, generated);
        }
        return names;
    }

    @Nullable
    private static String[] givenTargetNames(MeshModel mesh) {
        if (!(mesh.getExtras() instanceof Map<?, ?> extras) || !(extras.get("targetNames") instanceof List<?> list)) return null;
        String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) {
            if (!(list.get(i) instanceof String name)) return null;
            names[i] = name;
        }
        return names;
    }

    // ── Clips ─────────────────────────────────────────────────────────────────────

    /**
     * Every animation baked as Unreal bakes one ({@code GetBakedAnimationTransformPayloadData}): its length the latest
     * key of any channel, its frame rate the first usual one that length lands on, {@code round(length × rate) + 1} keys
     * sampled from time 0, each animated node's unkeyed channels at its rest.
     *
     * @param bones the skeleton's nodes, or null for clips alone: then any node's channels make a track
     */
    private static List<CgAnimSequence> sequences(GltfModel model, String asset, Map<MeshModel, String[]> targetNames,
                                                  @Nullable Map<NodeModel, Integer> bones) throws IOException {
        List<CgAnimSequence> out = new ArrayList<>();
        List<AnimationModel> animations = model.getAnimationModels();
        for (int a = 0; a < animations.size(); a++) {
            AnimationModel animation = animations.get(a);
            String name = animation.getName() != null && !animation.getName().isEmpty() ? animation.getName()
                    : asset + "_animation_" + a;
            double stop = 0;
            for (AnimationModel.Channel channel : animation.getChannels()) {
                AccessorModel input = channel.getSampler().getInput();
                if (input.getCount() > 0) stop = Math.max(stop, value(input, input.getCount() - 1, 0, 0f));
            }
            double[] timing = frameRate(stop);
            double rate = timing[0];
            int frames = (int) Math.floor(Math.max(timing[1], MINIMUM_LENGTH) * rate + 0.5);
            int keys = frames + 1;

            Map<NodeModel, float[][]> tracks = new LinkedHashMap<>();
            Map<String, float[]> curves = new LinkedHashMap<>();
            for (AnimationModel.Channel channel : animation.getChannels()) {
                NodeModel node = channel.getNodeModel();
                String path = channel.getPath();
                if ("weights".equals(path)) {
                    weights(name, channel, node, targetNames, keys, rate, curves);
                    continue;
                }
                if (bones != null && !bones.containsKey(node)) {
                    throw new IOException(name + " animates node " + nodeName(model, node) + ", which is not a bone");
                }
                float[][] track = tracks.computeIfAbsent(node, n -> restTrack(n, keys));
                switch (path) {
                    case "translation" -> bake(name, channel, keys, rate, 3, false, track[0]);
                    case "rotation" -> bake(name, channel, keys, rate, 4, true, track[1]);
                    case "scale" -> bake(name, channel, keys, rate, 3, false, track[2]);
                    default -> throw new IOException(name + " animates " + path + ", which glTF does not define");
                }
            }
            CgAnimSequence.Builder sequence = CgAnimSequence.builder(name, rate, keys);
            Map<String, NodeModel> named = new HashMap<>();
            for (Map.Entry<NodeModel, float[][]> track : tracks.entrySet()) {
                String bone = nodeName(model, track.getKey());
                NodeModel had = named.put(bone, track.getKey());
                if (had != null) throw new IOException(name + " animates two nodes named " + bone);
                sequence.track(bone, track.getValue()[0], track.getValue()[1], track.getValue()[2]);
            }
            for (Map.Entry<String, float[]> curve : curves.entrySet()) sequence.curve(curve.getKey(), curve.getValue());
            out.add(sequence.build());
        }
        return out;
    }

    /**
     * {@code {rate, length}}: Unreal's {@code ValidateAnimationStopAndFrameRate}. The first usual rate whose nearest
     * frame lies within 1 ms of the length, or one frame of it; the length then snapped to that frame where it fell
     * between frames; else 30. At least one frame long.
     */
    static double[] frameRate(double stop) {
        for (double rate : FRAME_RATES) {
            double frames = stop * rate, rounded = Math.floor(frames + 0.5);
            double delta = Math.abs(rounded / rate - stop);
            if (delta <= 1e-3 || Math.abs(delta - 1 / rate) <= 1e-3) {
                double sub = frames - Math.floor(frames);
                if (sub > 1e-4 && sub < 1 - 1e-4) stop = rounded / rate;
                return new double[]{rate, Math.max(stop, 1 / rate)};
            }
        }
        return new double[]{DEFAULT_FRAME_RATE, Math.max(stop, 1 / DEFAULT_FRAME_RATE)};
    }

    /** A track at the node's rest for every key: translation, rotation, scale. */
    private static float[][] restTrack(NodeModel node, int keys) {
        Vector3f t = new Vector3f(), s = new Vector3f();
        Quaternionf r = new Quaternionf();
        rest(node, t, r, s);
        float[][] track = {new float[keys * 3], new float[keys * 4], new float[keys * 3]};
        float[] rest = {t.x, t.y, t.z, r.x, r.y, r.z, r.w, s.x, s.y, s.z};
        for (int k = 0; k < keys; k++) {
            System.arraycopy(rest, 0, track[0], k * 3, 3);
            System.arraycopy(rest, 3, track[1], k * 4, 4);
            System.arraycopy(rest, 7, track[2], k * 3, 3);
        }
        return track;
    }

    /** A channel's values at each key's time into {@code out}, {@code width} floats a key. */
    private static void bake(String name, AnimationModel.Channel channel, int keys, double rate, int width, boolean rotation,
                             float[] out) throws IOException {
        AnimationModel.Sampler sampler = channel.getSampler();
        float[] times = times(name, sampler);
        float[] values = outputs(name, sampler, times.length, width);
        float[] value = new float[width];
        for (int k = 0; k < keys; k++) {
            sample(times, values, width, k / rate, sampler.getInterpolation(), rotation, value);
            if (rotation) normalize(value);
            System.arraycopy(value, 0, out, k * width, width);
        }
    }

    /** A {@code weights} channel as one curve per morph target of the node's mesh. */
    private static void weights(String name, AnimationModel.Channel channel, NodeModel node,
                                Map<MeshModel, String[]> targetNames, int keys, double rate, Map<String, float[]> curves)
            throws IOException {
        if (node.getMeshModels().isEmpty()) throw new IOException(name + " keys the weights of a node with no mesh");
        String[] targets = targetNames.get(node.getMeshModels().get(0));
        AnimationModel.Sampler sampler = channel.getSampler();
        float[] times = times(name, sampler);
        float[] values = outputs(name, sampler, times.length, targets.length);
        float[] value = new float[targets.length];
        float[][] keyed = new float[targets.length][keys];
        for (int k = 0; k < keys; k++) {
            sample(times, values, targets.length, k / rate, sampler.getInterpolation(), false, value);
            for (int t = 0; t < targets.length; t++) keyed[t][k] = value[t];
        }
        for (int t = 0; t < targets.length; t++) {
            if (curves.put(targets[t], keyed[t]) != null) throw new IOException(name + " keys morph target " + targets[t] + " twice");
        }
    }

    private static float[] times(String name, AnimationModel.Sampler sampler) throws IOException {
        AccessorModel input = sampler.getInput();
        float[] times = new float[input.getCount()];
        for (int i = 0; i < times.length; i++) {
            times[i] = value(input, i, 0, 0f);
            if (i > 0 && times[i] < times[i - 1]) throw new IOException(name + ": a sampler's times go backwards at key " + i);
        }
        return times;
    }

    /** The sampler's outputs, {@code width} floats a key, three times as many for a cubic spline (in, value, out). */
    private static float[] outputs(String name, AnimationModel.Sampler sampler, int keys, int width) throws IOException {
        AccessorModel output = sampler.getOutput();
        int perKey = sampler.getInterpolation() == AnimationModel.Interpolation.CUBICSPLINE ? 3 : 1;
        int components = output.getAccessorData().getNumComponentsPerElement();
        int total = output.getCount() * components;
        if (total != keys * perKey * width) {
            throw new IOException(name + ": a sampler of " + keys + " keys has " + total + " output values, not "
                    + keys * perKey * width);
        }
        float[] values = new float[total];
        for (int e = 0, i = 0; e < output.getCount(); e++) {
            for (int c = 0; c < components; c++) values[i++] = value(output, e, c, 0f);
        }
        return values;
    }

    /**
     * The sampled value at {@code time}, as Unreal's {@code InterpolateValue} evaluates Khronos' interpolations between
     * the neighbouring keys, clamped to the first and last outside them ({@code GetNeighbourIndices}). Unreal's step
     * changes value half way between keys.
     */
    private static void sample(float[] times, float[] values, int width, double time,
                               AnimationModel.Interpolation interpolation, boolean rotation, float[] out) {
        int n = times.length;
        int lower = 0, higher = 0;
        if (n > 1) {
            if (time >= times[n - 1]) {
                lower = higher = n - 1;
            } else if (time >= times[0]) {
                for (int i = 0; i < n - 1; i++) {
                    if (times[i] <= time && time < times[i + 1]) {
                        lower = i;
                        higher = i + 1;
                        break;
                    }
                }
            }
        }
        boolean cubic = interpolation == AnimationModel.Interpolation.CUBICSPLINE;
        if (lower == higher) {
            System.arraycopy(values, (cubic ? lower * 3 + 1 : lower) * width, out, 0, width);
            return;
        }
        double td = times[higher] - times[lower], t = (time - times[lower]) / td;
        switch (interpolation) {
            case STEP -> System.arraycopy(values, (t < 0.5 ? lower : higher) * width, out, 0, width);
            case CUBICSPLINE -> {
                double t2 = t * t, t3 = t2 * t;
                int vk = (lower * 3 + 1) * width, bk = (lower * 3 + 2) * width, vk1 = (higher * 3 + 1) * width,
                        ak1 = higher * 3 * width;
                for (int c = 0; c < width; c++) {
                    out[c] = (float) ((2 * t3 - 3 * t2 + 1) * values[vk + c] + td * (t3 - 2 * t2 + t) * values[bk + c]
                            + (-2 * t3 + 3 * t2) * values[vk1 + c] + td * (t3 - t2) * values[ak1 + c]);
                }
            }
            default -> {
                int a = lower * width, b = higher * width;
                if (!rotation) {
                    for (int c = 0; c < width; c++) out[c] = (float) ((1 - t) * values[a + c] + t * values[b + c]);
                    return;
                }
                double dot = 0;
                for (int c = 0; c < 4; c++) dot += values[a + c] * values[b + c];
                dot = Math.max(-1, Math.min(1, dot));
                double angle = Math.acos(Math.abs(dot));
                if (Math.abs(angle) <= 1e-8) {
                    for (int c = 0; c < 4; c++) out[c] = (float) ((1 - t) * values[a + c] + t * values[b + c]);
                    return;
                }
                double s = dot / Math.abs(dot), sin = Math.sin(angle);
                double wa = Math.sin(angle * (1 - t)) / sin, wb = s * Math.sin(angle * t) / sin;
                for (int c = 0; c < 4; c++) out[c] = (float) (wa * values[a + c] + wb * values[b + c]);
            }
        }
    }

    private static void normalize(float[] q) {
        float length = (float) Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        if (length > 0f) for (int c = 0; c < 4; c++) q[c] /= length;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────────

    /** The primitive's indices, or 0 to {@code count} - 1 for one drawn in vertex order. */
    private static int[] indices(@Nullable AccessorModel accessor, int count) {
        int[] out = new int[accessor == null ? count : accessor.getCount()];
        if (accessor == null) {
            for (int i = 0; i < count; i++) out[i] = i;
            return out;
        }
        for (int i = 0; i < out.length; i++) out[i] = integer(accessor, i, 0);
        return out;
    }

    /** Component {@code c} of element {@code v} as an unsigned integer. */
    private static int integer(AccessorModel accessor, int v, int c) {
        AccessorData data = accessor.getAccessorData();
        if (data instanceof AccessorByteData b) return b.getInt(v, c);
        if (data instanceof AccessorShortData s) return s.getInt(v, c);
        if (data instanceof AccessorIntData d) return d.get(v, c);
        throw new IllegalArgumentException("integer data of " + data.getComponentType());
    }

    /** Component {@code c} of element {@code v} as a float, a normalised integer scaled; {@code absent} past its end. */
    private static float value(@Nullable AccessorModel accessor, int v, int c, float absent) {
        if (accessor == null) return absent;
        AccessorData data = accessor.getAccessorData();
        if (c >= data.getNumComponentsPerElement()) return absent;
        if (data instanceof AccessorFloatData f) return f.get(v, c);
        if (data instanceof AccessorByteData b) return b.isUnsigned() ? b.getInt(v, c) / 255f : Math.max(b.get(v, c) / 127f, -1f);
        if (data instanceof AccessorShortData s) {
            return s.isUnsigned() ? s.getInt(v, c) / 65535f : Math.max(s.get(v, c) / 32767f, -1f);
        }
        throw new IllegalArgumentException("attribute data of " + data.getComponentType());
    }
}
