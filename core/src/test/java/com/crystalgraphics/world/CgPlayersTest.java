package com.crystalgraphics.world;

import com.crystalgraphics.net.object.CgNetTestRig;
import org.junit.Test;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/** Players found through the facade, on a rig's server. */
public class CgPlayersTest {

    @Test
    public void aPlayerIsFoundByItsEntityIdAndItsProfileOnTheServer() {
        try (CgNetTestRig rig = CgNetTestRig.of(2).tick(1)) {
            CgNetTestRig.Client bob = rig.client(1);
            assertSame(bob, CgPlayers.player(2));
            assertSame(bob, CgPlayers.player(bob.id()));
            assertSame(bob.id(), CgPlayers.idOf(bob));
            assertNull("no player has id 9", CgPlayers.player(9));
        }
    }
}
