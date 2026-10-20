package com.crystalgraphics.tag;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * One connection's agreement on tag indices: what a tag is called on the wire, and which local tag a wire index
 * names. The server's link is its own table; a client's is empty until the server's table arrives, then maps each of
 * the server's tags to the client's of the same name.
 *
 * <pre>{@code
 * // server: one link, over the registry as it stands at login
 * CgTagLink toClient = CgTagLink.server(registry);       // send toClient.table(), then write indices with toWire
 * out.writeVarInt(toClient.toWire(tag));
 *
 * // client: on the table message
 * CgTagLink fromServer = CgTagLink.client(registry);
 * fromServer.agree(table);
 * CgGameplayTag tag = fromServer.fromWire(in.readVarInt());   // null: the server has a tag this client lacks
 * }</pre>
 *
 * <p>Easy to get wrong: an index read before {@link #agree} <em>throws</em>; it is never read against the local
 * order, which would name some other tag. {@link #invalid()} is the size, so it needs {@link #bits()} wide and is
 * never a tag (Unreal's {@code InvalidTagNetIndex}). A tag registered after the table was agreed is not on it:
 * {@link #toWire} throws for it.</p>
 */
public final class CgTagLink {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    private final CgTagRegistry registry;
    @Nullable
    private CgTagTable table;
    /** Server index to local tag; null where this registry lacks the name. */
    private CgGameplayTag[] local;
    /** Local index to server index; -1 where the server lacks the tag. */
    private int[] remote;
    private final Set<String> reported = new HashSet<>();

    private CgTagLink(CgTagRegistry registry) {
        this.registry = registry;
    }

    /** The server's end: its registry's table as it stands now, indices that are its own. */
    public static CgTagLink server(CgTagRegistry registry) {
        return server(registry, registry.table());
    }

    /** The server's end over a table already taken, so the one sent and the one written with are the same. */
    static CgTagLink server(CgTagRegistry registry, CgTagTable table) {
        CgTagLink link = new CgTagLink(registry);
        link.agree(table);
        return link;
    }

    /** A client's end, with nothing agreed. */
    public static CgTagLink client(CgTagRegistry registry) {
        return new CgTagLink(registry);
    }

    /**
     * Takes the server's table as the wire order. Tags the server has and this registry lacks are counted and named
     * once in the log; their indices read as null.
     */
    public synchronized void agree(CgTagTable serverTable) {
        CgGameplayTag[] byServer = new CgGameplayTag[serverTable.size()];
        int[] byLocal = new int[registry.size() + 1];
        Arrays.fill(byLocal, -1);
        int missing = 0;
        for (int i = 0; i < byServer.length; i++) {
            CgGameplayTag tag = registry.find(serverTable.name(i));
            byServer[i] = tag;
            if (tag == null) {
                missing++;
                if (reported.add(serverTable.name(i)) && reported.size() <= 16) {
                    LOGGER.warn("[cg-tags] the server has tag '{}', which this client lacks", serverTable.name(i));
                }
            } else {
                if (tag.index() >= byLocal.length) byLocal = Arrays.copyOf(byLocal, tag.index() + 1);
                byLocal[tag.index()] = i;
            }
        }
        if (missing > 0) LOGGER.warn("[cg-tags] {} of the server's {} tags are unknown here", missing, byServer.length);
        this.local = byServer;
        this.remote = byLocal;
        this.table = serverTable;
    }

    /** Whether a table has been agreed. */
    public synchronized boolean agreed() {
        return table != null;
    }

    /** The agreed table: the one a server sends. */
    public synchronized CgTagTable table() {
        return agreedTable();
    }

    /** The index meaning "no tag". */
    public synchronized int invalid() {
        return agreedTable().size();
    }

    /** Bits that hold every index and {@link #invalid()}. */
    public synchronized int bits() {
        return 32 - Integer.numberOfLeadingZeros(agreedTable().size());
    }

    /**
     * The wire index of {@code tag}.
     *
     * @throws CgTagException if nothing is agreed, or the other end lacks the tag
     */
    public synchronized int toWire(CgGameplayTag tag) {
        agreedTable();
        int i = tag.index() < remote.length ? remote[tag.index()] : -1;
        if (i < 0) throw new CgTagException("tag '" + tag + "' is not in the agreed table (" + table + ")");
        return i;
    }

    /**
     * The local tag a wire index names, or null for {@link #invalid()} and for a tag this registry lacks.
     *
     * @throws CgTagException before a table is agreed, or for an index past {@link #invalid()}
     */
    @Nullable
    public synchronized CgGameplayTag fromWire(int index) {
        CgTagTable agreed = agreedTable();
        if (index == agreed.size()) return null;
        if (index < 0 || index > agreed.size()) {
            throw new CgTagException("tag index " + index + " is out of range for " + agreed + ": out of sync");
        }
        return local[index];
    }

    private CgTagTable agreedTable() {
        if (table == null) throw new CgTagException("a tag index arrived before the tag table was agreed");
        return table;
    }
}
