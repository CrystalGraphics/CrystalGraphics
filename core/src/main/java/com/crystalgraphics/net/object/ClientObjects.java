package com.crystalgraphics.net.object;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgraphics.net.rate.CgRateGate;
import com.crystalgraphics.serialization.CgCodecException;

/** What one client connection sees: the server's objects as its frames describe them. Client thread. */
final class ClientObjects {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    /**
     * A server type as this client knows it: the local type; each server field and event index to a local one or -1;
     * each local command index to the server's or -1.
     */
    private static final class TypeView {
        @Nullable
        final CgNetType type;
        final int[] fields;
        final int[] events;
        final int[] commands;

        TypeView(@Nullable CgNetType type, int[] fields, int[] events, int[] commands) {
            this.type = type;
            this.fields = fields;
            this.events = events;
            this.commands = commands;
        }
    }

    private final CgProtocolConnection<Object> connection;
    /** Client ticks, as the gate's clock: 50 ms each. */
    private long ticks;
    private final CgRateGate<CgNetObject, Object> gate = new CgRateGate<CgNetObject, Object>(this::write).setClock(() -> ticks * 50);
    private final NetBuffer outgoing = new NetBuffer(64);
    private final NetBuffer value = new NetBuffer(64);
    /** This tick's prediction requests, for {@code cg/predict}. */
    private final NetBuffer predictions = new NetBuffer(64);
    /** Predictions the server has not answered, by key. */
    private final Map<Integer, CgNetObject> pending = new HashMap<>();
    private int nextKey;

    private final IdTable objects = new IdTable();
    /** Per-holder objects riding an entity, by local type index, keyed by entity id + 1 (an id may be 0). */
    private IdTable[] holders = new IdTable[8];
    /** Despawned gracefully, or fired: ticking until their behaviour is done. */
    private final List<CgNetObject> ending = new ArrayList<>();
    /** By the server's type index, as its frames name them. */
    private TypeView[] types = new TypeView[8];
    /** The same views by local type index, for an object's own type. */
    private TypeView[] byLocal = new TypeView[8];
    private int[] changedFields = new int[16];
    private boolean reportedMalformed;

    /** The server's tick as of its last frame, counted on between frames. */
    long serverTick;
    private boolean framed;

    ClientObjects(CgProtocolConnection<Object> connection) {
        this.connection = connection;
    }

    /** What this client shows of {@code type}: the server's objects, and its predictions still waiting on it. */
    List<CgNetObject> visible(CgNetType type) {
        List<CgNetObject> out = new ArrayList<>();
        for (int s = 0; s < objects.capacity(); s++) {
            CgNetObject obj = objects.at(s);
            if (obj != null && obj.type == type) out.add(obj);
        }
        for (CgNetObject obj : pending.values()) if (obj.type == type && !obj.transientSpawn) out.add(obj);
        return out;
    }

    @Nullable
    CgNetObject find(int id) {
        return objects.get(id);
    }

    /** The per-holder object of {@code type} riding entity {@code entityId}, or null: {@link CgNetType#find}. */
    @Nullable
    CgNetObject holder(CgNetType type, int entityId) {
        IdTable table = type.index < holders.length ? holders[type.index] : null;
        return table == null || entityId < 0 ? null : table.get(entityId + 1);
    }

    private void held(CgNetObject obj) {
        if (!obj.type.perHolder || obj.entityId < 0 || obj.kind != CgAnchor.Kind.ENTITY && obj.kind != CgAnchor.Kind.PLAYER) return;
        if (obj.type.index >= holders.length) holders = Arrays.copyOf(holders, obj.type.index + 8);
        IdTable table = holders[obj.type.index];
        if (table == null) holders[obj.type.index] = table = new IdTable();
        table.put(obj.entityId + 1, obj);
    }

    private void unheld(CgNetObject obj) {
        IdTable table = obj.type.index < holders.length ? holders[obj.type.index] : null;
        if (table != null && obj.entityId >= 0 && table.get(obj.entityId + 1) == obj) table.remove(obj.entityId + 1);
    }

    int size() {
        return objects.size();
    }

    int count(String namespace) {
        int n = 0;
        for (int s = 0; s < objects.capacity(); s++) {
            CgNetObject obj = objects.at(s);
            if (obj != null && obj.type.namespace.equals(namespace)) n++;
        }
        return n;
    }

