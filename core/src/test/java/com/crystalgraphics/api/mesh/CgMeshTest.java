package com.crystalgraphics.api.mesh;

import com.crystalgraphics.api.vertex.CgAttribType;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.api.vertex.CgVertexSemantic;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

import static org.junit.Assert.*;

/** The writer's rules, bounds, and what an edit tells a reader that last read an older revision. */
public class CgMeshTest {

    private static final CgVertexFormat PACKED = CgVertexFormat.builder("mesh-test-packed")
            .add(CgVertexSemantic.POSITION, "p", 3, CgAttribType.FLOAT)
            .add(CgVertexSemantic.COLOR, "c", 4, CgAttribType.UNSIGNED_BYTE, true)
            .add(CgVertexSemantic.GENERIC, "h", 2, CgAttribType.HALF_FLOAT)
            .add(CgVertexSemantic.GENERIC, "id", 1, CgAttribType.UNSIGNED_INT)
            .add(CgVertexSemantic.GENERIC, "s", 2, CgAttribType.SHORT, true)
            .build();

    private static CgMesh line(int n) {
        return CgMesh.build(CgVertexFormat.SPATIAL, m -> {
            for (int i = 0; i < n; i++) m.vertex().position(i, 0, 0).uv(0, 0).normal(0, 1, 0).end();
        });
    }

    // ── The writer ─────────────────────────────────────────────────────────────

