package com.crystalgraphics.mc.render.entity;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** {@link CgEntityPose#followingPointer} against vanilla's {@code renderEntityInInventoryFollowsMouse}. */
public class CgEntityPoseTest {

    @Test
    public void aPointerToTheRightTurnsItRightAndOneAboveLiftsItsHead() {
        CgEntityPose p = CgEntityPose.followingPointer(40, -40);
        // Vanilla: f = atan((centre - pointer) / 40), body 180 + f * 20, head yaw f * 40, xRot -f1 * 20
        float f = (float) Math.atan(-1.0), f1 = (float) Math.atan(1.0);
        assertEquals(f * 20, p.bodyYaw(), 1e-4);
        assertEquals(f * 40, p.headYaw(), 1e-4);
        assertEquals(-f1 * 20, p.pitch(), 1e-4);
        assertEquals(f1 * 20, p.tilt(), 1e-4);
        assertTrue("right of the viewer is negative yaw", p.bodyYaw() < 0);
        assertTrue("up is negative pitch, as Minecraft's", p.pitch() < 0);
    }

    @Test
    public void aPointerOnTheMiddleFacesTheViewer() {
        assertEquals(CgEntityPose.FRONT, CgEntityPose.followingPointer(0, 0));
    }
}
