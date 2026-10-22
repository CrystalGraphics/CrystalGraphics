package com.crystalgraphics.vfx.look;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgValueType;
import com.crystalgraphics.object.layout.CgLayout;
import com.crystalgraphics.object.value.CgQuantity;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.render.CgVfxSlotRenderer;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** A look composed from kits: what each composition keeps, how links and scaling move its values, its draw order. */
public class CgVfxLookTest {

    private static final CgVfxSchema SCHEMA = CgVfxSchema.of("test:look");
    private static final CgVfxParam.Scalar RADIUS = SCHEMA.length("radius", 1f);
    private static final CgVfxParam.Scalar REACH = SCHEMA.length("reach", RADIUS, 4f);
    private static final CgVfxParam.Scalar COUNT = SCHEMA.scalar("count", 3f);
    private static final CgVfxSchema KIT_SCHEMA = CgVfxSchema.of("test:look/kit");
    private static final CgVfxParam.Scalar BLAST = KIT_SCHEMA.length("blast", 10f);

    private static final String PART = "crystalgraphics:shaders/vfx/sequence/part.shader";
    private static final CgVfxSlot BODY = CgVfxSlot.tube("test:look/body");
    private static final CgVfxSlot HEAD = CgVfxSlot.mesh("test:look/head");
    private static final CgVfxEmitter SPARKS = CgVfxEmitter.builder("test:look/sparks").size(1f, 2f, 1f).build();
    private static final CgVfxEmitter DUST = CgVfxEmitter.builder("test:look/dust").size(1f, 2f, 1f).build();

    private static final CgVfxSlotRenderer SURFACE = slot("surface", BODY, CgVfxLook.SURFACE);
    private static final CgVfxSlotRenderer SMOKE = slot("smoke", BODY, 0);
    private static final CgVfxSlotRenderer GLOW = slot("glow", HEAD, CgVfxLook.SURFACE);
    private static final CgVfxKit BODY_KIT = CgVfxKit.of("test:look/body").slot(SURFACE).slot(SMOKE).build();
    private static final CgVfxKit HEAD_KIT = CgVfxKit.of("test:look/head").slot(GLOW).emitter(SPARKS).build();
    /** Its emitters authored where BLAST is 10: a look's scaling leaves them to BLAST. */
    private static final CgVfxKit BLAST_KIT = CgVfxKit.of("test:look/blast").params(KIT_SCHEMA).scalesWith(BLAST, 10f)
            .emitter(DUST).build();

    @Test
    public void aSchemaIsADeclaredTypeLaidOutByItsLayout() {
        CgVfxSchema schema = CgVfxSchema.of("test:look/typed");
        CgVfxParam.Scalar size = schema.length("size", 2f);
        CgVfxParam.Color tint = schema.color("tint", 0xFF336699, 2f);
        CgVfxParam.Palette bands = schema.palette("bands", 0xFFFFFFFF, 0xFF000000);
        CgVfxParam.Vector aim = schema.vector("aim", 0f, 1f, 0f);
        CgVfxLook look = CgVfxLook.builder(schema).build();
        CgType type = schema.type();
        assertEquals(CgQuantity.LENGTH, type.property("size").quantity());
        assertEquals(CgValueType.Kind.COLOUR, type.property("bands[1]").type().kind());
        CgLayout layout = schema.layout();
        assertEquals(1 + 4 + 8 + 3, layout.floats());
        assertEquals(look.layout().offset(tint), tint.slot().offset());
        assertEquals(look.layout().offset(aim), aim.slot().offset());
        assertEquals(look.layout().offset(bands), bands.slot().offset());
        assertEquals(2f, look.get(size), 0f);
        assertEquals(0x33 / 255f * 2f, look.get(tint, 0), 1e-6f);
    }

    private static CgVfxSlotRenderer slot(String name, CgVfxSlot slot, int order) {
        return CgVfxSlotRenderer.of(name, slot)
                .material(CgMaterialDefinition.of("test:look/" + name, PART).order(order).build())
                .build();
    }

    private static List<CgVfxSlotRenderer> slots(CgVfxLook look) {
        return look.slotted().stream().map(CgVfxLook.Placed::slotRenderer).toList();
    }

