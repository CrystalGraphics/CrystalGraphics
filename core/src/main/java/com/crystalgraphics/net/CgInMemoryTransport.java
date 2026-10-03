package com.crystalgraphics.net;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * A pair of transports wired to each other in one JVM, delivered only when asked.
 *
 * <p>Two properties make this a test double worth trusting rather than a stub that agrees with
 * everything:</p>
 * <ul>
 *   <li><b>Nothing is delivered implicitly.</b> {@link #deliver()} is explicit, so a test states
 *       exactly when the network is allowed to move and orderings are reproducible rather than
 *       incidental.</li>
 *   <li><b>Everything is recorded.</b> {@link #sent()} makes assertions about <em>traffic</em>
 *       possible — "opening a cached UI sends no description" is a claim about what went over the
 *       wire, and checking only the end state would pass whether or not the cache worked.</li>
 * </ul>
 *
 * <p>{@link #dropNext()} and {@link #corruptNext} exist because a transport that never loses or
 * mangles anything cannot exercise the code that copes with it.</p>
 */
public final class CgInMemoryTransport<T> implements CgTransport<T> {

    private CgInMemoryTransport<T> peer;
    private Consumer<T> receiver;

    private final Deque<T> inbox = new ArrayDeque<>();
    private final List<T> sent = new ArrayList<>();

    private int dropCount = 0;
    @Nullable
    private UnaryOperator<T> corruptNext = null;

    private CgInMemoryTransport() {
    }

    /** Two transports, each delivering into the other. */
    public static <T> CgInMemoryTransport<T>[] pair() {
        CgInMemoryTransport<T> a = new CgInMemoryTransport<>();
        CgInMemoryTransport<T> b = new CgInMemoryTransport<>();
        a.peer = b;
        b.peer = a;
        @SuppressWarnings("unchecked")
        CgInMemoryTransport<T>[] both = new CgInMemoryTransport[]{a, b};
        return both;
    }

    @Override
    public void send(T encodedPacket) {
        sent.add(encodedPacket);
        if (dropCount > 0) {
            dropCount--;
            return;
        }
        T payload = encodedPacket;
        if (corruptNext != null) {
            payload = corruptNext.apply(payload);
            corruptNext = null;
        }
        peer.inbox.add(payload);
    }

    @Override
    public void setReceiver(Consumer<T> receiver) {
        this.receiver = receiver;
    }

    /** Hands everything queued to the receiver. Returns how many were delivered. */
    public int deliver() {
        int count = 0;
        while (!inbox.isEmpty()) {
            T packet = inbox.poll();
            if (receiver != null) receiver.accept(packet);
            count++;
        }
        return count;
    }

    /** Everything this transport was asked to send, in order — including dropped packets, since the
     * sender did send them. */
    public List<T> sent() {
        return List.copyOf(sent);
    }

    public void clearSent() {
        sent.clear();
    }

    public int pending() {
        return inbox.size();
    }

    /** Silently discards the next {@code count} sends, as a lossy link would. */
    public void dropNext(int count) {
        this.dropCount = count;
    }

    /** Mangles the next send. */
    public void corruptNext(UnaryOperator<T> mutator) {
        this.corruptNext = mutator;
    }
}
