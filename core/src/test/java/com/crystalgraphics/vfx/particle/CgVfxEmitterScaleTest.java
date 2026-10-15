package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.shape.primitive.CgShell;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWords;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/** A scaled emitter plays the same burst at another size: the Step kernel gets its lengths and speeds times the scale. */
public class CgVfxEmitterScaleTest {

    private static final CgVfxEmitter BURST = CgVfxEmitter.builder("burst").capacity(200).burst(0f, 200)
            .shape(CgShell.of(1f, 3f)).launch(-0.2f, 1f, 1f).speed(4f, 12f).life(5f, 5f).size(0.2f, 0.6f, 1f)
            .module(new CgVfxModule.Gravity(9.8f))
            .module(new CgVfxModule.Drag(0.8f, 0.08f))
            .build();
    /**
     * Per lane of the spawn words: 's' scaled, '1' kept. Speed, life; size, skew, heat; spin, inherit; front; band, mode;
     * direction; kind, flags, arc, order; the shell's radii; rotation; offset.
     */
    private static final String SPAWN_LANES = "ss11" + "ss11" + "1111" + "ss11" + "1111" + "1111" + "1111" + "sss1"
            + "1111" + "sss1";

    private static float[] spawnWords(CgVfxEmitter emitter) {
        int[] words = new int[SPAWN_LANES.length()];
        CgVfxWords out = CgVfxWords.into(words, 0, words.length / 4, "spawn");
        emitter.writeSpawn(out);
        out.finish("its spawn");
        float[] lanes = new float[words.length];
        for (int i = 0; i < words.length; i++) lanes[i] = Float.intBitsToFloat(words[i]);
        return lanes;
    }

    @Test
    public void aScaledBurstSpawnsTheSameBurstScaled() {
        float scale = 0.375f;
        float[] full = spawnWords(BURST), small = spawnWords(BURST.scaled(scale));
        for (int i = 0; i < full.length; i++) {
            float want = SPAWN_LANES.charAt(i) == 's' ? full[i] * scale : full[i];
            assertEquals("lane " + i, want, small[i], 1e-6f);
        }
        assertEquals(new CgVfxModule.Gravity(9.8f * scale), BURST.scaled(scale).modules().get(0));
        assertEquals(new CgVfxModule.Drag(0.8f, 0.08f / scale), BURST.scaled(scale).modules().get(1));
    }

    @Test
    public void oneCopyPerScale() {
        assertSame(BURST, BURST.scaled(1f));
        assertSame(BURST.scaled(0.5f), BURST.scaled(0.5f));
    }
}
