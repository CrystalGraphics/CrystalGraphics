package com.crystalgraphics.net.object;

import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.CgObjectCodec;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** A codec field set back to null, a value sent again unchanged, and a refused guess put right. */
public class CgNetFieldValuesTest {

    static final CgNetField<String> ANIM = CgNetField.of("anim", CgCodecs.STRING).fallback("idle");
    static final CgNetType ACTOR = CgNetType.define("test:actor").fields(ANIM).persisted().build();

    static final CgNetField<Boolean> FLYING = CgNetField.ofBool("flying");
    static final CgNetType FLIER = CgNetType.define("test:flier").fields(FLYING).build();

    static final CgNetField<Boolean> WINGS = CgNetField.ofBool("wings").predicted().sanitizedBy((obj, wanted) -> false);
    static final CgNetType GLIDER = CgNetType.define("test:glider").fields(WINGS).build();

    static final class Stats {
        int power;
        static final CgObjectCodec<Stats> CODEC = CgCodecs.object(Stats::new)
                .field("power", CgCodecs.INT, s -> s.power, (s, v) -> s.power = v).build();
    }

    static final Stats NONE = new Stats();
    static final CgNetField<Stats> STATS = CgNetField.of("stats", Stats.CODEC).fallback(NONE);
    static final CgNetType FIGHTER = CgNetType.define("test:fighter").fields(STATS).build();

    static final List<Boolean> HEARD = new ArrayList<>();

    static {
        FLIER.onChange(FLYING, (obj, flying) -> HEARD.add(flying));
    }

    @Rule
    public final TemporaryFolder world = new TemporaryFolder();

    private CgNetTestRig rig;

    @Before
    public void setUp() {
        HEARD.clear();
        rig = CgNetTestRig.of(1).saveTo(world.getRoot().toPath());
        rig.tick(3);
    }

    @After
    public void tearDown() {
        rig.close();
    }

    @Test
    public void aCodecFieldGoesBackToNullEverywhere() {
        CgNetObject actor = ACTOR.spawn(CgAnchor.player(rig.client(0).id())).start().set(ANIM, "run");
        rig.tick(1);
        assertEquals("run", rig.client(0).visible(ACTOR).get(0).get(ANIM));

        actor.set(ANIM, null);
        rig.tick(1);
        assertNull("a viewer reads null again, not the fallback", rig.client(0).visible(ACTOR).get(0).get(ANIM));

        rig.restart().tick(3);
        String[] restored = {"unread"};
        ACTOR.forEach(obj -> restored[0] = obj.get(ANIM));
        assertNull("saved as null, so it does not load as the fallback", restored[0]);
    }

    @Test
    public void resendSendsAnUnchangedValueAgain() {
        CgNetObject flier = FLIER.spawn(CgAnchor.player(rig.client(0).id())).start().setBool(FLYING, true);
        rig.tick(1);
        int heard = HEARD.size();

        flier.setBool(FLYING, true);
        rig.tick(1);
        assertEquals("an unchanged set sends nothing", heard, HEARD.size());

        flier.resend(FLYING);
        rig.tick(1);
        assertEquals("a resend does", heard + 1, HEARD.size());
        assertTrue(HEARD.get(HEARD.size() - 1));

        CgNetObject seen = rig.client(0).visible(FLIER).get(0);
        assertThrows(IllegalStateException.class, () -> seen.resend(FLYING));
    }

    @Test
    public void aClientsHeldValueIsUpdatedInPlace() {
        CgNetObject fighter = FIGHTER.spawn(CgAnchor.player(rig.client(0).id())).start();
        Stats first = new Stats();
        first.power = 3;
        fighter.set(STATS, first);
        rig.tick(1);
        Stats held = rig.client(0).visible(FIGHTER).get(0).get(STATS);
        assertEquals(3, held.power);

        Stats next = new Stats();
        next.power = 9000;
        fighter.set(STATS, next);
        rig.tick(1);
        assertSame("the same instance, refreshed", held, rig.client(0).visible(FIGHTER).get(0).get(STATS));
        assertEquals(9000, held.power);
        assertEquals("the fallback, shared by all, is never decoded into", 0, NONE.power);
    }

    @Test
    public void aRefusedGuessIsPutRight() {
        GLIDER.spawn(CgAnchor.player(rig.client(0).id())).start();
        rig.tick(1);
        CgNetObject mine = rig.client(0).visible(GLIDER).get(0);

        mine.setBool(WINGS, true);
        assertTrue("shown at once", mine.getBool(WINGS));
        rig.tick(3);
        assertFalse("refused back to the server's own false: still sent, so the owner is corrected", mine.getBool(WINGS));
    }
}
