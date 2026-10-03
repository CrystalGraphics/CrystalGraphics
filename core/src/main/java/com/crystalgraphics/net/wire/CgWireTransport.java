package com.crystalgraphics.net.wire;

import com.crystalgraphics.net.CgTransport;
import com.crystalgraphics.serialization.CgBinaryFormat;

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
 */
public final class CgWireTransport implements CgTransport<Object> {

    private final CgFrameMultiplexer frames;
    private Consumer<Object> receiver = value -> { };

    public CgWireTransport(CgFrameMultiplexer frames) {
        this.frames = frames;
        // Decoding here rather than in the engine keeps CgFrameMultiplexer ignorant of what it carries: it
        // moves byte arrays, and every question about their meaning belongs on this side of the seam.
        frames.setMessageHandler(bytes -> receiver.accept(CgBinaryFormat.decode(bytes)));
    }

    @Override
    public void send(Object encodedPacket) {
        frames.send(CgBinaryFormat.encode(encodedPacket));
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
