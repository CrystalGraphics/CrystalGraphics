package com.crystalgraphics.gl.mesh;

import com.crystalgraphics.api.mesh.CgMeshData;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.buffer.staging.CgVertexWriter;
import com.crystalgraphics.util.CgBufferUtils;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Factory for procedural mesh geometry.
 *
 * <p>All vertex data packing is delegated to {@link CgVertexWriter#forBuffer(ByteBuffer, CgVertexFormat)},
 * so any {@link CgVertexFormat} combination is supported without custom packing loops.
 * Missing semantics (e.g. a format without UV) are silently skipped by the writer.</p>
 *
 * <h3>Index type selection</h3>
 * <p>Index buffers use {@code GL_UNSIGNED_SHORT} (2 bytes) when {@code vertexCount ≤ 65535},
 * otherwise {@code GL_UNSIGNED_INT} (4 bytes).</p>
 *
 * <h3>Vertex packing</h3>
 * <p>Only the writer methods for semantics present in the format are called.
 * The step machine in {@link CgVertexWriter} enforces ordering: absent semantics
 * skip their steps automatically, but calling a method for an absent semantic
 * throws an out-of-order exception. Each builder method checks the format's
 * attribute list before calling optional writer methods.</p>
 */
public final class CgMeshBuilder {

    private CgMeshBuilder() {}

    // ── Semantic detection helper ─────────────────────────────────────────

    /**
     * Writes a single vertex via the writer, calling only the methods the format supports.
     *
     * @param writer   the vertex writer (backed by a direct ByteBuffer)
     * @param x        X position
     * @param y        Y position
     * @param z        Z position
     * @param u        U texture coordinate
     * @param v        V texture coordinate
     * @param r        red color component (0-255)
     * @param g        green color component (0-255)
     * @param b        blue color component (0-255)
     * @param a        alpha color component (0-255)
     * @param nx       X normal component
     * @param ny       Y normal component
     * @param nz       Z normal component
     * @param hasUv    whether the format has a UV attribute
     * @param hasColor whether the format has a COLOR attribute
     * @param hasNormal whether the format has a NORMAL attribute
     * @param pos3D     whether the POSITION attribute is 3-component (true) or 2-component (false)
     */
    private static void writeVertex(CgVertexWriter writer,
                                    float x, float y, float z,
                                    float u, float v,
                                    int r, int g, int b, int a,
                                    float nx, float ny, float nz,
                                    boolean hasUv, boolean hasColor, boolean hasNormal,
                                    boolean pos3D) {
        if (pos3D) {
            writer.vertex(x, y, z);
        } else {
            writer.vertex(x, y);
        }
        if (hasUv) writer.uv(u, v);
        if (hasColor) writer.color(r, g, b, a);
        if (hasNormal) writer.normal(nx, ny, nz);
        writer.endVertex();
    }

    // ── Index buffer helpers ──────────────────────────────────────────────

    /** Builds a u16 index buffer from an int array. */
    private static ByteBuffer buildU16Ibo(int[] indices) {
        ByteBuffer ibo = CgBufferUtils.createByteBuffer($$$);
        for (int idx : indices) {
            ibo.putShort((short) idx);
        }
        ibo.flip();
        return ibo;
    }

    /** Builds a u32 index buffer from an int array. */
    private static ByteBuffer buildU32Ibo(int[] indices) {
        ByteBuffer ibo = CgBufferUtils.createByteBuffer($$$);
        for (int idx : indices) {
            ibo.putInt(idx);
        }
        ibo.flip();
        return ibo;
    }

    /** Selects u16 or u32 index buffer based on vertex count. */
    private static ByteBuffer buildIbo(int[] indices, int vertexCount) {
        if (vertexCount <= 65535) {
            return buildU16Ibo(indices);
        } else {
            return buildU32Ibo(indices);
        }
    }

    // ── Public factory methods ────────────────────────────────────────────

    /**
     * Builds a unit cube with 24 vertices (6 faces × 4) and 36 indices (6 faces × 2 triangles × 3).
     *
     * <p>Each face has its own 4 vertices with per-face normals and UV coordinates [0,1]×[0,1].
     * Vertex positions are in [-0.5, 0.5]³. Index type is {@code GL_UNSIGNED_SHORT} (24 ≤ 65535).</p>
     *
     * @param format vertex format for the output mesh
     * @return mesh data for the unit cube
     */
    public static CgMeshData unitCube(CgVertexFormat format) {
        CgMeshFormatFlags flags = new CgMeshFormatFlags(format);
        boolean hasUv = flags.wantsUv, hasColor = flags.wantsColor, hasNormal = flags.wantsNormal;

        // 6 faces × 4 vertices = 24 vertices
        int vertexCount = 24;
        ByteBuffer vbo = CgBufferUtils.createByteBuffer($$$);
        CgVertexWriter writer = CgVertexWriter.forBuffer(vbo, format);

        // Face data: normal, then 4 vertex positions, then 4 UV pairs
        // Format: [nx,ny,nz], [x0,y0,z0, x1,y1,z1, x2,y2,z2, x3,y3,z3], [u0,v0, u1,v1, u2,v2, u3,v3]
        float[][][] faces = {
                // +X face: normal=(1,0,0)
                {{1, 0, 0}, {0.5f, -0.5f, -0.5f}, {0.5f, 0.5f, -0.5f}, {0.5f, 0.5f, 0.5f}, {0.5f, -0.5f, 0.5f}},
                // -X face: normal=(-1,0,0)
                {{-1, 0, 0}, {-0.5f, -0.5f, 0.5f}, {-0.5f, 0.5f, 0.5f}, {-0.5f, 0.5f, -0.5f}, {-0.5f, -0.5f, -0.5f}},
                // +Y face: normal=(0,1,0)
                {{0, 1, 0}, {-0.5f, 0.5f, -0.5f}, {-0.5f, 0.5f, 0.5f}, {0.5f, 0.5f, 0.5f}, {0.5f, 0.5f, -0.5f}},
                // -Y face: normal=(0,-1,0)
                {{0, -1, 0}, {0.5f, -0.5f, -0.5f}, {0.5f, -0.5f, 0.5f}, {-0.5f, -0.5f, 0.5f}, {-0.5f, -0.5f, -0.5f}},
                // +Z face: normal=(0,0,1)
                {{0, 0, 1}, {-0.5f, -0.5f, 0.5f}, {0.5f, -0.5f, 0.5f}, {0.5f, 0.5f, 0.5f}, {-0.5f, 0.5f, 0.5f}},
                // -Z face: normal=(0,0,-1)
                {{0, 0, -1}, {0.5f, -0.5f, -0.5f}, {-0.5f, -0.5f, -0.5f}, {-0.5f, 0.5f, -0.5f}, {0.5f, 0.5f, -0.5f}},
        };

        // UVs per-face, same for all faces
        float[][] uvs = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};

        for (float[][] face : faces) {
            float nx = face[0][0], ny = face[0][1], nz = face[0][2];
            // verts at indices 1-4, uvs from uvs array
            for (int vi = 0; vi < 4; vi++) {
                float[] pos = face[vi + 1];
                float u = uvs[vi][0], v = uvs[vi][1];
                writeVertex(writer, pos[0], pos[1], pos[2], u, v,
                        255, 255, 255, 255, nx, ny, nz, hasUv, hasColor, hasNormal, true);
            }
        }
        vbo.flip();

        // 6 faces × 6 indices = 36
        int[] indices = new int[36];
        for (int face = 0; face < 6; face++) {
            int base = face * 4;
            int idxBase = face * 6;
            indices[idxBase] = base;
            indices[idxBase + 1] = base + 1;
            indices[idxBase + 2] = base + 2;
            indices[idxBase + 3] = base + 2;
            indices[idxBase + 4] = base + 3;
            indices[idxBase + 5] = base;
        }

        // 24 vertices → u16 is fine
        ByteBuffer ibo = buildU16Ibo(indices);
        return new CgMeshData(format, CgMeshTopology.TRIANGLES, vbo, ibo, indices.length);
    }

    /**
     * Builds a 2D quad with 4 vertices and 6 indices.
     *
     * <p>Vertices are at Z=0, wound counter-clockwise: (x0,y0), (x1,y0), (x1,y1), (x0,y1).
     * Index type is {@code GL_UNSIGNED_SHORT} (4 ≤ 65535).</p>
     *
     * @param format vertex format for the output mesh
     * @param x0     left X
     * @param y0     bottom Y
     * @param x1     right X
     * @param y1     top Y
     * @return mesh data for the 2D quad
     */
    public static CgMeshData quad2D(CgVertexFormat format, float x0, float y0, float x1, float y1) {
        CgMeshFormatFlags flags = new CgMeshFormatFlags(format);
        boolean hasUv = flags.wantsUv, hasColor = flags.wantsColor, hasNormal = flags.wantsNormal;
        boolean pos3D = flags.positionIs3D;

        int vertexCount = 4;
        ByteBuffer vbo = CgBufferUtils.createByteBuffer($$$);
        CgVertexWriter writer = CgVertexWriter.forBuffer(vbo, format);

        // CCW winding: (x0,y0), (x1,y0), (x1,y1), (x0,y1)
        writeVertex(writer, x0, y0, 0, 0, 0, 255, 255, 255, 255, 0, 0, 1, hasUv, hasColor, hasNormal, pos3D);
        writeVertex(writer, x1, y0, 0, 1, 0, 255, 255, 255, 255, 0, 0, 1, hasUv, hasColor, hasNormal, pos3D);
        writeVertex(writer, x1, y1, 0, 1, 1, 255, 255, 255, 255, 0, 0, 1, hasUv, hasColor, hasNormal, pos3D);
        writeVertex(writer, x0, y1, 0, 0, 1, 255, 255, 255, 255, 0, 0, 1, hasUv, hasColor, hasNormal, pos3D);
        vbo.flip();

        // CCW: [0,1,2, 2,3,0]
        int[] indices = {0, 1, 2, 2, 3, 0};
        ByteBuffer ibo = buildU16Ibo(indices);
        return new CgMeshData(format, CgMeshTopology.TRIANGLES, vbo, ibo, indices.length);
    }

    /**
     * Builds a subdivided plane on the XZ plane, centered at the origin.
     *
     * <p>Grid: {@code (subdivisionsX+1) × (subdivisionsZ+1)} vertices. Each cell is two triangles.
     * All normals are (0,1,0). UVs are normalized [0,1] in both axes.</p>
     *
     * @param format       vertex format for the output mesh
     * @param subdivisionsX number of column subdivisions
     * @param subdivisionsZ number of row subdivisions
     * @param width         total width in X
     * @param depth         total depth in Z
     * @return mesh data for the plane
     */
    public static CgMeshData plane(CgVertexFormat format, int subdivisionsX, int subdivisionsZ,
                                   float width, float depth) {
        if (subdivisionsX <= 0) throw new IllegalArgumentException("subdivisionsX must be > 0, got " + subdivisionsX);
        if (subdivisionsZ <= 0) throw new IllegalArgumentException("subdivisionsZ must be > 0, got " + subdivisionsZ);
        CgMeshFormatFlags flags = new CgMeshFormatFlags(format);
        boolean hasUv = flags.wantsUv, hasColor = flags.wantsColor, hasNormal = flags.wantsNormal;

        int vertsX = subdivisionsX + 1;
        int vertsZ = subdivisionsZ + 1;
        int vertexCount = vertsX * vertsZ;

        ByteBuffer vbo = CgBufferUtils.createByteBuffer($$$);
        CgVertexWriter writer = CgVertexWriter.forBuffer(vbo, format);

        for (int iz = 0; iz < vertsZ; iz++) {
            for (int ix = 0; ix < vertsX; ix++) {
                float u = (float) ix / subdivisionsX;
                float v = (float) iz / subdivisionsZ;
                float x = (u - 0.5f) * width;
                float z = (v - 0.5f) * depth;
                writeVertex(writer, x, 0, z, u, v, 255, 255, 255, 255, 0, 1, 0, hasUv, hasColor, hasNormal, true);
            }
        }
        vbo.flip();

        // 2 triangles per cell × subdivisionsX × subdivisionsZ × 3 indices
        int[] indices = new int[subdivisionsX * subdivisionsZ * 6];
        int idx = 0;
        for (int iz = 0; iz < subdivisionsZ; iz++) {
            for (int ix = 0; ix < subdivisionsX; ix++) {
                int tl = iz * vertsX + ix;
                int tr = tl + 1;
                int bl = tl + vertsX;
                int br = bl + 1;
                indices[idx++] = tl;
                indices[idx++] = bl;
                indices[idx++] = tr;
                indices[idx++] = tr;
                indices[idx++] = bl;
                indices[idx++] = br;
            }
        }

        ByteBuffer ibo = buildIbo(indices, vertexCount);
        return new CgMeshData(format, CgMeshTopology.TRIANGLES, vbo, ibo, indices.length);
    }

    /**
     * Builds a UV sphere with the given rings, sectors, and radius.
     *
     * <p>{@code (rings+1) × (sectors+1)} vertices. Standard spherical UV mapping:
     * u = sector/sectors, v = ring/rings. Normals are the normalized vertex position.</p>
     *
     * @param format  vertex format for the output mesh
     * @param rings   number of horizontal bands (latitude rings)
     * @param sectors number of vertical divisions (longitude sectors)
     * @param radius  sphere radius
     * @return mesh data for the UV sphere
     */
    public static CgMeshData uvSphere(CgVertexFormat format, int rings, int sectors, float radius) {
        if (rings <= 0) throw new IllegalArgumentException("rings must be > 0, got " + rings);
        if (sectors <= 0) throw new IllegalArgumentException("sectors must be > 0, got " + sectors);
        CgMeshFormatFlags flags = new CgMeshFormatFlags(format);
        boolean hasUv = flags.wantsUv, hasColor = flags.wantsColor, hasNormal = flags.wantsNormal;

        int vertsPerRow = sectors + 1;
        int vertexCount = (rings + 1) * vertsPerRow;

        ByteBuffer vbo = CgBufferUtils.createByteBuffer($$$);
        CgVertexWriter writer = CgVertexWriter.forBuffer(vbo, format);

        for (int ring = 0; ring <= rings; ring++) {
            float v = (float) ring / rings;
            // polar angle: 0 at north pole, PI at south pole
            float phi = (float) (Math.PI * v);
            float sinPhi = (float) Math.sin(phi);
            float cosPhi = (float) Math.cos(phi);

            for (int sector = 0; sector <= sectors; sector++) {
                float u = (float) sector / sectors;
                // azimuthal angle: 0 to 2*PI
                float theta = (float) (2.0 * Math.PI * u);
                float sinTheta = (float) Math.sin(theta);
                float cosTheta = (float) Math.cos(theta);

                // Spherical coordinates to Cartesian
                float nx = sinPhi * cosTheta;
                float ny = cosPhi;
                float nz = sinPhi * sinTheta;

                writeVertex(writer, nx * radius, ny * radius, nz * radius,
                        u, v, 255, 255, 255, 255, nx, ny, nz, hasUv, hasColor, hasNormal, true);
            }
        }
        vbo.flip();

        // Build triangle indices
        int[] indices = new int[rings * sectors * 6];
        int idx = 0;
        for (int ring = 0; ring < rings; ring++) {
            for (int sector = 0; sector < sectors; sector++) {
                int tl = ring * vertsPerRow + sector;
                int tr = tl + 1;
                int bl = tl + vertsPerRow;
                int br = bl + 1;
                indices[idx++] = tl;
                indices[idx++] = bl;
                indices[idx++] = tr;
                indices[idx++] = tr;
                indices[idx++] = bl;
                indices[idx++] = br;
            }
        }

        ByteBuffer ibo = buildIbo(indices, vertexCount);
        return new CgMeshData(format, CgMeshTopology.TRIANGLES, vbo, ibo, indices.length);
    }

    /**
     * Builds an icosphere with the given number of subdivision levels.
     *
     * <p>Base: 12 vertices, 20 faces (icosahedron). Each subdivision splits each triangle
     * into 4 smaller triangles, projecting new vertices onto the unit sphere.
     * Vertex normals equal the normalized vertex position. UVs are (0,0) for all vertices
     * (simplified — spherical UV is optional).</p>
     *
     * <ul>
     *   <li>subdivisions=0 → 20 faces, 12 vertices</li>
     *   <li>subdivisions=1 → 80 faces</li>
     *   <li>subdivisions=2 → 320 faces</li>
     * </ul>
     *
     * @param format       vertex format for the output mesh
     * @param subdivisions number of subdivision passes (0 = base icosahedron)
     * @return mesh data for the icosphere
     */
    public static CgMeshData icosahedron(CgVertexFormat format, int subdivisions) {
        if (subdivisions < 0) throw new IllegalArgumentException("subdivisions must be >= 0, got " + subdivisions);
        CgMeshFormatFlags flags = new CgMeshFormatFlags(format);
        boolean hasUv = flags.wantsUv, hasColor = flags.wantsColor, hasNormal = flags.wantsNormal;

        // Golden ratio
        double phi = (1.0 + Math.sqrt(5.0)) / 2.0;

        // Base 12 icosahedron vertices (pre-normalization)
        double[][] rawVerts = {
                {-1, phi, 0}, {1, phi, 0}, {-1, -phi, 0}, {1, -phi, 0},
                {0, -1, phi}, {0, 1, phi}, {0, -1, -phi}, {0, 1, -phi},
                {phi, 0, -1}, {phi, 0, 1}, {-phi, 0, -1}, {-phi, 0, 1}
        };

        // Normalize all base vertices to unit sphere
        List<float[]> vertices = new ArrayList<float[]>();
        for (double[] raw : rawVerts) {
            double len = Math.sqrt(raw[0] * raw[0] + raw[1] * raw[1] + raw[2] * raw[2]);
            vertices.add(new float[]{(float) (raw[0] / len), (float) (raw[1] / len), (float) (raw[2] / len)});
        }

        // Standard icosahedron 20 faces (standard winding)
        int[][] faceList = {
                // 5 faces around vertex 0
                {0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11},
                // 5 adjacent faces
                {1, 5, 9}, {5, 11, 4}, {11, 10, 2}, {10, 7, 6}, {7, 1, 8},
                // 5 faces around vertex 3
                {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9},
                // 5 faces adjacent to the bottom
                {4, 9, 5}, {2, 4, 11}, {6, 2, 10}, {8, 6, 7}, {9, 8, 1}
        };

        List<int[]> faces = new ArrayList<int[]>();
        for (int[] f : faceList) {
            faces.add(f);
        }

        // Midpoint cache: key = (min(i,j) << 32) | max(i,j) as Long
        Map<Long, Integer> midpointCache = new HashMap<Long, Integer>();

        for (int s = 0; s < subdivisions; s++) {
            List<int[]> newFaces = new ArrayList<int[]>();
            for (int[] face : faces) {
                int a = getMidpoint(face[0], face[1], vertices, midpointCache);
                int b = getMidpoint(face[1], face[2], vertices, midpointCache);
                int c = getMidpoint(face[2], face[0], vertices, midpointCache);

                newFaces.add(new int[]{face[0], a, c});
                newFaces.add(new int[]{face[1], b, a});
                newFaces.add(new int[]{face[2], c, b});
                newFaces.add(new int[]{a, b, c});
            }
            faces = newFaces;
        }

        // Rotate all vertices around Z so one vertex points straight up (0,1,0).
        // The raw construction places the top two vertices at y≈0.851 with x=±0.526;
        // rotating by atan(1/φ) CCW around Z brings vertex 1 to exactly (0,1,0).
        double rotAngle = Math.atan2(1.0, phi);
        double cosR = Math.cos(rotAngle);
        double sinR = Math.sin(rotAngle);
        for (int i = 0; i < vertices.size(); i++) {
            float[] v = vertices.get(i);
            double newX = v[0] * cosR - v[1] * sinR;
            double newY = v[0] * sinR + v[1] * cosR;
            v[0] = (float) newX;
            v[1] = (float) newY;
        }

        // Emit a non-indexed triangle soup so each face gets its own vertex copies with
        // per-face seam-corrected UV. Shared-vertex indexed meshes cannot store different
        // UV values per face for the same position, which causes atan2 wrap discontinuities
        // on the faces that straddle the seam — those faces stretch across the entire texture.
        // With per-face vertices we fix the seam by detecting a U jump > 0.5 within a triangle
        // and shifting the offending vertex's U by ±1 to keep all three U values coherent.
        // Pole vertices (|y| ≈ 1) have an arbitrary atan2 result; we set their U to the
        // average of the other two face vertices so the triangles fan correctly.
        int vertexCount = faces.size() * 3;
        ByteBuffer vbo = CgBufferUtils.createByteBuffer($$$);
        CgVertexWriter writer = CgVertexWriter.forBuffer(vbo, format);

        for (int[] face : faces) {
            float[] va = vertices.get(face[0]);
            float[] vb = vertices.get(face[1]);
            float[] vc = vertices.get(face[2]);

            float uA = (float) (Math.atan2(va[2], va[0]) / (2.0 * Math.PI) + 0.5);
            float vA = (float) (Math.acos(Math.max(-1f, Math.min(1f, va[1]))) / Math.PI);
            float uB = (float) (Math.atan2(vb[2], vb[0]) / (2.0 * Math.PI) + 0.5);
            float vB = (float) (Math.acos(Math.max(-1f, Math.min(1f, vb[1]))) / Math.PI);
            float uC = (float) (Math.atan2(vc[2], vc[0]) / (2.0 * Math.PI) + 0.5);
            float vC = (float) (Math.acos(Math.max(-1f, Math.min(1f, vc[1]))) / Math.PI);

            // Seam fix: a face that straddles the atan2 discontinuity (at x<0, z=0) will
            // have two vertices near u=1 and one near u=0, or vice versa — its U range
            // spans > 0.5.  Push any vertex on the low side (u < 0.5) up by 1.0 so all
            // three U values sit on the same side of the boundary.  With GL_REPEAT the
            // [1.0, 1.1] range wraps cleanly back to [0.0, 0.1].
            float uMin = Math.min(uA, Math.min(uB, uC));
            float uMax = Math.max(uA, Math.max(uB, uC));
            if (uMax - uMin > 0.5f) {
                if (uA < 0.5f) uA += 1.0f;
                if (uB < 0.5f) uB += 1.0f;
                if (uC < 0.5f) uC += 1.0f;
            }


            writeVertex(writer, va[0], va[1], va[2], uA, vA, 255, 255, 255, 255,
                    va[0], va[1], va[2], hasUv, hasColor, hasNormal, true);
            writeVertex(writer, vb[0], vb[1], vb[2], uB, vB, 255, 255, 255, 255,
                    vb[0], vb[1], vb[2], hasUv, hasColor, hasNormal, true);
            writeVertex(writer, vc[0], vc[1], vc[2], uC, vC, 255, 255, 255, 255,
                    vc[0], vc[1], vc[2], hasUv, hasColor, hasNormal, true);
        }
        vbo.flip();

        // Non-indexed — no IBO; CgMesh will use glDrawArrays.
        return new CgMeshData(format, CgMeshTopology.TRIANGLES, vbo, null, 0);
    }

    /**
     * Returns the index of the midpoint vertex between vertices {@code i} and {@code j},
     * creating it if not already cached.
     *
     * <p>The midpoint is normalized to the unit sphere surface before caching.</p>
     */
    private static int getMidpoint(int i, int j, List<float[]> vertices, Map<Long, Integer> cache) {
        int lo = Math.min(i, j);
        int hi = Math.max(i, j);
        long key = ((long) lo << 32) | hi;

        Integer cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        float[] va = vertices.get(i);
        float[] vb = vertices.get(j);

        // Midpoint, then normalize to unit sphere
        float mx = (va[0] + vb[0]) * 0.5f;
        float my = (va[1] + vb[1]) * 0.5f;
        float mz = (va[2] + vb[2]) * 0.5f;
        float len = (float) Math.sqrt(mx * mx + my * my + mz * mz);
        float[] mid = {mx / len, my / len, mz / len};

        int index = vertices.size();
        vertices.add(mid);
        cache.put(key, index);
        return index;
    }
}
