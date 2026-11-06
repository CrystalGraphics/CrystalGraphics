package com.crystalgraphics.api.mesh.geckolib;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.animation.CgSocket;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshWriter;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.format.CgFiles;
import com.crystalgraphics.util.io.CgIO;

/**
 * A GeckoLib model ({@code .geo.json}, Bedrock's geometry) baked into a skinned mesh: each bone a bone of its skeleton,
 * each cube's faces skinned rigidly to its bone, its locators sockets. Ported from GeckoLib 4.8's
 * {@code BakedModelFactory}, {@code GeoQuad} and {@code RenderUtils} (MIT, branch {@code 1.20.1}), so the texels and
 * the pose land where GeckoLib's renderer puts them.
 *
 * <pre>{@code
 * CgGeckoModel saiyan = CgGeckoModel.load("rpgcore:geo/model.geo.json");     // read once and shared
 * CgSkeletalMesh mesh = saiyan.mesh();                                       // SKINNED, one submesh, in blocks
 * for (CgGeckoAnimation a : saiyan.animations("rpgcore:animations/core/styles/default/melee.animation.json")) {
 *     a.sequence().sample(pose, 0.4f);                                      // keyed: GeckoLib's curves exactly
 * }
 * }</pre>
 *
 * <pre>{@code
 * CgGeckoModel parsed = CgGeckoModel.read(text, "mymod:geo/golem.geo.json");  // from text in hand, not cached
 * }</pre>
 *
 * <ul>
 *   <li>A bone's rest is GeckoLib's: its pivot from its parent's, in blocks, its rotation {@code Rz·Ry·Rx} of the file's
 *       degrees with x and y negated. Several top-level bones are given a root of the geometry's identifier.</li>
 *   <li>Animations are this model's: GeckoLib adds a clip's rotations to each bone's rest angles, so a clip converted
 *       for one model is not another's.</li>
 *   <li>What fails at load, naming the file and where: no geometry, a bone whose parent is not in the file, two bones
 *       of one name, a poly mesh or texture mesh (not baked).</li>
 *   <li>A flat cube's normal GeckoLib flips by its sign in the posed space each frame ({@code fixInvertedFlatCube});
 *       the bake flips it once, in the rest pose.</li>
 * </ul>
 */
public final class CgGeckoModel {

    private static final Map<String, CgGeckoModel> LOADED = new ConcurrentHashMap<>();

    private final String file;
    private final CgSkeletalMesh mesh;
    /** Per skeleton bone: its pivot in blocks (x negated, as GeckoLib stores it) and its rest Euler angles, radians. */
    private final float[] pivots, restEuler;
    private final Map<String, Integer> boneIndex;
    private final Map<String, List<CgGeckoAnimation>> animations = new ConcurrentHashMap<>();

    private CgGeckoModel(String file, CgSkeletalMesh mesh, float[] pivots, float[] restEuler, Map<String, Integer> boneIndex) {
        this.file = file;
        this.mesh = mesh;
        this.pivots = pivots;
        this.restEuler = restEuler;
        this.boneIndex = boneIndex;
    }

    /**
     * The model in {@code path}, read through {@code CgIO} once and shared.
     *
     * @throws IllegalArgumentException if it is missing or will not bake, naming the file and the reason
     */
    public static CgGeckoModel load(String path) {
        return LOADED.computeIfAbsent(path, p -> {
            String text = CgIO.loadSource(p);
            if (text == null) throw new IllegalArgumentException(p + ": not found");
            return read(text, p);
        });
    }

    /** The model in {@code text}, {@code file} naming it in errors. Not cached. */
    public static CgGeckoModel read(String text, String file) {
        Object tree = CgFiles.JSON.fromText(CgCodecs.PLAIN, text);
        return new Baker(file).bake(map(tree, file));
    }

    /** The skinned mesh: {@link CgVertexFormat#SKINNED}, one submesh, positions in blocks, its locators as sockets. */
    public CgSkeletalMesh mesh() {
        return mesh;
    }

    public CgSkeleton skeleton() {
        return mesh.skeleton();
    }

    /** The file it was read from. */
    public String file() {
        return file;
    }

    /**
     * The animations in the {@code .animation.json} at {@code path}, converted for this model: read once per model and
     * shared.
     *
     * @throws IllegalArgumentException as {@link #animations(String, String)} does
     */
    public List<CgGeckoAnimation> animations(String path) {
        return animations.computeIfAbsent(path, p -> {
            String text = CgIO.loadSource(p);
            if (text == null) throw new IllegalArgumentException(p + ": not found");
            return animations(text, p);
        });
    }

