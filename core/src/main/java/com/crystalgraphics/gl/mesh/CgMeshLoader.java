package com.crystalgraphics.gl.mesh;

import com.crystalgraphics.api.mesh.CgMeshData;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.util.io.CgIO;

import java.io.IOException;
import java.io.InputStream;

/**
 * Unified mesh loading facade.
 *
 * <p>Dispatches to the appropriate loader based on format:
 * {@link CgObjLoader} for OBJ, {@link CgGltfLoader} for glTF/GLB.</p>
 *
 * <h3>Auto-detection</h3>
 * <p>{@link #load(String, CgVertexFormat)} detects the format from the resource path
 * extension: {@code .obj} → OBJ loader; {@code .gltf} / {@code .glb} → glTF loader.</p>
 */
public final class CgMeshLoader {

    private CgMeshLoader() {}

    /**
     * Loads an OBJ mesh from the given stream.
     *
     * @param stream input stream of OBJ text
     * @param format vertex format for the output mesh
     * @return loaded mesh data
     * @throws IOException on read error
     */
    public static CgMeshData loadObj(InputStream stream, CgVertexFormat format) throws IOException {
        return CgObjLoader.load(stream, format);
    }

    /**
     * Loads the first primitive of a glTF/GLB mesh from the given stream.
     *
     * @param stream input stream of glTF or GLB data
     * @param format vertex format for the output mesh
     * @return loaded mesh data
     * @throws IOException on read error
     */
    public static CgMeshData loadGltf(InputStream stream, CgVertexFormat format) throws IOException {
        return CgGltfLoader.loadFirstPrimitive(stream, format);
    }

    /**
     * Auto-detects the mesh format from the resource path extension and loads the mesh.
     *
     * <ul>
     *   <li>{@code .obj} → {@link CgObjLoader}</li>
     *   <li>{@code .gltf} or {@code .glb} → {@link CgGltfLoader}</li>
     * </ul>
     *
     * <p>The resource is resolved via {@link Class#getResourceAsStream(String)} on
     * {@code CgMeshLoader}'s class loader.</p>
     *
     * @param resourcePath classpath resource path (e.g. {@code "/meshes/cube.obj"})
     * @param format       vertex format for the output mesh
     * @return loaded mesh data
     * @throws IOException              on read error or if the resource is not found
     * @throws IllegalArgumentException if the extension is not recognized
     */
    public static CgMeshData load(String resourcePath, CgVertexFormat format) throws IOException {
        String lower = resourcePath.toLowerCase();
        if (lower.endsWith(".obj")) {
            InputStream stream = CgIO.openStream(resourcePath);
            if (stream == null) throw new IOException("Resource not found: " + resourcePath);
            try {
                return loadObj(stream, format);
            } finally {
                stream.close();
            }
        } else if (lower.endsWith(".gltf") || lower.endsWith(".glb")) {
            InputStream stream = CgIO.openStream(resourcePath);
            if (stream == null) throw new IOException("Resource not found: " + resourcePath);
            try {
                return loadGltf(stream, format);
            } finally {
                stream.close();
            }
        }
        throw new IllegalArgumentException("Unknown mesh format for path: " + resourcePath);
    }
}
