package com.crystalgraphics.probe;

import com.crystalgraphics.lifecycle.CgEngineLifecycle;
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
import com.crystalgraphics.tag.CgGameplayTag;
import com.crystalgraphics.tag.CgTagRegistry;
import com.crystalgraphics.vfx.camera.CgShakePlay;
import com.crystalgraphics.world.CgEntityQueries;

/**
 * The world probe's cue: a mod jar's cue, mounted on both sides, executed by its tag on the single-player server, and
 * its shake felt on this client. Named by {@code -Dcrystalgraphics.worldprobe.cue=<cue id>|<tag>}, the probe mod's
 * {@code cgprobe:shake|cgprobe:cue.shake}.
 */
final class CgCueProbe {

    private static final String SPEC = System.getProperty("crystalgraphics.worldprobe.cue");
    private static volatile boolean armed, executed;
    private static volatile String serverFailure, serverCounts = "";
    private static volatile int serverTicksAfter;
    private static double x, y, z;
    private static volatile int player = -1;
    private static boolean listening, felt;
    private static CgAssetRef<CgCueDefinition> ref;
    private static int startGeneration;
    private static boolean fromSource;

    private CgCueProbe() {
    }

    /** At the probe's static checks: whether the cue's namespace was mounted, and the server's play armed at the player. */
    static void start(double px, double py, double pz) {
        if (SPEC == null || SPEC.indexOf('|') < 0) {
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

    /** Each frame of the probe's events: whether the cue's shake plays here yet. */
    static void watch() {
        if (ref == null || felt) return;
        CgCueDefinition cue = ref.loaded();
        if (cue == null || cue.burst().shake() == null) return;
        if (cue.burst().shake().definition() instanceof CgCameraShakeDefinition d && CgShakePlay.playing(d) != null) felt = true;
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
        CgWorldProbe.check("cue.played", felt, felt ? ref.id() + "'s shake felt here after the server played it"
                : ref.id() + " played on the server, its shake never felt here (" + ref.state() + "; client unrouted "
                + client.unrouted() + ", late-dropped " + client.lateDropped() + "; " + serverCounts + "; shake "
                + (shake == null ? "none" : shake.getClass().getSimpleName()) + ")");
    }
}
