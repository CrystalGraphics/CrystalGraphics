package com.crystalgraphics.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.joml.Quaternionf;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.asset.camera.CgCameraRigDefinition;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgCameraKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.world.CgHostCamera;
import com.crystalgraphics.render.stage.CgHostEnvironment;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.world.CgCamera;

/** The composed pose reaching the host: held while a rig has the camera, released once, and a rig's {@code on_missing}. */
public class CgCameraHandoffTest {

    /** A host that records what it was handed. */
    private static final class Recorder implements CgHostCamera {
        final int capabilities;
        int holds, releases, flags;
        double x;
        float roll, fov;

        Recorder(int capabilities) {
            this.capabilities = capabilities;
        }

        @Override
        public void offset(float x, float y, float z, float yaw, float pitch, float roll, float fovScale) {
        }

        @Override
        public void hold(double x, double y, double z, float yaw, float pitch, float roll, float fov, int flags) {
            holds++;
            this.flags = flags;
            this.x = x;
            this.roll = roll;
            this.fov = fov;
        }

        @Override
        public void release() {
            releases++;
        }

        @Override
        public int capabilities() {
            return capabilities;
        }
    }

    private static final int ALL = CgHostCamera.ROTATION | CgHostCamera.ROLL | CgHostCamera.FOV | CgHostCamera.POSITION
            | CgHostCamera.LOCK;

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    @After
    public void none() {
        CgCamera.release();
        CgPlatform.provide(CgHostCamera.SERVICE, CgHostCamera.NONE);
    }

    private static Recorder host(int capabilities) {
        Recorder r = new Recorder(capabilities);
        CgPlatform.provide(CgHostCamera.SERVICE, r);
        return r;
    }

    private static String rig(String extra) {
        return "_v: 1\n" + extra
                + "root:\n  - { id: at, node: set_location, location: [10, 0, 0], space: world }\n"
                + "  - { id: turn, node: set_rotation, rotation: [0.258819, 0, 0, 0.965926], space: world }\n"
                + "  - { id: fov, node: field_of_view, fov: 40 }\n"
                + "enter_transitions: [ { conditions: [], blend: { kind: pop } } ]\n"
                + "exit_transitions: [ { conditions: [], blend: { kind: pop }, to: host } ]\n";
    }

    private static CgCameraContext move(String yaml) {
        CgCameraRigDefinition d = CgCameraRigDefinition.compile(CgAssetData.of("test:rig", CgCameraKinds.CAMERA_RIG,
                (Map<?, ?>) CgAssetYaml.read(yaml), null), CgUnits.HOST);
        return new CgCameraContext("test:move").director(CgCameraDirector.single(new CgCameraRig(d)));
    }

    private static CgCameraSystem cameras() {
        return new CgCameraSystem().host(pose -> pose.location(-3, 64, 0).fov(70));
    }

    @Test
    public void aRigHoldsTheCameraEveryFrameAndReleasesItOnce() {
        Recorder host = host(ALL);
        CgCameraSystem cameras = cameras();
        cameras.update(0.1f);
        assertEquals("the host's own camera is never held", 0, host.holds);

        CgCameraContext move = move(rig(""));
        cameras.push(move);
        cameras.update(0.1f);
        cameras.update(0.1f);
        assertEquals(2, host.holds);
        assertEquals(10, host.x, 1e-6);
        assertEquals(40, host.fov, 1e-4);
        assertTrue(CgCamera.held());

        cameras.remove(move);
        cameras.update(0.1f);
        cameras.update(0.1f);
        assertEquals("released once, however many frames follow", 1, host.releases);
        assertFalse(CgCamera.held());
    }

