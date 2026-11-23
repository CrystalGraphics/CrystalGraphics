package com.crystalgraphics.mc.render.atlas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;

/** The icon atlas's grid, as Mojang's {@code DynamicAtlasAllocator} behaves: draw once, reuse, reclaim, grow. */
public class CgIconAtlasTest {

    private static final int MAX = 4096;
    /** A page's texture: the atlas only reads it to make images. */
    private static final CgTexture PAGE = new CgTexture() {
        @Override
        public CgTextureView rhiView() {
            return null;
        }

        @Override
        public int getWidth() {
            return 1;
        }

        @Override
        public int getHeight() {
            return 1;
        }
    };

    private static List<CgIconAtlas.Cell> show(CgIconAtlas atlas, long frame, Object... keys) {
        List<CgIconAtlas.Cell> out = new ArrayList<>();
        for (Object k : keys) {
            CgIconAtlas.Cell c = atlas.cell(k, k, false, null);
            c.shown(frame);
            out.add(c);
        }
        return out;
    }

    // A frame as CgItemIcons runs it: placed, a remade page given its targets, the due cells drawn
    private static List<CgIconAtlas.Cell> place(CgIconAtlas atlas, long frame) {
        List<CgIconAtlas.Cell> due = new ArrayList<>();
        if (atlas.place(frame, MAX, due)) atlas.targets("page", null, PAGE, null);
        for (CgIconAtlas.Cell c : due) atlas.drawn(c, frame, false);
        return due;
    }

    @Test
    public void aStillCellDrawsOnceThenNever() {
        CgIconAtlas atlas = new CgIconAtlas(32, CgIconAtlas.ICON);
        show(atlas, 1, "sword", "apple");
        assertEquals(2, place(atlas, 1).size());
        for (long f = 2; f < 10; f++) {
            show(atlas, f, "sword", "apple");
            List<CgIconAtlas.Cell> due = new ArrayList<>();
            assertFalse("a still frame remakes nothing", atlas.place(f, MAX, due));
            assertTrue("frame " + f + " draws " + due.size(), due.isEmpty());
        }
    }

    @Test
    public void anAnimatedCellDrawsEveryFrameItShows() {
        CgIconAtlas atlas = new CgIconAtlas(16, CgIconAtlas.ICON);
        CgIconAtlas.Cell glint = atlas.cell("glint", "glint", true, "glint");
        for (long f = 1; f < 5; f++) {
            glint.shown(f);
            assertEquals(1, place(atlas, f).size());
        }
        // Not shown, not drawn
        assertTrue(place(atlas, 5).isEmpty());
    }

    @Test
    public void aPacedAnimatedCellDrawsOnlyWhenItsPeriodHasPassed() {
        CgIconAtlas atlas = new CgIconAtlas(16, CgIconAtlas.ICON);
        CgIconAtlas.Cell glint = atlas.cell("glint", "glint", true, "glint");
        float tick = 20f;
        int drawn = 0;
        // Sixty frames a second for a second, paced at twenty: every third frame, the first included
        for (long f = 1; f <= 60; f++) {
            glint.shown(f);
            List<CgIconAtlas.Cell> due = new ArrayList<>();
            if (atlas.place(f, MAX, f * 16_666_667L, tick, due)) atlas.targets("page", null, PAGE, null);
            for (CgIconAtlas.Cell c : due) atlas.drawn(c, f, false);
            drawn += due.size();
        }
        assertEquals(20, drawn);
    }

    @Test
    public void unevenFramesKeepThePaceOnAverage() {
        CgIconAtlas atlas = new CgIconAtlas(16, CgIconAtlas.ICON);
        CgIconAtlas.Cell glint = atlas.cell("glint", "glint", true, "glint");
        float tick = 20f;
        long now = 0L;
        int drawn = 0;
        // Frames of 30 and 90 ms in turn, 16 a second on average, for 2.4 s: slower than the pace only half the time
        for (long f = 1; f <= 40; f++) {
            now += f % 2 == 0 ? 90_000_000L : 30_000_000L;
            glint.shown(f);
            List<CgIconAtlas.Cell> due = new ArrayList<>();
            if (atlas.place(f, MAX, now, tick, due)) atlas.targets("page", null, PAGE, null);
            for (CgIconAtlas.Cell c : due) atlas.drawn(c, f, false);
            drawn += due.size();
        }
        assertTrue(drawn + " draws", drawn >= 36 && drawn <= 40);
    }

