package com.crystalgraphics.render.scene.component;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.animation.CgAnimation;
import com.crystalgraphics.animation.CgSocketTest;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.material.CgMaterialUsage;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.material.CgMaterialProperty;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * A skeletal mesh component stands its sockets in the world (placement × bone × relative), carries what rides them the
 * frame they move, draws a leader's pose, is bounded by its pose, sets parameters through its slots' own instances and
 * collapses hidden bones.
 */
public class CgSkeletalMeshTest {

    private static final CgVfxLook LOOK = CgVfxLook.builder(CgVfxSchema.of("test:socket")).build();
    private static final String SPRITE = "crystalgraphics:shaders/vfx/particle/sprite.shader";

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    private static CgSkeletalMeshComponent playing(CgScene scene, CgSkeletalMesh mesh) {
        CgSkeletalMeshComponent arm = scene.skeletal(mesh).at(10, 64, -3).scale(2f);
        arm.anim().play(mesh.sequence("bend"));
        return arm;
    }

    /** {@code arm}'s palm in the world as its pose stands: its placement times the socket in its mesh. */
    private static Vector3d palm(CgSkeletalMeshComponent arm, CgAnimInstance pose) {
        Matrix4f inMesh = pose.socket(pose.socket("palm"), new Matrix4f());
        Matrix4d world = new Matrix4d().translation(arm.x(), arm.y(), arm.z()).scale(2).mul(new Matrix4d(inMesh));
        return world.getTranslation(new Vector3d());
    }

    @Test
    public void aSocketStandsWhereItsComponentPlacesIt() {
        CgScene scene = new CgScene();
        CgSkeletalMeshComponent arm = playing(scene, CgSocketTest.arm()).spawn();
        arm.anim().position(0.5f).tick(0f);                                  // key 2: up 3, turned 45 degrees
        double[] point = new double[3];
        assertTrue(arm.attachment("palm").place(point, null));
        Matrix4f expected = new Matrix4f().scaling(2f).translate(0, 3, 0).rotateZ((float) Math.toRadians(45)).translate(0, 0.5f, 0);
        assertEquals(10 + expected.m30(), point[0], 1e-5);
        assertEquals(64 + expected.m31(), point[1], 1e-5);
        assertEquals(-3 + expected.m32(), point[2], 1e-5);
        assertTrue(arm.attachment("tail").place(point, null));            // neither socket nor bone: where it stands
        assertEquals(10, point[0], 1e-9);
        arm.remove();
        assertFalse("removed, it is gone", arm.attachment("palm").place(point, null));
    }

    /** Records where it stood each tick: its logic's view of the socket. */
    static final class OnHand extends CgVfxEffect<OnHand> {
        double x, y, z;

        OnHand() {
            super(LOOK, 0, 0, 0);
        }

        @Override
        protected void tick(float dt) {
            x = originX;
            y = originY;
            z = originZ;
        }

        @Override
        protected void submit(CgVfxFrame frame) {
        }
    }

    @Test
    public void anEffectOnAPosedHandNeverLagsIt() {
        CgSkeletalMesh mesh = CgSocketTest.arm();
        CgScene scene = new CgScene();
        try (CgWorld world = new CgWorld()) {
            CgAnimation animation = world.add(new CgAnimation());
            CgSkeletalMeshComponent arm = playing(scene, mesh).spawn();
            animation.add(arm.anim());
            world.schedule().add(CgTickGroup.ATTACH, "test:scene", dt -> scene.update());
            CgVfxSystem vfx = new CgVfxSystem();
            OnHand hand = vfx.play(new OnHand().follow(arm.attachment("palm")));
            double[] clock = {0};
            world.schedule().add(CgTickGroup.EFFECTS, "test:vfx", dt -> vfx.update(clock[0]));
            CgAnimInstance reference = new CgAnimInstance(mesh)
                    .play(mesh.sequence("bend"));
            int checked = 0;
            for (int f = 0; f < 90; f++) {
                clock[0] = f / 60.0;
                CgFrameClock.set(clock[0]);
                world.frame(null);
                if (f < 2) continue;                                   // the system's first update only sets its clock
                // This frame's pose, worked out apart: the effect ticked against exactly it, not the frame before's
                Vector3d expected = palm(arm, reference.position(arm.anim().position()).tick(0f));
                assertEquals("frame " + f, expected.x, hand.x, 1e-6);
                assertEquals("frame " + f, expected.y, hand.y, 1e-6);
                assertEquals("frame " + f, expected.z, hand.z, 1e-6);
                checked++;
            }
            assertTrue(checked > 80);

            // Moved after the effects ticked: the late re-read puts what draws on it
            arm.at(20, 70, 5);
            vfx.lateAttach();
            Vector3d expected = palm(arm, arm.anim());
            assertEquals(expected.x, hand.originX(), 1e-6);
            assertEquals(expected.y, hand.originY(), 1e-6);

            // Removed: what follows it stops
            arm.remove();
            vfx.lateAttach();
            assertFalse(hand.state() == CgVfxEffect.State.PLAYING);
        }
    }

