package com.crystalgraphics.render.world;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialManifest;
import com.crystalgraphics.api.material.CgMaterialPermutation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The program list: warmed under the settings asked for, re-warmed before a toggle applies, never held for ever. */
public class CgWorldProgramsTest {

    private static final String SOURCE = String.join(System.lineSeparator(), "#type spatial", "#pragma cg_feature FOG_ON",
            "#pragma cg_feature NORMAL_MAP", "Properties { _Color (\"Color\", color) = (1, 1, 1, 1) }",
            "Pass {", "    void vertex(out v2f o) { }",
            "    void fragment(in v2f i, out vec4 fragColor) { fragColor = _Color; }", "}");
    private static String shader;
    private static final CgWorldPrograms.Settings HDR = new CgWorldPrograms.Settings(true, true, true, true);
    private static final CgWorldPrograms.Settings LDR = new CgWorldPrograms.Settings(false, true, true, true);

    /** Builds a material the second time it is asked under the same settings. */
    private static final class Fake implements CgWorldPrograms.Preparer {
        final Set<Object> seen = new HashSet<>();
        final List<CgWorldPrograms.Settings> asked = new ArrayList<>();
        boolean never;

        @Override
        public boolean prepare(CgMaterial material, boolean half, CgWorldPrograms.Settings settings) {
            asked.add(settings);
            return !never && !seen.add(Arrays.asList(material, settings));
        }
    }

    @BeforeClass
    public static void writeShader() throws IOException {
        Path file = Files.createTempFile("programs", ".shader");
        Files.write(file, SOURCE.getBytes(StandardCharsets.UTF_8));
        file.toFile().deleteOnExit();
        shader = file.toAbsolutePath().toString();
    }

    private static CgMaterialManifest manifest() {
        CgMaterialPermutation p = CgMaterialPermutation.of(shader, Arrays.asList("FOG_ON"), "opaque", false, null);
        return CgMaterialManifest.builder().add(p, null, false).add(p, null, false).add(p, "NORMAL_MAP", true).build();
    }

    @Test
    public void aManifestWarmsEachUseOnceAndAnswersWhenBuilt() {
        Fake fake = new Fake();
        CgWorldPrograms programs = new CgWorldPrograms(fake);
        CgWorldPrograms.Warming move = programs.warm(manifest());
        assertEquals(2, programs.size());
        assertFalse(move.ready());
        assertTrue(programs.frame(HDR, HDR));
        assertFalse(move.ready());
        programs.frame(HDR, HDR);
        assertTrue(move.ready());
        programs.warm(manifest());
        assertEquals("a second manifest naming them shares the materials", 2, programs.size());
    }

    @Test
    public void aToggleWaitsForTheListWarmedUnderIt() {
        Fake fake = new Fake();
        CgWorldPrograms programs = new CgWorldPrograms(fake);
        programs.warm(manifest());
        programs.frame(HDR, HDR);
        programs.frame(HDR, HDR);
        assertEquals(0, programs.pending());
        fake.asked.clear();
        assertFalse("the list re-warms before the toggle applies", programs.frame(LDR, HDR));
        assertEquals(Arrays.asList(LDR, LDR), fake.asked);
        assertTrue(programs.frame(LDR, HDR));
        assertTrue(programs.frame(LDR, LDR));
    }

    @Test
    public void aToggleNeverWaitsForEver() {
        Fake fake = new Fake();
        fake.never = true;
        CgWorldPrograms programs = new CgWorldPrograms(fake);
        programs.warm(manifest());
        int frames = 1;
        while (!programs.frame(LDR, HDR)) frames++;
        assertEquals(CgWorldPrograms.MAX_WAIT_FRAMES + 1, frames);
    }

    @Test
    public void anEmptyListAppliesAtOnce() {
        CgWorldPrograms programs = new CgWorldPrograms(new Fake());
        assertTrue(programs.frame(LDR, HDR));
    }
}
