package com.crystalgraphics.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.asset.camera.CgCameraRigDefinition;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgCameraKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.binding.CgSequenceRole;
import com.crystalgraphics.sequence.camera.CgCameraAnimationEvaluator;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.world.CgAttachment;

/** The camera system: Unreal's blend curves, a rig blending in over the host and out, and a camera animation. */
public class CgCameraSystemTest {

    private static final CgUnits KIT = CgUnits.of(Map.of("length", "cm", "axes", "unreal", "angles", "degrees",
            "rotation", "unreal_rotator"), CgUnits.HOST);

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    private static CgCameraRigDefinition rig(String yaml, CgUnits units) {
        return CgCameraRigDefinition.compile(CgAssetData.of("test:rig", CgCameraKinds.CAMERA_RIG,
                (Map<?, ?>) CgAssetYaml.read(yaml), null), units);
    }

    private static CgSequenceDefinition sequence(String id, String yaml, CgUnits units) {
        return CgSequenceDefinition.COMPILER.compile(CgAssetData.of(id, CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(yaml), null),
                new CgAssetCompiler.Dependencies() {
                    @Override
                    public Object definition(CgType kind, String depId) {
                        return null;
                    }

                    @Override
                    public CgUnits units() {
                        return units;
                    }
                });
    }

    private static float factor(String blend, float t) {
        CgCameraBlend.Running r = CgCameraBlend.of(CgAssetYaml.read(blend)).start(1f);
        r.advance(t);
        return r.factor();
    }

    /** Fixtures from Unreal's own formulas: Linear t, SmoothStep, SmootherStep, and Sequencer's easings. */
    @Test
    public void theBlendCurvesAreUnreals() {
        for (float t : new float[] {0f, 0.1f, 0.25f, 0.5f, 0.8f, 1f}) {
            assertEquals(t, factor("{ kind: linear, time: 1 }", t), 1e-6);
            assertEquals(t * t * (3 - 2 * t), factor("{ kind: smooth, time: 1 }", t), 1e-6);
            assertEquals(t * t * t * (t * (t * 6 - 15) + 10), factor("{ kind: smooth, time: 1, type: smoother_step }", t), 1e-6);
            assertEquals((float) Math.pow(t, 3), factor("{ kind: easing, time: 1, type: cubic_in }", t), 1e-6);
            float in = t * 2;
            float quadInOut = in < 1 ? 0.5f * in * in : 0.5f + 0.5f * (1 - (1 - (t - 0.5f) * 2) * (1 - (t - 0.5f) * 2));
            assertEquals(quadInOut, factor("{ kind: easing, time: 1, type: quad_in_out }", t), 1e-6);
        }
        assertEquals(1f, factor("{ kind: pop }", 0f), 0);
        assertThrows(IllegalArgumentException.class, () -> CgCameraBlend.of(CgAssetYaml.read("{ kind: orbit }")));
    }

    private static final String LOOK = "_v: 1\nparameters:\n  fov: { type: float, quantity: angle, default: 40 }\n"
            + "root:\n  - { id: at, node: set_location, location: [10, 0, 0], space: world }\n"
            + "  - { id: fov, node: field_of_view, fov: { bind: rig.fov } }\n"
            + "enter_transitions: [ { conditions: [], blend: { kind: linear, time: 1 } } ]\n"
            + "exit_transitions: [ { conditions: [], blend: { kind: linear, time: 0.5 }, to: host } ]\n";

