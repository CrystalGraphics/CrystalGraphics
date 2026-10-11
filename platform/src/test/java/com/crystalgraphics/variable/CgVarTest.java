package com.crystalgraphics.variable;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Sources in priority, ranges, parsing, -D at declaration, read-only, restart and change callbacks. */
public class CgVarTest {

    enum Mode { FAST, EXACT }

    @Test
    public void theHighestSourceHoldingAValueWins() {
        CgVar.Int v = CgVar.integer("test.var.priority", 2, 1, 8, "test");
        assertEquals(2, v.get());
        assertNull(v.source());
        v.set(CgVar.Source.FILE, 3);
        v.set(CgVar.Source.CONSOLE, 5);
        v.set(CgVar.Source.CODE, 4);
        assertEquals(5, v.get());
        assertSame(CgVar.Source.CONSOLE, v.source());
        v.clear(CgVar.Source.CONSOLE);
        assertEquals(3, v.get());
        v.clear(CgVar.Source.FILE);
        assertEquals(4, v.get());
        v.clear(CgVar.Source.CODE);
        assertEquals(2, v.get());
    }

    @Test
    public void numbersClampAndTextParses() {
        CgVar.Num v = CgVar.number("test.var.range", 0.5f, 0f, 1f, "test");
        v.set(4f);
        assertEquals(1f, v.get(), 0f);
        assertFalse(v.setText(CgVar.Source.CONSOLE, "lots"));
        assertTrue(v.setText(CgVar.Source.CONSOLE, " 0.25 "));
        assertEquals(0.25f, v.get(), 0f);
        CgVar.Choice<Mode> mode = CgVar.choice("test.var.choice", Mode.FAST, "test");
        assertTrue(mode.setText(CgVar.Source.CONSOLE, "Exact"));
        assertSame(Mode.EXACT, mode.get());
        assertEquals("exact", mode.text());
        assertEquals("fast, exact", mode.range());
    }

    @Test
    public void aPropertyIsReadAtDeclarationAndOutranksTheFile() {
        System.setProperty("test.var.property", "7");
        System.setProperty("test.var.bare", "");
        System.setProperty("test.var.bad", "seven");
        CgVar.Int v = CgVar.integer("test.var.property", 2, 0, 10, "test");
        assertEquals(7, v.get());
        v.set(CgVar.Source.FILE, 3);
        assertEquals(7, v.get());
        assertTrue(CgVar.bool("test.var.bare", false, "test").get());
        assertEquals(2, CgVar.integer("test.var.bad", 2, 0, 10, "test").get());
    }

    @Test
    public void readOnlyRefusesTheConsoleAndRemote() {
        CgVar.Bool v = CgVar.bool("test.var.readonly", false, CgVar.READ_ONLY, "test");
        assertFalse(v.set(CgVar.Source.CONSOLE, true));
        assertFalse(v.set(CgVar.Source.REMOTE, true));
        assertFalse(v.get());
        assertTrue(v.set(CgVar.Source.CODE, true));
        assertTrue(v.get());
    }

    @Test
    public void aRestartVariableKeepsItsFirstReadValue() {
        CgVar.Int v = CgVar.integer("test.var.restart", 1, 0, 9, CgVar.RESTART, "test");
        v.set(CgVar.Source.CODE, 2);
        assertEquals(2, v.get());
        v.set(CgVar.Source.CONSOLE, 3);
        assertEquals(2, v.get());
        assertEquals(Integer.valueOf(3), v.valueAt(CgVar.Source.CONSOLE));
    }

    @Test
    public void listenersHearEachChangeOnce() {
        CgVar.Text v = CgVar.text("test.var.listen", "", "test");
        List<String> heard = new ArrayList<>();
        v.onChange(heard::add);
        v.set("a");
        v.set("a");
        v.set(CgVar.Source.CONSOLE, "b");
        v.clear(CgVar.Source.CONSOLE);
        assertEquals(List.of("a", "b", "a"), heard);
        assertTrue(v.isSet());
    }

    @Test
    public void theRegistryListsEachIdOnce() {
        CgVar.Bool v = CgVar.bool("test.var.listed", true, CgVar.DEV, "test");
        assertSame(v, CgVars.get("test.var.listed"));
        assertTrue(CgVars.under("test.var.").contains(v));
        try {
            CgVar.bool("test.var.listed", true, "again");
            fail("declared twice");
        } catch (IllegalStateException expected) {
        }
        CgVars.unregister(v);
        assertNull(CgVars.get("test.var.listed"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void anIdIsDottedWords() {
        CgVar.bool("novar", true, "test");
    }
}
