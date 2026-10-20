package com.crystalgraphics.asset.codec;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.tree.CgAssetException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The writer's rules, the cooked form and the {@code _v} steps. */
public class CgAssetYamlTest {

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    @Test
    public void scalarsAreSpelledAsTheRulesSay() {
        assertEquals("1.0", CgAssetYaml.scalar(1.0));
        assertEquals("0.1", CgAssetYaml.scalar(0.1));
        assertEquals("0.0001", CgAssetYaml.scalar(1e-4));
        assertEquals("1.0e-05", CgAssetYaml.scalar(1e-5));
        assertEquals("1000000000000000.0", CgAssetYaml.scalar(1e15));
        assertEquals("2.5e+16", CgAssetYaml.scalar(2.5e16));
        assertEquals("0.019999999552965164", CgAssetYaml.scalar((double) 0.02f));
        assertEquals("-0.0", CgAssetYaml.scalar(-0.0));
        assertEquals(".nan", CgAssetYaml.scalar(Double.NaN));
        assertEquals("plain_text/ok", CgAssetYaml.scalar("plain_text/ok"));
        assertEquals("\"no\"", CgAssetYaml.scalar("no"));
        assertEquals("\"25tdy48m\"", CgAssetYaml.scalar("25tdy48m"));
        assertEquals("\"a\\\"b\\n\"", CgAssetYaml.scalar("a\"b\n"));
        assertEquals("0xFFFFA21A", CgAssetYaml.scalar(4294943258L, "argb"));
    }

    @Test
    public void anIdListIsALineAnElementAndAValueFlowsOnItsKeysLine() {
        String text = "_v: 1\n"
                + "user: { size: { range: [0.5, 1.0] }, tint: { argb: 0xFFFF0000, intensity: 2.0 } }\n"
                + "emitters:\n"
                + "  - { id: h1, name: core, emitter: dragonforge:a }\n";
        assertEquals(text, CgAssetYaml.write(CgAssetYaml.read(text), CgParticleKinds.SYSTEM));
    }

    @Test
    public void keysTakeTheirDeclaredOrderAndEditorClosesTheMap() {
        Map<Object, Object> tree = new LinkedHashMap<>();
        tree.put("editor", new LinkedHashMap<>());
        tree.put("emitters", new java.util.ArrayList<>());
        tree.put("_v", 1);
        assertEquals("_v: 1\nemitters: []\neditor: {}\n", CgAssetYaml.write(tree, CgParticleKinds.SYSTEM));
    }

    @Test
    public void commentsMoveIntoEditor() {
        String text = "# the charge, bigger\n_v: 1\nemitters:\n  # the core\n  - { id: h1, name: core }   # its glow\n";
        Map<?, ?> tree = (Map<?, ?>) CgAssetYaml.read(text);
        assertEquals("the charge, bigger", ((Map<?, ?>) tree.get("editor")).get("note"));
        Map<?, ?> h1 = (Map<?, ?>) ((java.util.List<?>) tree.get("emitters")).get(0);
        assertEquals("the core; its glow", ((Map<?, ?>) h1.get("editor")).get("note"));
    }

    @Test
    public void aCookedAssetIsItsTreeWithoutEditor() {
        Map<?, ?> tree = (Map<?, ?>) CgAssetYaml.read("_v: 1\nemitters:\n  - { id: h1, editor: { note: x } }\neditor: { note: y }\n");
        Map<Object, Object> cooked = CgAssetBinary.read(CgAssetBinary.cook(tree));
        assertFalse(cooked.containsKey("editor"));
        assertEquals("[{id=h1}]", cooked.get("emitters").toString());
        assertTrue("the source tree is left whole", tree.containsKey("editor"));
    }

    @Test
    public void aFileNewerThanTheCodeFailsNamingBothVersions() {
        try {
            CgAssetVersions.upgrade((Map<?, ?>) CgAssetYaml.read("_v: 9\n"), CgParticleKinds.SYSTEM);
            fail("a newer file loaded");
        } catch (CgAssetException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("_v 9") && e.getMessage().contains("_v 1"));
        }
        assertEquals("{_v=1, emitters=[]}", CgAssetVersions.upgrade(
                (Map<?, ?>) CgAssetYaml.read("_v: 1\nemitters: []\n"), CgParticleKinds.SYSTEM).toString());
    }
}
