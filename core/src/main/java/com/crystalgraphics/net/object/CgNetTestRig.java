package com.crystalgraphics.net.object;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import javax.annotation.Nullable;

import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.net.protocol.CgConnections;
import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgraphics.net.protocol.CgProtocols;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgNetworkChannel;
import com.crystalgraphics.platform.world.CgBoxSink;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgLevels;
import com.crystalgraphics.platform.world.CgPlayerQuery;
import com.crystalgraphics.platform.world.CgWorldQuery;

/**
 * A server and any number of clients in one JVM, for testing networked objects without Minecraft: real connections,
 * real frames, a tick you step, latency you choose.
 *
 * <pre>{@code
 * try (CgNetTestRig rig = CgNetTestRig.of(2).latencyTicks(3)) {
 *     CgNetObject turret = Turret.TYPE.spawn(rig.at(0, 64, 0)).owner(rig.client(0).id()).start();
 *     rig.tick(5);
 *     assertEquals(1, rig.client(1).visible(Turret.TYPE).size());
 * }
 * }</pre>
 *
 * <p>Clients stand at the origin of {@link #DIMENSION} until moved; persistence needs a folder:</p>
 *
 * <pre>{@code
 * rig.client(1).moveTo(500, 64, 0);          // out of a 96-block range at the next tick
 * rig.saveTo(folder).restart();              // the server stops and starts again, clients reconnect
 * }</pre>
 *
 * <p>Entities, deaths and chunks, for the other anchors:</p>
 *
 * <pre>{@code
 * CgNetTestRig.Entity zombie = rig.entity(10, 64, 0);
 * AURA.spawn(CgAnchor.on(zombie)).start();   // tracked by clients within 64 blocks
 * zombie.kill();                             // and the aura despawns
 * rig.client(0).die();                       // a player anchor without copyOnDeath ends
 * rig.unloadChunk(0, 0);                     // a chunk anchor goes dormant
 * }</pre>
 *
 * <ul>
 *   <li>It replaces this process's network and server players while open, and resets them on {@link #close()}:
 *       one rig at a time, never inside a game.</li>
 *   <li>Declare types before {@link #of}, as a mod declares them before any player joins.</li>
 *   <li>Behaviours from {@link CgNetType#onClient} run for every client; {@link Client#visible} tells them apart.</li>
 * </ul>
 */
public final class CgNetTestRig implements AutoCloseable {

    /** The dimension clients stand in and {@link #at} anchors to; also its level, for {@link CgAnchor#chunk}. */
    public static final String DIMENSION = "rig:overworld";
    /** Blocks within which a client tracks an entity, horizontally. */
    public static final double TRACKING_RANGE = 64;
    /** Chunks within which a client tracks a chunk. */
    public static final int VIEW_DISTANCE = 8;

    private static final int MAX_FRAME_BYTES = 32_000;

    private final List<Client> clients = new ArrayList<>();
    private final List<Entity> entities = new ArrayList<>();
    private final Set<Long> unloaded = new HashSet<>();
    private final Levels levels = new Levels();
    private final CgConnections clientConnections = new CgConnections("client", MAX_FRAME_BYTES, true);
    private final ArrayDeque<Packet> toClients = new ArrayDeque<>();
    private final ArrayDeque<Packet> toServer = new ArrayDeque<>();
    private BiConsumer<Object, byte[]> serverInbound = (sender, frame) -> { };
    private int latency;
    private long now;
    @Nullable
    private Path saveDirectory;

    private static final class Packet {
        final Client client;
        final byte[] frame;
        final long due;

        Packet(Client client, byte[] frame, long due) {
            this.client = client;
            this.frame = frame;
            this.due = due;
        }
    }

    private CgNetTestRig() {
    }

    /** A server with {@code clients} connected players. */
    public static CgNetTestRig of(int clients) {
        CgNetTestRig rig = new CgNetTestRig();
        CgNetwork.resetForTesting();
        CgProtocols.resetForTesting();
        CgPlatform.provide(CgLevels.SERVICE, rig.levels);
        CgPlatform.provide(CgPlayerQuery.SERVICE, rig.new Players());
        CgNetwork.install(rig.new Channel(), player -> ((Client) player).id);
        CgNetwork.serverStarting(null);
        for (int i = 0; i < clients; i++) rig.clients.add(rig.new Client(i));
        for (Client client : rig.clients) client.connect();
        return rig;
    }

    /** Every frame, either way, arrives {@code ticks} ticks after it was sent. */
    public CgNetTestRig latencyTicks(int ticks) {
        if (ticks < 0) throw new IllegalArgumentException("latency: " + ticks);
        latency = ticks;
        return this;
    }