    @Test
    public void aRigBlendsInOverTheHostAndBackOutOnUnrealsCurve() {
        CgCameraSystem cameras = new CgCameraSystem();
        cameras.host(pose -> pose.location(0, 0, 0).fov(70));
        CgCameraContext move = new CgCameraContext("test:move").director(CgCameraDirector.single(new CgCameraRig(rig(LOOK, CgUnits.HOST))));
        cameras.update(0.1f);
        assertFalse("the host's own camera", cameras.controls());

        cameras.push(move);
        cameras.update(0f);
        cameras.update(0.25f);
        assertEquals("a quarter in", 2.5, cameras.pose().location().x, 1e-6);
        assertEquals(70 + (40 - 70) * 0.25f, cameras.pose().fov(), 1e-4);
        cameras.update(0.75f);
        assertEquals(10, cameras.pose().location().x, 1e-6);
        assertEquals("covered, the host's entry popped", 1, cameras.blending());

        cameras.remove(move);
        cameras.update(0f);
        cameras.update(0.25f);
        assertEquals("half back out, by its exit's 0.5 s", 5, cameras.pose().location().x, 1e-6);
        cameras.update(0.25f);
        assertEquals(0, cameras.pose().location().x, 1e-6);
        assertEquals(70, cameras.pose().fov(), 1e-6);
        assertFalse("given back", cameras.controls());
    }

    @Test
    public void anExitInterruptingAnEnterTakesItsShareOfTime() {
        CgCameraSystem cameras = new CgCameraSystem();
        cameras.host(pose -> pose.location(0, 0, 0));
        CgCameraContext move = new CgCameraContext("test:move").director(CgCameraDirector.single(new CgCameraRig(rig(LOOK, CgUnits.HOST))));
        cameras.push(move);
        cameras.update(0f);
        cameras.update(0.5f);                                   // half in: x 5
        cameras.remove(move);
        cameras.update(0f);
        cameras.update(0.125f);                                 // the exit's 0.5 s times 0.5: half of 0.25 s
        // Below it the rig's own blend runs on (InternalUpdate runs every entry): 0.625 of the way in, then half back.
        assertEquals(0.625 * 10 * 0.5, cameras.pose().location().x, 1e-6);
    }

    @Test
    public void aNodeKindNotPlayedFailsAsTheRigIsBuilt() {
        CgCameraRigDefinition d = rig("_v: 1\nroot:\n  - { id: frame, node: dolly_framing }\n", CgUnits.HOST);
        RuntimeException e = assertThrows(RuntimeException.class, () -> new CgCameraRig(d));
        assertTrue(e.getMessage(), e.getMessage().contains("dolly_framing") && e.getMessage().contains("not played"));
    }

    /** The rotator lerp goes the short way round, as FMath::Lerp<FRotator> does. */
    @Test
    public void aTurnBlendsTheShortWayRound() {
        CgCameraPose a = new CgCameraPose(), b = new CgCameraPose();
        float[] q = new float[4];
        CgUnits.rotatorToHost(0, 0, Math.toRadians(170), q);
        a.rotation().set(q[0], q[1], q[2], q[3]);
        CgUnits.rotatorToHost(0, 0, Math.toRadians(-170), q);
        b.rotation().set(q[0], q[1], q[2], q[3]);
        a.lerp(b, 0.5f);
        double[] r = CgCameraPose.rotator(a.rotation(), new double[3]);
        assertEquals("through 180, not 0", 180, Math.abs(r[1]), 1e-3);
    }

    private static final String CAM = "_v: 1\ntick_resolution: 1000\ndisplay_rate: 30\nrange: [0, 1000]\n"
            + "spawnables:\n  - { id: c1, name: camera, camera: { cine: { fov: 20 } } }\n"
            + "tracks:\n"
            + "  - { id: t1, kind: transform, binding: { camera: c1 }, sections: [ { id: a, range: [0, 1000],\n"
            + "      translation: [ { keys: [[0, 50.0, linear], [1000, 150.0, linear]] }, { keys: [[0, 0.0, constant]] }, { keys: [[0, 0.0, constant]] } ] } ] }\n"
            + "  - { id: t2, kind: property, property: fov, binding: { camera: c1 }, sections: [ { id: b, range: [0, 1000],\n"
            + "      value: { keys: [[0, 20.0, linear], [1000, 30.0, linear]] } } ] }\n";
    private static final String CUT = "_v: 1\ntick_resolution: 1000\ndisplay_rate: 30\nrange: [0, 2000]\n"
            + "tracks:\n  - { id: k1, kind: camera_animation, sections: [ { id: s1, range: [0, 1000], animation: test:cam } ] }\n";