    // ── Frames ──────────────────────────────────────────────────────────────────────────────────

    /** Object frames received and their bytes, and the largest, since this connection opened. */
    long bytesReceived;
    int framesReceived, largestFrame;

    void receive(byte[] payload) {
        bytesReceived += payload.length;
        framesReceived++;
        largestFrame = Math.max(largestFrame, payload.length);
        NetBuffer in = new NetBuffer(payload);
        try {
            serverTick = in.readVarLong();
            framed = true;
            while (in.more()) {
                int record = in.readByte();
                switch (record) {
                    case CgNetObjects.DEFINE: define(in); break;
                    case CgNetObjects.SPAWN: spawn(in); break;
                    case CgNetObjects.CHANGE: change(in); break;
                    case CgNetObjects.MOVE: move(in); break;
                    case CgNetObjects.DESPAWN: despawn(in.readVarInt(), in.readBool()); break;
                    case CgNetObjects.EVENT: event(in); break;
                    case CgNetObjects.REFUSED: refused(in.readVarInt()); break;
                    case CgNetObjects.STOPPING: stopping(in.readVarInt()); break;
                    default: throw new CgCodecException("unknown object record " + record);
                }
            }
        } catch (CgCodecException malformed) {
            if (!reportedMalformed) {
                reportedMalformed = true;
                LOGGER.warn("[cg-net] a malformed object frame was dropped: {}", malformed.getMessage());
            }
        }
    }

    private void define(NetBuffer in) {
        int index = in.readVarInt();
        String name = in.readString();
        CgNetType type = CgNetType.named(name);
        int[] fields = new int[in.readVarInt()];
        for (int i = 0; i < fields.length; i++) {
            String field = in.readString();
            fields[i] = -1;
            if (type == null) continue;
            for (int j = 0; j < type.fields.length; j++) {
                if (type.fields[j].name.equals(field)) fields[i] = j;
            }
        }
        int[] events = new int[in.readVarInt()];
        for (int i = 0; i < events.length; i++) {
            String event = in.readString();
            events[i] = -1;
            if (type == null) continue;
            for (int j = 0; j < type.events.length; j++) {
                if (type.events[j].name.equals(event)) events[i] = j;
            }
        }
        int[] commands = new int[type == null ? 0 : type.commands.length];
        Arrays.fill(commands, -1);
        int serverCommands = in.readVarInt();
        for (int i = 0; i < serverCommands; i++) {
            String command = in.readString();
            if (type == null) continue;
            for (int j = 0; j < type.commands.length; j++) {
                if (type.commands[j].name.equals(command)) commands[j] = i;
            }
        }
        if (index >= types.length) types = Arrays.copyOf(types, index + 8);
        types[index] = new TypeView(type, fields, events, commands);
        if (type != null) {
            if (type.index >= byLocal.length) byLocal = Arrays.copyOf(byLocal, type.index + 8);
            byLocal[type.index] = types[index];
        }
    }

