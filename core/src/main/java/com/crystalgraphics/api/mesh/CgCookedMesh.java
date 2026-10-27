package com.crystalgraphics.api.mesh;

import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgMorphTargets;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.util.io.CgCookedBytes;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A cooked mesh file ({@code .cgmesh}): a mesh's vertices as its format lays them out, copied in one step at load, its
 * indices and submeshes, and for a skinned mesh its skeleton, bind pose and morph targets. Its sequences go beside it
 * in a {@code .cganim} ({@code CgCookedAnimation}). {@link CgMeshLoader} loads a path ending in {@code .cgmesh} as it
 * loads a glTF.
 *
 * <pre>{@code
 * byte[] mesh = CgCookedMesh.write(kurama);                         // the .cgmesh
 * byte[] clips = CgCookedAnimation.write(kurama.sequences());       // the .cganim beside it
 * CgSkeletalMesh back = CgCookedMesh.readSkeletal(mesh, CgCookedAnimation.read(clips), false);
 *
 * byte[] rock = CgCookedMesh.write(CgMeshLoader.model("mymod:models/rock.glb"));   // a static mesh
 * }</pre>
 *
 * <pre>
 * "CGMS"  u16 version (1)  u16 flags (1 skinned, 2 morph targets)
 * string vertex format, u8 topology, u32 vertices, u32 indices, u32 submeshes
 * per submesh: u32 first index, u32 index count, u32 first vertex, u32 vertex count, string material
 * the vertices: vertices x the format's stride bytes; u8 index width (2 or 4), then the indices
 * skinned: u32 bones; per bone string name, u32 parent (0xFFFFFFFF for the root), f32 x 10 its rest pose
 *          (translation, rotation x y z w, scale); then f32 x 16 per bone, its inverse bind, column-major
 * morph targets: u32 targets; per target string name, f32 default weight; u32 entries;
 *          u32 x (vertices + 1) each vertex's first entry; per entry u32 target, f32 x 6 position and normal delta
 * little-endian; a string is u16 length and UTF-8, 0xFFFF for none
 * </pre>
 *
 * <ul>
 *   <li>The layout is the engine's own and versioned with it: a file of another version, or one whose format this
 *       engine does not have with the same stride, fails to read, naming why.</li>
 *   <li>Vertices are stored as the machine lays them out, little-endian: a big-endian machine refuses the file.</li>
 *   <li>Any thread.</li>
 * </ul>
 */
public final class CgCookedMesh {

    private static final int VERSION = 1, SKINNED = 1, MORPHS = 2, NO_PARENT = 0xFFFFFFFF;

    private CgCookedMesh() {
    }

    /** Whether {@code path} names a cooked mesh file. */
    public static boolean is(String path) {
        return path != null && path.endsWith(".cgmesh");
    }

    /** A static mesh's file. */
    public static byte[] write(CgMeshLoader.Model model) {
        return write(model.mesh(), model.materials(), null);
    }

    /** A skinned mesh's file; its sequences are written apart, by {@code CgCookedAnimation}. */
    public static byte[] write(CgSkeletalMesh skinned) {
        return write(skinned.mesh(), skinned.materials(), skinned);
    }

    /** A static mesh from its file. @throws IllegalArgumentException naming what is wrong with it */
    public static CgMeshLoader.Model readModel(byte[] file, boolean shared) {
        Read read = read(file, shared);
        if (read.skeleton != null) throw new IllegalArgumentException("a skinned .cgmesh: load it as a skeletal mesh");
        return new CgMeshLoader.Model(read.mesh, read.materials);
    }

    /**
     * A skinned mesh from its file and the sequences cooked beside it.
     *
     * @throws IllegalArgumentException naming what is wrong with the file
     */
    public static CgSkeletalMesh readSkeletal(byte[] file, List<CgAnimSequence> sequences, boolean shared) {
        Read read = read(file, shared);
        if (read.skeleton == null) throw new IllegalArgumentException("a static .cgmesh: load it as a model");
        CgSkeletalMesh.Builder built = CgSkeletalMesh.builder(read.mesh, read.skeleton).morphTargets(read.morphs);
        Matrix4f m = new Matrix4f();
        for (int b = 0; b < read.inverseBinds.length / 16; b++) built.inverseBind(b, m.set(read.inverseBinds, b * 16));
        for (int i = 0; i < read.materials.size(); i++) built.material(i, read.materials.get(i));
        for (CgAnimSequence s : sequences) built.sequence(s);
        return built.build();
    }

