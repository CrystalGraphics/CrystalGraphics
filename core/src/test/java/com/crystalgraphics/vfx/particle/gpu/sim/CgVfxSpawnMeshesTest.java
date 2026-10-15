package com.crystalgraphics.vfx.particle.gpu.sim;

import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgProceduralMesh;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class CgVfxSpawnMeshesTest {

    /** Each column is picked 1/n of the time; what it keeps and gives away must add up to every weight's share. */
    @Test
    public void aliasTableGivesEachItsShare() {
        float[] weight = {1f, 7f, 0f, 2f, 0.5f, 9.5f};
        double total = 20;
        float[] share = new float[weight.length];
        int[] alias = new int[weight.length];
        CgVfxSpawnMeshes.vose(weight, total, share, alias);
        double[] picked = new double[weight.length];
        for (int i = 0; i < weight.length; i++) {
            picked[i] += share[i] / weight.length;
            picked[alias[i]] += (1 - share[i]) / weight.length;
        }
        for (int i = 0; i < weight.length; i++) assertEquals("weight " + i, weight[i] / total, picked[i], 1e-6);
    }

    @Test
    public void aMeshIsAddedOnceAndKeepsItsPlace() {
        CgMesh dome = CgProceduralMesh.revolve(CgProceduralMesh.sphere(2f, 0f, 2f)).divisions(12, 6).build();
        CgMesh cone = CgProceduralMesh.revolve(CgProceduralMesh.cone(1f, 2f)).divisions(8, 2).build();
        CgVfxSpawnMeshes.Section a = CgVfxSpawnMeshes.of(dome), b = CgVfxSpawnMeshes.of(cone);
        assertSame(a, CgVfxSpawnMeshes.of(dome));
        assertEquals(dome.indexCount() / 3, a.triangles());
        assertEquals(dome.vertexCount(), a.vertices());
        assertEquals(a.firstTriangle() + a.triangles() * CgVfxSpawnMeshes.TRIANGLE_VECTORS, a.firstVertex());
        assertEquals(a.firstVertex() + a.vertices() * CgVfxSpawnMeshes.VERTEX_VECTORS, b.firstTriangle());
        assertEquals(2f, a.radius(), 1e-4f);
        assertTrue(b.radius() > 2f);
    }
}
