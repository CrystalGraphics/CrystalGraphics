package com.crystalgraphics.net;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.crystalgraphics.net.protocol.CgProtocolConnection;

/**
 * A connected player, as a server's connection names it: {@link CgProtocolConnection#peer()} answers one.
 *
 * <pre>{@code
 * CgProtocols.server("mymod", connection -> {
 *     CgPeer peer = (CgPeer) connection.peer();
 *     connection.onNotify("mymod/hello", args -> greet(peer.name()));
 * });
 * }</pre>
 *
 * <ul>
 *   <li>{@link #id()} is the profile UUID, stable across death, respawn and a dimension change: key on it.</li>
 *   <li>{@link #player()} is the live player handle, resolved on each call. Never hold it: the game builds a new
 *       entity on a respawn, and a held one is a body nobody is in.</li>
 *   <li>{@link #name()} is the name at join time, for logs and display, never a key.</li>
 * </ul>
 *
 * @see CgNetwork
 */
public final class CgPeer {

    private final UUID id;
    private final String name;
    private final Supplier<Object> player;

    /** @param player resolves the live player handle, or null once it has gone */
    public CgPeer(UUID id, String name, Supplier<Object> player) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = name == null ? id.toString() : name;
        this.player = Objects.requireNonNull(player, "player");
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    /** The loader's player handle now, or null between a disconnect and this peer being dropped. */
    @Nullable
    public Object player() {
        return player.get();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CgPeer && id.equals(((CgPeer) other).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "CgPeer[" + name + " " + id + "]";
    }
}
