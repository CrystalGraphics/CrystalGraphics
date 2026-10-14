package com.crystalgraphics.net.object;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nullable;

import com.crystalgraphics.world.CgEntityQueries;
import com.crystalgraphics.world.CgPlayers;
import com.crystalgraphics.world.CgWorldQueries;

/**
 * One networked object, on the server or as a client sees it: its type, where it is, its owner and its fields.
 *
 * <pre>{@code
 * // server
 * CgNetObject mine = MINE.spawn(CgAnchor.at(level, x, y, z)).owner(player).setInt(FUSE, 40).lifetime(200).start();
 * mine.setInt(FUSE, mine.getInt(FUSE) - 1);
 * mine.moveTo(x, y + 1, z);
 * mine.despawn();                    // viewers end it gracefully; kill() ends it at once
 *
 * // client, inside a behaviour or an onChange
 * int fuse = obj.getInt(FUSE);
 * boolean mineToDefuse = obj.isMine();
 * }</pre>
 *
 * <ul>
 *   <li>The server's object is changed on the server thread; a client's is read on the client thread and written only
 *       while a prediction is being built, or through a field its owner may predict.</li>
 *   <li>Changes made in one tick reach viewers as one, after the tick: the last value of each field, once.</li>
 *   <li>{@link #id()} names it for this session; a client never keeps one across a reconnect.</li>
 * </ul>
 */
public final class CgNetObject {

    /** STOPPING: despawned gracefully, its server behaviour finishing in front of the viewers who saw it. */
    private static final int PENDING = 0, LIVE = 1, STOPPING = 2, ENDING = 3, REMOVED = 4;

    final CgNetType type;
    final boolean server;
    int id;

    CgAnchor.Kind kind;
    @Nullable
    String dimension;
    double x, y, z;
    /** Server: the entity it is on, the player it follows as of this tick, or the level it was anchored in. */
    @Nullable
    Object holder;
    /** The player of a player anchor. */
    @Nullable
    UUID player;
    int chunkX, chunkZ;
    /** The network id of the entity or player it follows; -1 for none. */
    int entityId = -1;
    /** Its player is away, or its chunk unloaded: kept, seen by nobody. */
    boolean dormant;
    /** Server: its key in its type's holder index, for a per-holder type. */
    @Nullable
    Object holderKey;
    @Nullable
    UUID owner;
    /** On a client: this client's player owns it. */
    boolean mine;

    final long[] bits;
    final Object[] refs;
    /** By field index: set since spawn, so sent at a spawn. */
    final long[] set;
    /** By field index: changed this tick. */
    final long[] dirty;
    /** Server, by field index: this tick's value is the one its owner predicted, so the owner is not sent it. */
    final long[] ownerHas;
    boolean anyDirty;
    boolean moved;

    long spawnTick;
    int lifetime = -1;
    boolean transientSpawn;
    int state = PENDING;
    boolean killed;

    /** Server: its server behaviour is made at its next tick. */
    boolean behaviourPending;
    /** Server: its server behaviour has been told of the despawn, and the tick it is ended at, done or not. */
    boolean stopAnnounced;
    long lingerUntil;
    /** Server: viewers are sent that it is stopping, this tick. */
    boolean stopSend;
    /** Server: its level, and the dimension it was found for. */
    @Nullable
    private Object level;
    @Nullable
    private String levelDimension;
    /** Client: shown before the server answered; server: the key the predicting client is sent back, until sent. */
    boolean predicting;
    int predictionKey;
    /** Client: the client tick its prediction was sent. */
    long predictedAt;

    /** Server: by viewer slot, who has been sent its spawn. */
    long[] seenBy = new long[1];
    /** Server: this tick's events, encoded, and per event its start, end and whether it is the owner's alone. */
    @Nullable
    NetBuffer events;
    int[] eventSpans = new int[6];
    int eventCount;

    /** What it is on this side; on a client, the client holding it. */
    @Nullable
    CgNetBehaviour behaviour;
    @Nullable
    ClientObjects client;

    /** A server object, pending. */
    CgNetObject(CgNetType type, CgAnchor anchor) {
        this(type, true);
        kind = anchor.kind;
        dimension = anchor.dimension;
        x = anchor.x;
        y = anchor.y;
        z = anchor.z;
        holder = anchor.handle;
        player = anchor.player;
        owner = anchor.player;
        chunkX = anchor.chunkX;
        chunkZ = anchor.chunkZ;
        if (kind == CgAnchor.Kind.ENTITY) entityId = CgEntityQueries.idOf(holder);
    }