    @Test
    public void aSlotNotShownThisFrameIsReclaimedBeforeThePageGrows() {
        CgIconAtlas atlas = new CgIconAtlas(16, CgIconAtlas.ICON);
        show(atlas, 1, "a", "b", "c", "d");
        place(atlas, 1);
        int side = atlas.side();
        int capacity = (int) Math.pow(Math.floor(side / atlas.stride), 2);
        // A frame showing as many new looks as fit, none of the old: their slots go to the new ones, no regrow
        Object[] fresh = new Object[capacity];
        for (int i = 0; i < capacity; i++) fresh[i] = "n" + i;
        show(atlas, 2, fresh);
        List<CgIconAtlas.Cell> due = new ArrayList<>();
        assertFalse(atlas.place(2, MAX, due));
        assertEquals(side, atlas.side());
        assertEquals(capacity, due.size());
        // An old look shown again lost its slot: it draws again
        show(atlas, 3, "a");
        assertEquals(1, place(atlas, 3).size());
    }

    @Test
    public void thePageGrowsToOneAndAHalfTimesTheFrameToAPowerOfTwo() {
        CgIconAtlas atlas = new CgIconAtlas(32, CgIconAtlas.ICON);
        Object[] keys = new Object[100];
        for (int i = 0; i < keys.length; i++) keys[i] = i;
        show(atlas, 1, keys);
        List<CgIconAtlas.Cell> due = new ArrayList<>();
        assertTrue(atlas.place(1, MAX, due));
        int side = atlas.side();
        assertEquals("a power of two", Integer.bitCount(side), 1);
        int across = (int) (side / atlas.stride);
        assertTrue(across * across >= 150);
        assertEquals(100, due.size());
        // Every cell has its own slot
        assertEquals(100, due.stream().map(c -> atlas.unitsX(c) + "," + atlas.unitsY(c)).distinct().count());
    }

    @Test
    public void iconsStartOnWholeUnitsAndNeighboursShareEdges() {
        CgIconAtlas atlas = new CgIconAtlas(20, CgIconAtlas.ICON);   // scale 1.25: a cell every 22.5 pixels
        Object[] keys = new Object[9];
        for (int i = 0; i < keys.length; i++) keys[i] = i;
        show(atlas, 1, keys);
        List<CgIconAtlas.Cell> due = place(atlas, 1);
        int[] r = new int[4];
        for (CgIconAtlas.Cell c : due) {
            assertEquals(1, atlas.unitsX(c) % atlas.strideUnits);
            assertEquals(1, atlas.unitsY(c) % atlas.strideUnits);
            atlas.rect(c, r, 0);
            int right = r[0] + r[2];
            // The next cell along starts where this one ends
            for (CgIconAtlas.Cell n : due) {
                if (atlas.unitsY(n) == atlas.unitsY(c) && atlas.unitsX(n) == atlas.unitsX(c) + atlas.strideUnits) {
                    int[] rn = new int[4];
                    atlas.rect(n, rn, 0);
                    assertEquals(right, rn[0]);
                }
            }
        }
    }

    @Test
    public void aSweepForgetsCellsNobodyUses() {
        CgIconAtlas atlas = new CgIconAtlas(16, CgIconAtlas.ICON);
        List<CgIconAtlas.Cell> cells = show(atlas, 1, "kept", "dropped");
        place(atlas, 1);
        cells.get(0).use();
        atlas.sweep();
        assertEquals(1, atlas.cells());
    }

    @Test
    public void aFailedCellIsNotDrawnUntilRetried() {
        CgIconAtlas atlas = new CgIconAtlas(16, CgIconAtlas.ICON);
        CgIconAtlas.Cell c = show(atlas, 1, "broken").get(0);
        place(atlas, 1);
        c.fail();
        c.shown(2);
        assertTrue(place(atlas, 2).isEmpty());
        c.retry();
        c.shown(3);
        assertEquals(1, place(atlas, 3).size());
    }

    @Test
    public void aFrameLargerThanThePageCanHoldIsRefusedByName() {
        CgIconAtlas atlas = new CgIconAtlas(64, CgIconAtlas.ICON);
        Object[] keys = new Object[100];
        for (int i = 0; i < keys.length; i++) keys[i] = i;
        show(atlas, 1, keys);
        try {
            atlas.place(1, 256, new ArrayList<>());
            fail("100 icons of 64 px fit no 256 px page");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("100 item icons"));
        }
    }
}
