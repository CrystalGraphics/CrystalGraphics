package com.crystalgraphics.sequence.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.net.object.CgNetTestRig;
import com.crystalgraphics.net.object.CgPredictionKey;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.binding.CgSequenceRole;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.world.CgAttachment;

/** A served sequence on the rig with latency: viewers on the server's time and section, an owner's jumps predicted. */
public class CgSequenceNetTest {

    /** 1 s long, 100 ticks a second: a charge, a loop that laps until relinked, a release. */
    private static final String MONTAGE = "_v: 1\ntick_resolution: 100\ndisplay_rate: 10\nrange: [0, 100]\n"
            + "composite_sections:\n"
            + "  - { id: q1, name: charge, range: [0, 40], next: q2 }\n"
            + "  - { id: q2, name: loop, range: [40, 80], next: q2 }\n"
            + "  - { id: q3, name: release, range: [80, 100] }\n"
            + "roles:\n  - { id: b1, name: caster, kind: caster }\n"
            + "tracks:\n"
            + "  - { id: t1, kind: event, side: ours, binding: { role: caster }, sections: [ { id: a, keys: [[50, mymod:pulse]] } ] }\n";

    /** The loop's length, in seconds. */
    private static final double LAP = 0.4;

    private static final class Caster implements CgSequenceRole {
        @Override public CgAttachment attachment(String socket) {
            return (point, turn) -> {
                point[0] = point[1] = point[2] = 0;
                if (turn != null) turn.identity();
                return true;
            };
        }

        @Override public int slot(String name) {
            return 0;
        }

        @Override public void set(int slot, float[] v, int mask) {
        }

        @Override public void get(int slot, float[] out) {
        }
    }

    private final CgSequenceDefinition montage = CgSequenceDefinition.compile(
            CgAssetData.of("mymod:charge", CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(MONTAGE), null));
    private CgNetTestRig rig;
    private CgSequencer server, owner, viewer;
    private final List<CgSequencePlayer> owners = new ArrayList<>(), viewers = new ArrayList<>();
    private CgSequencePlayer played;

    @Before
    public void setUp() {
        CgSequenceNet.install();
        CgSequenceNet.acceptJumps((player, section) -> true);
        rig = CgNetTestRig.of(2);
        rig.latencyTicks(2);
        server = new CgWorld(true).add(new CgSequencer());
        owner = new CgWorld().add(new CgSequencer());
        viewer = new CgWorld().add(new CgSequencer());
        CgSequenceNet.client((obj, sequence, roles, seed) -> {
            assertEquals("mymod:charge", sequence);
            CgSequencePlayer p = (obj.isMine() ? owner : viewer).play(montage, Map.of("caster", new Caster()));
            (obj.isMine() ? owners : viewers).add(p);
            return p;
        });
        rig.tick(2);
        played = server.play(montage, Map.of("caster", new Caster()));
        CgSequenceNet.serve(played, rig.at(0, 64, 0), 7).owner(rig.client(0).id()).start();
    }

    @After
    public void tearDown() {
        rig.close();
    }

    /** A game tick: the server's play, the wire, then a frame on each client. */
    private void ticks(int most, BooleanSupplier done) {
        for (int n = 0; n < most && !done.getAsBoolean(); n++) {
            server.evaluate((float) CgWorld.TICK_SECONDS);
            rig.tick(1);
            owner.evaluate((float) CgWorld.TICK_SECONDS);
            viewer.evaluate((float) CgWorld.TICK_SECONDS);
        }
    }

    @Test
    public void aLateJoinerLandsOnTheServersTimeAndSectionAndFollowsItsRelink() {
        rig.client(1).moveTo(5000, 64, 0);
        ticks(30, () -> false);                                             // 1.5 s: into the loop's third lap
        assertEquals("loop", played.section());
        rig.client(1).moveTo(0, 64, 0);
        ticks(100, () -> !viewers.isEmpty());
        ticks(3, () -> false);
        CgSequencePlayer late = viewers.get(0);
        assertEquals("on the server's section", "loop", late.section());
        double lag = (played.seconds() - late.seconds() + LAP) % LAP;      // both in the loop: its laps fold away
        assertEquals("on the server's time, the trip behind", 2 * CgWorld.TICK_SECONDS, lag, 1e-6);

        played.nextSection("loop", "release");                             // the server lets go of the charge
        ticks(20, () -> late.finished());
        assertEquals("release", late.section());
        assertTrue(late.finished());
        assertEquals("release", owners.get(0).section());
    }

    @Test
    public void aRefusedJumpRollsBackOnItsOwnerAndTheServerNeverMoves() {
        CgSequenceNet.acceptJumps((player, section) -> !section.equals("release"));
        ticks(12, () -> false);                                             // 0.6 s: in the loop
        CgSequencePlayer mine = owners.get(0);
        assertEquals("loop", mine.section());
        CgPredictionKey key = CgPredictionKey.open();
        CgSequenceNet.jump(mine, "release", key);
        owner.evaluate(0f);
        assertEquals("shown at once", "release", mine.section());
        ticks(20, () -> key.state() != CgPredictionKey.State.OPEN);
        owner.evaluate(0f);
        assertEquals(CgPredictionKey.State.REFUSED, key.state());
        assertEquals("back where it jumped from", "loop", mine.section());
        assertEquals("loop", played.section());
        assertEquals("a viewer never saw it", "loop", viewers.get(0).section());
    }

    @Test
    public void anAcceptedJumpLandsOnTheServerAndItsViewersAndTheOwnerIsNotPulledBack() {
        ticks(12, () -> false);
        CgSequencePlayer mine = owners.get(0);
        CgPredictionKey key = CgPredictionKey.open();
        CgSequenceNet.jump(mine, "release", key);
        ticks(20, () -> key.state() != CgPredictionKey.State.OPEN);
        assertEquals(CgPredictionKey.State.ACCEPTED, key.state());
        assertEquals("release", played.section());
        double ahead = mine.seconds() - played.seconds();
        assertTrue("the owner keeps its own jump, never snapped back: " + ahead, ahead >= -1e-6);
        ticks(3, () -> false);
        assertNotNull(viewers.get(0).section());
        assertEquals("release", viewers.get(0).section());
    }
}
