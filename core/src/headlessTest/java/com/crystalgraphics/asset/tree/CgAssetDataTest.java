package com.crystalgraphics.asset.tree;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgParticleKinds;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/** Inheritance, on the format's own example: what resolves, from where, and the delta. */
public class CgAssetDataTest {

    private static final String PARENT = "_v: 1\n"
            + "units: { length: m }\n"
            + "emitters:\n"
            + "  - { id: h1, name: core, emitter: { particle_spawn: [ { id: k3q9x2ab, module: crystalgraphics:sprite_size, inputs: { sprite_size: 1.0 } } ] } }\n"
            + "  - { id: h2, name: sparks, emitter: dragonforge:kamehameha/sparks }\n"
            + "  - { id: p0fr8m1c, name: fringe, emitter: dragonforge:kamehameha/fringe }\n";

    private static final String CHILD = "_v: 1\n"
            + "parent: dragonforge:kamehameha/charge\n"
            + "emitters:\n"
            + "  - id: h1\n"
            + "    emitter:\n"
            + "      particle_spawn:\n"
            + "        - { id: k3q9x2ab, inputs: { sprite_size: { range: [1.1, 1.3] } } }\n"
            + "  - { id: r7m2n0qe, after: h1, name: ring, emitter: dragonforge:kamehameha/ring }\n"
            + "remove: [p0fr8m1c]\n";

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    private static CgAssetData parent() {
        return CgAssetData.of("dragonforge:kamehameha/charge", CgParticleKinds.SYSTEM, tree(PARENT), null);
    }

    private static CgAssetData child() {
        return CgAssetData.of("dragonforge:kamehameha/charge_big", CgParticleKinds.SYSTEM, tree(CHILD), parent());
    }

    private static Map<?, ?> tree(String text) {
        return (Map<?, ?>) CgAssetYaml.read(text);
    }

    @Test
    public void aChildReadsAsItsParentWithItsDeltaApplied() {
        Map<Object, Object> resolved = child().resolved();
        List<Object> names = new ArrayList<>();
        for (Object e : (List<?>) resolved.get("emitters")) names.add(((Map<?, ?>) e).get("name"));
        assertEquals("a new id goes after the one it names; a removed one is gone", listOf("core", "ring", "sparks"), names);
        Map<?, ?> h1 = (Map<?, ?>) ((List<?>) resolved.get("emitters")).get(0);
        Map<?, ?> module = (Map<?, ?>) ((List<?>) ((Map<?, ?>) h1.get("emitter")).get("particle_spawn")).get(0);
        assertEquals("the module keeps its parent's fields", "crystalgraphics:sprite_size", module.get("module"));
        assertEquals("and takes the child's input, a form replacing a value whole",
                "{range=[1.1, 1.3]}", ((Map<?, ?>) module.get("inputs")).get("sprite_size").toString());
        assertEquals("a child's units are its parent's", "{length=m}", resolved.get("units").toString());
    }

    @Test
    public void everyValueKnowsWhereItComesFrom() {
        CgAssetData big = child();
        assertEquals(CgOrigin.INHERITED, big.origin("emitters", "h2"));
        assertEquals("dragonforge:kamehameha/charge", big.from("emitters", "h2", "name"));
        assertEquals(CgOrigin.ADDED, big.origin("emitters", "r7m2n0qe"));
        assertEquals(CgOrigin.REMOVED, big.origin("emitters", "p0fr8m1c"));
        assertEquals(CgOrigin.OVERRIDDEN, big.origin("emitters", "h1", "emitter", "particle_spawn", "k3q9x2ab", "inputs", "sprite_size"));
        assertEquals(CgOrigin.INHERITED, big.origin("emitters", "h1", "name"));
        assertEquals("an override below makes its holders modified", CgOrigin.MODIFIED, big.origin("emitters", "h1"));
        assertEquals(CgOrigin.MODIFIED, big.origin("emitters", "h1", "emitter", "particle_spawn"));
        assertEquals(CgOrigin.DEFAULT, big.origin("emitters", "h1", "enabled"));
    }

    @Test
    public void theDeltaIsTheFileAndWritesBackToItsBytes() {
        CgAssetData big = child();
        String file = CgAssetYaml.write(tree(CHILD), CgParticleKinds.SYSTEM);
        assertEquals(file, CgAssetYaml.write(big.delta(), CgParticleKinds.SYSTEM));
    }

    @Test
    public void anEnabledFalseDisablesAndKeepsTheElement() {
        String child = "_v: 1\nparent: dragonforge:kamehameha/charge\nemitters:\n  - { id: h2, enabled: false }\n";
        CgAssetData data = CgAssetData.of("x:y", CgParticleKinds.SYSTEM, tree(child), parent());
        assertEquals(CgOrigin.DISABLED, data.origin("emitters", "h2"));
        assertEquals(3, ((List<?>) data.resolved().get("emitters")).size());
    }

    @Test
    public void aChildDeclaringOtherUnitsFailsNamingBoth() {
        String child = "_v: 1\nparent: dragonforge:kamehameha/charge\nunits: { length: cm }\n";
        try {
            CgAssetData.of("x:y", CgParticleKinds.SYSTEM, tree(child), parent());
            fail("other units loaded");
        } catch (CgAssetException e) {
            assertEquals(true, e.getMessage().contains("x:y") && e.getMessage().contains("dragonforge:kamehameha/charge"));
        }
    }

    @Test
    public void removingAnIdTheParentLacksFails() {
        String child = "_v: 1\nparent: dragonforge:kamehameha/charge\nremove: [nope]\n";
        try {
            CgAssetData.of("x:y", CgParticleKinds.SYSTEM, tree(child), parent());
            fail("removed an id the parent lacks");
        } catch (CgAssetException e) {
            assertEquals(true, e.getMessage().contains("nope"));
        }
    }

    private static List<Object> listOf(Object... xs) {
        List<Object> out = new ArrayList<>();
        for (Object x : xs) out.add(x);
        return out;
    }
}
