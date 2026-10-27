package com.crystalgraphics.api.mesh;

import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgCookedAnimation;
import com.crystalgraphics.api.animation.CgMorphTargets;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.*;

/**
 * Skinned glTF loading and the cook: a small glTF of known answers, and with
 * {@code -PskinnedMeshes=<folder of .glb>} every file there loaded and round-tripped through the cooked files.
 */
public class CgSkeletalMeshTest {

    // ── Weights, as Unreal's FBoneWeights stores them ──────────────────────────────

    @Test
    public void weightsAreQuantisedHeaviestFirstAndSumToOne() {
        CgMesh mesh = CgMesh.build(CgVertexFormat.SKINNED, m -> {
            vertex(m).bone(3, 0.5f).bone(1, 0.3f).bone(3, 0.6f).bone(2, 0.1f).end();   // 3 twice: its larger weight
            vertex(m).bone(1, 0.5f).bone(2, 0.5f).end();                               // equal: the carry decides
            vertex(m).bone(7, 0.004f).bone(5, 1f).end();                               // rounds to 1/255: kept
        });
        assertArrayEquals(new int[]{3, 1, 2, 0, 152, 77, 26, 0}, bones(mesh, 0));
        assertArrayEquals(new int[]{2, 1, 0, 0, 128, 127, 0, 0}, bones(mesh, 1));
        assertArrayEquals(new int[]{5, 7, 0, 0, 254, 1, 0, 0}, bones(mesh, 2));
    }

