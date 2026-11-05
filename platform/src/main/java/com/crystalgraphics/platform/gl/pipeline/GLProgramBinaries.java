package com.crystalgraphics.platform.gl.pipeline;

import com.crystalgraphics.platform.gl.binding.GLBindingPoints;
import com.crystalgraphics.platform.gl.driver.CgGLDriver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
import java.util.concurrent.Executor;
import java.util.stream.Stream;

import static com.crystalgraphics.platform.gl.driver.GLC.*;

/**
 * Linked programs kept across launches as the driver's own binaries (Unreal's {@code FOpenGLProgramBinaryCache}): a
 * program linked once loads in about a millisecond afterwards, with neither SPIRV-Cross nor the driver's compiler run.
 * Keyed by its modules' SPIR-V and the binding points it was translated under. A binary is its format word, then the
 * driver's bytes, as Unreal stores it (`OpenGLShaders.cpp:1431`).
 *
 * <pre>{@code
 * device.programCache(cacheRoot.resolve("glprogram"), job -> CgJob.run(job));   // before the first pipeline
 * }</pre>
 *
 * <ul>
 *   <li>Under {@code <root>/<driver>/}, {@code <driver>} a hash of the renderer, vendor and version: another driver's
 *   folder is deleted when this one is first made.</li>
 *   <li>Off where the driver lists no binary formats.</li>
 *   <li>A binary the driver refuses is forgotten, and the program compiles as if there had been none.</li>
 * </ul>
 */
public final class GLProgramBinaries {

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics");

    private final Path dir;
    private final Executor writer;

    /** @param writer runs the file writes, off the render thread */
    public GLProgramBinaries(CgGLDriver gl, Path root, Executor writer) {
        this.writer = writer;
        Path at = null;
        if (gl.glGetInteger(GL_NUM_PROGRAM_BINARY_FORMATS) > 0) {
            String driver = hex(sha(gl.glGetString(GL_VENDOR), gl.glGetString(GL_RENDERER), gl.glGetString(GL_VERSION)))
                    .substring(0, 16);
            try {
                at = Files.createDirectories(root.resolve(driver));
                writer.execute(() -> deleteOthers(root, driver));
            } catch (IOException e) {
                LOG.warn("No GL program binaries: {} cannot be made ({})", root.resolve(driver), e.toString());
            }
        }
        dir = at;
    }

    /** Whether binaries are kept. */
    public boolean on() { return dir != null; }

    /** The key of a program linked from {@code modules} under {@code points}. */
    public static String key(GLBindingPoints points, GLShaderModule... modules) {
        MessageDigest sha = sha();
        sha.update(points.toString().getBytes(StandardCharsets.UTF_8));
        for (GLShaderModule m : modules) {
            sha.update((byte) m.stage().ordinal());
            sha.update(m.spirv.duplicate());
        }
        return hex(sha);
    }

    /** {@code key}'s kept binary, or null; any thread. */
    public byte[] read(String key) {
        if (dir == null) return null;
        Path file = dir.resolve(key);
        try {
            byte[] bytes = Files.readAllBytes(file);
            return bytes.length > 4 ? bytes : null;
        } catch (NoSuchFileException absent) {
            return null;
        } catch (IOException e) {
            LOG.warn("GL program binary {} unreadable: {}", file, e.toString());
            return null;
        }
    }

    /** Keeps {@code binary} under {@code key}, written by the writer. */
    public void write(String key, byte[] binary) {
        Path at = dir;
        if (at != null) writer.execute(() -> store(at, key, binary));
    }

    /** Deletes {@code key}'s binary: the driver refused it. */
    public void forget(String key) {
        if (dir == null) return;
        try {
            Files.deleteIfExists(dir.resolve(key));
        } catch (IOException ignored) {
            // overwritten by the next write
        }
    }

    /** {@code program}'s binary, its format word first; the program was linked retrievable. */
    public static byte[] binary(CgGLDriver gl, int program) {
        int length = gl.glGetProgrami(program, GL_PROGRAM_BINARY_LENGTH);
        ByteBuffer out = ByteBuffer.allocateDirect(Math.max(length, 0));
        int format = gl.glGetProgramBinary(program, out);
        byte[] bytes = new byte[4 + length];
        ByteBuffer.wrap(bytes, 0, 4).putInt(format);
        out.get(bytes, 4, length);
        return bytes;
    }

    /** Links {@code program} from {@code binary}: whether the driver took it. */
    public static boolean load(CgGLDriver gl, int program, byte[] binary) {
        ByteBuffer driver = ByteBuffer.allocateDirect(binary.length - 4).order(ByteOrder.nativeOrder());
        driver.put(binary, 4, binary.length - 4).flip();
        gl.glProgramBinary(program, ByteBuffer.wrap(binary, 0, 4).getInt(), driver);
        return gl.glGetProgrami(program, GL_LINK_STATUS) != 0;
    }

    private static void store(Path at, String key, byte[] bytes) {
        try {
            Path temp = Files.createTempFile(at, key, ".tmp");
            Files.write(temp, bytes);
            Files.move(temp, at.resolve(key), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.warn("GL program binary {} not kept: {}", key, e.toString());
        }
    }

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
            LOG.debug("Old GL program binaries not cleared: {}", e.toString());
        }
    }

    private static MessageDigest sha(String... parts) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            for (String part : parts) {
                sha.update(String.valueOf(part).getBytes(StandardCharsets.UTF_8));
                sha.update((byte) 0);
            }
            return sha;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(MessageDigest sha) {
        StringBuilder s = new StringBuilder(40);
        for (byte b : sha.digest()) s.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return s.toString();
    }
}
