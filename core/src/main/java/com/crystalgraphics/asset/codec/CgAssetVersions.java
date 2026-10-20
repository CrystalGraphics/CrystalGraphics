package com.crystalgraphics.asset.codec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.CgPlainOps;
import com.crystalgraphics.serialization.CgTreeEdit;
import com.crystalgraphics.serialization.CgVersionedCodec;

/**
 * A kind's {@code _v} steps ({@code spec/core.md} § File): an older file is upgraded on read, one step a version,
 * before anything decodes it; a newer one fails, naming both versions. The steps are {@link CgTreeEdit}s, the same as
 * a mod's own saves take.
 *
 * <pre>{@code
 * // when the system kind goes to _v 2, its file shape having changed:
 * CgAssetVersions.step(CgParticleKinds.SYSTEM, 1, tree -> tree.rename("warmup", "prewarm"));
 *
 * Map<Object, Object> tree = CgAssetVersions.upgrade(CgAssetYaml.read(text), CgParticleKinds.SYSTEM);
 * }</pre>
 *
 * <ul>
 *   <li>A step from version {@code n} turns a version {@code n} file into a version {@code n + 1} one; declare it with
 *       the kind's new {@code version}, before any file of the kind loads.</li>
 *   <li>Opening never resaves: an upgraded tree is written back only by an explicit bulk upgrade.</li>
 * </ul>
 */
public final class CgAssetVersions {

    private static final Map<CgType, TreeMap<Integer, Consumer<CgTreeEdit>>> STEPS = new ConcurrentHashMap<>();

    private CgAssetVersions() {
    }

    /**
     * Declares {@code kind}'s step from {@code version} to {@code version + 1}.
     *
     * @throws IllegalArgumentException if {@code version} is not below the kind's, or already has a step
     */
    public static synchronized void step(CgType kind, int version, Consumer<CgTreeEdit> step) {
        if (version < 1 || version >= kind.version()) {
            throw new IllegalArgumentException(kind.id() + " is at _v " + kind.version() + ": a step migrates from 1 to "
                    + (kind.version() - 1) + ", not from " + version);
        }
        TreeMap<Integer, Consumer<CgTreeEdit>> steps = STEPS.computeIfAbsent(kind, k -> new TreeMap<>());
        if (steps.putIfAbsent(version, step) != null) {
            throw new IllegalArgumentException(kind.id() + " has two steps from _v " + version);
        }
    }

    /**
     * {@code tree} at {@code kind}'s current {@code _v}, with {@code _v} first; the tree itself is left as it was.
     *
     * @throws CgAssetException if the file is newer than the code
     */
    public static Map<Object, Object> upgrade(Map<?, ?> tree, CgType kind) {
        CgVersionedCodec<Object> codec = CgCodecs.versioned(kind.version(), CgCodecs.PLAIN);
        TreeMap<Integer, Consumer<CgTreeEdit>> steps = STEPS.get(kind);
        if (steps != null) {
            for (Map.Entry<Integer, Consumer<CgTreeEdit>> s : steps.entrySet()) codec = codec.from(s.getKey(), s.getValue());
        }
        Object body;
        try {
            body = codec.decode(CgPlainOps.INSTANCE, tree);
        } catch (CgCodecException newer) {
            throw new CgAssetException("a " + kind.id() + " file at _v " + tree.get(CgType.VERSION)
                    + " is newer than this build, which reads _v " + kind.version());
        }
        Map<Object, Object> out = new LinkedHashMap<>();
        out.put(CgType.VERSION, kind.version());
        out.putAll((Map<?, ?>) body);
        return out;
    }
}
