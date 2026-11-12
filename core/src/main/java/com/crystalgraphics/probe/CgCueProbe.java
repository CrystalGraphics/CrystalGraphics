package com.crystalgraphics.probe;

import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.asset.camera.CgCameraShakeDefinition;
import com.crystalgraphics.asset.kind.CgGameplayKinds;
import com.crystalgraphics.asset.load.CgAssetRef;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.asset.registry.CgAssetEntry;
import com.crystalgraphics.asset.registry.CgAssetSource;
import com.crystalgraphics.cue.CgCueDefinition;
import com.crystalgraphics.cue.CgCueManager;
import com.crystalgraphics.cue.CgCueParameters;
import com.crystalgraphics.net.object.CgAnchor;
import com.crystalgraphics.platform.world.CgHostCamera;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.tag.CgGameplayTag;
import com.crystalgraphics.tag.CgTagRegistry;
import com.crystalgraphics.vfx.camera.CgShakePlay;
import com.crystalgraphics.world.CgCameraControl;
import com.crystalgraphics.world.CgEntityQueries;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The world probe's cue: a mod jar's cue, mounted on both sides, executed by its tag on the single-player server, and
 * its shake seen on this client: the frame's view turned and, where the host applies an offset's translation, moved. Named by {@code -Dcrystalgraphics.worldprobe.cue=<cue id>|<tag>}, the probe mod's
 * {@code cgprobe:shake|cgprobe:cue.shake}.
 */
final class CgCueProbe {

    private static final String SPEC = CgVar.text("crystalgraphics.worldprobe.cue", "", CgVar.DEV | CgVar.RESTART,
            "The cue crystalgraphics:world plays on the server and feels on the client: <cue id>|<tag>; empty for none.").get();
    private static volatile boolean armed, executed;
    private static volatile String serverFailure, serverCounts = "";
    private static volatile int serverTicksAfter;
    private static double x, y, z;
    private static volatile int player = -1;
    private static boolean listening, felt;
    private static CgAssetRef<CgCueDefinition> ref;
    private static int startGeneration;
    private static boolean fromSource;

    /** A frame's view moved or turned at least this much, and twice the baseline's, counts as the shake's. */
    private static final double MIN_MOVE = 1e-4, MIN_TURN = 1e-3;
    /** More than this in one frame is another check moving the camera, not a shake. */
    private static final double MAX_MOVE = 0.25, MAX_TURN = 3.0;
    private static final Matrix4f INVERSE = new Matrix4f();
    private static final Vector3f EYE = new Vector3f(), FACING = new Vector3f(), LAST_FACING = new Vector3f();
    private static double lastX, lastY, lastZ, shakeMove, shakeTurn, baseMove, baseTurn;
    private static boolean hasLast, lastHeld;

    private CgCueProbe() {
    }

    /** At the probe's static checks: whether the cue's namespace was mounted, and the server's play armed at the player. */
    static void start(double px, double py, double pz) {
        if (SPEC.indexOf('|') < 0) {
            CgWorldProbe.skip("cue.mounted", "no -Dcrystalgraphics.worldprobe.cue=<cue id>|<tag>");
            return;
        }
        String id = SPEC.substring(0, SPEC.indexOf('|'));
        String ns = id.substring(0, id.indexOf(':'));
        int assets = CgVfxAssets.instance().registry().entries(ns).size();
        CgWorldProbe.check("cue.mounted", assets > 0 && CgVfxAssets.instance().registry().entry(CgGameplayKinds.CUE, id) != null,
                assets + " assets of " + ns + " mounted, " + id + (assets > 0 ? " among them" : ""));
        ref = CgVfxAssets.ref(CgGameplayKinds.CUE, id);
        CgWorldProbe.check("cue.loaded-ahead", ref.ready(), id + " is " + ref.state() + " as the probe plays it: loaded at init");
        CgAssetEntry entry = CgVfxAssets.instance().registry().entry(CgGameplayKinds.CUE, id);
        fromSource = entry != null && entry.source() == CgAssetSource.DEV;
        startGeneration = ref.generation();
        x = px;
        y = py + 1.0;
        z = pz;
        player = CgEntityQueries.client().localPlayer();   // a network id: the same on the server
        armed = true;
        if (!listening) {
            listening = true;
            CgEngineLifecycle.onServerTick(CgCueProbe::serverTick);
        }
    }

    /** The server's half, on its thread: the cue played by tag through the server world's manager. */
    private static void serverTick() {
        if (executed && ++serverTicksAfter == 20) {
            CgCueManager m = CgCueManager.server();
            serverCounts = "server unrouted " + m.unrouted() + ", late-dropped " + m.lateDropped();
        }
        if (!armed) return;
        armed = false;
        try {
            CgGameplayTag tag = CgTagRegistry.global().find(SPEC.substring(SPEC.indexOf('|') + 1));
            if (tag == null) {
                serverFailure = "its tag is not declared";
                return;
            }
            if (player < 0) {
                serverFailure = "this client has no player";
                return;
            }
            // A server's play without an anchor is sent to no one; single player has one viewer
            CgCueManager.server().execute(tag, new CgCueParameters().at(x, y, z).anchor(CgAnchor.global()));
            executed = true;
        } catch (RuntimeException e) {
            serverFailure = e.toString();
        }
    }

