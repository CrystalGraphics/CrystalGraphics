package com.crystalgraphics.render.scene.gpu;

import com.crystalgraphics.render.draw.CgPassConstants;
import org.joml.Matrix4d;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class CgGpuSceneTest {

    private static final int ORIGIN = 0, MODEL_T = 4 + 12, PREVIOUS_T = 20 + 12, NORMAL = 36;

    @Test
    public void aRecordFarOut_decodesToTheCameraRelativePosition_toAFractionOfAMillimetre() {
        CgGpuScene scene = new CgGpuScene("test");
        int slot = scene.add();
        double x = 29_999_871.375, y = 70.5, z = -12_345_678.0625;
        scene.transform(slot, new Matrix4d().translation(x, y, z));

        double cx = 29_999_860.0, cy = 64.0, cz = -12_345_690.5;   // the camera, 11 to 13 blocks away
        float[] view = new float[CgPassConstants.FLOATS];
        new CgPassConstants().origin(cx, cy, cz).write(view, 0);

        float[] r = scene.mirror();
        int at = slot * CgGpuScene.FLOATS;
        for (int axis = 0; axis < 3; axis++) {
            // cg_env.glsl's cg_SceneTileToWorld, in float as the shader runs it
            float tileToWorld = (r[at + ORIGIN + axis] - view[72 + axis]) * view[75] - view[76 + axis];
            float decoded = r[at + MODEL_T + axis] + tileToWorld;
            double want = new double[]{x - cx, y - cy, z - cz}[axis];
            assertEquals("axis " + axis, want, decoded, 1e-4);
        }
    }

    @Test
    public void thePreviousTransform_isTheOneBeforeTheLastMove_andCatchesUpWhenItStops() {
        CgGpuScene scene = new CgGpuScene("test");
        int slot = scene.add();
        scene.transform(slot, new Matrix4d().translation(1, 2, 3));
        assertPositions(scene, slot, 1, 1);           // the first transform is its own previous
        scene.settle();

        scene.transform(slot, new Matrix4d().translation(5, 2, 3));
        assertPositions(scene, slot, 5, 1);
        scene.transform(slot, new Matrix4d().translation(6, 2, 3));   // again in the same frame: previous kept
        assertPositions(scene, slot, 6, 1);
        scene.settle();
        assertPositions(scene, slot, 6, 1);           // it moved in the frame that ended: still moving

        scene.settle();                                // a frame with no move
        assertPositions(scene, slot, 6, 6);
    }

    @Test
    public void aFreedSlot_isHandedOutAgain_reset_andItsIdCarriesTheSceneBit() {
        CgGpuScene scene = new CgGpuScene("test");
        int a = scene.add();
        scene.transform(a, new Matrix4d().translation(9, 9, 9));
        scene.light(a, 15f, 7f);
        scene.remove(a);
        assertThrows(IllegalArgumentException.class, () -> scene.light(a, 1f, 1f));

        int b = scene.add();
        assertEquals(a, b);
        float[] r = scene.mirror();
        assertEquals(0f, r[b * CgGpuScene.FLOATS + MODEL_T], 0f);
        assertEquals(0f, r[b * CgGpuScene.FLOATS + NORMAL + 12], 0f);   // light
        assertEquals(1f, r[b * CgGpuScene.FLOATS + NORMAL + 15], 0f);   // the spare lanes' w
        assertEquals(1, scene.size());
        assertEquals(0x80000000 | b, CgGpuScene.id(b));
    }

    private static void assertPositions(CgGpuScene scene, int slot, double x, double previousX) {
        float[] r = scene.mirror();
        int at = slot * CgGpuScene.FLOATS;
        double tile = r[at + ORIGIN] * CgGpuScene.TILE;
        assertEquals("x", x, tile + r[at + MODEL_T], 1e-6);
        assertEquals("previous x", previousX, tile + r[at + PREVIOUS_T], 1e-6);
    }
}
