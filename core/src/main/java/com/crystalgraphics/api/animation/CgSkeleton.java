package com.crystalgraphics.api.animation;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A bone hierarchy and its rest pose: the names a mesh's vertices and a sequence's tracks are bound by. Mirrors
 * Unreal's reference skeleton ({@code FReferenceSkeleton}): one root at bone 0, every parent before its children,
 * unique names, each bone's rest transform local to its parent.
 *
 * <pre>{@code
 * CgSkeleton arm = CgSkeleton.builder()
 *         .bone("root", null, new Vector3f())
 *         .bone("shoulder", "root", new Vector3f(0, 1.4f, 0))
 *         .bone("elbow", "shoulder", new Vector3f(0, -0.3f, 0), new Quaternionf().rotateZ(0.2f), new Vector3f(1))
 *         .build();
 *
 * int elbow = arm.indexOf("elbow");                       // 2; -1 for a name it lacks
 * arm.parent(elbow);                                      // 1
 * arm.restRotation(elbow, new Quaternionf());             // the rest pose, local to the shoulder
 * }</pre>
 *
 * <p>Loaded from a file, it comes with its mesh ({@code CgSkeletalMesh.skeleton()}).</p>
 *
 * <ul>
 *   <li>Immutable, and shared by every mesh and instance using it; any thread.</li>
 *   <li>Bones are visited parents first by index order alone: {@code for (b = 0; b < boneCount(); b++)} composes a
 *       pose with each parent already done.</li>
 *   <li>The inverse bind matrices are the mesh's, not the skeleton's, as Unreal keeps them on its skeletal mesh: one
 *       skeleton serves meshes bound differently.</li>
 * </ul>
 */
public final class CgSkeleton {

    /** Floats a bone's rest pose takes: translation 3, rotation 4 (x, y, z, w), scale 3. */
    static final int REST = 10;

    private final String[] names;
    private final int[] parents;
    private final float[] rest;
    private final Map<String, Integer> index;
    /** Per bone, the bone a virtual one follows, or -1 for a real bone. */
    private final int[] virtualTargets;
    /** The virtual bones, in index order. */
    private final int[] virtualBones;

    private CgSkeleton(String[] names, int[] parents, float[] rest) {
        this(names, parents, rest, filled(names.length));
    }

    private CgSkeleton(String[] names, int[] parents, float[] rest, int[] virtualTargets) {
        this.names = names;
        this.parents = parents;
        this.rest = rest;
        this.virtualTargets = virtualTargets;
        this.index = new HashMap<>(names.length * 2);
        for (int i = 0; i < names.length; i++) index.put(names[i], i);
        int n = 0;
        for (int t : virtualTargets) if (t >= 0) n++;
        this.virtualBones = new int[n];
        for (int b = 0, i = 0; b < virtualTargets.length; b++) if (virtualTargets[b] >= 0) virtualBones[i++] = b;
    }

    private static int[] filled(int n) {
        int[] out = new int[n];
        Arrays.fill(out, -1);
        return out;
    }