    /**
     * Each frame of the probe's events: whether the cue's shake plays here yet, and how far the frame's view moved and
     * turned since the last, while it plays and while nothing does (the baseline). A held camera's frames, and the free
     * frames either side of a hold, are not read.
     */
    static void watch() {
        if (ref == null) return;
        CgCueDefinition cue = ref.loaded();
        boolean playing = cue != null && cue.burst().shake() != null
                && cue.burst().shake().definition() instanceof CgCameraShakeDefinition d && CgShakePlay.playing(d) != null;
        if (playing) felt = true;
        CgCamera view = CgRenderStage.WORLD_OPAQUE.host().camera();
        INVERSE.set(view.view()).invert();
        INVERSE.transformPosition(EYE.zero());
        double ex = view.x() + EYE.x, ey = view.y() + EYE.y, ez = view.z() + EYE.z;
        INVERSE.transformDirection(FACING.set(0f, 0f, -1f)).normalize();
        boolean held = CgCameraControl.held();
        // A hold's first and last frames jump the camera: only a frame whose neighbour was free too is read.
        if (hasLast && !held && !lastHeld) {
            double moved = Math.sqrt((ex - lastX) * (ex - lastX) + (ey - lastY) * (ey - lastY) + (ez - lastZ) * (ez - lastZ));
            double turned = Math.toDegrees(FACING.angle(LAST_FACING));
            // A bigger jump is another check moving the camera (a hold, the probe's own offset), not a shake.
            boolean shakeSized = moved <= MAX_MOVE && turned <= MAX_TURN;
            if (shakeSized && playing) {
                shakeMove = Math.max(shakeMove, moved);
                shakeTurn = Math.max(shakeTurn, turned);
            } else if (shakeSized && !felt) {
                baseMove = Math.max(baseMove, moved);
                baseTurn = Math.max(baseTurn, turned);
            }
        }
        lastX = ex;
        lastY = ey;
        lastZ = ez;
        LAST_FACING.set(FACING);
        lastHeld = held;
        hasLast = true;
    }

    /** At the probe's end: the server executed it, this client felt its shake, and an edit to its file reloaded it. */
    static void report() {
        if (ref == null) return;
        if (!fromSource) {
            CgWorldProbe.skip("cue.reloaded", "mounted from a jar: only a source folder reloads");
        } else {
            CgWorldProbe.check("cue.reloaded", ref.generation() > startGeneration,
                    "generation " + startGeneration + " to " + ref.generation() + " (edit its file while the probe runs)");
        }
        if (serverFailure != null || !executed) {
            CgWorldProbe.check("cue.played", false, serverFailure != null ? "the server's play failed: " + serverFailure
                    : "no server tick ran it (no single-player server)");
            return;
        }
        CgCueManager client = CgCueManager.client();
        CgCueDefinition cue = ref.loaded();
        Object shake = cue == null || cue.burst().shake() == null ? null : cue.burst().shake().definition();
        CgWorldProbe.check("cue.played", felt, felt ? ref.id() + "'s shake played here after the server played it"
                : ref.id() + " played on the server, its shake never felt here (" + ref.state() + "; client unrouted "
                + client.unrouted() + ", late-dropped " + client.lateDropped() + "; " + serverCounts + "; shake "
                + (shake == null ? "none" : shake.getClass().getSimpleName()) + ")");
        if (!felt) return;
        // What the frame renders from, not only that the play ran: its view turned and moved past the baseline's jitter.
        boolean turned = shakeTurn > Math.max(MIN_TURN, 2 * baseTurn);
        CgWorldProbe.check("cue.view-turned", turned, String.format("the view turned up to %.4f° a frame under the shake,"
                + " %.4f° without", shakeTurn, baseTurn));
        if ((CgCameraControl.capabilities() & CgHostCamera.TRANSLATION) == 0) {
            CgWorldProbe.skip("cue.view-moved", "this host does not apply an offset's translation");
        } else {
            boolean moved = shakeMove > Math.max(MIN_MOVE, 2 * baseMove);
            boolean ran = (CgCameraControl.applied() & CgHostCamera.TRANSLATION) != 0;
            CgWorldProbe.check("cue.view-moved", moved, String.format("the eye moved up to %.5f blocks a frame under the"
                    + " shake, %.5f without; the host's translation hook %s", shakeMove, baseMove,
                    ran ? "ran" : "never ran"));
        }
    }
}