    /**
     * What its levels hold, for collision: every rig level answers these blocks and entities, the client's included.
     * Nothing unless given. An id the rig knows (its {@link #entity entities}, its clients) is the rig's.
     *
     * <pre>{@code
     * try (CgNetTestRig rig = CgNetTestRig.of(2).world(new Yard(), table)) {
     *     SLAM.spawn(rig.at(0, 1, 0)).start();     // its hitbox hits the table's mobs, on the server and each client
     * }
     * }</pre>
     */
    public CgNetTestRig world(CgWorldQuery blocks, CgEntityQuery entities) {
        levels.blocks = blocks;
        levels.entities = entities;
        return this;
    }

    /** Where the server saves; without it nothing persists. */
    public CgNetTestRig saveTo(Path folder) {
        saveDirectory = folder;
        return this;
    }

    /** A point in {@link #DIMENSION}. */
    public CgAnchor at(double x, double y, double z) {
        return CgAnchor.at(DIMENSION, x, y, z);
    }

    /** A new living entity in {@link #DIMENSION}. */
    public Entity entity(double x, double y, double z) {
        Entity entity = new Entity(1000 + entities.size());
        entities.add(entity);
        return entity.moveTo(x, y, z);
    }

    /** Chunk ({@code chunkX}, {@code chunkZ}) of {@link #DIMENSION} unloads; every chunk starts loaded. */
    public CgNetTestRig unloadChunk(int chunkX, int chunkZ) {
        unloaded.add(chunkKey(chunkX, chunkZ));
        return this;
    }

    public CgNetTestRig loadChunk(int chunkX, int chunkZ) {
        unloaded.remove(chunkKey(chunkX, chunkZ));
        return this;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | chunkZ & 0xFFFFFFFFL;
    }

    /** The {@code index}th client, from 0. */
    public Client client(int index) {
        return clients.get(index);
    }

    /** Steps the server and every client {@code ticks} times. */
    public CgNetTestRig tick(int ticks) {
        for (int t = 0; t < ticks; t++) {
            now++;
            long start = System.nanoTime();
            deliver(toServer, toServerSink);
            CgNetwork.serverTick();
            long served = System.nanoTime();
            deliver(toClients, toClientSink);
            clientConnections.tick();
            serverNanos = served - start;
            clientNanos = System.nanoTime() - served;
        }
        return this;
    }

    /** The last tick's server half (receiving and {@link CgNetwork#serverTick}) and every client's, in nanoseconds. */
    long serverNanos, clientNanos;

    // Fields, not lambdas at the call: a capturing lambda is an allocation every tick.
    private final Consumer<Packet> toServerSink = packet -> serverInbound.accept(packet.client, packet.frame);
    private final Consumer<Packet> toClientSink = packet -> clientConnections.route(packet.client.id, packet.frame);

    private void deliver(ArrayDeque<Packet> queue, Consumer<Packet> to) {
        while (!queue.isEmpty() && queue.peekFirst().due <= now) {
            Packet packet = queue.pollFirst();
            if (packet.client.connected) to.accept(packet);
        }
    }

    /** The server stops, saving what persists, and starts again; every client reconnects. */
    public CgNetTestRig restart() {
        for (Client client : clients) {
            clientConnections.close(client.id, "server stopping");
            client.connected = false;
        }
        CgNetwork.closeAll("restart");
        toClients.clear();
        toServer.clear();
        CgNetwork.serverStarting(null);
        for (Client client : clients) client.connect();
        return this;
    }

    @Override
    public void close() {
        clientConnections.closeAll("rig closed");
        CgNetwork.resetForTesting();
        CgProtocols.resetForTesting();
        CgPlatform.provide(CgLevels.SERVICE, null);
        CgPlatform.provide(CgPlayerQuery.SERVICE, null);
        CgPlatform.provide(CgNetworkChannel.SERVICE, null);
    }

    /** One connected player and what its client sees. */
    public final class Client {
        final UUID id;
        final String name;
        boolean connected;
        boolean dead;
        String dimension = DIMENSION;
        double x, y, z;
        long bytesReceived;
        int framesReceived;

        private Client(int index) {
            this.name = "player" + index;
            this.id = UUID.nameUUIDFromBytes(("cg-rig:" + name).getBytes(StandardCharsets.UTF_8));
        }

        /** Its profile id: what {@link CgNetObject#owner} takes. */
        public UUID id() {
            return id;
        }

