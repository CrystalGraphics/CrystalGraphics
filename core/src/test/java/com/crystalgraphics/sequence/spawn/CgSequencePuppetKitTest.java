package com.crystalgraphics.sequence.spawn;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;

import java.io.File;
import java.util.Map;

import com.crystalgraphics.api.mesh.CgMeshLoader;
import org.junit.Assume;
import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.render.stage.CgDirectionalLights;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.binding.CgSequenceRole;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.world.CgAttachment;

/** A sequence's puppet, posed by its clip, its bones carrying what rides them: the Kamehameha cut's dummy and light. */
public class CgSequencePuppetKitTest {

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

    /** {@code -Pkits=kakarot_kit=<dir>}: the cut's dummy posed by its clip, the cut-in light along its EffPoint_01. */
    @Test
    public void theCutsDummyCarriesItsLight() {
        String kits = System.getProperty("crystalgraphics.kits", "");
        String dir = null;
        for (String kit : kits.split(";")) if (kit.startsWith("kakarot_kit=")) dir = kit.substring("kakarot_kit=".length());
        Assume.assumeTrue("no -Pkits=kakarot_kit=<dir>", dir != null);
        File model = new File(dir, "models/Art/EFF/e/cmn/SKMeshes/Dmy001.glb");
        File clip = new File(dir, "models/Art/ANM/cpl/C001A/BTL/SKL/C001A_S_SKL001_Light_Dmy.glb");
        Assume.assumeTrue(model + " and " + clip, model.isFile() && clip.isFile());
        CgSequencer sequencer = new CgWorld().add(new CgSequencer());
        CgSequencePuppet.install(sequencer, id -> CgMeshLoader.sequences(clip.getPath()).get(0));
        String path = model.getPath().replace('\\', '/');
        CgSequenceDefinition cut = compile(HEAD + "spawnables:\n  - { id: dmy, at: { role: caster },\n"
                + "      puppet: { model: '" + path + "', animation: kakarot:Art/ANM/cpl/C001A/BTL/SKL/C001A_S_SKL001_Light_Dmy } }\n"
                + "  - { id: light, at: { spawnable: dmy, node: EffPoint_01 }, light: { type: directional, intensity: 10, priority: 1 } }\n"
                + "tracks: []\n");
        sequencer.play(cut, Map.of("caster", new Caster()));
        sequencer.evaluate(0.05f);
        CgDirectionalLights.Light light = CgDirectionalLights.get().active(1f);
        assertNotNull("the cut-in light, priority 1", light);
        float[] early = {light.dx(), light.dy(), light.dz()};
        for (int i = 0; i < 60; i++) sequencer.evaluate(0.05f);
        System.out.println("[puppet] the cut-in light, priority " + light.priority() + ", travels (" + light.dx() + ", "
                + light.dy() + ", " + light.dz() + ")");
        // The clip holds one pose for its 5 s: root a quarter about +X, EffPoint_01 turned under it; the light is its +X.
        assertArrayEquals("EffPoint_01's +X, as the clip poses it", new float[] {0.8937f, 0.1898f, -0.4066f}, early, 2e-3f);
        assertArrayEquals("and held there", early, new float[] {light.dx(), light.dy(), light.dz()}, 1e-5f);
    }
}
