package com.crystalgraphics.frame;

import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgHostView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * A simulation world: its subsystems and its schedule of tick functions. The client level's is {@link #client()}; an
 * editor preview or a test makes its own.
 *
 * <pre>{@code
 * // Presentation code on the client world: one function in a group, ahead of another by name
 * CgWorld.client().schedule().add(CgTickGroup.PLAYS, "mymod:spawner", dt -> spawner.tick(dt));
 *
 * // A world of your own, driven by you
 * try (CgWorld preview = new CgWorld()) {
 *     preview.add(new Cutscenes());
 *     preview.frame(view);                  // every group once, in order
 * }
 * }</pre>
 *
 * <ul>
 *   <li>The client world runs its groups once a ring frame, at the top of the first world stage's firing, before
 *       any renderer records, so what a function submits reaches the renderers of the same firing.</li>
 *   <li>A world runs on the render thread: its functions touch the GPU only in {@link CgTickGroup#SUBMIT}.</li>
 *   <li>{@link #paused} says what pause means for this world; a function runs while it holds only if it asked
 *       ({@link CgTickFunction#whilePaused}).</li>
 * </ul>
 */
public final class CgWorld implements AutoCloseable {

    private static CgWorld client;

    private final CgTickSchedule schedule = new CgTickSchedule(this);
    private final List<CgWorldSubsystem> subsystems = new ArrayList<>();
    private final boolean server;
    private BooleanSupplier paused = () -> false;
    private CgHostView view;
    private boolean closed;

    /** A world for a client: a preview, a test. */
    public CgWorld() {
        this(false);
    }

    /** {@code server}: a dedicated server's, which runs only functions that asked ({@link CgTickFunction#onServer}). */
    public CgWorld(boolean server) {
        this.server = server;
    }

    /**
     * The client level's world, made on first ask. It is paused with the host's game, and
     * {@code CgWorldRenderer} runs it. Render thread.
     */
    public static synchronized CgWorld client() {
        if (client == null) {
            client = new CgWorld();
            client.paused(() -> CgRenderStage.WORLD_OPAQUE.host().environment().paused());
        }
        return client;
    }

    /** Where functions are added. */
    public CgTickSchedule schedule() {
        return schedule;
    }

    /** Whether this is a dedicated server's world. */
    public boolean server() {
        return server;
    }

    /** Whether the game is paused, as this world decides it. */
    public boolean paused() {
        return paused.getAsBoolean();
    }

    /** What pause means here: the host's game pause for the client's world, never for a preview unless told. */
    public CgWorld paused(BooleanSupplier paused) {
        this.paused = paused;
        return this;
    }

    /** The host's camera for the frame being run; null before the first. */
    public CgHostView view() {
        return view;
    }

    /** Adds {@code subsystem} and initializes it. Returns it. */
    public synchronized <T extends CgWorldSubsystem> T add(T subsystem) {
        if (closed) throw new IllegalStateException("world is closed");
        subsystems.add(subsystem);
        subsystem.attach(this);
        return subsystem;
    }

    /** The subsystem of {@code type}, or null. */
    public synchronized <T extends CgWorldSubsystem> T subsystem(Class<T> type) {
        for (CgWorldSubsystem s : subsystems) if (type.isInstance(s)) return type.cast(s);
        return null;
    }

    /** Runs every group once, in order, under {@code view}: the host's camera for this frame. */
    public void frame(CgHostView view) {
        this.view = view;
        schedule.run();
    }

    /** Deinitializes the subsystems, latest first. */
    @Override
    public void close() {
        List<CgWorldSubsystem> ending;
        synchronized (this) {
            if (closed) return;
            closed = true;
            ending = new ArrayList<>(subsystems);
            subsystems.clear();
        }
        Collections.reverse(ending);
        for (CgWorldSubsystem s : ending) s.deinitialize();
    }
}
