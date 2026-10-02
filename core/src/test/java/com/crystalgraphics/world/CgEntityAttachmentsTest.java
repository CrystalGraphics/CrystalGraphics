package com.crystalgraphics.world;

import com.crystalgraphics.platform.service.CgEntityQuery;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Minecraft's angle conventions: yaw 0 faces +z, so the right hand is toward -x; pitch down is positive. */
public class CgEntityAttachmentsTest {

    private static double[] player(double yaw, double pitch) {
        double[] pose = new double[CgEntityQuery.POSE_LENGTH];
        pose[CgEntityQuery.X] = 10;
        pose[CgEntityQuery.Y] = 64;
        pose[CgEntityQuery.Z] = -5;
        pose[CgEntityQuery.WIDTH] = 0.6;
        pose[CgEntityQuery.HEIGHT] = 1.8;
        pose[CgEntityQuery.EYE_HEIGHT] = 1.62;
        pose[CgEntityQuery.YAW] = pose[CgEntityQuery.BODY_YAW] = pose[CgEntityQuery.HEAD_YAW] = yaw;
        pose[CgEntityQuery.PITCH] = pitch;
        return pose;
    }

    @Test
    public void facingSouthTheRightHandIsWest() {
        double[] at = new double[3];
        CgEntityAttachments.point(CgEntityAttachments.RIGHT_HAND, player(0, 0), at);
        assertTrue("right hand toward -x: " + at[0], at[0] < 10);
        CgEntityAttachments.point(CgEntityAttachments.LEFT_HAND, player(0, 0), at);
        assertTrue("left hand toward +x: " + at[0], at[0] > 10);
    }

    @Test
    public void handsForwardAreAheadAlongTheLook() {
        double[] at = new double[3], look = new double[3];
        CgEntityAttachments.forward(player(90, 0), look);
        assertEquals("yaw 90 faces -x", -1.0, look[0], 1e-9);
        CgEntityAttachments.point(CgEntityAttachments.HANDS_FORWARD, player(90, 0), at);
        assertTrue("in front: " + at[0], at[0] < 10 - 0.4);
        assertEquals("between the hands", -5.0, at[2], 1e-9);
        CgEntityAttachments.forward(player(0, 90), look);
        assertEquals("pitch 90 looks straight down", -1.0, look[1], 1e-9);
    }

    @Test
    public void eyesAndFeetAreOnTheBox() {
        double[] at = new double[3];
        assertEquals(64 + 1.62, CgEntityAttachments.point(CgEntityAttachments.EYES, player(0, 0), at)[1], 1e-9);
        assertEquals(64.0, CgEntityAttachments.point(CgEntityAttachments.FEET, player(0, 0), at)[1], 1e-9);
    }
}
