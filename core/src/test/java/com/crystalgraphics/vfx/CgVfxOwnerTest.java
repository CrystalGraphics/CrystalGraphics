package com.crystalgraphics.vfx;

import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

/** Owner handles: namespaced ids, ending only their own effects, and an effect that throws killed alone. */
public class CgVfxOwnerTest {

    private static final CgVfxLook LOOK = CgVfxLook.builder(new CgVfxSchema()).build();

    static class Quiet extends CgVfxEffect {
        int ticks;

        Quiet(double x) {
            super(LOOK, x, 0, 0);
        }

        @Override
        protected void tick(float dt) {
            ticks++;
        }

        @Override
        protected void submit(CgVfxFrame frame) {
        }
    }

    static final class Throws extends Quiet {
        Throws() {
            super(0);
        }

        @Override
        protected void tick(float dt) {
            if (++ticks == 3) throw new IllegalStateException("on purpose");
        }
    }

    @Test
    public void anEffectThatThrowsIsKilledAndTheRestPlayOn() {
        CgVfxSystem vfx = new CgVfxSystem();
        CgVfxOwner owner = CgVfx.owner("owner_test", vfx);
        Quiet before = owner.play(new Quiet(1));
        Throws thrower = owner.play(new Throws());
        Quiet after = owner.play(new Quiet(2));
        for (int f = 0; f <= 10; f++) vfx.update(f / 60.0);
        assertEquals(CgVfxEffect.State.DEAD, thrower.state());
        assertEquals(3, thrower.ticks);
        assertEquals(20, before.ticks);
        assertEquals(20, after.ticks);
        assertEquals(2, owner.playing());
        assertEquals(Arrays.asList(before, after), vfx.effects());
    }

    @Test
    public void anOwnersListFollowsItsEffectsOutOfOrder() {
        CgVfxSystem vfx = new CgVfxSystem();
        CgVfxOwner mine = CgVfx.owner("owner_list", vfx);
        Quiet a = mine.play(new Quiet(1)), b = mine.play(new Quiet(2)), c = mine.play(new Quiet(3));
        assertSame(mine, a.owner());
        assertEquals(3, mine.playing());
        a.kill();
        vfx.update(0.0);
        vfx.update(1 / 60.0);
        assertEquals(2, mine.playing());
        assertEquals(Arrays.asList(b, c), vfx.effects());
        c.kill();
        vfx.update(2 / 60.0);
        assertEquals(1, mine.playing());
        mine.clear();
        assertEquals(CgVfxEffect.State.DEAD, b.state());
        vfx.update(3 / 60.0);
        assertEquals(0, mine.playing());
        vfx.clear();
        assertEquals(0, mine.playing());
    }

    @Test
    public void stopEndsGracefullyAndClearAtOnceOnlyTheOwners() {
        CgVfxSystem vfx = new CgVfxSystem();
        CgVfxOwner mine = CgVfx.owner("owner_ends", vfx), theirs = CgVfx.owner("owner_others", vfx);
        Quiet a = mine.play(new Quiet(1)), b = theirs.play(new Quiet(2));
        mine.stop();
        assertSame(mine, a.owner());
        assertEquals(CgVfxEffect.State.STOPPING, a.state());
        assertEquals(CgVfxEffect.State.PLAYING, b.state());
        mine.clear();
        assertEquals(CgVfxEffect.State.DEAD, a.state());
        assertEquals(CgVfxEffect.State.PLAYING, b.state());
        vfx.update(0.0);
        vfx.update(1 / 60.0);
        assertEquals(Arrays.asList(b), vfx.effects());
    }

    @Test
    public void aDisabledOwnerEndsItsEffectsAndPlaysNothingUntilEnabled() {
        CgVfxSystem vfx = new CgVfxSystem();
        CgVfxOwner mine = CgVfx.owner("owner_switch", vfx), theirs = CgVfx.owner("owner_switch_others", vfx);
        Quiet a = mine.play(new Quiet(1)), b = theirs.play(new Quiet(2));
        mine.enabled(false);
        assertEquals(CgVfxEffect.State.DEAD, a.state());
        Quiet muted = mine.play(new Quiet(3));
        assertEquals("returned dead", CgVfxEffect.State.DEAD, muted.state());
        muted.stop();
        vfx.update(0.0);
        vfx.update(1 / 60.0);
        assertEquals(Arrays.asList(b), vfx.effects());
        mine.enabled(true);
        Quiet back = mine.play(new Quiet(4));
        vfx.update(2 / 60.0);
        assertEquals(CgVfxEffect.State.PLAYING, back.state());
        assertEquals(Arrays.asList(b, back), vfx.effects());
    }

    @Test
    public void aKilledEffectsGpuParticlesGoWithIt() {
        CgVfxSystem vfx = new CgVfxSystem();
        CgVfxOwner owner = CgVfx.owner("owner_slots", vfx);
        CgVfxStartAtTest.Phased effect = owner.play(new CgVfxStartAtTest.Phased());
        for (int f = 0; f <= 4; f++) vfx.update(f / 60.0);
        assertNotNull(vfx.gpuSteps().of(effect.embers));
        owner.clear();
        for (int f = 5; f <= 7; f++) vfx.update(f / 60.0);
        assertNull(vfx.gpuSteps().of(effect.embers));
    }

    @Test
    public void registeredEffectsAreNamespacedAndPlayedById() {
        CgVfxSystem vfx = new CgVfxSystem();
        CgVfxOwner owner = CgVfx.owner("owner_ids", vfx);
        CgVfxRegistry.Entry entry = owner.register("ping", "Ping", "Tests", LOOK, (look, x, y, z, tx, ty, tz) -> new Quiet(x));
        assertEquals("owner_ids:ping", entry.id());
        assertSame(entry, CgVfxRegistry.get("owner_ids:ping"));
        CgVfxEffect played = owner.play("ping", 5, 0, 0, 0, 0, 0);
        assertNotNull(played);
        assertEquals(5.0, played.originX, 0.0);
        assertSame(vfx, played.system);
        assertNull(owner.play("missing", 0, 0, 0, 0, 0, 0));
        try {
            owner.register("ping", "Ping", "Tests", LOOK, (look, x, y, z, tx, ty, tz) -> new Quiet(x));
            fail("registered twice");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void anOwnerIsANamespace() {
        CgVfx.owner("Not A Namespace");
    }

    @Test(expected = IllegalStateException.class)
    public void anEffectPlaysOnce() {
        CgVfxSystem vfx = new CgVfxSystem();
        Quiet once = vfx.play(new Quiet(0));
        CgVfx.owner("owner_twice", vfx).play(once);
    }
}
