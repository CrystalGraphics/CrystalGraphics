package com.crystalgraphics.render.scene.capture;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgPrimitiveComponent;
import com.crystalgraphics.render.scene.component.CgSceneCaptureComponent;
import com.crystalgraphics.render.scene.component.CgSceneRoot;
import com.crystalgraphics.render.target.CgRenderTarget;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.binding.CgSequenceRole;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.world.CgAttachment;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** A sequence's capture spawnable: a scene capture of the client scene into its render target, its bindings hidden. */
public class CgSceneCaptureSpawnerTest {

    private static String sequence(String target) {
        return "_v: 1\ntick_resolution: 20\ndisplay_rate: 20\nrange: [0, 40]\n"
                + "roles:\n  - { id: b1, name: caster, kind: caster }\n"
                + "spawnables:\n  - { id: s1, name: mirror, at: { offset: [0, 1.5, 3] }, capture: { target: " + target
                + ", fov: 60, every: 1, hide: [ { role: caster } ] } }\n";
    }

    /** A role drawn by one primitive of the client scene. */
    private static final class Caster implements CgSequenceRole {
        final CgSceneRoot body = CgScene.client().root().spawn();

        @Override public CgAttachment attachment(String socket) {
            return (point, turn) -> {
                point[0] = point[1] = point[2] = 0;
                if (turn != null) turn.identity();
                return true;
            };
        }

        @Override public CgPrimitiveComponent<?> primitive() {
            return body;
        }

        @Override public int slot(String name) {
            return -1;
        }

        @Override public void set(int slot, float[] v, int mask) {
        }

        @Override public void get(int slot, float[] out) {
        }
    }

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    private static CgSequencePlayer play(String yaml, Caster caster) {
        CgSequenceDefinition d = CgSequenceDefinition.compile(CgAssetData.of("mymod:mirror", CgSequenceKinds.SEQUENCE,
                (Map<?, ?>) CgAssetYaml.read(yaml), null));
        CgSequencer sequencer = new CgWorld(false).add(new CgSequencer());
        CgSceneCaptureSpawner.install(sequencer);
        CgSequencePlayer player = sequencer.play(d, Map.of("caster", caster));
        sequencer.evaluate(0.05f);
        return player;
    }

    @Test
    public void aCaptureSpawnable_capturesTheClientSceneIntoItsTarget_leavingOutWhatItHides() {
        Caster caster = new Caster();
        CgSequencePlayer player = play(sequence("cgtest:textures/mirror"), caster);
        if (player.failure() != null) throw player.failure();
        CgSceneCaptureComponent capture = (CgSceneCaptureComponent) player.spawned(0).primitive();
        assertNotNull(capture);
        assertSame(CgRenderTarget.of("cgtest:textures/mirror"), capture.target());
        assertEquals(60f, capture.fov(), 0f);
        assertFalse("its caster is hidden from it", capture.view().sees(caster.body));
        assertTrue(capture.spawned());
        player.stop();
        CgScene.client().update();
        caster.body.remove();
    }

    @Test
    public void aTargetThatIsNoRenderTarget_failsAsItsPlayStarts() {
        Caster caster = new Caster();
        CgAssetException e = assertThrows(CgAssetException.class, () -> play(sequence("cgtest:textures/ramp.hdr"), caster));
        assertTrue(e.getMessage(), e.getMessage().contains("is no render target"));
        caster.body.remove();
    }
}
