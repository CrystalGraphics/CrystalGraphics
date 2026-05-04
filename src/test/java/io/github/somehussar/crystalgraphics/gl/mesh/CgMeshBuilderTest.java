package io.github.somehussar.crystalgraphics.gl.mesh;

import io.github.somehussar.crystalgraphics.api.mesh.CgMeshData;
import io.github.somehussar.crystalgraphics.api.mesh.CgMeshTopology;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CgMeshBuilder}.
 *
 * <p>All tests verify counts and structural invariants of the returned
 * {@link CgMeshData} without uploading to the GPU.</p>
 */
public class CgMeshBuilderTest {

    private static final CgVertexFormat FORMAT = CgVertexFormat.POS3_UV2_COL4UB;

    // ── unitCube ──────────────────────────────────────────────────────────

    @Test
    public void unitCube_returnsNonNull() {
        CgMeshData data = CgMeshBuilder.unitCube(FORMAT);
        assertNotNull(data);
        assertNotNull(data.vertexBuffer());
    }

    @Test
    public void unitCube_vertexCount_is24() {
        CgMeshData data = CgMeshBuilder.unitCube(FORMAT);
        assertEquals(24, data.getVertexCount());
    }

    @Test
    public void unitCube_indexCount_is36() {
        CgMeshData data = CgMeshBuilder.unitCube(FORMAT);
        assertEquals(36, data.indexCount());
    }

    @Test
    public void unitCube_topology_isTriangles() {
        CgMeshData data = CgMeshBuilder.unitCube(FORMAT);
        assertEquals(CgMeshTopology.TRIANGLES, data.topology());
    }

    @Test
    public void unitCube_vertexBufferFlipped() {
        CgMeshData data = CgMeshBuilder.unitCube(FORMAT);
        assertEquals(0, data.vertexBuffer().position());
        assertTrue(data.vertexBuffer().limit() > 0);
    }

    // ── quad2D ────────────────────────────────────────────────────────────

    @Test
    public void quad2D_returnsNonNull() {
        CgMeshData data = CgMeshBuilder.quad2D(FORMAT, 0, 0, 1, 1);
        assertNotNull(data);
    }

    @Test
    public void quad2D_vertexCount_is4() {
        CgMeshData data = CgMeshBuilder.quad2D(FORMAT, 0, 0, 1, 1);
        assertEquals(4, data.getVertexCount());
    }

    @Test
    public void quad2D_indexCount_is6() {
        CgMeshData data = CgMeshBuilder.quad2D(FORMAT, 0, 0, 1, 1);
        assertEquals(6, data.indexCount());
    }

    @Test
    public void quad2D_topology_isTriangles() {
        CgMeshData data = CgMeshBuilder.quad2D(FORMAT, 0, 0, 1, 1);
        assertEquals(CgMeshTopology.TRIANGLES, data.topology());
    }

    // ── uvSphere ──────────────────────────────────────────────────────────

    @Test
    public void uvSphere_vertexCount_matchesFormula() {
        int rings = 16;
        int sectors = 16;
        CgMeshData data = CgMeshBuilder.uvSphere(FORMAT, rings, sectors, 1.0f);
        // (rings+1) * (sectors+1)
        int expected = (rings + 1) * (sectors + 1);
        assertEquals(expected, data.getVertexCount());
    }

    @Test
    public void uvSphere_topology_isTriangles() {
        CgMeshData data = CgMeshBuilder.uvSphere(FORMAT, 16, 16, 1.0f);
        assertEquals(CgMeshTopology.TRIANGLES, data.topology());
    }

    @Test
    public void uvSphere_returnsNonNull() {
        CgMeshData data = CgMeshBuilder.uvSphere(FORMAT, 8, 8, 1.0f);
        assertNotNull(data);
        assertNotNull(data.vertexBuffer());
        assertTrue(data.indexCount() > 0);
    }

    // ── icosahedron ───────────────────────────────────────────────────────