    private static byte[] write(CgMesh mesh, List<String> materials, CgSkeletalMesh skinned) {
        littleEndian();
        CgMorphTargets morphs = skinned == null ? CgMorphTargets.NONE : skinned.morphTargets();
        int flags = (skinned != null ? SKINNED : 0) | (morphs != CgMorphTargets.NONE ? MORPHS : 0);
        int vertices = mesh.vertexCount(), indexCount = mesh.indexCount(), submeshes = mesh.submeshCount();
        CgCookedBytes.Writer out = new CgCookedBytes.Writer();
        out.magic("CGMS").u16(VERSION).u16(flags).string(mesh.format().getKey()).u8(mesh.topology().ordinal())
                .u32(vertices).u32(indexCount).u32(submeshes);
        int widest = 0;
        for (int i = 0; i < submeshes; i++) {
            CgSubmesh s = mesh.submesh(i);
            out.u32(s.firstIndex()).u32(s.indexCount()).u32(s.firstVertex()).u32(s.vertexCount()).string(materials.get(i));
            widest = Math.max(widest, s.vertexCount());
        }
        int stride = mesh.format().getStride();
        ByteBuffer bytes = ByteBuffer.allocate(vertices * stride).order(ByteOrder.nativeOrder());
        mesh.readVertices(0, vertices, bytes);
        out.bytes(bytes.array(), 0, vertices * stride);
        int[] indices = new int[indexCount];
        mesh.readIndices(0, indexCount, indices, 0);
        boolean shorts = widest <= 0x10000;
        out.u8(shorts ? 2 : 4);
        for (int index : indices) {
            if (shorts) out.u16(index);
            else out.u32(index);
        }

        if (skinned != null) {
            CgSkeleton skeleton = skinned.skeleton();
            Vector3f v = new Vector3f();
            Quaternionf q = new Quaternionf();
            out.u32(skeleton.boneCount());
            for (int b = 0; b < skeleton.boneCount(); b++) {
                out.string(skeleton.name(b)).u32(skeleton.parent(b) < 0 ? NO_PARENT : skeleton.parent(b));
                skeleton.restTranslation(b, v);
                out.f32(v.x).f32(v.y).f32(v.z);
                skeleton.restRotation(b, q);
                out.f32(q.x).f32(q.y).f32(q.z).f32(q.w);
                skeleton.restScale(b, v);
                out.f32(v.x).f32(v.y).f32(v.z);
            }
            float[] column = new float[16];
            Matrix4f m = new Matrix4f();
            for (int b = 0; b < skeleton.boneCount(); b++) out.floats(skinned.inverseBind(b, m).get(column));
        }
        if (morphs != CgMorphTargets.NONE) {
            out.u32(morphs.targetCount());
            for (int t = 0; t < morphs.targetCount(); t++) out.string(morphs.name(t)).f32(morphs.defaultWeight(t));
            out.u32(morphs.entryCount());
            for (int v = 0; v <= vertices; v++) out.u32(morphs.firstEntry(v));
            Vector3f d = new Vector3f(), n = new Vector3f();
            for (int e = 0; e < morphs.entryCount(); e++) {
                morphs.positionDelta(e, d);
                morphs.normalDelta(e, n);
                out.u32(morphs.entryTarget(e)).f32(d.x).f32(d.y).f32(d.z).f32(n.x).f32(n.y).f32(n.z);
            }
        }
        return out.toBytes();
    }

    /** What a file holds, its skinned parts null for a static mesh. */
    private record Read(CgMesh mesh, List<String> materials, CgSkeleton skeleton, float[] inverseBinds, CgMorphTargets morphs) {
    }

