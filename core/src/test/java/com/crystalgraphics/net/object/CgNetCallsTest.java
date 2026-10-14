package com.crystalgraphics.net.object;

import java.util.ArrayList;
import java.util.List;

import com.crystalgraphics.net.rate.CgRatePolicy;
import com.crystalgraphics.serialization.CgCodecs;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/** Events from the server to viewers and commands from a client to the server, on the rig. */
public class CgNetCallsTest {

    static final CgNetField<Float> HEAT = CgNetField.ofFloat("heat");
    static final CgNetEvent<String> SAID = CgNetEvent.of("said", CgCodecs.STRING);
    static final CgNetEvent<Void> PING = CgNetEvent.of("ping");
    static final CgNetCommand<Float> SET_HEAT = CgNetCommand.of("set_heat", CgCodecs.FLOAT)
            .sanitizedBy((obj, heat) -> Math.max(0f, Math.min(1f, heat))).perSecond(5);
    static final CgNetCommand<Float> AIM = CgNetCommand.of("aim", CgCodecs.FLOAT).rate(CgRatePolicy.throttle(100)).perSecond(100);
    static final CgNetCommand<Void> POKE = CgNetCommand.of("poke").fromAnyViewer();

    static final CgNetType MACHINE = CgNetType.define("test:machine").fields(HEAT).events(SAID, PING)
            .commands(SET_HEAT, AIM, POKE).range(32).build();

    private final List<String> heard = new ArrayList<>();
    private CgNetTestRig rig;
    private CgNetObject machine;

    @Before
    public void setUp() {
        rig = CgNetTestRig.of(2);
        MACHINE.onClient(obj -> new CgNetBehaviour() {
            @Override
            public void changed(CgNetObject o, CgNetField<?> field) {
                heard.add("heat " + o.getFloat(HEAT));
            }

            @Override
            public void event(CgNetObject o, CgNetEvent<?> event, Object value) {
                heard.add(event.name() + " " + value + " at heat " + o.getFloat(HEAT));
            }

            @Override
            public void despawned(CgNetObject o, boolean killed) {
                heard.add("despawned");
            }
        });
        MACHINE.onCommand(SET_HEAT, (obj, sender, heat) -> obj.setFloat(HEAT, heat));
        MACHINE.onCommand(AIM, (obj, sender, yaw) -> heard.add("aim " + yaw + " from " + sender.name()));
        MACHINE.onCommand(POKE, (obj, sender, nothing) -> heard.add("poked by " + sender.name()));
        rig.tick(3);
        machine = MACHINE.spawn(rig.at(0, 0, 0)).owner(rig.client(0).id()).start();
        rig.tick(1);
    }

    @After
    public void tearDown() {
        rig.close();
    }

    private CgNetObject seenBy(int client) {
        return rig.client(client).visible(MACHINE).get(0);
    }

    @Test
    public void anEventArrivesAfterTheTicksChangesInEmitOrder() {
        rig.client(1).disconnect();
        heard.clear();
        machine.emit(SAID, "first").setFloat(HEAT, 0.5f).emit(PING, null).emit(SAID, "second");
        rig.tick(1);
        assertEquals(List.of("heat 0.5", "said first at heat 0.5", "ping null at heat 0.5", "said second at heat 0.5"), heard);
    }

    @Test
    public void anEventReachesNoLaterViewerAndAnOwnersEventOnlyItsOwner() {
        rig.client(1).moveTo(500, 0, 0);
        rig.tick(1);
        heard.clear();
        machine.emit(SAID, "missed").emitToOwner(SAID, "private");
        rig.tick(1);
        assertEquals(List.of("said missed at heat 0.0", "said private at heat 0.0"), heard);
        heard.clear();
        rig.client(1).moveTo(0, 0, 0);
        rig.tick(2);
        assertEquals("a later viewer never hears what it missed", List.of(), heard);
        machine.emitToOwner(SAID, "again");
        rig.tick(1);
        assertEquals(List.of("said again at heat 0.0"), heard);
    }

    @Test
    public void anEventInTheDespawnTickArrivesBeforeTheDespawn() {
        rig.client(1).disconnect();
        heard.clear();
        machine.despawn();
        machine.emit(SAID, "goodbye");
        rig.tick(1);
        assertEquals(List.of("said goodbye at heat 0.0", "despawned"), heard);
    }

    @Test
    public void theTicksChangesArriveBeforeItsEventsInTheTickItEnds() {
        rig.client(1).disconnect();
        heard.clear();
        machine.emit(SAID, "last").setFloat(HEAT, 0.75f).kill();
        rig.tick(1);
        assertEquals(List.of("heat 0.75", "said last at heat 0.75", "despawned"), heard);
    }

    @Test
    public void theOwnersCommandRunsSanitizedAndOthersAreRefused() {
        seenBy(0).send(SET_HEAT, 7f);
        rig.tick(2);
        assertEquals("clamped, not refused", 1f, machine.getFloat(HEAT), 0f);
        assertThrows(IllegalStateException.class, () -> seenBy(1).send(SET_HEAT, 0.5f));
        long refusedBefore = SET_HEAT.refused();
        seenBy(1).send(POKE, null);
        rig.tick(2);
        assertEquals(List.of("heat 1.0", "heat 1.0", "poked by player1"), heard);
        assertEquals(refusedBefore, SET_HEAT.refused());
    }

    @Test
    public void aCommandOverTheRateOrOutOfSightIsRefusedAndCounted() {
        long refused = SET_HEAT.refused();
        for (int i = 0; i < 9; i++) seenBy(0).send(SET_HEAT, i / 10f);
        rig.tick(2);
        assertEquals("a burst of five, the rest refused", refused + 4, SET_HEAT.refused());
        assertEquals(0.4f, machine.getFloat(HEAT), 1e-6f);

        CgNetObject mine = seenBy(0);
        rig.client(0).moveTo(500, 0, 0);
        rig.tick(40);
        refused = SET_HEAT.refused();
        mine.send(SET_HEAT, 0.9f);   // an object this client no longer holds sends nothing
        rig.tick(2);
        assertEquals(0.4f, machine.getFloat(HEAT), 1e-6f);
        assertEquals(refused, SET_HEAT.refused());
    }

    @Test
    public void aThrottledCommandDropsTheMiddleButNeverTheLastValue() {
        CgNetObject mine = seenBy(0);
        for (int t = 0; t < 10; t++) {
            mine.send(AIM, (float) t);
            rig.tick(1);
        }
        rig.tick(4);
        assertEquals("the first at once, then what is held as each 100 ms window ends, and the last",
                List.of("aim 0.0 from player0", "aim 1.0 from player0", "aim 3.0 from player0", "aim 5.0 from player0",
                        "aim 7.0 from player0", "aim 9.0 from player0"), heard);
    }
}
