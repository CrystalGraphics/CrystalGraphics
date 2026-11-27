package com.crystalgraphics.net.object;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.sun.management.ThreadMXBean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.serialization.CgCodecs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Watched objects and fields: sent to those with it open, set when told, never polled. */
public class CgNetWatchedTest {

    static final CgNetField<Integer> PROGRESS = CgNetField.ofInt("progress");
    static final CgNetField<Integer> SPEED = CgNetField.ofInt("speed").predicted();
    static final CgNetCommand<Integer> GRIND = CgNetCommand.of("grind", CgCodecs.INT);
    /** A machine's screen: seen only by who has it open. */
    static final CgNetType GRINDER = CgNetType.define("test:grinder").fields(PROGRESS, SPEED).commands(GRIND)
            .range(32).watched().build();

    static final CgNetField<Float> GLOW = CgNetField.ofFloat("glow");
    static final CgNetField<Integer> FUEL = CgNetField.ofInt("fuel").watched();
    /** A furnace: its glow to everyone in range, its fuel to who has it open. */
    static final CgNetType FURNACE = CgNetType.define("test:furnace").fields(GLOW, FUEL).range(32).build();

    private final List<String> heard = new ArrayList<>();
    private CgNetTestRig rig;

    @Before
    public void setUp() {
        rig = CgNetTestRig.of(2);
        CgNetBehaviour told = new CgNetBehaviour() {
            @Override
            public void watched(CgNetObject obj, UUID player) {
                heard.add("watched " + index(player));
            }

            @Override
            public void unwatched(CgNetObject obj, UUID player, CgNetUnwatched why) {
                heard.add("unwatched " + index(player) + " " + why);
            }
        };
        FURNACE.onServer(obj -> told);
        GRINDER.onServer(obj -> told);
        GRINDER.onCommand(GRIND, (obj, sender, amount) -> obj.setInt(PROGRESS, obj.getInt(PROGRESS) + amount));
        rig.tick(3);   // the hellos
    }

    @After
    public void tearDown() {
        rig.close();
    }

    private int index(UUID player) {
        return rig.client(0).id().equals(player) ? 0 : 1;
    }

    @Test
    public void aWatcherGetsTheObjectAndItsFieldsNextTickAndADespawnOnUnwatch() {
        CgNetObject grinder = GRINDER.spawn(rig.at(0, 0, 0)).start();
        grinder.setInt(PROGRESS, 5);
        rig.tick(2);
        assertTrue("watched: nobody sees it until told", rig.client(0).visible(GRINDER).isEmpty());

        grinder.watch(rig.client(0).id());
        assertTrue("applied at the tick's end", grinder.watchers().isEmpty());
        rig.tick(2);
        List<CgNetObject> seen = rig.client(0).visible(GRINDER);
        assertEquals(1, seen.size());
        assertEquals(5, seen.get(0).getInt(PROGRESS));
        assertTrue(seen.get(0).isWatching());
        assertTrue("only its watcher", rig.client(1).visible(GRINDER).isEmpty());
        assertEquals(List.of(rig.client(0).id()), grinder.watchers());

        grinder.unwatch(rig.client(0).id());
        rig.tick(2);
        assertTrue("despawned on unwatch", rig.client(0).visible(GRINDER).isEmpty());
        assertEquals(List.of("watched 0", "unwatched 0 ASKED"), heard);
    }

    @Test
    public void aWatchAndAnUnwatchInOneTickSendNothing() {
        CgNetObject grinder = GRINDER.spawn(rig.at(0, 0, 0)).start();
        rig.tick(2);
        long before = rig.client(0).bytesReceived();
        grinder.watch(rig.client(0).id());
        grinder.unwatch(rig.client(0).id());
        rig.tick(2);
        assertTrue(rig.client(0).visible(GRINDER).isEmpty());
        assertEquals("nothing sent", before, rig.client(0).bytesReceived());
        assertTrue("and nothing told", heard.isEmpty());
    }

    @Test
    public void aWatchedFieldReachesItsWatchersOnlyAsABaselineThenChanges() {
        CgNetObject furnace = FURNACE.spawn(rig.at(0, 0, 0)).start();
        furnace.setFloat(GLOW, 0.5f).setInt(FUEL, 7);
        furnace.watch(rig.client(0).id());
        rig.tick(2);
        CgNetObject watching = rig.client(0).visible(FURNACE).get(0);
        CgNetObject near = rig.client(1).visible(FURNACE).get(0);
        assertEquals(7, watching.getInt(FUEL));
        assertTrue(watching.isWatching());
        assertEquals("everyone in range gets its glow", 0.5f, near.getFloat(GLOW), 0f);
        assertFalse("and not its fuel", near.has(FUEL));

        furnace.setInt(FUEL, 8);
        rig.tick(2);
        assertEquals(8, watching.getInt(FUEL));
        assertFalse(near.has(FUEL));

        furnace.unwatch(rig.client(0).id());
        rig.tick(2);
        assertFalse("unwatched: absent, not the last value", watching.has(FUEL));
        assertEquals(0, watching.getInt(FUEL));
        assertFalse(watching.isWatching());

        furnace.setInt(FUEL, 9);
        rig.tick(2);
        furnace.watch(rig.client(0).id());
        rig.tick(2);
        assertEquals("watched again: the whole current value, not a delta", 9, watching.getInt(FUEL));
        assertTrue(watching.has(FUEL));
    }