    private static Read read(byte[] file, boolean shared) {
        littleEndian();
        CgCookedBytes.Reader in = new CgCookedBytes.Reader(file);
        in.magic("CGMS");
        int version = in.u16();
        if (version != VERSION) throw new IllegalArgumentException("a .cgmesh of version " + version + ", this engine reads " + VERSION);
        int flags = in.u16();
        String key = in.string();
        CgVertexFormat format = key == null ? null : CgVertexFormat.forShaderType(key);
        if (format == null) throw new IllegalArgumentException("vertex format " + key + " is not one this engine has");
        int topology = in.u8();
        if (topology >= CgMeshTopology.values().length) throw new IllegalArgumentException("topology " + topology);
        int vertices = in.u32(), indexCount = in.u32(), submeshCount = in.u32();
        int[][] submeshes = new int[Math.min(submeshCount, 1 << 16)][];
        if (submeshCount != submeshes.length) throw new IllegalArgumentException(submeshCount + " submeshes");
        List<String> materials = new ArrayList<>(submeshCount);
        for (int i = 0; i < submeshCount; i++) {
            submeshes[i] = new int[]{in.u32(), in.u32(), in.u32(), in.u32()};
            materials.add(in.string());
        }
        int stride = format.getStride();
        if (vertices < 0 || (long) vertices * stride > file.length) throw new IllegalArgumentException(vertices + " vertices");
        byte[] bytes = new byte[vertices * stride];
        in.bytes(bytes, 0, bytes.length);
        int width = in.u8();
        if (width != 2 && width != 4) throw new IllegalArgumentException("index width " + width);
        int[] indices = new int[Math.max(indexCount, 0)];
        for (int i = 0; i < indexCount; i++) indices[i] = width == 2 ? in.u16() : in.u32();
        for (int[] s : submeshes) {
            if (s[0] < 0 || s[1] < 0 || s[0] + s[1] > indexCount || s[2] < 0 || s[3] < 0 || s[2] + s[3] > vertices) {
                throw new IllegalArgumentException("a submesh outside the mesh: " + Arrays.toString(s));
            }
        }
        CgMeshTopology drawn = CgMeshTopology.values()[topology];
        CgMesh mesh = CgMeshLoader.mesh(format, shared, m -> {
            m.topology(drawn);
            for (int i = 0; i < submeshes.length; i++) {
                if (i > 0) m.submesh();
                int[] s = submeshes[i];
                m.copyVertices(bytes, s[2] * stride, s[3]);
                for (int k = s[0]; k < s[0] + s[1]; k++) m.index(indices[k]);
            }
        });

        CgSkeleton skeleton = null;
        float[] inverseBinds = null;
        if ((flags & SKINNED) != 0) {
            int bones = in.u32();
            if (bones < 1 || bones > 65536) throw new IllegalArgumentException(bones + " bones");
            CgSkeleton.Builder built = CgSkeleton.builder();
            for (int b = 0; b < bones; b++) {
                String name = in.string();
                int parent = in.u32();
                float[] r = in.floats(10);
                built.bone(name, parent == NO_PARENT ? -1 : parent, r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8], r[9]);
            }
            skeleton = built.build();
            inverseBinds = in.floats(bones * 16);
        }
        CgMorphTargets morphs = CgMorphTargets.NONE;
        if ((flags & MORPHS) != 0) {
            int targets = in.u32();
            CgMorphTargets.Builder built = CgMorphTargets.builder(vertices);
            for (int t = 0; t < targets; t++) built.target(in.string(), in.f32());
            int entries = in.u32();
            int[] starts = in.ints(vertices + 1);
            for (int v = 0, e = 0; v < vertices; v++) {
                if (starts[v] != e || starts[v + 1] < e || starts[v + 1] > entries) {
                    throw new IllegalArgumentException("vertex " + v + "'s morph entries start at " + starts[v]);
                }
                for (; e < starts[v + 1]; e++) {
                    int target = in.u32();
                    float[] d = in.floats(6);
                    built.delta(target, v, d[0], d[1], d[2], d[3], d[4], d[5]);
                }
            }
            morphs = built.build();
        }
        if (!in.atEnd()) throw new IllegalArgumentException("bytes past the end of the mesh");
        return new Read(mesh, Collections.unmodifiableList(materials), skeleton, inverseBinds, morphs);
    }

    private static void littleEndian() {
        if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) {
            throw new IllegalStateException("cooked meshes hold little-endian vertices; this machine is big-endian");
        }
    }
}
