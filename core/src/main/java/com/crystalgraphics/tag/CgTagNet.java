package com.crystalgraphics.tag;

import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.net.object.CgAnchor;
import com.crystalgraphics.net.object.CgNetField;
import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.net.object.CgNetType;

import javax.annotation.Nullable;

/**
 * The tag table's trip to each client: the server holds its {@link CgTagTable} as a networked object every player
 * sees, so a player who connects is sent it and one already connected is sent each change. A client maps its own
 * tags onto it.
 *
 * <pre>{@code
 * // server: write a tag as the index every client has agreed
 * out.writeVarInt(CgTagNet.server().toWire(tag));
 *
 * // client: read one the server sent
 * CgGameplayTag tag = CgTagNet.fromServer().fromWire(in.readVarInt());
 * }</pre>
 *
 * <p>Installed with the network ({@code CgNetwork.install}); nothing to call. Easy to get wrong: a tag registered
 * after the table was last published is not on it, so {@code server().toWire} throws for it until the next tick
 * publishes the new table; and {@link #fromServer} throws until the table has arrived.</p>
 */
public final class CgTagNet {

    private static final CgNetField<CgTagTable> TABLE = CgNetField.of("table", CgTagTable.CODEC);

    private static final CgNetType TYPE = CgNetType.define("crystalgraphics:tags").fields(TABLE).build();

    private static CgNetObject object;
    private static CgTagLink server;
    private static CgTagLink fromServer;

    private CgTagNet() {
    }

    /** Hooks the table's exchange. From {@code CgNetwork.install}, once for each time the network is installed. */
    public static synchronized void install() {
        TYPE.onChange(TABLE, (obj, sent) -> fromServer().agree(sent));
        CgNetwork.onServerTick(CgTagNet::publish);
        CgNetwork.onSessionEnd(() -> {
            synchronized (CgTagNet.class) {
                fromServer = null;
            }
        });
    }

    /**
     * The server's link: the table last published, which every client has been sent or will be at the end of this
     * tick. Null before the first server tick.
     */
    @Nullable
    public static synchronized CgTagLink server() {
        return server;
    }

    /** The client's link to the server's table; reading through it throws until the table arrives. */
    public static synchronized CgTagLink fromServer() {
        if (fromServer == null) fromServer = CgTagLink.client(CgTagRegistry.global());
        return fromServer;
    }

    /** Forgets every link and the object. Tests only; from {@code CgNetwork.resetForTesting}. */
    public static synchronized void resetForTesting() {
        object = null;
        server = null;
        fromServer = null;
    }

    private static synchronized void publish() {
        CgTagRegistry registry = CgTagRegistry.global();
        CgTagTable now = registry.table();
        if (object == null || object.isRemoved()) {
            object = TYPE.spawn(CgAnchor.global()).set(TABLE, now).start();
            server = CgTagLink.server(registry, now);
        } else if (server.table() != now) {
            object.set(TABLE, now);
            server = CgTagLink.server(registry, now);
        }
    }
}
