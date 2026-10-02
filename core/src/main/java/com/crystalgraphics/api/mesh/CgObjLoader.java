package com.crystalgraphics.api.mesh;

import com.crystalgraphics.api.vertex.CgVertexFormat;
import de.javagl.obj.Obj;
import de.javagl.obj.ObjData;
import de.javagl.obj.ObjFace;
import de.javagl.obj.ObjReader;
import de.javagl.obj.ObjUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OBJ for {@link CgMeshLoader}, through {@code de.javagl:obj}: a submesh per material group, in order of first use. */
final class CgObjLoader {

    private CgObjLoader() {
    }

    static CgMeshLoader.Model read(InputStream in, CgVertexFormat format, boolean shared) throws IOException {
        Obj obj = ObjUtils.convertToRenderable(ObjReader.read(in));   // triangles, one index per vertex
        float[] positions = ObjData.getVerticesArray(obj);
        float[] uvs = ObjData.getTexCoordsArray(obj, 2);
        float[] normals = ObjData.getNormalsArray(obj);

        Map<String, List<ObjFace>> parts = new LinkedHashMap<>();
        String material = null;
        for (int f = 0; f < obj.getNumFaces(); f++) {
            ObjFace face = obj.getFace(f);
            String named = obj.getActivatedMaterialGroupName(face);   // only on the face a usemtl precedes
            if (named != null) material = named;
            parts.computeIfAbsent(material, k -> new ArrayList<>()).add(face);
        }

        int[] local = new int[obj.getNumVertices()];
        CgMesh mesh = CgMeshLoader.mesh(format, shared, m -> {
            boolean first = true;
            for (List<ObjFace> faces : parts.values()) {
                if (!first) m.submesh();
                first = false;
                Arrays.fill(local, -1);   // a vertex two groups share is written into each
                for (ObjFace face : faces) {
                    for (int k = 0; k < face.getNumVertices(); k++) {
                        int v = face.getVertexIndex(k);
                        if (local[v] < 0) local[v] = vertex(m, v, positions, uvs, normals);
                        m.index(local[v]);
                    }
                }
            }
        });
        return new CgMeshLoader.Model(mesh, Collections.unmodifiableList(new ArrayList<>(parts.keySet())));
    }

    private static int vertex(CgMeshWriter m, int v, float[] positions, float[] uvs, float[] normals) {
        float u = uvs.length >= v * 2 + 2 ? uvs[v * 2] : 0f, t = uvs.length >= v * 2 + 2 ? uvs[v * 2 + 1] : 0f;
        boolean n = normals.length >= v * 3 + 3;
        return CgMeshLoader.vertex(m, positions[v * 3], positions[v * 3 + 1], positions[v * 3 + 2], u, t,
                n ? normals[v * 3] : 0f, n ? normals[v * 3 + 1] : 1f, n ? normals[v * 3 + 2] : 0f, 1f, 1f, 1f, 1f);
    }
}
