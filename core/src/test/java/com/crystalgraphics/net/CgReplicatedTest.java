package com.crystalgraphics.net;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.crystalgraphics.net.protocol.CgProtocols;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgNetworkChannel;
import com.crystalgraphics.platform.service.CgServerPlayers;
import com.crystalgraphics.serialization.CgCodecs;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;

/** One player over a loopback channel, a host answering from plain fields, and a view writing down what it was told. */
public class CgReplicatedTest {

    private static final class Body {
        final UUID id = UUID.randomUUID();
        String dimension = "minecraft:overworld";
        double x;
    }

    private final class Players implements CgServerPlayers {
        @Override
        public String dimension(Object handle) {
            return handle instanceof Body ? ((Body) handle).dimension : null;
        }

        @Override
        public boolean position(Object entity, double[] out) {
            if (!(entity instanceof Body)) return false;
            out[0] = ((Body) entity).x;
            out[1] = 64;
            out[2] = 0;
            return true;
        }

        @Override
        public UUID playerId(Object entity) {
            return entity instanceof Body ? ((Body) entity).id : null;
        }

        @Override
        public void trackingChunk(Object level, int chunkX, int chunkZ, Consumer<UUID> out) {
        }

        @Override
        public void trackingEntity(Object entity, Consumer<UUID> out) {
        }

        @Override
        public Path saveDirectory(Object handle) {
            return world.getRoot().toPath();
        }
    }

    private final class Loopback implements CgNetworkChannel {
        BiConsumer<Object, byte[]> inbound = (sender, frame) -> { };

        @Override public void sendToServer(byte[] frame) { inbound.accept(body, frame); }
        @Override public void sendToPlayer(Object player, byte[] frame) { inbound.accept(null, frame); }
        @Override public void setInboundHandler(BiConsumer<Object, byte[]> handler) { inbound = handler; }
        @Override public int maxFrameBytes() { return 32_000; }
        @Override public boolean isAvailable() { return true; }
    }

    @Rule
    public final TemporaryFolder world = new TemporaryFolder();

    private final Body body = new Body();
    private final List<String> told = new ArrayList<>();

    private final CgReplicated.View<String> view = new CgReplicated.View<String>() {
        @Override public void added(long id, String value) { told.add("added " + value); }
        @Override public void changed(long id, String value) { told.add("changed " + value); }
        @Override public void removed(long id) { told.add("removed"); }
    };

    @Before
    public void setUp() {
        reset();
        CgPlatform.provide(CgServerPlayers.SERVICE, new Players());
        CgNetwork.install(new Loopback(), player -> ((Body) player).id);
    }

    @After
    public void tearDown() {
        reset();
        CgPlatform.provide(CgServerPlayers.SERVICE, null);
    }

    private static void reset() {
        CgNetwork.resetForTesting();
        CgProtocols.resetForTesting();
        CgMessage.resetForTesting();
        CgReplicated.resetForTesting();
    }

    private void join() {
        CgNetwork.playerJoined(body.id, "Steve", () -> body);
        CgNetwork.clientConnected();
    }

    private static void settle() {
        for (int i = 0; i < 12; i++) {
            CgNetwork.serverTick();
            CgNetwork.clientTick();
        }
    }

    @Test
    public void changesInOneTickArriveAsOne() {
        CgReplicated<String> craters = CgReplicated.define("test:crater", CgCodecs.STRING).onClient(view);
        join();
        settle();
        CgReplicated.Handle<String> crater = craters.create("minecraft:overworld", 0, 64, 0, "fresh");
        settle();
        crater.set("older");
        crater.set("oldest");
        settle();

        assertEquals(List.of("added fresh", "changed oldest"), told);
    }

    @Test
    public void aLateViewerIsSentTheStateNotTheChanges() {
        CgReplicated<String> craters = CgReplicated.define("test:crater", CgCodecs.STRING).onClient(view);
        CgReplicated.Handle<String> crater = craters.create(body, 0, 64, 0, "fresh");
        settle();
        crater.set("older");
        settle();
        join();
        settle();

        assertEquals(List.of("added older"), told);
    }

    @Test
    public void leavingRangeRemovesItAndComingBackAddsItAgain() {
        CgReplicated<String> craters = CgReplicated.define("test:crater", CgCodecs.STRING).visibleWithin(16).onClient(view);
        join();
        settle();
        CgReplicated.Handle<String> crater = craters.create(body, 4, 64, 0, "fresh");
        settle();
        body.x = 100;
        settle();
        crater.set("changed while away");
        settle();
        body.x = 0;
        settle();
        crater.remove();
        settle();

        assertEquals(List.of("added fresh", "removed", "added changed while away", "removed"), told);
        assertEquals(0, craters.size());
    }

    @Test
    public void aPersistedObjectSurvivesARestart() {
        CgReplicated<String> craters = CgReplicated.define("test:crater", CgCodecs.STRING).persisted().onClient(view);
        craters.create(body, 0, 64, 0, "kept");
        CgNetwork.closeAll("server stopping");
        assertEquals(0, craters.size());

        CgNetwork.serverStarting(null);
        assertEquals(1, craters.size());
        join();
        settle();

        assertEquals(List.of("added kept"), told);
    }

    /** One defined after the server started loads on its first use. */
    @Test
    public void aDefinitionMadeAfterTheStartStillLoads() {
        CgReplicated<String> craters = CgReplicated.define("test:crater", CgCodecs.STRING).persisted();
        craters.create(body, 0, 64, 0, "kept");
        CgNetwork.closeAll("server stopping");
        CgReplicated.resetForTesting();
        CgMessage.resetForTesting();

        CgNetwork.serverStarting(null);
        CgReplicated<String> again = CgReplicated.define("test:crater", CgCodecs.STRING).persisted();

        assertEquals(1, again.size());
    }
}