    /**
     * The animations in {@code text}, each a keyed {@link CgAnimSequence} of this
     * model's bones. Not cached.
     *
     * @throws IllegalArgumentException naming the file, the animation, the bone and the channel: an easing GeckoLib does
     *                                  not name, a molang value, keys out of time order
     */
    public List<CgGeckoAnimation> animations(String text, String file) {
        return CgGeckoAnimations.read(this, text, file);
    }

    int bone(String name) {
        Integer b = boneIndex.get(name);
        return b == null ? -1 : b;
    }

    /** Bone {@code bone}'s pivot, blocks, x negated as GeckoLib stores it: {@code axis} 0 to 2. */
    float pivot(int bone, int axis) {
        return pivots[bone * 3 + axis];
    }

    /** Bone {@code bone}'s rest Euler angle about {@code axis}, radians, GeckoLib's signs. */
    float restEuler(int bone, int axis) {
        return restEuler[bone * 3 + axis];
    }

    /** Bone {@code bone}'s parent in the skeleton, or -1. */
    int parent(int bone) {
        return mesh.skeleton().parent(bone);
    }

    static Map<?, ?> map(Object o, String where) {
        if (!(o instanceof Map<?, ?> m)) throw new IllegalArgumentException(where + ": an object, not " + o);
        return m;
    }

    static List<?> list(Object o, String where) {
        if (!(o instanceof List<?> l)) throw new IllegalArgumentException(where + ": an array, not " + o);
        return l;
    }

    static double num(Object o, String where) {
        if (!(o instanceof Number n)) throw new IllegalArgumentException(where + ": a number, not " + o);
        return n.doubleValue();
    }

    /** {@code o} as three numbers, or zeros where absent (GeckoLib's {@code RenderUtils.arrayToVec}). */
    static double[] vec(Object o, String where) {
        if (o == null) return new double[3];
        List<?> l = list(o, where);
        if (l.size() < 3) throw new IllegalArgumentException(where + ": three numbers, not " + l);
        return new double[]{num(l.get(0), where), num(l.get(1), where), num(l.get(2), where)};
    }

    /** One file's bake: bones parent first, then each bone's cubes into one SKINNED mesh. */
    private static final class Baker {

        /** GeckoLib's {@code Direction} in {@code buildQuads}' order: west, east, north, south, up, down. */
        private static final String[] FACES = {"west", "east", "north", "south", "up", "down"};
        private static final float[][] NORMALS = {{-1, 0, 0}, {1, 0, 0}, {0, 0, -1}, {0, 0, 1}, {0, 1, 0}, {0, -1, 0}};

        private final String file;
        private final List<Map<?, ?>> order = new ArrayList<>();
        private final Map<String, Integer> index = new LinkedHashMap<>();
        private float textureWidth, textureHeight;

        Baker(String file) {
            this.file = file;
        }

