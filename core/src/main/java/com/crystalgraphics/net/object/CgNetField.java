package com.crystalgraphics.net.object;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.crystalgraphics.net.rate.CgRatePolicy;

import com.crystalgraphics.serialization.CgBinaryFormat;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.CgPlainOps;

/**
 * One piece of a networked object's state: a name, a type and the rule for who is sent it. Declared once, as a
 * {@code static final}, and listed in a {@link CgNetType}.
 *
 * <pre>{@code
 * public static final CgNetField<Float>   HEAT   = CgNetField.ofFloat("heat");                  // every viewer
 * public static final CgNetField<Integer> AMMO   = CgNetField.ofInt("ammo").ownerOnly();         // the owner alone
 * public static final CgNetField<UUID>    TARGET = CgNetField.of("target", CgCodecs.UUID);       // any codec
 * public static final CgNetField<Integer> SEED   = CgNetField.ofInt("seed").initialOnly();       // spawn data
 * }</pre>
 *
 * <p>Read and write a primitive field unboxed, which allocates nothing:</p>
 *
 * <pre>{@code
 * turret.setFloat(HEAT, turret.getFloat(HEAT) + 0.1f);
 * }</pre>
 *
 * <p>A field the owner's client may set itself, shown there at once and confirmed or corrected by the server:</p>
 *
 * <pre>{@code
 * public static final CgNetField<Float> YAW = CgNetField.ofFloat("yaw")
 *         .predicted(CgRatePolicy.throttle(50))
 *         .sanitizedBy((obj, yaw) -> clampToArc(obj, yaw));
 *
 * turretSeenByMe.setFloat(YAW, yaw);   // client, its owner: applied here now, sent to the server
 * }</pre>
 *
 * <ul>
 *   <li>Rules are set where the field is declared and frozen once a type lists it.</li>
 *   <li>A field the server never set is not sent at spawn: a viewer reads its {@link #fallback}, as it does a field
 *       its server does not have.</li>
 *   <li>A name is matched within each type that lists it, so a short one ({@code "heat"}) is enough: the type's
 *       {@code namespace:path} keeps it apart from another mod's. Lowercase, digits and {@code _ . / -}.</li>
 *   <li>A field may be listed by several types; its name is what crosses the wire, once per connection.</li>
 *   <li>A value read off a {@code CgCodec} field is shared, not copied: change it by setting a new one.</li>
 * </ul>
 */
public final class CgNetField<V> {

    private static final Pattern NAME = Pattern.compile("[a-z0-9_./-]+");

    enum Kind { INT, LONG, FLOAT, DOUBLE, BOOL, CODEC }

    final String name;
    final Kind kind;
    final CgCodec<V> codec;

    boolean initialOnly, ownerOnly, skipOwner, serverOnly;
    /** A predicted field's way to the server: a command its type lists after its own. */
    @Nullable
    CgNetCommand<V> command;
    /** A primitive's fallback, as {@link CgNetObject} stores it. */
    long fallbackBits;
    V fallback;
    private boolean frozen;

    private CgNetField(String name, Kind kind, CgCodec<V> codec, V fallback, long fallbackBits) {
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("a field name is lowercase, not '" + name + "'");
        this.name = name;
        this.kind = kind;
        this.codec = Objects.requireNonNull(codec, "codec");
        this.fallback = fallback;
        this.fallbackBits = fallbackBits;
    }

    public static CgNetField<Integer> ofInt(String name) {
        return new CgNetField<>(name, Kind.INT, CgCodecs.INT, 0, 0);
    }

    public static CgNetField<Long> ofLong(String name) {
        return new CgNetField<>(name, Kind.LONG, CgCodecs.LONG, 0L, 0);
    }

    public static CgNetField<Float> ofFloat(String name) {
        return new CgNetField<>(name, Kind.FLOAT, CgCodecs.FLOAT, 0f, Float.floatToRawIntBits(0f));
    }

    public static CgNetField<Double> ofDouble(String name) {
        return new CgNetField<>(name, Kind.DOUBLE, CgCodecs.DOUBLE, 0d, Double.doubleToRawLongBits(0d));
    }

    public static CgNetField<Boolean> ofBool(String name) {
        return new CgNetField<>(name, Kind.BOOL, CgCodecs.BOOL, false, 0);
    }

    /** A field of any type with a codec; null until set unless given a {@link #fallback}. */
    public static <V> CgNetField<V> of(String name, CgCodec<V> codec) {
        return new CgNetField<>(name, Kind.CODEC, codec, null, 0);
    }

    // ── Rules ───────────────────────────────────────────────────────────────────────────────────

    /** Sent at spawn and never again: what a viewer builds from. Later changes still reach a later viewer's spawn. */
    public CgNetField<V> initialOnly() {
        mutable().initialOnly = true;
        return this;
    }

    /** Sent to the object's owner alone: hidden information. */
    public CgNetField<V> ownerOnly() {
        if (skipOwner) throw new IllegalStateException(name + " is skipOwner");
        mutable().ownerOnly = true;
        return this;
    }

