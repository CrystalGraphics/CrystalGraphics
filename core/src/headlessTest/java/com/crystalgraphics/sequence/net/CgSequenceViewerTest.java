package com.crystalgraphics.sequence.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.api.material.CgParameterCollection;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.camera.CgCameraSystem;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.net.object.CgNetTestRig;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequenceTarget;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.binding.CgSequenceRole;
import com.crystalgraphics.sequence.camera.CgCameraAnimationEvaluator;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.world.CgAttachment;

/** Viewer scope on the rig: a cinematic takes the caster's and the target's cameras, never a third player's. */
public class CgSequenceViewerTest {

    private static final String CAM = "_v: 1\ntick_resolution: 1000\ndisplay_rate: 30\nrange: [0, 2000]\n"
            + "spawnables:\n  - { id: c1, name: camera, camera: { cine: { fov: 20 } } }\n"
            + "tracks:\n"
            + "  - { id: t1, kind: transform, binding: { camera: c1 }, sections: [ { id: a, range: [0, 2000],\n"
            + "      translation: [ { keys: [[0, 0.0, linear], [2000, 4.0, linear]] }, { keys: [[0, 0.0, constant]] }, { keys: [[0, 0.0, constant]] } ] } ] }\n";

    /** 2 s: a cut-in on the viewer's camera, and a darkening of the viewer's shaders. */
    private static final String MOVE = "_v: 1\ntick_resolution: 1000\ndisplay_rate: 30\nrange: [0, 2000]\n"
            + "roles:\n  - { id: b1, name: caster, kind: caster }\n  - { id: b2, name: target, kind: target }\n"
            + "  - { id: b3, name: crowd, kind: character, absent: skip }\n"
            + "tracks:\n"
            + "  - { id: k1, kind: camera_animation, sections: [ { id: s1, range: [0, 2000], animation: test:cam } ] }\n"
            + "  - id: t1\n    kind: global\n    collection: mymod:parameters/skill\n"
            + "    sections:\n      - { id: s2, range: [0, 2000], scalars: { darken: [[0, 1, constant]] } }\n";

    /** The kits' units: centimetres and Unreal's rotators, as a cine camera's keys are written. */
    private static final CgUnits KIT = CgUnits.of(Map.of("length", "cm", "axes", "unreal", "angles", "degrees",
            "rotation", "unreal_rotator"), CgUnits.HOST);

    private static final int CASTER = 7, TARGET = 8, BYSTANDER = 9;

    /** A fighter on a client: its entity id. */
    private record Fighter(int entity) implements CgSequenceRole {
        @Override public CgAttachment attachment(String socket) {
            return (point, turn) -> {
                point[0] = point[1] = point[2] = 0;
                if (turn != null) turn.identity();
                return true;
            };
        }

        @Override public int slot(String name) {
            return -1;
        }

        @Override public void set(int slot, float[] v, int mask) {
        }

        @Override public void get(int slot, float[] out) {
        }
    }

    /** One client: its world's sequencer and camera system, and the entity its player is. */
    private static final class Viewer {
        final int me;
        final CgSequencer sequencer;
        final CgCameraSystem cameras;
        CgSequencePlayer play;

        Viewer(int me, Map<String, CgSequenceDefinition> loaded) {
            this.me = me;
            CgWorld world = new CgWorld();
            sequencer = world.add(new CgSequencer());
            cameras = world.add(new CgCameraSystem());
            cameras.host(pose -> pose.location(0, 64, 0).fov(70));
            CgCameraAnimationEvaluator.install(sequencer, cameras, loaded::get);
            sequencer.viewer(target -> target instanceof Fighter f && f.entity() == me);
        }
    }

    private static final CgParameterCollection SKILL = CgParameterCollection.of("sqviewer");
    private static final CgParameterCollection.Scalar DARKEN = SKILL.scalar("darken", 0f);

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    private static CgSequenceDefinition compile(String id, String yaml) {
        CgAssetCompiler.Dependencies deps = new CgAssetCompiler.Dependencies() {
            @Override
            public Object definition(CgType kind, String dep) {
                return dep.equals("mymod:parameters/skill") ? SKILL : null;
            }

            @Override
            public CgUnits units() {
                return KIT;
            }
        };
        return CgSequenceDefinition.COMPILER.compile(CgAssetData.of(id, CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(yaml), null), deps);
    }

