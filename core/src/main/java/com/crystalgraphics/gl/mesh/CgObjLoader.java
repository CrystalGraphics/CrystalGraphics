package com.crystalgraphics.gl.mesh;

import de.javagl.obj.*;
import com.crystalgraphics.api.mesh.CgMeshData;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.buffer.staging.CgVertexWriter;
import com.crystalgraphics.util.CgBufferUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads OBJ files into {@link CgMeshData} using {@code de.javagl:obj}.
 *
 * <p>Uses {@link ObjUtils#convertToRenderable(ReadableObj)} (Obj)} which triangulates faces
 * and produces a single-indexed geometry (one index per position/uv/normal tuple).
 * Vertex packing delegates to {@link CgVertexWriter#forBuffer(ByteBuffer, CgVertexFormat)}
 * — no custom byte-packing loops for vertex data.</p>
 */
public final class CgObjLoader {

    private CgObjLoader() {}

    /**
     * Loads the first (or only) material group from the OBJ stream.
     *
     * @param stream input stream of OBJ text
     * @param format vertex format for the output mesh
     * @return loaded mesh data
     * @throws IOException on read error
     */
    public static CgMeshData load(InputStream stream, CgVertexFormat format) throws IOException {
        Obj obj = ObjReader.read(stream);
        // triangulates + produces single-indexed geometry
        obj = ObjUtils.convertToRenderable(obj);
        return buildMeshData(obj, format);
    }

    /**
     * Loads one {@link CgMeshData} per material group. Groups with zero faces are skipped.
     * Currently returns a single mesh (multi-group split is optional in this implementation).
     *
     * @param stream input stream of OBJ text
     * @param format vertex format for the output mesh
     * @return list of loaded mesh data (one per non-empty group)
     * @throws IOException on read error
     */
    public static List<CgMeshData> loadAll(InputStream stream, CgVertexFormat format) throws IOException {
        Obj obj = ObjReader.read(stream);
        obj = ObjUtils.convertToRenderable(obj);
        List<CgMeshData> result = new ArrayList<CgMeshData>();
        result.add(buildMeshData(obj, format));
        return result;
    }

    /**
     * Builds a {@link CgMeshData} from a renderable (single-indexed, triangulated) OBJ.
     *
     * <p>Vertex packing uses {@link CgVertexWriter#forBuffer(ByteBuffer, CgVertexFormat)}.
     * Only the writer methods for semantics present in the format are called to
     * respect the step machine ordering constraints.</p>
     */
    private static CgMeshData buildMeshData(Obj obj, CgVertexFormat format) {
        // ObjData extracts flattened arrays from the renderable obj
        float[] positions = ObjData.getVerticesArray(obj);
        float[] uvs       = ObjData.getTexCoordsArray(obj, 2);  // empty float[] if absent
        float[] normals   = ObjData.getNormalsArray(obj);        // empty float[] if absent
        // getFaceVertexIndicesArray returns flat int array of triangulated indices
        IntBuffer indexBuf = ObjData.getFaceVertexIndices(obj);
        int[] indices = new int[indexBuf.remaining()];
        indexBuf.get(indices);

        // Detect which semantics the format needs
        CgMeshFormatFlags flags = new CgMeshFormatFlags(format);
        boolean wantsUv = flags.wantsUv;
        boolean wantsColor = flags.wantsColor;
        boolean wantsNormal = flags.wantsNormal;

        int vertexCount = positions.length / 3;
        ByteBuffer vbo = CgBufferUtils.createByteBuffer(vertexCount * format.getStride());
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

        // Index buffer: u16 if maxIdx <= 65535, else u32
        int maxIdx = 0;
        for (int idx : indices) {
            if (idx > maxIdx) maxIdx = idx;
        }
        ByteBuffer ibo;
        if (maxIdx <= 65535) {
            ibo = CgBufferUtils.createByteBuffer(indices.length * 2);
            for (int idx : indices) {
                ibo.putShort((short) idx);
            }
        } else {
            ibo = CgBufferUtils.createByteBuffer(indices.length * 4);
            for (int idx : indices) {
                ibo.putInt(idx);
            }
        }
        ibo.flip();

        return new CgMeshData(format, CgMeshTopology.TRIANGLES, vbo, ibo, indices.length);
    }
}
