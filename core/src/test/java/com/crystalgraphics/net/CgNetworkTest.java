package com.crystalgraphics.net;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgraphics.net.protocol.CgProtocols;
import com.crystalgraphics.platform.service.CgNetworkChannel;
import com.crystalgraphics.serialization.CgPlainOps;
import com.crystalgraphics.serialization.CgStateMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** {@link CgNetwork} over one in-process channel, as in single player: both tables, one process. */
public class CgNetworkTest {

    /** A player handle as a loader hands one over: a new object per respawn, one profile id throughout. */
    private static final class Body {
        final UUID id;

        Body(UUID id) {
            this.id = id;
        }
    }

    /** Delivers each frame straight to the other side's inbound handler, which only enqueues. */
    private static final class Loopback implements CgNetworkChannel {
        BiConsumer<Object, byte[]> inbound = (sender, frame) -> { };
        Body clientIs;
        final List<Object> sentTo = new ArrayList<>();
        boolean available = true;

        @Override
        public void sendToServer(byte[] frame) {
            inbound.accept(clientIs, frame);
        }

        @Override
        public void sendToPlayer(Object player, byte[] frame) {
            sentTo.add(player);
            inbound.accept(null, frame);
        }

        @Override
        public void setInboundHandler(BiConsumer<Object, byte[]> handler) {
            inbound = handler;
        }

        @Override
        public int maxFrameBytes() {
            return 32_000;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }
    }

    private final Loopback channel = new Loopback();
    private final UUID id = UUID.randomUUID();
    private final AtomicReference<Body> wearing = new AtomicReference<>(new Body(id));

    @Before
    public void setUp() {
        CgNetwork.resetForTesting();
        CgProtocols.resetForTesting();
        channel.clientIs = wearing.get();
    }

    @After
    public void tearDown() {
        CgNetwork.resetForTesting();
        CgProtocols.resetForTesting();
    }

    private void install() {
        assertTrue(CgNetwork.install(channel, player -> ((Body) player).id));
    }

    private void join() {
        CgNetwork.playerJoined(id, "Steve", wearing::get);
        CgNetwork.clientConnected();
    }

    private static void settle() {
        for (int i = 0; i < 8; i++) {
            CgNetwork.serverTick();
            CgNetwork.clientTick();
        }
    }

    @Test
    public void aCallCrossesBothTablesAndNamesItsPeer() {
        AtomicReference<String> askedBy = new AtomicReference<>();
        CgProtocols.server("echo", connection -> connection.onRequest("echo/say", (args, respond) -> {
            askedBy.set(((CgPeer) connection.peer()).name());
            respond.ok(new CgStateMap<>(connection.ops()).putString("said", args.getString("text", "")));
        }));
        install();
        join();

        AtomicReference<String> answer = new AtomicReference<>();
        CgNetwork.client().call("echo/say", new CgStateMap<>(CgPlainOps.INSTANCE).putString("text", "hi"),
                result -> answer.set(result.getString("said", null)), null);
        settle();

        assertEquals("hi", answer.get());
        assertEquals("Steve", askedBy.get());
    }

    /** A respawn replaces the entity; a send goes to the one worn now. */
    @Test
    public void aSendResolvesTheLivePlayer() {
        install();
        join();
        Body respawned = new Body(id);
        wearing.set(respawned);
        channel.clientIs = respawned;

        CgNetwork.forPlayer(id).notify("any/thing", new CgStateMap<>(CgPlainOps.INSTANCE).putInt("n", 1));
        settle();

        assertSame(respawned, channel.sentTo.get(channel.sentTo.size() - 1));
    }

    @Test
    public void aFrameFromNobodyOpensNothing() {
        install();
        channel.inbound.accept(new Body(UUID.randomUUID()), new byte[]{1, 0, 1});
        settle();

        assertEquals(0, CgNetwork.openConnections());
    }

    @Test
    public void leavingClosesByIdAndTellsTheListeners() {
        List<CgPeer> closed = new ArrayList<>();
        CgNetwork.onPeerClosed(closed::add);
        install();
        join();

        CgNetwork.playerLeft(id);

        assertNull(CgNetwork.forPlayer(id));
        assertEquals(1, closed.size());
        assertEquals(id, closed.get(0).id());
    }

    @Test
    public void closeAllFailsWhatIsOutstanding() {
        install();
        join();
        AtomicReference<String> failure = new AtomicReference<>();
        CgProtocolConnection<Object> toServer = CgNetwork.client();
        toServer.call("nobody/answers", new CgStateMap<>(CgPlainOps.INSTANCE), result -> { }, failure::set);

        CgNetwork.closeAll("server stopping");

        assertNotNull(failure.get());
        assertEquals(0, CgNetwork.openConnections());
    }

    @Test
    public void withNoChannelNothingOpens() {
        channel.available = false;

        assertFalse(CgNetwork.install(channel, player -> id));
        CgNetwork.playerJoined(id, "Steve", wearing::get);
        CgNetwork.clientConnected();

        assertFalse(CgNetwork.isInstalled());
        assertNull(CgNetwork.forPlayer(id));
        assertNull(CgNetwork.client());
    }
}
