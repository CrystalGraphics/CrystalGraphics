package com.crystalgraphics.vulkan.shader;

import com.crystalgraphics.platform.service.CgCacheDirectory;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

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
        CgCacheDirectory.prune(dir, MOST, KEEP);
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
            CgCacheDirectory.used(file);
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

    private void warn(String what, IOException e) {
        if (warned) return;
        warned = true;
        LOG.warn("SPIR-V cache in {}: could not {} ({}); compiling instead", dir, what, e.toString());
    }
}
