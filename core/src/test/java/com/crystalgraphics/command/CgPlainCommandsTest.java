package com.crystalgraphics.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.crystalgraphics.platform.command.CgCommandSource;
import com.crystalgraphics.variable.CgVar;
import org.junit.After;
import org.junit.Test;

public class CgPlainCommandsTest {

    private static final CgVar.Int BATCH = CgVar.integer("test.cgplain.batch", 4, 1, 16, "A test variable");
    private static final CgVar.Bool FLAG = CgVar.bool("test.cgplain.flag", false, "A test switch");

    private final List<String> said = new ArrayList<>();
    private final List<String> failed = new ArrayList<>();

    private CgCommandSource source(int level) {
        return CgCommandSource.of(CgCommandSource.Kind.PLAYER, "tester", level, said::add, failed::add);
    }

    @After
    public void reset() {
        BATCH.clear(CgVar.Source.CONSOLE);
    }

    @Test
    public void setThenGet() {
        assertEquals(1, CgPlainCommands.run(source(2), "var set test.cgplain.batch 9".split(" ")));
        assertEquals(9, BATCH.get());
        said.clear();
        CgPlainCommands.run(source(0), "var get test.cgplain.batch".split(" "));
        assertTrue(said.get(0), said.get(0).startsWith("test.cgplain.batch = 9 (console)"));
    }

    @Test
    public void aChangeNeedsTheLevel() {
        assertEquals(0, CgPlainCommands.run(source(1), "var set test.cgplain.batch 9".split(" ")));
        assertEquals(4, BATCH.get());
        assertTrue(failed.get(0), failed.get(0).contains("operator level"));
    }

    @Test
    public void aLineItCannotReadSendsTheUsage() {
        assertEquals(0, CgPlainCommands.run(source(4), new String[] {"var"}));
        assertTrue(failed.get(0), failed.get(0).startsWith("Usage:"));
    }

    @Test
    public void completesWordsVariablesAndValues() {
        assertEquals(Arrays.asList("var"), CgPlainCommands.complete(source(0), new String[] {"v"}));
        assertEquals(Arrays.asList("set"), CgPlainCommands.complete(source(2), new String[] {"var", "s"}));
        assertEquals(Collections.emptyList(), CgPlainCommands.complete(source(0), new String[] {"var", "s"}));
        assertEquals(Arrays.asList("test.cgplain.batch"), CgPlainCommands.complete(source(0), new String[] {"var", "get", "test.cgplain.b"}));
        assertEquals(Arrays.asList("true"), CgPlainCommands.complete(source(2), new String[] {"var", "set", "test.cgplain.flag", "t"}));
        assertFalse(CgPlainCommands.complete(source(0), new String[] {""}).contains("reload"));
    }
}