    /**
     * This skeleton with {@code bones} added after its own, virtual bones each under its source and following
     * its target, its rest the target's rest relative to the source.
     *
     * <pre>{@code
     * CgSkeleton withIk = skeleton.withVirtualBones(List.of(new CgVirtualBone("ik_foot_l", "root", "foot_l")));
     * }</pre>
     *
     * @throws IllegalArgumentException for a name the skeleton has, or a source or target it lacks (a target must be a
     *                                  real bone)
     */
    public CgSkeleton withVirtualBones(List<CgVirtualBone> bones) {
        if (bones.isEmpty()) return this;
        int n = names.length, total = n + bones.size();
        String[] nn = Arrays.copyOf(names, total);
        int[] np = Arrays.copyOf(parents, total), nt = Arrays.copyOf(virtualTargets, total);
        float[] nr = Arrays.copyOf(rest, total * REST);
        Matrix4f[] global = new Matrix4f[total];
        for (int b = 0; b < total; b++) global[b] = new Matrix4f();
        restGlobal(global);
        Map<String, Integer> at = new HashMap<>(index);
        Matrix4f rel = new Matrix4f();
        Vector3f t = new Vector3f(), s = new Vector3f();
        Quaternionf q = new Quaternionf();
        for (int i = 0; i < bones.size(); i++) {
            CgVirtualBone v = bones.get(i);
            int b = n + i;
            if (at.containsKey(v.name())) throw new IllegalArgumentException("virtual bone " + v.name() + ": the skeleton has that name");
            Integer source = at.get(v.source()), target = index.get(v.target());
            if (source == null) throw new IllegalArgumentException("virtual bone " + v.name() + "'s source " + v.source() + " is not a bone before it");
            if (target == null || virtualTargets[target] >= 0) {
                throw new IllegalArgumentException("virtual bone " + v.name() + "'s target " + v.target() + " is not a real bone");
            }
            global[source].invertAffine(rel).mul(global[target]);
            rel.getTranslation(t);
            rel.getNormalizedRotation(q);
            rel.getScale(s);
            float[] values = {t.x, t.y, t.z, q.x, q.y, q.z, q.w, s.x, s.y, s.z};
            System.arraycopy(values, 0, nr, b * REST, REST);
            nn[b] = v.name();
            np[b] = source;
            nt[b] = target;
            global[source].mul(rel, global[b]);
            at.put(v.name(), b);
        }
        return new CgSkeleton(nn, np, nr, nt);
    }

    /** The bone a virtual bone follows, or -1 for a real bone. */
    public int virtualTarget(int bone) {
        return virtualTargets[bone];
    }

    /** Its virtual bones, in index order; empty for none. Read-only. */
    int[] virtualBones() {
        return virtualBones;
    }

    /** A skeleton built bone by bone, root first. */
    public static Builder builder() {
        return new Builder();
    }

    public int boneCount() {
        return names.length;
    }

    public String name(int bone) {
        return names[bone];
    }

    /** The bone's parent, always a lower index; -1 for the root. */
    public int parent(int bone) {
        return parents[bone];
    }

    /** The bone named {@code name}, or -1. */
    public int indexOf(String name) {
        Integer i = index.get(name);
        return i == null ? -1 : i;
    }

    /**
     * The bone named {@code name} ignoring case, as Unreal's {@code FName} compares (NameTypes.h:629, 806): an exact
     * match first, else the first bone whose name differs only in case; -1 for none.
     */
    public int indexOfIgnoringCase(String name) {
        int exact = indexOf(name);
        if (exact >= 0) return exact;
        for (int b = 0; b < names.length; b++) if (names[b].equalsIgnoreCase(name)) return b;
        return -1;
    }

    /** The rest poses, {@link #REST} floats a bone: what a pose resets to. */
    float[] rest() {
        return rest;
    }

    public Vector3f restTranslation(int bone, Vector3f out) {
        int r = bone * REST;
        return out.set(rest[r], rest[r + 1], rest[r + 2]);
    }

    public Quaternionf restRotation(int bone, Quaternionf out) {
        int r = bone * REST;
        return out.set(rest[r + 3], rest[r + 4], rest[r + 5], rest[r + 6]);
    }

    public Vector3f restScale(int bone, Vector3f out) {
        int r = bone * REST;
        return out.set(rest[r + 7], rest[r + 8], rest[r + 9]);
    }

    /** The bone's rest transform local to its parent: translation, rotation, then scale. */
    public Matrix4f restLocal(int bone, Matrix4f out) {
        int r = bone * REST;
        return out.translationRotateScale(rest[r], rest[r + 1], rest[r + 2], rest[r + 3], rest[r + 4], rest[r + 5],
                rest[r + 6], rest[r + 7], rest[r + 8], rest[r + 9]);
    }

