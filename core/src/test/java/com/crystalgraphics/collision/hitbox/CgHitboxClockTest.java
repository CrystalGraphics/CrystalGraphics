package com.crystalgraphics.collision.hitbox;

import com.crystalgraphics.collision.CgCollisionWorld;
import com.crystalgraphics.collision.CollisionFakes.Table;
import com.crystalgraphics.platform.world.CgWorldQuery;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.world.CgCharacterClock;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** A hitbox on a character's clock ages only on the ticks the character ran. */
public class CgHitboxClockTest {

    @Test
    public void itHoldsThroughHitstopWhileAnotherHitboxAges() {
        CgCharacterClock clock = new CgCharacterClock();
        CgCollisionWorld world = CgCollisionWorld.of(CgWorldQuery.NONE, new Table());
        CgHitbox bound = CgHitbox.of(CgSphere.of(1)).at(0, 0, 0).clock(clock).start(world);
        CgHitbox plain = CgHitbox.of(CgSphere.of(1)).at(0, 0, 0).start(world);
        for (int i = 0; i < 4; i++) {
            clock.advance();
            CgHitboxes.tickThread();
        }
        clock.freeze(6);
        for (int i = 0; i < 6; i++) {
            clock.advance();
            CgHitboxes.tickThread();
        }
        for (int i = 0; i < 2; i++) {
            clock.advance();
            CgHitboxes.tickThread();
        }
        assertEquals(6, bound.age(), 0.0);
        assertEquals(12, plain.age(), 0.0);
        bound.stop();
        plain.stop();
        CgHitboxes.tickThread();   // the engine drops stopped hitboxes on its next tick
    }
}