    /**
     * CameraLocal, relative to the first key (UCameraAnimInst::ApplyToView): half way, the camera has moved 50 cm along
     * the viewer's forward from where key 0 left it, its FOV 5 degrees wider; past the section, the viewer's own again.
     */
    @Test
    public void aCameraAnimationMovesTheViewersCameraFromItsFirstKey() {
        CgWorld world = new CgWorld();
        CgSequencer sequencer = world.add(new CgSequencer());
        CgCameraSystem cameras = world.add(new CgCameraSystem());
        Quaternionf hostTurn = new Quaternionf().rotationY((float) Math.toRadians(90));
        cameras.host(pose -> {
            pose.location(1, 2, 3).fov(70);
            pose.rotation().set(hostTurn);
        });
        Map<String, CgSequenceDefinition> loaded = new HashMap<>();
        loaded.put("test:cam", sequence("test:cam", CAM, KIT));
        CgCameraAnimationEvaluator.install(sequencer, cameras, loaded::get);
        CgSequencePlayer cut = sequencer.play(sequence("test:cut", CUT, KIT), Map.of());
        for (int i = 0; i < 3; i++) frame(sequencer, cameras, 0f);
        frame(sequencer, cameras, 0.5f);
        Vector3f f = hostTurn.transform(new Vector3f(1, 0, 0));
        assertEquals(1 + 0.5 * f.x, cameras.pose().location().x, 1e-3);
        assertEquals(3 + 0.5 * f.z, cameras.pose().location().z, 1e-3);
        assertEquals("70 + (25 - 20)", 75, cameras.pose().fov(), 1e-3);
        assertEquals("its camera is the view: its post blends in full", 1f, cameras.weightOf(cut), 0);
        for (int i = 0; i < 20; i++) frame(sequencer, cameras, 0.05f);
        assertEquals("and not after its section", 0f, cameras.weightOf(cut), 0);
        assertEquals("ended with its section", 1, cameras.pose().location().x, 1e-6);
        assertEquals(70, cameras.pose().fov(), 1e-6);
        assertTrue(cut.seconds() > 1);
    }

    /** Kakarot's pivot: share 0.2 of the way to the target, the offset first clamped to max_distance. */
    @Test
    public void attachBetweenGoesPartWayClampedAndHoldsWithOneRole() {
        CgCameraRigDefinition d = rig("_v: 1\nroot:\n  - { id: pivot, node: attach_between, from: { role: caster }, to: { role: target }, share: 0.2, max_distance: 4 }\n",
                CgUnits.HOST);
        CgAttachment caster = (p, t) -> {
            p[0] = 0; p[1] = 64; p[2] = 0;
            return true;
        }, target = (p, t) -> {
            p[0] = 40; p[1] = 64; p[2] = 0;
            return true;
        };
        boolean[] targetThere = {true};
        CgCameraSystem cameras = new CgCameraSystem();
        cameras.host(pose -> pose.location(0, 70, 10));
        cameras.push(new CgCameraContext("test:pivot").director(CgCameraDirector.single(new CgCameraRig(d)))
                .roles(r -> r.equals("caster") ? caster : r.equals("target") && targetThere[0] ? target : null));
        cameras.update(0f);
        assertEquals("40 clamped to 4, times 0.2", 0.8, cameras.pose().location().x, 1e-9);
        assertEquals(64, cameras.pose().location().y, 1e-9);
        targetThere[0] = false;
        cameras.update(0f);
        assertEquals("the caster's alone", 0, cameras.pose().location().x, 1e-9);
    }

