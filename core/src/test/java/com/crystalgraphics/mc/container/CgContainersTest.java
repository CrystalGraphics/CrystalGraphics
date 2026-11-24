package com.crystalgraphics.mc.container;

import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The container service's contract: no host is no menu, a drag is three clicks, and the mirror copies only what changed. */
public class CgContainersTest {

    /** A menu of plain slots the test edits. */
    private static final class FakeHost implements CgContainers.Host {
        boolean present = true;
        int id = 4;
        String type = "minecraft:furnace";
        String title = "Furnace";
        CgItemStack[] stacks = {CgItemStack.of("minecraft:coal", 2), CgItemStack.EMPTY, CgItemStack.EMPTY};
        CgItemStack carried = CgItemStack.EMPTY;
        CgNbtCompound state = new CgNbtCompound().put("burn", 500);
        int copies;
        final List<Action> acted = new ArrayList<>();

        @Override public boolean present() { return present; }
        @Override public int id() { return id; }
        @Override public String type() { return type; }
        @Override public String title() { return title; }
        @Override public int size() { return stacks.length; }
        @Override public CgContainers.Layout layout(int slot) {
            return new CgContainers.Layout(0, slot, 8, 18 * slot, slot == 2 ? SlotKind.RESULT : SlotKind.CONTAINER, 64, null);
        }
        @Override public int stamp(int slot) { return stacks[slot].hashCode(); }
        @Override public CgItemStack stack(int slot) { copies++; return stacks[slot]; }
        @Override public boolean canTake(int slot) { return true; }
        @Override public int carriedStamp() { return carried.hashCode(); }
        @Override public CgItemStack carried() { return carried; }
        @Override public boolean mayPlace(int slot, CgItemStack stack) { return slot != 2; }
        @Override public CgNbtCompound state() { return state.copy(); }
        @Override public boolean act(int id, Action action) { return acted.add(action); }
        @Override public Capabilities capabilities() { return Capabilities.NONE; }
    }

    @After
    public void clear() {
        CgPlatform.provide(CgContainers.HOST, null);
    }

    @Test
    public void withoutAHostThereIsNoMenuAndAnActionDoesNothing() {
        assertNull(CgContainers.menu());
        assertFalse(CgContainers.present());
        assertFalse(CgContainers.act(0, new Action.Close()));
        assertFalse(CgContainers.act(0, new Action.Click(36, 0, ClickType.PICKUP)));
    }

    @Test
    public void aDragIsThreeClicksPackedAsVanillaPacksThem() {
        Action.Click start = Action.Click.dragStart(1);
        Action.Click add = Action.Click.dragAdd(12, 1);
        Action.Click end = Action.Click.dragEnd(1);

        assertEquals(ClickType.QUICK_CRAFT, start.type());
        assertEquals("start: outside, stage 0 of the right button", 0 | (1 << 2), start.button());
        assertEquals(CgContainers.OUTSIDE, start.slot());
        assertEquals("a slot: stage 1", 1 | (1 << 2), add.button());
        assertEquals(12, add.slot());
        assertEquals("end: stage 2", 2 | (1 << 2), end.button());
    }

    @Test
    public void theFirstPollOpensTheMenuWithEverySlot() {
        CgPlatform.provide(CgContainers.HOST, new FakeHost());
        ContainerMirror mirror = CgContainers.mirror();

        ContainerMirror.Update update = mirror.poll();

        assertTrue(update.opened());
        assertArrayEquals(new int[]{0, 1, 2}, update.slots());
        assertEquals("minecraft:coal", update.menu().slot(0).stack().item());
        assertEquals(SlotKind.RESULT, update.menu().slot(2).kind());
        assertEquals("Furnace", update.menu().title());
        assertEquals(500, update.menu().state().getInt("burn"));
        assertNull("nothing changed since", mirror.poll());
    }

    @Test
    public void aPollCopiesOnlyTheSlotThatChanged() {
        FakeHost host = new FakeHost();
        ContainerMirror mirror = new ContainerMirror(host);
        mirror.poll();
        host.copies = 0;

        host.stacks[1] = CgItemStack.of("minecraft:iron_ingot", 1);
        ContainerMirror.Update update = mirror.poll();

        assertFalse(update.opened());
        assertArrayEquals(new int[]{1}, update.slots());
        assertEquals("one stack copied, not the menu", 1, host.copies);
        assertEquals("the other slot is the very record it was", update.menu().slot(0), mirror.menu().slot(0));
        assertFalse(update.carried());
        assertFalse(update.state());
    }

    @Test
    public void aCountChangeIsAChange() {
        FakeHost host = new FakeHost();
        ContainerMirror mirror = new ContainerMirror(host);
        mirror.poll();

        host.stacks[0] = CgItemStack.of("minecraft:coal", 1);

        assertArrayEquals(new int[]{0}, mirror.poll().slots());
    }

    @Test
    public void theCursorAndTheStateAreWatchedToo() {
        FakeHost host = new FakeHost();
        ContainerMirror mirror = new ContainerMirror(host);
        mirror.poll();

        host.carried = CgItemStack.of("minecraft:stick", 3);
        host.state = new CgNbtCompound().put("burn", 400);
        ContainerMirror.Update update = mirror.poll();

        assertTrue(update.carried());
        assertTrue(update.state());
        assertEquals(0, update.slots().length);
        assertEquals("minecraft:stick", update.menu().carried().item());
        assertEquals(400, update.menu().state().getInt("burn"));
    }

    @Test
    public void aNewWindowIdIsANewOpeningAndNoPlayerIsAClose() {
        FakeHost host = new FakeHost();
        ContainerMirror mirror = new ContainerMirror(host);
        mirror.poll();

        host.id = 5;
        assertTrue(mirror.poll().opened());

        host.present = false;
        ContainerMirror.Update closed = mirror.poll();
        assertTrue(closed.closed());
        assertNull(closed.menu());
        assertNull(mirror.menu());
        assertNull("closed once", mirror.poll());
    }

    @Test
    public void everyMenuTypeOf263IsExtractedPlainOrNamedUnsupported() {
        String[] vanilla = {"generic_9x1", "generic_9x2", "generic_9x3", "generic_9x4", "generic_9x5", "generic_9x6", "generic_3x3",
                "crafter_3x3", "anvil", "beacon", "blast_furnace", "brewing_stand", "crafting", "enchantment", "furnace",
                "grindstone", "hopper", "lectern", "loom", "merchant", "shulker_box", "smithing", "smoker", "cartography_table",
                "stonecutter"};
        for (String name : vanilla) {
            MenuTypes.Support support = MenuTypes.support("minecraft:" + name);
            assertTrue(name, support != MenuTypes.Support.UNKNOWN);
            if (support == MenuTypes.Support.UNSUPPORTED) assertFalse(name, MenuTypes.reason("minecraft:" + name).isEmpty());
        }
        assertEquals(MenuTypes.Support.UNKNOWN, MenuTypes.support("mymod:chest"));
    }
}
