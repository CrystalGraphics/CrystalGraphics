package com.crystalgraphics.vfx.look;

import com.crystalgraphics.vfx.particle.CgVfxEmitter;
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

    private static final CgVfxSlot BODY = CgVfxSlot.tube("test:look/body");
    private static final CgVfxSlot HEAD = CgVfxSlot.mesh("test:look/head");
    private static final CgVfxEmitter SPARKS = CgVfxEmitter.builder("test:look/sparks").size(1f, 2f, 1f).build();
    private static final CgVfxEmitter DUST = CgVfxEmitter.builder("test:look/dust").size(1f, 2f, 1f).build();

    private static final CgVfxLayer SURFACE = CgVfxLayer.of("test:surface.shader").on(BODY).build();
    private static final CgVfxLayer SMOKE = CgVfxLayer.of("test:smoke.shader").on(BODY, HEAD).role(CgVfxLayer.Role.SMOKE).build();
    private static final CgVfxLayer GLOW = CgVfxLayer.of("test:glow.shader").on(HEAD).build();
    private static final CgVfxKit BODY_KIT = CgVfxKit.of("test:look/body").layer(SURFACE).layer(SMOKE).build();
    private static final CgVfxKit HEAD_KIT = CgVfxKit.of("test:look/head").layer(GLOW).emitter(SPARKS).build();
    /** Its emitters authored where BLAST is 10: a look's scaling leaves them to BLAST. */
    private static final CgVfxKit BLAST_KIT = CgVfxKit.of("test:look/blast").params(KIT_SCHEMA).scalesWith(BLAST, 10f)
            .emitter(DUST).build();

    @Test
    public void layersDrawByRoleThenAsAddedAndSlotsHoldTheirOwn() {
        CgVfxLook look = CgVfxLook.builder(SCHEMA).with(BODY_KIT, HEAD_KIT).build();
        assertEquals(List.of(SMOKE, SURFACE, GLOW), look.layers());
        assertEquals(2, look.on(BODY).length);
        assertSame(SMOKE, look.on(HEAD)[0].layer());
        assertEquals(0, look.on(HEAD)[0].order());
        assertEquals(0, look.on(CgVfxSlot.mesh("test:look/nothing")).length);
    }

    @Test
    public void aKitDroppedTakesItsLayersAndEmittersAndAReplacementTakesItsPlace() {
        CgVfxLook look = CgVfxLook.builder(SCHEMA).with(BODY_KIT, HEAD_KIT, BLAST_KIT).build();
        CgVfxLook without = look.derive().without(HEAD_KIT).build();
        assertFalse(without.draws(HEAD) && without.on(HEAD).length > 1);
        assertEquals(1, without.on(HEAD).length);
        assertTrue(without.emitter(SPARKS.name()) == null);
        assertTrue(without.layout().includes(BLAST));
        assertTrue(without.has(BODY_KIT) && !without.has(HEAD_KIT));

        CgVfxKit other = CgVfxKit.of("test:look/other").layer(CgVfxLayer.of("test:other.shader").on(BODY).build()).build();
        CgVfxLook replaced = look.derive().replace(BODY_KIT, other).build();
        assertEquals("test:other.shader", replaced.on(BODY)[0].layer().shader());
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
