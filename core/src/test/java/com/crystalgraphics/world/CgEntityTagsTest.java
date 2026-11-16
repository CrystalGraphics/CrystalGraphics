package com.crystalgraphics.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.net.object.CgNetTestRig;
import com.crystalgraphics.tag.CgGameplayTag;
import com.crystalgraphics.tag.CgTagCounts;
import com.crystalgraphics.tag.CgTagRegistry;

/** An entity's owned tags: counted on the server, mirrored on the client that sees it, a late joiner and a death included. */
public class CgEntityTagsTest {

    private CgNetTestRig rig;
    private CgGameplayTag flying, fast;

    @Before
    public void setUp() {
        flying = CgTagRegistry.global().register("entitytags:state.flying");
        fast = CgTagRegistry.global().register("entitytags:state.flying.fast");
        CgEntityTags.resetForTesting();
        rig = CgNetTestRig.of(1);
        rig.tick(4);                                         // the tag table published and agreed
    }

    @After
    public void tearDown() {
        rig.close();
        CgEntityTags.resetForTesting();
    }

    @Test
    public void countsReachTheClientThatSeesTheEntity() {
        CgNetTestRig.Entity e = rig.entity(0, 64, 0);
        CgEntityTags.add(e, fast);
        CgEntityTags.add(e, fast);
        assertEquals("the server counts a tag's children", 2, CgEntityTags.count(e, flying));
        rig.tick(2);
        CgTagCounts seen = CgEntityTags.client(e.id());
        assertEquals(2, seen.count(fast));
        assertEquals(2, seen.count(flying));

        assertTrue(CgEntityTags.remove(e, fast));
        rig.tick(2);
        assertEquals(1, CgEntityTags.client(e.id()).count(flying));
        assertFalse("a parent is never removed through its child", CgEntityTags.remove(e, flying));
        assertTrue(CgEntityTags.remove(e, fast));
        rig.tick(2);
        assertFalse(CgEntityTags.client(e.id()).has(flying));
    }

    @Test
    public void aLateJoinerIsSentTheCountsAndADeathEndsThem() {
        CgNetTestRig.Entity e = rig.entity(0, 64, 0);
        CgEntityTags.add(e, flying, 3);
        rig.tick(2);
        rig.client(0).disconnect();
        rig.tick(2);
        rig.client(0).connect();
        rig.tick(6);
        assertEquals(3, CgEntityTags.client(e.id()).count(flying));

        e.kill();
        rig.tick(3);
        assertEquals("gone with the entity", 0, CgEntityTags.client(e.id()).count(flying));
    }

    @Test
    public void aHeldMirrorIsTheOneUpdated() {
        CgNetTestRig.Entity e = rig.entity(0, 64, 0);
        CgTagCounts held = CgEntityTags.hold(e.id());
        CgEntityTags.add(e, flying);
        rig.tick(2);
        assertSame("an actor's reference stays live", held, CgEntityTags.client(e.id()));
        assertEquals(1, held.count(flying));
        CgEntityTags.release(e.id());
        assertEquals("still served", 1, CgEntityTags.client(e.id()).count(flying));
    }
}