    /** A play's switches reach the host while it holds; overlapping plays ask together, released when none does. */
    @Test
    public void thePlaysSwitchesReachTheHost() {
        Recorder host = host(ALL);
        CgCameraSystem cameras = cameras();
        CgCameraContext cut = move(rig("")).flags(CgHostCamera.HIDE_HUD);
        CgCameraContext move = move(rig("")).flags(CgHostCamera.HIDE_PLAYER);
        cameras.push(move);
        cameras.update(0.1f);
        assertEquals("a battle move keeps the HUD", CgHostCamera.HIDE_PLAYER, host.flags);
        cameras.push(cut);
        cameras.update(0.1f);
        assertEquals(CgHostCamera.HIDE_HUD | CgHostCamera.HIDE_PLAYER, host.flags);
        cameras.remove(cut);
        cameras.update(0.1f);
        assertEquals(CgHostCamera.HIDE_PLAYER, host.flags);
    }

    @Test
    public void refuseLeavesTheHostItsCamera() {
        Recorder host = host(ALL & ~CgHostCamera.ROLL);
        CgCameraSystem cameras = cameras();
        cameras.push(move(rig("requires: [roll]\non_missing: refuse\n")));
        cameras.update(0.1f);
        cameras.update(0.1f);
        assertEquals(0, host.holds);
        assertFalse(CgCamera.held());
    }

    @Test
    public void dropRollPlaysItLevel() {
        Recorder host = host(ALL & ~CgHostCamera.ROLL);
        CgCameraSystem cameras = cameras();
        cameras.push(move(rig("requires: [roll]\non_missing: drop_roll\n")));
        cameras.update(0.1f);
        assertEquals(1, host.holds);
        assertEquals(0, host.roll, 0);

        host = host(ALL);
        cameras.update(0.1f);
        assertEquals("a host with roll gets the rig's", 30, Math.abs(host.roll), 1e-3);
    }

    @Test
    public void offsetOnlyKeepsTheHostsPosition() {
        Recorder host = host(ALL & ~CgHostCamera.POSITION);
        CgCameraSystem cameras = cameras();
        cameras.push(move(rig("requires: [position]\non_missing: offset_only\n")));
        cameras.update(0.1f);
        assertEquals(-3, host.x, 1e-6);
        assertEquals(40, host.fov, 1e-4);
    }

    /** A pose past the chunks the client has, the render distance less one round the anchor, is held at that edge. */
    @Test
    public void aPosePastTheLoadedChunksIsHeldAtTheEdge() {
        CgHostEnvironment env = CgRenderStage.WORLD_OPAQUE.host().environment();
        Recorder host = host(ALL);
        CgCameraSystem cameras = cameras().anchor(out -> {
            out.set(-3, 64, 0);
            return true;
        });
        cameras.push(move(rig("")));
        try {
            env.camera(CgHostEnvironment.FLUID_NONE, CgHostEnvironment.FIRST_PERSON, 70f, 20f, false);
            cameras.update(0.1f);
            assertEquals("13 blocks out, 4 allowed", 1, host.x, 1e-6);
            env.camera(CgHostEnvironment.FLUID_NONE, CgHostEnvironment.FIRST_PERSON, 70f, 64f, false);
            cameras.update(0.1f);
            assertEquals("within reach, untouched", 10, host.x, 1e-6);
        } finally {
            env.camera(CgHostEnvironment.FLUID_NONE, CgHostEnvironment.FIRST_PERSON, 70f, 0f, false);
        }
    }

    /** Minecraft's angles: yaw 0 faces +z and -90 faces +x, pitch is positive looking down. */
    @Test
    public void aTurnBecomesMinecraftsAngles() {
        float[] a = new float[3];
        CgCamera.angles(new Quaternionf(), a);
        assertEquals("forward +x", -90, a[0], 1e-4);
        assertEquals(0, a[1], 1e-4);
        assertEquals(0, a[2], 1e-4);

        CgCamera.angles(new Quaternionf().rotateY((float) Math.toRadians(-90)), a);
        assertEquals("forward +z", 0, a[0], 1e-4);

        CgCamera.angles(new Quaternionf().rotateZ((float) Math.toRadians(-30)), a);
        assertEquals("nose down", 30, a[1], 1e-3);
        assertEquals(0, a[2], 1e-3);

        CgCamera.angles(new Quaternionf().rotateX((float) Math.toRadians(20)), a);
        assertEquals(-90, a[0], 1e-3);
        assertEquals("a roll about forward alone", 20, Math.abs(a[2]), 1e-3);
    }
}