    CgNetObject(CgNetType type, boolean server) {
        this.type = type;
        this.server = server;
        int n = type.fields.length;
        bits = new long[n];
        refs = new Object[n];
        for (int i = 0; i < n; i++) {
            bits[i] = type.fields[i].fallbackBits;
            refs[i] = type.fields[i].fallback;
        }
        set = new long[(n + 63) >>> 6];
        dirty = new long[set.length];
        ownerHas = new long[set.length];
    }

    // ── Before it starts ────────────────────────────────────────────────────────────────────────

    /** Its owner: a player entity, or a profile id. Before it starts; a player anchor's player by default. */
    public CgNetObject owner(Object playerOrId) {
        pending();
        if (playerOrId instanceof UUID) {
            owner = (UUID) playerOrId;
        } else {
            owner = CgPlayers.idOf(Objects.requireNonNull(playerOrId, "playerOrId"));
            if (owner == null) throw new IllegalArgumentException("not a player: " + playerOrId);
        }
        return this;
    }

    /**
     * Its server behaviour, in place of its type's {@link CgNetType#onServer} factory: for an authority built from what
     * the spawner knows. Before it starts; ticked from its first server tick like the factory's.
     *
     * <pre>{@code
     * CgNetObject wave = WAVE.spawn(CgAnchor.on(caster)).behaviour(new WaveAuthority(level, look)).start();
     * }</pre>
     */
    public CgNetObject behaviour(CgNetBehaviour behaviour) {
        if (!server) throw new IllegalStateException("a server behaviour is the server's");
        pending();
        this.behaviour = Objects.requireNonNull(behaviour, "behaviour");
        return this;
    }

    /** Despawns gracefully after {@code ticks} server ticks. */
    public CgNetObject lifetime(int ticks) {
        if (ticks <= 0) throw new IllegalArgumentException("lifetime must be positive: " + ticks);
        lifetime = ticks;
        return this;
    }

    /**
     * Live: sent to whoever sees it at the end of this tick, and to whoever comes to see it later. A prediction: shown
     * here now, and the server asked to make it real.
     */
    public CgNetObject start() {
        pending();
        if (predicting) client.submit(this, false);
        else CgNetObjects.start(this);
        return this;
    }

    /**
     * Built now by every client that sees it at the end of this tick, never by a later one, and kept nowhere on the
     * server: a spark, a sound, a flash.
     */
    public CgNetObject fire() {
        pending();
        transientSpawn = true;
        if (predicting) client.submit(this, true);
        else CgNetObjects.fire(this);
        return this;
    }

    private void pending() {
        if (!server && !predicting) throw new IllegalStateException("a client's object is the server's to change");
        if (state != PENDING) throw new IllegalStateException(this + " has started");
    }

    // ── Reading ─────────────────────────────────────────────────────────────────────────────────

    public CgNetType type() {
        return type;
    }

    public int id() {
        return id;
    }

    public boolean isServer() {
        return server;
    }

    public CgAnchor.Kind anchor() {
        return kind;
    }

    /** Its dimension's id; null for a global object, and for a player anchor whose player has not been seen. */
    @Nullable
    public String dimension() {
        return dimension;
    }

    /** A point's place; an entity's or a player's on the server only, where a client follows {@link #entityId()}. */
    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    /** The network id of the entity or player it is anchored on, as its clients know it; -1 for other anchors. */
    public int entityId() {
        return entityId;
    }

    /**
     * The host's entity it is anchored on, cast to the type the caller names: on the server the entity, or the player it
     * follows (null while that player is away); on a client the entity of {@link #entityId()} in the client's level,
     * null while it is not loaded. Null for other anchors.
     *
     * <pre>{@code
     * LivingEntity wearer = aura.entity();
     * if (wearer != null) wearer.addEffect(GLOW);
     * }</pre>
     */
    @Nullable
    public <V> V entity() {
        if (kind != CgAnchor.Kind.ENTITY && kind != CgAnchor.Kind.PLAYER) return null;
        if (server) return cast(holder);
        return entityId < 0 ? null : CgEntityQueries.client().entity(entityId);
    }