        CgGeckoModel bake(Map<?, ?> root) {
            Object geometries = root.get("minecraft:geometry");
            if (geometries == null) throw new IllegalArgumentException(file + ": no minecraft:geometry");
            Map<?, ?> geometry = map(list(geometries, file + ": minecraft:geometry").isEmpty() ? null
                    : ((List<?>) geometries).get(0), file + ": minecraft:geometry[0]");
            Map<?, ?> description = map(geometry.get("description"), file + ": description");
            textureWidth = (float) num(description.get("texture_width"), file + ": texture_width");
            textureHeight = (float) num(description.get("texture_height"), file + ": texture_height");
            String identifier = description.get("identifier") instanceof String s ? s : "geometry";

            // GeometryTree: bones by name, children under parents in file order
            Map<String, Map<?, ?>> byName = new LinkedHashMap<>();
            Map<String, List<Map<?, ?>>> children = new HashMap<>();
            List<Map<?, ?>> tops = new ArrayList<>();
            for (Object o : geometry.get("bones") == null ? List.of() : list(geometry.get("bones"), file + ": bones")) {
                Map<?, ?> bone = map(o, file + ": a bone");
                String name = bone.get("name") instanceof String s ? s : null;
                if (name == null) throw new IllegalArgumentException(file + ": a bone has no name");
                if (byName.put(name, bone) != null) throw new IllegalArgumentException(file + ": two bones named " + name);
                if (bone.get("poly_mesh") != null || bone.get("texture_meshes") != null) {
                    throw new IllegalArgumentException(file + ": bone " + name + ": a poly mesh or texture mesh is not baked");
                }
            }
            for (Map<?, ?> bone : byName.values()) {
                Object parent = bone.get("parent");
                if (parent == null) {
                    tops.add(bone);
                } else {
                    if (!byName.containsKey(String.valueOf(parent))) {
                        throw new IllegalArgumentException(file + ": bone " + bone.get("name") + "'s parent " + parent + " is not in the file");
                    }
                    children.computeIfAbsent(String.valueOf(parent), k -> new ArrayList<>()).add(bone);
                }
            }
            if (tops.isEmpty()) throw new IllegalArgumentException(file + ": no bones");

            CgSkeleton.Builder skeleton = CgSkeleton.builder();
            List<float[]> pivotList = new ArrayList<>(), eulerList = new ArrayList<>();
            boolean synthetic = tops.size() > 1;
            if (synthetic) {
                if (byName.containsKey(identifier)) throw new IllegalArgumentException(file + ": a bone shares the geometry's name " + identifier);
                skeleton.bone(identifier, -1, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1);
                index.put(identifier, 0);
                order.add(null);
                pivotList.add(new float[3]);
                eulerList.add(new float[3]);
            }
            for (Map<?, ?> top : tops) add(top, synthetic ? 0 : -1, children, skeleton, pivotList, eulerList);
            CgSkeleton built = skeleton.build();

            // Each bone's rest frame in model space, to place its cubes and its locators
            int n = order.size();
            Matrix4f[] frames = new Matrix4f[n];
            for (int b = 0; b < n; b++) {
                Matrix4f local = new Matrix4f().translationRotateScale(built.restTranslation(b, new Vector3f()),
                        built.restRotation(b, new Quaternionf()), built.restScale(b, new Vector3f()));
                int p = built.parent(b);
                frames[b] = p < 0 ? local : new Matrix4f(frames[p]).mul(local);
            }

            List<CgSocket> sockets = new ArrayList<>();
            CgMesh mesh = CgMesh.build(CgVertexFormat.SKINNED, m -> {
                for (int b = 0; b < n; b++) {
                    Map<?, ?> bone = order.get(b);
                    if (bone == null) continue;
                    float[] pivot = pivotList.get(b);
                    // GeckoLib draws a bone's cubes under World(bone) = frame(bone) · T(-pivot)
                    Matrix4f world = new Matrix4f(frames[b]).translate(-pivot[0], -pivot[1], -pivot[2]);
                    Double boneInflate = bone.get("inflate") instanceof Number i ? i.doubleValue() : null;
                    for (Object c : bone.get("cubes") == null ? List.of() : list(bone.get("cubes"), where(bone))) {
                        cube(m, map(c, where(bone) + ": a cube"), b, world, boneInflate, where(bone));
                    }
                }
            });
            for (int b = 0; b < n; b++) {
                Map<?, ?> bone = order.get(b);
                if (bone == null || !(bone.get("locators") instanceof Map<?, ?> locators)) continue;
                Matrix4f toBone = new Matrix4f(frames[b]).invert();
                for (Map.Entry<?, ?> e : locators.entrySet()) {
                    Object value = e.getValue() instanceof Map<?, ?> obj ? obj.get("offset") : e.getValue();
                    double[] at = vec(value, where(bone) + ": locator " + e.getKey());
                    Vector3f local = toBone.transformPosition(new Vector3f((float) -at[0] / 16f, (float) at[1] / 16f, (float) at[2] / 16f));
                    sockets.add(CgSocket.of(String.valueOf(e.getKey()), (String) bone.get("name"), local));
                }
            }
            CgSkeletalMesh.Builder skinned = CgSkeletalMesh.builder(mesh, built);
            for (CgSocket s : sockets) skinned.socket(s);
            float[] pivots = new float[n * 3], euler = new float[n * 3];
            for (int b = 0; b < n; b++) {
                System.arraycopy(pivotList.get(b), 0, pivots, b * 3, 3);
                System.arraycopy(eulerList.get(b), 0, euler, b * 3, 3);
            }
            return new CgGeckoModel(file, skinned.build(), pivots, euler, Collections.unmodifiableMap(new HashMap<>(index)));
        }

        private String where(Map<?, ?> bone) {
            return file + ": bone " + bone.get("name");
        }