    /** Sent to every viewer but the owner, who showed it already. */
    public CgNetField<V> skipOwner() {
        if (ownerOnly) throw new IllegalStateException(name + " is ownerOnly");
        mutable().skipOwner = true;
        return this;
    }

    /** Never sent; kept and persisted on the server. */
    public CgNetField<V> serverOnly() {
        if (command != null) throw new IllegalStateException(name + " is predicted");
        mutable().serverOnly = true;
        return this;
    }

    /**
     * Its owner's client may set it: applied there at once and sent to the server, which takes it through
     * {@link #sanitizedBy} and replicates it. The owner is sent the server's value only when it differs from what it
     * sent. Immediate, at most 20 a second.
     */
    public CgNetField<V> predicted() {
        return predicted(CgRatePolicy.IMMEDIATE);
    }

    /** {@link #predicted()}, leaving the client at {@code rate}. */
    public CgNetField<V> predicted(CgRatePolicy rate) {
        if (serverOnly || initialOnly) throw new IllegalStateException(name + " is never sent after spawn: it cannot be predicted");
        mutable().command = CgNetCommand.of("field/" + name, codec).rate(rate);
        command.field = this;
        return this;
    }

    /** What makes a value its owner predicted safe, given the object; null drops it. Server thread. */
    public CgNetField<V> sanitizedBy(BiFunction<CgNetObject, V, V> sanitize) {
        if (mutable().command == null) throw new IllegalStateException(name + " is not predicted: only a predicted field is sanitized");
        command.sanitizedBy(sanitize);
        return this;
    }

    /** What the field reads before anything sets it, on every side. */
    public CgNetField<V> fallback(V value) {
        mutable();
        fallback = value;
        if (kind != Kind.CODEC) fallbackBits = bitsOf(Objects.requireNonNull(value, "a primitive's fallback"));
        return this;
    }

    private CgNetField<V> mutable() {
        if (frozen) throw new IllegalStateException(name + " is listed by a type already: its rules are fixed");
        return this;
    }

    void freeze() {
        frozen = true;
    }

    public String name() {
        return name;
    }

    /** What it reads before anything sets it. */
    public V fallback() {
        return fallback;
    }

    /** Sent to a viewer who is, or is not, the owner. */
    boolean sentTo(boolean owner) {
        if (serverOnly) return false;
        return owner ? !skipOwner : !ownerOnly;
    }

    // ── Values ──────────────────────────────────────────────────────────────────────────────────

    long bitsOf(Object value) {
        switch (kind) {
            case INT: return (Integer) value;
            case LONG: return (Long) value;
            case FLOAT: return Float.floatToRawIntBits((Float) value);
            case DOUBLE: return Double.doubleToRawLongBits((Double) value);
            case BOOL: return (Boolean) value ? 1 : 0;
            default: throw new IllegalStateException(name + " is not a primitive");
        }
    }

    @SuppressWarnings("unchecked")
    V box(long bits) {
        switch (kind) {
            case INT: return (V) Integer.valueOf((int) bits);
            case LONG: return (V) Long.valueOf(bits);
            case FLOAT: return (V) Float.valueOf(Float.intBitsToFloat((int) bits));
            case DOUBLE: return (V) Double.valueOf(Double.longBitsToDouble(bits));
            case BOOL: return (V) Boolean.valueOf(bits != 0);
            default: throw new IllegalStateException(name + " is not a primitive");
        }
    }

    /** One value onto the wire: a primitive directly, anything else through its codec. */
    void write(NetBuffer out, long bits, Object ref) {
        switch (kind) {
            case INT:
            case LONG: out.writeZigZag(bits); break;
            case FLOAT: out.writeInt((int) bits); break;
            case DOUBLE: out.writeLong(bits); break;
            case BOOL: out.writeByte((int) bits); break;
            default: {
                @SuppressWarnings("unchecked") V value = (V) ref;
                byte[] encoded = CgBinaryFormat.encode(codec.encode(CgPlainOps.INSTANCE, value));
                out.writeBytes(encoded, 0, encoded.length);
            }
        }
    }

    /** A primitive's bits off the wire. */
    long readBits(NetBuffer in) {
        switch (kind) {
            case INT: return (int) in.readZigZag();
            case LONG: return in.readZigZag();
            case FLOAT: return in.readInt();
            case DOUBLE: return in.readLong();
            case BOOL: return in.readByte();
            default: throw new IllegalStateException(name + " is not a primitive");
        }
    }

    /** A codec value off the wire, {@code length} bytes. */
    V readRef(NetBuffer in, int length) {
        byte[] encoded = new byte[length];
        System.arraycopy(in.bytes, in.pos, encoded, 0, length);
        in.skip(length);
        return codec.decode(CgPlainOps.INSTANCE, CgBinaryFormat.decode(encoded));
    }

    @Override
    public String toString() {
        return "CgNetField[" + name + " " + kind + "]";
    }
}
