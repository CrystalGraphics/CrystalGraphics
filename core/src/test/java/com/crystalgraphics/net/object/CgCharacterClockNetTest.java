package com.crystalgraphics.net.object;

import com.crystalgraphics.world.CgCharacterClock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** A character's clock on the rig, with latency: the server's, a viewer's and a late joiner's agree. */
public class CgCharacterClockNetTest {

    private static final int LATENCY = 3;
    private static long level;

    private CgNetTestRig rig;
    private CgNetTestRig.Entity zombie;
    private final CgCharacterClock server = new CgCharacterClock();

    @Before
    public void setUp() {
        level = 100;
        CgCharacterClock.forgetAll();
        CgCharacterClockNet.install(() -> level);
        rig = CgNetTestRig.of(3).latencyTicks(LATENCY);
        zombie = rig.entity(10, 64, 0);
        rig.client(2).moveTo(500, 64, 0);
        rig.tick(2);
    }

    @After
    public void tearDown() {
        rig.close();
        CgCharacterClock.forgetAll();
    }

    /** One game tick on both sides: the server advances and publishes, the viewers hear after the latency. */
    private void step() {
        level++;
        server.advance();
        CgCharacterClockNet.publish(zombie, server, level);
        rig.tick(1);
    }

    private CgCharacterClock viewer() {
        return CgCharacterClock.find(zombie.id());
    }

    @Test
    public void aFreezeHeardLateHoldsItsFullLengthAndNeverStepsBack() {
        for (int i = 0; i < 20; i++) step();
        // the freeze begins at tick P; the viewer hears LATENCY ticks later
        server.freeze(6);
        CgCharacterClock seen = null;
        double last = -1;
        int held = 0, longestHold = 0;
        for (int i = 0; i < 30; i++) {
            step();
            seen = viewer();
            assertNotNull(seen);
            double now = seen.now(0f);
            assertTrue("never steps back: " + now + " after " + last, now >= last - 1e-9);
            held = Math.abs(now - last) < 1e-9 ? held + 1 : 0;
            longestHold = Math.max(longestHold, held);
            last = now;
        }
        assertEquals("hitstop reads as long as it was, everywhere", 6, longestHold);
        assertEquals("and then it is the server's time", server.exact(0f), seen.exact(0f), 1e-9);
        assertEquals(server.exact(0f), seen.now(0f), 1e-9);
    }

    @Test
    public void aViewerAndTheServerAgreeOnTheRateAndTheTicks() {
        for (int i = 0; i < 10; i++) step();
        server.rate(0.5f);
        for (int i = 0; i < 20; i++) step();
        assertEquals(server.ticks(), viewer().ticks());
        assertEquals(server.exact(0f), viewer().exact(0f), 1e-9);
        assertEquals(0.5f, viewer().rate(), 0f);
    }

    @Test
    public void aLateJoinerComputesTheTimeFromTheState() {
        for (int i = 0; i < 10; i++) step();
        server.freeze(8);
        server.rate(0.5f);
        for (int i = 0; i < 4; i++) step();
        // client 2 comes into range mid-freeze
        rig.client(2).moveTo(10, 64, 0);
        for (int i = 0; i < 12; i++) step();
        CgCharacterClock joined = CgCharacterClock.find(zombie.id());
        assertNotNull(joined);
        assertEquals(server.ticks(), joined.ticks());
        assertEquals(server.exact(0f), joined.exact(0f), 1e-9);
        assertEquals(server.frozenTicks(), joined.frozenTicks());
    }
}
