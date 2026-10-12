package com.crystalgraphics.shape;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;

public class CgPoseTest {

    /** {@code facing} turns local +Z to the look {@code CgEntityAttachments} derives from the same yaw and pitch. */
    @Test
    public void facingMatchesMinecraftsLook() {
        CgPose pose = new CgPose();
        Vector3d forward = new Vector3d(), right = new Vector3d();
        for (double yaw = -180; yaw <= 180; yaw += 22.5) {
            for (double pitch = -89; pitch <= 89; pitch += 17.8) {
                double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
                pose.facing(y, p);
                pose.rotate(0, 0, 1, forward);
                assertEquals(-Math.sin(y) * Math.cos(p), forward.x, 1e-12);
                assertEquals(-Math.sin(p), forward.y, 1e-12);
                assertEquals(Math.cos(y) * Math.cos(p), forward.z, 1e-12);
                pose.rotate(1, 0, 0, right);
                assertEquals("no roll: local X stays level", 0, right.y, 1e-12);
            }
        }
    }

    @Test
    public void worldAndLocalRoundTrip() {
        Random random = new Random(3);
        CgPose pose = new CgPose();
        Vector3d w = new Vector3d(), l = new Vector3d();
        for (int i = 0; i < 100; i++) {
            pose.at(random.nextGaussian() * 1e6, random.nextGaussian() * 1e3, random.nextGaussian() * 1e6)
                    .rotation(random.nextGaussian(), random.nextGaussian(), random.nextGaussian(), random.nextGaussian());
            double x = random.nextGaussian(), y = random.nextGaussian(), z = random.nextGaussian();
            pose.toWorld(x, y, z, w);
            pose.toLocal(w.x, w.y, w.z, l);
            assertEquals(x, l.x, 1e-9);
            assertEquals(y, l.y, 1e-9);
            assertEquals(z, l.z, 1e-9);
        }
    }

    @Test
    public void rotationIsNormalizedAndKept() {
        CgPose pose = new CgPose().rotation(0, 2, 0, 2);
        Quaterniond q = pose.rotation(new Quaterniond());
        assertEquals(1, q.lengthSquared(), 1e-12);
        Vector3d v = pose.rotate(1, 0, 0, new Vector3d());      // 90 degrees about +Y: +X goes to -Z
        assertEquals(0, v.x, 1e-12);
        assertEquals(-1, v.z, 1e-12);
        assertEquals("a copy keeps the matrix", pose.m20(), new CgPose(pose).m20(), 0);
    }
}
