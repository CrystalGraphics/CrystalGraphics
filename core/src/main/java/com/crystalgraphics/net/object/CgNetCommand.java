package com.crystalgraphics.net.object;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.crystalgraphics.net.CgPeer;
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

    private static final Pattern NAME = Pattern.compile("[a-z0-9_./-]+");

    /** What the server does with one. */
    @FunctionalInterface
    public interface Handler<P> {
        void handle(CgNetObject obj, CgPeer sender, @Nullable P value);
    }

    final String name;
    @Nullable
    final CgCodec<P> codec;
    CgRatePolicy rate = CgRatePolicy.IMMEDIATE;
    float perSecond = 20;
    float burst = -1;
    boolean fromAnyViewer;
    @Nullable
    BiFunction<CgNetObject, P, P> sanitize;
    long refused;
    long malformed;
    /** The predicted field this command carries, whose value it sets; null for a declared command. */
    @Nullable
    CgNetField<P> field;

    private CgNetCommand(String name, @Nullable CgCodec<P> codec) {
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("a command name is lowercase, not '" + name + "'");
        this.name = name;
        this.codec = codec;
    }

    /** A command carrying a value. */
    public static <P> CgNetCommand<P> of(String name, CgCodec<P> codec) {
        return new CgNetCommand<>(name, Objects.requireNonNull(codec, "codec"));
    }

    /** A command carrying nothing. */
    public static CgNetCommand<Void> of(String name) {
        return new CgNetCommand<>(name, null);
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

    public String name() {
        return name;
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
        if (codec == null) return;
        @SuppressWarnings("unchecked") P typed = (P) value;
        byte[] encoded = CgBinaryFormat.encode(codec.encode(CgPlainOps.INSTANCE, Objects.requireNonNull(typed, name)));
        out.writeBytes(encoded, 0, encoded.length);
    }

    @Nullable
    P read(NetBuffer in, int length) {
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
