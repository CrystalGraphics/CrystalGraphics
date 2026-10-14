package com.crystalgraphics.serialization;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * A codec for a record, declared once as its keys and its constructor: what {@link CgCodecs#record} makes. The shape
 * of DataFixerUpper's {@code RecordCodecBuilder} (MIT), without its applicative types.
 *
 * <pre>{@code
 * public record Spell(String name, int cost, @Nullable String icon) {
 *     public static final CgCodec<Spell> CODEC = CgCodecs.record(
 *             CgCodecs.field("name", CgCodecs.STRING, Spell::name),
 *             CgCodecs.optionalField("cost", CgCodecs.INT, Spell::cost, 0),
 *             CgCodecs.optionalField("icon", CgCodecs.STRING, Spell::icon, null),
 *             Spell::new);
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Keys are written in declaration order, so the same record encodes to the same bytes.</li>
 *   <li>A required key the input lacks throws, naming it; so does a required value that is null.</li>
 *   <li>An optional key equal to its fallback is not written, and reads as its fallback when absent.</li>
 *   <li>A {@code fieldOr} key is always written, and reads as its fallback when absent: a shape whose bytes are
 *       pinned (a content hash) and whose older files lacked the key.</li>
 *   <li>Keys the record does not declare are ignored.</li>
 *   <li>A {@link CgRecordField#lenient lenient} key whose value fails to decode reads its fallback, with a warning.</li>
 *   <li>Up to sixteen keys, as DataFixerUpper; nest a record, or use {@link CgCodecs#object}, beyond that.</li>
 * </ul>
 */
public final class CgRecordCodec<R> implements CgCodec<R> {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    private final CgRecordField<R, ?>[] fields;
    private final Function<Object[], R> make;
    /** The record's own comment by null and each key's by its name: one map, handed to every encode. */
    private final Map<String, String> comments;

    @SafeVarargs
    CgRecordCodec(Function<Object[], R> make, CgRecordField<R, ?>... fields) {
        this(make, fields, null);
    }

    private CgRecordCodec(Function<Object[], R> make, CgRecordField<R, ?>[] fields, @Nullable String about) {
        this.make = make;
        this.fields = fields;
        Map<String, String> byKey = new LinkedHashMap<>();
        if (about != null) byKey.put(null, about);
        for (int i = 0; i < fields.length; i++) {
            for (int j = 0; j < i; j++) {
                if (fields[i].key.equals(fields[j].key)) {
                    throw new IllegalArgumentException("a record codec declares '" + fields[i].key + "' twice");
                }
            }
            if (fields[i].comment != null) byKey.put(fields[i].key, fields[i].comment);
        }
        this.comments = Collections.unmodifiableMap(byKey);
    }

    /** This codec, with {@code text} about the record as a whole: see {@link CgCodec#comment}. */
    @Override
    public CgRecordCodec<R> comment(String text) {
        return new CgRecordCodec<>(make, fields, text);
    }

    @Override
    public <T> T encode(CgDynamicOps<T> ops, R input) {
        Map<T, T> entries = new LinkedHashMap<>(fields.length * 2);
        for (CgRecordField<R, ?> f : fields) write(ops, f, input, entries);
        T made = ops.createMap(entries);
        return comments.isEmpty() ? made : ops.withComments(made, comments);
    }

    private static <T, R, F> void write(CgDynamicOps<T> ops, CgRecordField<R, F> f, R input, Map<T, T> entries) {
        F value = f.get.apply(input);
        if (f.omits && Objects.equals(value, f.fallback)) return;
        if (value == null) throw new CgCodecException("'" + f.key + "' is null; declare it with optionalField");
        try {
            entries.put(ops.createString(f.key), f.codec.encode(ops, value));
        } catch (CgCodecException e) {
            throw new CgCodecException("'" + f.key + "': " + e.getMessage(), e);
        }
    }

    @Override
    public <T> R decode(CgDynamicOps<T> ops, T input) {
        Map<String, T> byKey = new LinkedHashMap<>();
        for (Map.Entry<T, T> e : ops.getMapValue(input).entrySet()) byKey.put(ops.getStringValue(e.getKey()), e.getValue());
        Object[] args = new Object[fields.length];
        // By identity: JSON's null is one instance, while NBT's empty value is an empty compound, which a map holds.
        T empty = ops.empty();
        for (int i = 0; i < fields.length; i++) {
            CgRecordField<R, ?> f = fields[i];
            T raw = byKey.get(f.key);
            if (raw == null || raw == empty) {
                if (!f.defaults) throw new CgCodecException("Missing required field '" + f.key + "'");
                args[i] = f.fallback;
                continue;
            }
            try {
                args[i] = f.codec.decode(ops, raw);
            } catch (CgCodecException | ClassCastException e) {
                if (!f.lenient) throw new CgCodecException("'" + f.key + "': " + e.getMessage(), e);
                LOGGER.warn("'{}' could not be read ({}); it reads as its fallback", f.key, e.getMessage());
                args[i] = f.fallback;
            }
        }
        return make.apply(args);
    }

    /** A constructor of three; {@code Spell::new}. */
    @FunctionalInterface
    public interface Of3<A, B, C, R> {
        R apply(A a, B b, C c);
    }

    @FunctionalInterface
    public interface Of4<A, B, C, D, R> {
        R apply(A a, B b, C c, D d);
    }

    @FunctionalInterface
    public interface Of5<A, B, C, D, E, R> {
        R apply(A a, B b, C c, D d, E e);
    }

    @FunctionalInterface
    public interface Of6<A, B, C, D, E, F, R> {
        R apply(A a, B b, C c, D d, E e, F f);
    }

    @FunctionalInterface
    public interface Of7<A, B, C, D, E, F, G, R> {
        R apply(A a, B b, C c, D d, E e, F f, G g);
    }

    @FunctionalInterface
    public interface Of8<A, B, C, D, E, F, G, H, R> {
        R apply(A a, B b, C c, D d, E e, F f, G g, H h);
    }

    @FunctionalInterface
    public interface Of9<A, B, C, D, E, F, G, H, I, R> {
        R apply(A a, B b, C c, D d, E e, F f, G g, H h, I i);
    }

    @FunctionalInterface
    public interface Of10<A, B, C, D, E, F, G, H, I, J, R> {
        R apply(A a, B b, C c, D d, E e, F f, G g, H h, I i, J j);
    }

    @FunctionalInterface
    public interface Of11<A, B, C, D, E, F, G, H, I, J, K, R> {
        R apply(A a, B b, C c, D d, E e, F f, G g, H h, I i, J j, K k);
    }

    @FunctionalInterface
    public interface Of12<A, B, C, D, E, F, G, H, I, J, K, L, R> {
        R apply(A a, B b, C c, D d, E e, F f, G g, H h, I i, J j, K k, L l);
    }

    @FunctionalInterface
    public interface Of13<A, B, C, D, E, F, G, H, I, J, K, L, M, R> {
        R apply(A a, B b, C c, D d, E e, F f, G g, H h, I i, J j, K k, L l, M m);
    }

    @FunctionalInterface
    public interface Of14<A, B, C, D, E, F, G, H, I, J, K, L, M, N, R> {
        R apply(A a, B b, C c, D d, E e, F f, G g, H h, I i, J j, K k, L l, M m, N n);
    }

    @FunctionalInterface
    public interface Of15<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, R> {
        R apply(A a, B b, C c, D d, E e, F f, G g, H h, I i, J j, K k, L l, M m, N n, O o);
    }

    @FunctionalInterface
    public interface Of16<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, R> {
        R apply(A a, B b, C c, D d, E e, F f, G g, H h, I i, J j, K k, L l, M m, N n, O o, P p);
    }
}
