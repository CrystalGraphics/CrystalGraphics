package com.crystalgraphics.net;

import java.util.function.Consumer;

/**
 * Carries already-encoded messages between the two halves of a session.
 *
 * <p>Deliberately takes {@code T} rather than an {@link com.crystalgraphics.net.protocol.CgEnvelope}:
 * sessions encode before handing over,
 * so every implementation — including the in-memory one used by tests — exercises the real codec on
 * every hop. A transport that passed object references would let a field somebody forgot to encode
 * pass every test and fail only in game.</p>
 *
 * <p><b>The receiver may be called from any thread.</b> It is a mailbox, not a dispatcher: a session
 * queues what arrives and processes it from {@code tick()} on the thread that owns the tree.
 * {@code Property} and {@code SignalBase} are single-threaded by documented contract, so touching
 * elements from a network thread is not a race to be tuned but a correctness bug.</p>
 *
 * @param <T> the encoded representation, matching the session's {@code CgDynamicOps}
 */
public interface CgTransport<T> {

    /** Fire-and-forget. Called on the session's own thread. */
    void send(T encodedPacket);

    /** Installs the sink. Replacing it replaces the previous one. */
    void setReceiver(Consumer<T> receiver);
}
