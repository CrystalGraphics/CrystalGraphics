package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What an instance's books decide about events (plan vfx-gpu X5): its children's instance steps beside it, and it
 * finishes only once the last child its last particle could spawn has lived its longest life. Where children spawn on
 * the GPU is the harness's {@code vfx-events} gate.
 */
public class CgVfxEmitterEventsTest {

    private static final float DT = 1f / 60f;

    @Test
    public void aParentFinishesWithItsChildren() {
        CgVfxEmitter smoke = CgVfxEmitter.builder("smoke").life(1f, 1f).speed(0f, 1f).build();
        CgVfxEmitter flash = CgVfxEmitter.builder("flash").burst(0f, 20).life(0.2f, 0.2f)
                .event(CgVfxEvent.onAge(0.1f).spawn(smoke, 1)).build();
        CgVfxEmitterInstance instance = new CgVfxEmitterInstance(flash, 0.5f);
        instance.start(0f, 0f, 0f);
        int finished = -1;
        for (int step = 0; step < 200 && finished < 0; step++) {
            instance.schedule(DT, 0.0, 0.0, 0.0);
            assertTrue(instance.child(0).scheduled());
            assertEquals(0, instance.child(0).stepCandidates());
            if (instance.finished()) finished = step;
        }
        // Its own last particle goes at 0.2 s; a child it spawns then lives a second more.
        float at = (finished + 1) * DT;
        assertTrue("finished at " + at + " s", at >= 1.2f && at <= 1.2f + 3 * DT);
    }
}
