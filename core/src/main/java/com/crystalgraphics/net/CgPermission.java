package com.crystalgraphics.net;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.world.CgPermissions;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;

/**
 * Something a player may or may not do, decided on the server and known to the player's own client: one declaration
 * for the server's guard and the client's greyed-out button.
 *
 * <pre>{@code
 * public static final CgPermission EDIT_NPC = CgPermission.define("mymod:npc.edit")
 *         .defaultsTo(CgPermission.OPERATORS).describedAs("Edit an NPC's settings").build();
 *
 * if (!peer.has(EDIT_NPC)) return;                              // server: a request's guard
 * NPC.allowEdits(EDIT_NPC);                                     // or a networked type's
 * editButton.setDisabled(!EDIT_NPC.granted());                  // client: what the server told it
 * CgPermission.onLocalChange(() -> editButton.setDisabled(!EDIT_NPC.granted()));
 * }</pre>
 *
 * <ul>
 *   <li>The defaults: {@link #EVERYONE}; {@link #OPERATORS}, an operator or the single-player world's owner;
 *       {@link #OWNER}, the owner alone.</li>
 *   <li>Where the loader has a permission API (Forge, NeoForge) and a permission mod is installed, that mod
 *       answers, the node seeded with this default; elsewhere the default answers.</li>
 *   <li>Declare at init, on both sides, before the server starts: a loader gathers its nodes once.</li>
 *   <li>A client's {@link #granted()} follows the server's answer about a second after it changes (an operator
 *       made); a server's {@link #has} answers now.</li>
 * </ul>
 */
public final class CgPermission {

    /** Everyone. */
    public static final CgPermissions.Level EVERYONE = CgPermissions.Level.EVERYONE;
    /** Operators, and the owner of a single-player world (who may have cheats off). */
    public static final CgPermissions.Level OPERATORS = CgPermissions.Level.OPERATORS;
    /** The single-player world's owner alone; nobody on a dedicated server. */
    public static final CgPermissions.Level OWNER = CgPermissions.Level.OWNER;

    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    static final List<CgPermission> DECLARED = new CopyOnWriteArrayList<>();
    private static final Map<String, CgPermission> BY_ID = new ConcurrentHashMap<>();

    private final String id;
    private final CgPermissions.Level level;
    private final String description;

    private CgPermission(String id, CgPermissions.Level level, String description) {
        this.id = id;
        this.level = level;
        this.description = description;
    }

    /** Starts declaring a permission {@code namespace:path}, lowercase: {@code "mymod:npc.edit"}. */
    public static Builder define(String id) {
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("a permission is namespace:path in lowercase, not '" + id + "'");
        return new Builder(id);
    }

    /** The permission declared as {@code id}, or null. */
    @Nullable
    public static CgPermission named(String id) {
        return BY_ID.get(id);
    }

    public String id() {
        return id;
    }

    public CgPermissions.Level defaultLevel() {
        return level;
    }

    /** Server: whether {@code peer} has it now. */
    public boolean has(CgPeer peer) {
        Boolean answered = CgPermissions.has(peer.id(), id);
        if (answered != null) return answered;
        return byDefault(peer.isOperator(), peer.isOwner());
    }

    private boolean byDefault(boolean operator, boolean owner) {
        switch (level) {
            case EVERYONE: return true;
            case OPERATORS: return operator || owner;
            default: return owner;
        }
    }

    /** Client: whether the server last said this client's player has it; false before it has said. */
    public boolean granted() {
        return LOCAL.granted.contains(id);
    }

    /** Client: whether the server last said this client's player is an operator. */
    public static boolean localOperator() {
        return LOCAL.operator;
    }

    /** Client: whether the server last said this client's player owns the single-player world. */
    public static boolean localOwner() {
        return LOCAL.owner;
    }