    @Test
    public void aWatcherWhoGoesAwayIsUnwatchedAndTheBehaviourHearsWhy() {
        CgNetObject furnace = FURNACE.spawn(rig.at(0, 0, 0)).start();
        furnace.setInt(FUEL, 3);
        furnace.watch(rig.client(0).id()).watch(rig.client(1).id());
        rig.tick(2);
        rig.client(0).moveTo(500, 0, 0);
        rig.tick(2);
        rig.client(1).dimension("test:elsewhere");
        rig.tick(2);
        assertTrue(furnace.watchers().isEmpty());
        assertTrue(heard.contains("unwatched 0 RANGE"));
        assertTrue(heard.contains("unwatched 1 DIMENSION"));

        rig.client(0).moveTo(0, 0, 0);
        rig.tick(2);
        furnace.watch(rig.client(0).id());
        rig.tick(2);
        rig.client(0).disconnect();
        rig.tick(3);
        assertTrue(heard.contains("unwatched 0 LEFT"));
    }

    @Test
    public void aCommandFromANonWatcherIsRefusedAndCounted() {
        CgNetObject grinder = GRINDER.spawn(rig.at(0, 0, 0)).start();
        grinder.watch(rig.client(0).id());
        rig.tick(2);
        CgNetObject mine = rig.client(0).visible(GRINDER).get(0);
        mine.send(GRIND, 2);
        rig.tick(2);
        assertEquals("a watcher's command runs", 2, grinder.getInt(PROGRESS));

        long refused = GRIND.refused();
        rig.latencyTicks(3);
        mine.send(GRIND, 2);            // in flight while the server unwatches them
        grinder.unwatch(rig.client(0).id());
        rig.tick(8);
        assertEquals("not a watcher's by the time it arrives", 2, grinder.getInt(PROGRESS));
        assertEquals(refused + 1, GRIND.refused());
    }

    @Test
    public void aPredictedFieldOfAWatchedTypeIsSetByItsWatchers() {
        CgNetObject grinder = GRINDER.spawn(rig.at(0, 0, 0)).start();
        grinder.watch(rig.client(0).id());
        rig.tick(2);
        CgNetObject watching = rig.client(0).visible(GRINDER).get(0);
        assertFalse("not its owner", watching.isMine());
        watching.setInt(SPEED, 4);
        rig.tick(3);
        assertEquals("a watcher's prediction lands", 4, grinder.getInt(SPEED));

        long refused = SPEED.command.refused();
        rig.latencyTicks(3);
        watching.setInt(SPEED, 6);      // in flight while the server unwatches them
        grinder.unwatch(rig.client(0).id());
        rig.tick(8);
        assertEquals("from a player no longer watching: refused", 4, grinder.getInt(SPEED));
        assertEquals(refused + 1, SPEED.command.refused());
    }

    @Test
    public void aQuietTickWithAThousandWatchedObjectsSendsAndAllocatesNothing() {
        List<CgNetObject> furnaces = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            CgNetObject furnace = FURNACE.spawn(rig.at(i % 20, 0, i / 50)).start();
            furnace.setInt(FUEL, i).watch(rig.client(i % 2).id());
            furnaces.add(furnace);
        }
        rig.tick(40);
        for (CgNetObject furnace : furnaces) assertEquals(1, furnace.watchers().size());
        long sent0 = rig.client(0).bytesReceived(), sent1 = rig.client(1).bytesReceived();
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId();
        for (int warm = 0; warm < 3000; warm++) CgNetObjects.serverTick();
        long quiet = Long.MAX_VALUE;
        for (int window = 0; window < 3 && quiet != 0; window++) {
            long before = threads.getThreadAllocatedBytes(thread);
            for (int t = 0; t < 1000; t++) CgNetObjects.serverTick();
            quiet = threads.getThreadAllocatedBytes(thread) - before;
        }
        rig.tick(2);
        assertEquals("nothing sent", sent0, rig.client(0).bytesReceived());
        assertEquals(sent1, rig.client(1).bytesReceived());
        assertEquals("nothing allocated in 1000 quiet ticks", 0, quiet);
    }
}