    @Test
    public void icosahedron_subdiv0_has12Vertices() {
        // base icosahedron: 12 shared vertices
        CgMeshData data = CgMeshBuilder.icosahedron(FORMAT, 0);
        assertEquals(12, data.getVertexCount());
    }

    @Test
    public void icosahedron_subdiv0_has60Indices() {
        // 20 faces × 3 indices = 60
        CgMeshData data = CgMeshBuilder.icosahedron(FORMAT, 0);
        assertEquals(60, data.indexCount());
    }

    @Test
    public void icosahedron_topology_isTriangles() {
        CgMeshData data = CgMeshBuilder.icosahedron(FORMAT, 0);
        assertEquals(CgMeshTopology.TRIANGLES, data.topology());
    }

    @Test
    public void icosahedron_subdiv1_hasMoreFaces() {
        CgMeshData sub0 = CgMeshBuilder.icosahedron(FORMAT, 0);
        CgMeshData sub1 = CgMeshBuilder.icosahedron(FORMAT, 1);
        // Each subdivision × 4 → 80 faces, 240 indices
        assertEquals(sub0.indexCount() * 4, sub1.indexCount());
    }

    // ── plane ─────────────────────────────────────────────────────────────

    @Test
    public void plane_4x4_has25Vertices() {
        // subdivisionsX=4, subdivisionsZ=4 → (4+1)×(4+1) = 25 vertices
        CgMeshData data = CgMeshBuilder.plane(FORMAT, 4, 4, 1.0f, 1.0f);
        assertEquals(25, data.getVertexCount());
    }

    @Test
    public void plane_topology_isTriangles() {
        CgMeshData data = CgMeshBuilder.plane(FORMAT, 4, 4, 1.0f, 1.0f);
        assertEquals(CgMeshTopology.TRIANGLES, data.topology());
    }

    @Test
    public void plane_indexCount_correct() {
        // 4×4 subdivisions → 4*4*6 = 96 indices
        CgMeshData data = CgMeshBuilder.plane(FORMAT, 4, 4, 1.0f, 1.0f);
        assertEquals(4 * 4 * 6, data.indexCount());
    }

    @Test
    public void plane_returnsNonNull() {
        CgMeshData data = CgMeshBuilder.plane(FORMAT, 1, 1, 2.0f, 2.0f);
        assertNotNull(data);
        assertNotNull(data.vertexBuffer());
    }

    private static final CgVertexFormat FMT = CgVertexFormat.POS3_UV2_COL4UB;

    @Test(expected = IllegalArgumentException.class)
    public void planeZeroSubdivisionsXThrows() {
        CgMeshBuilder.plane(FMT, 0, 1, 1f, 1f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void planeZeroSubdivisionsZThrows() {
        CgMeshBuilder.plane(FMT, 1, 0, 1f, 1f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void planeNegativeSubdivisionsXThrows() {
        CgMeshBuilder.plane(FMT, -1, 2, 1f, 1f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void uvSphereZeroRingsThrows() {
        CgMeshBuilder.uvSphere(FMT, 0, 8, 1f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void uvSphereZeroSectorsThrows() {
        CgMeshBuilder.uvSphere(FMT, 4, 0, 1f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void uvSphereNegativeRingsThrows() {
        CgMeshBuilder.uvSphere(FMT, -1, 8, 1f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void icosahedronNegativeSubdivisionsThrows() {
        CgMeshBuilder.icosahedron(FMT, -1);
    }

    @Test
    public void icosahedronZeroSubdivisionsOk() {
        CgMeshData data = CgMeshBuilder.icosahedron(FMT, 0);
        org.junit.Assert.assertNotNull(data);
    }

    @Test
    public void planeOneSubdivisionOk() {
        CgMeshData data = CgMeshBuilder.plane(FMT, 1, 1, 1f, 1f);
        org.junit.Assert.assertNotNull(data);
    }
}
