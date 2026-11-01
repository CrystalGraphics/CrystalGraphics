package com.crystalgraphics.vfx;

import com.crystalgraphics.frame.CgTickFunction;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.lifecycle.CgLifecycleListener;
import com.crystalgraphics.net.object.CgCharacterClockNet;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.world.CgWorldQuery;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.world.CgPipelinePrecache;
import com.crystalgraphics.vfx.particle.gpu.draw.CgVfxRange;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.vfx.camera.CgCameraShake;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.net.CgVfxCueClient;
import com.crystalgraphics.vfx.prefab.CgVfxPrefabAssets;
import com.crystalgraphics.vfx.prefab.CgVfxPrefabPreparer;
import com.crystalgraphics.vfx.system.CgVfxCueEffects;
import com.crystalgraphics.vfx.system.CgVfxSystemAssets;
import com.crystalgraphics.vfx.system.CgVfxSystemPreparer;
import com.crystalgraphics.asset.kind.CgBodyKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.audio.CgAudioEngine;
import com.crystalgraphics.sequence.sound.CgSequenceSoundPreparer;
import com.crystalgraphics.render.post.volume.CgPostVolumePreparer;
import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.world.CgWorldQueries;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.variable.CgVar;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.crystalgraphics.render.graph.CgRehearsal;
import org.joml.Vector3d;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The client world's VFX: one {@link CgVfxSystem} every mod plays into, through an owner handle. The engine updates and
 * draws it each world frame on every host, and clears it when the world goes: logout, disconnect, a dimension change.
 *
 * <pre>{@code
 * static final CgVfxOwner VFX = CgVfx.owner("mymod");   // once
 * VFX.play(new HitSpark(HitSpark.LOOK, x, y, z));       // anywhere in the client
 *
 * // A preview viewport, a test or a harness scene keeps a system of its own, updated and drawn by its caller:
 * CgVfxSystem preview = new CgVfxSystem();
 * CgVfxOwner lab = CgVfx.owner("mymod", preview);
 * }</pre>
 *
 * <ul>
 *   <li>Played effects start at the next world frame, in the {@code EFFECTS} group: a play queued by an earlier group
 *       of the same frame ({@code PLAYS}, {@code SEQUENCES}) starts that frame; one from a later group, a frame after.</li>
 *   <li>A dimension change or a new level clears it; warmed programs and meshes stay. Effects played in the tick that
 *       changed the level are kept.</li>
 *   <li>{@link #world()} is for what the engine owns (a HUD, a dev tool); a mod plays through its owner.</li>
 * </ul>
 */
public final class CgVfx {

    private static final ConcurrentHashMap<String, CgVfxOwner> OWNERS = new ConcurrentHashMap<>();
    /** {@link #OWNERS}' values, walked by index each frame. */
    private static final CopyOnWriteArrayList<CgVfxOwner> WORLD_OWNERS = new CopyOnWriteArrayList<>();
    private static CgVfxSystem world;
    private static boolean installed;
    private static int epoch;

    private static final Logger LOGGER = LogManager.getLogger("CgVfx");
    private static final CgVar.Bool REHEARSE = CgVar.bool("crystalgraphics.vfx.rehearse", true, CgVar.DEV | CgVar.RESTART,
            "Plays every precached registered effect, from the first frame, and every system asset before it is READY in a "
                    + "hidden system, so their first play runs no cold Java (class loading, first runs).");
    private static final CgVar.Int REHEARSAL_PASSES = CgVar.integer("crystalgraphics.vfx.rehearsalPasses", 60, 1, 20_000,
            CgVar.DEV | CgVar.RESTART, "Hidden frames a rehearsal runs at least (500 at most); it stops at the first one "
                    + "after them that loads no class and compiles nothing.");
    /** How far ahead of the camera the hidden effects play: in view, so culling keeps their draw paths. */
    private static final double REHEARSAL_AHEAD = 6.0;
    private static boolean rehearsalStarted;

    private CgVfx() {
    }

    /** The owner {@code namespace} of the world's system: the same handle each call. */
    public static CgVfxOwner owner(String namespace) {
        CgVfxOwner owner = OWNERS.get(namespace);
        return owner != null ? owner : OWNERS.computeIfAbsent(namespace, ns -> {
            CgVfxOwner made = new CgVfxOwner(ns, null);
            WORLD_OWNERS.add(made);
            return made;
        });
    }

    /** An owner over a system of the caller's, which the caller updates, draws and deletes. */
    public static CgVfxOwner owner(String namespace, CgVfxSystem system) {
        if (system == null) throw new IllegalArgumentException("system");
        return new CgVfxOwner(namespace, system);
    }

    /** The client world's system, made on first ask. Render thread. */
    public static CgVfxSystem world() {
        if (world == null) world = new CgVfxSystem().world(CgWorld.client());
        return world;
    }

    /**
     * Registers on {@code assets} what makes a client's assets READY with nothing left for a play to build: systems and
     * prefabs warmed on {@code vfx}, sequences' sounds decoded into the client world's audio and their post materials'
     * textures and programs made ready. {@link #install} does it
     * for the process's assets; an assets instance of a tool's or a scene's own does it too.
     *
     * <pre>{@code
     * CgVfxAssets assets = new CgVfxAssets(registry);
     * CgVfx.preparers(assets, () -> myVfx);
     * }</pre>
     */
    public static void preparers(CgVfxAssets assets, Supplier<CgVfxSystem> vfx) {
        assets.preparer(CgParticleKinds.SYSTEM, new CgVfxSystemPreparer(vfx));
        assets.preparer(CgBodyKinds.PREFAB, new CgVfxPrefabPreparer(vfx));
        assets.preparer(CgSequenceKinds.SEQUENCE,
                new CgSequenceSoundPreparer(() -> CgWorld.client().subsystem(CgAudioEngine.class)));
        assets.preparer(CgSequenceKinds.SEQUENCE, new CgPostVolumePreparer());
    }

    /** Updates and draws the world's system each world frame from now on, and clears it with the level. Once. */
    public static void install() {
        if (installed) return;
        installed = true;
        CgWorld.client().schedule().add(CgTickGroup.SCALABILITY, "crystalgraphics:vfx.scalability",
                dt -> world().scalability(CgFrameClock.seconds())).whilePaused(true);
        CgTickFunction effects = CgWorld.client().schedule().add(CgTickGroup.EFFECTS, "crystalgraphics:vfx", dt -> effects())
                .whilePaused(true);
        CgWorld.client().schedule().add(CgTickGroup.LATE_ATTACH, "crystalgraphics:vfx.lateAttach", dt -> world().lateAttach())
                .after(effects).whilePaused(true);
        CgWorld.client().schedule().add(CgTickGroup.SUBMIT, "crystalgraphics:vfx.submit", dt -> submit())
                .after(effects).whilePaused(true);
        CgVfxCueClient.install();
        CgCameraShake.schedule();
        // Range and Place draw every pool's particles: built while loading, not at the first burst.
        CgPipelinePrecache.register("crystalgraphics:vfx", pipelines -> pipelines.kernels(CgVfxRange::precache));
        // Characters' clocks the server sends, kept on the level's tick: the effects following them hold and slow.
        CgCharacterClockNet.install(() -> CgRenderStage.WORLD_OPAQUE.host().environment().gameTime());
        CgVfxSystemAssets.register();   // system assets compile to playable definitions
        CgVfxPrefabAssets.register();
        // and are READY once the world's system has warmed what they draw: nothing compiles or uploads at a play
        preparers(CgVfxAssets.instance(), CgVfx::world);
        // The client world's cue manager: what the server's cues and gameplay's tags play through
        CgVfxCueEffects.installClient(world());
        // Every frame's end, the title screen's too: a logout clears before the next world; looks declared since precache.
        CgEngineLifecycle.addListener(new CgLifecycleListener() {
            @Override
            public void onFrame(long frame) {
                if (world != null) followLevel();
                precachePending();
                // From the first frame, the title screen's: done before a world's first play.
                if (!rehearsalStarted) rehearseRegistry(world());
            }
        });
        // What mods declared and asked for at init, built with the graphics start's own, inside the loading screen.
        precachePending();
    }

    /** Whether a precache may run here and now: the engine installed, on the thread that drives the device. */
    static boolean mayPrecache() {
        return installed && CgGL.mayIssueGl();
    }

    /**
     * Precaches what was declared or asked for before it could be: every look a cue or {@link CgVfxRegistry} entry
     * plays (unless it opted out), on the world's system, and every owner's queued {@code precache}. Render thread.
     */
    static void precachePending() {
        CgVfxOwner.precachePending();
        List<CgVfxLook> looks = CgVfxRegistry.takePrecache();
        if (!looks.isEmpty()) world().precache(looks.toArray(new CgVfxLook[0]));
    }

    /** Frees the world's system with the context; the next one makes another. */
    public static void release() {
        if (world != null) world.delete();
        world = null;
    }

    /** Starts what owners queued, since the last frame and by earlier groups this one, then steps the system. */
    private static void effects() {
        CgVfxSystem vfx = world();
        followLevel();
        for (int i = 0; i < WORLD_OWNERS.size(); i++) WORLD_OWNERS.get(i).drain(vfx);
        vfx.update(CgFrameClock.seconds());
    }

    private static void submit() {
        world().submit(CgWorldRenderer.get());
    }

    /**
     * The engine's rehearsal, once, from the first frame (the title screen's): every registered effect precached plays
     * in a hidden system ahead of the camera, drawn into a scratch target, a few frames a frame under the warm budget
     * once their precache is done, until a frame past {@link #REHEARSAL_PASSES} loads no class and compiles nothing.
     * Their first real play then runs no class loading and no first run of the engine's paths: what was a 50-70 ms
     * hitch.
     */
    private static void rehearseRegistry(CgVfxSystem vfx) {
        rehearsalStarted = true;
        List<CgVfxRegistry.Entry> entries = new ArrayList<>();
        for (CgVfxRegistry.Entry entry : CgVfxRegistry.entries()) if (entry.precached()) entries.add(entry);
        if (entries.isEmpty()) return;
        Vector3d at = new Vector3d();
        Vector3f forward = new Vector3f();
        rehearse(vfx, hidden -> {
            rehearsalPoint(at, forward);
            for (int i = entries.size() - 1; i >= 0; i--) {
                CgVfxRegistry.Entry entry = entries.get(i);
                try {
                    hidden.play(entry.spawn(entry.look(), at.x, at.y, at.z, at.x + forward.x, at.y + forward.y, at.z + forward.z));
                } catch (RuntimeException e) {
                    // One effect that cannot play out of context costs only its own warm-up.
                    LOGGER.warn("[CgVfx] '{}' cannot be rehearsed; its first play warms as it plays", entry.id(), e);
                    entries.remove(i);
                }
            }
        });
    }

    /**
     * Asks {@code vfx} for a rehearsal of {@code cast} at the engine's length ({@code crystalgraphics.vfx.rehearsalPasses}
     * at least, 500 at most, ending at the first pass that loads no class and compiles nothing); nothing while
     * {@code crystalgraphics.vfx.rehearse} is off. What the registry's effects and a system asset's preparer warm the
     * JIT with before their first play. Render thread.
     *
     * <pre>{@code
     * Vector3d at = new Vector3d();
     * CgRehearsal r = CgVfx.rehearse(vfx, hidden -> {   // again each time its effects end
     *     CgVfx.rehearsalPoint(at, null);
     *     hidden.spawn(ref, at.x, at.y, at.z);
     * });
     * if (r == null || r.done()) play();                // each frame
     * }</pre>
     *
     * @return the rehearsal it ran in or joined ({@link CgVfxSystem#rehearse}); null while rehearsals are off
     */
    @Nullable
    public static CgRehearsal rehearse(CgVfxSystem vfx, Consumer<CgVfxSystem> cast) {
        if (!REHEARSE.get()) return null;
        int passes = REHEARSAL_PASSES.get();
        CgRehearsal rehearsal = vfx.rehearse(cast);
        // Counted from now: a cast joining a running rehearsal gets its own passes.
        int from = rehearsal.passes();
        return rehearsal.minPasses(from + passes).maxPasses(from + Math.max(passes, 500)).window(1);
    }

    /**
     * Where a rehearsal plays: six blocks ahead of the world's camera as of its latest frame, so
     * no scalability culls it; {@code forward} (if given) its view direction. Answers {@code at}.
     */
    public static Vector3d rehearsalPoint(Vector3d at, @Nullable Vector3f forward) {
        CgHostView view = CgRenderStage.WORLD_OPAQUE.host().view();
        Vector3f f = forward != null ? forward : new Vector3f();
        view.view().positiveZ(f).negate();
        return at.set(view.x() + f.x * REHEARSAL_AHEAD, view.y() + f.y * REHEARSAL_AHEAD, view.z() + f.z * REHEARSAL_AHEAD);
    }

    /** Clears the world's system when the level changed; when there is none any more, what waits to play too. */
    private static void followLevel() {
        int now = CgWorldQueries.client().levelEpoch();
        if (now == epoch) return;
        epoch = now;
        world.clear();
        if (now == 0) {
            for (int i = 0; i < WORLD_OWNERS.size(); i++) WORLD_OWNERS.get(i).dropPending();
        }
    }

    /** Whether a play into the world system can ever start: not on a dedicated server, which has no context. */
    static boolean mayPlay() {
        return CgWorldQueries.client() != CgWorldQuery.NONE || CgGraphicsLifecycle.isInitialized();
    }
}
