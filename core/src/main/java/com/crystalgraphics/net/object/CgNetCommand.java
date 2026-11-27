package com.crystalgraphics.net.object;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.crystalgraphics.net.rate.CgRatePolicy;
import com.crystalgraphics.serialization.CgBinaryFormat;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgPlainOps;

/**
 * A client asking the server to act on one networked object: aim, fire, defuse. Declared once as a
 * {@code static final}, listed in its {@link CgNetType}, handled on the server.
 *
 * <pre>{@code
 * public static final CgNetCommand<Aim> AIM = CgNetCommand.of("aim", Aim.CODEC)
 *         .rate(CgRatePolicy.throttle(50))                         // the client sends at most 20 a second
 *         .perSecond(25)                                           // the server refuses more
 *         .sanitizedBy((obj, aim) -> aim.clampedTo(obj.getFloat(ARC)));
 *
 * TURRET.onCommand(AIM, (obj, sender, aim) -> obj.set(TARGET, aim.target()));   // server, at init
 * turretSeenByMe.send(AIM, new Aim(yaw, pitch));                               // client, its owner
 * }</pre>
 *
 * <ul>
 *   <li><b>Only the object's owner may send one</b>, unless the command says {@link #fromAnyViewer()}; and only while
 *       it sees the object. Anything else is refused and counted ({@link #refused()}), never handled.</li>
 *   <li><b>Sanitize, don't reject</b>: {@link #sanitizedBy} turns an arriving value into one a legal gesture could have
 *       made, against the object's own state. Answer null to drop it.</li>
 *   <li>A throttled or debounced command always sends its last value: a drag's end is never lost.</li>
 *   <li>The sender is the connection's, never a field of the value.</li>
 * </ul>
 */