    @SuppressWarnings("unchecked")
    private static <V> V cast(Object o) {
        return (V) o;
    }

    /**
     * Server: the level it is in, as the loader knows it: what the world facades take for its blocks and entities.
     * Null for a global object, a player away, and a dimension the server has no level for.
     *
     * <pre>{@code
     * CgWorldQuery world = CgWorldQueries.of(obj.level());     // NONE where it is null
     * CgCollisionWorld.of(obj.level()).ray()...
     * }</pre>
     */
    @Nullable
    public Object level() {
        if (!server) throw new IllegalStateException("a level is the server's; a client is in its own");
        switch (kind) {
            case GLOBAL:
                return null;
            case ENTITY:
            case PLAYER:
                if (holder == null) return null;
                break;
            default:
                if (holder != null) return holder;   // the level it was anchored in, or its chunk's
        }
        if (dimension == null) return null;
        if (!dimension.equals(levelDimension)) {
            level = CgWorldQueries.level(holder != null ? holder : CgNetObjects.server(), dimension);
            levelDimension = level == null ? null : dimension;
        }
        return level;
    }

    /** The profile id of a player anchor's player, on the server; null for other anchors. */
    @Nullable
    public UUID player() {
        return player;
    }

    /** A chunk anchor's chunk. */
    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    /** Server: kept but seen by nobody, as its player is away or its chunk unloaded. */
    public boolean isDormant() {
        return dormant;
    }

    /** Its owner's profile id, on the server; null for none, and on a client. */
    @Nullable
    public UUID owner() {
        return owner;
    }

    /** On a client: this client's player owns it. */
    public boolean isMine() {
        return mine;
    }

    /** Server ticks since it spawned, on either side. */
    public long age() {
        long now = server ? CgNetObjects.currentTick() : client == null ? spawnTick : client.serverTick;
        return spawnTick < 0 ? 0 : now - spawnTick;
    }

    /** On a client: shown ahead of the server, which has not yet made it real. */
    public boolean isPredicted() {
        return predicting;
    }

    /** It has despawned, or been killed: nothing it sets is sent. */
    public boolean isRemoved() {
        return state >= ENDING;
    }

    /**
     * Despawned gracefully, while its server behaviour finishes: what it sets and emits still reaches the viewers who
     * saw it, and nobody new is sent it. On a client, from {@link CgNetBehaviour#stopping} on.
     */
    public boolean isStopping() {
        return state == STOPPING;
    }

    /** {@link #fire() Fired}: built once by those who saw it, kept nowhere, never despawned by the server. */
    public boolean isFired() {
        return transientSpawn;
    }

    /** Whether {@code field} holds a value set on the server, rather than its fallback: on a client, one it was sent. */
    public boolean has(CgNetField<?> field) {
        return has(set, type.require(field));
    }

    /** What it is on this side: its client behaviour, or on the server its server behaviour. */
    @Nullable
    @SuppressWarnings("unchecked")
    public <B extends CgNetBehaviour> B behaviour() {
        return (B) behaviour;
    }

    @SuppressWarnings("unchecked")
    public <V> V get(CgNetField<V> field) {
        int i = type.require(field);
        return field.kind == CgNetField.Kind.CODEC ? (V) refs[i] : field.box(bits[i]);
    }

    public int getInt(CgNetField<Integer> field) {
        return (int) bits[primitive(field, CgNetField.Kind.INT)];
    }

    public long getLong(CgNetField<Long> field) {
        return bits[primitive(field, CgNetField.Kind.LONG)];
    }

    public float getFloat(CgNetField<Float> field) {
        return Float.intBitsToFloat((int) bits[primitive(field, CgNetField.Kind.FLOAT)]);
    }

    public double getDouble(CgNetField<Double> field) {
        return Double.longBitsToDouble(bits[primitive(field, CgNetField.Kind.DOUBLE)]);
    }

    public boolean getBool(CgNetField<Boolean> field) {
        return bits[primitive(field, CgNetField.Kind.BOOL)] != 0;
    }

    private int primitive(CgNetField<?> field, CgNetField.Kind kind) {
        if (field.kind != kind) throw new IllegalArgumentException(field + " is not " + kind);
        return type.require(field);
    }

    // ── Writing, on the server ──────────────────────────────────────────────────────────────────

