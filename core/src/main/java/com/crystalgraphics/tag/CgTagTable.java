package com.crystalgraphics.tag;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * A registry's tags in net-index order, with a hash of the order. The server sends its table to each client at
 * login, and {@link CgTagLink} maps the client's tags onto it.
 *
 * <p>Mirrors the net index of Unreal's {@code UGameplayTagsManager::ConstructNetIndex}: tags sorted by name, so
 * identical tag sets agree without exchanging anything, and a hash over the lowercase names in order
 * ({@code NetworkGameplayTagNodeIndexHash}) that tells at a glance whether they do.</p>
 *
 * <pre>{@code
 * // server, at login
 * CgTagTable table = CgTagRegistry.global().table();
 * TABLE.send(CgAudience.player(id), table);              // CgMessage<CgTagTable> with CgTagTable.CODEC
 *
 * // client
 * table.hash() == CgTagRegistry.global().table().hash(); // same tag sets: indices already agree
 * }</pre>
 */
public final class CgTagTable {

    /** Names and hash; decoding fails when the hash is not the names'. */
    public static final CgCodec<CgTagTable> CODEC = CgCodecs.record(
            CgCodecs.field("n", CgCodecs.listOf(CgCodecs.STRING), CgTagTable::names),
            CgCodecs.field("h", CgCodecs.INT, CgTagTable::hash),
            CgTagTable::decode);

    private final List<String> names;
    private final int hash;

    CgTagTable(List<String> names) {
        this.names = Collections.unmodifiableList(new ArrayList<>(names));
        this.hash = hashOf(this.names);
    }

    private static CgTagTable decode(List<String> names, int hash) {
        CgTagTable table = new CgTagTable(names);
        if (table.hash != hash) {
            throw new CgTagException("tag table hash " + Integer.toHexString(hash) + " is not its names' "
                    + Integer.toHexString(table.hash));
        }
        return table;
    }

    /** The tags' names in index order. */
    public List<String> names() {
        return names;
    }

    /** The name at {@code index}. */
    public String name(int index) {
        return names.get(index);
    }

    /** How many tags; also the index meaning "no tag". */
    public int size() {
        return names.size();
    }

    /** A CRC-32 over the lowercase names in order, each followed by a newline. */
    public int hash() {
        return hash;
    }

    private static int hashOf(List<String> names) {
        CRC32 crc = new CRC32();
        for (String name : names) {
            crc.update(name.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            crc.update('\n');
        }
        return (int) crc.getValue();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CgTagTable && ((CgTagTable) other).names.equals(names);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return "tags[" + names.size() + "] #" + Integer.toHexString(hash);
    }
}