    /** A primitive attached to a posed palm stands there the frame the palm moves, offset in the palm's space. */
    @Test
    public void anAttachedPrimitiveRidesItsParentsSocketTheSameFrame() {
        CgSkeletalMesh mesh = CgSocketTest.arm();
        CgScene scene = new CgScene();
        CgSkeletalMeshComponent hat = scene.skeletal(mesh).spawn();
        CgSkeletalMeshComponent arm = playing(scene, mesh).spawn();
        hat.attach(arm, "palm").at(0, 1, 0).scale(0.5f);
        for (int f = 0; f < 30; f++) {
            arm.anim().position(f / 60f).tick(0f);
            scene.update();
            Matrix4f inMesh = arm.anim().socket(arm.anim().socket("palm"), new Matrix4f()).translate(0, 1, 0);
            Matrix4d expected = new Matrix4d().translation(10, 64, -3).scale(2).mul(new Matrix4d(inMesh));
            assertEquals("frame " + f, expected.m30(), hat.x(), 1e-5);
            assertEquals("frame " + f, expected.m31(), hat.y(), 1e-5);
            assertEquals("frame " + f, expected.m32(), hat.z(), 1e-5);
            assertEquals("the arm's 2 times its own half", 1f, hat.basis(new Matrix4f()).getScale(new Vector3f()).x, 1e-5f);
        }
        hat.detach();
        double x = hat.x();
        arm.anim().position(1.0f).tick(0f);
        scene.update();
        assertEquals("detached, it stays where it stood", x, hat.x(), 1e-9);

        hat.attach(arm, "palm");
        arm.remove();
        assertEquals("its parent removed, it stands where it was", null, hat.attachParent());
    }

    /** A placement given before attaching is kept as the offset from the socket, as one given after. */
    @Test
    public void aPlacementGivenBeforeAttachingIsKeptAsItsOffset() {
        CgSkeletalMesh mesh = CgSocketTest.arm();
        CgScene scene = new CgScene();
        CgSkeletalMeshComponent arm = playing(scene, mesh).spawn();
        CgSkeletalMeshComponent before = scene.skeletal(mesh).at(0, 1, 0).scale(0.5f).attach(arm, "palm").spawn();
        CgSkeletalMeshComponent after = scene.skeletal(mesh).spawn();
        after.attach(arm, "palm").at(0, 1, 0).scale(0.5f);
        arm.anim().position(0.25f).tick(0f);
        scene.update();
        assertEquals(after.x(), before.x(), 1e-9);
        assertEquals(after.y(), before.y(), 1e-9);
        assertEquals(after.z(), before.z(), 1e-9);
        assertEquals(after.basis(new Matrix4f()).getScale(new Vector3f()).x,
                before.basis(new Matrix4f()).getScale(new Vector3f()).x, 1e-6f);
    }