    public <V> CgNetObject set(CgNetField<V> field, V value) {
        int i = type.require(field);
        if (field.kind == CgNetField.Kind.CODEC) {
            if (predictLocally(i)) {
                refs[i] = value;
                client.predicted(this, i, value);
                return this;
            }
            writable();
            if (!Objects.equals(refs[i], value)) {
                refs[i] = value;
                changed(i);
            } else {
                mark(set, i);
            }
            return this;
        }
        return setBits(i, field.bitsOf(Objects.requireNonNull(value, "a primitive field's value")));
    }

    public CgNetObject setInt(CgNetField<Integer> field, int value) {
        return setBits(primitive(field, CgNetField.Kind.INT), value);
    }

    public CgNetObject setLong(CgNetField<Long> field, long value) {
        return setBits(primitive(field, CgNetField.Kind.LONG), value);
    }

    public CgNetObject setFloat(CgNetField<Float> field, float value) {
        return setBits(primitive(field, CgNetField.Kind.FLOAT), Float.floatToRawIntBits(value));
    }

    public CgNetObject setDouble(CgNetField<Double> field, double value) {
        return setBits(primitive(field, CgNetField.Kind.DOUBLE), Double.doubleToRawLongBits(value));
    }

    public CgNetObject setBool(CgNetField<Boolean> field, boolean value) {
        return setBits(primitive(field, CgNetField.Kind.BOOL), value ? 1 : 0);
    }

    /**
     * Sends {@code field}'s current value again at the tick's end, changed or not, to every viewer it reaches, the owner
     * included: a correction for a client that may have guessed, as Unreal's {@code ForceNetUpdate} is for one
     * property. Server only.
     *
     * <pre>{@code
     * boolean wanted = !character.getBool(FLYING);
     * if (hurt) character.resend(FLYING);          // refused: the owner, who toggled at once, is put right
     * else character.setBool(FLYING, wanted);
     * }</pre>
     *
     * Before the object is live it does nothing: its spawn carries every field.
     */
    public CgNetObject resend(CgNetField<?> field) {
        if (!server) throw new IllegalStateException("resend is the server's: a client's object is the server's to change");
        int i = type.require(field);
        mark(set, i);
        changed(i);
        return this;
    }

    private CgNetObject setBits(int i, long value) {
        if (predictLocally(i)) {
            bits[i] = value;
            client.predicted(this, i, type.fields[i].box(value));
            return this;
        }
        writable();
        if (bits[i] != value) {
            bits[i] = value;
            changed(i);
        } else {
            mark(set, i);
        }
        return this;
    }

    private void changed(int i) {
        mark(set, i);
        if (state == LIVE || state == STOPPING) {
            mark(dirty, i);
            unmark(ownerHas, i);
            anyDirty = true;
        }
    }

    private void writable() {
        if (!server && !(predicting && state == PENDING)) {
            throw new IllegalStateException("a client's object is the server's to change, but for its owner's predicted fields");
        }
    }

    /** A client's live object, a field its owner predicts: applied here, and sent. */
    private boolean predictLocally(int i) {
        if (server || state != LIVE || client == null) return false;
        CgNetField<?> field = type.fields[i];
        if (field.command == null) throw new IllegalStateException(field.name + " is not predicted: it is the server's to change");
        if (!mine) throw new IllegalStateException(field.name + " is its owner's to predict, and this client does not own " + this);
        mark(set, i);
        return true;
    }

    /** Server: a value the owner predicted, as sanitized; {@code asSent} when the owner already shows it. */
    @SuppressWarnings("unchecked")
    void predictedSet(int i, Object value, boolean asSent) {
        CgNetField<Object> field = (CgNetField<Object>) type.fields[i];
        set(field, value);
        // refused back to the value the server already held: no change, so nothing would correct the owner's guess
        if (asSent) mark(ownerHas, i);
        else changed(i);
    }

    /** Moves a point-anchored object; who sees it is decided again at the end of the tick. */
    public CgNetObject moveTo(double x, double y, double z) {
        writable();
        if (kind != CgAnchor.Kind.POINT) throw new IllegalStateException(this + " is not anchored at a point");
        if (this.x == x && this.y == y && this.z == z) return this;
        this.x = x;
        this.y = y;
        this.z = z;
        if (state == LIVE || state == STOPPING) {
            moved = true;
        }
        return this;
    }

