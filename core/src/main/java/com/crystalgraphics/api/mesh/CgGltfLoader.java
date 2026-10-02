package com.crystalgraphics.api.mesh;

import com.crystalgraphics.api.vertex.CgVertexFormat;
import de.javagl.jgltf.model.AccessorByteData;
import de.javagl.jgltf.model.AccessorData;
import de.javagl.jgltf.model.AccessorFloatData;
import de.javagl.jgltf.model.AccessorIntData;
import de.javagl.jgltf.model.AccessorModel;
import de.javagl.jgltf.model.AccessorShortData;
import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.MaterialModel;
import de.javagl.jgltf.model.MeshModel;
import de.javagl.jgltf.model.MeshPrimitiveModel;
import de.javagl.jgltf.model.io.GltfModelReader;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** glTF and GLB for {@link CgMeshLoader}, through {@code de.javagl:jgltf-model}: a submesh per primitive, mesh by mesh. */
final class CgGltfLoader {

    private static final int POINTS = 0, LINES = 1, LINE_LOOP = 2, LINE_STRIP = 3, TRIANGLES = 4, TRIANGLE_STRIP = 5,
            TRIANGLE_FAN = 6;

    private CgGltfLoader() {
    }

    static CgMeshLoader.Model read(InputStream in, CgVertexFormat format, boolean shared) throws IOException {
        GltfModel model = new GltfModelReader().readWithoutReferences(in);
        List<MeshPrimitiveModel> primitives = new ArrayList<>();
        for (MeshModel mesh : model.getMeshModels()) primitives.addAll(mesh.getMeshPrimitiveModels());
        if (primitives.isEmpty()) throw new IOException("no mesh primitives");

        CgMeshTopology topology = null;
        List<String> materials = new ArrayList<>();
        for (MeshPrimitiveModel p : primitives) {
            Map<String, AccessorModel> attributes = p.getAttributes();
            if (attributes.containsKey("JOINTS_0") || attributes.containsKey("WEIGHTS_0")) {
                throw new UnsupportedOperationException("skinned primitives are not supported");
            }
            if (!attributes.containsKey("POSITION")) throw new IOException("a primitive has no POSITION");
            CgMeshTopology t = topologyOf(p.getMode());
            if (topology != null && t != topology) {
                throw new IllegalArgumentException("primitives of " + topology + " and " + t + " in one file");
            }
            topology = t;
            MaterialModel material = p.getMaterialModel();
            materials.add(material == null ? null : material.getName());
        }

        CgMeshTopology drawn = topology;
        CgMesh mesh = CgMeshLoader.mesh(format, shared, m -> {
            m.topology(drawn);
            for (int i = 0; i < primitives.size(); i++) {
                if (i > 0) m.submesh();
                write(m, primitives.get(i));
            }
        });
        return new CgMeshLoader.Model(mesh, Collections.unmodifiableList(materials));
    }

    private static CgMeshTopology topologyOf(int mode) {
        return switch (mode) {
            case POINTS -> CgMeshTopology.POINTS;
            case LINES, LINE_LOOP, LINE_STRIP -> CgMeshTopology.LINES;
            case TRIANGLES, TRIANGLE_STRIP, TRIANGLE_FAN -> CgMeshTopology.TRIANGLES;
            default -> throw new IllegalArgumentException("glTF primitive mode " + mode);
        };
    }

    private static void write(CgMeshWriter m, MeshPrimitiveModel p) {
        Map<String, AccessorModel> attributes = p.getAttributes();
        AccessorModel position = attributes.get("POSITION");
        AccessorModel uv = attributes.get("TEXCOORD_0"), normal = attributes.get("NORMAL"), color = attributes.get("COLOR_0");
        int count = position.getCount();
        for (int v = 0; v < count; v++) {
            boolean n = normal != null;
            CgMeshLoader.vertex(m,
                    value(position, v, 0, 0f), value(position, v, 1, 0f), value(position, v, 2, 0f),
                    value(uv, v, 0, 0f), value(uv, v, 1, 0f),
                    n ? value(normal, v, 0, 0f) : 0f, n ? value(normal, v, 1, 1f) : 1f, n ? value(normal, v, 2, 0f) : 0f,
                    value(color, v, 0, 1f), value(color, v, 1, 1f), value(color, v, 2, 1f), value(color, v, 3, 1f));
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

    /** The primitive's indices, or 0 to {@code count} - 1 for one drawn in vertex order. */
    private static int[] indices(@Nullable AccessorModel accessor, int count) {
        int[] out = new int[accessor == null ? count : accessor.getCount()];
        if (accessor == null) {
            for (int i = 0; i < count; i++) out[i] = i;
            return out;
        }
        AccessorData data = accessor.getAccessorData();
        for (int i = 0; i < out.length; i++) {
            if (data instanceof AccessorByteData b) out[i] = b.getInt(i, 0);
            else if (data instanceof AccessorShortData s) out[i] = s.getInt(i, 0);
            else if (data instanceof AccessorIntData d) out[i] = d.get(i, 0);
            else throw new IllegalArgumentException("index data of " + data.getComponentType());
        }
        return out;
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