    private final Map<String, CgSequenceDefinition> loaded = Map.of("test:cam", compile("test:cam", CAM));
    private final CgSequenceDefinition move = compile("mymod:ultimate", MOVE);
    private CgNetTestRig rig;
    private CgSequencer server;
    private final List<Viewer> viewers = new ArrayList<>();
    private int started;

    @Before
    public void setUp() {
        CgSequenceNet.install();
        rig = CgNetTestRig.of(3);
        rig.latencyTicks(2);
        server = new CgWorld(true).add(new CgSequencer());
        for (int me : new int[] {CASTER, TARGET, BYSTANDER}) viewers.add(new Viewer(me, loaded));
        // Each client hears the move once; which arrives first does not matter, since they differ only in who they are.
        CgSequenceNet.client((obj, sequence, roles, seed) -> {
            Viewer v = viewers.get(started++);
            v.play = v.sequencer.play(move, Map.of("caster", new Fighter(roles[0]), "target", new Fighter(roles[1])));
            return v.play;
        });
        rig.tick(2);
    }

    @After
    public void tearDown() {
        rig.close();
    }

    private void ticks(int n) {
        for (int t = 0; t < n; t++) {
            server.evaluate((float) CgWorld.TICK_SECONDS);
            rig.tick(1);
            for (Viewer v : viewers) {
                v.sequencer.evaluate((float) CgWorld.TICK_SECONDS);
                v.cameras.update((float) CgWorld.TICK_SECONDS);
            }
        }
    }

    @Test
    public void aThirdPlayerKeepsTheirCameraWhileTheCastersAndTheTargetsCut() {
        CgSequencePlayer played = server.play(move, Map.of("caster", new Fighter(CASTER), "target", new Fighter(TARGET)));
        CgSequenceNet.serve(played, rig.at(0, 64, 0), CASTER, TARGET).start();
        ticks(10);
        assertEquals("every client hears it", 3, started);
        for (Viewer v : viewers) {
            boolean involved = v.me != BYSTANDER;
            assertEquals("player " + v.me, involved, v.play.involved());
            assertEquals("player " + v.me + "'s camera", involved ? 1f : 0f, v.cameras.weightOf(v.play), 0f);
            assertEquals(involved, v.cameras.controls());
        }
        Viewer bystander = viewers.stream().filter(v -> v.me == BYSTANDER).findFirst().orElseThrow();
        assertEquals("the bystander's view is their own", 0, bystander.cameras.pose().location().x, 0);
        assertEquals(70, bystander.cameras.pose().fov(), 0);
    }

    @Test
    public void aGlobalTrackDarkensOnlyAnInvolvedViewersShaders() {
        Viewer bystander = new Viewer(BYSTANDER, loaded);
        DARKEN.set(0f);
        bystander.sequencer.play(move, Map.of("caster", new Fighter(CASTER), "target", new Fighter(TARGET)));
        bystander.sequencer.evaluate(0.1f);
        assertEquals("a remote ultimate never reaches my shaders", 0f, DARKEN.get(), 0f);

        Viewer target = new Viewer(TARGET, loaded);
        target.sequencer.play(move, Map.of("caster", new Fighter(CASTER), "target", new Fighter(TARGET)));
        target.sequencer.evaluate(0.1f);
        assertEquals("the target's darken", 1f, DARKEN.get(), 0f);
    }

    @Test
    public void withNoViewerSetEveryPlayInvolvesTheViewer() {
        CgSequencer sequencer = new CgWorld().add(new CgSequencer());
        CgSequencePlayer p = sequencer.play(move, Map.of("caster", new Fighter(CASTER), "target", new Fighter(TARGET)));
        assertTrue(p.involved());
        CgSequenceTarget crowd = new Fighter(BYSTANDER);
        sequencer.viewer(t -> t == crowd);
        CgSequencePlayer watched = sequencer.play(move, Map.of("caster", new Fighter(CASTER), "target", new Fighter(TARGET), "crowd", crowd));
        assertFalse("a character role is a bystander's", watched.involved());
    }
}
