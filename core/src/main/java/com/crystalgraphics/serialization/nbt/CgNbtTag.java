package com.crystalgraphics.serialization.nbt;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgDynamicOps;

/**
 * One NBT tag, as plain Java: a {@link CgNbtCompound}, a {@link CgNbtList}, or a {@link CgNbtValue} (a string, a number of one of
 * six widths, or a byte, int or long array). It is Minecraft's tag model without Minecraft: build it, read it, print
 * it as SNBT, store it as binary NBT, run codecs over it, and move it to and from the game's own tags
 * ({@link CgNbt#of}, {@link CgNbt#toHost}), all the same on every version.
 *
 * <pre>{@code
 * CgNbtCompound tag = CgNbt.compound().put("name", "Goku").put("level", 9).put("tags", CgNbtList.of("saiyan", "z"));
 * tag.toString();                      // {name:"Goku",level:9,tags:["saiyan","z"]}
 * int level = tag.getInt("level");     // 0 when it is not there; getInt("level", 1) names the default
 * String first = tag.at("tags.0").asString();
 * }</pre>
 *
 * <ul>
 *   <li>Tags are mutable and owned by what holds them: {@code put} stores the tag itself, {@link #copy} takes a deep copy.</li>
 *   <li>{@link #toString()} is SNBT, what {@code /data get} prints, and {@link CgNbt#parse} reads it back.</li>
 *   <li>Equal means the same type and the same contents: {@code 1b} is not {@code 1}.</li>
 * </ul>
 */
public abstract class CgNbtTag {

    /** NBT's tag kinds, with the ids every version and the binary format use. */
    public enum Type {
        BYTE(1), SHORT(2), INT(3), LONG(4), FLOAT(5), DOUBLE(6), BYTE_ARRAY(7), STRING(8), LIST(9), COMPOUND(10),
        INT_ARRAY(11), LONG_ARRAY(12);

        private static final Type[] BY_ID = values();

        /** The id Minecraft gives this kind. */
        public final int id;

        Type(int id) {
            this.id = id;
        }

        /** The kind with {@code id}, or null for none (0 is the end tag). */
        public static Type of(int id) {
            return id >= 1 && id <= BY_ID.length ? BY_ID[id - 1] : null;
        }

        /** Whether this is one of the six number kinds. */
        public boolean isNumber() {
            return id >= 1 && id <= 6;
        }
    }

    /**
     * Any tag as a codec's value, carried whole through any format. NBT keeps every width; JSON, TOML and YAML keep
     * structure and read numbers back as int, long or double.
     */
    public static final CgCodec<CgNbtTag> CODEC = new CgCodec<CgNbtTag>() {
        @Override
        @SuppressWarnings("unchecked")
        public <T> T encode(CgDynamicOps<T> ops, CgNbtTag input) {
            if (ops == CgNbtOps.INSTANCE) return (T) input.copy();
            return CgNbt.walk(ops, input);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CgNbtTag decode(CgDynamicOps<T> ops, T input) {
            if (ops == CgNbtOps.INSTANCE) return ((CgNbtTag) input).copy();
            return CgNbt.fromPlain(ops.toPlain(input));
        }
    };

    CgNbtTag() {
    }

    /** This tag's kind. */
    public abstract Type type();

    /** A deep copy, sharing nothing with this tag. */
    public abstract CgNbtTag copy();

    /** This tag as SNBT. */
    @Override
    public final String toString() {
        return Snbt.write(this);
    }

    /** This tag read through {@code codec}: {@code tag.as(Spell.CODEC)}. */
    public final <V> V as(CgCodec<V> codec) {
        return CgNbt.decode(codec, this);
    }

    /** This tag as a compound; throws if it is another kind. */
    public final CgNbtCompound asCompound() {
        if (this instanceof CgNbtCompound c) return c;
        throw new CgCodecException("expected a compound tag, found " + type());
    }

    /** This tag as a list; throws if it is another kind. */
    public final CgNbtList asList() {
        if (this instanceof CgNbtList l) return l;
        throw new CgCodecException("expected a list tag, found " + type());
    }

    /** This tag as a value (string, number or array); throws if it is another kind. */
    public final CgNbtValue asValue() {
        if (this instanceof CgNbtValue v) return v;
        throw new CgCodecException("expected a value tag, found " + type());
    }

    /** This tag's text if it is a string tag, a number's digits if it is a number, otherwise its SNBT. */
    public String asString() {
        return toString();
    }
}
