package com.crystalgraphics.net;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;

/** {@link CgNetwork#side()} in one process holding a client and its integrated server. */
public class CgNetworkSideTest {

    @After
    public void tearDown() {
        CgNetTesting.reset();
    }

    @Test
    public void theServersThreadIsTheServerAndTheClientsTheClient() throws InterruptedException {
        CgNetwork.clientTick();
        AtomicReference<CgNetwork.Side> onServer = new AtomicReference<>();
        Thread server = new Thread(() -> {
            CgNetwork.serverStarting(null);
            onServer.set(CgNetwork.side());
        });
        server.start();
        server.join();
        assertEquals(CgNetwork.Side.SERVER, onServer.get());
        assertEquals(CgNetwork.Side.CLIENT, CgNetwork.side());
    }
}
