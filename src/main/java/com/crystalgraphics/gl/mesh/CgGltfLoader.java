package com.crystalgraphics.gl.mesh;

import de.javagl.jgltf.model.AccessorData;
import de.javagl.jgltf.model.AccessorFloatData;
import de.javagl.jgltf.model.AccessorIntData;
import de.javagl.jgltf.model.AccessorModel;
import de.javagl.jgltf.model.AccessorShortData;
import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.MeshModel;
import de.javagl.jgltf.model.MeshPrimitiveModel;
import de.javagl.jgltf.model.io.GltfModelReader;
import com.crystalgraphics.api.mesh.CgMeshData;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.buffer.staging.CgVertexWriter;
import org.lwjgl.BufferUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

/**
 * Loads glTF / GLB files into {@link CgMeshData} using {@code de.javagl:jgltf-model}.
 *
 * <p>Only static geometry is supported. Skinned meshes (containing JOINTS_0 or
 * WEIGHTS_0 attributes) are rejected with {@link UnsupportedOperationException}.
 * No animation, morphing, or material data is loaded.</p>
 *
 * <h3>Stream reading</h3>
 * <p>Uses {@link GltfModelReader#readWithoutReferences(InputStream)} — the only
 * method that accepts an {@link InputStream} directly. This is suitable for
 * self-contained GLB binaries or embedded glTF that have no external references.</p>
 *
 * <h3>Accessor data extraction</h3>
 * <p>Float accessors (POSITION, TEXCOORD_0, NORMAL) are cast to {@link AccessorFloatData}
 * and read via {@code get(elementIndex, componentIndex)}. Index accessors are cast
 * to {@link AccessorShortData} or {@link AccessorIntData} and read as unsigned values.</p>
 */
public final class CgGltfLoader {

    private CgGltfLoader() {}

    /**
     * Loads the first primitive of the first mesh from the glTF/GLB stream.
     *
     * <p>Uses {@link GltfModelReader#readWithoutReferences(InputStream)} — suitable
     * for self-contained GLB or embedded glTF assets without external references.</p>
     *
     * @param stream input stream of glTF or GLB data
     * @param format vertex format for the output mesh
     * @return loaded mesh data for the first primitive
     * @throws IOException                   on read error
     * @throws UnsupportedOperationException if the primitive contains skinning data
     */
    public static CgMeshData loadFirstPrimitive(InputStream stream, CgVertexFormat format) throws IOException {
        return loadPrimitive(stream, 0, 0, format);
    }

    /**
     * Loads a specific mesh primitive by mesh index and primitive index.
     *
     * <p>Uses {@link GltfModelReader#readWithoutReferences(InputStream)} — suitable
     * for self-contained GLB or embedded glTF assets without external references.</p>
     *
     * @param stream         input stream of glTF or GLB data
     * @param meshIndex      0-based index of the mesh in the glTF scene
     * @param primitiveIndex 0-based index of the primitive within the mesh
     * @param format         vertex format for the output mesh
     * @return loaded mesh data
     * @throws IOException                   on read error
     * @throws UnsupportedOperationException if the primitive contains skinning data (JOINTS_0 / WEIGHTS_0)
     * @throws IllegalArgumentException      if mesh or primitive index is out of range
     */
    public static CgMeshData loadPrimitive(InputStream stream, int meshIndex, int primitiveIndex,
                                            CgVertexFormat format) throws IOException {
        // Use readWithoutReferences since we load from InputStream (no external reference resolution)
        GltfModelReader reader = new GltfModelReader();
        GltfModel model = reader.readWithoutReferences(stream);

        List<MeshModel> meshModels = model.getMeshModels();
        if (meshIndex >= meshModels.size()) {
            throw new IllegalArgumentException(
                    "Mesh index " + meshIndex + " out of range (mesh count: " + meshModels.size() + ")");
        }
        MeshModel mesh = meshModels.get(meshIndex);

        List<MeshPrimitiveModel> primitives = mesh.getMeshPrimitiveModels();
        if (primitiveIndex >= primitives.size()) {
            throw new IllegalArgumentException(
                    "Primitive index " + primitiveIndex + " out of range (primitive count: " + primitives.size() + ")");
        }
        MeshPrimitiveModel primitive = primitives.get(primitiveIndex);

        // Reject skinned meshes
        Map<String, AccessorModel> attributes = primitive.getAttributes();
        if (attributes.containsKey("JOINTS_0") || attributes.containsKey("WEIGHTS_0")) {
            throw new UnsupportedOperationException(
                    "CgGltfLoader does not support skinned meshes (JOINTS_0 / WEIGHTS_0 present)");
        }

        return buildMeshData(primitive, format);
    }