    /** Bound to a role: a camera of its own at the role, its keys in the role's space, its FOV the keys' own. */
    @Test
    public void aBoundCameraAnimationIsACameraAtItsRole() {
        CgWorld world = new CgWorld();
        CgSequencer sequencer = world.add(new CgSequencer());
        CgCameraSystem cameras = world.add(new CgCameraSystem());
        cameras.host(pose -> pose.location(100, 100, 100).fov(70));
        Map<String, CgSequenceDefinition> loaded = new HashMap<>();
        loaded.put("test:cam", sequence("test:cam", CAM, KIT));
        CgCameraAnimationEvaluator.install(sequencer, cameras, loaded::get);
        Quaternionf facing = new Quaternionf().rotationY((float) Math.toRadians(90));
        CgSequenceRole caster = new CgSequenceRole() {
            @Override public CgAttachment attachment(String socket) {
                return (p, t) -> {
                    p[0] = 5; p[1] = 64; p[2] = 0;
                    if (t != null) t.set(facing);
                    return true;
                };
            }
            @Override public int slot(String name) { return -1; }
            @Override public void set(int slot, float[] v, int mask) { }
            @Override public void get(int slot, float[] out) { }
        };
        String bound = "_v: 1\ntick_resolution: 1000\ndisplay_rate: 30\nrange: [0, 2000]\n"
                + "roles:\n  - { id: b1, name: caster, kind: caster }\n"
                + "tracks:\n  - { id: k1, kind: camera_animation, binding: { role: caster }, sections: [ { id: s1, range: [0, 1000], animation: test:cam } ] }\n";
        CgSequencePlayer cut = sequencer.play(sequence("test:bound", bound, KIT), Map.of("caster", caster));
        for (int i = 0; i < 3; i++) frame(sequencer, cameras, 0f);
        frame(sequencer, cameras, 0.5f);
        // 100 cm along Unreal's forward at 0.5 s: one block along the role's turned +x.
        Vector3f along = facing.transform(new Vector3f(1, 0, 0));
        assertEquals(5 + along.x, cameras.pose().location().x, 1e-3);
        assertEquals(64, cameras.pose().location().y, 1e-3);
        assertEquals(0 + along.z, cameras.pose().location().z, 1e-3);
        assertEquals("the keys' own FOV", 25, cameras.pose().fov(), 1e-3);
        assertEquals(1f, cameras.weightOf(cut), 0);
        for (int i = 0; i < 20; i++) frame(sequencer, cameras, 0.05f);
        assertEquals("cut back to the host's", 100, cameras.pose().location().x, 1e-6);
        assertEquals(0f, cameras.weightOf(cut), 0);
    }

    /** A bystander: the play's camera does not involve this viewer, so it keeps its own and the play's weight is 0. */
    @Test
    public void aViewerThePlayDoesNotInvolveKeepsItsOwnCamera() {
        CgWorld world = new CgWorld();
        CgSequencer sequencer = world.add(new CgSequencer());
        CgCameraSystem cameras = world.add(new CgCameraSystem());
        cameras.host(pose -> pose.location(1, 2, 3).fov(70)).involved(owner -> false);
        Map<String, CgSequenceDefinition> loaded = new HashMap<>();
        loaded.put("test:cam", sequence("test:cam", CAM, KIT));
        CgCameraAnimationEvaluator.install(sequencer, cameras, loaded::get);
        CgSequencePlayer cut = sequencer.play(sequence("test:cut", CUT, KIT), Map.of());
        for (int i = 0; i < 3; i++) frame(sequencer, cameras, 0f);
        frame(sequencer, cameras, 0.5f);
        assertEquals(1, cameras.pose().location().x, 1e-9);
        assertEquals(70, cameras.pose().fov(), 0);
        assertEquals(0f, cameras.weightOf(cut), 0);
        assertFalse(cameras.controls());
    }

    private static void frame(CgSequencer sequencer, CgCameraSystem cameras, float dt) {
        sequencer.evaluate(dt);
        cameras.update(dt);
    }

