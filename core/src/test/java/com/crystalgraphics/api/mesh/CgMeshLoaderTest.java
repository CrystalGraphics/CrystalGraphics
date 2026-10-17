package com.crystalgraphics.api.mesh;

import com.crystalgraphics.api.vertex.CgVertexAttribute;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.shape.bounds.CgAABB;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.Assert.*;

/** Mesh rewrite M5: every OBJ material group and every glTF primitive a submesh, with its material's name. */
public class CgMeshLoaderTest {

    private static final String OBJ = """
            v 0 0 0
            v 1 0 0
            v 1 1 0
            v 0 1 0
            v 2 0 0
            usemtl stone
            f 1 2 3 4
            usemtl moss
            f 2 5 3
            """;

    @Test
    public void anObjMaterialGroupIsASubmesh() throws IOException {
        CgMeshLoader.Model model = read(OBJ.getBytes(StandardCharsets.US_ASCII), "obj");
        CgMesh mesh = model.mesh();
        assertEquals(Arrays.asList("stone", "moss"), model.materials());
        assertEquals(2, mesh.submeshCount());
        assertEquals(6, mesh.submesh(0).indexCount());          // the quad, triangulated
        assertEquals(4, mesh.submesh(0).vertexCount());
        assertEquals(3, mesh.submesh(1).indexCount());
        assertEquals(3, mesh.submesh(1).vertexCount());         // vertices 2 and 3 written again for the second group
        assertFalse(mesh.isShared());                           // read() hands over a mesh of its own
    }

    @Test
    public void aGltfPrimitiveIsASubmeshAndAStripBecomesAList() throws IOException {
        CgMeshLoader.Model model = read(gltf().getBytes(StandardCharsets.US_ASCII), "gltf");
        CgMesh mesh = model.mesh();
        assertEquals(Arrays.asList("red", null), model.materials());
        assertEquals(CgMeshTopology.TRIANGLES, mesh.topology());
        assertEquals(2, mesh.submeshCount());
        assertEquals(3, mesh.submesh(0).indexCount());
        assertEquals(6, mesh.submesh(1).indexCount());          // a 4-vertex strip: two triangles
        int[] indices = new int[6];
        mesh.readIndices(mesh.submesh(1).firstIndex(), 6, indices, 0);
        assertArrayEquals(new int[] {0, 1, 2, 2, 1, 3}, indices);   // the second keeps the winding
        CgAABB bounds = mesh.bounds(new CgAABB());
        assertArrayEquals(new double[] {0, 0, 0, 3, 1, 0},
                new double[] {bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ}, 0);
    }

    /** A glTF's second UV set and colour land in SPATIAL; without them, white and the first UV, as Unreal's streams. */
    @Test
    public void aGltfFillsColourAndTheSecondUvOrTheirDefaults() throws IOException {
        CgVertexFormat f = CgVertexFormat.SPATIAL;
        CgVertexAttribute colour = f.getAttribute(f.indexOf("cg_Color")), uv0 = f.getAttribute(f.indexOf("cg_TexCoord0"));
        CgVertexAttribute uv1 = f.getAttribute(f.indexOf("cg_TexCoord1"));
        ByteBuffer vertex = ByteBuffer.allocate(f.getStride()).order(ByteOrder.nativeOrder());

        CgMesh both = CgMeshLoader.read(new ByteArrayInputStream(twoUvGltf(true).getBytes(StandardCharsets.US_ASCII)),
                "gltf", null).mesh();
        assertEquals(f, both.format());
        both.readVertices(1, 1, vertex.clear());
        assertEquals(0.25f, uv1.read(vertex, 0, 0), 0f);
        assertEquals(0.75f, uv1.read(vertex, 0, 1), 0f);
        assertEquals(1f, colour.read(vertex, 0, 0), 0f);             // COLOR_0 red
        assertEquals(0f, colour.read(vertex, 0, 1), 0f);

        CgMesh oneUv = CgMeshLoader.read(new ByteArrayInputStream(twoUvGltf(false).getBytes(StandardCharsets.US_ASCII)),
                "gltf", null).mesh();
        oneUv.readVertices(1, 1, vertex.clear());
        assertEquals(uv0.read(vertex, 0, 0), uv1.read(vertex, 0, 0), 0f);   // the first UV repeated
        assertEquals(1f, uv1.read(vertex, 0, 0), 0f);

        CgMesh obj = CgMeshLoader.read(new ByteArrayInputStream(OBJ.getBytes(StandardCharsets.US_ASCII)), "obj", null).mesh();
        obj.readVertices(0, 1, vertex.clear());
        assertEquals(1f, colour.read(vertex, 0, 1), 0f);             // white where the file has no colour
    }

