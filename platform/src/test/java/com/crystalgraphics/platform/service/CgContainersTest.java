package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgPlatform;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The container service's contract: a stack compares by its data, a drag is three clicks, and no host is no menu. */
public class CgContainersTest {

    @After
    public void clear() {
        CgPlatform.provide(CgContainers.HOST, null);
    }

    @Test
    public void aStackComparesByItsDataAndNotByTheHostsObject() {
        CgItemStack a = new CgItemStack("minecraft:stone", 16, 0, 0, 7, new Object());
        CgItemStack b = new CgItemStack("minecraft:stone", 16, 0, 0, 7, new Object());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(new CgItemStack("minecraft:stone", 15, 0, 0, 7, null)));
        assertFalse("a rename is another stack", a.equals(new CgItemStack("minecraft:stone", 16, 0, 0, 8, null)));
        assertEquals("every empty stack is one", CgItemStack.EMPTY, new CgItemStack("minecraft:air", 0, 0, 0, 3, null));
        assertTrue(CgItemStack.of("minecraft:stone", 0).isEmpty());
    }

    @Test
    public void withoutAHostThereIsNoMenuAndAClickDoesNothing() {
        assertNull(CgContainers.menu());
        CgContainers.click(0, 36, 0, CgContainers.Click.PICKUP);
        CgContainers.close();
    }

    @Test
    public void aDragIsThreeClicksPackedAsVanillaPacksThem() {
        List<int[]> sent = new ArrayList<>();
        CgPlatform.provide(CgContainers.HOST, new CgContainers.Host() {
            @Override public CgContainers.Menu menu() { return null; }
            @Override public void click(int id, int slot, int button, CgContainers.Click type) {
                assertEquals(CgContainers.Click.QUICK_CRAFT, type);
                sent.add(new int[]{id, slot, button});
            }
            @Override public void close() { }
        });

        CgContainers.dragStart(3, 1);
        CgContainers.dragAdd(3, 12, 1);
        CgContainers.dragEnd(3, 1);

        assertEquals(3, sent.size());
        assertEquals("start: outside, stage 0 of the right button", 0 | (1 << 2), sent.get(0)[2]);
        assertEquals(CgContainers.OUTSIDE, sent.get(0)[1]);
        assertEquals("a slot: stage 1", 1 | (1 << 2), sent.get(1)[2]);
        assertEquals(12, sent.get(1)[1]);
        assertEquals("end: stage 2", 2 | (1 << 2), sent.get(2)[2]);
    }

    @Test
    public void aMenuIsReadOnlyAndAnswersItsSlotsAndData() {
        List<CgContainers.Slot> slots = new ArrayList<>();
        slots.add(new CgContainers.Slot(0, CgItemStack.of("minecraft:coal", 2)));
        CgContainers.Menu menu = new CgContainers.Menu(4, "minecraft:furnace", slots, CgItemStack.EMPTY, Map.of("burn", 500));
        slots.add(new CgContainers.Slot(1, CgItemStack.EMPTY));

        assertEquals("a copy was taken", 1, menu.slots().size());
        assertEquals(500, menu.data("burn"));
        assertEquals(0, menu.data("cook"));
        assertFalse(menu.isPlayerInventory());
    }
}