    /**
     * Builds a {@link CgMeshData} from a single glTF mesh primitive.
     *
     * <p>Extracts POSITION, TEXCOORD_0, and NORMAL accessor data as float arrays.
     * Vertex packing uses {@link CgVertexWriter#forBuffer(ByteBuffer, CgVertexFormat)}.
     * Index data is read from the indices accessor supporting u16 and u32 component types.</p>
     */
    private static CgMeshData buildMeshData(MeshPrimitiveModel primitive, CgVertexFormat format) {
        Map<String, AccessorModel> attributes = primitive.getAttributes();

        // Extract position data (required)
        AccessorModel posAccessor = attributes.get("POSITION");
        if (posAccessor == null) {
            throw new IllegalArgumentException("glTF primitive has no POSITION accessor");
        }
        float[] positions = extractFloatArray(posAccessor);

        // Extract UV data (optional)
        AccessorModel uvAccessor = attributes.get("TEXCOORD_0");
        float[] uvs = (uvAccessor != null) ? extractFloatArray(uvAccessor) : new float[0];

        // Extract normal data (optional)
        AccessorModel normalAccessor = attributes.get("NORMAL");
        float[] normals = (normalAccessor != null) ? extractFloatArray(normalAccessor) : new float[0];

        // Detect which semantics the format wants
        CgMeshFormatFlags flags = new CgMeshFormatFlags(format);
        boolean wantsUv = flags.wantsUv;
        boolean wantsColor = flags.wantsColor;
        boolean wantsNormal = flags.wantsNormal;

        int vertexCount = positions.length / 3;
        ByteBuffer vbo = BufferUtils.createByteBuffer(vertexCount * format.getStride());
        CgVertexWriter writer = CgVertexWriter.forBuffer(vbo, format);

        for (int i = 0; i < vertexCount; i++) {
            writer.vertex(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]);

            if (wantsUv) {
                if (uvs.length >= (i * 2 + 2)) {
                    writer.uv(uvs[i * 2], uvs[i * 2 + 1]);
                } else {
                    writer.uv(0f, 0f);
                }
            }

            if (wantsColor) {
                writer.color(255, 255, 255, 255);
            }

            if (wantsNormal) {
                if (normals.length >= (i * 3 + 3)) {
                    writer.normal(normals[i * 3], normals[i * 3 + 1], normals[i * 3 + 2]);
                } else {
                    writer.normal(0f, 1f, 0f);
                }
            }

            writer.endVertex();
        }
        vbo.flip();

        // Build index buffer from indices accessor
        AccessorModel indicesAccessor = primitive.getIndices();
        ByteBuffer ibo;
        int indexCount;
        if (indicesAccessor != null) {
            int[] indices = extractIndexArray(indicesAccessor);
            indexCount = indices.length;

            int maxIdx = 0;
            for (int idx : indices) {
                if (idx > maxIdx) maxIdx = idx;
            }
            if (maxIdx <= 65535) {
                ibo = BufferUtils.createByteBuffer(indexCount * 2);
                for (int idx : indices) {
                    ibo.putShort((short) idx);
                }
            } else {
                ibo = BufferUtils.createByteBuffer(indexCount * 4);
                for (int idx : indices) {
                    ibo.putInt(idx);
                }
            }
            ibo.flip();
        } else {
            // Non-indexed: sequential index buffer
            indexCount = vertexCount;
            ibo = BufferUtils.createByteBuffer(indexCount * 2);
            for (int i = 0; i < indexCount; i++) {
                ibo.putShort((short) i);
            }
            ibo.flip();
        }

        return new CgMeshData(format, CgMeshTopology.TRIANGLES, vbo, ibo, indexCount);
    }

    /**
     * Extracts float data from an accessor model into a plain float array.
     *
     * <p>The accessor data is cast to {@link AccessorFloatData} and read via
     * {@code get(elementIndex, componentIndex)}.</p>
     *
     * @param accessor the float accessor model (POSITION, TEXCOORD_0, or NORMAL)
     * @return flat float array: [e0c0, e0c1, ..., e0cN, e1c0, e1c1, ...]
     */
    private static float[] extractFloatArray(AccessorModel accessor) {
        AccessorData data = accessor.getAccessorData();
        if (!(data instanceof AccessorFloatData)) {
            throw new IllegalArgumentException(
                    "Expected float accessor data, got: " + data.getClass().getSimpleName());
        }
        AccessorFloatData floatData = (AccessorFloatData) data;
        int count = floatData.getNumElements();
        int numComponents = floatData.getNumComponentsPerElement();
        float[] result = new float[count * numComponents];
        for (int i = 0; i < count; i++) {
            for (int c = 0; c < numComponents; c++) {
                result[i * numComponents + c] = floatData.get(i, c);
            }
        }
        return result;
    }

    /**
     * Extracts index data from an accessor model into a plain int array.
     *
     * <p>Supports {@link AccessorShortData} (component type UNSIGNED_SHORT / 5123)
     * and {@link AccessorIntData} (component type UNSIGNED_INT / 5125).
     * Short values are interpreted as unsigned (masked with {@code 0xFFFF}).</p>
     *
     * @param accessor the scalar index accessor model
     * @return int array of index values
     */
    private static int[] extractIndexArray(AccessorModel accessor) {
        AccessorData data = accessor.getAccessorData();
        int count = accessor.getCount();
        int[] result = new int[count];

        if (data instanceof AccessorShortData) {
            // UNSIGNED_SHORT — mask to treat as unsigned
            AccessorShortData shortData = (AccessorShortData) data;
            for (int i = 0; i < count; i++) {
                result[i] = shortData.get(i, 0) & 0xFFFF;
            }
        } else if (data instanceof AccessorIntData) {
            // UNSIGNED_INT
            AccessorIntData intData = (AccessorIntData) data;
            for (int i = 0; i < count; i++) {
                result[i] = intData.get(i, 0);
            }
        } else {
            throw new IllegalArgumentException(
                    "Unsupported index accessor data type: " + data.getClass().getSimpleName());
        }
        return result;
    }
}
