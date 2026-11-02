package com.crystalgraphics.render.post.volume;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequenceTarget;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;

/** A colour region from a sequence: its block read, its program's keywords, and its fields keyed by property tracks. */
public class CgColourRegionTest {

    private static final String FLASH = "_v: 1\ntick_resolution: 20\ndisplay_rate: 20\nrange: [0, 40]\n"
            + "spawnables:\n  - { id: s1, name: flash, colour_region: { priority: 1, falloff: 0.2, colour: { gain: [2, 2, 2, 1] } } }\n"
            + "tracks:\n  - id: t1\n    kind: property\n    binding: { spawnable: s1 }\n    property: colour_region.colour.gain\n"
            + "    sections:\n      - { id: x1, range: [0, 40], value: { a: { keys: [[0, 2.0], [40, 1.0]] } } }\n";

    @Test
    public void aBlocksKeywordsAreItsShapeRangesAndTemperature() {
        assertArrayEquals(new String[0], CgColourRegion.keywords(Map.of("colour", Map.of("gain", List.of(2, 2, 2, 1)))));
        assertArrayEquals(new String[]{"SHAPE_BOX", "ADVANCED"}, CgColourRegion.keywords(Map.of("shape", "box",
                "shadows", Map.of("gain", List.of(1, 1, 1.2, 1)))));
        assertArrayEquals(new String[]{"TEMPERATURE_LEGACY"}, CgColourRegion.keywords(Map.of("temperature",
                Map.of("type", "legacy", "value", 4000))));
        // Unreal grades no temperature at 6500 K and no tint, whatever its type
        assertArrayEquals(new String[0], CgColourRegion.keywords(Map.of("temperature", Map.of("type", "white_balance"))));
    }

    @Test
    public void whatIsNotPlayedFailsByName() {
        CgAssetException e = assertThrows(CgAssetException.class,
                () -> CgColourRegion.of(Map.of("affected", Map.of("mode", "include")), "mymod:hit: spawnables.s1.colour_region"));
        assertEquals("mymod:hit: spawnables.s1.colour_region.affected: per-actor colour regions are not played yet", e.getMessage());
        assertThrows(CgAssetException.class, () -> CgColourRegion.of(Map.of("temperature",
                Map.of("type", "colour_temperature", "value", 5000)), "p"));
        assertThrows(CgAssetException.class, () -> CgColourRegion.of(Map.of("glow", 1), "p"));
    }

    /** The flash's gain scalar keyed from 2 to 1 by a property track on its components. */
    @Test
    public void aPropertyTrackKeysItsGrade() {
        CgSequenceDefinition d = CgSequenceDefinition.compile(CgAssetData.of("mymod:hit", CgSequenceKinds.SEQUENCE,
                (Map<?, ?>) CgAssetYaml.read(FLASH), null));
        CgSequencer sequencer = new CgWorld(false).add(new CgSequencer());
        CgColourRegionSpawner.install(sequencer);
        CgSequencePlayer player = sequencer.play(d, Map.of());
        sequencer.evaluate(0.05f);
        sequencer.evaluate(1f);
        if (player.failure() != null) throw player.failure();
        CgSequenceTarget flash = player.spawned(0);
        float[] gain = new float[4];
        flash.get(flash.slot("colour.gain"), gain);
        assertEquals("rgb as its block says", 2f, gain[0], 0f);
        assertEquals("its scalar keyed halfway down", 1.5f, gain[3], 0.05f);
        assertEquals(0.2f, value(flash, "falloff"), 0f);
    }

    private static float value(CgSequenceTarget t, String slot) {
        float[] out = new float[4];
        t.get(t.slot(slot), out);
        return out[0];
    }
}
