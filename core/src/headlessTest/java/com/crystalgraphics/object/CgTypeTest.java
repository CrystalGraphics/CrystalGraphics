package com.crystalgraphics.object;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.crystalgraphics.object.value.CgQuantity;
import com.crystalgraphics.object.value.CgValueForm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Blueprint 01 O1: the declaration model every later step reads. */
public class CgTypeTest {

    private final CgTypes types = CgTypes.create();

    @Test
    public void aKindHoldsTheSharedKeysAroundItsOwnInTheSpecsOrder() {
        CgType shake = types.declare("test:shake", CgTypeKind.KIND).folder("cameras/shakes").version(1)
                .property("duration", CgValueType.FLOAT)
                .property("scale", CgValueType.FLOAT)
                .register();
        assertEquals(Arrays.asList("_v", "parent", "units", "duration", "scale", "unsupported", "editor", "remove"),
                names(shake));
        assertTrue(shake.property("_v").required());
        assertTrue(shake.property("editor").has(CgPropertyFlag.EDITOR_ONLY));
        assertEquals(3, shake.property("duration").index());
    }

    @Test
    public void anElementStartsWithItsIdAndHoldsOnlyWhatItDeclares() {
        CgType emitter = types.declare("test:system.emitter", CgTypeKind.ELEMENT)
                .property("name", CgValueType.STRING)
                .register();
        assertEquals(Arrays.asList("id", "name"), names(emitter));
        assertTrue(emitter.property("id").required());
    }

    @Test
    public void aNumberTakesEveryFormUnlessNarrowedAndAnythingElseOnlyItself() {
        CgType t = types.declare("test:inputs", CgTypeKind.STRUCT)
                .property("rate", CgValueType.FLOAT).quantity(CgQuantity.RATE)
                .property("count", CgValueType.INT).forms(CgValueForm.CONSTANT, CgValueForm.RANGE)
                .property("loop", CgValueType.enumOf("infinite", "once"))
                .register();
        assertEquals(CgValueForm.ALL, t.property("rate").forms());
        assertTrue(t.property("count").accepts(CgValueForm.RANGE));
        assertFalse(t.property("count").accepts(CgValueForm.CURVE));
        assertEquals(1, t.property("loop").forms().size());
        assertEquals(CgQuantity.RATE, t.property("rate").quantity());
        assertEquals(CgQuantity.NONE, t.property("count").quantity());
    }

    @Test
    public void modifiersApplyToTheLastPropertyAndFillItsMetadata() {
        CgType t = types.declare("test:drag", CgTypeKind.STRUCT)
                .property("use_quadratic", CgValueType.BOOL).defaultValue(false).toggleFor("quadratic")
                .property("quadratic", CgValueType.FLOAT).range(0, null).visibleWhen("use_quadratic", true)
                        .by("age", "random").rolls(CgPropertyMeta.Rolls.PARTICLE).description("Lost a second.")
                .register();
        CgProperty q = t.property("quadratic");
        assertEquals(0.0, q.meta().min(), 0.0);
        assertNull(q.meta().max());
        assertEquals(Boolean.TRUE, q.meta().visibleWhen().get("use_quadratic"));
        assertEquals("age", q.meta().by().get(0));
        assertEquals(CgPropertyMeta.Rolls.PARTICLE, q.meta().rolls());
        assertEquals("quadratic", t.property("use_quadratic").meta().toggleFor());
        assertEquals(Boolean.FALSE, t.property("use_quadratic").defaultValue());
    }

    @Test
    public void aReplicationModeFlagsThePropertyNetworked() {
        CgType t = types.declare("test:cue_fields", CgTypeKind.STRUCT)
                .property("end", CgValueType.POSITION).replication(CgPropertyMeta.Replication.GOAL)
                .register();
        assertTrue(t.property("end").has(CgPropertyFlag.NETWORKED));
        assertEquals(CgPropertyMeta.Replication.GOAL, t.property("end").meta().replication());
    }

    @Test
    public void aFamilyChoosesItsMemberByKeyAndRefusesOneDeclaringTheKey() {
        CgType patterns = types.declare("test:pattern", CgTypeKind.DISPATCH).dispatchKey("kind", true).register();
        CgType wave = types.declare("test:pattern.wave", CgTypeKind.STRUCT)
                .property("frequency", CgValueType.FLOAT).quantity(CgQuantity.FREQUENCY).register();
        patterns.member("wave", wave);
        assertSame(wave, patterns.member("wave"));
        assertEquals("dispatch<test:pattern>", CgValueType.dispatch(patterns).toString());

        CgType clash = types.declare("test:pattern.clash", CgTypeKind.STRUCT)
                .property("kind", CgValueType.STRING).register();
        assertThrows(IllegalArgumentException.class, () -> patterns.member("clash", clash));
        assertThrows(IllegalStateException.class, () -> patterns.member("wave", wave));
    }

    @Test
    public void aFrozenFamilyTakesNoMember() {
        CgType patterns = types.declare("test:frozen", CgTypeKind.DISPATCH).dispatchKey("kind", true).register();
        CgType late = types.declare("test:frozen.late", CgTypeKind.STRUCT).register();
        types.freezeAll();
        assertTrue(patterns.frozen());
        assertThrows(IllegalStateException.class, () -> patterns.member("late", late));
    }

    @Test
    public void anIdIsNamespacedAndUnique() {
        assertThrows(IllegalArgumentException.class, () -> types.declare("NoNamespace", CgTypeKind.STRUCT));
        types.declare("test:once", CgTypeKind.STRUCT).register();
        assertThrows(IllegalStateException.class, () -> types.declare("test:once", CgTypeKind.STRUCT).register());
        assertThrows(IllegalArgumentException.class,
                () -> types.declare("test:twice", CgTypeKind.STRUCT).property("a", CgValueType.INT).property("a", CgValueType.INT));
    }

    @Test
    public void aKindNeedsItsFolderAndVersion() {
        assertThrows(IllegalStateException.class, () -> types.declare("test:bare", CgTypeKind.KIND).register());
        assertThrows(IllegalStateException.class, () -> types.declare("test:s", CgTypeKind.STRUCT).folder("x"));
    }

    @Test
    public void anIdListHoldsElementsAndAPlainListAnything() {
        CgType element = types.declare("test:el", CgTypeKind.ELEMENT).register();
        CgType struct = types.declare("test:st", CgTypeKind.STRUCT).register();
        assertEquals("id-list<test:el>", CgValueType.idList(element).toString());
        assertThrows(IllegalArgumentException.class, () -> CgValueType.idList(struct));
        assertEquals("list<float>", CgValueType.listOf(CgValueType.FLOAT).toString());
        assertEquals(CgValueType.ref("material", CgValueType.Strength.HARD), CgValueType.ref("material", CgValueType.Strength.HARD));
    }

    private static List<String> names(CgType t) {
        List<String> out = new ArrayList<>();
        for (CgProperty p : t.properties()) out.add(p.name());
        return out;
    }
}
