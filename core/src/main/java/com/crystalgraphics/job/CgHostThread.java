package com.crystalgraphics.job;

import com.crystalgraphics.job.reply.CgPendingReply;
import com.crystalgraphics.job.reply.CgReply;
import com.crystalgraphics.job.reply.CgReplyError;
import com.crystalgraphics.platform.service.CgGameThreads;
import com.crystalgraphics.platform.service.CgGameThreads.Binding;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The game's threads, as UI code reaches them. A UI may run on a thread of its own, while a mod's world, player and
 * inventories live on the game's; this is how UI code runs work there and gets the answer back.
 *
 * <pre>{@code
 * CgHostThread.CLIENT.run(() -> player.drop(stack, false));                  // fire and forget, on the client thread
 *
 * CgHostThread.SERVER.call(() -> level.getBlockState(pos))                   // a CgReply...
 *         .then(state -> label.setText(state.getBlock().getName()));      // ...answered on the UI's own thread
 * }</pre>
 *
 * <p>For a value read every frame, a widget's {@code extract} is the convenient form:</p>
 *
 * <pre>{@code
 * extract(() -> player.getHealth(), bar::setHealth);                       // CLIENT unless told otherwise
 * }</pre>
 *
 * <ul>
 *   <li>Asked from the thread itself, the work runs at once and a {@link #call} is answered before it returns.</li>
 *   <li>Otherwise it goes to the thread's own queue, and the answer comes back to the thread that asked: a document's
 *       sequence, or the host's frame thread.</li>
 *   <li>{@link #SERVER} is only the integrated server of single player. Connected to a remote server there is none:
 *       {@link #isAvailable()} says so, {@link #run} drops the work with a warning, {@link #call} fails its reply.</li>
 *   <li>The work must not touch a widget: it runs on the game's thread. Hand the answer back and touch it there.</li>
 * </ul>
 */
public enum CgHostThread {

    /** The game's client thread: its world, its player, its screens. Also where frames are drawn, on every version so far. */
    CLIENT,

    /** Where the host draws frames, and documents are framed: the camera, interpolated positions, what is on screen. */
    RENDER,

    /** The integrated server's thread in single player: the authoritative world. None on a remote connection. */
    SERVER;

    /**
     * A thread a host drains itself: work queued from anywhere, run when its owner calls {@link #drain()} -- a game
     * whose thread has no task queue of its own (1.7.10's server tick, the frame a document is drawn in).
     *
     * <pre>{@code
     * CgHostThread.Queue serverTasks = new CgHostThread.Queue(() -> server != null);
     * // the server's tick handler, on the server thread
     * serverTasks.drain();
     * }</pre>
     */
    public static final class Queue implements Binding {

        private final ConcurrentLinkedQueue<Runnable> work = new ConcurrentLinkedQueue<>();
        private final BooleanSupplier available;
        @Nullable
        private volatile Thread owner;

        public Queue(BooleanSupplier available) {
            this.available = available;
        }

        public Queue() {
            this(() -> true);
        }

        /** Runs what was queued, on the calling thread, which this queue then answers {@link #isCurrent()} for. */
        public void drain() {
            owner = Thread.currentThread();
            for (Runnable next; (next = work.poll()) != null; ) next.run();
        }

        @Override
        public void execute(Runnable runnable) {
            work.add(runnable);
        }

        @Override
        public boolean isCurrent() {
            return Thread.currentThread() == owner;
        }

        @Override
        public boolean isAvailable() {
            return available.getAsBoolean();
        }
    }

    /** Where documents are framed: drained by every host frame before its documents run. */
    private static final Queue FRAMES = new Queue();

    private volatile boolean unavailableReported;

    /** Where an answer goes back to when the asker is in a context of its own, or null: a UI's sequence. */
    private static volatile Supplier<Binding> answerThrough = () -> null;

    /**
     * Routes {@link #call} answers to the asker's own context when it has one: a UI registers its sequence here, so a
     * {@code then} runs where it may touch the tree. The supplier answers null outside such a context.
     */
    public static void answerThrough(Supplier<Binding> current) {
        answerThrough = current;
    }

    private Binding bound() {
        return this == CLIENT ? CgGameThreads.client() : this == SERVER ? CgGameThreads.server() : null;
    }

    /**
     * Runs the work queued for the frame thread. Called once a host frame, first, on the thread that frames documents
     * -- whether or not one is framed that frame, since an answer to UI code may be waiting there.
     */
    public static void drainFrames() {
        FRAMES.drain();
    }

    private Binding resolved() {
        Binding bound = bound();
        if (bound != null) return bound;
        // A host that names no client thread draws on it: the harness, a test.
        return this == SERVER ? NONE : FRAMES;
    }

    /** Whether the calling thread is this one, so work for it would run at once. */
    public boolean isCurrent() {
        Binding bound = resolved();
        // Before any frame has run there is no frame thread yet: the caller is the only candidate.
        if (bound == FRAMES && FRAMES.owner == null) return true;
        return bound.isCurrent();
    }

    /** Whether the thread exists now. False for {@link #SERVER} on a remote connection. */
    public boolean isAvailable() {
        return resolved().isAvailable();
    }

    /** Runs {@code work} on this thread: now, if this is it; otherwise as soon as it next takes work. */
    public void run(Runnable work) {
        if (!isAvailable()) {
            reportUnavailable();
            return;
        }
        if (isCurrent()) work.run();
        else resolved().execute(work);
    }

    /**
     * Runs {@code work} on this thread and answers its result on the calling thread -- where a document runs, so its
     * {@code then} may touch the tree. Answered before it returns when called from the thread itself.
     */
    public <T> CgReply<T> call(Supplier<T> work) {
        if (!isAvailable()) return CgReply.failed(new CgReplyError(UNAVAILABLE, name() + " has no thread here"));
        if (isCurrent()) {
            try {
                return CgReply.of(work.get());
            } catch (RuntimeException failed) {
                return CgReply.failed(CgReplyError.failed(failed));
            }
        }
        CgPendingReply<T> reply = new CgPendingReply<>(null);
        Binding back = answerTo();
        resolved().execute(() -> {
            T value;
            try {
                value = work.get();
            } catch (RuntimeException failed) {
                back.execute(() -> reply.fail(CgReplyError.failed(failed)));
                return;
            }
            back.execute(() -> reply.resolve(value));
        });
        return reply;
    }

    private static final Logger LOGGER = LogManager.getLogger(CgHostThread.class);

    private static final CgHostThread[] GAME_THREADS = {CLIENT, SERVER};

    /** A {@link CgReplyError} code: the thread does not exist here, a server that is not running. */
    public static final String UNAVAILABLE = "UNAVAILABLE";

    /**
     * Where an answer goes back to: the calling document's sequence; else the game thread the caller is on, which
     * takes work whether or not a document is being framed; else the frame thread.
     */
    private static Binding answerTo() {
        Binding own = answerThrough.get();
        if (own != null) return own;
        for (CgHostThread thread : GAME_THREADS) {
            Binding bound = thread.bound();
            if (bound != null && bound.isAvailable() && bound.isCurrent()) return bound;
        }
        return FRAMES;
    }

    private void reportUnavailable() {
        if (unavailableReported) return;
        unavailableReported = true;
        LOGGER.warn("[cg] work for the {} thread was dropped: there is none here", name());
    }

    private static final Binding NONE = new Binding() {
        @Override
        public void execute(Runnable work) {
        }

        @Override
        public boolean isCurrent() {
            return false;
        }

        @Override
        public boolean isAvailable() {
            return false;
        }
    };
}
