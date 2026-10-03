package com.crystalgraphics.net.protocol;

import com.crystalgraphics.net.CgInMemoryTransport;
import com.crystalgraphics.serialization.CgPlainOps;
import com.crystalgraphics.serialization.CgStateMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Can something that is not the UI speak this protocol? — the question the whole layering exists to
 * answer yes to.
 *
 * <p>{@code ProtocolTest} covers the envelope and the router. This covers the <b>wiring</b>: that a
 * subsystem registers once, globally — as a lambda, sided at the call site — and is bound onto every
 * connection afterwards without knowing one exists. Both halves matter and the second is the one that
 * was missing — the protocol was general while a router was reachable only by constructing a
 * {@code ServerUiSession}.</p>
 */
public class CgProtocolContributionTest {

    private CgInMemoryTransport<Object>[] pair;
    private CgProtocolConnection<Object> a;
    private CgProtocolConnection<Object> b;

    @Before
    public void setUp() {
        CgProtocols.resetForTesting();
        pair = CgInMemoryTransport.pair();
    }

    @After
    public void tearDown() {
        CgProtocols.resetForTesting();
    }

    /** Opens both ends after whatever the test contributed. */
    private void connect() {
        a = CgProtocols.open(pair[0], CgPlainOps.INSTANCE, () -> { }, "peer-b");
        b = CgProtocols.open(pair[1], CgPlainOps.INSTANCE, () -> { }, null);
    }

    private void settle() {
        for (int i = 0; i < 8; i++) {
            pair[0].deliver();
            pair[1].deliver();
            a.tick();
            b.tick();
        }
    }

    // ── The claim ───────────────────────────────────────────────────────────

    /**
     * Two unrelated subsystems, one connection, neither aware of the other.
     *
     * <p>This is the CustomNPC+ ergonomic with per-peer correctness: register once at init, and every
     * connection afterwards carries both. Namespaced methods are what make the coexistence safe —
     * {@code workspace/*} and {@code script/*} cannot collide unless someone picks the same prefix, and
     * the router refuses a duplicate outright rather than letting the second win.</p>
     */
    @Test
    public void twoUnrelatedSubsystemsShareOneConnection() {
        CgProtocols.contribute("workspace", connection ->
                connection.onRequest("workspace/read", (args, respond) -> {
                    CgStateMap<Object> out = new CgStateMap<>(connection.ops());
                    out.putString("body", "contents of " + args.getString("path", "?"));
                    respond.ok(out);
                }));
        CgProtocols.contribute("script", connection ->
                connection.onRequest("script/eval", (args, respond) -> {
                    CgStateMap<Object> out = new CgStateMap<>(connection.ops());
                    out.putInt("result", args.getInt("x", 0) * 2);
                    respond.ok(out);
                }));
        connect();

        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<Integer> doubled = new AtomicReference<>();

        CgStateMap<Object> read = new CgStateMap<>(CgPlainOps.INSTANCE);
        read.putString("path", "src/Main.java");
        b.call("workspace/read", read, result -> body.set(result.getString("body", "")), null);

        CgStateMap<Object> eval = new CgStateMap<>(CgPlainOps.INSTANCE);
        eval.putInt("x", 21);
        b.call("script/eval", eval, result -> doubled.set(result.getInt("result", -1)), null);

        settle();

        assertEquals("contents of src/Main.java", body.get());
        assertEquals(Integer.valueOf(42), doubled.get());
    }

    /**
     * A subsystem registers once and never sees a connection — the point of the split.
     *
     * <p>Contribution happens at init, when no peer exists. Every connection opened afterwards binds it,
     * which is what lets a dedicated server accept players for hours without the subsystem being involved
     * in any of it.</p>
     */
    @Test
    public void everyConnectionOpenedAfterwardsCarriesTheContribution() {
        List<Object> boundTo = new ArrayList<>();
        CgProtocols.contribute("audit", connection -> boundTo.add(connection.peer()));

        CgProtocols.open(pair[0], CgPlainOps.INSTANCE, () -> { }, "player-one");
        CgProtocols.open(pair[1], CgPlainOps.INSTANCE, () -> { }, "player-two");

        assertEquals(List.of("player-one", "player-two"), boundTo);
    }

    /**
     * {@code server()} and {@code client()} put the side in the method name — the guard every server
     * contributor used to open with, now unwritable wrong.
     */
    @Test
    public void aSidedContributorBindsOnlyOnItsOwnEnd() {
        List<String> log = new ArrayList<>();
        CgProtocols.server("mymod", connection -> log.add("server:" + connection.peer()));
        CgProtocols.client("mymod", connection -> log.add("client:" + connection.peer()));
        connect();

        // One name, both sides: two halves of one protocol, and each end got exactly its own.
        assertEquals(List.of("server:peer-b", "client:null"), log);
        assertEquals("one subsystem, however many sides it registered",
                Set.of("mymod"), CgProtocols.contributors());
    }

    /**
     * A connection knows which peer it belongs to, and the handle stays opaque.
     *
     * <p>{@code core} cannot name {@code EntityPlayerMP}, so a subsystem that needs one casts at its own
     * loader. What matters here is that two connections do not share it — an authorisation check reading
     * the wrong player is the failure this prevents.</p>
     */
    @Test
    public void eachConnectionCarriesItsOwnPeer() {
        connect();
        assertEquals("peer-b", a.peer());
        assertNull("a client has only one peer and does not name it", b.peer());
    }

    /** A contributor that throws costs the connection itself, never the other subsystems on it. */
    @Test
    public void oneBrokenContributorDoesNotTakeTheOthersWithIt() {
        CgProtocols.contribute("broken", connection -> {
            throw new IllegalStateException("misconfigured");
        });
        CgProtocols.contribute("working", connection ->
                connection.onRequest("working/ping", (args, respond) -> respond.ok(null)));
        connect();

        AtomicReference<Boolean> answered = new AtomicReference<>(false);
        b.call("working/ping", null, result -> answered.set(true), null);
        settle();

        assertTrue("the surviving contributor must still serve its methods", answered.get());
    }

    /** Two subsystems claiming one name ON ONE SIDE is a wiring mistake, refused rather than resolved. */
    @Test
    public void aDuplicateContributorNameIsRefused() {
        CgProtocols.Contributor noop = connection -> { };
        CgProtocols.contribute("workspace", noop);
        try {
            CgProtocols.contribute("workspace", noop);
            fail("a second contributor under one name must be refused");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("workspace"));
        }
        try {
            CgProtocols.server("only", noop);
            CgProtocols.server("only", noop);
            fail("the sided registrations dedupe too");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("only"));
        }
        assertEquals(Set.of("workspace", "only"), CgProtocols.contributors());
    }

    /**
     * {@code tick()} pumps the wire as well as draining — one call, nothing to forget.
     *
     * <p>A subsystem that had to pump its transport separately would receive nothing, silently, which is
     * the failure shape this codebase keeps paying for. So the pump is supplied at open time and run by
     * the same tick that dispatches.</p>
     */
    @Test
    public void tickPumpsTheWireItself() {
        int[] pumps = {0};
        CgProtocols.contribute("noop", connection -> { });
        CgProtocolConnection<Object> connection =
                CgProtocols.open(pair[0], CgPlainOps.INSTANCE, () -> pumps[0]++, null);

        connection.tick();
        connection.tick();

        assertEquals("the supplied pump runs once per tick", 2, pumps[0]);
    }
}
