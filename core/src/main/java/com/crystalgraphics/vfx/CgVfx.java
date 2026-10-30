package com.crystalgraphics.vfx;

import com.crystalgraphics.frame.CgTickFunction;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.gl.lifecycle.CgLifecycleListener;
import com.crystalgraphics.net.object.CgCharacterClockNet;
import com.crystalgraphics.platform.world.CgWorldQuery;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.vfx.camera.CgCameraShake;
import com.crystalgraphics.vfx.net.CgVfxCueClient;
import com.crystalgraphics.vfx.prefab.CgVfxPrefabAssets;
import com.crystalgraphics.vfx.prefab.CgVfxPrefabPreparer;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
        // Characters' clocks the server sends, kept on the level's tick: the effects following them hold and slow.
        CgCharacterClockNet.install(() -> CgRenderStage.WORLD_OPAQUE.host().environment().gameTime());
        CgVfxSystemAssets.register();   // system assets compile to playable definitions
        CgVfxPrefabAssets.register();
        // and are READY once the world's system has warmed what they draw: nothing compiles or uploads at a play
        preparers(CgVfxAssets.instance(), CgVfx::world);
        // Every frame's end, the title screen's too: a logout clears before the next world.
        CgGraphicsLifecycle.addListener(new CgLifecycleListener() {
            @Override
            public void onFrame(long frame) {
                if (world != null) followLevel();
            }
        });
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