    /** Where the entity or player it follows is, on the server; its clients follow the entity themselves. */
    void place(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** Sends {@code event} to every client that sees it at the end of the tick, after the tick's changes; nothing once it has ended. */
    public <P> CgNetObject emit(CgNetEvent<P> event, @Nullable P value) {
        return emit(event, value, false);
    }

    /** Sends {@code event} to its owner alone, if the owner sees it. */
    public <P> CgNetObject emitToOwner(CgNetEvent<P> event, @Nullable P value) {
        return emit(event, value, true);
    }

    private <P> CgNetObject emit(CgNetEvent<P> event, @Nullable P value, boolean toOwner) {
        if (!server) throw new IllegalStateException("an event is the server's to emit");
        int index = type.requireEvent(event);
        if (state == PENDING) throw new IllegalStateException(this + " is not live: start it first");
        if (state == REMOVED) return this;
        if (events == null) events = new NetBuffer(128);
        if (eventCount * 3 == eventSpans.length) eventSpans = Arrays.copyOf(eventSpans, eventSpans.length * 2);
        int start = events.size;
        CgNetObjects.writeEvent(events, id, index, event, value);
        eventSpans[eventCount * 3] = start;
        eventSpans[eventCount * 3 + 1] = events.size;
        eventSpans[eventCount * 3 + 2] = toOwner ? 1 : 0;
        eventCount++;
        return this;
    }

    /**
     * On a client: asks the server to run {@code command} on this object. The command's rate decides when it leaves;
     * the server decides whether it runs.
     */
    public <P> CgNetObject send(CgNetCommand<P> command, @Nullable P value) {
        if (server) throw new IllegalStateException("a command is a client's to send; the server handles it");
        type.requireCommand(command);
        if (!mine && !command.fromAnyViewer) {
            throw new IllegalStateException(command + " is its owner's to send, and this client does not own " + this);
        }
        if (client != null && state == LIVE) client.send(this, command, value);
        return this;
    }

    /**
     * Ends it gracefully at the end of the tick: each viewer's behaviour finishes what it shows. With a server
     * behaviour, it is {@link #isStopping stopping} first: its viewers keep it, and hear what it sets and emits, until
     * that behaviour is {@link CgNetBehaviour#done done} or its type's linger runs out.
     */
    public void despawn() {
        end(false);
    }

    /** Ends it at once on every viewer, stopping or not. */
    public void kill() {
        end(true);
    }

    private void end(boolean kill) {
        writable();
        if (state >= ENDING || state == STOPPING && !kill) return;
        killed = kill;
        if (state == PENDING) {
            state = REMOVED;
            return;
        }
        state = !kill && server && behaviour != null && state == LIVE ? STOPPING : ENDING;
    }

    boolean isLive() {
        return state == LIVE;
    }

    /** Server: its stop has run its course; a client: the server said it is stopping. */
    void stopped() {
        state = server ? ENDING : STOPPING;
    }

    boolean isEnding() {
        return state == ENDING;
    }

    void live(int id, long tick) {
        this.id = id;
        this.spawnTick = tick;
        state = LIVE;
    }

    void removed() {
        state = REMOVED;
    }

    // ── Bit sets ────────────────────────────────────────────────────────────────────────────────

    static void mark(long[] bits, int i) {
        bits[i >>> 6] |= 1L << i;
    }

    static boolean has(long[] bits, int i) {
        int word = i >>> 6;
        return word < bits.length && (bits[word] & 1L << i) != 0;
    }

    static void unmark(long[] bits, int i) {
        int word = i >>> 6;
        if (word < bits.length) bits[word] &= ~(1L << i);
    }

    void seenBy(int slot) {
        if (slot >>> 6 >= seenBy.length) seenBy = Arrays.copyOf(seenBy, (slot >>> 6) + 1);
        mark(seenBy, slot);
    }

    void clearDirty() {
        Arrays.fill(dirty, 0);
        Arrays.fill(ownerHas, 0);
        anyDirty = false;
        moved = false;
        stopSend = false;
        predictionKey = 0;
        if (eventCount > 0) {
            eventCount = 0;
            events.clear();
        }
    }

    @Override
    public String toString() {
        return "CgNetObject[" + type.name + " #" + id + (server ? "" : predicting ? " predicted" : " client") + "]";
    }
}
