package com.crystalgraphics.asset.registry;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** Bump with any change to what a scan reads from a file (references, redirects, searchable values, tags) or to its keys. */
    private static final int VERSION = 5;

    /** One file's gathered data: its own asset's, then each child it writes inline. */
    record Record(long modified, String kind, @Nullable String redirect, List<CgAssetReferences.Reference> references,
                  Map<String, String> searchable, List<String> tags, List<Child> children) {
    }

    /** A child a file writes inline, by its full id. */
    record Child(String id, List<CgAssetReferences.Reference> references, Map<String, String> searchable,
                 List<String> tags) {
    }

    private final Map<String, Record> records = new ConcurrentHashMap<>();
    /** The records this run found or made: what {@link #save} writes for the folders it scanned. */
    private final Map<String, Record> seen = new ConcurrentHashMap<>();
    /** The folders this run scanned, as key prefixes: their files not {@link #seen} are gone. */
    private final Set<String> scanned = ConcurrentHashMap.newKeySet();

    /** Marks {@code root}'s files as listed by this run: a record under it not found again is dropped at the save. */
    void scanning(Path root) {
        scanned.add(root.toAbsolutePath().normalize().toString());
    }

    /** How many files under {@code root} the last scan read: what a scan's progress is measured against. */
    int countUnder(Path root) {
        String prefix = root.toAbsolutePath().normalize().toString();
        int n = 0;
        for (String key : records.keySet()) if (key.startsWith(prefix)) n++;
        return n;
    }

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
        added = true;
    }

    /** Whether this run read a file the cache did not hold. */
    private volatile boolean added;

    /** Whether a save would write anything new: a file read, or one under a scanned folder gone. */
    private boolean changed() {
        if (added) return true;
        for (String key : records.keySet()) {
            if (!seen.containsKey(key) && underScanned(key)) return true;
        }
        return false;
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
                List<CgAssetReferences.Reference> list = readReferences(in);
                Map<String, String> searchable = readSearchable(in);
                List<String> tags = readTags(in);
                int n2 = in.readInt();
                List<Child> children = n2 == 0 ? Collections.emptyList() : new ArrayList<>(n2);
                for (int j = 0; j < n2; j++) children.add(new Child(in.readUTF(), readReferences(in), readSearchable(in), readTags(in)));
                cache.records.put(key, new Record(modified, kind, redirect, list, searchable, tags, children));
            }
        } catch (IOException | RuntimeException unreadable) {
            cache.records.clear();   // rebuilt by the next scan
        }
        return cache;
    }

    /**
     * Writes the cache, replacing {@code file}: for a folder this run scanned, the records it found or made; for any
     * other, those it loaded whose file still exists, so a run scanning one kit keeps another kit's records. Nothing
     * when no scanned file changed: the file already holds it.
     */
    void save(Path file) throws IOException {
        if (!changed() && Files.isRegularFile(file)) return;
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        List<Map.Entry<String, Record>> written = new ArrayList<>(seen.entrySet());
        for (Map.Entry<String, Record> e : records.entrySet()) {
            if (seen.containsKey(e.getKey()) || underScanned(e.getKey())) continue;
            if (Files.isRegularFile(Paths.get(e.getKey()))) written.add(e);
        }
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
                writeReferences(out, r.references);
                writeSearchable(out, r.searchable);
                writeTags(out, r.tags);
                out.writeInt(r.children.size());
                for (Child c : r.children) {
                    out.writeUTF(c.id);
                    writeReferences(out, c.references);
                    writeSearchable(out, c.searchable);
                    writeTags(out, c.tags);
                }
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static List<CgAssetReferences.Reference> readReferences(DataInputStream in) throws IOException {
        int refs = in.readInt();
        List<CgAssetReferences.Reference> list = new ArrayList<>(refs);
        for (int j = 0; j < refs; j++) {
            CgType refKind = CgTypes.get(in.readUTF());
            String id = in.readUTF();
            CgValueType.Strength strength = in.readBoolean() ? CgValueType.Strength.HARD : CgValueType.Strength.SOFT;
            CgPropertyPath at = CgPropertyPath.parse(in.readUTF());
            if (refKind != null) list.add(new CgAssetReferences.Reference(refKind, id, strength, at));
        }
        return Collections.unmodifiableList(list);
    }

    private static Map<String, String> readSearchable(DataInputStream in) throws IOException {
        int values = in.readInt();
        if (values == 0) return Collections.emptyMap();
        Map<String, String> searchable = new LinkedHashMap<>();
        for (int j = 0; j < values; j++) searchable.put(in.readUTF(), in.readUTF());
        return Collections.unmodifiableMap(searchable);
    }

    private static void writeReferences(DataOutputStream out, List<CgAssetReferences.Reference> refs) throws IOException {
        out.writeInt(refs.size());
        for (CgAssetReferences.Reference ref : refs) {
            out.writeUTF(ref.kind().id());
            out.writeUTF(ref.id());
            out.writeBoolean(ref.strength() == CgValueType.Strength.HARD);
            out.writeUTF(ref.at().toString());
        }
    }

    private static void writeSearchable(DataOutputStream out, Map<String, String> searchable) throws IOException {
        out.writeInt(searchable.size());
        for (Map.Entry<String, String> v : searchable.entrySet()) {
            out.writeUTF(v.getKey());
            out.writeUTF(v.getValue());
        }
    }

    private static List<String> readTags(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n == 0) return Collections.emptyList();
        List<String> tags = new ArrayList<>(n);
        for (int j = 0; j < n; j++) tags.add(in.readUTF());
        return Collections.unmodifiableList(tags);
    }

    private static void writeTags(DataOutputStream out, List<String> tags) throws IOException {
        out.writeInt(tags.size());
        for (String t : tags) out.writeUTF(t);
    }

    private boolean underScanned(String key) {
        for (String root : scanned) {
            if (key.startsWith(root) && (key.length() == root.length() || key.charAt(root.length()) == File.separatorChar)) return true;
        }
        return false;
    }
}
