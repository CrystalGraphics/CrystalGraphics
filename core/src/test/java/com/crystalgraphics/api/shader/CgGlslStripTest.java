package com.crystalgraphics.api.shader;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CgGlslStripTest {

    private static final String SOURCE = String.join("\n",
            "#version 430 core",
            "#extension GL_KHR_shader_subgroup_basic : enable",
            "// a comment",
            "#define CG_COMPUTE_STAGE 1",
            "#define FEATURE 2 /* two */",
            "#define UNUSED_MACRO 7",
            "#define CALLS_VIA_MACRO(x) viaMacro(x)",
            "#if !defined(CG_VERTEX_STAGE) && FEATURE > 1",
            "float picked() { return 1.0; }",
            "#else",
            "float picked() { return dropped(); }",
            "#endif",
            "#ifdef GL_KHR_shader_subgroup_basic",
            "float driver() { return 1.0; }",
            "#else",
            "float driver() { return 2.0; }",
            "#endif",
            "float viaMacro(float x) { return x; }",
            "float helper(float x);",
            "float helper(float x) { return x * picked(); }",
            "float helper(vec2 x) { return x.x; }",
            "float dead(float x) {",
            "    return x;   // never called",
            "}",
            "layout(std430) buffer Out { float values[]; };",
            "void main() {",
            "    values[0] = helper(2.0) + driver() + CALLS_VIA_MACRO(1.0);",
            "}",
            "");

    @Test
    public void stripsWhatTheDriverNeverUsesAndKeepsEveryLine() {
        String out = CgGlslStrip.strip(SOURCE);
        assertFalse("no blank lines", out.contains("\n\n"));
        for (String kept : new String[]{"float viaMacro(float x) { return x; }", "void main() {", "float helper(vec2 x) { return x.x; }"}) {
            assertEquals("the driver's line for " + kept, lineOf(SOURCE, kept), driverLineOf(out, kept));
        }
        assertFalse("comments", out.contains("//") || out.contains("/*"));
        assertFalse("the branch its macros decide against", out.contains("dropped()") || out.contains("#else\nfloat picked"));
        assertTrue(out.contains("float picked() { return 1.0; }"));
        assertTrue("a driver macro's branches", out.contains("#ifdef GL_KHR_shader_subgroup_basic") && out.contains("return 2.0"));
        assertFalse("a function nothing reaches", out.contains("dead("));
        assertFalse("a macro nothing names", out.contains("UNUSED_MACRO"));
        assertTrue("reached through a macro", out.contains("float viaMacro(float x)"));
        assertTrue("a reached name's overloads", out.contains("float helper(float x);") && out.contains("float helper(vec2 x)"));
        assertTrue(out.contains("buffer Out") && out.contains("void main()"));
        assertEquals("stripping is idempotent", out, CgGlslStrip.strip(out));
    }

    private static int lineOf(String text, String line) {
        return Arrays.asList(text.split("\n", -1)).indexOf(line) + 1;
    }

    /** The line number a driver gives {@code line}, through the {@code #line} directives before it. */
    private static int driverLineOf(String text, String line) {
        int number = 1;
        for (String l : text.split("\n", -1)) {
            if (l.equals(line)) return number;
            number = l.startsWith("#line ") ? Integer.parseInt(l.substring(6).trim()) : number + 1;
        }
        return -1;
    }

    @Test
    public void tokenPastingKeepsEveryFunction() {
        String source = "#define CALL(n) f_##n()\nfloat f_a() { return 1.0; }\nvoid main() { CALL(a); }\n";
        assertTrue(CgGlslStrip.strip(source).contains("float f_a()"));
    }

    @Test
    public void anUnclosedConditionalLeavesOnlyCommentsStripped() {
        String source = "#if 1 // open\nvoid unused() {}\nvoid main() {}\n";
        String out = CgGlslStrip.strip(source);
        assertTrue(out.contains("void unused()") && !out.contains("open"));
    }
}
