package com.crystalgraphics.serialization;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * A codec whose saves carry a version, so a file written by an older shape is migrated before today's codec reads it:
 * DataFixerUpper's idea, for a mod's own data. What {@link CgCodecs#versioned} makes.
 *
 * <pre>{@code
 * static final CgCodec<Character> SAVED = CgCodecs.versioned(3, Character.CODEC)
 *         .from(1, tree -> tree.rename("Level", "level"))                       // version 1 to 2
 *         .from(2, tree -> tree.move("skills", "abilities.list"));              // version 2 to 3
 *
 * CgFormats.write(file, SAVED, character);     // writes "_v": 3 before the codec's keys
 * Character back = CgFormats.read(file, SAVED);   // a version 1 file runs both steps first
 * }</pre>
 *
 * <ul>
 *   <li>A save with no version key is version 1: a codec gains {@code versioned} without touching its old files.</li>
 *   <li>A save newer than the codec throws, naming both versions, rather than being read on a best effort.</li>
 *   <li>Steps edit the tree in the format it was read from ({@link CgTreeEdit}), so one step serves binary, JSON,
 *       YAML and NBT alike.</li>
 *   <li>The codec it wraps must encode a map.</li>
 * </ul>
 */
public final class CgVersionedCodec<A> implements CgCodec<A> {

    private final String key;
    private final int current;
    private final CgCodec<A> codec;
    /** Each step by the version it migrates from. */
    private final TreeMap<Integer, Consumer<CgTreeEdit>> steps;

    CgVersionedCodec(String key, int current, CgCodec<A> codec, TreeMap<Integer, Consumer<CgTreeEdit>> steps) {
        if (current < 1) throw new IllegalArgumentException("a version is 1 or more, not " + current);
        this.key = key;
        this.current = current;
        this.codec = codec;
        this.steps = steps;
    }

    /** This codec with {@code step}, which turns a version {@code version} save into a version {@code version + 1} one. */
    public CgVersionedCodec<A> from(int version, Consumer<CgTreeEdit> step) {
        if (version < 1 || version >= current) {
            throw new IllegalArgumentException("a step migrates from 1 to " + (current - 1) + ", not from " + version);
        }
        if (steps.containsKey(version)) throw new IllegalArgumentException("two steps from version " + version);
        TreeMap<Integer, Consumer<CgTreeEdit>> more = new TreeMap<>(steps);
        more.put(version, step);
        return new CgVersionedCodec<>(key, current, codec, more);
    }

    @Override
    public <T> T encode(CgDynamicOps<T> ops, A input) {
        T body = codec.encode(ops, input);
        Map<T, T> out = new LinkedHashMap<>();
        out.put(ops.createString(key), ops.createNumber(current));
        out.putAll(ops.getMapValue(body));
        T made = ops.createMap(out);
        Map<String, String> comments = ops.commentsOf(body);
        return comments.isEmpty() ? made : ops.withComments(made, comments);
    }

    @Override
    public <T> A decode(CgDynamicOps<T> ops, T input) {
        return codec.decode(ops, migrated(ops, input));
    }

    @Override
    public <T> A decode(CgDynamicOps<T> ops, T input, @Nullable A target) {
        return codec.decode(ops, migrated(ops, input), target);
    }

    @SuppressWarnings("unchecked")
    private <T> T migrated(CgDynamicOps<T> ops, T input) {
        CgTreeEdit tree = new CgTreeEdit(ops, input);
        CgTreeEdit.Value written = tree.get(key);
        int version = written == null ? 1 : written.asInt();
        if (version > current) {
            throw new CgCodecException("this save is version " + version + " and this build reads " + current
                    + "; update the mod");
        }
        tree.remove(key);
        for (Consumer<CgTreeEdit> step : steps.subMap(version, current).values()) step.accept(tree);
        return (T) tree.build();
    }
}