    private void spawn(NetBuffer in) {
        int id = in.readVarInt();
        int typeIndex = in.readVarInt();
        TypeView view = typeIndex < types.length ? types[typeIndex] : null;
        if (view == null) throw new CgCodecException("a spawn of an undefined type " + typeIndex);
        int flags = in.readByte();
        int key = (flags & CgNetObjects.FLAG_PREDICTED) != 0 ? in.readVarInt() : 0;
        long spawnTick = in.readVarLong();
        int kind = in.readByte();
        if (kind >= CgAnchor.Kind.values().length) throw new CgCodecException("unknown anchor " + kind);
        CgAnchor.Kind anchor = CgAnchor.Kind.values()[kind];
        String dimension = anchor == CgAnchor.Kind.GLOBAL ? null : in.readString();
        CgNetObject predicted = key == 0 ? null : pending.get(key);
        if (predicted != null && predicted.type == view.type) {
            pending.remove(key);
            readPlace(in, anchor, predicted.transientSpawn ? null : predicted);
            if (predicted.transientSpawn) {
                readFields(in, view, null);
                predicted.predicting = false;
                if (!done(predicted)) ending.add(predicted);
            } else {
                adopt(predicted, in, view, id, spawnTick, anchor, dimension);
            }
            return;
        }
        CgNetObject obj = view.type == null ? null : new CgNetObject(view.type, false);
        readPlace(in, anchor, obj);
        if (view.type == null) {
            readFields(in, view, null);
            return;
        }
        obj.id = id;
        obj.kind = anchor;
        obj.dimension = dimension;
        obj.mine = (flags & CgNetObjects.FLAG_MINE) != 0;
        obj.transientSpawn = (flags & CgNetObjects.FLAG_TRANSIENT) != 0;
        obj.spawnTick = spawnTick;
        obj.client = this;
        obj.live(id, spawnTick);
        int changed = readFields(in, view, obj);

        CgNetObject previous = obj.transientSpawn ? null : objects.put(id, obj);
        if (previous != null) end(previous, true);
        if (!obj.transientSpawn) held(obj);
        Function<CgNetObject, CgNetBehaviour> factory = obj.type.client;
        if (factory != null) {
            try {
                obj.behaviour = factory.apply(obj);
                if (obj.behaviour != null) obj.behaviour.spawned(obj, Math.max(0, serverTick - spawnTick));
            } catch (RuntimeException failed) {
                LOGGER.error("[cg-net] the client behaviour of {} failed to spawn", obj, failed);
            }
        }
        notifyListeners(obj, changed);
        if (obj.transientSpawn && !done(obj)) ending.add(obj);
    }

    /** The server made a prediction real: the predicted object becomes the server's, its fields overriding. */
    private void adopt(CgNetObject obj, NetBuffer in, TypeView view, int id, long spawnTick, CgAnchor.Kind anchor,
                       @Nullable String dimension) {
        long[] bits = obj.bits.clone();
        Object[] refs = obj.refs.clone();
        obj.predicting = false;
        obj.predictionKey = 0;
        obj.kind = anchor;
        obj.dimension = dimension;
        obj.live(id, spawnTick);
        int read = readFields(in, view, obj);
        int changed = 0;
        for (int c = 0; c < read; c++) {
            int i = changedFields[c];
            boolean differs = obj.type.fields[i].kind == CgNetField.Kind.CODEC ? !Objects.equals(refs[i], obj.refs[i]) : bits[i] != obj.bits[i];
            if (differs) changedFields[changed++] = i;
        }
        objects.put(id, obj);
        if (obj.behaviour != null) {
            for (int c = 0; c < changed; c++) {
                try {
                    obj.behaviour.changed(obj, obj.type.fields[changedFields[c]]);
                } catch (RuntimeException failed) {
                    LOGGER.error("[cg-net] the client behaviour of {} failed on a change", obj, failed);
                }
            }
            if (changed > 0) received(obj);
        }
        notifyListeners(obj, changed);
    }

    private static void received(CgNetObject obj) {
        try {
            obj.behaviour.received(obj);
        } catch (RuntimeException failed) {
            LOGGER.error("[cg-net] the client behaviour of {} failed on a change", obj, failed);
        }
    }

    private void change(NetBuffer in) {
        CgNetObject obj = objects.get(in.readVarInt());
        TypeView view = obj == null ? null : viewOf(obj.type);
        int changed = readFields(in, view, obj);
        if (obj == null || changed == 0) return;
        CgNetBehaviour behaviour = obj.behaviour;
        if (behaviour != null) {
            for (int c = 0; c < changed; c++) {
                try {
                    behaviour.changed(obj, obj.type.fields[changedFields[c]]);
                } catch (RuntimeException failed) {
                    LOGGER.error("[cg-net] the client behaviour of {} failed on a change", obj, failed);
                }
            }
            received(obj);
        }
        notifyListeners(obj, changed);
    }

    private void move(NetBuffer in) {
        CgNetObject obj = objects.get(in.readVarInt());
        int kind = in.readByte();
        if (kind >= CgAnchor.Kind.values().length) throw new CgCodecException("unknown anchor " + kind);
        CgAnchor.Kind anchor = CgAnchor.Kind.values()[kind];
        if (obj != null) unheld(obj);
        readPlace(in, anchor, obj);
        String dimension = anchor == CgAnchor.Kind.PLAYER ? in.readString() : null;
        if (obj == null) return;
        held(obj);
        if (dimension != null) obj.dimension = dimension;
        if (obj.behaviour != null) {
            try {
                obj.behaviour.moved(obj);
            } catch (RuntimeException failed) {
                LOGGER.error("[cg-net] the client behaviour of {} failed on a move", obj, failed);
            }
        }
    }

