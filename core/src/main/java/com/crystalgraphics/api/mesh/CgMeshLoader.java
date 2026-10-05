package com.crystalgraphics.api.mesh;

import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.io.CgIO;
import com.crystalgraphics.util.trace.CgChannels;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Meshes from files: OBJ, glTF and GLB, by extension. Every OBJ material group and every glTF primitive is a submesh
 * of one mesh, in file order, so each part draws with its own material.
 *
 * <pre>{@code
 * CgMeshLoader.Model ship = CgMeshLoader.model("mymod:models/ship.glb", CgVertexFormat.SPATIAL);
 * for (int i = 0; i < ship.mesh().submeshCount(); i++) {
 *     world.draw(ship.mesh(), materialNamed(ship.material(i))).submesh(i).at(x, y, z).submit();
 * }
 *
 * CgMesh rock = CgMeshLoader.load("mymod:models/rock.obj", CgVertexFormat.SPATIAL);   // every part, drawn whole
 * }</pre>
 *
 * <ul>
 *   <li>Loaded once per path and format and shared, like a {@link CgMeshShapes} shape: edits and
 *       {@link CgMesh#release()} refuse.</li>
 *   <li>Any thread. Paths resolve through {@link CgIO}, so resource packs and the override directory apply.</li>
 *   <li>Positions, UVs, normals and glTF's {@code COLOR_0} fill what the format has; colour is white where the file
 *       has none. A format with other attributes needs a reader of its own.</li>
 *   <li>glTF is read in mesh space: node transforms are not applied. Strips, fans and loops become lists; a file
 *       mixing triangles with lines or points, a skinned primitive, or one with external buffers throws.</li>
 * </ul>
 */
public final class CgMeshLoader {

    /** A loaded file: its mesh, and the material each submesh names (null where it names none). */
    public record Model(CgMesh mesh, List<String> materials) {

        /** The material submesh {@code i} names in the file, or null. */
        @Nullable
        public String material(int i) {
            return materials.get(i);
        }
    }

    private record Key(String path, CgVertexFormat format) {
    }

    private static final Map<Key, Model> LOADED = new ConcurrentHashMap<>();

    private CgMeshLoader() {
    }

    /** The mesh in {@code path}. @throws UncheckedIOException if it cannot be read */
    public static CgMesh load(String path, CgVertexFormat format) {
        return model(path, format).mesh();
    }

    /** The mesh in {@code path}, with its submeshes' material names. @throws UncheckedIOException if it cannot be read */
    public static Model model(String path, CgVertexFormat format) {
        return LOADED.computeIfAbsent(new Key(path, format), k -> read(k.path(), k.format()));
    }

    /** Reads {@code in} as {@code extension} ({@code obj}, {@code gltf} or {@code glb}) into a mesh of its own: not cached. */
    public static Model read(InputStream in, String extension, CgVertexFormat format) throws IOException {
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "obj" -> CgObjLoader.read(in, format, false);
            case "gltf", "glb" -> CgGltfLoader.read(in, format, false);
            default -> throw new IllegalArgumentException("not a mesh file type: " + extension);
        };
    }

    private static Model read(String path, CgVertexFormat format) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) throw new IllegalArgumentException("no extension to read a mesh by: " + path);
        try (CgTrace.Zone zone = CgTrace.zone(CgChannels.GL, "mesh.load"); InputStream in = CgIO.openStream(path)) {
            if (in == null) throw new IOException("not found: " + path);
            return switch (path.substring(dot + 1).toLowerCase(Locale.ROOT)) {
                case "obj" -> CgObjLoader.read(in, format, true);
                case "gltf", "glb" -> CgGltfLoader.read(in, format, true);
                default -> throw new IllegalArgumentException("not a mesh file type: " + path);
            };
        } catch (IOException e) {
            throw new UncheckedIOException("reading mesh " + path, e);
        }
    }

    /** A mesh {@code body} writes: shared when cached, the caller's own otherwise. */
    static CgMesh mesh(CgVertexFormat format, boolean shared, Consumer<CgMeshWriter> body) {
        return shared ? CgMesh.shared(format, body) : CgMesh.build(format, body);
    }

    /** One vertex, every attribute the format has of these. */
    static int vertex(CgMeshWriter m, float x, float y, float z, float u, float v, float nx, float ny, float nz,
                      float r, float g, float b, float a) {
        return m.vertex().position(x, y, z).uv(u, v).normal(nx, ny, nz).color(r, g, b, a).end();
    }
}
