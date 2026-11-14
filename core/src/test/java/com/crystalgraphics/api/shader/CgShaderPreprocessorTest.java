package com.crystalgraphics.api.shader;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CgShaderPreprocessorTest {

    @Test
    public void insertsAfterVersion() {
        Map<String, String> defines = new LinkedHashMap<String, String>();
        defines.put("B", "2");
        defines.put("A", "1");

        CgShaderPreprocessor preprocessor = new CgShaderPreprocessor(defines);
        String result = preprocessor.process("#version 120\nvoid main(){}\n");

        assertTrue(result.startsWith("#version 120\n#define A 1\n#define B 2\n"));
    }

    @Test
    public void insertsAtTopWithoutVersion() {
        Map<String, String> defines = new LinkedHashMap<String, String>();
        defines.put("FOO", "");

        CgShaderPreprocessor preprocessor = new CgShaderPreprocessor(defines);
        String result = preprocessor.process("void main(){}\n");

        assertTrue(result.startsWith("#define FOO\nvoid main(){}\n"));
    }

    @Test
    public void anIncludeThatCannotBeRead_isNamed() {
        String panner = "#include \"crystalgraphics:shaders/lib/material/panner.glsl\"\n";
        String source = panner + "#include \"nosuch:shaders/missing.glsl\"\n";
        CgShaderPreprocessor preprocessor = new CgShaderPreprocessor();
        assertTrue("an unreadable include counts as a mention", preprocessor.mentions(source, "test:a.shader", "cg_SceneColor"));
        String unread = preprocessor.unreadableInclude(source, "test:a.shader");
        assertTrue(unread, unread != null && unread.endsWith("shaders/missing.glsl"));
        assertNull(preprocessor.unreadableInclude(panner, "test:a.shader"));
    }
}