    @Test
    public void aVertexMissingAnAttributeNamesIt() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> CgMesh.build(CgVertexFormat.SPATIAL,
                m -> m.vertex().position(0, 0, 0).uv(0, 0).end()));
        assertTrue(e.getMessage(), e.getMessage().contains("cg_Normal"));
    }

    @Test
    public void attributesGoInAnyOrderAndOnesTheFormatLacksAreDropped() {
        CgMesh a = CgMesh.build(CgVertexFormat.SPATIAL, m -> m.vertex().normal(0, 1, 0).color(0xFF00FF00).uv(1, 2).position(3, 4, 5).end());
        CgMesh b = CgMesh.build(CgVertexFormat.SPATIAL, m -> m.vertex().position(3, 4, 5).uv(1, 2).normal(0, 1, 0).end());
        assertEquals(bytes(a), bytes(b));
    }

    @Test
    public void theWriterRefusesWhatHasNoVertex() {
        assertThrows(IllegalStateException.class, () -> CgMesh.build(CgVertexFormat.SPATIAL, m -> m.position(0, 0, 0)));
        assertThrows(IllegalStateException.class, () -> CgMesh.build(CgVertexFormat.SPATIAL, m -> m.vertex().vertex()));
        assertThrows(IllegalStateException.class, () -> CgMesh.build(CgVertexFormat.SPATIAL, CgMeshWriter::vertex));
    }

    @Test
    public void anIndexPastItsSubmeshIsRefused() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> CgMesh.build(CgVertexFormat.SPATIAL, m -> {
            CgMeshShapes.quad(m, 0, 0, 1, 1);
            m.submesh();
            CgMeshShapes.quad(m, 0, 0, 1, 1);
            m.triangle(0, 1, 4);
        }));
        assertTrue(e.getMessage(), e.getMessage().contains("submesh 1"));
    }

    @Test
    public void eachTypeIsEncodedAsTheGpuReadsIt() {
        CgMesh mesh = CgMesh.build(PACKED, m -> m.vertex()
                .position(1, 2, 3)
                .color(0x80FF0000)
                .set(PACKED.indexOf("h"), 0.5f, 65504f)
                .setInt(PACKED.indexOf("id"), -1)
                .set(PACKED.indexOf("s"), -2f, 0.5f)
                .end());
        ByteBuffer v = bytes(mesh);
        assertEquals(3f, v.getFloat(8), 0f);
        assertEquals((byte) 255, v.get(12));
        assertEquals((byte) 0, v.get(13));
        assertEquals((byte) 128, v.get(15));
        assertEquals(Float.floatToFloat16(0.5f), v.getShort(16));
        assertEquals(Float.floatToFloat16(65504f), v.getShort(18));
        assertEquals(-1, v.getInt(20));
        assertEquals("clamped", (short) -32767, v.getShort(24));
        assertEquals((short) 16384, v.getShort(26));
    }

    @Test
    public void halfFloatsRoundAsTheJdkDoes() {
        Random random = new Random(7);
        float[] edges = {0f, -0f, 1f, 65504f, 65519.99f, 65520f, 1e-8f, 2.98e-8f, 5.96e-8f, 6.1e-5f, 6.0975e-5f,
                Float.MIN_VALUE, Float.MAX_VALUE, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (float f : edges) assertEquals(Float.toString(f), Float.floatToFloat16(f), CgMeshWriter.toHalf(f));
        for (int i = 0; i < 200_000; i++) {
            float f = Float.intBitsToFloat(random.nextInt());
            if (Float.isNaN(f)) continue;
            assertEquals(Float.toString(f), Float.floatToFloat16(f), CgMeshWriter.toHalf(f));
        }
    }

    // ── Bounds ─────────────────────────────────────────────────────────────────

    @Test
    public void boundsFollowThePositionsUnlessStatedAndPadGrowsEither() {
        CgMesh mesh = CgMeshShapes.sphere(8, 8);
        assertArrayEquals(new float[]{-1, -1, -1, 1, 1, 1}, mesh.bounds(new float[6]), 1e-6f);

        CgMesh mine = line(4);
        assertArrayEquals(new float[]{0, 0, 0, 3, 0, 0}, mine.bounds(new float[6]), 0f);
        mine.pad(0.5f);
        assertArrayEquals(new float[]{-0.5f, -0.5f, -0.5f, 3.5f, 0.5f, 0.5f}, mine.bounds(new float[6]), 0f);
        mine.bounds(-10, -10, -10, 10, 10, 10);
        assertArrayEquals(new float[]{-10.5f, -10.5f, -10.5f, 10.5f, 10.5f, 10.5f}, mine.bounds(new float[6]), 0f);
        mine.autoBounds();
        mine.pad(0f);
        mine.writeVertices(1, vertex(-7f));
        assertArrayEquals("raw bytes count too", new float[]{-7, 0, 0, 3, 0, 0}, mine.bounds(new float[6]), 0f);
    }

    // ── Edits and what a reader is told ────────────────────────────────────────

    @Test
    public void aNewReaderAndAReplacedMeshReadEverything() {
        CgMesh mesh = line(4);
        CgMeshChanges changes = new CgMeshChanges();
        assertTrue(mesh.changesSince(0, changes));
        assertTrue(changes.all);
        int seen = changes.revision;
        assertFalse(mesh.changesSince(seen, changes));

        mesh.edit(m -> CgMeshShapes.cube(m));
        assertTrue(mesh.changesSince(seen, changes));
        assertTrue(changes.all);
        assertEquals(24, mesh.vertexCount());
    }

    @Test
    public void partialWritesReportTheUnionOfWhatTheyTouched() {
        CgMesh mesh = line(10);
        CgMeshChanges changes = new CgMeshChanges();
        mesh.changesSince(0, changes);
        int seen = changes.revision;

        mesh.writeVertices(2, vertex(5f));
        mesh.writeVertices(6, vertex(5f));
        assertTrue(mesh.changesSince(seen, changes));
        assertFalse(changes.all);
        assertEquals(2, changes.vertexFrom);
        assertEquals(7, changes.vertexTo);
        assertEquals(changes.indexFrom, changes.indexTo);

        int middle = changes.revision;
        mesh.writeIndices(0, new int[]{0, 1, 2});
        mesh.changesSince(middle, changes);
        assertEquals(changes.vertexFrom, changes.vertexTo);
        assertEquals(0, changes.indexFrom);
        assertEquals(3, changes.indexTo);

        mesh.writeVertices(10, vertex(1f));
        assertEquals("a write past the end grows the mesh", 11, mesh.vertexCount());
    }

    @Test
    public void aReaderFurtherBehindThanTheLogReadsEverything() {
        CgMesh mesh = line(4);
        CgMeshChanges changes = new CgMeshChanges();
        mesh.changesSince(0, changes);
        int seen = changes.revision;
        for (int i = 0; i < 40; i++) mesh.writeVertices(1, vertex(i));
        assertTrue(mesh.changesSince(seen, changes));
        assertTrue(changes.all);
        assertTrue(mesh.changesSince(changes.revision - 3, changes));
        assertFalse(changes.all);
        assertEquals(1, changes.vertexFrom);
        assertEquals(2, changes.vertexTo);
    }

    @Test
    public void anEditReusesTheArraysItSwappedOut() {
        CgMesh mesh = line(64);
        mesh.edit(m -> CgMeshShapes.sphere(m, 4, 4, 1f));
        mesh.edit(4, (m, rings) -> CgMeshShapes.sphere(m, rings, 4, 1f));
        assertEquals(25, mesh.vertexCount());
        assertEquals(96, mesh.indexCount());
        assertArrayEquals(new int[]{0, 96, 0, 25}, mesh.submesh(0, new int[4]));
    }

    @Test
    public void quadsAreSharedIndicesOverNoVertexBytes() {
        CgMesh quads = CgMesh.quads(3);
        assertSame(quads, CgMesh.quads(3));
        assertTrue(quads.isShared());
        assertEquals(12, quads.vertexCount());
        int[] indices = new int[18];
        quads.readIndices(0, 18, indices, 0);
        assertArrayEquals(new int[]{0, 1, 2, 2, 3, 0, 4, 5, 6, 6, 7, 4, 8, 9, 10, 10, 11, 8}, indices);
        assertNull("no positions: a draw states its bounds", quads.bounds(new float[6]));
        assertEquals(0, CgMesh.vertices(64, CgMeshTopology.TRIANGLE_STRIP).indexCount());
        assertNotSame(CgMesh.vertices(64, CgMeshTopology.TRIANGLE_STRIP), CgMesh.vertices(64, CgMeshTopology.LINES));
    }

    private static ByteBuffer vertex(float x) {
        ByteBuffer b = ByteBuffer.allocate(CgVertexFormat.SPATIAL.getStride()).order(ByteOrder.nativeOrder());
        b.putFloat(0, x);
        return b;
    }

    @Test
    public void aGpuOnlyMeshDropsItsBytesOnceStagedAndAnEditWritesThemAgain() {
        CgMesh mesh = CgMesh.build(CgVertexFormat.SPATIAL, CgMesh.Usage.GPU_ONLY, m -> CgMeshShapes.sphere(m, 8, 16, 2f));
        int vertices = mesh.vertexCount(), indices = mesh.indexCount();
        mesh.dropCpuCopy();
        assertEquals(vertices, mesh.vertexCount());
        assertEquals(indices, mesh.indexCount());
        assertArrayEquals(new float[] {-2, -2, -2, 2, 2, 2}, mesh.bounds(new float[6]), 1e-5f);
        assertThrows(IllegalStateException.class, () -> mesh.readVertices(0, 1, ByteBuffer.allocate(64)));
        assertThrows(IllegalStateException.class, () -> mesh.readIndices(0, 1, new int[1], 0));
        assertThrows(IllegalStateException.class, () -> mesh.writeIndices(0, new int[] {0}));

        mesh.edit(CgMeshShapes::cube);
        assertEquals(24, bytes(mesh).remaining() / mesh.format().getStride());

        CgMesh kept = CgMesh.build(CgVertexFormat.SPATIAL, CgMeshShapes::cube);
        kept.dropCpuCopy();                                   // any other usage keeps its copy
        assertEquals(24, bytes(kept).remaining() / kept.format().getStride());
    }

    @Test
    public void reserveKeepsContentsAndRefusesASharedShape() {
        CgMesh trail = CgMesh.build(CgVertexFormat.SPATIAL, CgMesh.Usage.FRAME, m -> {});
        trail.reserve(1024, 4096);
        trail.edit(CgMeshShapes::cube);
        assertEquals(24, trail.vertexCount());
        assertEquals(36, trail.indexCount());
        trail.reserve(8, 8);                                   // never shrinks what an edit wrote
        assertEquals(24, bytes(trail).remaining() / trail.format().getStride());
        assertThrows(IllegalStateException.class, () -> CgMeshShapes.cube().reserve(64, 64));
    }

    private static ByteBuffer bytes(CgMesh mesh) {
        ByteBuffer b = ByteBuffer.allocate(mesh.vertexCount() * mesh.format().getStride()).order(ByteOrder.nativeOrder());
        mesh.readVertices(0, mesh.vertexCount(), b);
        return b.flip();
    }
}
