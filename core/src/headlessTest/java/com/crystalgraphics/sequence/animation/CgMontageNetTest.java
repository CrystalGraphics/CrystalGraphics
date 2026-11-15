package com.crystalgraphics.sequence.animation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.animation.montage.CgMontageInstance;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.net.object.CgNetTestRig;
import com.crystalgraphics.net.object.CgPredictionKey;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.sequence.net.CgSequenceNet;

/**
 * A melee chain, its branch taken on an input, served over the rig with latency: the owner's predicted combo, a viewer
 * and a late joiner each play it as one montage, in step with the server's section and time.
 */
public class CgMontageNetTest {

    /**
     * Two strikes, each into its recovery; the game's combo input takes the first strike to the second. The strikes'
     * clips hold the elbow at 2 and 4, the recoveries at 3 and 5.
     */
    private static final CgSequenceDefinition CHAIN = CgMontageTest.sequence("test:chain",
            "_v: 1\ntick_resolution: 1000\ndisplay_rate: 20\nrange: [0, 2500]\n"
                    + "composite_sections:\n"
                    + "  - { id: q1, name: strike1, range: [0, 500], next: q2 }\n"
                    + "  - { id: q2, name: recover1, range: [500, 1000] }\n"
                    + "  - { id: q3, name: strike2, range: [1000, 2000], next: q4 }\n"
                    + "  - { id: q4, name: recover2, range: [2000, 2500] }\n"
                    + "roles:\n  - { id: b1, name: caster, kind: caster }\n"
                    + "tracks:\n"
                    + "  - { id: t1, kind: animation, binding: { role: caster }, sections: [\n"
                    + "      { id: s1, range: [0, 500], clip: test:c1, ease: { in: 100 } },\n"
                    + "      { id: s2, range: [500, 1000], clip: test:c2 },\n"
                    + "      { id: s3, range: [1000, 2000], clip: test:c3 },\n"
                    + "      { id: s4, range: [2000, 2500], clip: test:c4, ease: { out: 200 } } ] }\n"
                    + "  - { id: t2, kind: event, side: rpg, binding: { role: caster }, sections: [\n"
                    + "      { id: h1, range: [100, 300], event: test:hit_window },\n"
                    + "      { id: h2, range: [1100, 1300], event: test:hit_window } ] }\n");

    private CgNetTestRig rig;
    private CgSequencer server, owner, viewer;
    private final List<CgSequencePlayer> owners = new ArrayList<>(), viewers = new ArrayList<>();
    private final CgAnimInstance ownerBody = new CgAnimInstance(CgMontageTest.mesh()), viewerBody = new CgAnimInstance(CgMontageTest.mesh());
    private CgSequencePlayer played;

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    @Before
    public void setUp() {
        CgSequenceNet.install();
        // The combo window: the first strike and its recovery, where the server stands when the input arrives
        CgSequenceNet.acceptJumps((player, section) -> section.equals("strike2")
                && ("strike1".equals(player.section()) || "recover1".equals(player.section())));
        rig = CgNetTestRig.of(2);
        rig.latencyTicks(2);
        server = new CgWorld(true).add(new CgSequencer());
        owner = new CgWorld().add(new CgSequencer());
        viewer = new CgWorld().add(new CgSequencer());
        CgSequenceNet.client((obj, sequence, roles, seed) -> {
            boolean mine = obj.isMine();
            CgSequencePlayer p = (mine ? owner : viewer).play(CHAIN, Map.of("caster", new CgMontageTest.Fighter(mine ? ownerBody : viewerBody)));
            (mine ? owners : viewers).add(p);
            return p;
        });
        rig.tick(2);
        played = server.play(CHAIN, Map.of("caster", new CgMontageTest.Fighter(new CgAnimInstance(CgMontageTest.mesh()))));
        CgSequenceNet.serve(played, rig.at(0, 64, 0), 7).owner(rig.client(0).id()).start();
    }

    @After
    public void tearDown() {
        rig.close();
    }

    /** A game tick: the server's play, the wire, then a frame on each client and its character's pose. */
    private void ticks(int most, BooleanSupplier done) {
        float dt = (float) CgWorld.TICK_SECONDS;
        for (int n = 0; n < most && !done.getAsBoolean(); n++) {
            server.evaluate(dt);
            rig.tick(1);
            owner.evaluate(dt);
            ownerBody.tick(dt);
            viewer.evaluate(dt);
            viewerBody.tick(dt);
        }
    }

    private static float elbow(CgAnimInstance body) {
        return CgMontageTest.elbow(body);
    }

    @Test
    public void aComboBranchPredictedByItsOwnerPlaysAsOneMontageEverywhereAndALateJoinerLandsInIt() {
        rig.client(1).moveTo(5000, 64, 0);                                 // the late joiner, out of range
        ticks(100, () -> !owners.isEmpty());
        ticks(4, () -> false);                                             // 0.2 s: in the first strike
        CgSequencePlayer mine = owners.get(0);
        assertEquals("strike1", mine.section());
        CgMontageInstance montage = ownerBody.montages().active().get(0);

        CgPredictionKey key = CgPredictionKey.open();                      // the combo input
        CgSequenceNet.jump(mine, "strike2", key);
        ticks(1, () -> false);
        assertEquals("shown at once on its owner", "strike2", mine.section());
        assertEquals(4f, elbow(ownerBody), 1e-6f);
        assertEquals("the same montage, at full weight: a branch is no new play", List.of(montage), ownerBody.montages().active());
        assertEquals(1f, montage.weight(), 0f);
        ticks(20, () -> key.state() != CgPredictionKey.State.OPEN);
        assertEquals(CgPredictionKey.State.ACCEPTED, key.state());
        assertEquals("strike2", played.section());

        rig.client(1).moveTo(0, 64, 0);                                    // the late joiner comes into range
        ticks(100, () -> !viewers.isEmpty());
        ticks(6, () -> false);                                             // its montage blended in over 0.1 s
        CgSequencePlayer late = viewers.get(0);
        assertEquals("on the server's section", played.section(), late.section());
        assertEquals("on its time, the trip behind", played.seconds() - 2 * CgWorld.TICK_SECONDS, late.seconds(), 1e-6);
        CgMontageInstance lateMontage = viewerBody.montages().active().get(0);
        assertEquals(1f, lateMontage.weight(), 0f);
        assertEquals("the owner and the late joiner pose alike", elbow(ownerBody), elbow(viewerBody), 1e-6f);

        ticks(60, () -> montage.terminated() && lateMontage.terminated());
        assertTrue("the chain's end blends it out on the owner", montage.terminated());
        assertTrue("and on the late joiner", lateMontage.terminated());
        assertFalse("the owner's montage was never interrupted", montage.interrupted());
        assertEquals(1f, elbow(ownerBody), 1e-6f);
    }
}
