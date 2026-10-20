package com.crystalgraphics.asset.registry;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.tree.CgAssetReferences;
import com.crystalgraphics.object.CgPropertyPath;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgTypes;
import com.crystalgraphics.object.CgValueType;

/**
 * What a scan read from each file, kept across runs so an unchanged file is not read again: Unreal's
 * {@code FDiskCachedAssetData}. A record holds while its file's modification time and kind are the same.
 */
final class CgAssetRegistryCache {

    private static final int VERSION = 1;

    /** One file's gathered data. */
    record Record(long modified, String kind, @Nullable String redirect, List<CgAssetReferences.Reference> references) {
    }

    private final Map<String, Record> records = new ConcurrentHashMap<>();
    /** The records this run found or made: what {@link #save} writes, so a deleted file's record is dropped. */
    private final Map<String, Record> seen = new ConcurrentHashMap<>();

    /** The record for the file at {@code key}, if it still holds: the same modification time and kind. */
    @Nullable
    Record find(String key, long modified, CgType kind) {
        Record r = records.get(key);
        if (r == null || r.modified != modified || !r.kind.equals(kind.id())) return null;
        seen.put(key, r);
        return r;
    }

    void put(String key, Record r) {
        records.put(key, r);
        seen.put(key, r);
    }

    /** Reads a cache written by {@link #save}; an absent, older or unreadable file reads as empty. */
    static CgAssetRegistryCache load(@Nullable Path file) {
        CgAssetRegistryCache cache = new CgAssetRegistryCache();
        if (file == null || !Files.isRegularFile(file)) return cache;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != VERSION) return cache;
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                String key = in.readUTF();
                long modified = in.readLong();
                String kind = in.readUTF();
                String redirect = in.readBoolean() ? in.readUTF() : null;
                int refs = in.readInt();
                List<CgAssetReferences.Reference> list = new ArrayList<>(refs);
                for (int j = 0; j < refs; j++) {
                    CgType refKind = CgTypes.get(in.readUTF());
                    String id = in.readUTF();
                    CgValueType.Strength strength = in.readBoolean() ? CgValueType.Strength.HARD : CgValueType.Strength.SOFT;
                    CgPropertyPath at = CgPropertyPath.parse(in.readUTF());
                    if (refKind != null) list.add(new CgAssetReferences.Reference(refKind, id, strength, at));
                }
                cache.records.put(key, new Record(modified, kind, redirect, Collections.unmodifiableList(list)));
            }
        } catch (IOException | RuntimeException unreadable) {
            cache.records.clear();   // rebuilt by the next scan
        }
        return cache;
    }

    /** Writes the records this run found or made, replacing {@code file}. */
    void save(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        List<Map.Entry<String, Record>> written = new ArrayList<>(seen.entrySet());
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.writeInt(VERSION);
            out.writeInt(written.size());
            for (Map.Entry<String, Record> e : written) {
                Record r = e.getValue();
                out.writeUTF(e.getKey());
                out.writeLong(r.modified);
                out.writeUTF(r.kind);
                out.writeBoolean(r.redirect != null);
                if (r.redirect != null) out.writeUTF(r.redirect);
                out.writeInt(r.references.size());
                for (CgAssetReferences.Reference ref : r.references) {
                    out.writeUTF(ref.kind().id());
                    out.writeUTF(ref.id());
                    out.writeBoolean(ref.strength() == CgValueType.Strength.HARD);
                    out.writeUTF(ref.at().toString());
                }
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
