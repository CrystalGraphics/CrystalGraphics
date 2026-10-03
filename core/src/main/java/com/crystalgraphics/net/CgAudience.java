package com.crystalgraphics.net;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Which players a server-side send reaches. Each answers profile ids; {@link CgMessage#send} turns them into
 * connections, so a player without one is passed over.
 *
 * <pre>{@code
 * PLAY.send(CgAudience.all(), play);
 * PLAY.send(CgAudience.player(playerUuid), play);
 * }</pre>
 */
@FunctionalInterface
public interface CgAudience {

    /** Hands each player's profile id to {@code out}, once. */
    void forEach(Consumer<UUID> out);

    /** One player. */
    static CgAudience player(UUID id) {
        Objects.requireNonNull(id, "id");
        return out -> out.accept(id);
    }

    /** Every player with a connection. */
    static CgAudience all() {
        return CgNetwork::forEachPlayer;
    }
}
