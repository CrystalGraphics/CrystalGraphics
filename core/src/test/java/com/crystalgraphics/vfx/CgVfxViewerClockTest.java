package com.crystalgraphics.vfx;

import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.post.volume.CgPostSettings;
import com.crystalgraphics.render.post.volume.CgPostVolume;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.world.CgCharacterClock;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/** An effect's flash is the viewer's: it fades on through its character's hitstop, while the effect itself holds. */
public class CgVfxViewerClockTest {

    private static final CgVfxLook LOOK = CgVfxLook.builder(CgVfxSchema.of("test:viewer_clock")).build();

    static final class Flasher extends CgVfxEffect<Flasher> {
        CgPostVolume flash;

        Flasher() {
            super(LOOK, 0, 64, 0);
        }

        @Override
        protected void tick(float dt) {
            if (flash == null) flash = openVolume(10, new CgPostSettings().flash(1f), 0, 0, 0).timeline(s -> 1f - Math.min(s / 0.5f, 1f));
        }

        @Override
        protected void submit(CgVfxFrame frame) {
        }
    }

    @Test
    public void aFlashFadesOnThroughItsCharactersHitstop() {
        CgCharacterClock clock = new CgCharacterClock();
        CgVfxSystem vfx = new CgVfxSystem();
        CgFrameClock.set(10.0);
        Flasher effect = vfx.play(new Flasher().clock(clock));
        for (int f = 0; f <= 3; f++) vfx.update(f / 60.0);
        assertNotNull("opened on its first tick", effect.flash);
        assertEquals(1f, effect.flash.weightAt(0, 64, 0), 0f);

        clock.freeze(1000);                                   // the caster's hitstop
        float held = effect.age();
        CgFrameClock.set(10.25);
        for (int f = 4; f <= 18; f++) vfx.update(f / 60.0);
        assertEquals("the effect holds", held, effect.age(), 0f);
        assertEquals("its flash fades on, a quarter second in", 0.5f, effect.flash.weightAt(0, 64, 0), 1e-6f);
        CgFrameClock.set(10.75);
        assertEquals(0f, effect.flash.weightAt(0, 64, 0), 0f);
    }
}
