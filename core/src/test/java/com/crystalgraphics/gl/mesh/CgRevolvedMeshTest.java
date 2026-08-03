package com.crystalgraphics.gl.mesh;

import com.crystalgraphics.api.mesh.CgMeshData;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.*;

/**
 * P6.3.12 — the two shapes the main preview needed that did not exist: cylinder and capsule.
 *
 * <h3>What is actually being asserted</h3>
 * <p>Structure, not pictures. Both are surfaces of revolution built by the same shared sweep, so the
 * things worth pinning are the ones a wrong profile breaks silently: <b>unit-length normals</b> (a
 * denormalised normal is invisible until something lights or reflects with it), <b>positions inside the
 * declared extent</b> (which is what the preview camera is framed against), and <b>indices in range</b>
 * (an out-of-range index is undefined behaviour in the driver, not an exception here).</p>
 *
 * <p>No GL anywhere — {@link CgMeshBuilder} is arithmetic over a {@link ByteBuffer}, which is exactly why
 * it can be tested at all.</p>
 */
public class CgRevolvedMeshTest {

    /** Position + UV + colour + normal, so the normals are actually written and can be read back. */
    private static final CgVertexFormat FORMAT = CgVertexFormat.SPATIAL;

    private static final int SECTORS = 16;
    private static final float EPS = 1e-3f;

    // ── Structure ───────────────────────────────────────────────────────────

    @Test
    public void aCylinderIsATriangleMeshWithMatchingCounts() {
        CgMeshData data = CgMeshBuilder.cylinder(FORMAT, SECTORS, 0.5f, 2f);
        assertEquals(CgMeshTopology.TRIANGLES, data.topology());
        // Six profile rings -> five bands.
        assertEquals(6 * (SECTORS + 1), data.getVertexCount());
        assertEquals(5 * SECTORS * 6, data.indexCount());
        assertEquals("the buffer must be flipped and ready to upload", 0, data.vertexBuffer().position());
    }

    @Test
    public void aCapsuleHasTwoCapsWorthOfRings() {
        int capRings = 6;
        CgMeshData data = CgMeshBuilder.capsule(FORMAT, SECTORS, capRings, 0.5f, 1f);
        // Each hemisphere emits capRings+1 rings, and BOTH emit an equator — that duplicate pair is the
        // cylindrical wall. Welding them would produce a sphere with no body.
        int rings = 2 * (capRings + 1);
        assertEquals(rings * (SECTORS + 1), data.getVertexCount());
        assertEquals((rings - 1) * SECTORS * 6, data.indexCount());
    }

    @Test
    public void degenerateParametersAreRefusedRatherThanProducingRubbish() {
        assertThrows(IllegalArgumentException.class,
                () -> CgMeshBuilder.cylinder(FORMAT, 2, 1f, 1f));
        assertThrows(IllegalArgumentException.class,
                () -> CgMeshBuilder.capsule(FORMAT, 16, 0, 1f, 1f));
    }

    // ── The invariants that fail silently ───────────────────────────────────

    /**
     * Every normal is unit length.
     *
     * <p>The profile supplies {@code (normalY, normalR)} by hand, so an entry that is not on the unit
     * circle produces a mesh that draws perfectly and lights wrongly — the classic silent geometry bug,
     * and the reason each ring's two normal components are asserted together rather than trusted.</p>
     */
    @Test
    public void everyNormalIsUnitLength() {
        assertNormalsAreUnit(CgMeshBuilder.cylinder(FORMAT, SECTORS, 0.5f, 2f));
        assertNormalsAreUnit(CgMeshBuilder.capsule(FORMAT, SECTORS, 6, 0.5f, 1f));
    }

