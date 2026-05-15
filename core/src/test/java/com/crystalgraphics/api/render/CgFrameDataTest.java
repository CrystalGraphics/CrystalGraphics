package com.crystalgraphics.api.render;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CgFrameData#deriveFromViewMatrix()}.
 *
 * Tests that camera forward direction and position are correctly extracted
 * from known JOML view matrices.
 */
public class CgFrameDataTest {

    private static final float EPSILON = 1e-5f;

    @Test
    public void identityView_cameraAtOrigin_forwardNegZ() {
        CgFrameData fc = new CgFrameData();
        fc.viewMatrix.identity();
        fc.deriveFromViewMatrix();

        // Camera at origin
        assertEquals(0f, fc.cameraPos.x, EPSILON);
        assertEquals(0f, fc.cameraPos.y, EPSILON);
        assertEquals(0f, fc.cameraPos.z, EPSILON);

        // Camera looks down -Z in OpenGL convention
        assertEquals(0f,  fc.cameraForward.x, EPSILON);
        assertEquals(0f,  fc.cameraForward.y, EPSILON);
        assertEquals(-1f, fc.cameraForward.z, EPSILON);
    }

    @Test
    public void pureTranslation_cameraAtTranslationNegative() {
        CgFrameData fc = new CgFrameData();
        // translate(3, 7, -5) moves world, so camera world pos = -(3, 7, -5)
        fc.viewMatrix.identity().translate(3f, 7f, -5f);
        fc.deriveFromViewMatrix();

        // Camera world pos = -R^T * t = -(3, 7, -5) for pure translation (R=I)
        assertEquals(-3f, fc.cameraPos.x, EPSILON);
        assertEquals(-7f, fc.cameraPos.y, EPSILON);
        assertEquals( 5f, fc.cameraPos.z, EPSILON);

        // No rotation → forward still (0, 0, -1)
        assertEquals(0f,  fc.cameraForward.x, EPSILON);
        assertEquals(0f,  fc.cameraForward.y, EPSILON);
        assertEquals(-1f, fc.cameraForward.z, EPSILON);
    }

    @Test
    public void rotated90AroundY_forwardPointsNegX() {
        CgFrameData fc = new CgFrameData();
        // 90° Y rotation of the view matrix — camera Z axis in world aligns with -X
        fc.viewMatrix.identity().rotateY((float) Math.PI / 2f);
        fc.deriveFromViewMatrix();

        // After 90° Y rotation, camera's -Z axis in world = -X world
        assertEquals(-1f, fc.cameraForward.x, EPSILON);
        assertEquals( 0f, fc.cameraForward.y, EPSILON);
        assertEquals( 0f, fc.cameraForward.z, EPSILON);
    }

    @Test
    public void combinedTranslate_cameraPos() {
        CgFrameData fc = new CgFrameData();
        // Pure translation of the view matrix: translate(0, 5, -10)
        fc.viewMatrix.identity().translate(0f, 5f, -10f);
        fc.deriveFromViewMatrix();

        // Camera world pos = -t = (0, -5, 10)
        assertEquals(  0f, fc.cameraPos.x, EPSILON);
        assertEquals( -5f, fc.cameraPos.y, EPSILON);
        assertEquals( 10f, fc.cameraPos.z, EPSILON);
    }

    @Test
    public void defaultValues_sensible() {
        CgFrameData fc = new CgFrameData();

        assertEquals(0.1f,    fc.nearPlane, EPSILON);
        assertEquals(1000f,   fc.farPlane, EPSILON);
        assertEquals(0,       fc.viewportW);
        assertEquals(0f,      fc.timeSecs, EPSILON);

        // Default cameraForward initialised to (0, 0, -1) from field declaration
        assertEquals( 0f, fc.cameraForward.x, EPSILON);
        assertEquals( 0f, fc.cameraForward.y, EPSILON);
        assertEquals(-1f, fc.cameraForward.z, EPSILON);
    }

    @Test
    public void hasDirectionalLight_alwaysFalse() {
        CgFrameData fc = new CgFrameData();
        assertFalse("MVP: no directional light in Phase 1", fc.hasDirectionalLight());
    }
}