        /** Where its player stands, from the next tick. */
        public Client moveTo(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        public Client dimension(String dimension) {
            this.dimension = dimension;
            return this;
        }

        /** Its player dies; it stays connected until {@link #respawn()}. */
        public Client die() {
            dead = true;
            return this;
        }

        public Client respawn() {
            dead = false;
            return this;
        }

        /** The objects of {@code type} this client sees now. */
        public List<CgNetObject> visible(CgNetType type) {
            ClientObjects objects = objects();
            return objects == null ? Collections.emptyList() : objects.visible(type);
        }

        /** The object this client knows by {@code id}, or null. */
        @Nullable
        public CgNetObject find(int id) {
            ClientObjects objects = objects();
            return objects == null ? null : objects.find(id);
        }

        /** This client's per-holder object of {@code type} on entity {@code entityId}, as {@link CgNetType#find} answers. */
        @Nullable
        public CgNetObject find(CgNetType type, int entityId) {
            ClientObjects objects = objects();
            return objects == null ? null : objects.holder(type, entityId);
        }

        /** A prediction from this client, as {@link CgNetType#predict} makes one in a game. */
        public CgNetObject predict(CgNetType type, CgAnchor anchor) {
            ClientObjects objects = objects();
            if (objects == null) throw new IllegalStateException(this + " is not connected");
            return objects.predict(type, anchor);
        }

        /** Bytes and frames the server has sent it. */
        public long bytesReceived() {
            return bytesReceived;
        }

        public int framesReceived() {
            return framesReceived;
        }

        public Client disconnect() {
            if (!connected) return this;
            connected = false;
            CgNetwork.playerLeft(id);
            clientConnections.close(id, "disconnected");
            return this;
        }

        public Client connect() {
            if (connected) return this;
            connected = true;
            CgNetwork.playerJoined(id, name, () -> connected ? this : null);
            clientConnections.open(id, null, frame -> toServer.addLast(new Packet(this, frame, now + latency)));
            return this;
        }

        @Nullable
        private ClientObjects objects() {
            if (!connected) return null;
            CgProtocolConnection<Object> connection = clientConnections.get(id);
            return connection == null ? null : connection.attachment(ClientObjects.class, ClientObjects::new);
        }

        @Override
        public String toString() {
            return "CgNetTestRig.Client[" + name + "]";
        }
    }

    /** A non-player entity on the server. */
    public final class Entity {
        final int id;
        String dimension = DIMENSION;
        double x, y, z;
        boolean alive = true;

        private Entity(int id) {
            this.id = id;
        }

        /** Its network id: what {@link CgNetObject#entityId()} answers on a client. */
        public int id() {
            return id;
        }

        public Entity moveTo(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        public Entity dimension(String dimension) {
            this.dimension = dimension;
            return this;
        }

        public Entity kill() {
            alive = false;
            return this;
        }

        @Override
        public String toString() {
            return "CgNetTestRig.Entity[" + id + "]";
        }
    }

    private final class Channel implements CgNetworkChannel {
        @Override
        public int maxFrameBytes() {
            return MAX_FRAME_BYTES;
        }

        @Override
        public void sendToServer(byte[] frame) {
            // The rig's clients send through their own connections.
        }

        @Override
        public void sendToPlayer(Object player, byte[] frame) {
            Client client = (Client) player;
            client.bytesReceived += frame.length;
            client.framesReceived++;
            toClients.addLast(new Packet(client, frame, now + latency));
        }

        @Override
        public void setInboundHandler(BiConsumer<Object, byte[]> handler) {
            serverInbound = handler;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }

    private final class Players implements CgPlayerQuery {
        private final double[] at = new double[3];