    /** The counterpart of {@code CgNetObjects.writePlace}, into {@code into} when it is known. */
    private static void readPlace(NetBuffer in, CgAnchor.Kind anchor, @Nullable CgNetObject into) {
        switch (anchor) {
            case POINT: {
                double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                if (into != null) into.place(x, y, z);
                return;
            }
            case ENTITY:
            case PLAYER: {
                int entityId = in.readVarInt();
                if (into != null) into.entityId = entityId;
                return;
            }
            case CHUNK: {
                int chunkX = (int) in.readZigZag(), chunkZ = (int) in.readZigZag();
                if (into != null) {
                    into.chunkX = chunkX;
                    into.chunkZ = chunkZ;
                }
                return;
            }
            default:
        }
    }

    @SuppressWarnings("unchecked")
    private void event(NetBuffer in) {
        CgNetObject obj = objects.get(in.readVarInt());
        int serverIndex = in.readVarInt();
        int length = in.readVarInt();
        TypeView view = obj == null ? null : viewOf(obj.type);
        int local = view == null || serverIndex >= view.events.length ? -1 : view.events[serverIndex];
        if (local < 0) {
            in.skip(length);
            return;
        }
        CgNetEvent<Object> event = (CgNetEvent<Object>) obj.type.events[local];
        Object payload = event.read(in, length);
        if (obj.behaviour != null) {
            try {
                obj.behaviour.event(obj, event, payload);
            } catch (RuntimeException failed) {
                LOGGER.error("[cg-net] the client behaviour of {} failed on {}", obj, event, failed);
            }
        }
        for (BiConsumer<CgNetObject, Object> listener : obj.type.eventListeners[local]) {
            try {
                listener.accept(obj, payload);
            } catch (RuntimeException failed) {
                LOGGER.error("[cg-net] an event listener of {} failed", obj.type.name, failed);
            }
        }
    }

    private void despawn(int id, boolean killed) {
        CgNetObject obj = objects.remove(id);
        if (obj != null) end(obj, killed);
    }

    private void stopping(int id) {
        CgNetObject obj = objects.get(id);
        if (obj == null || obj.isStopping()) return;
        obj.stopped();
        if (obj.behaviour == null) return;
        try {
            obj.behaviour.stopping(obj);
        } catch (RuntimeException failed) {
            LOGGER.error("[cg-net] the client behaviour of {} failed to stop", obj, failed);
        }
    }

    @Nullable
    private TypeView viewOf(CgNetType type) {
        return type.index < byLocal.length ? byLocal[type.index] : null;
    }

    /**
     * Reads a field block into {@code obj}, or past it when null. Leaves the local indices changed, ascending, in
     * {@link #changedFields} and answers how many.
     */
    private int readFields(NetBuffer in, @Nullable TypeView view, @Nullable CgNetObject obj) {
        int count = in.readVarInt();
        int changed = 0;
        for (int f = 0; f < count; f++) {
            int serverIndex = in.readVarInt();
            int length = in.readVarInt();
            int end = in.pos + length;
            int local = view == null || serverIndex >= view.fields.length ? -1 : view.fields[serverIndex];
            if (obj == null || local < 0) {
                in.skip(length);
                continue;
            }
            CgNetField<?> field = obj.type.fields[local];
            if (field.kind == CgNetField.Kind.CODEC) {
                // into the held value where its codec can: whatever holds it sees the change
                obj.refs[local] = field.readRef(in, length, obj.refs[local]);
            } else {
                obj.bits[local] = field.readBits(in);
                if (in.pos != end) throw new CgCodecException(field.name + " is " + length + " bytes here, another type there");
            }
            CgNetObject.mark(obj.set, local);
            if (changed == changedFields.length) changedFields = Arrays.copyOf(changedFields, changed * 2);
            changedFields[changed++] = local;
        }
        Arrays.sort(changedFields, 0, changed);
        return changed;
    }

