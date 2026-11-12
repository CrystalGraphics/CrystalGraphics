package com.crystalgraphics.camera;

import org.joml.Vector4f;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.junit.Test;

/** A world point put on the screen, as a HUD places what follows a target. */
public class CgCameraTest {

    /** A point ahead of the camera lands on the rectangle, y down; one behind answers false. */
    @Test
    public void aViewProjectsAheadAndRefusesBehind() {
        CgCamera view = new CgCamera().set(10d, 64d, 0d, new Matrix4f(),
                CgCamera.perspectiveMatrix(new Matrix4f(), 90f, 1f, 0.05f, 100f));
        Vector2f at = new Vector2f();
        assertTrue(view.project(10d, 64d, -5d, 200f, 100f, at));
        assertEquals("the centre", 100f, at.x, 1e-3f);
        assertEquals(50f, at.y, 1e-3f);
        assertTrue(view.project(10d, 66.5d, -5d, 200f, 100f, at));
        assertEquals("above is up the screen: a quarter of the way", 25f, at.y, 1e-3f);
        assertFalse(view.project(10d, 64d, 5d, 200f, 100f, at));
    }

    /** The engine's projection is Unreal's reversed Z: near at depth 1, far at 0. */
    @Test
    public void theEngineProjectionPutsNearAtOneAndFarAtZero() {
        Matrix4f p = CgCamera.perspectiveMatrix(new Matrix4f(), 70f, 1.5f, 0.05f, 300f);
        assertEquals(1f, depth(p, 0.05f), 1e-5f);
        assertEquals(0f, depth(p, 300f), 1e-5f);
        Matrix4f infinite = CgCamera.perspectiveMatrix(new Matrix4f(), 70f, 1.5f, 0.05f, Float.POSITIVE_INFINITY);
        assertEquals(1f, depth(infinite, 0.05f), 1e-5f);
        assertEquals("no far plane: far away tends to 0", 0f, depth(infinite, 1e6f), 1e-5f);
        Matrix4f o = CgCamera.orthographicMatrix(new Matrix4f(), 0, 640, 480, 0, -1, 1);
        assertEquals(1f, depth(o, -1f), 1e-6f);
        assertEquals(0f, depth(o, 1f), 1e-6f);
    }

    /** A host's projection, in each convention a Minecraft era hands over, converts to the engine's builder. */
    @Test
    public void aHostProjectionConvertsToTheEngines() {
        Matrix4f engine = CgCamera.perspectiveMatrix(new Matrix4f(), 70f, 1.5f, 0.05f, 300f);
        float fov = (float) Math.toRadians(70);
        Matrix4f gl = new Matrix4f().setPerspective(fov, 1.5f, 0.05f, 300f);
        Matrix4f standard = new Matrix4f().setPerspective(fov, 1.5f, 0.05f, 300f, true);
        Matrix4f reversedGl = new Matrix4f().setPerspective(fov, 1.5f, 300f, 0.05f, false);
        for (Matrix4f converted : new Matrix4f[] {CgCamera.engineFrom(gl, false, false),
                CgCamera.engineFrom(standard, false, true), CgCamera.engineFrom(reversedGl, true, false)}) {
            for (float z : new float[] {0.05f, 1f, 30f, 300f}) {
                assertEquals("at " + z, depth(engine, z), depth(converted, z), 1e-5f);
            }
        }
    }

    /** The window depth of a point {@code distance} ahead, 0 to 1. */
    private static float depth(Matrix4f projection, float distance) {
        Vector4f clip = projection.transform(new Vector4f(0f, 0f, -distance, 1f));
        return clip.z / clip.w;
    }
}