    @Test
    public void aVertexFollowingFiveBonesFails() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> CgMesh.build(CgVertexFormat.SKINNED,
                m -> vertex(m).bone(0, .2f).bone(1, .2f).bone(2, .2f).bone(3, .2f).bone(4, .2f).end()));
        assertTrue(e.getMessage(), e.getMessage().contains("at most 4"));
    }

    @Test
    public void aVertexWithNoWeightFails() {
        assertThrows(IllegalStateException.class, () -> CgMesh.build(CgVertexFormat.SKINNED, m -> vertex(m).bone(0, 0f).end()));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> CgMesh.build(CgVertexFormat.SKINNED,
                m -> vertex(m).end()));
        assertTrue(e.getMessage(), e.getMessage().contains("cg_BoneIndices"));
    }

    // ── The skeleton ─────────────────────────────────────────────────────────────────

    @Test
    public void aSkeletonKeepsParentsBeforeChildrenAndOneRoot() {
        CgSkeleton arm = CgSkeleton.builder().bone("root", null, new Vector3f())
                .bone("elbow", "root", new Vector3f(0, 1, 0)).build();
        assertEquals(1, arm.indexOf("elbow"));
        assertEquals(0, arm.parent(1));
        assertThrows(IllegalArgumentException.class, () -> CgSkeleton.builder().bone("root", null, new Vector3f())
                .bone("hand", "elbow", new Vector3f()));
        assertThrows(IllegalArgumentException.class, () -> CgSkeleton.builder().bone("root", null, new Vector3f())
                .bone("root", "root", new Vector3f()));
        assertThrows(IllegalArgumentException.class, () -> CgSkeleton.builder().bone("root", null, new Vector3f())
                .bone("other", null, new Vector3f()));
    }

    // ── A glTF of known answers ──────────────────────────────────────────────────────

    @Test
    public void aSkinnedGltfLoadsItsSkeletonWeightsAndMorphTargets() throws IOException {
        CgSkeletalMesh mesh = CgGltfLoader.readSkeletal(new ByteArrayInputStream(gltf()), "test", false);
        CgSkeleton skeleton = mesh.skeleton();
        assertEquals(3, skeleton.boneCount());
        assertEquals("armature", skeleton.name(0));      // a joint's ancestor that is no joint is a bone
        assertEquals("root", skeleton.name(1));
        assertEquals("child", skeleton.name(2));
        assertEquals(1, skeleton.parent(2));
        assertEquals(new Vector3f(0, 1, 0), skeleton.restTranslation(2, new Vector3f()));

        Matrix4f inverse = mesh.inverseBind(2, new Matrix4f());
        assertEquals(-1f, inverse.m31(), 0f);             // the file's inverse bind: the child bound 1 up
        assertArrayEquals(new int[]{1, 0, 0, 0, 255, 0, 0, 0}, bones(mesh.mesh(), 0));   // joint 0 is bone 1
        assertArrayEquals(new int[]{2, 0, 0, 0, 255, 0, 0, 0}, bones(mesh.mesh(), 1));
        assertArrayEquals(new int[]{2, 1, 0, 0, 128, 127, 0, 0}, bones(mesh.mesh(), 2));

        CgMorphTargets morphs = mesh.morphTargets();
        assertEquals(1, morphs.targetCount());
        assertEquals("bulge", morphs.name(0));
        assertEquals(0.25f, morphs.defaultWeight(0), 0f);
        assertEquals(1, morphs.entryCount());              // the zero deltas are dropped
        assertEquals(0, morphs.firstEntry(1));             // vertex 1's one entry
        assertEquals(1, morphs.firstEntry(2));
        assertEquals(new Vector3f(0, 0.5f, 0), morphs.positionDelta(0, new Vector3f()));
    }

    @Test
    public void aClipIsBakedAsUnrealBakesIt() throws IOException {
        CgAnimSequence bend = CgGltfLoader.readSkeletal(new ByteArrayInputStream(gltf()), "test", false).sequence("bend");
        assertNotNull(bend);
        assertEquals(30, bend.frameRate(), 0);             // 1 s lands on 30 a second
        assertEquals(31, bend.frameCount());

        int child = bend.track("child");
        Quaternionf half = bend.rotation(child, 15, new Quaternionf());   // slerp half way: 45 degrees about z
        assertEquals(Math.sin(Math.PI / 8), half.z, 1e-6);
        assertEquals(Math.cos(Math.PI / 8), half.w, 1e-6);
        assertEquals(new Vector3f(0, 1, 0), bend.translation(child, 15, new Vector3f()));   // unkeyed: the rest, held
        assertFalse(bend.held(child));

        int root = bend.track("root");                      // Unreal's step changes half way between keys
        assertEquals(0f, bend.translation(root, 14, new Vector3f()).x, 0f);
        assertEquals(1f, bend.translation(root, 15, new Vector3f()).x, 0f);
        assertEquals(-1, bend.track("armature"));          // not animated: it rests
    }

    @Test
    public void theFrameRateIsTheFirstUsualOneTheLengthLandsOn() {
        assertArrayEquals(new double[]{30, 1.5333333}, CgGltfLoader.frameRate(1.5333333), 1e-6);
        assertArrayEquals(new double[]{24, 7 / 24.0}, CgGltfLoader.frameRate(7 / 24.0), 1e-9);
        assertEquals(20 / 30.0, CgGltfLoader.frameRate(0.6667)[1], 1e-9);   // snapped to its frame
        assertEquals(1 / 30.0, CgGltfLoader.frameRate(0)[1], 1e-9);         // at least a frame long
    }

    @Test
    public void aHeldChannelKeepsOneKey() {
        float[] still = new float[3 * 5];
        float[] turning = new float[4 * 5];
        for (int k = 0; k < 5; k++) {
            still[k * 3 + 1] = 1f + k * 1e-8f;                // inside Unreal's translation threshold
            Quaternionf q = new Quaternionf().rotateY(k * 0.1f);
            turning[k * 4] = q.x;
            turning[k * 4 + 1] = q.y;
            turning[k * 4 + 2] = q.z;
            turning[k * 4 + 3] = q.w;
        }
        CgAnimSequence s = CgAnimSequence.builder("s", 30, 5).track("b", still, turning, new float[]{1, 1, 1}).build();
        assertEquals(new Vector3f(0, 1, 0), s.translation(0, 4, new Vector3f()));
        assertFalse(s.held(0));
        assertEquals(0.4f, s.rotation(0, 4, new Quaternionf()).angle(), 1e-5);
    }

    // ── The cook ─────────────────────────────────────────────────────────────────────

    @Test
    public void theCookRoundTrips() throws IOException {
        roundTrip(CgGltfLoader.readSkeletal(new ByteArrayInputStream(gltf()), "test", false));

        CgMeshLoader.Model model = CgMeshLoader.read(new ByteArrayInputStream(gltf()), "gltf", null);   // static, bind pose
        byte[] cooked = CgCookedMesh.write(model);
        CgMeshLoader.Model back = CgCookedMesh.readModel(cooked, false);
        assertEquals(model.materials(), back.materials());
        assertEquals(vertexBytes(model.mesh()), vertexBytes(back.mesh()));
        assertArrayEquals(cooked, CgCookedMesh.write(back));
        assertThrows(IllegalArgumentException.class, () -> CgCookedMesh.readSkeletal(cooked, List.of(), false));
    }

    /** {@code -PskinnedMeshes=<folder>,...}: every .glb there loads, and its cook reads back equal. */
    @Test
    public void everySkinnedFileLoadsAndRoundTrips() throws IOException {
        String folders = System.getProperty("crystalgraphics.skinnedMeshes");
        Assume.assumeTrue("-PskinnedMeshes=<folder>,... names the files", folders != null);
        for (String folder : folders.split(",")) {
            File[] files = new File(folder).listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".glb"));
            assertNotNull(folder, files);
            assertTrue(folder + " holds no .glb", files.length > 0);
            for (File file : files) {
                CgSkeletalMesh mesh;
                try (InputStream in = Files.newInputStream(file.toPath())) {
                    mesh = CgGltfLoader.readSkeletal(in, file.getName(), false);
                }
                System.out.println(file.getName() + ": " + mesh + ", " + mesh.mesh().format().getKey());
                for (CgAnimSequence s : mesh.sequences()) System.out.println("  " + s);
                roundTrip(mesh);
            }
        }
    }

    private static void roundTrip(CgSkeletalMesh mesh) {
        byte[] cookedMesh = CgCookedMesh.write(mesh), cookedClips = CgCookedAnimation.write(mesh.sequences());
        CgSkeletalMesh back = CgCookedMesh.readSkeletal(cookedMesh, CgCookedAnimation.read(cookedClips), false);
        assertEquals(mesh.skeleton(), back.skeleton());
        assertEquals(mesh.materials(), back.materials());
        assertEquals(mesh.morphTargets(), back.morphTargets());
        assertEquals(mesh.sequences(), back.sequences());
        for (int b = 0; b < mesh.skeleton().boneCount(); b++) {
            assertEquals(mesh.inverseBind(b, new Matrix4f()), back.inverseBind(b, new Matrix4f()));
        }
        CgMesh a = mesh.mesh(), c = back.mesh();
        assertEquals(a.vertexCount(), c.vertexCount());
        assertEquals(a.indexCount(), c.indexCount());
        assertEquals(a.submeshCount(), c.submeshCount());
        for (int i = 0; i < a.submeshCount(); i++) assertEquals(a.submesh(i), c.submesh(i));
        assertEquals(vertexBytes(a), vertexBytes(c));
        int[] ia = new int[a.indexCount()], ic = new int[c.indexCount()];
        a.readIndices(0, ia.length, ia, 0);
        c.readIndices(0, ic.length, ic, 0);
        assertArrayEquals(ia, ic);
        assertArrayEquals(cookedMesh, CgCookedMesh.write(back));        // and the same bytes again
        assertArrayEquals(cookedClips, CgCookedAnimation.write(back.sequences()));
    }

    private static ByteBuffer vertexBytes(CgMesh mesh) {
        ByteBuffer b = ByteBuffer.allocate(mesh.vertexCount() * mesh.format().getStride()).order(ByteOrder.nativeOrder());
        mesh.readVertices(0, mesh.vertexCount(), b);
        b.rewind();
        return b;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────

    private static CgMeshWriter vertex(CgMeshWriter m) {
        return m.vertex().position(0, 0, 0).normal(0, 1, 0).uv(0, 0);
    }

    /** Vertex {@code v}'s four bone indices, then its four raw weights. */
    private static int[] bones(CgMesh mesh, int v) {
        CgVertexFormat f = mesh.format();
        ByteBuffer b = ByteBuffer.allocate(f.getStride()).order(ByteOrder.nativeOrder());
        mesh.readVertices(v, 1, b);
        int indices = f.getAttribute(f.indexOf("cg_BoneIndices")).getOffset();
        int weights = f.getAttribute(f.indexOf("cg_BoneWeights")).getOffset();
        int[] out = new int[8];
        for (int i = 0; i < 4; i++) {
            out[i] = b.get(indices + i) & 0xFF;
            out[4 + i] = b.get(weights + i) & 0xFF;
        }
        return out;
    }

    /**
     * An armature over two joints, {@code root} at the origin and {@code child} one up; a triangle on them with a
     * morph target moving its second vertex; a one-second clip turning the child 90 degrees about z (linear) and
     * stepping the root one along x.
     */
    private static byte[] gltf() {
        Buffer b = new Buffer();
        int positions = b.floats("VEC3", 0, 0, 0, 0, 1, 0, 1, 0.5f, 0);
        int normals = b.floats("VEC3", 0, 0, 1, 0, 0, 1, 0, 0, 1);
        int joints = b.bytes("VEC4", 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0);
        int weights = b.floats("VEC4", 1, 0, 0, 0, 1, 0, 0, 0, 0.5f, 0.5f, 0, 0);
        int indices = b.shorts(0, 1, 2);
        int bulge = b.floats("VEC3", 0, 0, 0, 0, 0.5f, 0, 0, 0, 0);
        float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        float[] down = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, -1, 0, 1};
        float[] binds = new float[32];
        System.arraycopy(identity, 0, binds, 0, 16);
        System.arraycopy(down, 0, binds, 16, 16);
        int inverseBinds = b.floats("MAT4", binds);
        int times = b.floats("SCALAR", 0, 1);
        float s = (float) Math.sin(Math.PI / 4), c = (float) Math.cos(Math.PI / 4);
        int turns = b.floats("VEC4", 0, 0, 0, 1, 0, 0, s, c);
        int steps = b.floats("VEC3", 0, 0, 0, 1, 0, 0);
        String json = """
                {"asset": {"version": "2.0"}, "scene": 0, "scenes": [{"nodes": [0, 3]}],
                 "nodes": [
                   {"name": "armature", "children": [1]},
                   {"name": "root", "children": [2]},
                   {"name": "child", "translation": [0, 1, 0]},
                   {"name": "body", "mesh": 0, "skin": 0}],
                 "skins": [{"joints": [1, 2], "inverseBindMatrices": %d}],
                 "meshes": [{"name": "body", "weights": [0.25], "extras": {"targetNames": ["bulge"]},
                   "primitives": [{"attributes": {"POSITION": %d, "NORMAL": %d, "JOINTS_0": %d, "WEIGHTS_0": %d},
                     "indices": %d, "targets": [{"POSITION": %d}]}]}],
                 "animations": [{"name": "bend",
                   "channels": [{"sampler": 0, "target": {"node": 2, "path": "rotation"}},
                                {"sampler": 1, "target": {"node": 1, "path": "translation"}}],
                   "samplers": [{"input": %d, "output": %d, "interpolation": "LINEAR"},
                                {"input": %d, "output": %d, "interpolation": "STEP"}]}],
                 %s}
                """.formatted(inverseBinds, positions, normals, joints, weights, indices, bulge, times, turns, times, steps,
                b.json());
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /** One buffer as a data URI, a buffer view and an accessor per array. */
    private static final class Buffer {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final List<String> views = new ArrayList<>(), accessors = new ArrayList<>();

        int floats(String type, float... values) {
            ByteBuffer b = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (float v : values) b.putFloat(v);
            return add(b.array(), type, 5126, values.length / components(type), minMax(type, values));
        }

        int bytes(String type, int... values) {
            byte[] b = new byte[values.length];
            for (int i = 0; i < values.length; i++) b[i] = (byte) values[i];
            return add(b, type, 5121, values.length / components(type), "");
        }

        int shorts(int... values) {
            ByteBuffer b = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int v : values) b.putShort((short) v);
            return add(b.array(), "SCALAR", 5123, values.length, "");
        }

        /** Each view starts four-aligned. */
        private int add(byte[] data, String type, int componentType, int count, String extra) {
            views.add("{\"buffer\": 0, \"byteOffset\": " + bytes.size() + ", \"byteLength\": " + data.length + "}");
            bytes.write(data, 0, data.length);
            while (bytes.size() % 4 != 0) bytes.write(0);
            accessors.add("{\"bufferView\": " + (views.size() - 1) + ", \"componentType\": " + componentType
                    + ", \"count\": " + count + ", \"type\": \"" + type + "\"" + extra + "}");
            return accessors.size() - 1;
        }

        String json() {
            return "\"buffers\": [{\"byteLength\": " + bytes.size() + ", \"uri\": \"data:application/octet-stream;base64,"
                    + Base64.getEncoder().encodeToString(bytes.toByteArray()) + "\"}], \"bufferViews\": [" + String.join(", ", views)
                    + "], \"accessors\": [" + String.join(", ", accessors) + "]";
        }

        private static int components(String type) {
            return switch (type) {
                case "SCALAR" -> 1;
                case "VEC2" -> 2;
                case "VEC3" -> 3;
                case "VEC4" -> 4;
                case "MAT4" -> 16;
                default -> throw new IllegalArgumentException(type);
            };
        }

        /** POSITION needs its bounds; any accessor may carry them. */
        private static String minMax(String type, float[] values) {
            int n = components(type);
            if (n > 4) return "";
            float[] min = new float[n], max = new float[n];
            Arrays.fill(min, Float.MAX_VALUE);
            Arrays.fill(max, -Float.MAX_VALUE);
            for (int i = 0; i < values.length; i++) {
                min[i % n] = Math.min(min[i % n], values[i]);
                max[i % n] = Math.max(max[i % n], values[i]);
            }
            return ", \"min\": " + Arrays.toString(min) + ", \"max\": " + Arrays.toString(max);
        }
    }
}