    /** A cylinder of height h and radius r stays inside that box — which is what frames the camera. */
    @Test
    public void aCylinderStaysInsideItsDeclaredExtent() {
        CgMeshData data = CgMeshBuilder.cylinder(FORMAT, SECTORS, 0.6f, 1.6f);
        forEachVertex(data, (x, y, z, nx, ny, nz) -> {
            assertTrue("y within half-height, was " + y, Math.abs(y) <= 0.8f + EPS);
            assertTrue("radius within 0.6, was " + Math.hypot(x, z),
                    Math.hypot(x, z) <= 0.6f + EPS);
        });
    }

    /**
     * A capsule's total height is the cylindrical section plus a radius at each end.
     *
     * <p>Pinned because the parameterisation is the one place this diverges from Unity, whose inspector
     * takes <em>total</em> height and clamps it against the radius. Friendly in an inspector, a trap in
     * an API — so the divergence should fail a test if anyone "fixes" it.</p>
     */
    @Test
    public void aCapsuleIsCylinderPlusTwoCaps() {
        float radius = 0.5f, section = 1f;
        CgMeshData data = CgMeshBuilder.capsule(FORMAT, SECTORS, 6, radius, section);
        float[] extremes = { Float.MAX_VALUE, -Float.MAX_VALUE };
        forEachVertex(data, (x, y, z, nx, ny, nz) -> {
            extremes[0] = Math.min(extremes[0], y);
            extremes[1] = Math.max(extremes[1], y);
        });
        float expected = section * 0.5f + radius;
        assertEquals(-expected, extremes[0], EPS);
        assertEquals(expected, extremes[1], EPS);
    }

    /** An index outside the vertex range is undefined behaviour in the driver, never an exception here. */
    @Test
    public void everyIndexIsInRange() {
        assertIndicesInRange(CgMeshBuilder.cylinder(FORMAT, SECTORS, 0.5f, 2f));
        assertIndicesInRange(CgMeshBuilder.capsule(FORMAT, SECTORS, 6, 0.5f, 1f));
    }

    // ── Reading the buffers back ────────────────────────────────────────────

    private interface VertexVisitor {
        void visit(float x, float y, float z, float nx, float ny, float nz);
    }

    /** SPATIAL is pos3 + uv2 + normal3, tightly packed floats. */
    private static void forEachVertex(CgMeshData data, VertexVisitor visitor) {
        // duplicate() does NOT carry byte order across — it comes back BIG_ENDIAN regardless of what
        // the original was built with, so every absolute getFloat reads a byte-swapped value. Silent,
        // and it produces numbers that look like plausible garbage rather than obvious garbage.
        ByteBuffer vbo = data.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
        int stride = data.format().getStride();
        for (int i = 0; i < data.getVertexCount(); i++) {
            int base = i * stride;
            float x = vbo.getFloat(base);
            float y = vbo.getFloat(base + 4);
            float z = vbo.getFloat(base + 8);
            float nx = vbo.getFloat(base + 20);
            float ny = vbo.getFloat(base + 24);
            float nz = vbo.getFloat(base + 28);
            visitor.visit(x, y, z, nx, ny, nz);
        }
    }

    private static void assertNormalsAreUnit(CgMeshData data) {
        forEachVertex(data, (x, y, z, nx, ny, nz) -> {
            double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
            assertEquals("normal length at (" + x + "," + y + "," + z + ")", 1.0, length, 1e-3);
        });
    }

    private static void assertIndicesInRange(CgMeshData data) {
        ByteBuffer ibo = data.indexBuffer().duplicate().order(ByteOrder.nativeOrder());
        int vertexCount = data.getVertexCount();
        // buildIbo picks 16- or 32-bit indices by vertex count; both meshes here are well under 65536.
        boolean shortIndices = vertexCount <= 65536;
        for (int i = 0; i < data.indexCount(); i++) {
            int index = shortIndices ? (ibo.getShort(i * 2) & 0xFFFF) : ibo.getInt(i * 4);
            assertTrue("index " + index + " outside 0.." + vertexCount, index >= 0 && index < vertexCount);
        }
    }
}
