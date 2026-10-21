package com.crystalgraphics.gl.shader;

import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.service.CgCacheDirectory;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.variable.CgVar;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.stream.Stream;

/**
 * Linked GL programs kept across launches as the driver's own binaries, so a program linked once loads in about a
 * millisecond afterwards instead of compiling again: Unreal's OpenGL program binary cache. {@link
 * com.crystalgraphics.api.shader.CgShaderProgram} uses it for every program it links; nothing else needs to.
 *
 * <pre>{@code
 * String key = CgProgramBinaryCache.key(vertexSource, fragmentSource, "cg_Position", "cg_Normal");
 * if (key == null || !CgProgramBinaryCache.load(program, key)) {
 *     CgProgramBinaryCache.retrievable(program, key);      // before the link
 *     ... compile, attach, link ...
 *     if (linked) CgProgramBinaryCache.store(program, key);
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Render thread only. The file is written on a worker; a binary read is on the calling thread.</li>
 *   <li>Kept under {@code crystalgraphics/cache/glprogram/<driver>/}, where {@code <driver>} hashes the renderer,
 *       vendor and version strings: another driver's folder is deleted at the first use.</li>
 *   <li>{@link #key} is null where nothing is kept: the driver lists no binary formats (macOS, the tracked backend),
 *       {@code -Dcrystalgraphics.shader.binaryCache=false}, or {@code -Dcrystalgraphics.cache=false}.</li>
 *   <li>A binary the driver refuses is deleted, and the program compiles as if there had been none.</li>
 * </ul>
 */
public final class CgProgramBinaryCache {

    public static final CgVar.Bool ENABLED = CgVar.bool("crystalgraphics.shader.binaryCache", true, CgVar.DEV,
            "Keep linked GL programs' binaries across launches where the driver gives them; false compiles every"
                    + " program from source.");

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics");
    private static final int LOAD = CgTrace.name("shader.binaryLoad"), HITS = CgTrace.name("shader.binary-hits"),
            MISSES = CgTrace.name("shader.binary-misses");

    /** The capabilities {@link #dir} was found for: a new context may be another driver. */
    @Nullable private static CgCapabilities probedFor;
    @Nullable private static Path dir;

    private CgProgramBinaryCache() {}

    /** The key of a program linked from {@code parts} (its sources and attribute names), or null where none is kept. */
    @Nullable
    public static String key(String... parts) {
        if (dir() == null) return null;
        MessageDigest sha = sha1();
        for (String part : parts) {
            sha.update(part.getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
        }
        return hex(sha);
    }

    /**
     * Loads {@code key}'s binary into {@code program}: true when the program is linked from it, false when there is
     * none or the driver refused it.
     */
    public static boolean load(int program, String key) {
        Path file = dir == null ? null : dir.resolve(key);
        if (file == null) return false;
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException absent) {
            CgTrace.add(CgChannels.GL, MISSES, 1);
            return false;
        } catch (IOException e) {
            LOG.warn("Program binary {} unreadable: {}", file, e.toString());
            return false;
        }
        if (bytes.length <= 4) return refused(file);
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, LOAD)) {
            ByteBuffer binary = ByteBuffer.allocateDirect(bytes.length - 4).order(ByteOrder.nativeOrder());
            binary.put(bytes, 4, bytes.length - 4).flip();
            int format = ByteBuffer.wrap(bytes, 0, 4).getInt();
            CgGL.glProgramBinary(program, format, binary);
            if (CgGL.glGetProgrami(program, CgGL.GL_LINK_STATUS) != CgGL.GL_TRUE) return refused(file);
        }
        CgTrace.add(CgChannels.GL, HITS, 1);
        return true;
    }

    /** Asks the driver to keep {@code program}'s binary: before its link, where {@code key} is not null. */
    public static void retrievable(int program, @Nullable String key) {
        if (key != null) CgGL.glProgramParameteri(program, CgGL.GL_PROGRAM_BINARY_RETRIEVABLE_HINT, CgGL.GL_TRUE);
    }

    /** Keeps a linked {@code program}'s binary under {@code key}; the file is written on a worker. */
    public static void store(int program, @Nullable String key) {
        Path at = dir;
        if (key == null || at == null) return;
        int length = CgGL.glGetProgrami(program, CgGL.GL_PROGRAM_BINARY_LENGTH);
        if (length <= 0) return;
        ByteBuffer binary = ByteBuffer.allocateDirect(length);
        int format = CgGL.glGetProgramBinary(program, binary);
        byte[] bytes = new byte[4 + length];
        ByteBuffer.wrap(bytes, 0, 4).putInt(format);
        binary.get(bytes, 4, length);
        CgJob.run(() -> write(at, key, bytes));
    }

    private static void write(Path at, String key, byte[] bytes) {
        try {
            Path temp = Files.createTempFile(at, key, ".tmp");
            Files.write(temp, bytes);
            Files.move(temp, at.resolve(key), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.warn("Program binary {} not kept: {}", key, e.toString());
        }
    }

    private static boolean refused(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Overwritten by the next store.
        }
        CgTrace.add(CgChannels.GL, MISSES, 1);
        return false;
    }

    /** This context's folder, found once per context; null where nothing is kept. */
    @Nullable
    private static Path dir() {
        CgCapabilities caps = CgCapabilities.detect();
        if (caps == probedFor) return dir;
        probedFor = caps;
        dir = null;
        if (!ENABLED.get() || caps.getProgramBinaryFormats() <= 0) return null;
        Path root = CgCacheDirectory.of("glprogram");
        if (root == null) return null;
        String driver = hex(sha1(CgGL.glGetString(CgGL.GL_RENDERER), CgGL.glGetString(CgGL.GL_VENDOR),
                CgGL.glGetString(CgGL.GL_VERSION))).substring(0, 16);
        try {
            dir = Files.createDirectories(root.resolve(driver));
        } catch (IOException e) {
            LOG.warn("No program binary cache: {} cannot be made ({})", root.resolve(driver), e.toString());
            return null;
        }
        CgJob.run(() -> deleteOthers(root, driver));
        return dir;
    }

    /** Every other driver's folder under {@code root}: their binaries never load on this one. */
    private static void deleteOthers(Path root, String driver) {
        try (DirectoryStream<Path> folders = Files.newDirectoryStream(root)) {
            for (Path folder : folders) {
                if (folder.getFileName().toString().equals(driver) || !Files.isDirectory(folder)) continue;
                try (Stream<Path> files = Files.list(folder)) {
                    for (Path file : (Iterable<Path>) files::iterator) Files.deleteIfExists(file);
                }
                Files.deleteIfExists(folder);
            }
        } catch (IOException e) {
            LOG.debug("Old program binaries not cleared: {}", e.toString());
        }
    }

    private static MessageDigest sha1(String... parts) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            for (String part : parts) sha.update(String.valueOf(part).getBytes(StandardCharsets.UTF_8));
            return sha;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) s.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return s.toString();
    }

    private static String hex(MessageDigest sha) {
        return hex(sha.digest());
    }
}
