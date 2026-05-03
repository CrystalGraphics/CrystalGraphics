package io.github.somehussar.crystalgraphics.gl.render;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CgInstancedBatchRenderer#validateDrawShape} pure validation logic.
 */
public class CgInstancedBatchRendererTest {

    @Test
    public void testZeroBaseAndInstanceIsNoOp() {
        CgInstancedBatchRenderer.validateDrawShape(CgInstancedDrawMode.INDEXED_QUADS, 0, 0);
    }

    @Test
    public void testZeroBaseIsNoOp() {
        CgInstancedBatchRenderer.validateDrawShape(CgInstancedDrawMode.INDEXED_QUADS, 0, 10);
    }

    @Test
    public void testZeroInstanceIsNoOp() {
        CgInstancedBatchRenderer.validateDrawShape(CgInstancedDrawMode.INDEXED_QUADS, 4, 0);
    }

    @Test
    public void testIndexedQuadsAcceptsMultipleOfFour() {
        CgInstancedBatchRenderer.validateDrawShape(CgInstancedDrawMode.INDEXED_QUADS, 4, 1);
        CgInstancedBatchRenderer.validateDrawShape(CgInstancedDrawMode.INDEXED_QUADS, 8, 10);
        CgInstancedBatchRenderer.validateDrawShape(CgInstancedDrawMode.INDEXED_QUADS, 400, 100);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testIndexedQuadsRejectsOneVertex() {
        CgInstancedBatchRenderer.validateDrawShape(CgInstancedDrawMode.INDEXED_QUADS, 1, 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testIndexedQuadsRejectsTwoVertices() {
        CgInstancedBatchRenderer.validateDrawShape(CgInstancedDrawMode.INDEXED_QUADS, 2, 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testIndexedQuadsRejectsThreeVertices() {
        CgInstancedBatchRenderer.validateDrawShape(CgInstancedDrawMode.INDEXED_QUADS, 3, 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testIndexedQuadsRejectsFiveVertices() {
        CgInstancedBatchRenderer.validateDrawShape(CgInstancedDrawMode.INDEXED_QUADS, 5, 1);
    }

    @Test
    public void testArrayTrianglesAcceptsMultipleOfThree() {
        CgInstancedBatchRenderer.validateDrawShape(CgInstancedDrawMode.ARRAY_TRIANGLES, 3, 1);
        CgInstancedBatchRenderer.validateDrawShape(CgInstancedDrawMode.ARRAY_TRIANGLES, 6, 10);
        CgInstancedBatchRenderer.validateDrawShape(CgInstancedDrawMode.ARRAY_TRIANGLES, 300, 100);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testArrayTrianglesRejectsOneVertex() {
        CgInstancedBatchRenderer.validateDrawShape(CgInstancedDrawMode.ARRAY_TRIANGLES, 1, 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testArrayTrianglesRejectsTwoVertices() {
        CgInstancedBatchRenderer.validateDrawShape(CgInstancedDrawMode.ARRAY_TRIANGLES, 2, 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testArrayTrianglesRejectsFourVertices() {
        CgInstancedBatchRenderer.validateDrawShape(CgInstancedDrawMode.ARRAY_TRIANGLES, 4, 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testArrayTrianglesRejectsFiveVertices() {
        CgInstancedBatchRenderer.validateDrawShape(CgInstancedDrawMode.ARRAY_TRIANGLES, 5, 1);
    }
}
