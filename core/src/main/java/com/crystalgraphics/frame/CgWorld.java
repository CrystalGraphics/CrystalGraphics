package com.crystalgraphics.frame;

import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.platform.world.CgWorldBegin;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

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
 *   <li>The client world runs its groups once a frame in two parts: {@link CgTickGroup#BEGIN} through
 *       {@link CgTickGroup#SHAKE} from the host's camera setup ({@link #begin}, {@code WORLD_BEGIN}), so a shake moves
 *       this frame's camera; {@link CgTickGroup#LATE_ATTACH} and {@link CgTickGroup#SUBMIT} at the first world stage's
 *       firing ({@link #frame(CgHostView, long)}), before any renderer records.</li>
 *   <li>A firing with no {@code begin} this frame (a host whose hook is not wired, a mod drawing frames of its own)
 *       runs the first part there, late, counted as {@code frame.begin-late}.</li>
 *   <li>A world runs on the render thread: its functions touch the GPU only in {@link CgTickGroup#SUBMIT}.</li>
 *   <li>{@link #paused} says what pause means for this world; a function runs while it holds only if it asked
 *       ({@link CgTickFunction#whilePaused}).</li>
 * </ul>
 */
public final class CgWorld implements AutoCloseable {

    private static final int BEGIN_LATE = CgTrace.name("frame.begin-late");
    /** A game tick, the host's twentieth of a second. */
    public static final double TICK_SECONDS = 0.05;

    private static CgWorld client;
    /** The running server's: its tick thread's. */
    private static CgWorld serverWorld;

    private final CgTickSchedule schedule = new CgTickSchedule(this);
    private final List<CgWorldSubsystem> subsystems = new ArrayList<>();
    private final boolean server;
    private BooleanSupplier paused = () -> false;
    private CgHostView view;
    private boolean closed;
    /** The frame {@link #begin} last ran for; -1 for none. */
    private long begun = -1;
    private long beganLate;
    private long ticks, frames;
    private volatile float timeDilation = 1f;

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

    /**
     * The running server's world, dedicated or integrated, made on first ask and ticked by its server from then on; it
     * ends with the session. Its functions run only if they say {@code onServer(true)}. Server thread.
     *
     * <pre>{@code
     * CgWorld.serverWorld().schedule().add(CgTickGroup.AUTHORITY, "mymod:referee", dt -> referee.tick(dt)).onServer(true);
     * }</pre>
     */
    public static synchronized CgWorld serverWorld() {
        if (serverWorld == null) {
            serverWorld = new CgWorld(true);
            if (!driving) {
                driving = true;
                CgNetwork.onServerTick(CgWorld::tickServer);
                CgNetwork.onSessionEnd(CgWorld::endServer);
            }
        }
        return serverWorld;
    }

    private static boolean driving;

    private static void tickServer() {
        CgWorld w;
        synchronized (CgWorld.class) {
            w = serverWorld;
        }
        if (w != null) w.tick();
    }

    private static void endServer() {
        CgWorld w;
        synchronized (CgWorld.class) {
            w = serverWorld;
            serverWorld = null;
        }
        if (w != null) w.close();
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

    /**
     * Scales the time of everything on this world: 0.25 is slow motion, 0 holds it. Unreal's {@code TimeDilation}; the
     * host's pause and tick rate apply on top, and a character's own clock under it.
     *
     * <pre>{@code
     * CgWorld.client().timeDilation(0.25f);
     * }</pre>
     */
    public CgWorld timeDilation(float dilation) {
        timeDilation = Math.max(0f, dilation);
        return this;
    }

    public float timeDilation() {
        return timeDilation;
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

    /** Runs every frame group once, in order, under {@code view}: the host's camera for this frame. A preview's, a test's. */
    public void frame(CgHostView view) {
        this.view = view;
        frames++;
        schedule.run();
    }

    /**
     * One game tick: the tick groups {@link CgTickGroup#NET_IN} through {@link CgTickGroup#NET_OUT}, each function's
     * {@code dt} on the tick clock, a twentieth of a second a tick. A server's world is ticked by its server.
     */
    public void tick() {
        ticks++;
        schedule.run(CgTickGroup.NET_IN, CgTickGroup.NET_OUT, ticks * TICK_SECONDS);
    }

    /** Game ticks run. */
    public long ticks() {
        return ticks;
    }

    /** Frames whose groups ran; always 0 for a dedicated server's. */
    public long frames() {
        return frames;
    }

    /**
     * {@code WORLD_BEGIN}: the groups {@link CgTickGroup#BEGIN} through {@link CgTickGroup#SHAKE}, once for
     * {@code frame}, under {@code view} as it stands: last frame's camera, since the host has not taken this one yet.
     */
    public void begin(CgHostView view, long frame) {
        if (frame == begun) return;
        begun = frame;
        frames++;
        this.view = view;
        schedule.run(CgTickGroup.BEGIN, CgTickGroup.SHAKE);
    }

    /**
     * The first world stage's firing of {@code frame}: {@link CgTickGroup#LATE_ATTACH} and {@link CgTickGroup#SUBMIT}
     * under this frame's {@code view}, after the first part if no camera hook ran it.
     */
    public void frame(CgHostView view, long frame) {
        if (frame != begun) {
            if (!CgWorldBegin.isAtStage()) {
                beganLate++;
                CgTrace.counter(CgChannels.WORLD, BEGIN_LATE, beganLate);
            }
            begin(view, frame);
        }
        this.view = view;
        schedule.run(CgTickGroup.LATE_ATTACH, CgTickGroup.SUBMIT);
    }

    /** Frames whose first part ran at a stage, not at a camera hook, where the host has hooks. */
    public long beganLate() {
        return beganLate;
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