        /** {@code BakedModelFactory.constructBone}: pivot (-x, y, z) and rotation (-x, -y, z) in radians. */
        private void add(Map<?, ?> bone, int parent, Map<String, List<Map<?, ?>>> children, CgSkeleton.Builder skeleton,
                         List<float[]> pivotList, List<float[]> eulerList) {
            String name = (String) bone.get("name");
            double[] p = vec(bone.get("pivot"), where(bone) + ": pivot"), r = vec(bone.get("rotation"), where(bone) + ": rotation");
            float[] pivot = {(float) -p[0] / 16f, (float) p[1] / 16f, (float) p[2] / 16f};
            float[] euler = {(float) Math.toRadians(-r[0]), (float) Math.toRadians(-r[1]), (float) Math.toRadians(r[2])};
            float[] parentPivot = parent < 0 ? new float[3] : pivotList.get(parent);
            Quaternionf q = new Quaternionf().rotationZ(euler[2]).rotateY(euler[1]).rotateX(euler[0]);
            int at = skeleton.next();
            skeleton.bone(name, parent, pivot[0] - parentPivot[0], pivot[1] - parentPivot[1], pivot[2] - parentPivot[2],
                    q.x, q.y, q.z, q.w, 1, 1, 1);
            index.put(name, at);
            order.add(bone);
            pivotList.add(pivot);
            eulerList.add(euler);
            for (Map<?, ?> child : children.getOrDefault(name, List.of())) add(child, at, children, skeleton, pivotList, eulerList);
        }

        /** {@code constructCube}, {@code buildQuads} and the renderer's {@code renderCube}, one cube's faces into the mesh. */
        private void cube(CgMeshWriter m, Map<?, ?> cube, int bone, Matrix4f world, Double boneInflate, String where) {
            boolean mirror = Boolean.TRUE.equals(cube.get("mirror"));
            double inflate = cube.get("inflate") instanceof Number i ? i.doubleValue() / 16 : boneInflate == null ? 0 : boneInflate / 16;
            double[] size = vec(cube.get("size"), where + ": size"), origin = vec(cube.get("origin"), where + ": origin");
            double[] rotation = vec(cube.get("rotation"), where + ": rotation"), pivot = vec(cube.get("pivot"), where + ": pivot");
            double ox = -(origin[0] + size[0]) / 16, oy = origin[1] / 16, oz = origin[2] / 16;
            double sx = size[0] / 16, sy = size[1] / 16, sz = size[2] / 16;
            // VertexSet: blb, brb, tlb, trb, tlf, trf, blf, brf
            double[][] v = {
                    {ox - inflate, oy - inflate, oz - inflate}, {ox - inflate, oy - inflate, oz + sz + inflate},
                    {ox - inflate, oy + sy + inflate, oz - inflate}, {ox - inflate, oy + sy + inflate, oz + sz + inflate},
                    {ox + sx + inflate, oy + sy + inflate, oz - inflate}, {ox + sx + inflate, oy + sy + inflate, oz + sz + inflate},
                    {ox + sx + inflate, oy - inflate, oz - inflate}, {ox + sx + inflate, oy - inflate, oz + sz + inflate}};
            // renderCube: about the cube's pivot (-x, y, z), rotated z, y, x by (-x, -y, z) radians
            Matrix4f cubeToModel = new Matrix4f(world)
                    .translate((float) -pivot[0] / 16f, (float) pivot[1] / 16f, (float) pivot[2] / 16f)
                    .rotateZ((float) Math.toRadians(rotation[2])).rotateY((float) Math.toRadians(-rotation[1]))
                    .rotateX((float) Math.toRadians(-rotation[0]))
                    .translate((float) pivot[0] / 16f, (float) -pivot[1] / 16f, (float) -pivot[2] / 16f);
            Object uvs = cube.get("uv");
            boolean box = uvs instanceof List<?>;
            for (int face = 0; face < 6; face++) {
                float[] uv = box ? boxUv(list(uvs, where + ": uv"), size, face) : faceUv(uvs, face, where);
                if (uv == null) continue;
                int[] corners = corners(face, box, mirror);
                float u = uv[0] / textureWidth, vv = uv[1] / textureHeight;
                float uWidth = (uv[0] + uv[2]) / textureWidth, vHeight = (uv[1] + uv[3]) / textureHeight;
                float[] normal = NORMALS[face].clone();
                // GeoQuad.build: unmirrored, u runs from its far edge
                if (!mirror) {
                    float t = uWidth;
                    uWidth = u;
                    u = t;
                } else {
                    normal[0] = -normal[0];
                }
                float[] rotated = rotateUvs((int) uv[4], u, vv, uWidth, vHeight);
                Vector3f n = cubeToModel.transformDirection(new Vector3f(normal[0], normal[1], normal[2])).normalize();
                // fixInvertedFlatCube, once, in the rest pose
                if (n.x < 0 && (size[1] == 0 || size[2] == 0)) n.x = -n.x;
                if (n.y < 0 && (size[0] == 0 || size[2] == 0)) n.y = -n.y;
                if (n.z < 0 && (size[0] == 0 || size[1] == 0)) n.z = -n.z;
                int[] ids = new int[4];
                for (int k = 0; k < 4; k++) {
                    double[] p = v[corners[k]];
                    Vector4f at = cubeToModel.transform(new Vector4f((float) p[0], (float) p[1], (float) p[2], 1f));
                    ids[k] = m.vertex().position(at.x, at.y, at.z).normal(n.x, n.y, n.z).uv(rotated[k * 2], rotated[k * 2 + 1])
                            .color(0xFFFFFFFF).bone(bone, 1f).end();
                }
                m.triangle(ids[0], ids[1], ids[2]);
                m.triangle(ids[2], ids[3], ids[0]);
            }
        }