    /** {@code -Pkits=kakarot_kit=...}: the skill rig builds and frames between its caster and target. */
    @Test
    public void theKakarotSkillRigFramesBetweenItsFighters() throws Exception {
        Path kit = null;
        for (String k : System.getProperty("crystalgraphics.kits", "").split(";")) {
            if (k.startsWith("kakarot_kit=")) kit = Paths.get(k.substring("kakarot_kit=".length()));
        }
        Assume.assumeTrue("no -Pkits=kakarot_kit=...", kit != null);
        String yaml = new String(Files.readAllBytes(kit.resolve("cameras/rigs/skill_camera.yaml")), StandardCharsets.UTF_8);
        CgCameraRig skill = new CgCameraRig(rig(yaml, KIT)).parameter("centre", 0.5).parameter("fov", 50);
        CgAttachment caster = (p, t) -> {
            p[0] = 0; p[1] = 64; p[2] = 0;
            return true;
        }, target = (p, t) -> {
            p[0] = 4; p[1] = 64; p[2] = 0;
            return true;
        };
        CgCameraSystem cameras = new CgCameraSystem();
        cameras.host(pose -> pose.location(0, 70, 10));
        cameras.push(new CgCameraContext("test:skill").director(CgCameraDirector.single(skill))
                .roles(r -> r.equals("caster") ? caster : r.equals("target") ? target : null));
        cameras.update(0f);
        cameras.update(0.1f);
        // attach_between, the game's pivot: half way from the caster to the target, no limit.
        assertEquals(2, cameras.pose().location().x, 1e-6);
        assertEquals(64, cameras.pose().location().y, 1e-6);
        assertEquals(50, cameras.pose().fov(), 1e-4);
    }

    /**
     * {@code -Pkits=kakarot_kit=...}: the Kamehameha cut's camera, from its file. Its first frame leaves the viewer's
     * camera where it is (relative to key 0); it then moves, and its FOV narrows by the file's own change.
     */
    @Test
    public void theKamehamehaCutInCameraPlaysFromItsFile() throws Exception {
        Path kit = null;
        for (String k : System.getProperty("crystalgraphics.kits", "").split(";")) {
            if (k.startsWith("kakarot_kit=")) kit = Paths.get(k.substring("kakarot_kit=".length()));
        }
        Assume.assumeTrue("no -Pkits=kakarot_kit=...", kit != null);
        String id = "kakarot_kit:Art/ANM/cpl/C001A/BTL/SKL/C001A_S_SKL001_Cam";
        String yaml = new String(Files.readAllBytes(kit.resolve("sequences/Art/ANM/cpl/C001A/BTL/SKL/C001A_S_SKL001_Cam.yaml")),
                StandardCharsets.UTF_8);
        CgWorld world = new CgWorld();
        CgSequencer sequencer = world.add(new CgSequencer());
        CgCameraSystem cameras = world.add(new CgCameraSystem());
        cameras.host(pose -> pose.location(0, 64, 0).fov(70).rotation().identity());
        CgCameraAnimationEvaluator.install(sequencer, cameras, Map.of(id, sequence(id, yaml, KIT))::get);
        String cut = "_v: 1\ntick_resolution: 60000\ndisplay_rate: 30\nrange: [0, 300000]\n"
                + "tracks:\n  - { id: k1, kind: camera_animation, sections: [ { id: s1, range: [0, 259939], animation: " + id + " } ] }\n";
        sequencer.play(sequence("test:cut", cut, KIT), Map.of());
        for (int i = 0; i < 3; i++) frame(sequencer, cameras, 0f);
        assertEquals("key 0 is the base", 0, cameras.pose().location().x, 1e-4);
        assertEquals(64, cameras.pose().location().y, 1e-4);
        assertEquals(70, cameras.pose().fov(), 1e-3);
        double moved = 0;
        for (int i = 0; i < 60; i++) {
            frame(sequencer, cameras, 1 / 30f);
            moved = Math.max(moved, cameras.pose().location().distance(0, 64, 0));
            assertTrue(cameras.pose().fov() >= 5 && cameras.pose().fov() <= 170);
        }
        assertTrue("it moves the camera: " + moved, moved > 0.01);
    }
}
