package com.crystalgraphics.job.reply;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * An answer that is not here yet: a job on a worker, a call across a wire. One shape, so answers compose.
 *
 * <pre>{@code
 * CgJob.run(() -> parse(text))                    // a CgJob is a CgReply
 *      .then(tree -> editor.show(tree))
 *      .onError(e -> status.show(e.detail()))
 *      .always(spinner::hide);
 * }</pre>
 *
 * Several at once, and a view over one:
 *
 * <pre>{@code
 * CgReply.all(List.of(loadIcons, loadFonts)).then(() -> open());
 * CgReply<Integer> lines = CgJob.run(() -> read(file)).map(String::length);
 * }</pre>
 *
 * <ul>
 *   <li>Listeners run on the thread that owns the reply: for a job, the thread it reports back to, at its drain.
 *       Not thread-safe, deliberately: register and cancel from that thread.</li>
 *   <li>A listener registered after the reply settled runs at once, so a caller never depends on whether the work
 *       happened to finish first.</li>
 *   <li>{@link #cancel()} settles it with {@link CgReplyError#CANCELLED}, so {@link #always} still runs and a batch
 *       waiting on it still completes.</li>
 * </ul>
 */
public interface CgReply<T> {

    /** The value arrived. */
    CgReply<T> then(Consumer<? super T> onResult);

    /** The value arrived, when only that it did matters. */
    default CgReply<T> then(Runnable onDone) {
        Objects.requireNonNull(onDone, "onDone");
        return then(ignored -> onDone.run());
    }

    /** It did not: failed, cancelled or timed out. {@link CgReplyError#code()} says which. */
    CgReply<T> onError(Consumer<? super CgReplyError> onError);

    /** Settled, either way. */
    CgReply<T> always(Runnable whenSettled);

    /** A view over this reply's value. Cancelling the view cancels this reply. */
    <U> CgReply<U> map(Function<? super T, ? extends U> mapper);

    /**
     * The reply {@code next} makes of this one's value, as one reply: a job started on an answer that is not here yet.
     *
     * <pre>{@code
     * CgCollisionWorld.of(level).snapshot(x, y, z, 24)            // CgReply<CgCollisionWorld>
     *         .flatMap(area -> CgJob.run(() -> simulate(area)))   // CgReply<List<Debris>>: a worker steps the copy
     *         .then(next -> pieces = next);                       // back on this thread
     * }</pre>
     *
     * <ul>
     *   <li>{@code next} runs where this reply's listeners run, with its value; a throw fails the result.</li>
     *   <li>Cancelling the result cancels this reply, and the one {@code next} made once there is one.</li>
     * </ul>
     */
    default <U> CgReply<U> flatMap(Function<? super T, ? extends CgReply<? extends U>> next) {
        Objects.requireNonNull(next, "next");
        List<CgReply<?>> made = new ArrayList<>(1);
        CgPendingReply<U> joined = new CgPendingReply<>(() -> {
            cancel();
            for (CgReply<?> r : made) r.cancel();
        });
        onError(joined::fail).then(value -> {
            if (joined.isDone()) return;
            CgReply<? extends U> r;
            try {
                r = next.apply(value);
            } catch (RuntimeException e) {
                joined.fail(CgReplyError.failed(e));
                return;
            }
            made.add(r);
            r.onError(joined::fail).then(joined::resolve);
        });
        return joined;
    }

    /** Stops caring and tells whoever produces the answer; settles with {@link CgReplyError#CANCELLED}. */
    void cancel();

    /** Whether it has settled, either way. */
    boolean isDone();

    /** The value, or null before it settles or when it failed. For tests; code uses {@link #then}. */
    @Nullable
    T result();

    /** The error, or null before it settles or when it answered. */
    @Nullable
    CgReplyError error();

    /** Two as one. Fails with the first error either produces; cancelling cancels both. */
    static <A, B> CgReply<Both<A, B>> both(CgReply<A> a, CgReply<B> b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        CgPendingReply<Both<A, B>> joined = new CgPendingReply<>(() -> {
            a.cancel();
            b.cancel();
        });
        Runnable check = () -> {
            if (!joined.isDone() && a.isDone() && b.isDone() && a.error() == null && b.error() == null) {
                joined.resolve(new Both<>(a.result(), b.result()));
            }
        };
        a.onError(joined::fail).then(check);
        b.onError(joined::fail).then(check);
        check.run();   // both may have settled already
        return joined;
    }

    /** All of them. Fails with the first error any produces; cancelling cancels every one. */
    static CgReply<Void> all(Collection<? extends CgReply<?>> replies) {
        List<CgReply<?>> members = new ArrayList<>(replies);
        CgPendingReply<Void> joined = new CgPendingReply<>(() -> {
            for (CgReply<?> member : members) member.cancel();
        });
        Runnable check = () -> {
            if (joined.isDone()) return;
            for (CgReply<?> member : members) {
                if (!member.isDone() || member.error() != null) return;
            }
            joined.resolve(null);
        };
        for (CgReply<?> member : members) member.onError(joined::fail).then(check);
        check.run();
        return joined;
    }

    /** An answer already here. */
    static <T> CgReply<T> of(@Nullable T value) {
        CgPendingReply<T> settled = new CgPendingReply<>(null);
        settled.resolve(value);
        return settled;
    }

    /** A failure already here. */
    static <T> CgReply<T> failed(CgReplyError error) {
        CgPendingReply<T> settled = new CgPendingReply<>(null);
        settled.fail(error);
        return settled;
    }

    /** What {@link #both} answers. */
    record Both<A, B>(@Nullable A first, @Nullable B second) {
    }
}