    /** Client: {@code listener}, on the client thread, each time the server's answers for this player change. */
    public static void onLocalChange(Runnable listener) {
        LISTENERS.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public String toString() {
        return "CgPermission[" + id + " " + level + "]";
    }

    public static final class Builder {
        private final String id;
        private CgPermissions.Level level = OPERATORS;
        private String description = "";

        private Builder(String id) {
            this.id = id;
        }

        /** Who has it unless a permission mod says otherwise; {@link #OPERATORS} unless said. */
        public Builder defaultsTo(CgPermissions.Level level) {
            this.level = Objects.requireNonNull(level, "level");
            return this;
        }

        /** What it allows, in words, for a permission mod's list. */
        public Builder describedAs(String description) {
            this.description = Objects.requireNonNull(description, "description");
            return this;
        }

        /** @throws IllegalStateException when {@code id} is declared already */
        public CgPermission build() {
            CgPermission made = new CgPermission(id, level, description);
            if (BY_ID.putIfAbsent(id, made) != null) throw new IllegalStateException("permission " + id + " is declared twice");
            DECLARED.add(made);
            CgPermissions.declare(id, level, description, player -> {
                CgPeer peer = CgNetwork.peer(player);
                return peer != null && made.byDefault(peer.isOperator(), peer.isOwner());
            });
            return made;
        }
    }

    // ── Each peer's own answers ─────────────────────────────────────────────────────────────────

    /** What the server told a client of its own player. */
    record Answers(boolean operator, boolean owner, List<String> granted) {
        static final CgCodec<Answers> CODEC = CgCodecs.record(
                CgCodecs.field("o", CgCodecs.BOOL, Answers::operator),
                CgCodecs.field("w", CgCodecs.BOOL, Answers::owner),
                CgCodecs.field("g", CgCodecs.listOf(CgCodecs.STRING), Answers::granted),
                Answers::new);
    }

    /** A client's last answers. */
    private static final class Local {
        volatile boolean operator, owner;
        volatile Set<String> granted = new HashSet<>();
    }

    private static final Local LOCAL = new Local();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    static final CgMessage<Answers> ANSWERS = CgMessage.toClients("crystalgraphics:permissions", Answers.CODEC);

    static {
        ANSWERS.onReceive(answers -> {
            LOCAL.operator = answers.operator();
            LOCAL.owner = answers.owner();
            LOCAL.granted = new HashSet<>(answers.granted());
            for (Runnable listener : LISTENERS) listener.run();
        });
    }

    /** Server, per peer: the answers last sent, and when they are next checked. */
    static final class Sent {
        @Nullable
        Answers last;
        int checkIn;
    }

    private static final Map<UUID, Sent> SENT = new ConcurrentHashMap<>();
    /** Ticks between checks of one peer's answers; peers are spread across them. */
    static final int CHECK_EVERY = 20;

    static void joined(CgPeer peer) {
        Sent sent = new Sent();
        sent.checkIn = 1;
        SENT.put(peer.id(), sent);
    }

    static void left(UUID id) {
        SENT.remove(id);
    }

    /** Server thread, each tick: each peer's answers checked once a second, sent when they changed. */
    static void serverTick() {
        if (SENT.isEmpty()) return;
        for (Map.Entry<UUID, Sent> e : SENT.entrySet()) {
            Sent sent = e.getValue();
            if (--sent.checkIn > 0) continue;
            sent.checkIn = CHECK_EVERY;
            CgPeer peer = CgNetwork.peer(e.getKey());
            if (peer == null) continue;
            Answers now = answersOf(peer);
            if (now.equals(sent.last)) continue;
            sent.last = now;
            ANSWERS.send(CgAudience.player(peer.id()), now);
        }
    }

    private static Answers answersOf(CgPeer peer) {
        List<String> granted = new ArrayList<>();
        for (CgPermission p : DECLARED) if (p.has(peer)) granted.add(p.id);
        return new Answers(peer.isOperator(), peer.isOwner(), granted);
    }

    /** Server: checks {@code peer}'s answers at the next tick rather than within the second. */
    public static void recheck(CgPeer peer) {
        Sent sent = SENT.get(peer.id());
        if (sent != null) sent.checkIn = 1;
    }
}
