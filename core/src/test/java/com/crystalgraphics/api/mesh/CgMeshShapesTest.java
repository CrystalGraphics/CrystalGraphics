package com.crystalgraphics.api.mesh;

import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.mesh.CgMeshBuilder;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/** Every shape written through {@link CgMeshWriter} is byte for byte what {@link CgMeshBuilder} made. */
public class CgMeshShapesTest {

    private static final CgVertexFormat[] FORMATS = {CgVertexFormat.SPATIAL, CgVertexFormat.POS3_UV2_COL4UB};

    @Test
    public void everyShapeMatchesTheBuilderItReplaces() {
        for (CgVertexFormat f : FORMATS) {
            same(CgMeshBuilder.unitCube(f), f, CgMeshShapes::cube);
            same(CgMeshBuilder.quad2D(f, -1f, -1f, 1f, 1f), f, m -> CgMeshShapes.quad(m, -1f, -1f, 1f, 1f));
            same(CgMeshBuilder.plane(f, 3, 5, 120f, 7f), f, m -> CgMeshShapes.plane(m, 3, 5, 120f, 7f));
            same(CgMeshBuilder.uvSphere(f, 24, 32, 1f), f, m -> CgMeshShapes.sphere(m, 24, 32, 1f));
            same(CgMeshBuilder.cylinder(f, 32, 0.6f, 1.6f), f, m -> CgMeshShapes.cylinder(m, 32, 0.6f, 1.6f));
            same(CgMeshBuilder.capsule(f, 32, 8, 0.5f, 1f), f, m -> CgMeshShapes.capsule(m, 32, 8, 0.5f, 1f));
            same(CgMeshBuilder.icosahedron(f, 3), f, m -> CgMeshShapes.icosphere(m, 3));
        }
        same(CgMeshBuilder.quad2D(CgVertexFormat.POS2_UV2_COL4UB, 0f, 0f, 1f, 1f), CgVertexFormat.POS2_UV2_COL4UB,
                m -> CgMeshShapes.quad(m, 0f, 0f, 1f, 1f));
        same(CgMeshBuilder.uvSphere(CgVertexFormat.SPATIAL, 300, 300, 1f), CgVertexFormat.SPATIAL,
                m -> CgMeshShapes.sphere(m, 300, 300, 1f));
    }

    private static void same(CgMeshData old, CgVertexFormat format, Consumer<CgMeshWriter> shape) {
        CgMesh mesh = CgMesh.build(format, shape);
        String what = format.getKey() + " " + mesh;
        assertEquals(what, old.getVertexCount(), mesh.vertexCount());
        assertEquals(what, old.indexCount(), mesh.indexCount());
        ByteBuffer bytes = ByteBuffer.allocate(mesh.vertexCount() * format.getStride());
        mesh.readVertices(0, mesh.vertexCount(), bytes);
        assertEquals(what + " vertex bytes", old.vertexBuffer().duplicate(), bytes.flip());

        int[] indices = new int[mesh.indexCount()];
        mesh.readIndices(0, indices.length, indices, 0);
        ByteBuffer ib = old.indexBuffer() == null ? null : old.indexBuffer().duplicate().order(ByteOrder.nativeOrder());
        boolean wide = old.getVertexCount() > 65535;
        for (int i = 0; i < indices.length; i++) {
            int expected = wide ? ib.getInt(i * 4) : ib.getShort(i * 2) & 0xFFFF;
            assertEquals(what + " index " + i, expected, indices[i]);
        }
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
