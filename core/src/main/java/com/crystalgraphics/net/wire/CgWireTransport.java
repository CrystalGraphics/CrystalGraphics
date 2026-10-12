package com.crystalgraphics.net.wire;

import com.crystalgraphics.net.CgTransport;
import com.crystalgraphics.net.protocol.CgEnvelope;
import com.crystalgraphics.serialization.CgBinaryFormat;
import com.crystalgraphics.serialization.CgCodecException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * A {@link CgTransport} over a real connection — the swap the in-memory one was built to be replaced by.
 *
 * <p>The argument is the 1.7.10 workspace's, kept because it is still the reason this class has the shape
 * it does. That class was deleted at W3, when the loader stopped assembling a product and started
 * answering a host seam:</p>
 *
 * <blockquote>
 * <i>Both halves of a real workspace, in the client process … every listing, read and write crosses
 * {@code CgInMemoryTransport} as a real packet. Shortcutting that would make the later phase — the same
 * client against a workspace on a dedicated server — <b>a rewrite rather than a transport swap</b>.</i>
 * </blockquote>
 *
 * <p>So this deliberately implements {@code CgTransport<Object>} — the same parameterisation
 * {@code CgInMemoryTransport<Object>} has, over the same {@code CgPlainOps} trees. A session cannot tell the
 * difference, and the swap is a constructor call rather than a change to anything above it.</p>
 *
 * <h3>Where the encoding happens</h3>
 *
 * <p>{@code CgTransport} takes {@code T} rather than an {@code CgEnvelope} precisely so that <i>"every
 * implementation — including the in-memory one used by tests — exercises the real codec on every
 * hop"</i>. That is upheld here and extended by one step: the session encodes its envelope to a
 * {@code CgPlainOps} tree, and this encodes that tree to bytes through {@link CgBinaryFormat}. The tree
 * crossing an in-memory transport today and the bytes crossing a socket tomorrow describe the same
 * value, which is what keeps a headless test meaningful about production.</p>
 *
 * <p>The one exception is {@link #sendNotification}: a payload already in bytes (an object frame, a command batch)
 * travels as {@code [RAW_NOTIFICATION][method][bytes]} and arrives as a decoded {@link CgEnvelope.Notification}, so
 * the busiest traffic skips the tree and its copies.</p>
 */
public final class CgWireTransport implements CgTransport<Object> {

    /**
     * Leads a notification sent as bytes, {@code [tag][varint method length][method][payload]}, in place of an
     * encoded envelope. Never a {@link CgBinaryFormat} tag an envelope starts with: an envelope is a map.
     */
    static final int RAW_NOTIFICATION = 0x4E;

    /** Methods seen, as UTF-8, so a frame neither encodes nor decodes its method's name. Never shrinks. */
    private static final int MAX_METHODS = 32;
    private final List<String> methods = new ArrayList<>();
    private final List<byte[]> methodBytes = new ArrayList<>();

    private final CgFrameMultiplexer frames;
    private Consumer<Object> receiver = value -> { };

    public CgWireTransport(CgFrameMultiplexer frames) {
        this.frames = frames;
        // Decoding here rather than in the engine keeps CgFrameMultiplexer ignorant of what it carries: it
        // moves byte arrays, and every question about their meaning belongs on this side of the seam.
        frames.setMessageHandler(bytes -> receiver.accept(bytes.length > 0 && (bytes[0] & 0xFF) == RAW_NOTIFICATION
                ? readNotification(bytes) : CgBinaryFormat.decode(bytes)));
    }

    @Override
    public void send(Object encodedPacket) {
        frames.send(CgBinaryFormat.encode(encodedPacket));
    }

    /** One copy into the message: no envelope tree, no {@link CgBinaryFormat}. */
    @Override
    public boolean sendNotification(String method, byte[] payload, int length) {
        int known = methods.indexOf(method);
        if (known < 0) {
            known = methods.size();
            methods.add(method);
            methodBytes.add(method.getBytes(StandardCharsets.UTF_8));
        }
        byte[] name = methodBytes.get(known);
        byte[] message = new byte[1 + varIntSize(name.length) + name.length + length];
        message[0] = (byte) RAW_NOTIFICATION;
        int at = 1;
        for (int v = name.length; ; v >>>= 7) {
            if ((v & ~0x7F) == 0) {
                message[at++] = (byte) v;
                break;
            }
            message[at++] = (byte) ((v & 0x7F) | 0x80);
        }
        System.arraycopy(name, 0, message, at, name.length);
        System.arraycopy(payload, 0, message, at + name.length, length);
        frames.send(message);
        return true;
    }

    private CgEnvelope.Notification<Object> readNotification(byte[] message) {
        int at = 1, nameLength = 0;
        for (int shift = 0; ; shift += 7) {
            if (at >= message.length || shift > 28) throw new CgCodecException("a notification's method length is cut off");
            int b = message[at++] & 0xFF;
            nameLength |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
        }
        if (nameLength < 0 || at + nameLength > message.length) throw new CgCodecException("a notification's method is cut off");
        return new CgEnvelope.Notification<>(methodAt(message, at, nameLength),
                Arrays.copyOfRange(message, at + nameLength, message.length));
    }

    private String methodAt(byte[] message, int from, int length) {
        for (int i = 0; i < methodBytes.size(); i++) {
            if (sameBytes(methodBytes.get(i), message, from, length)) return methods.get(i);
        }
        String method = new String(message, from, length, StandardCharsets.UTF_8);
        if (methods.size() < MAX_METHODS) {   // a peer naming a new method per frame must not grow this
            methods.add(method);
            methodBytes.add(Arrays.copyOfRange(message, from, from + length));
        }
        return method;
    }

    private static boolean sameBytes(byte[] name, byte[] message, int from, int length) {
        if (name.length != length) return false;
        for (int i = 0; i < length; i++) {
            if (name[i] != message[from + i]) return false;
        }
        return true;
    }

    private static int varIntSize(int v) {
        int size = 1;
        while ((v & ~0x7F) != 0) {
            v >>>= 7;
            size++;
        }
        return size;
    }

    @Override
    public void setReceiver(Consumer<Object> receiver) {
        this.receiver = receiver == null ? value -> { } : receiver;
    }

    /**
     * Delivers what arrived and sends what is queued. <b>Call once per frame, on the thread that owns
     * the tree.</b>
     *
     * <p>The one thing an in-memory transport did not need and this does. Nothing here is delivered
     * spontaneously — see {@link CgFrameMultiplexer}'s threading note.</p>
     *
     * @return whole packets delivered this pump
     */
    public int pump() {
        return frames.pump();
    }

    public CgFrameMultiplexer frames() {
        return frames;
    }
}
