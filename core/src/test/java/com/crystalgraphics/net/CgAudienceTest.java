package com.crystalgraphics.net;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.crystalgraphics.net.protocol.CgProtocols;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgNetworkChannel;
import com.crystalgraphics.platform.service.CgServerPlayers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The audiences over three connected players and a host that answers from plain fields. */
public class CgAudienceTest {

    private static final class Body {
        final UUID id = UUID.randomUUID();
        final String dimension;
        final double x, y, z;
        final List<UUID> tracking = new ArrayList<>();

        Body(String dimension, double x, double y, double z) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final class Players implements CgServerPlayers {
        @Override
        public String dimension(Object handle) {
            return handle instanceof Body ? ((Body) handle).dimension : null;
        }

        @Override
        public boolean position(Object entity, double[] out) {
            if (!(entity instanceof Body)) return false;
            Body body = (Body) entity;
            out[0] = body.x;
            out[1] = body.y;
            out[2] = body.z;
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
            ((Body) entity).tracking.forEach(out);
        }
    }

    private static final class Silent implements CgNetworkChannel {
        @Override public void sendToServer(byte[] frame) { }
        @Override public void sendToPlayer(Object player, byte[] frame) { }
        @Override public void setInboundHandler(BiConsumer<Object, byte[]> handler) { }
        @Override public int maxFrameBytes() { return 32_000; }
        @Override public boolean isAvailable() { return true; }
    }

    private final Body home = new Body("minecraft:overworld", 0, 64, 0);
    private final Body near = new Body("minecraft:overworld", 30, 64, 40);
    private final Body nether = new Body("minecraft:the_nether", 0, 64, 0);

    @Before
    public void setUp() {
        CgNetwork.resetForTesting();
        CgProtocols.resetForTesting();
        CgNetwork.install(new Silent(), player -> ((Body) player).id);
        for (Body body : Arrays.asList(home, near, nether)) {
            CgNetwork.playerJoined(body.id, body.dimension, () -> body);
        }
        CgPlatform.provide(CgServerPlayers.SERVICE, new Players());
    }

    @After
    public void tearDown() {
        CgPlatform.provide(CgServerPlayers.SERVICE, null);
        CgNetwork.resetForTesting();
        CgProtocols.resetForTesting();
    }

    private static Set<UUID> reached(CgAudience audience) {
        Set<UUID> ids = new HashSet<>();
        audience.forEach(ids::add);
        return ids;
    }

    private static Set<UUID> ids(Body... bodies) {
        Set<UUID> ids = new HashSet<>();
        for (Body body : bodies) ids.add(body.id);
        return ids;
    }

    @Test
    public void dimensionAndNearFilterThePeers() {
        assertEquals(ids(home, near), reached(CgAudience.dimension(home)));
        assertEquals(ids(nether), reached(CgAudience.dimension("minecraft:the_nether")));
        assertEquals(ids(home, near), reached(CgAudience.near(home, 0, 64, 0, 50)));
        assertEquals(ids(home), reached(CgAudience.near("minecraft:overworld", 0, 64, 0, 49.9)));
    }

    @Test
    public void trackingAddsSelfOnlyWhenAskedAndExceptRemovesOne() {
        home.tracking.add(near.id);
        assertEquals(ids(near), reached(CgAudience.tracking(home)));
        assertEquals(ids(home, near), reached(CgAudience.trackingAndSelf(home)));
        assertEquals(ids(near), reached(CgAudience.except(CgAudience.trackingAndSelf(home), home.id)));
    }

    @Test
    public void withoutAHostOnlyIdsReachAnyone() {
        CgPlatform.provide(CgServerPlayers.SERVICE, null);
        assertEquals(ids(), reached(CgAudience.near(home, 0, 64, 0, 1000)));
        assertEquals(ids(), reached(CgAudience.dimension("minecraft:overworld")));
        assertEquals(ids(home, near, nether), reached(CgAudience.all()));
    }
}