    @Test
    public void anUnknownExtensionIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> read(new byte[0], "fbx"));
    }

    private static CgMeshLoader.Model read(byte[] bytes, String extension) throws IOException {
        return CgMeshLoader.read(new ByteArrayInputStream(bytes), extension, CgVertexFormat.SPATIAL);
    }

    /** A triangle with two UV sets (the second only with {@code uv1}) and red COLOR_0. */
    private static String twoUvGltf(boolean uv1) {
        float[] data = {0, 0, 0, 1, 0, 0, 0, 1, 0,                 // POSITION
                0, 0, 1, 0, 0, 1,                                  // TEXCOORD_0
                0, 0, 0.25f, 0.75f, 1, 1,                          // TEXCOORD_1
                1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1};               // COLOR_0
        ByteBuffer buffer = ByteBuffer.allocate(data.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : data) buffer.putFloat(f);
        String bytes = Base64.getEncoder().encodeToString(buffer.array());
        return """
                {"asset": {"version": "2.0"},
                 "buffers": [{"byteLength": %d, "uri": "data:application/octet-stream;base64,%s"}],
                 "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 36}, {"buffer": 0, "byteOffset": 36, "byteLength": 24},
                   {"buffer": 0, "byteOffset": 60, "byteLength": 24}, {"buffer": 0, "byteOffset": 84, "byteLength": 48}],
                 "accessors": [
                   {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3", "min": [0, 0, 0], "max": [1, 1, 0]},
                   {"bufferView": 1, "componentType": 5126, "count": 3, "type": "VEC2"},
                   {"bufferView": 2, "componentType": 5126, "count": 3, "type": "VEC2"},
                   {"bufferView": 3, "componentType": 5126, "count": 3, "type": "VEC4"}],
                 "meshes": [{"primitives": [{"attributes": {"POSITION": 0, "TEXCOORD_0": 1, %s"COLOR_0": 3}}]}]}
                """.formatted(data.length * 4, bytes, uv1 ? "\"TEXCOORD_1\": 2, " : "");
    }

    /** A triangle with material "red", then a strip of four vertices with none, in one embedded buffer. */
    private static String gltf() {
        float[] positions = {0, 0, 0, 1, 0, 0, 0, 1, 0, 2, 0, 0, 2, 1, 0, 3, 0, 0, 3, 1, 0};
        ByteBuffer buffer = ByteBuffer.allocate(positions.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : positions) buffer.putFloat(f);
        String data = Base64.getEncoder().encodeToString(buffer.array());
        return """
                {"asset": {"version": "2.0"},
                 "buffers": [{"byteLength": %d, "uri": "data:application/octet-stream;base64,%s"}],
                 "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 36}, {"buffer": 0, "byteOffset": 36, "byteLength": 48}],
                 "accessors": [
                   {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3", "min": [0, 0, 0], "max": [1, 1, 0]},
                   {"bufferView": 1, "componentType": 5126, "count": 4, "type": "VEC3", "min": [2, 0, 0], "max": [3, 1, 0]}],
                 "materials": [{"name": "red"}],
                 "meshes": [{"primitives": [
                   {"attributes": {"POSITION": 0}, "material": 0},
                   {"attributes": {"POSITION": 1}, "mode": 5}]}]}
                """.formatted(positions.length * 4, data);
    }
}
