package com.crystalgraphics.net;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgraphics.net.protocol.CgProtocols;
import com.crystalgraphics.platform.service.CgNetworkChannel;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.CgDynamicOps;
import com.crystalgraphics.serialization.CgPlainOps;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** {@link CgMessage} and the hello, over {@link CgNetwork} in one process (single player) and over a bare pair. */
public class CgMessageTest {

    record Blast(String effect, long seed) {
        static final CgCodec<Blast> CODEC = new CgCodec<>() {
            @Override
            public <T> T encode(CgDynamicOps<T> ops, Blast blast) {
                return CgCodecs.map(ops).field("e", CgCodecs.STRING, blast.effect()).field("s", CgCodecs.LONG, blast.seed())
                        .build();
            }

            @Override
            public <T> Blast decode(CgDynamicOps<T> ops, T input) {
                CgCodecs.MapCodecReader<T> in = CgCodecs.read(ops, input);
                return new Blast(in.field("e", CgCodecs.STRING), in.field("s", CgCodecs.LONG));
            }
        };
    }

    private static final class Loopback implements CgNetworkChannel {
        BiConsumer<Object, byte[]> inbound = (sender, frame) -> { };
        final UUID player;

        Loopback(UUID player) {
            this.player = player;
        }

        @Override
        public void sendToServer(byte[] frame) {
            inbound.accept(player, frame);
        }

        @Override
        public void sendToPlayer(Object player, byte[] frame) {
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
            return true;
        }
    }

    private final UUID id = UUID.randomUUID();

    @Before
    public void setUp() {
        CgNetwork.resetForTesting();
        CgProtocols.resetForTesting();
        CgMessage.resetForTesting();
    }

    @After
    public void tearDown() {
        setUp();
    }

    private void connect() {
        CgNetwork.install(new Loopback(id), player -> (UUID) player);
        CgNetwork.playerJoined(id, "Steve", () -> id);
        CgNetwork.clientConnected();
    }

    private static void settle() {
        for (int i = 0; i < 12; i++) {
            CgNetwork.serverTick();
            CgNetwork.clientTick();
        }
    }

    @Test
    public void aTypedMessageReachesTheClient() {
        CgMessage<Blast> play = CgMessage.toClients("test:vfx/play", Blast.CODEC);
        AtomicReference<Blast> arrived = new AtomicReference<>();
        play.onReceive(arrived::set);
        connect();
        settle();

        play.send(CgAudience.all(), new Blast("blast", 42L));
        settle();

        assertEquals(new Blast("blast", 42L), arrived.get());
        assertEquals(0, play.skipped());
    }

    @Test
    public void aServerHandlerIsToldWhoSentIt() {
        CgMessage<Blast> ask = CgMessage.toServer("test:vfx/ask", Blast.CODEC);
        AtomicReference<String> from = new AtomicReference<>();
        ask.onReceiveFrom((peer, blast) -> from.set(peer.name() + ":" + blast.effect()));
        connect();

        ask.sendToServer(new Blast("spark", 1L));   // before the hello: held, then sent
        settle();

        assertEquals("Steve:spark", from.get());
    }

    /** One declared after the hello is unknown to the peer: skipped and counted, never sent. */
    @Test
    public void aNamespaceThePeerLacksIsSkipped() {
        connect();
        settle();
        CgMessage<Blast> late = CgMessage.toClients("late:vfx/play", Blast.CODEC);
        AtomicReference<Blast> arrived = new AtomicReference<>();
        late.onReceive(arrived::set);

        late.send(CgAudience.player(id), new Blast("blast", 7L));
        settle();

        assertNull(arrived.get());
        assertEquals(1, late.skipped());
    }

    /** Over a bare pair, so each side declares its own version. */
    @Test
    public void aVersionMismatchSkips() {
        CgMessage<Blast> play = CgMessage.toClients("test:vfx/play", Blast.CODEC);
        CgInMemoryTransport<Object>[] pair = CgInMemoryTransport.pair();
        CgProtocolConnection<Object> server = CgProtocols.open(pair[0], CgPlainOps.INSTANCE, () -> { }, "peer");
        CgProtocolConnection<Object> client = CgProtocols.open(pair[1], CgPlainOps.INSTANCE, () -> { }, null);
        Map<String, Integer> clientSpeaks = new LinkedHashMap<>();
        clientSpeaks.put("test", 2);
        CgHello.bind(server, CgMessage::namespaces);
        CgHello.bind(client, () -> clientSpeaks);
        CgMessage.bindAll(client);
        List<Blast> arrived = new ArrayList<>();
        play.onReceive(arrived::add);
        for (int i = 0; i < 8; i++) {
            pair[0].deliver();
            pair[1].deliver();
            server.tick();
            client.tick();
        }
        assertTrue(CgHello.state(server).known());

        play.sendTo(server, Blast.CODEC.encode(server.ops(), new Blast("blast", 3L)));
        play.sendTo(server, Blast.CODEC.encode(server.ops(), new Blast("blast", 4L)));
        for (int i = 0; i < 8; i++) {
            pair[0].deliver();
            pair[1].deliver();
            server.tick();
            client.tick();
        }

        assertTrue(arrived.isEmpty());
        assertEquals(2, play.skipped());
    }

    /** A first hello can reach the server before it has opened that player's connection: it is said again. */
    @Test
    public void aLostHelloIsSaidAgain() {
        CgInMemoryTransport<Object>[] pair = CgInMemoryTransport.pair();
        CgProtocolConnection<Object> server = CgProtocols.open(pair[0], CgPlainOps.INSTANCE, () -> { }, "peer");
        CgProtocolConnection<Object> client = CgProtocols.open(pair[1], CgPlainOps.INSTANCE, () -> { }, null);
        pair[1].dropNext(1);
        CgHello.bind(server, CgMessage::namespaces);
        CgHello.bind(client, CgMessage::namespaces);
        for (int i = 0; i < CgHello.RESEND_TICKS * 2; i++) {
            pair[0].deliver();
            pair[1].deliver();
            server.tick();
            client.tick();
        }

        assertTrue(CgHello.state(server).known());
        assertTrue(CgHello.state(client).known());
    }

    /** A peer without the mod never says hello: what waited for it is dropped, and nothing was ever sent. */
    @Test
    public void aSilentPeerIsGivenUpOn() {
        CgMessage<Blast> play = CgMessage.toClients("test:vfx/play", Blast.CODEC);
        CgNetwork.install(new Loopback(id), player -> (UUID) player);
        CgNetwork.playerJoined(id, "Vanilla", () -> id);   // no client connection: nobody says hello

        play.send(CgAudience.all(), new Blast("blast", 5L));
        for (int i = 0; i < CgHello.SILENT_TICKS + 1; i++) CgNetwork.serverTick();
        play.send(CgAudience.all(), new Blast("blast", 6L));

        assertTrue(CgHello.state(CgNetwork.forPlayer(id)).absent());
        assertEquals("the one that waited and the one after", 2, play.skipped());
    }
}
