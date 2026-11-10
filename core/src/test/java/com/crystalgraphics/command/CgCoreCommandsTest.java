package com.crystalgraphics.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.crystalgraphics.platform.command.CgCommandSource;
import com.crystalgraphics.platform.command.CgCommands;
import com.crystalgraphics.variable.CgVar;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class CgCoreCommandsTest {

    private static final CgVar.Int BATCH = CgVar.integer("test.cgcore.batch", 4, 1, 16, "A test variable");
    private static final CgVar.Bool LOCKED = CgVar.bool("test.cgcore.locked", false, CgVar.READ_ONLY, "A read-only switch");

    private final List<String> said = new ArrayList<>();
    private final List<String> failed = new ArrayList<>();

    private CgCommandSource source(int level) {
        return CgCommandSource.of(CgCommandSource.Kind.CLIENT, "tester", level, said::add, failed::add);
    }

    @Before
    public void register() {
        CgCommands.client(new CgCoreCommands("cg"));
    }

    @After
    public void reset() {
        BATCH.clear(CgVar.Source.CONSOLE);
    }

    @Test
    public void setThenGetThenReset() {
        assertEquals(1, CgCommands.run(CgCommands.Side.CLIENT, source(4), "cg var set test.cgcore.batch 9"));
        assertEquals(9, BATCH.get());
        said.clear();
        CgCommands.run(CgCommands.Side.CLIENT, source(0), "cg var get test.cgcore.batch");
        assertTrue(said.get(0), said.get(0).startsWith("test.cgcore.batch = 9 (console)"));
        CgCommands.run(CgCommands.Side.CLIENT, source(4), "cg var reset test.cgcore.batch");
        assertEquals(4, BATCH.get());
    }

    @Test
    public void aChangeNeedsTheLevel() {
        assertEquals(0, CgCommands.run(CgCommands.Side.CLIENT, source(0), "cg var set test.cgcore.batch 9"));
        assertEquals(4, BATCH.get());
        assertFalse(failed.isEmpty());
    }

    @Test
    public void aValueOutOfTheRangeIsRefusedByName() {
        assertEquals(0, CgCommands.run(CgCommands.Side.CLIENT, source(4), "cg var set test.cgcore.batch banana"));
        assertTrue(failed.get(0), failed.get(0).contains("test.cgcore.batch takes"));
    }

    @Test
    public void aReadOnlyVariableRefusesASet() {
        assertEquals(0, CgCommands.run(CgCommands.Side.CLIENT, source(4), "cg var set test.cgcore.locked true"));
        assertTrue(failed.get(0), failed.get(0).contains("read-only"));
    }

    @Test
    public void anUnknownVariableIsNamed() {
        assertEquals(0, CgCommands.run(CgCommands.Side.CLIENT, source(4), "cg var get test.cgcore.nope"));
        assertTrue(failed.get(0), failed.get(0).contains("Unknown variable 'test.cgcore.nope'"));
    }

    @Test
    public void listFiltersByPrefix() {
        CgCommands.run(CgCommands.Side.CLIENT, source(0), "cg var list test.cgcore");
        assertEquals(2, said.size());
    }

    @Test
    public void valuesAreSuggestedForASwitch() throws Exception {
        List<String> values = CgCommands.suggest(CgCommands.Side.CLIENT, source(4), "cg var set test.cgcore.locked t", 31)
                .get().getList().stream().map(s -> s.getText()).collect(Collectors.toList());
        assertEquals(java.util.Collections.singletonList("true"), values);
    }

    @Test
    public void setIsNotOfferedBelowTheLevel() throws Exception {
        List<String> words = CgCommands.suggest(CgCommands.Side.CLIENT, source(0), "cg var ", 7)
                .get().getList().stream().map(s -> s.getText()).collect(Collectors.toList());
        assertFalse(words.toString(), words.contains("set"));
        assertTrue(words.toString(), words.contains("get"));
    }
}
