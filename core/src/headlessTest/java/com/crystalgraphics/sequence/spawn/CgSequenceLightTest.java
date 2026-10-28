package com.crystalgraphics.sequence.spawn;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.joml.Quaternionf;
import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.stage.CgDirectionalLights;
import com.crystalgraphics.render.stage.CgHostEnvironment;
import com.crystalgraphics.render.stage.CgWorldAtmosphere;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.binding.CgSequenceRole;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.world.CgAttachment;

/** A sequence's directional light: chosen over the sun by priority, then brightness, and riding its node. */
public class CgSequenceLightTest {

    private static final String MOVE = "_v: 1\ntick_resolution: 20\ndisplay_rate: 20\nrange: [0, 20]\n"
            + "roles:\n  - { id: b1, name: caster, kind: caster }\n"
            + "spawnables:\n  - { id: s1, name: cut_in, at: { role: caster, socket: aim },\n"
            + "      light: { type: TYPE, colour: [1, 0.5, 0.5], intensity: 10, priority: 1 } }\ntracks: []\n";

    /** Its aim socket turned a quarter about +Y: its forward, +X, turned to -Z. */
    private static final class Caster implements CgSequenceRole {
        @Override public CgAttachment attachment(String socket) {
            return (point, turn) -> {
                point[0] = point[1] = point[2] = 0;
                if (turn != null) turn.set(new Quaternionf().rotationY((float) (Math.PI / 2)));
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

    private static CgSequenceDefinition compile(String type) {
        return CgSequenceDefinition.compile(CgAssetData.of("mymod:cut", CgSequenceKinds.SEQUENCE,
                (Map<?, ?>) CgAssetYaml.read(MOVE.replace("TYPE", type)), null));
    }

    @Test
    public void theHighestPriorityLightsTheWorld_aTieTheBrighter_theSunBelowBoth() {
        CgDirectionalLights lights = CgDirectionalLights.get();
        try (CgDirectionalLights.Light dim = lights.add(0).colour(0xFFFFFFFF, 0.5f)) {
            assertNull("at 0 and dimmer than the sun, the sun lights the world", lights.active(1f));
            try (CgDirectionalLights.Light a = lights.add(1).colour(0xFFFFFFFF, 2f);
                 CgDirectionalLights.Light b = lights.add(1).colour(0xFFFFFFFF, 5f)) {
                assertSame(b, lights.active(1f));
                assertSame("a priority above another's whatever its brightness", b, lights.active(100f));
            }
            assertNull(lights.active(1f));
        }
        assertThrows(IllegalArgumentException.class, () -> lights.add(-1));
    }

    @Test
    public void aSpawnedSkillLight_takesTheSunsDirection_whileItLives() {
        CgSequencer sequencer = new CgWorld().add(new CgSequencer());
        CgSequencePlayer play = sequencer.play(compile("directional"), Map.of("caster", new Caster()));
        sequencer.evaluate(0.05f);
        CgDirectionalLights.Light light = CgDirectionalLights.get().active(1f);
        assertTrue("spawned", light != null);
        assertArrayEquals("travels along its node's +X", new float[] {0f, 0f, -1f}, new float[] {light.dx(), light.dy(), light.dz()}, 1e-5f);

        CgWorldAtmosphere.apply(new CgHostEnvironment(), new CgPassConstants());
        float[] v = CgDirectionalLights.DIRECTION.get(new float[4]);
        assertArrayEquals("the light vector points back at it", new float[] {0f, 0f, 1f}, new float[] {v[0], v[1], v[2]}, 1e-5f);
        CgDirectionalLights.COLOUR.get(v);
        assertArrayEquals(new float[] {10f, 5f, 5f}, new float[] {v[0], v[1], v[2]}, 1e-5f);

        play.stop();
        sequencer.evaluate(0.05f);
        assertNull("gone with its play", CgDirectionalLights.get().active(1f));
        CgWorldAtmosphere.apply(new CgHostEnvironment(), new CgPassConstants());
        CgDirectionalLights.DIRECTION.get(v);
        assertEquals("the sun's again", CgPassConstants.SUN_Y, v[1], 0.05f);
    }

    @Test
    public void aPointLightFailsAsItSpawns_namingIt() {
        CgSequencer sequencer = new CgWorld().add(new CgSequencer());
        CgAssetException e = assertThrows(CgAssetException.class, () -> {
            sequencer.play(compile("point"), Map.of("caster", new Caster()));
            sequencer.evaluate(0.05f);
        });
        assertTrue(e.getMessage(), e.getMessage().contains("spawnables.s1.light"));
    }
}
