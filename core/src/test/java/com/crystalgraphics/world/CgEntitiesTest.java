package com.crystalgraphics.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.net.object.CgNetTestRig;
import com.crystalgraphics.net.object.CgPredictionKey;
import com.crystalgraphics.tag.CgGameplayTag;
import com.crystalgraphics.tag.CgTagRegistry;
import com.crystalgraphics.world.actor.CgEntities;
import com.crystalgraphics.world.actor.CgEntity;

/** The gameplay handle: tag verbs on the server, an owner's predicted ones taken or refused, a window's one key. */
public class CgEntitiesTest {

    private CgNetTestRig rig;
    private CgGameplayTag stunned, burning, burningHard;

    @Before
    public void setUp() {
        stunned = CgTagRegistry.global().register("handle:state.stunned");
        burning = CgTagRegistry.global().register("handle:state.burning");
        burningHard = CgTagRegistry.global().register("handle:state.burning.hard");
        CgEntityTags.resetForTesting();
        CgEntities.resetForTesting();
        rig = CgNetTestRig.of(1);
        rig.client(0).local();
        rig.tick(4);                                         // the tag table published and agreed
    }

    @After
    public void tearDown() {
        rig.close();
        CgEntityTags.resetForTesting();
        CgEntities.resetForTesting();
    }

    @Test
    public void theServerCountsTagsAndAnswersQueriesWithTheTagsUnderOne() {
        CgNetTestRig.Entity zombie = rig.entity(0, 64, 0);
        CgEntity handle = CgEntities.of(zombie);
        assertSame("one handle per entity", handle, CgEntities.of(zombie));
        assertTrue(handle.isAuthority());
        assertFalse("a server has no owner", handle.isOwner());

        handle.addTag(burningHard).addTag(burningHard).addTag(stunned);
        assertTrue("a tag under it counts", handle.hasTag(burning));
        assertTrue(handle.hasAnyTag(burning, stunned));
        assertTrue(handle.hasAllTags(burning, stunned));

        handle.removeTag(burningHard, 2);
        assertFalse(handle.hasTag(burning));
        assertFalse(handle.hasAllTags(burning, stunned));

        handle.setTagCount(stunned, 3);
        assertEquals(3, CgEntityTags.count(zombie, stunned));
        handle.setTagCount(stunned, 0);
        assertFalse(handle.hasTag(stunned));
    }

    @Test
    public void aListenerHearsTheServersChangeOnBothSides() {
        CgNetTestRig.Entity zombie = rig.entity(0, 64, 0);
        List<String> server = new ArrayList<>(), client = new ArrayList<>();
        CgEntityTags.Watch onServer = CgEntities.of(zombie).onTagChanged(stunned, (before, now) -> server.add(before + ">" + now));
        CgEntityTags.Watch onClient = CgEntityTags.watch(zombie.id(), stunned, (before, now) -> client.add(before + ">" + now));

        CgEntities.of(zombie).addTag(stunned).addTag(stunned);
        rig.tick(2);
        assertEquals(List.of("0>1", "1>2"), server);
        assertEquals("the client hears the frame's one change", List.of("0>2"), client);

        onServer.cancel();
        onClient.cancel();
        CgEntities.of(zombie).removeTag(stunned);
        rig.tick(2);
        assertEquals(2, server.size());
        assertEquals(1, client.size());
    }

    @Test
    public void anOwnersChangeIsShownAtOnceAndTakenByTheServersRule() {
        Object self = ownedEntity();
        int selfId = CgEntityQueries.idOf(self);
        CgEntityTags.acceptTags((entity, tag, delta) -> tag == stunned);
        CgEntity mine = CgEntities.of(selfId);

        onClient(() -> {
            assertTrue(mine.isOwner());
            mine.addTag(stunned);
            assertTrue("shown now, before the server answers", mine.hasTag(stunned));
        });
        rig.tick(4);
        assertEquals("taken", 1, CgEntityTags.count(self, stunned));
        assertTrue(mine.hasTag(stunned));
    }

    @Test
    public void aRefusedChangeGoesBackOnTheClient() {
        Object self = ownedEntity();
        CgEntity mine = CgEntities.of(CgEntityQueries.idOf(self));

        onClient(() -> {
            mine.addTag(burning);                               // no rule: the server takes nothing
            assertTrue("shown now", mine.hasTag(burning));
        });
        rig.tick(4);
        assertFalse("refused: back as it was", mine.hasTag(burning));
        assertEquals(0, CgEntityTags.count(self, burning));
    }

    @Test
    public void everyCallInOneWindowSharesTheKeyAndAFailureUndoesThemAll() {
        Object self = ownedEntity();
        CgEntity mine = CgEntities.of(CgEntityQueries.idOf(self));
        List<CgPredictionKey> keys = new ArrayList<>();

        onClient(() -> keys.add(CgEntities.predicted(() -> {
            keys.add(CgEntities.predicted(() -> { }));          // nested: the window around it
            mine.addTag(stunned).addTag(burning);
        })));
        assertTrue("shown now", mine.hasAllTags(stunned, burning));
        assertSame("nested, it joins", keys.get(1), keys.get(0));
        assertEquals(CgPredictionKey.State.OPEN, keys.get(0).state());

        rig.tick(4);                                            // the server's rule takes nothing
        assertEquals(CgPredictionKey.State.REFUSED, keys.get(0).state());
        assertFalse(mine.hasTag(stunned));
        assertFalse(mine.hasTag(burning));
    }

    @Test
    public void aClientThatIsNotTheOwnerCannotChangeAnotherEntity() {
        CgNetTestRig.Entity other = rig.entity(5, 64, 5);
        rig.tick(2);
        CgEntity theirs = CgEntities.of(other.id());
        onClient(() -> {
            assertFalse(theirs.isOwner());
            try {
                theirs.addTag(stunned);
                fail("a non-owner's addTag throws");
            } catch (IllegalStateException e) {
                assertTrue("it names the verb: " + e.getMessage(), e.getMessage().contains("addTag"));
            }
            try {
                theirs.play("handle:swing");
                fail("play is the server's");
            } catch (IllegalStateException e) {
                assertTrue(e.getMessage().contains("play"));
            }
        });
    }

    /** The client's own player, with its gameplay object made as a joining player's is. */
    private Object ownedEntity() {
        Object self = rig.client(0);
        CgEntityTags.add(self, burning);
        CgEntityTags.remove(self, burning);
        rig.tick(2);
        return self;
    }

    /** Runs {@code calls} on a thread the network sees as the client's, as a rig has one thread for both sides. */
    private static void onClient(Runnable calls) {
        Throwable[] failure = new Throwable[1];
        Thread client = new Thread(() -> {
            CgNetwork.tickStarted(CgNetwork.Side.CLIENT);
            try {
                calls.run();
            } catch (Throwable e) {
                failure[0] = e;
            }
        });
        client.start();
        try {
            client.join();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        if (failure[0] instanceof RuntimeException r) throw r;
        if (failure[0] instanceof Error e) throw e;
        if (failure[0] != null) throw new IllegalStateException(failure[0]);
    }
}
