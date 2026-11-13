package com.crystalgraphics.material.shader.syntax;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** The lexer and the tree: what every file of the shader family is read into before any domain sees it. */
public class CgShaderSyntaxTest {

    private static CgShaderSyntax read(String source) {
        return CgShaderSyntax.read(CgShaderTokens.lex(source, "test:a.shader"), CgShaderSyntax.MATERIAL);
    }

    private static CgShaderError refused(String source) {
        try {
            read(source);
        } catch (CgShaderError e) {
            return e;
        }
        throw new AssertionError("read: " + source);
    }

    @Test
    public void tokensSkipCommentsKeepSpansAndMatchBrackets() {
        CgShaderTokens t = CgShaderTokens.lex("a(b[1]) { /* x */ c; } // y\n\"s\" 1.5e-3", "test:t");
        assertEquals("a", t.text(0));
        assertEquals(6, t.close(1));
        assertEquals(5, t.close(3));
        assertEquals(10, t.close(7));
        assertEquals(CgShaderTokens.STRING, t.kind(11));
        assertEquals("1.5e-3", t.text(12));
        assertEquals(13, t.count());
    }

    @Test
    public void aDirectiveIsOneTokenWithoutItsTrailingComment() {
        CgShaderTokens t = CgShaderTokens.lex("#define A(x) \\\n  (x + 1) // note\nfloat b;", "test:t");
        assertTrue(t.isDirective(0, "define"));
        assertEquals("#define A(x) \\\n  (x + 1)", t.text(0));
        assertTrue(t.isIdent(1, "float"));
    }

    @Test
    public void plainTextKeepsLineBreaksAndDropsComments() {
        CgShaderTokens t = CgShaderTokens.lex("float f() { // c\n  return 1.0; /* d */ }", "test:t");
        assertEquals("float f() {\nreturn 1.0; }", t.plain(0, t.count() - 1));
    }

    @Test
    public void itemsAreBlocksStructsFunctionsDirectivesAndDeclarations() {
        CgShaderSyntax f = read("""
                #domain surface
                #include "x:y.glsl"
                Material { BlendMode Translucent }
                struct v2f { vec2 uv0; };
                const float K = 2.0;
                float helper(float x) { return x * K; }
                void surface(in v2f i, inout CgSurface s) { s.baseColor = vec3(helper(i.uv0.x)); }
                """);
        assertEquals("surface", f.domainWord());
        List<CgShaderSyntax.Item> items = f.items();
        assertTrue(items.get(0) instanceof CgShaderSyntax.Directive);
        assertTrue(items.get(1) instanceof CgShaderSyntax.Block b && b.name().equals("Material"));
        assertTrue(items.get(2) instanceof CgShaderSyntax.Struct s && s.name().equals("v2f"));
        assertTrue(items.get(3) instanceof CgShaderSyntax.Declaration);
        assertTrue(items.get(4) instanceof CgShaderSyntax.Function fn && fn.name().equals("helper"));
        assertTrue(items.get(5) instanceof CgShaderSyntax.Function fn && fn.name().equals("surface"));
        assertNotNull(f.block("Material"));
        assertNotNull(f.struct("v2f"));
    }

    @Test
    public void aStepsSettingsEndWhereItsCodeBegins() {
        CgShaderSyntax f = read("""
                #domain post
                Step bright {
                    Target RGBA16_FLOAT  Scale 0.5
                    Spark helper(float x) { return Spark(x); }
                    void surface(in CgScreen i, inout CgPostSurface s) { }
                }
                """);
        CgShaderSyntax.Step step = (CgShaderSyntax.Step) f.items().get(0);
        assertEquals("bright", step.name());
        assertEquals(2, step.items().size());
        assertEquals("Spark", f.tokens().text(step.settingsEnd()));
    }

    @Test
    public void refusalsArePlacedOnTheirToken() {
        CgShaderError pass = refused("#domain surface\n\nPass { }\n");
        assertEquals(3, pass.line());
        assertEquals(1, pass.column());
        assertTrue(pass.getMessage(), pass.getMessage().startsWith("test:a.shader:3:1: 'Pass' is not written"));

        CgShaderError pragma = refused("#domain surface\n  #pragma cg_feature A\n");
        assertEquals(2, pragma.line());
        assertEquals(3, pragma.column());

        CgShaderError open = refused("#domain surface\nvoid f() {\n");
        assertEquals(2, open.line());
        assertTrue(open.getMessage(), open.getMessage().contains("'{' is never closed"));

        CgShaderError twice = refused("#domain surface\nMaterial { }\nMaterial { }\n");
        assertEquals(3, twice.line());
    }

    @Test
    public void aMaterialOpensWithItsDomain() {
        CgShaderError e = refused("// header\nMaterial { }\n");
        assertFalse(e.placed());
        assertTrue(e.getMessage(), e.getMessage().contains("opens with its #domain line"));
    }
}