        /** {@code VertexSet.verticesForQuad}: a face's four corners, swapped by mirroring. */
        private static int[] corners(int face, boolean box, boolean mirror) {
            int[] west = {3, 2, 0, 1}, east = {4, 5, 7, 6}, north = {2, 4, 6, 0}, south = {5, 3, 1, 7}, up = {3, 5, 4, 2}, down = {0, 6, 7, 1};
            switch (face) {
                case 0: return mirror ? east : west;
                case 1: return mirror ? west : east;
                case 2: return north;
                case 3: return south;
                case 4: return mirror && !box ? down : up;
                default: return mirror && !box ? up : down;
            }
        }

        /** Box UV ({@code buildQuad}): u, v, width, height, rotation, from the cube's floored size. */
        private static float[] boxUv(List<?> uv, double[] size, int face) {
            double u = num(uv.get(0), "uv"), v = num(uv.get(1), "uv");
            double x = Math.floor(size[0]), y = Math.floor(size[1]), z = Math.floor(size[2]);
            switch (face) {
                case 0: return new float[]{(float) (u + z + x), (float) (v + z), (float) z, (float) y, 0};
                case 1: return new float[]{(float) u, (float) (v + z), (float) z, (float) y, 0};
                case 2: return new float[]{(float) (u + z), (float) (v + z), (float) x, (float) y, 0};
                case 3: return new float[]{(float) (u + z + x + z), (float) (v + z), (float) x, (float) y, 0};
                case 4: return new float[]{(float) (u + z), (float) v, (float) x, (float) z, 0};
                default: return new float[]{(float) (u + z + x), (float) (v + z), (float) x, (float) -z, 0};
            }
        }

        /** A per-face UV ({@code FaceUV}), or null where the face is not drawn. */
        private static float[] faceUv(Object uvs, int face, String where) {
            Object f = map(uvs, where + ": uv").get(FACES[face]);
            if (f == null) return null;
            Map<?, ?> faceUv = map(f, where + ": uv " + FACES[face]);
            double[] uv = vec2(faceUv.get("uv"), where), size = vec2(faceUv.get("uv_size"), where);
            int rotation = faceUv.get("uv_rotation") instanceof Number r ? r.intValue() : 0;
            return new float[]{(float) uv[0], (float) uv[1], (float) size[0], (float) size[1], (rotation % 360) / 90};
        }

        private static double[] vec2(Object o, String where) {
            if (o == null) return new double[2];
            List<?> l = list(o, where);
            return new double[]{num(l.get(0), where), num(l.get(1), where)};
        }

        /** {@code FaceUV.Rotation.rotateUvs}. */
        private static float[] rotateUvs(int rotation, float u, float v, float uWidth, float vHeight) {
            switch (rotation) {
                case 1: return new float[]{uWidth, v, uWidth, vHeight, u, vHeight, u, v};
                case 2: return new float[]{uWidth, vHeight, u, vHeight, u, v, uWidth, v};
                case 3: return new float[]{u, vHeight, u, v, uWidth, v, uWidth, vHeight};
                default: return new float[]{u, v, uWidth, v, uWidth, vHeight, u, vHeight};
            }
        }
    }
}
