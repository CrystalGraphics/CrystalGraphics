package com.crystalgraphics.net.object;

import java.util.Objects;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.crystalgraphics.serialization.CgBinaryFormat;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgPlainOps;

/**
 * Something that happens to a networked object, sent by the server to the clients that see it: a shot, a hit, a
 * phase. Declared once as a {@code static final} and listed in its {@link CgNetType}.
 *
 * <pre>{@code
 * public static final CgNetEvent<Shot> FIRED  = CgNetEvent.of("fired", Shot.CODEC);
 * public static final CgNetEvent<Void> JAMMED = CgNetEvent.of("jammed");
 *
 * turret.emit(FIRED, new Shot(dx, dy, dz));          // server: every viewer, this tick
 * turret.emitToOwner(JAMMED, null);                  // server: the owner alone
 * TURRET.onEvent(FIRED, (obj, shot) -> flash(shot)); // client
 * }</pre>
 *
 * <ul>
 *   <li>Delivered after the tick, in emit order, after the object's changes of that tick: a behaviour reads the state
 *       the event happened in.</li>
 *   <li>A client that did not see the object that tick never gets it; a later viewer never does.</li>
 *   <li>An event emitted in the tick an object despawns still reaches its viewers, before the despawn.</li>
 * </ul>
 */
public final class CgNetEvent<P> {

    private static final Pattern NAME = Pattern.compile("[a-z0-9_./-]+");

    final String name;
    @Nullable
    final CgCodec<P> codec;

    private CgNetEvent(String name, @Nullable CgCodec<P> codec) {
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("an event name is lowercase, not '" + name + "'");
        this.name = name;
        this.codec = codec;
    }

    /** An event carrying a value. */
    public static <P> CgNetEvent<P> of(String name, CgCodec<P> codec) {
        return new CgNetEvent<>(name, Objects.requireNonNull(codec, "codec"));
    }

    /** An event carrying nothing. */
    public static CgNetEvent<Void> of(String name) {
        return new CgNetEvent<>(name, null);
    }

    public String name() {
        return name;
    }

    void write(NetBuffer out, @Nullable P value) {
        if (codec == null) return;
        byte[] encoded = CgBinaryFormat.encode(codec.encode(CgPlainOps.INSTANCE, Objects.requireNonNull(value, name)));
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
        return "CgNetEvent[" + name + "]";
    }
}