    /**
     * Every bone's rest transform in the skeleton's space, into {@code out}, one matrix a bone: the parent's composed
     * before the child's.
     */
    public Matrix4f[] restGlobal(Matrix4f[] out) {
        if (out.length < names.length) throw new IllegalArgumentException(out.length + " matrices for " + names.length + " bones");
        for (int b = 0; b < names.length; b++) {
            Matrix4f m = restLocal(b, out[b]);
            if (parents[b] >= 0) out[parents[b]].mul(m, m);
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof CgSkeleton s && Arrays.equals(names, s.names) && Arrays.equals(parents, s.parents)
                && Arrays.equals(rest, s.rest) && Arrays.equals(virtualTargets, s.virtualTargets);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(names) + Arrays.hashCode(parents);
    }

    @Override
    public String toString() {
        return "CgSkeleton{" + names.length + " bones, root " + names[0] + "}";
    }

    /** Builds a {@link CgSkeleton}: the root first, then each bone after its parent. */
    public static final class Builder {

        private final List<String> names = new ArrayList<>();
        private final List<Integer> parents = new ArrayList<>();
        private float[] rest = new float[REST * 16];
        private final Map<String, Integer> index = new HashMap<>();

        private Builder() {
        }

        /** A bone at {@code translation} from its parent, unrotated and unscaled. */
        public Builder bone(String name, @Nullable String parent, Vector3fc translation) {
            return bone(name, parent, translation, new Quaternionf(), new Vector3f(1f));
        }

        /**
         * A bone and its rest transform local to {@code parent}, which must already be a bone; null for the root, which
         * comes first and only once. The rotation is normalised.
         */
        public Builder bone(String name, @Nullable String parent, Vector3fc translation, Quaternionfc rotation, Vector3fc scale) {
            int p = -1;
            if (parent != null) {
                Integer at = index.get(parent);
                if (at == null) throw new IllegalArgumentException("bone " + name + "'s parent " + parent + " is not a bone before it");
                p = at;
            }
            return bone(name, p, translation.x(), translation.y(), translation.z(), rotation.x(), rotation.y(), rotation.z(),
                    rotation.w(), scale.x(), scale.y(), scale.z());
        }

        /** As {@link #bone(String, String, Vector3fc, Quaternionfc, Vector3fc)}, its parent by index. */
        public Builder bone(String name, int parent, float tx, float ty, float tz, float qx, float qy, float qz, float qw,
                            float sx, float sy, float sz) {
            int b = names.size();
            if (name == null || name.isEmpty()) throw new IllegalArgumentException("bone " + b + " has no name");
            Integer had = index.putIfAbsent(name, b);
            if (had != null) throw new IllegalArgumentException("bones " + had + " and " + b + " are both named " + name);
            if (b == 0 ? parent != -1 : parent < 0 || parent >= b) {
                throw new IllegalArgumentException(b == 0 ? "the root " + name + " has a parent"
                        : "bone " + name + " has no parent before it: the root is bone 0, and there is one");
            }
            float length = CgAnimSequence.unitLength(qx, qy, qz, qw);
            if (!(length > 0f)) throw new IllegalArgumentException("bone " + name + "'s rotation is zero");
            if (rest.length < (b + 1) * REST) rest = Arrays.copyOf(rest, rest.length * 2);
            float[] values = {tx, ty, tz, qx / length, qy / length, qz / length, qw / length, sx, sy, sz};
            System.arraycopy(values, 0, rest, b * REST, REST);
            names.add(name);
            parents.add(parent);
            return this;
        }

        /** The index the next bone will have. */
        public int next() {
            return names.size();
        }

        /** @throws IllegalStateException with no bones */
        public CgSkeleton build() {
            if (names.isEmpty()) throw new IllegalStateException("a skeleton has at least its root");
            int[] p = new int[parents.size()];
            for (int i = 0; i < p.length; i++) p[i] = parents.get(i);
            return new CgSkeleton(names.toArray(new String[0]), p, Arrays.copyOf(rest, names.size() * REST));
        }
    }
}
