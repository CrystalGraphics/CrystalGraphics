package com.crystalgraphics.vulkan.shader;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.Version;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * shaderc's output kept on disk by what went into it, so a launch compiles only what no launch before it did. One file
 * per module, named by the SHA-256 of the stage, the compiler's options and version, and the source.
 *
 * <pre>{@code
 * SpirvCache cache = new SpirvCache(dir, "vulkan1.2 relaxed");
 * ByteBuffer words = cache.get(kind, source);    // null: not kept
 * if (words == null) cache.put(kind, source, words = compile(source));
 * }</pre>
 *
 * <ul>
 *   <li>Over {@link #MOST} bytes when opened, the least recently read go until it holds {@link #KEEP}.</li>
 *   <li>A file that is not SPIR-V is a miss, and the compile after it writes it again.</li>
 *   <li>Any I/O failure is a miss or an unkept module, logged once: never an error.</li>
 * </ul>
 */
final class SpirvCache {

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics");
    static final long MOST = 64L << 20, KEEP = 48L << 20;
    private static final int MAGIC = 0x07230203;

    private final Path dir;
    private final String salt;
    private final MessageDigest sha;
    private boolean warned;
    int hits, misses;

    /** @param options what else decides the output: the compiler's settings; the LWJGL version is added */
    SpirvCache(Path dir, String options) {
        this.dir = dir;
        this.salt = options + "|lwjgl " + Version.getVersion() + "|";
        try {
            this.sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        prune();
    }

    /** The module compiled from {@code source} as {@code kind} by a launch before, or null. */
    ByteBuffer get(int kind, String source) {
        Path file = dir.resolve(key(kind, source));
        byte[] bytes;
        try {
            if (!Files.isRegularFile(file)) {
                misses++;
                return null;
            }
            bytes = Files.readAllBytes(file);
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException e) {
            warn("read", e);
            misses++;
            return null;
        }
        ByteBuffer words = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
        words.put(bytes).flip();
        if (bytes.length < 20 || bytes.length % 4 != 0 || words.getInt(0) != MAGIC) {
            misses++;
            return null;
        }
        hits++;
        return words;
    }

    /** Keeps {@code words}, written to a temporary file and moved into place, so a reader never sees half of one. */
    void put(int kind, String source, ByteBuffer words) {
        String key = key(kind, source);
        Path temp = dir.resolve(key + ".tmp" + Long.toHexString(System.nanoTime()));
        byte[] bytes = new byte[words.remaining()];
        words.duplicate().get(bytes);
        try {
            Files.write(temp, bytes);
            Files.move(temp, dir.resolve(key), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            warn("write", e);
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // A stray temporary file is the next prune's.
            }
        }
    }

    private String key(int kind, String source) {
        sha.reset();
        sha.update((salt + kind + "|").getBytes(StandardCharsets.UTF_8));
        byte[] digest = sha.digest(source.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2 + 4);
        for (byte b : digest) hex.append(Character.forDigit(b >> 4 & 15, 16)).append(Character.forDigit(b & 15, 16));
        return hex.append(".spv").toString();
    }

    /** Least recently read first, down to {@link #KEEP}, once the folder holds more than {@link #MOST}. */
    private void prune() {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> listed = Files.list(dir)) {
            listed.filter(Files::isRegularFile).forEach(files::add);
        } catch (IOException e) {
            warn("list", e);
            return;
        }
        long total = 0;
        long[] sizes = new long[files.size()], times = new long[files.size()];
        for (int i = 0; i < files.size(); i++) {
            try {
                sizes[i] = Files.size(files.get(i));
                times[i] = Files.getLastModifiedTime(files.get(i)).toMillis();
            } catch (IOException e) {
                sizes[i] = 0;
            }
            total += sizes[i];
        }
        if (total <= MOST) return;
        Integer[] order = new Integer[files.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Long.compare(times[a], times[b]));
        for (int i = 0; i < order.length && total > KEEP; i++) {
            try {
                Files.deleteIfExists(files.get(order[i]));
                total -= sizes[order[i]];
            } catch (IOException e) {
                warn("prune", e);
            }
        }
    }

    private void warn(String what, IOException e) {
        if (warned) return;
        warned = true;
        LOG.warn("SPIR-V cache in {}: could not {} ({}); compiling instead", dir, what, e.toString());
    }
}
