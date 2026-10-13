package com.crystalgraphics.job.reply;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The producer's half of a {@link CgReply}: hand it out as a {@code CgReply}, settle it yourself.
 *
 * <pre>{@code
 * CgPendingReply<Page> reply = new CgPendingReply<>(() -> request.abort());   // what cancel tells the producer
 * connection.onPage(page -> reply.resolve(page));                            // on the owning thread
 * connection.onClose(why -> reply.fail(new CgReplyError("CLOSED", why)));
 * return reply;
 * }</pre>
 *
 * <ul>
 *   <li>Settle on the thread that owns it, where its listeners run: never from a worker.</li>
 *   <li>The second settle is ignored, so a cancel racing an answer is harmless.</li>
 *   <li>Every listener runs even if one throws; the first throw is rethrown after the rest, the others suppressed.</li>
 * </ul>
 */
public final class CgPendingReply<T> implements CgReply<T> {

    @Nullable
    private final Runnable onCancel;
    @Nullable
    private ArrayList<Consumer<? super T>> onResult;
    @Nullable
    private ArrayList<Consumer<? super CgReplyError>> onError;
    @Nullable
    private ArrayList<Runnable> onSettled;
    private boolean done;
    @Nullable
    private T value;
    @Nullable
    private CgReplyError error;

    /** @param onCancel what to tell the producer when the caller stops caring; null when there is nothing to tell */
    public CgPendingReply(@Nullable Runnable onCancel) {
        this.onCancel = onCancel;
    }

    /** Answers with a value. */
    public void resolve(@Nullable T result) {
        if (done) return;
        done = true;
        value = result;
        RuntimeException thrown = null;
        if (onResult != null) {
            for (Consumer<? super T> listener : new ArrayList<>(onResult)) {
                try {
                    listener.accept(result);
                } catch (RuntimeException e) {
                    thrown = first(thrown, e);
                }
            }
        }
        settled(thrown);
    }

    /** Answers with a failure; returns whether an {@link #onError} listener heard it, so an unheard one can be logged. */
    public boolean fail(CgReplyError reason) {
        if (done) return false;
        done = true;
        error = reason == null ? new CgReplyError(CgReplyError.FAILED, "no reason given") : reason;
        boolean heard = onError != null && !onError.isEmpty();
        RuntimeException thrown = null;
        if (heard) {
            for (Consumer<? super CgReplyError> listener : new ArrayList<>(onError)) {
                try {
                    listener.accept(error);
                } catch (RuntimeException e) {
                    thrown = first(thrown, e);
                }
            }
        }
        settled(thrown);
        return heard;
    }

    private void settled(@Nullable RuntimeException thrown) {
        if (onSettled != null) {
            for (Runnable listener : new ArrayList<>(onSettled)) {
                try {
                    listener.run();
                } catch (RuntimeException e) {
                    thrown = first(thrown, e);
                }
            }
        }
        onResult = null;
        onError = null;
        onSettled = null;
        if (thrown != null) throw thrown;
    }

    private static RuntimeException first(@Nullable RuntimeException first, RuntimeException next) {
        if (first == null) return next;
        first.addSuppressed(next);
        return first;
    }

    @Override
    public CgReply<T> then(Consumer<? super T> listener) {
        if (done) {
            if (error == null) listener.accept(value);
        } else {
            if (onResult == null) onResult = new ArrayList<>(2);
            onResult.add(listener);
        }
        return this;
    }

    @Override
    public CgReply<T> onError(Consumer<? super CgReplyError> listener) {
        if (done) {
            if (error != null) listener.accept(error);
        } else {
            if (onError == null) onError = new ArrayList<>(2);
            onError.add(listener);
        }
        return this;
    }

    @Override
    public CgReply<T> always(Runnable listener) {
        if (done) {
            listener.run();
        } else {
            if (onSettled == null) onSettled = new ArrayList<>(2);
            onSettled.add(listener);
        }
        return this;
    }

    @Override
    public <U> CgReply<U> map(Function<? super T, ? extends U> mapper) {
        CgPendingReply<U> mapped = new CgPendingReply<>(this::cancel);
        onError(mapped::fail);
        then(result -> mapped.resolve(mapper.apply(result)));
        return mapped;
    }

    @Override
    public void cancel() {
        if (done) return;
        if (onCancel != null) onCancel.run();
        // after telling the producer: one that settles its own cancellation has already won the guard in fail
        fail(CgReplyError.cancelled());
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    @Nullable
    public T result() {
        return error == null ? value : null;
    }

    @Override
    @Nullable
    public CgReplyError error() {
        return error;
    }

    @Override
    public String toString() {
        if (!done) return "CgReply(pending)";
        return error != null ? "CgReply(" + error + ")" : "CgReply(" + value + ")";
    }
}