    private void notifyListeners(CgNetObject obj, int changed) {
        for (int c = 0; c < changed; c++) {
            int i = changedFields[c];
            List<BiConsumer<CgNetObject, Object>> listeners = obj.type.changeListeners[i];
            if (listeners.isEmpty()) continue;
            CgNetField<?> field = obj.type.fields[i];
            Object value = field.kind == CgNetField.Kind.CODEC ? obj.refs[i] : field.box(obj.bits[i]);
            for (BiConsumer<CgNetObject, Object> listener : listeners) {
                try {
                    listener.accept(obj, value);
                } catch (RuntimeException failed) {
                    LOGGER.error("[cg-net] a change listener of {} failed", obj.type.name, failed);
                }
            }
        }
    }

    // ── Ending and ticking ──────────────────────────────────────────────────────────────────────

    private void end(CgNetObject obj, boolean killed) {
        unheld(obj);
        obj.killed = killed;
        obj.removed();
        gate.discard(obj);
        if (obj.behaviour == null) return;
        try {
            obj.behaviour.despawned(obj, killed);
        } catch (RuntimeException failed) {
            LOGGER.error("[cg-net] the client behaviour of {} failed to despawn", obj, failed);
        }
        if (!killed && !done(obj)) ending.add(obj);
    }

    private static boolean done(CgNetObject obj) {
        try {
            return obj.behaviour == null || obj.behaviour.done();
        } catch (RuntimeException failed) {
            LOGGER.error("[cg-net] the client behaviour of {} failed", obj, failed);
            return true;
        }
    }

    // ── Predictions ─────────────────────────────────────────────────────────────────────────────

    CgNetObject predict(CgNetType type, CgAnchor anchor) {
        if (type.perHolder) throw new IllegalArgumentException(type.name + " is per holder: its objects are the server's alone");
        if (anchor.kind == CgAnchor.Kind.PLAYER || anchor.kind == CgAnchor.Kind.CHUNK) {
            throw new IllegalArgumentException("a prediction is anchored at a point, on an entity, a dimension or everywhere, not " + anchor);
        }
        CgNetObject obj = new CgNetObject(type, false);
        obj.predicting = true;
        obj.client = this;
        obj.kind = anchor.kind;
        obj.dimension = anchor.dimension;
        obj.place(anchor.x, anchor.y, anchor.z);
        obj.entityId = anchor.entityId;
        return obj;
    }

    /** Shows a prediction now and asks the server for it: (key, type, flags, anchor, place, fields), sent this tick. */
    void submit(CgNetObject obj, boolean transientSpawn) {
        int key = ++nextKey;
        obj.predictionKey = key;
        obj.predictedAt = ticks;
        obj.mine = true;
        obj.live(0, serverTick);
        pending.put(key, obj);

        predictions.writeVarInt(key);
        predictions.writeString(obj.type.name);
        predictions.writeByte(transientSpawn ? CgNetObjects.FLAG_TRANSIENT : 0);
        predictions.writeByte(obj.kind.ordinal());
        if (obj.kind == CgAnchor.Kind.POINT) {
            predictions.writeDouble(obj.x);
            predictions.writeDouble(obj.y);
            predictions.writeDouble(obj.z);
        } else if (obj.kind == CgAnchor.Kind.ENTITY) {
            predictions.writeVarInt(obj.entityId);
        }
        int count = 0;
        for (int i = 0; i < obj.type.fields.length; i++) if (CgNetObject.has(obj.set, i)) count++;
        predictions.writeVarInt(count);
        for (int i = 0; i < obj.type.fields.length; i++) {
            if (!CgNetObject.has(obj.set, i)) continue;
            value.clear();
            obj.type.fields[i].write(value, obj.bits[i], obj.refs[i]);
            predictions.writeVarInt(i);
            predictions.writeVarInt(value.size);
            predictions.append(value, 0, value.size);
        }

        int changed = 0;
        for (int i = 0; i < obj.type.fields.length; i++) {
            if (!CgNetObject.has(obj.set, i)) continue;
            if (changed == changedFields.length) changedFields = Arrays.copyOf(changedFields, changed * 2);
            changedFields[changed++] = i;
        }
        Function<CgNetObject, CgNetBehaviour> factory = obj.type.client;
        if (factory != null) {
            try {
                obj.behaviour = factory.apply(obj);
                if (obj.behaviour != null) obj.behaviour.spawned(obj, 0);
            } catch (RuntimeException failed) {
                LOGGER.error("[cg-net] the client behaviour of {} failed to spawn", obj, failed);
            }
        }
        notifyListeners(obj, changed);
    }

