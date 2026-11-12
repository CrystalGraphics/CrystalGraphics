package com.crystalgraphics.vulkan.shader;

import com.crystalgraphics.platform.io.CgAtomicFile;
import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.shader.CgGlslCompiler;
import com.crystalgraphics.platform.service.CgCacheDirectory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.Version;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A compile as written kept whole on disk by what went into it: its modules as patched, and what reflecting them found
 * (the sets, the heap, the push constants, the outputs). A launch after the first then runs neither shaderc nor
 * SPIRV-Cross for it, as Unreal's shader map keeps a shader's parameter map beside its bytecode. One {@code .cgx} file a
 * compile, beside {@link SpirvCache}'s modules.
 *
 * <pre>{@code
 * ExplicitCache results = new ExplicitCache(dir, "vulkan 1.2, ...");
 * CgGlslCompiler.Explicit p = results.program(vs, fs, attribs, label);   // null: not kept
 * if (p == null) results.put(vs, fs, attribs, label, p = compile(...));
 * }</pre>
 *
 * <ul>
 *   <li>Keyed by the sources, the label, the attribute locations, the options and the LWJGL version, so any of them
 *       changing is a miss.</li>
 *   <li>A file that does not read back whole is a miss, and the compile after it writes it again.</li>
 *   <li>Any thread, many at once; any I/O failure is a miss or an unkept result, logged once.</li>
 * </ul>
 */
final class ExplicitCache {

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics");
    /** "CGX" and the format's version: a file of another version is a miss. */
    private static final int MAGIC = 0x43475802;
    private static final byte PROGRAM = 0, KERNEL = 1;
    private static final CgBindingLayout.Type[] TYPES = CgBindingLayout.Type.values();

    private final Path dir;
    private final String salt;
    private volatile boolean warned;

    /** @param options what else decides the result: the compiler's settings; the LWJGL version is added */
    ExplicitCache(Path dir, String options) {
        this.dir = dir;
        this.salt = options + "|lwjgl " + Version.getVersion() + "|";
    }

    /** The program compiled from these by a launch before, or null. */
    CgGlslCompiler.Explicit program(String vertex, String fragment, Map<String, Integer> attribs, String label) {
        try (DataInputStream in = read(key(PROGRAM, label, attribs, vertex, fragment))) {
            if (in == null || in.readByte() != PROGRAM) return null;
            ByteBuffer vs = module(in), fs = module(in);
            List<List<CgBindingLayout.Slot>> sets = sets(in);
            return new CgGlslCompiler.Explicit(vs, fs, sets, in.readBoolean(), in.readInt(), in.readInt());
        } catch (IOException | RuntimeException e) {
            return null;   // short, damaged or another format: compiled again, and written over
        }
    }

    /** Keeps {@code made}, compiled from these. */
    void put(String vertex, String fragment, Map<String, Integer> attribs, String label, CgGlslCompiler.Explicit made) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeByte(PROGRAM);
            module(out, made.vertex());
            module(out, made.fragment());
            sets(out, made.sets());
            out.writeBoolean(made.heap());
            out.writeInt(made.pushConstantBytes());
            out.writeInt(made.colorOutputs());
        } catch (IOException e) {
            throw new IllegalStateException(e);   // a byte array does not fail
        }
        write(key(PROGRAM, label, attribs, vertex, fragment), bytes.toByteArray());
    }

    /** The kernel compiled from {@code glsl} by a launch before, or null. */
    CgGlslCompiler.ExplicitCompute kernel(String glsl, String label) {
        try (DataInputStream in = read(key(KERNEL, label, null, glsl))) {
            if (in == null || in.readByte() != KERNEL) return null;
            ByteBuffer spirv = module(in);
            int[] size = {in.readInt(), in.readInt(), in.readInt()};
            List<List<CgBindingLayout.Slot>> sets = sets(in);
            return new CgGlslCompiler.ExplicitCompute(spirv, size, sets, in.readBoolean(), in.readInt());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Keeps {@code made}, compiled from {@code glsl}. */
    void put(String glsl, String label, CgGlslCompiler.ExplicitCompute made) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeByte(KERNEL);
            module(out, made.spirv());
            for (int i = 0; i < 3; i++) out.writeInt(made.localSize()[i]);
            sets(out, made.sets());
            out.writeBoolean(made.heap());
            out.writeInt(made.pushConstantBytes());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        write(key(KERNEL, label, null, glsl), bytes.toByteArray());
    }

    private DataInputStream read(String key) {
        Path file = dir.resolve(key);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException absent) {
            return null;
        } catch (IOException e) {
            warn("read", e);
            return null;
        }
        CgCacheDirectory.used(file);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        try {
            return in.readInt() == MAGIC ? in : null;
        } catch (IOException e) {
            return null;
        }
    }

    private void write(String key, byte[] bytes) {
        try {
            CgAtomicFile.write(dir.resolve(key), bytes);
        } catch (IOException e) {
            warn("write", e);
        }
    }

    private static void module(DataOutputStream out, ByteBuffer spirv) throws IOException {
        ByteBuffer words = spirv.duplicate();
        words.position(0);
        byte[] bytes = new byte[words.remaining()];
        words.get(bytes);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static ByteBuffer module(DataInputStream in) throws IOException {
        byte[] bytes = new byte[in.readInt()];
        in.readFully(bytes);
        return ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder()).put(bytes).flip();
    }

    private static void sets(DataOutputStream out, List<List<CgBindingLayout.Slot>> sets) throws IOException {
        out.writeInt(sets.size());
        for (List<CgBindingLayout.Slot> set : sets) {
            out.writeInt(set.size());
            for (CgBindingLayout.Slot slot : set) {
                out.writeInt(slot.binding());
                out.writeByte(slot.type().ordinal());
                out.writeBoolean(slot.dynamic());
            }
        }
    }

    private static List<List<CgBindingLayout.Slot>> sets(DataInputStream in) throws IOException {
        int n = in.readInt();
        List<List<CgBindingLayout.Slot>> sets = new ArrayList<>(n);
        for (int s = 0; s < n; s++) {
            int count = in.readInt();
            List<CgBindingLayout.Slot> set = new ArrayList<>(count);
            for (int i = 0; i < count; i++) set.add(new CgBindingLayout.Slot(in.readInt(), TYPES[in.readByte()], in.readBoolean()));
            sets.add(List.copyOf(set));
        }
        return List.copyOf(sets);
    }

    /** A digest of its own each call: compiles run on many threads at once. */
    private String key(byte kind, String label, Map<String, Integer> attribs, String... sources) {
        MessageDigest sha = sha256();
        sha.update((salt + kind + "|" + label + "|" + (attribs == null ? "" : new TreeMap<>(attribs).toString()) + "|")
                .getBytes(StandardCharsets.UTF_8));
        for (String source : sources) {
            sha.update(source.getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
        }
        byte[] digest = sha.digest();
        StringBuilder hex = new StringBuilder(digest.length * 2 + 4);
        for (byte b : digest) hex.append(Character.forDigit(b >> 4 & 15, 16)).append(Character.forDigit(b & 15, 16));
        return hex.append(".cgx").toString();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void warn(String what, IOException e) {
        if (warned) return;
        warned = true;
        LOG.warn("Shader result cache in {}: could not {} ({}); compiling instead", dir, what, e.toString());
    }
}
