package com.crystalgraphics.net;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import com.crystalgraphics.net.protocol.CgProtocolErrors;
import com.crystalgraphics.net.protocol.CgProtocols;
import com.crystalgraphics.platform.service.CgNetworkChannel;
import com.crystalgraphics.serialization.CgCodecs;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Both directions over one loopback connection, as in single player. */
public class CgRequestTest {

    private final class Loopback implements CgNetworkChannel {
        BiConsumer<Object, byte[]> inbound = (sender, frame) -> { };

        @Override public void sendToServer(byte[] frame) { inbound.accept(id, frame); }
        @Override public void sendToPlayer(Object player, byte[] frame) { inbound.accept(null, frame); }
        @Override public void setInboundHandler(BiConsumer<Object, byte[]> handler) { inbound = handler; }
        @Override public int maxFrameBytes() { return 32_000; }
        @Override public boolean isAvailable() { return true; }
    }

    private final UUID id = UUID.randomUUID();
    private final AtomicReference<String> answer = new AtomicReference<>();
    private final AtomicReference<String> error = new AtomicReference<>();

    @Before
    public void setUp() {
        reset();
    }

    @After
    public void tearDown() {
        reset();
    }

    private static void reset() {
        CgNetwork.resetForTesting();
        CgProtocols.resetForTesting();
        CgMessage.resetForTesting();
        CgRequest.resetForTesting();
    }

    private void connect() {
        CgNetwork.install(new Loopback(), player -> (UUID) player);
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
    public void theServerAnswersAndKnowsWhoAsked() {
        CgRequest<String, String> greet = CgRequest.toServer("test:greet", CgCodecs.STRING, CgCodecs.STRING);
        greet.onServer((peer, name, reply) -> reply.ok("hello " + name + " from " + peer.name()));
        connect();

        greet.ask("Alex", answer::set, error::set);   // before the hello: held, then sent
        settle();

        assertEquals("hello Alex from Steve", answer.get());
        assertNull(error.get());
    }

    @Test
    public void aClientAnswersTheServer() {
        CgRequest<String, String> echo = CgRequest.toClient("test:echo", CgCodecs.STRING, CgCodecs.STRING);
        echo.onClient((text, reply) -> reply.ok(text + "!"));
        connect();
        settle();

        echo.ask(id, "ping", answer::set, error::set);
        settle();

        assertEquals("ping!", answer.get());
    }

    @Test
    public void aFailureReachesTheAsker() {
        CgRequest<String, String> greet = CgRequest.toServer("test:greet", CgCodecs.STRING, CgCodecs.STRING);
        greet.onServer((peer, name, reply) -> reply.fail("no such player"));
        connect();
        settle();

        greet.ask("Alex", answer::set, error::set);
        settle();

        assertNull(answer.get());
        assertEquals("no such player", error.get());
    }

    @Test
    public void aNamespaceThePeerLacksIsUnsupported() {
        connect();
        settle();
        CgRequest<String, String> late = CgRequest.toServer("late:greet", CgCodecs.STRING, CgCodecs.STRING);
        late.onServer((peer, name, reply) -> reply.ok(name));

        late.ask("Alex", answer::set, error::set);
        settle();

        assertNull(answer.get());
        assertEquals(CgRequest.UNSUPPORTED, error.get());
    }

    @Test
    public void aCancelledAskIsToldSoAndNeverAnswered() {
        CgRequest<String, String> greet = CgRequest.toServer("test:greet", CgCodecs.STRING, CgCodecs.STRING);
        greet.onServer((peer, name, reply) -> reply.ok(name));
        connect();
        settle();

        greet.ask("Alex", answer::set, error::set).cancel();
        settle();

        assertNull(answer.get());
        assertEquals(CgProtocolErrors.CANCELLED, error.get());
    }
}
