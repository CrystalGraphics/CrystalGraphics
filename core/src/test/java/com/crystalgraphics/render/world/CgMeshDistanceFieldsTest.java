package com.crystalgraphics.render.world;

import com.crystalgraphics.api.mesh.CgMeshShapes;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CgMeshDistanceFieldsTest {

    /** The voxel nearest (x, y, z): the field reaches a voxel past the mesh, as Unreal's border does. */
    private static float at(CgMeshDistanceFields.Field f, float x, float y, float z) {
        int ix = Math.round((x - f.ox()) / f.voxel()), iy = Math.round((y - f.oy()) / f.voxel()), iz = Math.round((z - f.oz()) / f.voxel());
        return f.distances()[(iz * f.ny() + iy) * f.nx() + ix];
    }

    @Test
    public void cubeMatchesItsDistanceInsideAndOut() {
        CgMeshDistanceFields.Field f = CgMeshDistanceFields.generate(CgMeshShapes.cube());
        float band = CgMeshDistanceFields.BAND * f.voxel();
        assertEquals(-band, at(f, 0f, 0f, 0f), 1e-4f);
        assertEquals(0.0625f, at(f, 0.5625f, 0f, 0f), 1e-3f);
        assertEquals(-0.125f, at(f, 0.375f, 0f, 0f), 1e-3f);
        assertEquals((float) Math.sqrt(2 * 0.0625 * 0.0625), at(f, 0.5625f, 0.5625f, 0f), 1e-3f);
        assertEquals(0f, at(f, 0.5f, 0.1875f, -0.25f), 1e-3f);
    }

    @Test
    public void sphereIsSignedAndNearItsRadius() {
        CgMeshDistanceFields.Field f = CgMeshDistanceFields.generate(CgMeshShapes.sphere(24, 32));
        float band = CgMeshDistanceFields.BAND * f.voxel();
        assertEquals(-band, at(f, 0f, 0f, 0f), 1e-4f);
        float x = 1f + f.voxel();
        // The facets sit inside the true sphere by at most its sagitta, about half a percent of the radius.
        assertEquals(x - 1f, at(f, x, 0f, 0f), 0.012f);
        for (float[] p : new float[][]{{0.7f, 0.3f, 0.2f}, {-0.4f, -0.6f, 0.3f}, {0.1f, 0.85f, -0.2f}}) {
            assertTrue("inside at " + p[0] + "," + p[1] + "," + p[2], at(f, p[0], p[1], p[2]) < 0f);
        }
        for (float[] p : new float[][]{{1.0f, 0.3f, 0.2f}, {-0.75f, -0.75f, 0.3f}, {0.2f, 0.95f, -0.4f}}) {
            assertTrue("outside at " + p[0] + "," + p[1] + "," + p[2], at(f, p[0], p[1], p[2]) > 0f);
        }
    }
}
