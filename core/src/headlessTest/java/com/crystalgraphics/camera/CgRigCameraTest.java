package com.crystalgraphics.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

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
import com.crystalgraphics.sequence.camera.CgSequenceCameras;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.world.CgAttachment;

/** A sequence's camera cut: onto a rig camera, keyed by its parameters, and onto a cine camera. */
public class CgRigCameraTest {

    private static final CgUnits KIT = CgUnits.of(Map.of("length", "cm", "axes", "unreal", "angles", "degrees",
            "rotation", "unreal_rotator"), CgUnits.HOST);

    /** A skill camera as a kit writes one: a pivot between the fighters, a boom behind it, a FOV, its blends. */
    private static final String RIG = "_v: 1\nparameters:\n"
            + "  centre: { type: float, quantity: none, default: 0 }\n"
            + "  boom: { type: vec3, quantity: offset, default: [0, 0, 0] }\n"
            + "  fov: { type: float, quantity: angle, default: 90 }\n"
            + "  blend_in: { type: float, quantity: time, default: 0 }\n"
            + "  blend_out: { type: float, quantity: time, default: 0 }\n"
            + "root:\n"
            + "  - { id: pivot, node: attach_between, from: { role: caster }, to: { role: target }, share: { bind: rig.centre } }\n"
            + "  - { id: boom, node: boom_arm, offset: { bind: rig.boom } }\n"
            + "  - { id: fov, node: field_of_view, fov: { bind: rig.fov } }\n"
            + "enter_transitions: [ { conditions: [], blend: { kind: linear, time: { bind: rig.blend_in } } } ]\n"
            + "exit_transitions: [ { conditions: [], blend: { kind: linear, time: { bind: rig.blend_out } }, to: host } ]\n";

    private static final String SHOT = "_v: 1\ntick_resolution: 1000\ndisplay_rate: 30\nrange: [0, 4000]\n"
            + "roles:\n  - { id: r1, name: caster, kind: caster }\n  - { id: r2, name: target, kind: target }\n"
            + "spawnables:\n  - { id: cam, name: skill_camera, camera: { rig: test:skill } }\n"
            + "tracks:\n"
            + "  - { id: p1, kind: property, binding: { camera: cam }, property: rig.centre, sections: [ { id: a1, range: [0, 4000], value: { keys: [[0, 0.25]] } } ] }\n"
            + "  - { id: p2, kind: property, binding: { camera: cam }, property: rig.boom, sections: [ { id: a2, range: [0, 4000],\n"
            + "      value: { x: { keys: [[0, -400.0]] }, \"y\": { keys: [[0, 0.0]] }, z: { keys: [[0, 0.0]] } } } ] }\n"
            + "  - { id: p3, kind: property, binding: { camera: cam }, property: rig.fov, sections: [ { id: a3, range: [0, 4000], value: { keys: [[0, 60.0]] } } ] }\n"
            + "  - { id: p4, kind: property, binding: { camera: cam }, property: rig.blend_in, sections: [ { id: a4, range: [0, 4000], value: { keys: [[0, 0.5]] } } ] }\n"
            + "  - { id: p5, kind: property, binding: { camera: cam }, property: rig.blend_out, sections: [ { id: a5, range: [0, 4000], value: { keys: [[0, 1.0]] } } ] }\n"
            + "  - { id: cut, kind: camera_cut, sections: [ { id: c, range: [0, 2000], camera: { camera: cam } } ] }\n";

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    private static CgCameraRigDefinition rig(String id, String yaml) {
        return CgCameraRigDefinition.compile(CgAssetData.of(id, CgCameraKinds.CAMERA_RIG, (Map<?, ?>) CgAssetYaml.read(yaml), null), KIT);
    }

    /** {@code yaml} compiled in a kit's units, {@code rig} its one camera rig. */
    private static CgSequenceDefinition sequence(String id, String yaml, @Nullable CgCameraRigDefinition rig) {
        return sequence(id, (Map<?, ?>) CgAssetYaml.read(yaml), rig);
    }

    private static CgSequenceDefinition sequence(String id, Map<?, ?> tree, @Nullable CgCameraRigDefinition rig) {
        return CgSequenceDefinition.COMPILER.compile(CgAssetData.of(id, CgSequenceKinds.SEQUENCE, tree, null),
                new CgAssetCompiler.Dependencies() {
                    @Override
                    public Object definition(CgType kind, String depId) {
                        return rig != null && kind == CgCameraKinds.CAMERA_RIG && depId.equals(rig.id()) ? rig : null;
                    }

                    @Override
                    public CgUnits units() {
                        return KIT;
                    }
                });
    }

    private static CgSequenceRole at(double x, double y, double z) {
        return new CgSequenceRole() {
            @Override public CgAttachment attachment(String socket) {
                return (p, t) -> {
                    p[0] = x; p[1] = y; p[2] = z;
                    if (t != null) t.identity();
                    return true;
                };
            }
            @Override public int slot(String name) { return -1; }
            @Override public void set(int slot, float[] v, int mask) { }
            @Override public void get(int slot, float[] out) { }
        };
    }

