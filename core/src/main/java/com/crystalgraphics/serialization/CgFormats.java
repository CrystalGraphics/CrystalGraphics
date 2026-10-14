package com.crystalgraphics.serialization;

import com.crystalgraphics.platform.CgPlatform;

import javax.annotation.Nullable;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Where a codec's value lives when it is not on the wire: a file of the mod's own, in CrystalGraphics' binary format,
 * or Minecraft's tags where Minecraft owns the container. A mod saves with the codecs it sends with, and needs no NBT
 * of its own.
 *
 * <pre>{@code
 * CgFormats.write(file, PlayerData.CODEC, data);                  // a file of the mod's own
 * PlayerData data = CgFormats.read(file, PlayerData.CODEC);       // null when there is no file yet
 * }</pre>
 *
 * Inside an entity's, block entity's or item's saved data, which only takes Minecraft's tags:
 *
 * <pre>{@code
 * tag.put("character", CgFormats.toNbt(Character.CODEC, character));              // saving
 * Character character = CgFormats.fromNbt(Character.CODEC, tag.get("character")); // loading
 * }</pre>
 *
 * <ul>
 *   <li>A file is compressed, and written whole beside itself then moved over the old one: a crash mid-save leaves the
 *       last save, never half of one.</li>
 *   <li>Files and NBT take any codec; NBT's own rules apply there: map keys are text, a list holds one kind of
 *       value, a boolean is a byte.</li>
 *   <li>{@link #toNbt}, {@link #fromNbt} and {@link #nbt} need a host: in a test or the harness they throw, naming the
 *       {@link CgHostFormats} slot.</li>
 * </ul>
 */
public final class CgFormats {

    private CgFormats() {
    }

    /** Writes {@code value} to {@code file} through {@code codec}, making its folders, replacing it whole. */
    public static <V> void write(Path file, CgCodec<V> codec, V value) throws IOException {
        Object tree = codec.encode(CgPlainOps.INSTANCE, value);
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
            CgBinaryFormat.writeTo(out, tree);
        }
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * The value {@link #write} left in {@code file}; null when there is no file.
     *
     * @throws CgCodecException where the file holds what {@code codec} cannot read
     */
    @Nullable
    public static <V> V read(Path file, CgCodec<V> codec) throws IOException {
        if (!Files.exists(file)) return null;
        return codec.decode(CgPlainOps.INSTANCE, tree(file));
    }

    /**
     * What {@link #write} left in {@code file}, decoded into {@code target} ({@link CgCodec#decode}): answers what
     * now holds the data, {@code target} itself for a codec that updates in place. No file leaves {@code target} as
     * it is.
     *
     * <pre>{@code
     * CgFormats.read(file, Settings.CODEC, settings);   // every screen holding settings sees the reload
     * }</pre>
     *
     * The file is read and parsed whole first, so a damaged one throws before {@code target} is touched.
     */
    public static <V> V read(Path file, CgCodec<V> codec, V target) throws IOException {
        if (!Files.exists(file)) return target;
        return codec.decode(CgPlainOps.INSTANCE, tree(file), target);
    }

    private static Object tree(Path file) throws IOException {
        // whole, then decoded: the format checks each length against what remains, which a gzip stream cannot say
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            byte[] chunk = new byte[8192];
            for (int n; (n = in.read(chunk)) > 0; ) bytes.write(chunk, 0, n);
        }
        return CgBinaryFormat.decode(bytes.toByteArray());
    }

    /**
     * {@code value} as the host's tags: a compound for a map, a string tag for a string, and so on. Typed as the caller
     * takes it ({@code Tag}, {@code CompoundTag}, {@code NBTBase}): asking for a kind it did not make is a
     * {@code ClassCastException} there.
     */
    @SuppressWarnings("unchecked")
    public static <V, N> N toNbt(CgCodec<V> codec, V value) {
        return (N) codec.encode(nbt(), value);
    }

    /** What {@link #toNbt} wrote, back through {@code codec}. */
    public static <V> V fromNbt(CgCodec<V> codec, Object tag) {
        return codec.decode(nbt(), tag);
    }

    /** What {@link #toNbt} wrote, decoded into {@code target}: an entity's live data refreshed from its tag. */
    public static <V> V fromNbt(CgCodec<V> codec, Object tag, V target) {
        return codec.decode(nbt(), tag, target);
    }

    /** The host's tags as a codec format, for a codec read or written by hand. */
    public static CgDynamicOps<Object> nbt() {
        CgHostFormats tags = CgPlatform.get(CgHostFormats.SERVICE);
        if (!tags.present()) {
            throw new IllegalStateException("no host tags here: crystalgraphics:host_formats has none (a test, the"
                    + " harness)");
        }
        return new HostTagOps(tags);
    }
}
