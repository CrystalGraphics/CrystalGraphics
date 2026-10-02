package com.crystalgraphics.api.mesh;

import com.crystalgraphics.api.vertex.CgVertexFormat;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;
import java.util.zip.CRC32;

import static org.junit.Assert.*;

/**
 * Every shape is pinned to the bytes the GL-era {@code CgMeshBuilder} made, which it matched byte for byte until that
 * class was deleted (mesh rewrite M5): counts, and a CRC-32 of the vertex bytes and of the indices as little-endian
 * ints.
 */
public class CgMeshShapesTest {

    @Test
    public void everyShapeKeepsTheBytesItWasPinnedTo() {
        CgVertexFormat s = CgVertexFormat.SPATIAL, c = CgVertexFormat.POS3_UV2_COL4UB;
        pin(s, CgMeshShapes::cube, 24, 36, 592579191L, 2079335227L);
        pin(s, m -> CgMeshShapes.quad(m, -1f, -1f, 1f, 1f), 4, 6, 1097077410L, 598887801L);
        pin(s, m -> CgMeshShapes.plane(m, 3, 5, 120f, 7f), 24, 90, 3310651287L, 4126433410L);
        pin(s, m -> CgMeshShapes.sphere(m, 24, 32, 1f), 825, 4608, 2599353313L, 1891855659L);
        pin(s, m -> CgMeshShapes.cylinder(m, 32, 0.6f, 1.6f), 198, 960, 3988531736L, 1393303421L);
        pin(s, m -> CgMeshShapes.capsule(m, 32, 8, 0.5f, 1f), 594, 3264, 383565552L, 2442595604L);
        pin(s, m -> CgMeshShapes.icosphere(m, 3), 3840, 0, 3632896096L, 0L);
        pin(c, CgMeshShapes::cube, 24, 36, 3601217581L, 2079335227L);
        pin(c, m -> CgMeshShapes.quad(m, -1f, -1f, 1f, 1f), 4, 6, 1045212050L, 598887801L);
        pin(c, m -> CgMeshShapes.plane(m, 3, 5, 120f, 7f), 24, 90, 3000861795L, 4126433410L);
        pin(c, m -> CgMeshShapes.sphere(m, 24, 32, 1f), 825, 4608, 3632560298L, 1891855659L);
        pin(c, m -> CgMeshShapes.cylinder(m, 32, 0.6f, 1.6f), 198, 960, 486012920L, 1393303421L);
        pin(c, m -> CgMeshShapes.capsule(m, 32, 8, 0.5f, 1f), 594, 3264, 2671504680L, 2442595604L);
        pin(c, m -> CgMeshShapes.icosphere(m, 3), 3840, 0, 1572712846L, 0L);
        pin(CgVertexFormat.POS2_UV2_COL4UB, m -> CgMeshShapes.quad(m, 0f, 0f, 1f, 1f), 4, 6, 1524397902L, 598887801L);
        pin(s, m -> CgMeshShapes.sphere(m, 300, 300, 1f), 90601, 540000, 1817023363L, 2848149590L);
    }

    private static void pin(CgVertexFormat format, Consumer<CgMeshWriter> shape, int vertexCount, int indexCount,
                            long vertexCrc, long indexCrc) {
        CgMesh mesh = CgMesh.build(format, shape);
        String what = format.getKey() + " " + mesh;
        assertEquals(what, vertexCount, mesh.vertexCount());
        assertEquals(what, indexCount, mesh.indexCount());
        ByteBuffer vertices = ByteBuffer.allocate(vertexCount * format.getStride());
        mesh.readVertices(0, vertexCount, vertices);
        int[] indices = new int[indexCount];
        mesh.readIndices(0, indexCount, indices, 0);
        ByteBuffer indexBytes = ByteBuffer.allocate(indexCount * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i : indices) indexBytes.putInt(i);
        assertEquals(what + " vertex bytes", vertexCrc, crc(vertices.array()));
        assertEquals(what + " indices", indexCrc, crc(indexBytes.array()));
    }

    private static long crc(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return crc.getValue();
    }

    @Test
    public void aSharedShapeIsOneMeshPerFormatAndSizeAndRefusesEdits() {
        CgMesh a = CgMeshShapes.sphere(12, 24), b = CgMeshShapes.sphere(12, 24);
        assertSame(a, b);
        assertNotSame(a, CgMeshShapes.sphere(12, 25));
        assertNotSame(a, CgMeshShapes.sphere(CgVertexFormat.POS3_UV2_COL4UB, 12, 24));
        assertTrue(a.isShared());
        assertThrows(IllegalStateException.class, () -> a.edit(CgMeshShapes::cube));
        assertThrows(IllegalStateException.class, a::release);
    }

    @Test
    public void shapesComposeAsSubmeshesWithTheirOwnIndices() {
        CgMesh lamp = CgMesh.build(CgVertexFormat.SPATIAL, m -> {
            CgMeshShapes.cube(m);
            m.submesh();
            CgMeshShapes.cube(m);
        });
        assertEquals(2, lamp.submeshCount());
        assertEquals(new CgSubmesh(0, 36, 0, 24), lamp.submesh(0));
        assertEquals(new CgSubmesh(36, 36, 24, 24), lamp.submesh(1));
        int[] first = new int[36], second = new int[36];
        lamp.readIndices(0, 36, first, 0);
        lamp.readIndices(36, 36, second, 0);
        assertArrayEquals("each part counts from its own first vertex", first, second);
    }
}