    private void refused(int key) {
        CgNetObject obj = pending.remove(key);
        if (obj != null) end(obj, !obj.type.keepRefused);
    }

    /** Its owner set a predicted field here: shown at once, and sent unless the object is still a prediction. */
    void predicted(CgNetObject obj, int i, Object payload) {
        CgNetField<?> field = obj.type.fields[i];
        if (obj.behaviour != null) {
            try {
                obj.behaviour.changed(obj, field);
            } catch (RuntimeException failed) {
                LOGGER.error("[cg-net] the client behaviour of {} failed on a change", obj, failed);
            }
        }
        changedFields[0] = i;
        notifyListeners(obj, 1);
        if (!obj.predicting) send(obj, field.command, payload);
    }

    // ── Commands ────────────────────────────────────────────────────────────────────────────────

    /** Queues {@code command} for {@code obj} through its rate; sent with the client's tick. */
    void send(CgNetObject obj, CgNetCommand<?> command, @Nullable Object payload) {
        gate.offer(obj, command.name, command.rate, payload);
    }

    /** What the gate lets go: (object id, the server's command index, length, value), into this tick's frame. */
    private void write(CgNetObject obj, String name, @Nullable Object payload) {
        if (obj.isRemoved()) return;
        TypeView view = viewOf(obj.type);
        if (view == null) return;
        for (int i = 0; i < obj.type.commands.length; i++) {
            CgNetCommand<?> command = obj.type.commands[i];
            if (!command.name.equals(name)) continue;
            if (view.commands[i] < 0) return;
            value.clear();
            command.write(value, payload);
            outgoing.writeVarInt(obj.id);
            outgoing.writeVarInt(view.commands[i]);
            outgoing.writeVarInt(value.size);
            outgoing.append(value, 0, value.size);
            return;
        }
    }

    void tick() {
        ticks++;
        if (!framed) serverTick++;
        framed = false;
        for (int s = 0; s < objects.capacity(); s++) {
            CgNetObject obj = objects.at(s);
            if (obj != null) tick(obj);
        }
        if (!pending.isEmpty()) tickPredictions();
        for (int i = ending.size() - 1; i >= 0; i--) {
            CgNetObject obj = ending.get(i);
            tick(obj);
            if (done(obj)) ending.remove(i);
        }
        // After the behaviours: what they command leaves this tick, not the next.
        gate.flush();
        if (!predictions.isEmpty() && !connection.isClosed()) connection.notifyBytes(CgNetObjects.PREDICT, predictions.bytes, predictions.size);
        if (!outgoing.isEmpty() && !connection.isClosed()) connection.notifyBytes(CgNetObjects.COMMANDS, outgoing.bytes, outgoing.size);
        if (!predictions.isEmpty() || !outgoing.isEmpty()) connection.flush();
        predictions.clear();
        outgoing.clear();
    }

    private void tickPredictions() {
        for (Iterator<CgNetObject> it = pending.values().iterator(); it.hasNext(); ) {
            CgNetObject obj = it.next();
            tick(obj);
            if (ticks - obj.predictedAt <= obj.type.predictionTimeout) continue;
            it.remove();
            end(obj, !obj.type.keepRefused);
        }
    }

    private static void tick(CgNetObject obj) {
        if (obj.behaviour == null) return;
        try {
            obj.behaviour.tick(obj);
        } catch (RuntimeException failed) {
            LOGGER.error("[cg-net] the client behaviour of {} failed to tick", obj, failed);
        }
    }

    /** The connection closed: everything it showed ends at once. */
    void closed() {
        List<CgNetObject> all = new ArrayList<>(pending.values());
        for (int s = 0; s < objects.capacity(); s++) {
            CgNetObject obj = objects.at(s);
            if (obj != null) all.add(obj);
        }
        objects.clear();
        pending.clear();
        Arrays.fill(holders, null);
        for (CgNetObject obj : all) end(obj, true);
        ending.clear();
    }
}
