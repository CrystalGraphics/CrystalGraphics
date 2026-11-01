package com.crystalgraphics.gl.material.parse;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** {@code #pragma cg_permutation}: a shader's runtime keywords parsed, and every combination a precache builds. */
public class CgPermutationDomainTest {

    private static String shader(String pragmas) {
        return "#type none\n"
                + pragmas
                + "\nstruct v2f { vec2 uv; };\n"
                + "Pass {\n"
                + "    void vertex(out v2f o) { gl_Position = vec4(0.0); }\n"
                + "    void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(1.0); }\n"
                + "}\n";
    }

    private static Set<Set<String>> combinations(CgPermutationDomain domain, List<String> fixed) {
        Set<Set<String>> out = new HashSet<>();
        domain.forEach(fixed, k -> assertTrue("each combination once", out.add(new HashSet<>(k))));
        return out;
    }

    @Test
    public void boolsAndOneOfSpanTheirProduct() {
        CgParsedShader parsed = CgShaderParser.parse(shader(
                "#pragma cg_feature KARIS\n#pragma cg_feature CHEAP\n#pragma cg_feature FADE\n#pragma cg_feature LOOK\n"
                        + "#pragma cg_permutation KARIS|CHEAP   // the tier picks one\n#pragma cg_permutation FADE"),
                "test:domain.shader");
        CgPermutationDomain domain = parsed.permutations();
        assertEquals(6, domain.size());
        assertEquals(Set.of(Set.of(), Set.of("KARIS"), Set.of("CHEAP"), Set.of("FADE"), Set.of("KARIS", "FADE"),
                Set.of("CHEAP", "FADE")), combinations(domain, List.of()));
        // A material's own keyword rides every combination; a fixed domain keyword pins its dimension.
        assertEquals(6, combinations(domain, List.of("LOOK")).size());
        assertEquals(Set.of(Set.of("CHEAP"), Set.of("CHEAP", "FADE")), combinations(domain, List.of("CHEAP")));
    }

    @Test
    public void noPragmaIsTheEmptyDomain() {
        CgParsedShader parsed = CgShaderParser.parse(shader("#pragma cg_feature GLOW"), "test:none.shader");
        assertEquals(CgPermutationDomain.EMPTY, parsed.permutations());
        assertEquals(Set.of(Set.of("GLOW")), combinations(parsed.permutations(), List.of("GLOW")));
    }

    @Test
    public void refusesUndeclaredAndRepeatedKeywords() {
        expectFailure(shader("#pragma cg_feature A\n#pragma cg_permutation B"), "not a #pragma cg_feature");
        expectFailure(shader("#pragma cg_feature A\n#pragma cg_feature B\n#pragma cg_permutation A A|B"), "two dimensions");
    }

    @Test
    public void refusesADomainPastItsLimit() {
        StringBuilder pragmas = new StringBuilder();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            names.add("K" + i);
            pragmas.append("#pragma cg_feature K").append(i).append('\n');
        }
        pragmas.append("#pragma cg_permutation ").append(String.join(" ", names));
        expectFailure(shader(pragmas.toString()), "512 combinations");
    }

    private static void expectFailure(String source, String why) {
        try {
            CgShaderParser.parse(source, "test:bad.shader");
            fail("expected a parse failure: " + why);
        } catch (CgShaderParseException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(why));
        }
    }
}