public final class CgNetCommand<P> {

    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9_./-]+");

    /** Refused: a sender who is not its owner, nor a watcher of a watched type. */
    public static final String REFUSED_NOT_SENDER = "net/not_sender";
    /** Refused: over its {@link #perSecond} for this sender, or past {@link #maxBytes}. Dropped and counted. */
    public static final String REFUSED_RATE = "net/rate", REFUSED_TOO_LARGE = "net/too_large";
    /** Refused: its type's {@code allow} guard, or {@code allowEdits} for an edit, said no. */
    public static final String REFUSED_NOT_ALLOWED = "net/not_allowed";
    /** Refused edits: a read-only key, or a value outside its key's range. */
    public static final String REFUSED_READ_ONLY = "net/read_only", REFUSED_OUT_OF_RANGE = "net/out_of_range";
    /** Refused: the server does not have it (another version of the mod), or its object is gone. */
    public static final String REFUSED_UNKNOWN = "net/unknown", REFUSED_GONE = "net/gone";
    /** Refused: it did not decode, or its sanitizer turned it to nothing. */
    public static final String REFUSED_MALFORMED = "net/malformed", REFUSED_SANITIZED = "net/sanitized";
    /** Refused: a call nobody handles on the server. */
    public static final String REFUSED_UNHANDLED = "net/unhandled";

    final String name;
    @Nullable
    final CgCodec<P> codec;
    @Nullable
    final CgNetWire<P> wire;
    CgRatePolicy rate = CgRatePolicy.IMMEDIATE;
    float perSecond = 20;
    float burst = -1;
    boolean fromAnyViewer, fromOwner;
    @Nullable
    BiFunction<CgNetObject, P, P> sanitize;
    int maxBytes = 32 * 1024;
    long refused;
    long malformed;
    /** Of {@link #refused}: over the rate or the size, dropped without being read. */
    long dropped;
    /** The call this command carries, whose handler answers; null for a command. */
    @Nullable
    CgNetCall<P, ?> call;
    /** The predicted field this command carries, whose value it sets; null for a declared command. */
    @Nullable
    CgNetField<P> field;

    private CgNetCommand(String name, @Nullable CgCodec<P> codec, @Nullable CgNetWire<P> wire) {
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("a command name is letters, digits and _ . / -, not '" + name + "'");
        this.name = name;
        this.codec = codec;
        this.wire = wire;
    }

    /** A command carrying a value. */
    public static <P> CgNetCommand<P> of(String name, CgCodec<P> codec) {
        return new CgNetCommand<>(name, Objects.requireNonNull(codec, "codec"), null);
    }

    /**
     * A command carrying a value in its fixed-width form, as {@link CgNetEvent#of(String, CgNetWire)}: nothing allocated
     * per send, for one sent often (a hit claimed). An immediate rate writes it at the send, so one value may be refilled
     * for the next send at once; a held rate keeps the instance until it leaves.
     */
    public static <P> CgNetCommand<P> of(String name, CgNetWire<P> wire) {
        return new CgNetCommand<>(name, null, Objects.requireNonNull(wire, "wire"));
    }

    /** A command carrying nothing. */
    public static CgNetCommand<Void> of(String name) {
        return new CgNetCommand<>(name, null, null);
    }

    /** How often the client lets it go; immediate unless said. */
    public CgNetCommand<P> rate(CgRatePolicy policy) {
        rate = Objects.requireNonNull(policy, "policy");
        return this;
    }

    /** How many a second the server takes from one player, 20 unless said; past it, refused. */
    public CgNetCommand<P> perSecond(float count) {
        if (!(count > 0)) throw new IllegalArgumentException("perSecond must be positive: " + count);
        perSecond = count;
        return this;
    }

    /** How many may come at once before {@link #perSecond} applies; one second's worth unless said. */
    public CgNetCommand<P> burst(float count) {
        if (!(count >= 1)) throw new IllegalArgumentException("burst must be at least 1: " + count);
        burst = count;
        return this;
    }

    /**
     * Only the object's owner may send it, on a {@linkplain CgNetType.Builder#watched watched} type whose commands come
     * from its watchers otherwise. A command of any other type is its owner's already.
     */
    public CgNetCommand<P> fromOwner() {
        if (fromAnyViewer) throw new IllegalStateException(this + " is fromAnyViewer");
        fromOwner = true;
        return this;
    }

    /** Any player who sees the object may send it, not only its owner. */
    public CgNetCommand<P> fromAnyViewer() {
        fromAnyViewer = true;
        return this;
    }

    /** What makes an arriving value safe, given the object it is for; null drops it. Server thread. */
    public CgNetCommand<P> sanitizedBy(BiFunction<CgNetObject, P, P> sanitize) {
        this.sanitize = Objects.requireNonNull(sanitize, "sanitize");
        return this;
    }

    /**
     * The largest value the server reads, in bytes; a larger one is dropped and refused, {@link #REFUSED_TOO_LARGE}.
     * 32 KB unless said.
     */
    public CgNetCommand<P> maxBytes(int bytes) {
        if (bytes <= 0) throw new IllegalArgumentException("maxBytes must be positive: " + bytes);
        maxBytes = bytes;
        return this;
    }

    public String name() {
        return name;
    }

    /** Of {@link #refused}: over its rate or its size, dropped unread. */
    public long dropped() {
        return dropped;
    }

    /** Refused on the server: not the owner, not seen, over the rate, or for no live object. */
    public long refused() {
        return refused;
    }

    /** Dropped on the server because they did not decode. */
    public long malformed() {
        return malformed;
    }

    float burstOrDefault() {
        return burst > 0 ? burst : Math.max(1, perSecond);
    }

    void write(NetBuffer out, @Nullable Object value) {
        @SuppressWarnings("unchecked") P typed = (P) value;
        if (wire != null) {
            wire.write(Objects.requireNonNull(typed, name), out);
            return;
        }
        if (codec == null) return;
        byte[] encoded = CgBinaryFormat.encode(codec.encode(CgPlainOps.INSTANCE, Objects.requireNonNull(typed, name)));
        out.writeBytes(encoded, 0, encoded.length);
    }

    @Nullable
    P read(NetBuffer in, int length) {
        if (wire != null) {
            int end = in.pos + length;
            int size = in.size;
            in.size = end;
            try {
                return wire.read(in);
            } finally {
                in.size = size;
                in.pos = end;
            }
        }
        if (codec == null) {
            in.skip(length);
            return null;
        }
        byte[] encoded = new byte[length];
        System.arraycopy(in.bytes, in.pos, encoded, 0, length);
        in.skip(length);
        return codec.decode(CgPlainOps.INSTANCE, CgBinaryFormat.decode(encoded));
    }

    @Override
    public String toString() {
        return "CgNetCommand[" + name + "]";
    }
}
