package com.crystalgraphics.job.reply;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The producer's half of a {@link CgStream}: {@link #emit} each piece, then {@link #finish} once.
 *
 * <pre>{@code
 * CgPendingStream<Entry> listing = new CgPendingStream<>(request::abort);
 * connection.onPage(page -> page.forEach(listing::emit));
 * connection.onEnd(listing::finish);
 * return listing;
 * }</pre>
 *
 * Settles with every piece emitted. Settle on the owning thread, as {@link CgPendingReply}.
 */
public final class CgPendingStream<T> implements CgStream<T> {

    private final CgPendingReply<List<T>> settlement;
    private final List<T> pieces = new ArrayList<>();
    private final List<Consumer<? super T>> onPartial = new ArrayList<>(2);

    public CgPendingStream(@Nullable Runnable onCancel) {
        this.settlement = new CgPendingReply<>(onCancel);
    }

    /** One piece, in order. Ignored once settled. */
    public void emit(T piece) {
        if (settlement.isDone()) return;
        pieces.add(piece);
        for (Consumer<? super T> listener : new ArrayList<>(onPartial)) listener.accept(piece);
    }

    /** No more pieces: settles with all of them. */
    public void finish() {
        onPartial.clear();
        settlement.resolve(Collections.unmodifiableList(new ArrayList<>(pieces)));
    }

    /** Settles with a failure; pieces already delivered stay delivered. */
    public boolean fail(CgReplyError reason) {
        onPartial.clear();
        return settlement.fail(reason);
    }

    /** How many pieces have arrived. */
    public int emitted() {
        return pieces.size();
    }

    @Override
    public CgStream<T> onPartial(Consumer<? super T> each) {
        for (T already : new ArrayList<>(pieces)) each.accept(already);
        if (!settlement.isDone()) onPartial.add(each);
        return this;
    }

    @Override
    public CgReply<List<T>> then(Consumer<? super List<T>> onResult) {
        settlement.then(onResult);
        return this;
    }

    @Override
    public CgReply<List<T>> onError(Consumer<? super CgReplyError> onError) {
        settlement.onError(onError);
        return this;
    }

    @Override
    public CgReply<List<T>> always(Runnable whenSettled) {
        settlement.always(whenSettled);
        return this;
    }

    @Override
    public <U> CgReply<U> map(Function<? super List<T>, ? extends U> mapper) {
        return settlement.map(mapper);
    }

    @Override
    public void cancel() {
        onPartial.clear();
        settlement.cancel();
    }

    @Override
    public boolean isDone() {
        return settlement.isDone();
    }

    @Override
    @Nullable
    public List<T> result() {
        return settlement.result();
    }

    @Override
    @Nullable
    public CgReplyError error() {
        return settlement.error();
    }

    @Override
    public String toString() {
        return "CgStream(" + pieces.size() + " pieces, " + (isDone() ? "settled" : "open") + ")";
    }
}
