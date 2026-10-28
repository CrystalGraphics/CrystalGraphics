package com.crystalgraphics.sequence.event;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/** One-shots fire as play sweeps across them, once, in order, as Unreal's {@code EvaluateSwept}. */
public class CgEventChannelTest {

    private static final int FRAME = 4800;      // 200 ms at 24000 ticks a second

    private static List<String> sweep(CgEventChannel c, int from, int to) {
        List<String> out = new ArrayList<>();
        c.sweep(from, to, (tick, name) -> out.add(name + "@" + tick));
        return out;
    }

    @Test
    public void anEventInsideALongFrameFiresOnce() {
        CgEventChannel c = new CgEventChannel(new int[] {1000, 2000, 7000}, new String[] {"a", "b", "c"}, true, false);
        c.place(-1);
        assertEquals(List.of("a@1000", "b@2000"), sweep(c, 0, FRAME));
        assertEquals(List.of("c@7000"), sweep(c, FRAME, 2 * FRAME));
        assertEquals(List.of(), sweep(c, FRAME, 2 * FRAME));
    }

    @Test
    public void aKeyOnAFrameBoundaryFiresInExactlyOneFrame() {
        CgEventChannel c = new CgEventChannel(new int[] {FRAME}, new String[] {"edge"}, true, false);
        c.place(0);
        List<String> all = new ArrayList<>(sweep(c, 0, FRAME));
        all.addAll(sweep(c, FRAME, 2 * FRAME));
        assertEquals(List.of("edge@" + FRAME), all);
    }

    @Test
    public void backwardsFiresInReverseOnlyWhereSwitchedOn() {
        int[] ticks = {10, 20, 30};
        String[] names = {"a", "b", "c"};
        CgEventChannel on = new CgEventChannel(ticks, names, true, true), off = new CgEventChannel(ticks, names, true, false);
        on.place(40);
        off.place(40);
        assertEquals(List.of("c@30", "b@20"), sweep(on, 40, 15));
        assertEquals(List.of(), sweep(off, 40, 15));
        assertEquals(List.of("a@10"), sweep(on, 15, 5));
    }

    @Test
    public void aSeekFiresNothingAndPlayContinuesFromThere() {
        CgEventChannel c = new CgEventChannel(new int[] {10, 20, 30}, new String[] {"a", "b", "c"}, true, false);
        c.place(25);
        assertEquals(List.of("c@30"), sweep(c, 25, 40));
    }

    @Test
    public void aTriggeredSectionRestartsOnASecondEvent() {
        CgEventTrigger swing = new CgEventTrigger("swing", 2400);
        assertFalse(swing.active(5000));
        assertFalse(swing.fire("other", 5000, 7));
        assertTrue(swing.fire("swing", 5000, 7));
        long first = swing.seed();
        assertTrue(swing.active(7399));
        assertFalse(swing.active(7400));
        assertEquals(600, swing.local(5600));
        assertTrue(swing.fire("swing", 6000, 7));
        assertEquals(2, swing.count());
        assertTrue(swing.active(8000));
        assertEquals(0, swing.local(6000));
        assertNotEquals(first, swing.seed());
    }
}