    private static void frame(CgSequencer sequencer, CgCameraSystem cameras, float dt) {
        sequencer.evaluate(dt);
        cameras.update(dt);
    }

    private static double distance(CgCameraPose pose, double x, double y, double z) {
        double dx = pose.location().x - x, dy = pose.location().y - y, dz = pose.location().z - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * The skill camera: blended in over its keyed 0.5 s, then framed by its keys (the pivot a quarter of the way to the
     * target, 4 blocks of boom from 400 cm, a FOV of 60 from 60 degrees); once its section ends, blended back over 1 s.
     */
    @Test
    public void aRigCameraCutsInByItsRigFramedByItsKeyedParameters() {
        CgWorld world = new CgWorld();
        CgSequencer sequencer = world.add(new CgSequencer());
        CgCameraSystem cameras = world.add(new CgCameraSystem());
        cameras.host(pose -> pose.location(0, 70, 20).fov(70));
        CgSequenceCameras.install(sequencer, cameras, id -> null);
        CgSequencePlayer play = sequencer.play(sequence("test:shot", SHOT, rig("test:skill", RIG)),
                Map.of("caster", at(0, 64, 0), "target", at(8, 64, 0)));
        frame(sequencer, cameras, 0f);
        frame(sequencer, cameras, 0.25f);
        assertNull(String.valueOf(play.failure()), play.failure());
        assertTrue("blending in", cameras.pose().fov() < 70 && cameras.pose().fov() > 60);
        for (int i = 0; i < 6; i++) frame(sequencer, cameras, 0.1f);
        assertEquals("rig.fov's 60 degrees", 60, cameras.pose().fov(), 1e-3);
        assertEquals("rig.boom's 400 cm from the pivot", 4, distance(cameras.pose(), 2, 64, 0), 1e-3);
        assertEquals(1f, cameras.weightOf(play), 0);
        // 0.85 s in; the cut's section ends at 2 s, then its rig's exit, rig.blend_out, over 1 s.
        for (int i = 0; i < 12; i++) frame(sequencer, cameras, 0.1f);
        frame(sequencer, cameras, 0.5f);
        assertTrue("blending out at " + cameras.pose().fov(), cameras.pose().fov() > 60 && cameras.pose().fov() < 70);
        for (int i = 0; i < 8; i++) frame(sequencer, cameras, 0.1f);
        assertEquals("the host's again", 70, cameras.pose().fov(), 1e-6);
        assertFalse(cameras.controls());
    }

    /** A cut onto a cine camera: its keyed pose and FOV while it plays, the host's once it ends. */
    @Test
    public void aCineCameraCutIsTheViewForItsSection() {
        CgWorld world = new CgWorld();
        CgSequencer sequencer = world.add(new CgSequencer());
        CgCameraSystem cameras = world.add(new CgCameraSystem());
        cameras.host(pose -> pose.location(0, 70, 20).fov(70));
        CgSequenceCameras.install(sequencer, cameras, id -> null);
        String shot = "_v: 1\ntick_resolution: 1000\ndisplay_rate: 30\nrange: [0, 2000]\n"
                + "spawnables:\n  - { id: cam, name: cine, camera: { cine: { fov: 25 } } }\n"
                + "tracks:\n"
                + "  - { id: t, kind: transform, binding: { camera: cam }, sections: [ { id: a, range: [0, 2000],\n"
                + "      translation: [ { keys: [[0, 300.0]] }, { keys: [[0, 0.0]] }, { keys: [[0, 0.0]] } ] } ] }\n"
                + "  - { id: cut, kind: camera_cut, sections: [ { id: c, range: [0, 1000], camera: cam } ] }\n";
        CgSequencePlayer play = sequencer.play(sequence("test:cine", shot, null), Map.of());
        frame(sequencer, cameras, 0f);
        frame(sequencer, cameras, 0.5f);
        assertNull(String.valueOf(play.failure()), play.failure());
        assertEquals("its cine FOV", 25, cameras.pose().fov(), 1e-3);
        assertEquals("300 cm along x", 3, cameras.pose().location().x, 1e-3);
        for (int i = 0; i < 8; i++) frame(sequencer, cameras, 0.1f);
        assertEquals(70, cameras.pose().fov(), 1e-6);
        assertEquals(20, cameras.pose().location().z, 1e-6);
    }

    /** A bystander keeps its own camera through the cut. */
    @Test
    public void aViewerThePlayDoesNotInvolveNeverCuts() {
        CgWorld world = new CgWorld();
        CgSequencer sequencer = world.add(new CgSequencer());
        CgCameraSystem cameras = world.add(new CgCameraSystem());
        cameras.host(pose -> pose.location(0, 70, 20).fov(70)).involved(owner -> false);
        CgSequenceCameras.install(sequencer, cameras, id -> null);
        CgSequencePlayer play = sequencer.play(sequence("test:shot", SHOT, rig("test:skill", RIG)),
                Map.of("caster", at(0, 64, 0), "target", at(8, 64, 0)));
        for (int i = 0; i < 10; i++) frame(sequencer, cameras, 0.1f);
        assertEquals(70, cameras.pose().fov(), 0);
        assertFalse(cameras.controls());
        assertEquals(0f, cameras.weightOf(play), 0);
    }

    /** What is not played yet fails by name: a parameter the rig lacks at compile, a cut's ease at bind. */
    @Test
    public void whatIsNotPlayedFailsByName() {
        CgCameraRigDefinition skill = rig("test:skill", RIG);
        Exception e = assertThrows(Exception.class, () -> sequence("test:bad", SHOT.replace("rig.centre", "rig.middle"), skill));
        assertTrue(e.getMessage(), e.getMessage().contains("test:skill has no parameter middle"));

        CgWorld world = new CgWorld();
        CgSequencer sequencer = world.add(new CgSequencer());
        CgCameraSystem cameras = world.add(new CgCameraSystem());
        CgSequenceCameras.install(sequencer, cameras, id -> null);
        String eased = SHOT.replace("{ id: c, range: [0, 2000],", "{ id: c, range: [0, 2000], ease: { in: 100 },");
        CgSequenceDefinition d = sequence("test:eased", eased, skill);
        e = assertThrows(Exception.class, () -> sequencer.play(d, Map.of("caster", at(0, 64, 0), "target", at(8, 64, 0))));
        assertTrue(e.getMessage(), e.getMessage().contains("ease: a camera cut's ease is not played yet"));
    }

    /**
     * {@code -Pkits=kakarot_kit=...}: K's shot binds, and its skill camera cuts in at tick 6600 and is the view by its
     * keys, its FOV on its keyed curve. Its camera tracks alone: its clips, effects and events are other kinds' work.
     */
    @Test
    public void kakarotsShotCutsToItsSkillCamera() throws Exception {
        Path kit = null;
        for (String k : System.getProperty("crystalgraphics.kits", "").split(";")) {
            if (k.startsWith("kakarot_kit=")) kit = Paths.get(k.substring("kakarot_kit=".length()));
        }
        Assume.assumeTrue("no -Pkits=kakarot_kit=...", kit != null);
        CgCameraRigDefinition skill = rig("kakarot_kit:skill_camera",
                new String(Files.readAllBytes(kit.resolve("cameras/rigs/skill_camera.yaml")), StandardCharsets.UTF_8));
        String yaml = new String(Files.readAllBytes(kit.resolve("sequences/Blueprints/Character/C001A/BTL/SKL/AM_C001A_S_SKL001_02.yaml")),
                StandardCharsets.UTF_8);
        CgWorld world = new CgWorld();
        CgSequencer sequencer = world.add(new CgSequencer());
        CgCameraSystem cameras = world.add(new CgCameraSystem());
        cameras.host(pose -> pose.location(0, 70, 20).fov(70));
        CgSequenceCameras.install(sequencer, cameras, id -> null);
        // Its effects and post are other kinds' work: stand-ins that place nothing.
        sequencer.spawner("system", (p, s, a) -> graceful -> { });
        sequencer.spawner("post", (p, s, a) -> graceful -> { });
        Map<Object, Object> tree = new LinkedHashMap<>((Map<?, ?>) CgAssetYaml.read(yaml));
        List<Object> cameraTracks = new ArrayList<>();
        for (Object o : (List<?>) tree.get("tracks")) {
            Map<?, ?> track = (Map<?, ?>) o;
            if (track.get("kind").equals("camera_cut") || track.get("binding") instanceof Map<?, ?> b && b.containsKey("camera")) cameraTracks.add(track);
        }
        assertEquals("rig.centre, centre_limit, boom, rotation, location, fov, blend_in, blend_out and the cut", 9, cameraTracks.size());
        tree.put("tracks", cameraTracks);
        CgSequencePlayer play = sequencer.play(sequence("kakarot_kit:shot", tree, skill),
                Map.of("caster", at(0, 64, 0), "target", at(10, 64, 0)));
        frame(sequencer, cameras, 0f);
        for (int i = 0; i < 16; i++) frame(sequencer, cameras, 0.05f);
        assertNull(String.valueOf(play.failure()), play.failure());
        // rig.fov: 60 at tick 6600 to 62 at 138600, linear; 0.8 s is tick 48000.
        assertEquals(60 + 2 * (48000 - 6600) / 132000.0, cameras.pose().fov(), 1e-3);
        assertEquals(1f, cameras.weightOf(play), 0);
        System.out.println("[CA5] K's shot at 0.8 s: " + cameras.pose().location() + " fov " + cameras.pose().fov());
    }
}