    @Test
    public void slotRenderersDrawByHintThenAsAddedAndSlotsHoldTheirOwn() {
        CgVfxLook look = CgVfxLook.builder(SCHEMA).with(BODY_KIT, HEAD_KIT).build();
        assertEquals(List.of(SMOKE, SURFACE, GLOW), slots(look));
        assertEquals(2, look.on(BODY).length);
        assertSame(GLOW, look.on(HEAD)[0].slotRenderer());
        assertEquals(2, look.on(HEAD)[0].order());
        assertEquals(0, look.on(CgVfxSlot.mesh("test:look/nothing")).length);
    }

    @Test
    public void aHintSortsAcrossTheBandsAndTiesKeepTheLooksOrder() {
        CgVfxSlotRenderer late = slot("late", BODY, CgVfxLook.SURFACE + 12);
        CgVfxSlotRenderer early = slot("early", BODY, CgVfxLook.SURFACE + 3);
        CgVfxSlotRenderer tie = slot("tie", BODY, CgVfxLook.SURFACE + 3);
        CgVfxSlotRenderer smoke = slot("smoke2", BODY, 40);
        CgVfxKit kit = CgVfxKit.of("test:look/hints").slot(late).slot(early).slot(tie).slot(smoke).build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).with(kit).build();
        assertEquals("the smoke band still comes first", List.of(smoke, early, tie, late), slots(look));
        assertEquals(3, look.on(BODY)[3].order());
    }

    @Test
    public void aKitDroppedTakesItsSlotRenderersAndEmittersAndAReplacementTakesItsPlace() {
        CgVfxLook look = CgVfxLook.builder(SCHEMA).with(BODY_KIT, HEAD_KIT, BLAST_KIT).build();
        CgVfxLook without = look.derive().without(HEAD_KIT).build();
        assertFalse(without.draws(HEAD));
        assertEquals(0, without.on(HEAD).length);
        assertTrue(without.emitter(SPARKS.name()) == null);
        assertTrue(without.layout().includes(BLAST));
        assertTrue(without.has(BODY_KIT) && !without.has(HEAD_KIT));

        CgVfxKit other = CgVfxKit.of("test:look/other").slot(slot("other", BODY, CgVfxLook.SURFACE)).build();
        CgVfxLook replaced = look.derive().replace(BODY_KIT, other).build();
        assertEquals("test:look/other", replaced.on(BODY)[0].slotRenderer().material());
        assertFalse(replaced.has(BODY_KIT));
    }

    @Test(expected = IllegalArgumentException.class)
    public void aKitAddedTwiceThrows() {
        CgVfxLook.builder(SCHEMA).with(BODY_KIT).with(BODY_KIT);
    }

    @Test
    public void linksFollowTheirSourceUntilSetAndScalingTakesLengthsAlone() {
        CgVfxLook look = CgVfxLook.builder(SCHEMA).with(BLAST_KIT).link(BLAST, RADIUS, 10f).build();
        assertEquals(4f, look.get(REACH), 0f);

        CgVfxLook wider = look.derive().set(RADIUS, 2f).build();
        assertEquals(8f, wider.get(REACH), 0f);
        assertEquals(20f, wider.get(BLAST), 0f);

        CgVfxLook fixed = look.derive().set(REACH, 1f).set(RADIUS, 3f).build();
        assertEquals(1f, fixed.get(REACH), 0f);

        CgVfxLook small = look.derive().scaled(0.5f).build();
        assertEquals(0.5f, small.get(RADIUS), 0f);
        assertEquals(2f, small.get(REACH), 0f);
        assertEquals(5f, small.get(BLAST), 0f);
        assertEquals(3f, small.get(COUNT), 0f);
    }

    @Test
    public void scalingLeavesAKitsEmittersToTheLengthTheyScaleWith() {
        CgVfxLook look = CgVfxLook.builder(SCHEMA).with(HEAD_KIT, BLAST_KIT).build().derive().scaled(0.5f).build();
        assertEquals(SPARKS.largestSize() * 0.5f, look.emitter(SPARKS.name()).largestSize(), 1e-6f);
        assertEquals(DUST.largestSize(), look.emitter(DUST.name()).largestSize(), 0f);
        assertEquals(0.5f, BLAST_KIT.emitterScale(look.get(BLAST)), 1e-6f);
    }
}
