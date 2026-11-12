package com.crystalgraphics.camera;

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
                new Matrix4f().perspective((float) Math.toRadians(90), 1f, 0.05f, 100f));
        Vector2f at = new Vector2f();
        assertTrue(view.project(10d, 64d, -5d, 200f, 100f, at));
        assertEquals("the centre", 100f, at.x, 1e-3f);
        assertEquals(50f, at.y, 1e-3f);
        assertTrue(view.project(10d, 66.5d, -5d, 200f, 100f, at));
        assertEquals("above is up the screen: a quarter of the way", 25f, at.y, 1e-3f);
        assertFalse(view.project(10d, 64d, 5d, 200f, 100f, at));
    }
}
