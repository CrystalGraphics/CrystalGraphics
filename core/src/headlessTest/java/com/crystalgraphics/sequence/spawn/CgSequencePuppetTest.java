package com.crystalgraphics.sequence.spawn;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.binding.CgSequenceRole;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.world.CgAttachment;

/** What no spawner here can make, or a puppet whose model does not load, fails as the play starts: never mid-frame. */
public class CgSequencePuppetTest {

    private static final String HEAD = "_v: 1\ntick_resolution: 1000\ndisplay_rate: 50\nrange: [0, 5000]\n"
            + "roles:\n  - { id: b1, name: caster, kind: caster }\n";

    private static final class Caster implements CgSequenceRole {
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

    private static CgSequenceDefinition compile(String yaml) {
        return CgSequenceDefinition.compile(CgAssetData.of("mymod:cut", CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(yaml), null));
    }

    @Test
    public void whatNoSpawnerMakes_andAModelThatDoesNotLoad_failAsThePlayStarts() {
        CgSequencer sequencer = new CgWorld().add(new CgSequencer());
        CgSequenceDefinition decal = compile(HEAD + "spawnables:\n  - { id: d1, at: { role: caster }, decal: { material: mymod:m } }\ntracks: []\n");
        IllegalArgumentException none = assertThrows(IllegalArgumentException.class, () -> sequencer.play(decal, Map.of("caster", new Caster())));
        assertTrue(none.getMessage(), none.getMessage().contains("spawnables.d1"));
        CgSequenceDefinition missing = compile(HEAD + "spawnables:\n  - { id: p1, at: { role: caster },\n"
                + "      puppet: { model: mymod:models/nothing.glb, animation: mymod:anim/none } }\ntracks: []\n");
        CgAssetException e = assertThrows(CgAssetException.class, () -> sequencer.play(missing, Map.of("caster", new Caster())));
        assertTrue(e.getMessage(), e.getMessage().contains("spawnables.p1.puppet.model"));
        assertTrue("nothing was left playing", sequencer.players().isEmpty());
    }
}
