package com.crystalgraphics.asset.tree;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.object.CgPropertyPath;
import com.crystalgraphics.object.CgType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Edits, their inverses and their change class on a parent and a child. */
public class CgAssetEditTest {

    private static final String PARENT_ID = "dragonforge:kamehameha/charge", CHILD_ID = "dragonforge:kamehameha/charge_big";
    private static final String PARENT = "_v: 1\n"
            + "delay: 0.5\n"
            + "emitters:\n"
            + "  - { id: h1, name: core, emitter: { particle_spawn: [ { id: k3q9x2ab, module: crystalgraphics:sprite_size, inputs: { sprite_size: 1.0, tint: [1.0, 0.5, 0.0] } } ] } }\n"
            + "  - { id: h2, name: sparks, emitter: dragonforge:kamehameha/sparks }\n"
            + "  - { id: p0fr8m1c, name: fringe, emitter: dragonforge:kamehameha/fringe }\n";
    private static final String CHILD = "_v: 1\n"
            + "parent: dragonforge:kamehameha/charge\n"
            + "emitters:\n"
            + "  - { id: r7m2n0qe, after: h1, name: ring, emitter: dragonforge:kamehameha/ring }\n";

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    private static CgAssetData parent() {
        return CgAssetData.of(PARENT_ID, CgParticleKinds.SYSTEM, (Map<?, ?>) CgAssetYaml.read(PARENT), null);
    }

    private static CgAssetData child() {
        return CgAssetData.of(CHILD_ID, CgParticleKinds.SYSTEM, (Map<?, ?>) CgAssetYaml.read(CHILD), parent());
    }

    private static String bytes(CgAssetData d) {
        return CgAssetYaml.write(d.delta(), CgParticleKinds.SYSTEM);
    }

    @Test
    public void aPathPrintsAndParsesBack() {
        CgPropertyPath p = CgPropertyPath.of(PARENT_ID).key("emitters").item("h1").key("emitter")
                .key("particle_spawn").item("k3q9x2ab").key("inputs").key("sprite_size");
        assertEquals("dragonforge:kamehameha/charge /emitters[h1]/emitter/particle_spawn[k3q9x2ab]/inputs/sprite_size",
                p.toString());
        assertEquals(p, CgPropertyPath.parse(p.toString()));
    }

    @Test
    public void anOverrideOfAnInheritedValueIsWrittenAndResetsAway() {
        CgAssetData big = child();
        String before = bytes(big);
        CgPropertyPath name = CgPropertyPath.of(CHILD_ID).key("emitters").item("h2").key("name");
        CgEdit undo = big.apply(CgEdit.set(name, "sparks"));
        assertEquals("an override equal to the parent's is kept", CgOrigin.OVERRIDDEN, big.origin("emitters", "h2", "name"));
        assertTrue(bytes(big).contains("{ id: h2, name: sparks }"));
        big.apply(CgEdit.reset(name));
        assertEquals(CgOrigin.INHERITED, big.origin("emitters", "h2", "name"));
        big.apply(undo);
        assertEquals(CgOrigin.INHERITED, big.origin("emitters", "h2", "name"));
        assertEquals(before, bytes(big).replace("  - { id: h2 }\n", ""));
    }

    @Test
    public void removingAnInheritedElementWritesRemoveAndUndoesToTheByte() {
        CgAssetData big = child();
        String before = bytes(big);
        CgEdit undo = big.apply(CgEdit.remove(CgPropertyPath.of(CHILD_ID).key("emitters").item("p0fr8m1c")));
        assertEquals(CgOrigin.REMOVED, big.origin("emitters", "p0fr8m1c"));
        assertTrue(bytes(big).contains("remove: [p0fr8m1c]"));
        big.apply(undo);
        assertEquals(before, bytes(big));
    }

    @Test
    public void anInsertAgainstAnInheritedElementIsPlacedByAfter() {
        CgAssetData big = child();
        Map<Object, Object> glow = new LinkedHashMap<>();
        glow.put(CgType.ID, "g1o2w3a4");
        glow.put("name", "glow");
        big.apply(CgEdit.insertBefore(CgPropertyPath.of(CHILD_ID).key("emitters"), glow, "h2"));
        List<Object> names = new ArrayList<>();
        for (Object e : (List<?>) big.resolved().get("emitters")) names.add(((Map<?, ?>) e).get("name"));
        assertEquals("[core, ring, glow, sparks, fringe]", names.toString());
        assertEquals(CgOrigin.ADDED, big.origin("emitters", "g1o2w3a4"));
    }

    @Test
    public void eachEditIsClassedByWhatItTouches() {
        CgAssetData d = parent();
        List<CgChange> changes = new ArrayList<>();
        d.onChanged(e -> changes.add(e.change()));
        CgPropertyPath h1 = CgPropertyPath.of(PARENT_ID).key("emitters").item("h1");
        CgPropertyPath module = h1.key("emitter").key("particle_spawn").item("k3q9x2ab");
        d.apply(CgEdit.set(h1.key("name"), "glow"));
        d.apply(CgEdit.set(module.key("inputs").key("sprite_size"), 2.0));
        d.apply(CgEdit.set(CgPropertyPath.of(PARENT_ID).key("delay"), 1.0));
        d.apply(CgEdit.set(module.key("module"), "crystalgraphics:color"));
        d.apply(CgEdit.disable(CgPropertyPath.of(PARENT_ID).key("emitters").item("h2")));
        assertEquals("[VALUE, VALUE, VALUE, STRUCTURAL, STRUCTURAL]", changes.toString());
    }

    @Test
    public void aTransactionIsOneEventAndUndoesAsOne() {
        CgAssetData d = parent();
        String before = bytes(d);
        List<CgAssetChanged> events = new ArrayList<>();
        d.onChanged(events::add);
        CgPropertyPath h1 = CgPropertyPath.of(PARENT_ID).key("emitters").item("h1");
        List<CgEdit> undo = d.apply(listOf(CgEdit.set(h1.key("name"), "a"), CgEdit.set(h1.key("name"), "b"),
                CgEdit.remove(CgPropertyPath.of(PARENT_ID).key("emitters").item("h2"))));
        assertEquals(1, events.size());
        assertEquals("a's before is the file's, b's before is a", "a", events.get(0).edits().get(1).before());
        d.apply(undo);
        assertEquals(before, bytes(d));
    }

    @Test
    public void aFailedEditLeavesTheAssetAsItWas() {
        CgAssetData d = parent();
        String before = bytes(d);
        try {
            d.apply(CgEdit.remove(CgPropertyPath.of(PARENT_ID).key("emitters").item("nope")));
        } catch (CgAssetException expected) {
            assertEquals(before, bytes(d));
            return;
        }
        throw new AssertionError("removed an element that is not there");
    }

    /** Random edits on both assets, undone in full, give the original bytes. */
    @Test
    public void randomEditsUndoneGiveTheOriginalBytes() {
        for (int seed = 0; seed < 40; seed++) {
            for (CgAssetData d : new CgAssetData[] {parent(), child()}) {
                String before = bytes(d);
                List<CgEdit> undo = CgRandomEdits.run(d, new Random(seed), 25);
                for (int i = undo.size() - 1; i >= 0; i--) d.apply(undo.get(i));
                assertEquals("seed " + seed + " on " + d.id(), before, bytes(d));
                assertFalse(undo.isEmpty());
            }
        }
    }

    @SafeVarargs
    private static <T> List<T> listOf(T... xs) {
        List<T> out = new ArrayList<>();
        for (T x : xs) out.add(x);
        return out;
    }
}
