package com.crystalgraphics.tag;

import com.crystalgraphics.net.object.CgNetTestRig;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/** The table a server sends at login, and a client with another mod set mapping onto it. */
public class CgTagNetTest {

    private CgNetTestRig rig;

    @Before
    public void setUp() {
        CgTagRegistry.global().register("tagnet:cue.beam.fire");
        CgTagRegistry.global().register("tagnet:state.charging");
        rig = CgNetTestRig.of(1);
    }

    @After
    public void tearDown() {
        rig.close();
    }

    @Test
    public void aClientIsSentTheTableOnceTheServerTicks() {
        assertFalse("nothing arrived before the first tick", CgTagNet.fromServer().agreed());
        rig.tick(4);
        CgTagLink toClient = CgTagNet.server();
        assertNotNull(toClient);
        assertEquals(CgTagRegistry.global().table(), toClient.table());
        assertTrue(CgTagNet.fromServer().agreed());
        assertEquals(toClient.table(), CgTagNet.fromServer().table());
    }

    @Test
    public void aClientWithAnotherModSetMapsTheServersIndices() {
        rig.tick(4);
        CgTagRegistry client = new CgTagRegistry();
        CgGameplayTag fire = client.register("tagnet:cue.beam.fire");
        CgTagLink link = CgTagLink.client(client);
        assertThrowsBeforeAgreement(link);
        link.agree(CgTagNet.server().table());

        CgTagLink toClient = CgTagNet.server();
        int wire = toClient.toWire(CgTagRegistry.global().require("tagnet:cue.beam.fire"));
        assertSame(fire, link.fromWire(wire));
        assertNull(link.fromWire(toClient.toWire(CgTagRegistry.global().require("tagnet:state.charging"))));
    }

    @Test
    public void aTagRegisteredLaterIsSentInAFreshTable() {
        rig.tick(4);
        CgTagTable before = CgTagNet.server().table();
        CgGameplayTag late = CgTagRegistry.global().register("tagnet:late." + System.nanoTime());
        rig.tick(3);
        CgTagLink toClient = CgTagNet.server();
        assertNotEquals(before, toClient.table());
        assertNotNull(CgTagNet.fromServer().fromWire(toClient.toWire(late)));
    }

    private static void assertThrowsBeforeAgreement(CgTagLink link) {
        try {
            link.fromWire(0);
            fail("an index before the table is agreed must fail");
        } catch (CgTagException expected) {
        }
    }
}
