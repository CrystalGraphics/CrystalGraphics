package com.crystalgraphics.api.mesh;

import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgMorphTargets;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.animation.CgSocket;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A skinned mesh: geometry whose vertices follow the bones of a {@link CgSkeleton}, with the bind pose that ties the
 * two, its submeshes' material names, its morph targets and the sequences that came with it. Mirrors Unreal's
 * {@code USkeletalMesh}: the skeleton names the bones, the mesh holds their inverse bind matrices.
 *
 * <pre>{@code
 * CgSkeletalMesh kurama = CgMeshLoader.skeletal("mymod:models/kurama.glb");   // or a cooked .cgmesh
 * kurama.mesh();                                   // CgVertexFormat.SKINNED, a submesh per glTF primitive
 *                                                  // (SKINNED_WIDE past 256 bones)
 * kurama.skeleton().indexOf("pelvis");
 * kurama.material(3);                              // what submesh 3 names, or null
 * kurama.sequence("atk010");                       // a clip from the same file, or null
 * }</pre>
 *
 * <p>Built in Java around a mesh you wrote:</p>
 *
 * <pre>{@code
 * CgSkeletalMesh arm = CgSkeletalMesh.builder(mesh, skeleton)      // bound at the skeleton's rest pose
 *         .material(0, "mymod:arm")
 *         .sequence(wave)
 *         .build();
 * }</pre>
 *
 * <ul>
 *   <li>Immutable and shared, its mesh too; any thread.</li>
 *   <li>A vertex's bone indices are the skeleton's: up to 256 bones in {@link CgVertexFormat#SKINNED}, more in
 *       {@link CgVertexFormat#SKINNED_WIDE} ({@link CgVertexFormat#skinned(int)} picks).</li>
 *   <li>Skinning a vertex: {@code pose(bone) * inverseBind(bone) * position}, summed by weight, with {@code pose} the
 *       bone's transform in the skeleton's space.</li>
 * </ul>
 */
public final class CgSkeletalMesh {

    private final CgMesh mesh;
    private final CgSkeleton skeleton;
    /** Sixteen floats a bone, column-major, as JOML reads them. */
    private final float[] inverseBinds;
    private final List<String> materials;
    private final CgMorphTargets morphTargets;
    private final List<CgAnimSequence> sequences;
    private final List<CgSocket> sockets;

    private CgSkeletalMesh(CgMesh mesh, CgSkeleton skeleton, float[] inverseBinds, List<String> materials,
                           CgMorphTargets morphTargets, List<CgAnimSequence> sequences, List<CgSocket> sockets) {
        this.mesh = mesh;
        this.skeleton = skeleton;
        this.inverseBinds = inverseBinds;
        this.materials = materials;
        this.morphTargets = morphTargets;
        this.sequences = sequences;
        this.sockets = sockets;
    }

    /**
     * This mesh with {@code sockets} in place of its own: those a skeleton asset declares, given to a mesh loaded from a
     * file, which carries none.
     *
     * <pre>{@code
     * CgSkeletalMesh goku = CgMeshLoader.skeletal(path).withSockets(List.of(CgSocket.of("right_hand", "hand_r", offset)));
     * }</pre>
     */
    public CgSkeletalMesh withSockets(List<CgSocket> sockets) {
        return new CgSkeletalMesh(mesh, skeleton, inverseBinds, materials, morphTargets, sequences, checkSockets(skeleton, sockets));
    }

    /** Its sockets, in the order given. */
    public List<CgSocket> sockets() {
        return sockets;
    }

    /** The socket named {@code name} ignoring case, or null. */
    @Nullable
    public CgSocket socket(String name) {
        for (CgSocket s : sockets) if (s.name().equalsIgnoreCase(name)) return s;
        return null;
    }

    private static List<CgSocket> checkSockets(CgSkeleton skeleton, List<CgSocket> sockets) {
        for (int i = 0; i < sockets.size(); i++) {
            CgSocket s = sockets.get(i);
            if (skeleton.indexOfIgnoringCase(s.bone()) < 0) {
                throw new IllegalArgumentException("socket " + s.name() + " is on bone " + s.bone() + ", which " + skeleton + " lacks");
            }
            for (int j = 0; j < i; j++) {
                if (sockets.get(j).name().equalsIgnoreCase(s.name())) throw new IllegalArgumentException("two sockets named " + s.name());
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(sockets));
    }

    /** A skinned mesh of {@code mesh}, in {@link CgVertexFormat#skinned(int)}'s format for the skeleton, over {@code skeleton}. */
    public static Builder builder(CgMesh mesh, CgSkeleton skeleton) {
        return new Builder(mesh, skeleton);
    }

    public CgMesh mesh() {
        return mesh;
    }

    public CgSkeleton skeleton() {
        return skeleton;
    }

    /** The inverse of the bone's transform in the skeleton's space when the mesh was bound to it. */
    public Matrix4f inverseBind(int bone, Matrix4f out) {
        return out.set(inverseBinds, bone * 16);
    }

    /** The material submesh {@code submesh} names, or null. */
    @Nullable
    public String material(int submesh) {
        return materials.get(submesh);
    }

    /** One name, or null, per submesh. */
    public List<String> materials() {
        return materials;
    }

    /** {@link CgMorphTargets#NONE} where it has none. */
    public CgMorphTargets morphTargets() {
        return morphTargets;
    }

    /** The sequences that came with it, in the file's order. */
    public List<CgAnimSequence> sequences() {
        return sequences;
    }

    /** The sequence named {@code name}, or null. */
    @Nullable
    public CgAnimSequence sequence(String name) {
        for (CgAnimSequence s : sequences) if (s.name().equals(name)) return s;
        return null;
    }

    @Override
    public String toString() {
        return "CgSkeletalMesh{" + mesh.vertexCount() + " vertices, " + mesh.submeshCount() + " submeshes, " + skeleton
                + ", " + morphTargets + ", " + sequences.size() + " sequences}";
    }

    /** Builds a {@link CgSkeletalMesh}. */
    public static final class Builder {

        private final CgMesh mesh;
        private final CgSkeleton skeleton;
        private final float[] inverseBinds;
        private final String[] materials;
        private CgMorphTargets morphTargets = CgMorphTargets.NONE;
        private final List<CgAnimSequence> sequences = new ArrayList<>();
        private final List<CgSocket> sockets = new ArrayList<>();

        private Builder(CgMesh mesh, CgSkeleton skeleton) {
            if (!mesh.format().equals(CgVertexFormat.SKINNED) && !mesh.format().equals(CgVertexFormat.SKINNED_WIDE)) {
                throw new IllegalArgumentException("a skeletal mesh is " + CgVertexFormat.SKINNED.getKey() + " or "
                        + CgVertexFormat.SKINNED_WIDE.getKey() + ", not " + mesh.format().getKey());
            }
            if (mesh.format().equals(CgVertexFormat.SKINNED) && skeleton.boneCount() > 256) {
                throw new IllegalArgumentException(skeleton.boneCount() + " bones: a " + CgVertexFormat.SKINNED.getKey()
                        + " vertex names at most 256; build the mesh " + CgVertexFormat.SKINNED_WIDE.getKey());
            }
            this.mesh = mesh;
            this.skeleton = skeleton;
            this.inverseBinds = new float[skeleton.boneCount() * 16];
            Matrix4f[] global = new Matrix4f[skeleton.boneCount()];
            for (int b = 0; b < global.length; b++) global[b] = new Matrix4f();
            skeleton.restGlobal(global);
            for (int b = 0; b < global.length; b++) global[b].invert().get(inverseBinds, b * 16);
            this.materials = new String[mesh.submeshCount()];
        }

        /** The bone's inverse bind matrix, where the mesh was bound in a pose other than the rest pose. */
        public Builder inverseBind(int bone, Matrix4fc inverseBind) {
            inverseBind.get(inverseBinds, bone * 16);
            return this;
        }

        public Builder material(int submesh, @Nullable String material) {
            materials[submesh] = material;
            return this;
        }

        public Builder morphTargets(CgMorphTargets targets) {
            if (targets != CgMorphTargets.NONE && targets.vertexCount() != mesh.vertexCount()) {
                throw new IllegalArgumentException("morph targets over " + targets.vertexCount() + " vertices, the mesh has "
                        + mesh.vertexCount());
            }
            this.morphTargets = targets;
            return this;
        }

        public Builder sequence(CgAnimSequence sequence) {
            for (CgAnimSequence s : sequences) {
                if (s.name().equals(sequence.name())) throw new IllegalArgumentException("two sequences named " + s.name());
            }
            sequences.add(sequence);
            return this;
        }

        /** A socket on one of its bones: Unreal's mesh socket. */
        public Builder socket(CgSocket socket) {
            sockets.add(socket);
            return this;
        }

        public CgSkeletalMesh build() {
            return new CgSkeletalMesh(mesh, skeleton, inverseBinds.clone(),
                    Collections.unmodifiableList(Arrays.asList(materials.clone())), morphTargets,
                    Collections.unmodifiableList(new ArrayList<>(sequences)), checkSockets(skeleton, sockets));
        }
    }
}