        @Override
        public UUID playerId(Object entity) {
            return entity instanceof Client ? ((Client) entity).id : null;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <V> V player(UUID id) {
            for (Client client : clients) if (client.connected && client.id.equals(id)) return (V) client;
            return null;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <V> V player(int entityId) {
            Client client = entityId >= 1 && entityId <= clients.size() ? clients.get(entityId - 1) : null;
            return client != null && client.connected ? (V) client : null;
        }

        @Override
        public <V> V localPlayer() {
            return null;
        }

        @Override
        public void trackingChunk(Object level, int chunkX, int chunkZ, Consumer<UUID> out) {
            if (!DIMENSION.equals(level) || unloaded.contains(chunkKey(chunkX, chunkZ))) return;
            for (Client client : clients) {
                if (!client.connected || !DIMENSION.equals(client.dimension)) continue;
                int dx = ((int) Math.floor(client.x) >> 4) - chunkX, dz = ((int) Math.floor(client.z) >> 4) - chunkZ;
                if (Math.max(Math.abs(dx), Math.abs(dz)) <= VIEW_DISTANCE) out.accept(client.id);
            }
        }

        @Override
        public void trackingEntity(Object entity, Consumer<UUID> out) {
            if (!levels.position(entity, at)) return;
            String dimension = levels.dimension(entity);
            double x = at[0], z = at[2];
            for (Client client : clients) {
                if (!client.connected || client == entity || !client.dimension.equals(dimension)) continue;
                double dx = client.x - x, dz = client.z - z;
                if (dx * dx + dz * dz <= TRACKING_RANGE * TRACKING_RANGE) out.accept(client.id);
            }
        }
    }

    /** A rig level is its dimension's id: no blocks, and entities found by id. */
    private final class Levels implements CgLevels {
        CgWorldQuery blocks = CgWorldQuery.NONE;
        CgEntityQuery entities = CgEntityQuery.NONE;
        final CgEntityQuery client = new LevelEntities(DIMENSION);

        @Override
        public CgWorldQuery world(Object level) {
            return dimension(level) == null ? CgWorldQuery.NONE : blocks;
        }

        @Override
        public CgEntityQuery entities(Object level) {
            String dimension = dimension(level);
            return dimension == null ? CgEntityQuery.NONE : new LevelEntities(dimension);
        }

        @Override
        public CgWorldQuery clientWorld() {
            return blocks;
        }

        @Override
        public CgEntityQuery clientEntities() {
            return client;
        }

        @Override
        public Object clientLevel() {
            return null;
        }

        @Override
        public String dimension(Object handle) {
            if (handle instanceof Client) return ((Client) handle).dimension;
            if (handle instanceof Entity) return ((Entity) handle).dimension;
            return handle instanceof String ? (String) handle : null;
        }

        @Override
        public boolean position(Object entity, double[] out) {
            if (entity instanceof Client) {
                Client client = (Client) entity;
                out[0] = client.x;
                out[1] = client.y;
                out[2] = client.z;
                return true;
            }
            if (!(entity instanceof Entity)) return false;
            Entity e = (Entity) entity;
            out[0] = e.x;
            out[1] = e.y;
            out[2] = e.z;
            return true;
        }

        @Override
        public int entityId(Object entity) {
            if (entity instanceof Entity) return ((Entity) entity).id;
            return entity instanceof Client ? clients.indexOf(entity) + 1 : -1;
        }

        @Override
        public boolean alive(Object entity) {
            if (entity instanceof Entity) return ((Entity) entity).alive;
            return entity instanceof Client && ((Client) entity).connected && !((Client) entity).dead;
        }

        @Override
        public Object level(Object handle, String dimension) {
            return dimension;
        }

        @Override
        public boolean chunkLoaded(Object level, int chunkX, int chunkZ) {
            return DIMENSION.equals(level) && !unloaded.contains(chunkKey(chunkX, chunkZ));
        }

        @Override
        public Path saveDirectory(Object handle) {
            return saveDirectory;
        }
    }

    /**
     * A rig level's entities: by id, the rig's entities, then clients (id = index + 1), then what {@link #world} gave;
     * every other question is {@link #world}'s.
     */
    private final class LevelEntities implements CgEntityQuery {
        private final String dimension;

        LevelEntities(String dimension) {
            this.dimension = dimension;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <V> V entity(int id) {
            for (Entity entity : entities) if (entity.id == id && entity.dimension.equals(dimension)) return (V) entity;
            Client client = id >= 1 && id <= clients.size() ? clients.get(id - 1) : null;
            if (client != null && client.dimension.equals(dimension)) return (V) client;
            return levels.entities.entity(id);
        }

        @Override public int localPlayer() { return levels.entities.localPlayer(); }
        @Override public int cameraEntity() { return levels.entities.cameraEntity(); }
        @Override public boolean pose(int id, float partialTick, double[] out) { return levels.entities.pose(id, partialTick, out); }
        @Override public int flags(int id) { return levels.entities.flags(id); }
        @Override public int kind(int id) { return levels.entities.kind(id); }
        @Override public boolean box(int id, float partialTick, CgBoxSink out) { return levels.entities.box(id, partialTick, out); }
        @Override public int parent(int id) { return levels.entities.parent(id); }
        @Override public int rootVehicle(int id) { return levels.entities.rootVehicle(id); }
        @Override public void within(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                                     IntConsumer visitor) {
            levels.entities.within(minX, minY, minZ, maxX, maxY, maxZ, visitor);
        }
    }
}
