package com.crystalgraphics.render.world;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class CgSortLayerTest {

    @Test
    public void aLayerSitsWhereItWasPlacedAndTheRestKeepTheirOrder() {
        CgSortLayer under = CgSortLayer.before("test:under_effects", CgSortLayer.EFFECTS);
        CgSortLayer over = CgSortLayer.after("test:over_effects", CgSortLayer.EFFECTS);
        assertTrue(CgSortLayer.BACKGROUND.rank() < CgSortLayer.DEFAULT.rank());
        assertTrue(CgSortLayer.DEFAULT.rank() < under.rank());
        assertTrue(under.rank() < CgSortLayer.EFFECTS.rank());
        assertTrue(CgSortLayer.EFFECTS.rank() < over.rank());
        assertTrue(over.rank() < CgSortLayer.OVERLAY.rank());
    }

    @Test(expected = IllegalArgumentException.class)
    public void anIdIsDefinedOnce() {
        CgSortLayer.after("test:twice", CgSortLayer.DEFAULT);
        CgSortLayer.after("test:twice", CgSortLayer.DEFAULT);
    }
}