    @Test
    public void anAttachmentIntoACycleOrOntoAMissingSocketIsRefused() {
        CgScene scene = new CgScene();
        CgSkeletalMesh mesh = CgSocketTest.arm();
        CgSkeletalMeshComponent a = scene.skeletal(mesh), b = scene.skeletal(mesh).attach(a, null);
        try {
            a.attach(b, "palm");
            throw new AssertionError("a cycle was taken");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("itself"));
        }
        try {
            b.attach(a, "tail");
            throw new AssertionError("a missing socket was taken");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("tail"));
        }
    }

    private static List<float[]> skinned(CgSkeletalMeshComponent c) {
        List<float[]> out = new ArrayList<>();
        c.cpuSkin(0, false, (x, y, z, u, v, argb, nx, ny, nz) -> out.add(new float[]{x, y, z}));
        return out;
    }

    @Test
    public void aFollowerDrawsItsLeadersPoseWhereTheLeaderStands() {
        CgScene scene = new CgScene();
        CgSkeletalMesh mesh = CgSocketTest.arm();
        CgSkeletalMeshComponent leader = playing(scene, mesh).spawn();
        CgSkeletalMeshComponent shell = scene.skeletal(mesh).followPose(leader).spawn();
        assertSame("posed by the leader", leader.anim(), shell.anim());
        leader.anim().position(0.75f).tick(0f);
        scene.update();
        assertEquals("it rides the leader's origin", leader.x(), shell.x(), 0);
        List<float[]> a = skinned(leader), b = skinned(shell);
        for (int i = 0; i < a.size(); i++) {
            for (int c = 0; c < 3; c++) assertEquals("vertex " + i, a.get(i)[c], b.get(i)[c], 1e-5f);   // riding it, scaled as it is
        }
    }

    @Test
    public void itsBoundsAreItsPosesNeverAPad() {
        CgScene scene = new CgScene();
        CgSkeletalMeshComponent arm = scene.skeletal(CgSocketTest.arm()).spawn();
        arm.anim().play(arm.mesh().sequence("bend"));
        arm.anim().position(0.75f).tick(0f);                                 // key 3: the elbow up 4, turned 67.5 degrees
        scene.update();
        CgAABB box = arm.drawnBounds(new CgAABB());
        for (float[] v : skinned(arm)) {
            assertTrue(v[1] + " within " + box, v[0] >= box.minX - 1e-4 && v[0] <= box.maxX + 1e-4
                    && v[1] >= box.minY - 1e-4 && v[1] <= box.maxY + 1e-4 && v[2] >= box.minZ - 1e-4 && v[2] <= box.maxZ + 1e-4);
        }
        assertTrue("past the bind pose's top, 1: " + box, box.maxY > 3);
    }

    private static float value(CgMaterialInterface m, String name) {
        for (CgMaterialProperty p : m.properties()) if (p.getName().equals(name)) return p.getFloatValue()[0];
        throw new AssertionError(name + " not declared");
    }

    @Test
    public void aParameterIsSetThroughEachDeclaringSlotsOwnInstance() {
        CgMaterialDefinition eye = CgMaterialDefinition.of("test:eye", SPRITE).usage(CgMaterialUsage.SPRITES)
                .value("_SoftDistance", 0.25f).build();
        CgMaterial shared = CgMaterial.of(eye);
        CgSkeletalMeshComponent c = new CgScene().skeletal(CgSocketTest.arm()).material(0, shared);
        c.scalar("_NotDeclared", 1f);
        assertSame("a name no slot declares makes no instance", shared, c.material(0));
        c.scalar("_SoftDistance", 2f);
        assertNotSame(shared, c.material(0));
        assertTrue(c.material(0) instanceof CgMaterialInstance);
        assertEquals(2f, value(c.material(0), "_SoftDistance"), 0f);
        assertEquals("the shared material keeps its own", 0.25f, value(shared, "_SoftDistance"), 0f);
        c.resetScalar("_SoftDistance");
        assertEquals(0.25f, value(c.material(0), "_SoftDistance"), 0f);

        CgMaterialInstance mine = shared.instance();
        c.material(0, mine);
        c.scalar("_SoftDistance", 3f);
        assertSame("a slot holding an instance is set as it is", mine, c.material(0));
        assertEquals(3f, value(mine, "_SoftDistance"), 0f);
    }

    @Test
    public void aHiddenBoneCollapsesItsVerticesNotItsSockets() {
        CgScene scene = new CgScene();
        CgSkeletalMeshComponent arm = scene.skeletal(CgSocketTest.arm()).spawn();
        arm.hideBone("ELBOW", CgBoneHide.BONE);
        arm.anim().evaluate();
        scene.update();
        for (float[] v : skinned(arm)) for (int c = 0; c < 3; c++) assertEquals(0f, v[c], 0f);
        double[] point = new double[3];
        arm.attachment("palm").place(point, null);
        assertEquals("the socket still stands on the posed bone", 1.5, point[1], 1e-5);

        arm.hideBone("elbow", CgBoneHide.NONE).hideBone("root", CgBoneHide.BONE);
        scene.update();
        assertTrue("the root's own vertices alone: the elbow's stay", skinned(arm).get(2)[1] != 0f);
        arm.hideBone("root", CgBoneHide.BRANCH);
        scene.update();
        for (float[] v : skinned(arm)) assertEquals("a branch takes its children", 0f, v[1], 0f);

        arm.hideBone("root", CgBoneHide.NONE).hideBoneFromOwner("root", CgBoneHide.BRANCH);
        scene.update();
        assertTrue("drawn for every other view", skinned(arm).get(2)[1] != 0f);
        List<float[]> owner = new ArrayList<>();
        arm.cpuSkin(0, true, (x, y, z, u, v, argb, nx, ny, nz) -> owner.add(new float[]{x, y, z}));
        for (float[] v : owner) assertEquals("hidden from its owner", 0f, v[1], 0f);
    }
}
