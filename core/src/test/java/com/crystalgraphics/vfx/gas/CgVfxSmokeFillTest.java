package com.crystalgraphics.vfx.gas;

import org.junit.Test;

import static org.junit.Assert.*;

/** A smoke's flood: round walls, through a door, down before up, within its budget, the same cells after the wire. */
public class CgVfxSmokeFillTest {

    /** A floor at cell y 0, a ceiling at 2, and a wall of cells at x = 8, with a door at z -1..1 when asked. */
    private static CgVfxSmokeFill.Solids room(boolean door) {
        return (x, y, z) -> y < 0 || y >= 2 || x == 8 && !(door && Math.abs(z) <= 1);
    }

    @Test
    public void aWallHoldsItBack() {
        CgVfxSmokeFill fill = CgVfxSmokeFill.flood(room(false), 0.25, 0.25, 0.25, 4000, 30f);
        for (int i = 0; i < fill.count(); i++) {
            assertTrue("no cell beyond the wall", fill.originX() + fill.offsetX(i) < 8);
            assertTrue("none under the floor", fill.originY() + fill.offsetY(i) >= 0);
        }
    }

    @Test
    public void itPoursThroughADoor() {
        CgVfxSmokeFill fill = CgVfxSmokeFill.flood(room(true), 0.25, 0.25, 5.25, 20000, 30f);
        int beyond = 0;
        for (int i = 0; i < fill.count(); i++) if (fill.originX() + fill.offsetX(i) > 8) beyond++;
        assertTrue("cells past the door", beyond > 0);
        // As far past the wall as into the open, but round through the door: more than twice as dear.
        assertEquals(9 * CgVfxSmokeFill.ACROSS, fill.cost(-9, 0, 10));
        assertTrue(fill.cost(9, 0, 10) > 2 * fill.cost(-9, 0, 10));
    }

    @Test
    public void itSpreadsRoundInTheOpen() {
        CgVfxSmokeFill fill = CgVfxSmokeFill.flood((x, y, z) -> y < 0, 0.25, 0.25, 0.25, 20000, 30f);
        // A cell 20 out along an axis and one 20 out along the diagonal cost what their distances do, within a tenth.
        float axis = fill.cost(20, 0, 0), diagonal = fill.cost(14, 0, 14);
        assertEquals(20f * CgVfxSmokeFill.ACROSS, axis, 0f);
        assertEquals(axis * (float) Math.hypot(14, 14) / 20f, diagonal, axis * 0.1f);
    }

    @Test
    public void downIsCheaperThanUp() {
        CgVfxSmokeFill fill = CgVfxSmokeFill.flood((x, y, z) -> false, 0.25, 5.25, 0.25, 4000, 30f);
        int start = fill.originY();
        assertEquals(4 * CgVfxSmokeFill.DOWN, fill.cost(fill.originX(), start - 4, fill.originZ()));
        assertEquals(4 * CgVfxSmokeFill.UP, fill.cost(fill.originX(), start + 4, fill.originZ()));
    }

    @Test
    public void itKeepsToItsBudgetCheapestFirst() {
        CgVfxSmokeFill fill = CgVfxSmokeFill.flood(room(false), 0.25, 0.25, 0.25, 500, 30f);
        assertEquals(500, fill.count());
        for (int i = 1; i < fill.count(); i++) assertTrue(fill.cost(i) >= fill.cost(i - 1));
    }

    @Test
    public void itStartsAboveASolidLandingAndFillsNothingInRock() {
        CgVfxSmokeFill lifted = CgVfxSmokeFill.flood((x, y, z) -> y < 2, 0.25, 0.25, 0.25, 100, 10f);
        assertEquals(2, lifted.originY());
        assertTrue(lifted.count() > 0);
        assertEquals(0, CgVfxSmokeFill.flood((x, y, z) -> true, 0.25, 0.25, 0.25, 100, 10f).count());
    }

    @Test
    public void theWireCarriesEveryCell() {
        CgVfxSmokeFill fill = CgVfxSmokeFill.flood(room(true), 3.1, 0.6, -2.2, 3000, 20f);
        CgVfxSmokeFill same = CgVfxSmokeFill.decode(fill.encode());
        assertEquals(fill.count(), same.count());
        assertEquals(fill.mostCost(), same.mostCost());
        for (int i = 0; i < fill.count(); i++) {
            int x = fill.originX() + fill.offsetX(i), y = fill.originY() + fill.offsetY(i), z = fill.originZ() + fill.offsetZ(i);
            assertEquals(fill.cost(i), same.cost(x, y, z));
        }
        assertEquals(-1, same.cost(fill.originX() + 100, 0, 0));
    }
}
