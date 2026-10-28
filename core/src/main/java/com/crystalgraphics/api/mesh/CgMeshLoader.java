package com.crystalgraphics.api.mesh;

import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgCookedAnimation;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.io.CgIO;
import com.crystalgraphics.util.trace.CgChannels;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Meshes from files: OBJ, glTF, GLB and cooked {@code .cgmesh}, by extension. Every OBJ material group and every glTF
 * primitive is a submesh of one mesh, in file order, so each part draws with its own material.
 *
 * <pre>{@code
 * CgMeshLoader.Model ship = CgMeshLoader.model("mymod:models/ship.glb");   // SPATIAL
 * for (int i = 0; i < ship.mesh().submeshCount(); i++) {
 *     world.draw(ship.mesh(), materialNamed(ship.material(i))).submesh(i).at(x, y, z).submit();
 * }
 *
 * CgMesh rock = CgMeshLoader.load("mymod:models/rock.obj");                          // every part, drawn whole
 * CgMesh points = CgMeshLoader.load("mymod:models/cloud.glb", CgVertexFormat.POS3_UV2_COL4UB);   // a format named
 * }</pre>
 *
 * <p>A skinned file loads as a {@link CgSkeletalMesh}, with its skeleton, morph targets and clips; a file of clips
 * alone as sequences, to play on any skeleton with their bones:</p>
 *
 * <pre>{@code
 * CgSkeletalMesh kurama = CgMeshLoader.skeletal("mymod:models/kurama.glb");     // or kurama.cgmesh, its .cganim beside
 * List<CgAnimSequence> moves = CgMeshLoader.sequences("mymod:anims/moves.glb");  // or moves.cganim
 * }</pre>
 *
 * <ul>
 *   <li>Loaded once per path and format and shared, like a {@link CgMeshShapes} shape: edits and
 *       {@link CgMesh#release()} refuse.</li>
 *   <li>Any thread. Paths resolve through {@link CgIO}, so resource packs and the override directory apply.</li>
 *   <li>Positions, normals, glTF's {@code COLOR_0} and {@code TEXCOORD_0} to {@code TEXCOORD_3} fill what the format has;
 *       colour is white and the second UV the first where the file has none. A format with other attributes needs a
 *       reader of its own.</li>
 *   <li>{@link #model} reads glTF in mesh space: node transforms are not applied, and a skinned primitive stays in its
 *       bind pose. Strips, fans and loops become lists; a file mixing triangles with lines or points, or one with
 *       external buffers, throws.</li>
 *   <li>{@link #skeletal} takes every node with both a mesh and a skin, in node order, a submesh per primitive. Its
 *       skeleton is the skins' joints and their ancestors, one root; two bones of one name, a vertex following more
 *       than four bones, or a clip animating a node that is no bone fails the load, naming it.</li>
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

    /** {@code format} null: {@link CgVertexFormat#SPATIAL}. */
    private record Key(String path, @Nullable CgVertexFormat format) {
    }

    private static final Map<Key, Model> LOADED = new ConcurrentHashMap<>();
    private static final Map<String, CgSkeletalMesh> SKELETAL = new ConcurrentHashMap<>();
    private static final Map<String, List<CgAnimSequence>> SEQUENCES = new ConcurrentHashMap<>();

    private CgMeshLoader() {
    }

    /** The mesh in {@code path}, {@link CgVertexFormat#SPATIAL}. @throws UncheckedIOException if it cannot be read */
    public static CgMesh load(String path) {
        return model(path).mesh();
    }

    /** The mesh in {@code path}, {@link CgVertexFormat#SPATIAL}, with its submeshes' material names. */
    public static Model model(String path) {
        return LOADED.computeIfAbsent(new Key(path, null), k -> read(k.path(), null));
    }

    /** Every mesh made in code, by the id a renderer names it by. */
    private static final Map<String, Supplier<CgMeshLods>> PROVIDED = new ConcurrentHashMap<>();
    private static final Map<String, CgMeshLods> LODS = new ConcurrentHashMap<>();

    /**
     * Names a mesh made in code, so a renderer draws it as it draws a file: Unreal's mesh asset, here made from a few
     * numbers rather than imported.
     *
     * <pre>{@code
     * CgMeshLoader.provide("mymod:models/slash", () -> CgMeshLods.of(CgProceduralMesh.revolve(...).build()));
     * CgVfxSlotRenderer.of("slash", SLASH).material(FLOW).mesh("mymod:models/slash").build();
     * }</pre>
     *
     * <ul>
     *   <li>Made at its first ask ({@link #lods}) and kept; provide it before anything draws it.</li>
     *   <li>One supplier an id: providing another under it throws.</li>
     * </ul>
     */
    public static void provide(String id, Supplier<CgMeshLods> mesh) {
        Supplier<CgMeshLods> had = PROVIDED.putIfAbsent(id, mesh);
        if (had != null && had != mesh) throw new IllegalStateException(id + " is provided already");
    }

    /**
     * The mesh {@code id} names at falling detail: one {@link #provide}d, else the file at that path at every height.
     *
     * @throws UncheckedIOException if it is neither provided nor a file that reads
     */
    public static CgMeshLods lods(String id) {
        return LODS.computeIfAbsent(id, i -> {
            Supplier<CgMeshLods> provided = PROVIDED.get(i);
            return provided != null ? provided.get() : CgMeshLods.of(load(i));
        });
    }

    /** The mesh in {@code path}. @throws UncheckedIOException if it cannot be read */
    public static CgMesh load(String path, CgVertexFormat format) {
        return model(path, format).mesh();
    }

    /** The mesh in {@code path}, with its submeshes' material names. @throws UncheckedIOException if it cannot be read */
    public static Model model(String path, CgVertexFormat format) {
        return LOADED.computeIfAbsent(new Key(path, format), k -> read(k.path(), k.format()));
    }

    /**
     * The skinned mesh in {@code path}: a glTF or GLB, or a cooked {@code .cgmesh} with the {@code .cganim} beside it
     * if there is one. Loaded once and shared.
     *
     * @throws UncheckedIOException if it cannot be read, or holds no skinned mesh
     */
    public static CgSkeletalMesh skeletal(String path) {
        return SKELETAL.computeIfAbsent(path, CgMeshLoader::readSkeletal);
    }

    /**
     * The clips in {@code path}, each track named by the bone it animates: a glTF or GLB (its skinned meshes, if any,
     * not loaded), or a cooked {@code .cganim}. Loaded once and shared.
     *
     * @throws UncheckedIOException if it cannot be read
     */
    public static List<CgAnimSequence> sequences(String path) {
        return SEQUENCES.computeIfAbsent(path, CgMeshLoader::readSequences);
    }

    /**
     * Reads {@code in} as {@code extension} ({@code obj}, {@code gltf} or {@code glb}) into a mesh of its own: not cached.
     * A null {@code format} is {@link CgVertexFormat#SPATIAL}.
     */
    public static Model read(InputStream in, String extension, @Nullable CgVertexFormat format) throws IOException {
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "obj" -> CgObjLoader.read(in, format, false);
            case "gltf", "glb" -> CgGltfLoader.read(in, format, false);
            case "cgmesh" -> cooked(in.readAllBytes(), format, false);
            default -> throw new IllegalArgumentException("not a mesh file type: " + extension);
        };
    }

    private static Model read(String path, @Nullable CgVertexFormat format) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) throw new IllegalArgumentException("no extension to read a mesh by: " + path);
        try (CgTrace.Zone zone = CgTrace.zone(CgChannels.GL, "mesh.load"); InputStream in = CgIO.openStream(path)) {
            if (in == null) throw new IOException("not found: " + path);
            return switch (path.substring(dot + 1).toLowerCase(Locale.ROOT)) {
                case "obj" -> CgObjLoader.read(in, format, true);
                case "gltf", "glb" -> CgGltfLoader.read(in, format, true);
                case "cgmesh" -> cooked(in.readAllBytes(), format, true);
                default -> throw new IllegalArgumentException("not a mesh file type: " + path);
            };
        } catch (IOException e) {
            throw new UncheckedIOException("reading mesh " + path, e);
        }
    }

    /** A cooked static mesh, which must be in the format asked for, if one is. */
    private static Model cooked(byte[] file, @Nullable CgVertexFormat format, boolean shared) {
        Model model = CgCookedMesh.readModel(file, shared);
        if (format != null && !format.equals(model.mesh().format())) {
            throw new IllegalArgumentException("a cooked mesh of " + model.mesh().format().getKey() + ", asked for "
                    + format.getKey());
        }
        return model;
    }

    private static CgSkeletalMesh readSkeletal(String path) {
        try (CgTrace.Zone zone = CgTrace.zone(CgChannels.GL, "mesh.load"); InputStream in = CgIO.openStream(path)) {
            if (in == null) throw new IOException("not found: " + path);
            if (CgCookedMesh.is(path)) {
                String clips = path.substring(0, path.length() - ".cgmesh".length()) + ".cganim";
                List<CgAnimSequence> sequences;
                try (InputStream beside = CgIO.openStream(clips)) {
                    sequences = beside == null ? List.of() : CgCookedAnimation.read(beside.readAllBytes());
                }
                return CgCookedMesh.readSkeletal(in.readAllBytes(), sequences, true);
            }
            return CgGltfLoader.readSkeletal(in, assetName(path), true);
        } catch (IOException e) {
            throw new UncheckedIOException("reading skeletal mesh " + path, e);
        }
    }

    private static List<CgAnimSequence> readSequences(String path) {
        try (InputStream in = CgIO.openStream(path)) {
            if (in == null) throw new IOException("not found: " + path);
            if (CgCookedAnimation.is(path)) return Collections.unmodifiableList(CgCookedAnimation.read(in.readAllBytes()));
            return Collections.unmodifiableList(withSidecarCurves(path, CgGltfLoader.readSequences(in, assetName(path))));
        } catch (IOException e) {
            throw new UncheckedIOException("reading sequences " + path, e);
        }
    }

    /**
     * The clip's float curves from its sidecar ({@code clip.yaml} beside {@code clip.glb}, a {@code mesh} asset's
     * {@code curves}: Unreal's {@code FFloatCurve}s, which a glTF file cannot carry), baked at each sequence's frames.
     */
    private static List<CgAnimSequence> withSidecarCurves(String path, List<CgAnimSequence> sequences) throws IOException {
        int dot = path.lastIndexOf('.');
        String sidecar = path.substring(0, dot) + ".yaml";
        String text = CgIO.loadSource(sidecar);
        if (text == null || !(CgAssetYaml.read(text) instanceof Map<?, ?> tree) || !(tree.get("curves") instanceof List<?> curves)) {
            return sequences;
        }
        List<CgAnimSequence> out = new ArrayList<>(sequences.size());
        for (CgAnimSequence s : sequences) {
            for (Object c : curves) {
                if (!(c instanceof Map<?, ?> curve) || !(curve.get("name") instanceof String name)) continue;
                int drives = switch (String.valueOf(curve.get("drives"))) {
                    case "material" -> CgAnimSequence.DRIVES_MATERIAL;
                    case "morph" -> CgAnimSequence.DRIVES_MORPH;
                    default -> CgAnimSequence.DRIVES_NONE;
                };
                List<?> keys = curve.get("keys") instanceof List<?> k ? k : List.of();
                if (keys.isEmpty() || s.curve(name) >= 0) continue;
                s = s.withCurve(name, bake(keys, s.frameRate(), s.frameCount()), drives);
            }
            out.add(s);
        }
        return out;
    }

    /**
     * A curve's keys ({@code [time, value, interp, arrive?, leave?]}) at each of {@code frames} frames: Unreal's
     * {@code FRichCurve::Eval} (RichCurve.cpp): {@code constant} holds the key before, {@code linear} lerps, {@code cubic}
     * is the Bezier whose inner points lie a third of the gap along the leave and arrive tangents; held past either end.
     */
    static float[] bake(List<?> keys, double rate, int frames) {
        int n = keys.size();
        float[] t = new float[n], v = new float[n], arrive = new float[n], leave = new float[n];
        String[] interp = new String[n];
        for (int i = 0; i < n; i++) {
            List<?> k = (List<?>) keys.get(i);
            t[i] = ((Number) k.get(0)).floatValue();
            v[i] = ((Number) k.get(1)).floatValue();
            interp[i] = k.size() > 2 ? String.valueOf(k.get(2)) : "linear";
            arrive[i] = k.size() > 3 ? ((Number) k.get(3)).floatValue() : 0f;
            leave[i] = k.size() > 4 ? ((Number) k.get(4)).floatValue() : 0f;
        }
        float[] out = new float[frames];
        for (int f = 0; f < frames; f++) {
            float time = (float) (f / rate);
            if (time <= t[0]) {
                out[f] = v[0];
                continue;
            }
            if (time >= t[n - 1]) {
                out[f] = v[n - 1];
                continue;
            }
            int a = 0;
            while (a + 1 < n && t[a + 1] <= time) a++;
            int b = a + 1;
            float gap = t[b] - t[a], alpha = (time - t[a]) / gap;
            out[f] = switch (interp[a]) {
                case "constant" -> v[a];
                case "cubic" -> {
                    float p1 = v[a] + leave[a] * gap / 3f, p2 = v[b] - arrive[b] * gap / 3f, u = 1f - alpha;
                    yield u * u * u * v[a] + 3f * u * u * alpha * p1 + 3f * u * alpha * alpha * p2 + alpha * alpha * alpha * v[b];
                }
                default -> v[a] + (v[b] - v[a]) * alpha;
            };
        }
        return out;
    }

    /** The file's name without folder or extension: what Unreal prefixes the names of a file's unnamed objects with. */
    private static String assetName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf(':')), dot = path.lastIndexOf('.');
        return path.substring(slash + 1, dot > slash ? dot : path.length());
    }

    /** A mesh {@code body} writes: shared when cached, the caller's own otherwise. */
    static CgMesh mesh(CgVertexFormat format, boolean shared, Consumer<CgMeshWriter> body) {
        return shared ? CgMesh.shared(format, body) : CgMesh.build(format, body);
    }

    /**
     * One vertex, every attribute the format has of these. {@code more} holds the further UV sets as u, v pairs from the
     * second, null or a NaN u where the file has no more: the writer repeats the set before.
     */
    static int vertex(CgMeshWriter m, float x, float y, float z, float u, float v, float nx, float ny, float nz,
                      float r, float g, float b, float a, float[] more) {
        m.vertex().position(x, y, z).uv(u, v).normal(nx, ny, nz).color(r, g, b, a);
        if (more != null) for (int set = 1; set <= more.length / 2 && !Float.isNaN(more[set * 2 - 2]); set++) {
            m.uv(set, more[set * 2 - 2], more[set * 2 - 1]);
        }
        return m.end();
    }
}
