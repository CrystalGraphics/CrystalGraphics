package com.crystalgraphics.net.rate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

import javax.annotation.Nullable;

/**
 * Holds reports back to their {@link CgRatePolicy} and lets them go: per source and kind, the latest value replaces
 * the one held, and the held one always leaves eventually.
 *
 * <pre>{@code
 * CgRateGate<Slider, Float> gate = new CgRateGate<>((slider, kind, value) -> send(kind, value));
 * gate.offer(slider, "value", CgRatePolicy.DRAGGING, 0.4f);   // leaves now, or is held
 * connection.onTick(gate::flush);                            // what expires a debounce
 * gate.forget(slider);                                       // the source has gone: its held value is sent
 * }</pre>
 *
 * <ul>
 *   <li><b>{@link #flush()} must be driven</b>, or a debounced value with nothing after it is held for good.</li>
 *   <li>An immediate policy goes straight to the sink and allocates nothing.</li>
 *   <li>Not thread-safe: driven from the thread that owns its sources.</li>
 *   <li>The clock is wall time unless {@link #setClock} hands over a tick clock, in milliseconds.</li>
 * </ul>
 *
 * @param <N> the source a report comes from
 * @param <P> what the sink is handed
 */
public final class CgRateGate<N, P> {

    /** Where a report goes once its policy lets it through. */
    @FunctionalInterface
    public interface Sink<N, P> {
        void send(N source, String kind, @Nullable P payload);
    }

    /** A report waiting for its policy. {@code held} and {@code sentOnce} are flags, as a payload may be null. */
    private static final class Pending<P> {
        @Nullable P payload;
        boolean held;
        long at;
        long sent;
        boolean sentOnce;
        CgRatePolicy policy = CgRatePolicy.IMMEDIATE;
    }

    private final Sink<N, P> sink;
    private final Map<N, Map<String, Pending<P>>> pending = new LinkedHashMap<>();
    private LongSupplier clock = System::currentTimeMillis;

    public CgRateGate(Sink<N, P> sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /** Where "now" comes from, in milliseconds. */
    public CgRateGate<N, P> setClock(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        return this;
    }

    /** Offers a report, which leaves now or waits, as {@code policy} says; a null policy is immediate. */
    public void offer(N source, String kind, @Nullable CgRatePolicy policy, @Nullable P payload) {
        if (policy == null || policy.isImmediate()) {
            sink.send(source, kind, payload);
            return;
        }
        Pending<P> slot = pending.computeIfAbsent(source, s -> new LinkedHashMap<>()).computeIfAbsent(kind, k -> new Pending<>());
        slot.payload = payload;
        slot.held = true;
        slot.at = clock.getAsLong();
        slot.policy = policy;
        flush();
    }

    /** Sends whatever has waited long enough. */
    public void flush() {
        if (pending.isEmpty()) return;
        long now = clock.getAsLong();
        for (Map.Entry<N, Map<String, Pending<P>>> bySource : pending.entrySet()) {
            for (Map.Entry<String, Pending<P>> entry : bySource.getValue().entrySet()) {
                Pending<P> slot = entry.getValue();
                if (!slot.held) continue;
                boolean due = slot.policy.debounceMillis() > 0
                        ? now - slot.at >= slot.policy.debounceMillis()
                        : !slot.sentOnce || now - slot.sent >= slot.policy.throttleMillis();
                if (!due) continue;
                slot.sent = now;
                slot.sentOnce = true;
                release(bySource.getKey(), entry.getKey(), slot);
            }
        }
    }

    /** Sends everything held, whatever its policy says, and forgets every source. What a teardown does. */
    public void commit() {
        for (Map.Entry<N, Map<String, Pending<P>>> bySource : pending.entrySet()) {
            for (Map.Entry<String, Pending<P>> entry : bySource.getValue().entrySet()) {
                if (entry.getValue().held) release(bySource.getKey(), entry.getKey(), entry.getValue());
            }
        }
        pending.clear();
    }

    /** Forgets a source, sending anything it still holds: its last value does not stop being its last. */
    public void forget(N source) {
        Map<String, Pending<P>> slots = pending.remove(source);
        if (slots == null) return;
        for (Map.Entry<String, Pending<P>> entry : slots.entrySet()) {
            if (entry.getValue().held) release(source, entry.getKey(), entry.getValue());
        }
    }

    /** Forgets a source and drops what it holds: for one whose reports can no longer mean anything. */
    public void discard(N source) {
        pending.remove(source);
    }

    /** Whether anything is waiting. */
    public boolean isHolding() {
        for (Map<String, Pending<P>> slots : pending.values()) {
            for (Pending<P> slot : slots.values()) if (slot.held) return true;
        }
        return false;
    }

    private void release(N source, String kind, Pending<P> slot) {
        P payload = slot.payload;
        slot.payload = null;
        // Cleared before the send, so a sink that offers back into this gate cannot release the slot twice.
        slot.held = false;
        sink.send(source, kind, payload);
    }
}
