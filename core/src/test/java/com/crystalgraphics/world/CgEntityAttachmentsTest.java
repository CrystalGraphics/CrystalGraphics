package com.crystalgraphics.world;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgBoxSink;
import com.crystalgraphics.platform.service.CgEntityQuery;
import com.crystalgraphics.platform.service.CgModelSpace;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.Test;

import java.util.function.IntConsumer;

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

    @Test
    public void aDeclaredPointIsAnsweredByItsProviderElseItsFallback() {
        int palm = CgEntityAttachments.declare("test:palm", CgEntityAttachments.CHEST);
        assertEquals(palm, CgEntityAttachments.declare("test:palm", CgEntityAttachments.FEET));
        double[] pose = player(0, 0), at = new double[3];
        assertEquals(64 + 1.8 * 0.6, CgEntityAttachments.point(7, palm, 0f, pose, at)[1], 1e-9);

        CgEntityAttachments.Registration registration = CgEntityAttachments.provide((entity, point, partialTick, p, out) -> {
            if (entity != 7 || point != palm) return false;
            out[0] = p[CgEntityQuery.X];
            out[1] = p[CgEntityQuery.Y] + 1.25;
            out[2] = p[CgEntityQuery.Z];
            return true;
        });
        try {
            assertEquals(64 + 1.25, CgEntityAttachments.point(7, palm, 0f, pose, at)[1], 1e-9);
            assertEquals("another entity", 64 + 1.8 * 0.6, CgEntityAttachments.point(8, palm, 0f, pose, at)[1], 1e-9);
            assertEquals("a point it does not answer", 64 + 1.62,
                    CgEntityAttachments.point(7, CgEntityAttachments.EYES, 0f, pose, at)[1], 1e-9);
        } finally {
            registration.close();
        }
        assertEquals(64 + 1.8 * 0.6, CgEntityAttachments.point(7, palm, 0f, pose, at)[1], 1e-9);
    }

    @Test
    public void aMarkComesBeforeEveryProvider() {
        int tip = CgEntityAttachments.declare("test:tip", CgEntityAttachments.FEET);
        CgEntityAttachments.Registration registration = CgEntityAttachments.provide((entity, point, partialTick, p, out) -> {
            out[0] = out[1] = out[2] = -1;
            return true;
        });
        try {
            CgEntityAttachments.mark(3, tip, 1.5, 2.5, 3.5);
            double[] at = CgEntityAttachments.point(3, tip, 0f, player(0, 0), new double[3]);
            assertEquals(1.5, at[0], 0);
            assertEquals(3.5, at[2], 0);
            assertEquals("unmarked: the provider", -1, CgEntityAttachments.point(4, tip, 0f, player(0, 0), at)[0], 0);
        } finally {
            registration.close();
        }
    }

    /** A pose matrix as Minecraft 1.15-1.20.4 draws an entity: the camera's turn, then the entity from the camera. */
    @Test
    public void aMatrixMarkIsTheModelPointInTheWorld() {
        double[] camera = {100, 70, 100};
        Matrix4f base = new Matrix4f().rotateX((float) Math.toRadians(20)).rotateY((float) Math.toRadians(135 + 180));
        CgPlatform.provide(CgModelSpace.SERVICE, (cam, b) -> {
            System.arraycopy(camera, 0, cam, 0, 3);
            base.get(b);
            return true;
        });
        CgPlatform.provide(CgEntityQuery.SERVICE, new OnePlayer(player(0, 0)));
        try {
            int grip = CgEntityAttachments.declare("test:grip", CgEntityAttachments.RIGHT_HAND);
            Matrix4f pose = new Matrix4f(base).translate((float) (10 - camera[0]), (float) (64 - camera[1]), (float) (-5 - camera[2]))
                    .rotateY(0.7f).translate(0.3f, 1.1f, 0f);
            CgEntityAttachments.mark(7, grip, pose, 0f, 0.2f, 0f);
            double[] at = CgEntityAttachments.point(7, grip, 0f, player(0, 0), new double[3]);
            Vector3f expected = new Matrix4f().rotateY(0.7f).translate(0.3f, 1.1f, 0f).transformPosition(0f, 0.2f, 0f, new Vector3f());
            assertEquals(10 + expected.x, at[0], 1e-3);
            assertEquals(64 + expected.y, at[1], 1e-3);
            assertEquals(-5 + expected.z, at[2], 1e-3);

            int far = CgEntityAttachments.declare("test:far", CgEntityAttachments.EYES);
            CgEntityAttachments.mark(7, far, new Matrix4f(base).translate(40f, 0f, 0f));
            assertEquals("drawn elsewhere: dropped", 64 + 1.62, CgEntityAttachments.point(7, far, 0f, player(0, 0), at)[1], 1e-9);
        } finally {
            CgPlatform.provide(CgModelSpace.SERVICE, null);
            CgPlatform.provide(CgEntityQuery.SERVICE, null);
        }
    }

    private static final class OnePlayer implements CgEntityQuery {
        private final double[] pose;

        OnePlayer(double[] pose) {
            this.pose = pose;
        }

        @Override public int localPlayer() { return 7; }
        @Override public int cameraEntity() { return 7; }
        @Override public boolean pose(int id, float partialTick, double[] out) {
            if (id != 7) return false;
            System.arraycopy(pose, 0, out, 0, pose.length);
            return true;
        }
        @Override public int flags(int id) { return 0; }
        @Override public int kind(int id) { return KIND_PLAYER; }
        @Override public boolean box(int id, float partialTick, CgBoxSink out) { return false; }
        @Override public int parent(int id) { return id; }
        @Override public int rootVehicle(int id) { return id; }
        @Override public void within(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                                     IntConsumer visitor) {
        }
    }
}
