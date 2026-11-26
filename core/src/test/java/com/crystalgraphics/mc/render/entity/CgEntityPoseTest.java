package com.crystalgraphics.mc.render.entity;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** {@link CgEntityPose}: the pointer's angles against vanilla's, the withers, the text form, {@code lerp}. */
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

    @Test
    public void followingKeepsThePosesMotion() {
        CgEntityPose walking = CgEntityPose.FRONT.walking(0.5f).swinging(0.25f);
        CgEntityPose looking = walking.following(40, 0);
        assertEquals(0.5f, looking.walkSpeed(), 0);
        assertEquals(0.25f, looking.swing(), 0);
        assertEquals(CgEntityPose.followingPointer(40, 0).bodyYaw(), looking.bodyYaw(), 0);
    }

    @Test
    public void motionIsTheEntitysOwnUntilSet() {
        assertTrue(CgEntityPose.FRONT.ownWalk());
        assertTrue(CgEntityPose.FRONT.ownSwing());
        CgEntityPose set = CgEntityPose.FRONT.walking(0.6f).swinging(2);
        assertEquals(1f, set.swing(), 0);
        assertEquals(CgEntityPose.FRONT, set.ownMotion());
    }

    @Test
    public void itsTextReadsBack() {
        CgEntityPose p = CgEntityPose.FRONT.body(30).head(45, -10.5f).tilt(5).walking(0.6f).swinging(0.25f);
        assertEquals("body 30 head 45 -10.5 tilt 5 walk 0.6 swing 0.25", p.toString());
        assertEquals(p, CgEntityPose.parse(p.toString()));
        assertEquals("front", CgEntityPose.FRONT.toString());
        assertEquals(CgEntityPose.FRONT, CgEntityPose.parse("front"));
        assertEquals(CgEntityPose.FRONT.body(-20), CgEntityPose.parse("  body   -20 "));
    }

    @Test(expected = IllegalArgumentException.class)
    public void anUnknownWordIsRefused() {
        CgEntityPose.parse("body 30 dance");
    }

    @Test(expected = IllegalArgumentException.class)
    public void aMissingNumberIsRefused() {
        CgEntityPose.parse("head 45");
    }

    @Test
    public void lerpTurnsTheShortWayRound() {
        CgEntityPose a = CgEntityPose.facing(170, 0), b = CgEntityPose.facing(-170, 0);
        assertEquals(180, a.lerp(b, 0.5f).bodyYaw(), 1e-4);
        CgEntityPose w = CgEntityPose.FRONT.walking(0).lerp(CgEntityPose.FRONT.walking(1), 0.25f);
        assertEquals(0.25f, w.walkSpeed(), 1e-6);
        assertTrue("the entity's own on either side stays its own", CgEntityPose.FRONT.lerp(w, 0.5f).ownWalk());
    }
}
