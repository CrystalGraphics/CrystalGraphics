package com.crystalgraphics.serialization.nbt;

import java.util.Arrays;

/**
 * A tag that holds one value: a string, a number of one of six widths, or an array of bytes, ints or longs.
 *
 * <pre>{@code
 * CgNbtValue level = CgNbtValue.ofInt(9);
 * level.asInt();        // 9
 * level.asDouble();     // 9.0: a number reads as any width
 * level.asString();     // "9"
 * CgNbtValue.ofBool(true); // 1b, as Minecraft stores a boolean
 * }</pre>
 *
 * <ul>
 *   <li>A number reads as any other width, narrowing as a Java cast does; a string or an array reads as 0 or false.</li>
 *   <li>Arrays are copied in and out, so a value never changes under you.</li>
 * </ul>
 */
public final class CgNbtValue extends CgNbtTag {

    private final Type type;
    private final Object value;

    private CgNbtValue(Type type, Object value) {
        this.type = type;
        this.value = value;
    }

    /** Values are immutable, so the small ones every tag is full of (flags, counts, levels) are shared. */
    private static final CgNbtValue[] BYTES = new CgNbtValue[256];
    private static final CgNbtValue[] SHORTS = new CgNbtValue[256];
    private static final CgNbtValue[] INTS = new CgNbtValue[256];
    private static final CgNbtValue[] LONGS = new CgNbtValue[256];

    static {
        for (int i = 0; i < 256; i++) {
            int v = i - 128;
            BYTES[i] = new CgNbtValue(Type.BYTE, (byte) v);
            SHORTS[i] = new CgNbtValue(Type.SHORT, (short) v);
            INTS[i] = new CgNbtValue(Type.INT, v);
            LONGS[i] = new CgNbtValue(Type.LONG, (long) v);
        }
    }

    public static CgNbtValue ofByte(byte value) {
        return BYTES[value + 128];
    }

    public static CgNbtValue ofShort(short value) {
        return value >= -128 && value < 128 ? SHORTS[value + 128] : new CgNbtValue(Type.SHORT, value);
    }

    public static CgNbtValue ofInt(int value) {
        return value >= -128 && value < 128 ? INTS[value + 128] : new CgNbtValue(Type.INT, value);
    }

    public static CgNbtValue ofLong(long value) {
        return value >= -128 && value < 128 ? LONGS[(int) value + 128] : new CgNbtValue(Type.LONG, value);
    }

    public static CgNbtValue ofFloat(float value) {
        return new CgNbtValue(Type.FLOAT, value);
    }

    public static CgNbtValue ofDouble(double value) {
        return new CgNbtValue(Type.DOUBLE, value);
    }

    /** A boolean as Minecraft stores it: a byte, 1 or 0. */
    public static CgNbtValue ofBool(boolean value) {
        return ofByte((byte) (value ? 1 : 0));
    }

    public static CgNbtValue ofString(String value) {
        return new CgNbtValue(Type.STRING, value == null ? "" : value);
    }

    public static CgNbtValue ofBytes(byte[] value) {
        return new CgNbtValue(Type.BYTE_ARRAY, value.clone());
    }

    public static CgNbtValue ofInts(int[] value) {
        return new CgNbtValue(Type.INT_ARRAY, value.clone());
    }

    public static CgNbtValue ofLongs(long[] value) {
        return new CgNbtValue(Type.LONG_ARRAY, value.clone());
    }

    /** The value of a number of the width its Java type has ({@code Integer} an int, {@code Double} a double...); any other {@code Number} is a double. */
    public static CgNbtValue ofNumber(Number number) {
        if (number instanceof Byte b) return ofByte(b);
        if (number instanceof Short s) return ofShort(s);
        if (number instanceof Integer i) return ofInt(i);
        if (number instanceof Long l) return ofLong(l);
        if (number instanceof Float f) return ofFloat(f);
        return ofDouble(number.doubleValue());
    }

    @Override
    public Type type() {
        return type;
    }

    @Override
    public CgNbtValue copy() {
        switch (type) {
            case BYTE_ARRAY: return ofBytes((byte[]) value);
            case INT_ARRAY: return ofInts((int[]) value);
            case LONG_ARRAY: return ofLongs((long[]) value);
            default: return this;   // the rest are immutable
        }
    }

    /** Whether this is a number of any width. */
    public boolean isNumber() {
        return type.isNumber();
    }

    /** Whether this is a string. */
    public boolean isString() {
        return type == Type.STRING;
    }

    /** This number, boxed as its own width, or null if this is not a number. */
    public Number number() {
        return isNumber() ? (Number) value : null;
    }

    public byte asByte() {
        return isNumber() ? ((Number) value).byteValue() : 0;
    }

    public short asShort() {
        return isNumber() ? ((Number) value).shortValue() : 0;
    }

    public int asInt() {
        return isNumber() ? ((Number) value).intValue() : 0;
    }

    public long asLong() {
        return isNumber() ? ((Number) value).longValue() : 0L;
    }

    public float asFloat() {
        return isNumber() ? ((Number) value).floatValue() : 0f;
    }

    public double asDouble() {
        return isNumber() ? ((Number) value).doubleValue() : 0d;
    }

    /** A number other than zero. */
    public boolean asBool() {
        return isNumber() && ((Number) value).doubleValue() != 0d;
    }

    @Override
    public String asString() {
        if (type == Type.STRING) return (String) value;
        if (isNumber()) return String.valueOf(value);
        return toString();
    }

    /** The bytes of a byte array tag, a copy; empty for any other kind. */
    public byte[] bytes() {
        return type == Type.BYTE_ARRAY ? ((byte[]) value).clone() : new byte[0];
    }

    /** The ints of an int array tag, a copy; empty for any other kind. */
    public int[] ints() {
        return type == Type.INT_ARRAY ? ((int[]) value).clone() : new int[0];
    }

    /** The longs of a long array tag, a copy; empty for any other kind. */
    public long[] longs() {
        return type == Type.LONG_ARRAY ? ((long[]) value).clone() : new long[0];
    }

    /** The payload without a copy, for the library's own readers: a boxed number, a String or an array. */
    Object raw() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CgNbtValue that) || that.type != type) return false;
        switch (type) {
            case BYTE_ARRAY: return Arrays.equals((byte[]) value, (byte[]) that.value);
            case INT_ARRAY: return Arrays.equals((int[]) value, (int[]) that.value);
            case LONG_ARRAY: return Arrays.equals((long[]) value, (long[]) that.value);
            default: return value.equals(that.value);
        }
    }

    @Override
    public int hashCode() {
        switch (type) {
            case BYTE_ARRAY: return 31 * type.id + Arrays.hashCode((byte[]) value);
            case INT_ARRAY: return 31 * type.id + Arrays.hashCode((int[]) value);
            case LONG_ARRAY: return 31 * type.id + Arrays.hashCode((long[]) value);
            default: return 31 * type.id + value.hashCode();
        }
    }
}
