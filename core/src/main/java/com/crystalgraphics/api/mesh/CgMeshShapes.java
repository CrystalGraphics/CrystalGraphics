package com.crystalgraphics.api.mesh;

import com.crystalgraphics.api.vertex.CgVertexFormat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Procedural shapes, two ways. A shared shape is built once per format and size and handed to everyone who asks, so
 * ten systems drawing spheres upload one; size it with the draw's transform. The writer form writes the same shape
 * into a mesh being built, beside anything else in it -- what a shape of your own looks like too.
 *
 * <pre>{@code
 * CgMesh ball = CgMeshShapes.sphere(24, 32);                         // shared: radius 1, SPATIAL
 * CgMesh crate = CgMeshShapes.cube(CgVertexFormat.POS3_UV2_COL4UB);   // shared, in another format
 *
 * CgMesh lamp = CgMesh.build(CgVertexFormat.SPATIAL, m -> {
 *     CgMeshShapes.cylinder(m, 16, 0.1f, 2f);                          // submesh 0: the post
 *     m.submesh();
 *     CgMeshShapes.sphere(m, 12, 24, 0.4f);                            // submesh 1: the globe
 * });
 * }</pre>
 *
 * <ul>
 *   <li>A shared shape refuses edits and {@link CgMesh#release()}: it lives for the process.</li>
 *   <li>Every shape writes the attributes the format has of position, UV, normal and colour (white); a format with
 *       anything else needs its own writer code.</li>
 *   <li>{@link #icosphere} is a triangle soup with no indices. Beside indexed shapes it takes a submesh of its own.</li>
 *   <li>Winding is counter-clockwise seen from outside, as back-face culling expects.</li>
 * </ul>
 */
public final class CgMeshShapes {

    private static final Map<Key, CgMesh> SHARED = new ConcurrentHashMap<>();

    private record Key(String kind, CgVertexFormat format, int a, int b, float x, float y) {
    }

    private CgMeshShapes() {
    }

    private static CgMesh shared(Key key, Consumer<CgMeshWriter> body) {
        return SHARED.computeIfAbsent(key, k -> CgMesh.shared(k.format(), body));
    }

    // ── Shared shapes ──────────────────────────────────────────────────────────

    /** The cube from -0.5 to 0.5 on each axis, a face of four vertices each. */
    public static CgMesh cube() {
        return cube(CgVertexFormat.SPATIAL);
    }

    public static CgMesh cube(CgVertexFormat format) {
        return shared(new Key("cube", format, 0, 0, 0f, 0f), CgMeshShapes::cube);
    }

    /** The square from -0.5 to 0.5 in X and Y at Z 0, facing +Z. */
    public static CgMesh quad() {
        return quad(CgVertexFormat.SPATIAL);
    }

    public static CgMesh quad(CgVertexFormat format) {
        return shared(new Key("quad", format, 0, 0, 0f, 0f), m -> quad(m, -0.5f, -0.5f, 0.5f, 0.5f));
    }

    /** The square from -0.5 to 0.5 in X and Z at Y 0, facing +Y, in {@code cells} by {@code cells} cells. */
    public static CgMesh grid(int cells) {
        return grid(CgVertexFormat.SPATIAL, cells);
    }

    public static CgMesh grid(CgVertexFormat format, int cells) {
        return shared(new Key("grid", format, cells, 0, 0f, 0f), m -> plane(m, cells, cells, 1f, 1f));
    }

    /** The sphere of radius 1, in {@code rings} bands of latitude and {@code sectors} of longitude. */
    public static CgMesh sphere(int rings, int sectors) {
        return sphere(CgVertexFormat.SPATIAL, rings, sectors);
    }

    public static CgMesh sphere(CgVertexFormat format, int rings, int sectors) {
        return shared(new Key("sphere", format, rings, sectors, 0f, 0f), m -> sphere(m, rings, sectors, 1f));
    }

    /** The sphere of radius 1 from an icosahedron subdivided {@code level} times: 20 x 4^level triangles. */
    public static CgMesh icosphere(int level) {
        return icosphere(CgVertexFormat.SPATIAL, level);
    }

    public static CgMesh icosphere(CgVertexFormat format, int level) {
        return shared(new Key("icosphere", format, level, 0, 0f, 0f), m -> icosphere(m, level));
    }

    /** The capped cylinder of radius 0.5 from Y -0.5 to 0.5. */
    public static CgMesh cylinder(int sectors) {
        return cylinder(CgVertexFormat.SPATIAL, sectors);
    }

    public static CgMesh cylinder(CgVertexFormat format, int sectors) {
        return shared(new Key("cylinder", format, sectors, 0, 0f, 0f), m -> cylinder(m, sectors, 0.5f, 1f));
    }

    /**
     * A capsule: a cylinder of {@code height} with hemispheres of {@code radius} on its ends. Shared per size, since
     * a capsule does not scale into another one.
     */
    public static CgMesh capsule(int sectors, int capRings, float radius, float height) {
        return capsule(CgVertexFormat.SPATIAL, sectors, capRings, radius, height);
    }

    public static CgMesh capsule(CgVertexFormat format, int sectors, int capRings, float radius, float height) {
        return shared(new Key("capsule", format, sectors, capRings, radius, height),
                m -> capsule(m, sectors, capRings, radius, height));
    }

    // ── Writer forms ───────────────────────────────────────────────────────────

    private static int vertex(CgMeshWriter m, float x, float y, float z, float u, float v, float nx, float ny, float nz) {
        return m.vertex().position(x, y, z).uv(u, v).normal(nx, ny, nz).color(0xFFFFFFFF).end();
    }

    /** The cube from -0.5 to 0.5: 24 vertices, 36 indices. */
    public static void cube(CgMeshWriter m) {
        float[][][] faces = {
                {{1, 0, 0}, {0.5f, -0.5f, -0.5f}, {0.5f, 0.5f, -0.5f}, {0.5f, 0.5f, 0.5f}, {0.5f, -0.5f, 0.5f}},
                {{-1, 0, 0}, {-0.5f, -0.5f, 0.5f}, {-0.5f, 0.5f, 0.5f}, {-0.5f, 0.5f, -0.5f}, {-0.5f, -0.5f, -0.5f}},
                {{0, 1, 0}, {-0.5f, 0.5f, -0.5f}, {-0.5f, 0.5f, 0.5f}, {0.5f, 0.5f, 0.5f}, {0.5f, 0.5f, -0.5f}},
                {{0, -1, 0}, {0.5f, -0.5f, -0.5f}, {0.5f, -0.5f, 0.5f}, {-0.5f, -0.5f, 0.5f}, {-0.5f, -0.5f, -0.5f}},
                {{0, 0, 1}, {-0.5f, -0.5f, 0.5f}, {0.5f, -0.5f, 0.5f}, {0.5f, 0.5f, 0.5f}, {-0.5f, 0.5f, 0.5f}},
                {{0, 0, -1}, {0.5f, -0.5f, -0.5f}, {-0.5f, -0.5f, -0.5f}, {-0.5f, 0.5f, -0.5f}, {0.5f, 0.5f, -0.5f}},
        };
        float[][] uvs = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (float[][] face : faces) {
            int a = -1;
            for (int corner = 0; corner < 4; corner++) {
                float[] p = face[corner + 1];
                int at = vertex(m, p[0], p[1], p[2], uvs[corner][0], uvs[corner][1], face[0][0], face[0][1], face[0][2]);
                if (corner == 0) a = at;
            }
            m.quad(a, a + 1, a + 2, a + 3);
        }
    }

    /** The rectangle from ({@code x0}, {@code y0}) to ({@code x1}, {@code y1}) at Z 0, facing +Z: 4 vertices, 6 indices. */
    public static void quad(CgMeshWriter m, float x0, float y0, float x1, float y1) {
        int a = vertex(m, x0, y0, 0, 0, 0, 0, 0, 1);
        int b = vertex(m, x1, y0, 0, 1, 0, 0, 0, 1);
        int c = vertex(m, x1, y1, 0, 1, 1, 0, 0, 1);
        int d = vertex(m, x0, y1, 0, 0, 1, 0, 0, 1);
        m.quad(a, b, c, d);
    }

    /** A rectangle on XZ centred on the origin, facing +Y, in {@code cellsX} by {@code cellsZ} cells. */
    public static void plane(CgMeshWriter m, int cellsX, int cellsZ, float width, float depth) {
        if (cellsX <= 0) throw new IllegalArgumentException("cellsX must be > 0, got " + cellsX);
        if (cellsZ <= 0) throw new IllegalArgumentException("cellsZ must be > 0, got " + cellsZ);
        int base = m.next(), row = cellsX + 1;
        for (int iz = 0; iz <= cellsZ; iz++) {
            for (int ix = 0; ix <= cellsX; ix++) {
                float u = (float) ix / cellsX;
                float v = (float) iz / cellsZ;
                vertex(m, (u - 0.5f) * width, 0, (v - 0.5f) * depth, u, v, 0, 1, 0);
            }
        }
        for (int iz = 0; iz < cellsZ; iz++) {
            for (int ix = 0; ix < cellsX; ix++) {
                int tl = base + iz * row + ix, tr = tl + 1, bl = tl + row, br = bl + 1;
                m.triangle(tl, bl, tr).triangle(tr, bl, br);
            }
        }
    }

    /** A UV sphere: {@code (rings + 1) * (sectors + 1)} vertices, the normal its direction from the centre. */
    public static void sphere(CgMeshWriter m, int rings, int sectors, float radius) {
        if (rings <= 0) throw new IllegalArgumentException("rings must be > 0, got " + rings);
        if (sectors <= 0) throw new IllegalArgumentException("sectors must be > 0, got " + sectors);
        int base = m.next(), row = sectors + 1;
        for (int ring = 0; ring <= rings; ring++) {
            float v = (float) ring / rings;
            float phi = (float) (Math.PI * v);
            float sinPhi = (float) Math.sin(phi), cosPhi = (float) Math.cos(phi);
            for (int sector = 0; sector <= sectors; sector++) {
                float u = (float) sector / sectors;
                float theta = (float) (2.0 * Math.PI * u);
                float sinTheta = (float) Math.sin(theta), cosTheta = (float) Math.cos(theta);
                float nx = sinPhi * cosTheta, ny = cosPhi, nz = sinPhi * sinTheta;
                vertex(m, nx * radius, ny * radius, nz * radius, u, v, nx, ny, nz);
            }
        }
        for (int ring = 0; ring < rings; ring++) {
            for (int sector = 0; sector < sectors; sector++) {
                int tl = base + ring * row + sector, tr = tl + 1, bl = tl + row, br = bl + 1;
                m.triangle(tl, tr, bl).triangle(tr, br, bl);
            }
        }
    }

    /**
     * A capped cylinder centred on the origin, on the Y axis. The rim is written twice, with the wall's normal and
     * with the cap's: one shared rim vertex would light the edge as if bevelled.
     */
    public static void cylinder(CgMeshWriter m, int sectors, float radius, float height) {
        if (sectors < 3) throw new IllegalArgumentException("sectors must be >= 3, got " + sectors);
        float half = height * 0.5f;
        List<Ring> profile = List.of(
                new Ring(-half, 0f, -1f, 0f, 0f),
                new Ring(-half, radius, -1f, 0f, 0f),
                new Ring(-half, radius, 0f, 1f, 0f),
                new Ring(half, radius, 0f, 1f, 1f),
                new Ring(half, radius, 1f, 0f, 1f),
                new Ring(half, 0f, 1f, 0f, 1f));
        revolve(m, profile, sectors);
    }

    /**
     * A capsule centred on the origin, on the Y axis: {@code height} is the cylindrical part, so the whole is
     * {@code height + 2 * radius}. {@code capRings} bands in each hemisphere.
     */
    public static void capsule(CgMeshWriter m, int sectors, int capRings, float radius, float height) {
        if (sectors < 3) throw new IllegalArgumentException("sectors must be >= 3, got " + sectors);
        if (capRings < 1) throw new IllegalArgumentException("capRings must be >= 1, got " + capRings);
        float half = height * 0.5f;
        List<Ring> profile = new ArrayList<>();
        for (int i = 0; i <= capRings; i++) {
            double phi = Math.PI - (Math.PI * 0.5) * i / capRings;
            float ny = (float) Math.cos(phi), nr = (float) Math.sin(phi);
            profile.add(new Ring(-half + ny * radius, nr * radius, ny, nr, (float) i / (capRings * 2)));
        }
        // The equator is written by both loops: the pair is the cylindrical wall.
        for (int i = 0; i <= capRings; i++) {
            double phi = (Math.PI * 0.5) - (Math.PI * 0.5) * i / capRings;
            float ny = (float) Math.cos(phi), nr = (float) Math.sin(phi);
            profile.add(new Ring(half + ny * radius, nr * radius, ny, nr, 0.5f + (float) i / (capRings * 2)));
        }
        revolve(m, profile, sectors);
    }

    /** One latitude ring of a surface of revolution; a zero radius collapses it to a point on the axis. */
    private record Ring(float y, float radius, float normalY, float normalR, float v) {
    }

    /** Sweeps a profile around the Y axis and stitches consecutive rings, wound as {@link #plane}. */
    private static void revolve(CgMeshWriter m, List<Ring> rings, int sectors) {
        int base = m.next(), row = sectors + 1;
        for (Ring ring : rings) {
            for (int sector = 0; sector <= sectors; sector++) {
                float u = (float) sector / sectors;
                float theta = (float) (2.0 * Math.PI * u);
                float cosTheta = (float) Math.cos(theta), sinTheta = (float) Math.sin(theta);
                vertex(m, ring.radius() * cosTheta, ring.y(), ring.radius() * sinTheta, u, ring.v(),
                        ring.normalR() * cosTheta, ring.normalY(), ring.normalR() * sinTheta);
            }
        }
        for (int band = 0; band < rings.size() - 1; band++) {
            for (int sector = 0; sector < sectors; sector++) {
                int tl = base + band * row + sector, tr = tl + 1, bl = tl + row, br = bl + 1;
                m.triangle(tl, bl, tr).triangle(tr, bl, br);
            }
        }
    }

    /**
     * The unit icosphere as a triangle soup, three vertices per face, so a face straddling the U seam gets its own
     * UVs; rotated so one vertex points along +Y.
     */
    public static void icosphere(CgMeshWriter m, int level) {
        if (level < 0) throw new IllegalArgumentException("level must be >= 0, got " + level);
        double phi = (1.0 + Math.sqrt(5.0)) / 2.0;
        double[][] raw = {
                {-1, phi, 0}, {1, phi, 0}, {-1, -phi, 0}, {1, -phi, 0},
                {0, -1, phi}, {0, 1, phi}, {0, -1, -phi}, {0, 1, -phi},
                {phi, 0, -1}, {phi, 0, 1}, {-phi, 0, -1}, {-phi, 0, 1}
        };
        List<float[]> vertices = new ArrayList<>();
        for (double[] r : raw) {
            double len = Math.sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]);
            vertices.add(new float[]{(float) (r[0] / len), (float) (r[1] / len), (float) (r[2] / len)});
        }
        int[][] base = {
                {0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11},
                {1, 5, 9}, {5, 11, 4}, {11, 10, 2}, {10, 7, 6}, {7, 1, 8},
                {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9},
                {4, 9, 5}, {2, 4, 11}, {6, 2, 10}, {8, 6, 7}, {9, 8, 1}
        };
        List<int[]> faces = new ArrayList<>(List.of(base));
        Map<Long, Integer> midpoints = new HashMap<>();
        for (int s = 0; s < level; s++) {
            List<int[]> next = new ArrayList<>();
            for (int[] f : faces) {
                int a = midpoint(f[0], f[1], vertices, midpoints);
                int b = midpoint(f[1], f[2], vertices, midpoints);
                int c = midpoint(f[2], f[0], vertices, midpoints);
                next.add(new int[]{f[0], a, c});
                next.add(new int[]{f[1], b, a});
                next.add(new int[]{f[2], c, b});
                next.add(new int[]{a, b, c});
            }
            faces = next;
        }
        double angle = Math.atan2(1.0, phi), cos = Math.cos(angle), sin = Math.sin(angle);
        for (float[] v : vertices) {
            double x = v[0] * cos - v[1] * sin, y = v[0] * sin + v[1] * cos;
            v[0] = (float) x;
            v[1] = (float) y;
        }
        for (int[] f : faces) {
            float[] a = vertices.get(f[0]), b = vertices.get(f[1]), c = vertices.get(f[2]);
            float uA = u(a), uB = u(b), uC = u(c);
            if (Math.max(uA, Math.max(uB, uC)) - Math.min(uA, Math.min(uB, uC)) > 0.5f) {
                if (uA < 0.5f) uA += 1.0f;
                if (uB < 0.5f) uB += 1.0f;
                if (uC < 0.5f) uC += 1.0f;
            }
            vertex(m, a[0], a[1], a[2], uA, v(a), a[0], a[1], a[2]);
            vertex(m, b[0], b[1], b[2], uB, v(b), b[0], b[1], b[2]);
            vertex(m, c[0], c[1], c[2], uC, v(c), c[0], c[1], c[2]);
        }
    }

    private static float u(float[] p) {
        return (float) (Math.atan2(p[2], p[0]) / (2.0 * Math.PI) + 0.5);
    }

    private static float v(float[] p) {
        return (float) (Math.acos(Math.max(-1f, Math.min(1f, p[1]))) / Math.PI);
    }

    /** The vertex halfway between {@code i} and {@code j}, on the unit sphere, made once per edge. */
    private static int midpoint(int i, int j, List<float[]> vertices, Map<Long, Integer> cache) {
        long key = ((long) Math.min(i, j) << 32) | Math.max(i, j);
        Integer cached = cache.get(key);
        if (cached != null) return cached;
        float[] a = vertices.get(i), b = vertices.get(j);
        float mx = (a[0] + b[0]) * 0.5f, my = (a[1] + b[1]) * 0.5f, mz = (a[2] + b[2]) * 0.5f;
        float len = (float) Math.sqrt(mx * mx + my * my + mz * mz);
        vertices.add(new float[]{mx / len, my / len, mz / len});
        cache.put(key, vertices.size() - 1);
        return vertices.size() - 1;
    }
}
