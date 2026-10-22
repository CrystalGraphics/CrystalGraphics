package com.crystalgraphics.vfx.particle;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.shape.primitive.CgBox;
import com.crystalgraphics.shape.primitive.CgCapsule;
import com.crystalgraphics.shape.primitive.CgCone;
import com.crystalgraphics.shape.primitive.CgPlane;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.vfx.effect.beam.CgBeamWake;
import com.crystalgraphics.vfx.element.CgVfxExplosion;
import com.crystalgraphics.vfx.particle.form.CgVfxFormCompiler;
import com.crystalgraphics.vfx.particle.form.CgVfxSpawnForm;
import com.crystalgraphics.vfx.particle.form.CgVfxSpawnInput;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWords;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxRecord;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxShape;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;
import com.crystalgraphics.vfx.particle.spawn.CgVfxSpawnBurst;

/**
 * Emitters compiled from their asset trees alone are the emitters that wrote them: the same spawn, curve and module
 * words, the same books. Today's built-in emitters, and a few made to reach every field they leave unused.
 */
public class CgVfxEmitterAssetsTest {

    private static final CgKeyframes EASED = CgKeyframes.start(0f, 0.2f).to(0.3f, 1f, CgEasings.OUT_QUAD)
            .to(0.7f, 0.6f, CgEasings.IN_OUT_SINE).to(1f, 0f, CgEasings.IN_CUBIC).build();

    private static List<CgVfxEmitter> emitters() {
        List<CgVfxEmitter> all = new ArrayList<>(Arrays.asList(CgVfxExplosion.BILLOWS, CgVfxExplosion.SPECKS,
                CgVfxExplosion.DUST, CgVfxExplosion.SURGE, CgVfxExplosion.SKIRT, CgVfxExplosion.SPARKLES, CgVfxExplosion.INK,
                CgVfxExplosion.RAYS, CgVfxExplosion.RINGS, CgBeamWake.EMITTER));
        all.add(CgVfxEmitter.builder("cone").optional().capacity(300).rate(80f, EASED).shape(CgCone.of(3, Math.toRadians(25)))
                .pointing(0.3f, 1f, 0.2f).at(0.5f, 1f, -2f).surface().direction(0f, 1f, 0f, 20f, 70f).speed(2f, 5f)
                .life(1f, 2f).size(0.1f, 0.6f, 2.5f).spin(0.5f, 3f).heat(0.4f).color(0xFFFFD27A, 3f, 0xFF3A7BFF, 1.5f)
                .scale(1f, 1f, 4f, 1.2f, 1.2f, 9f).scaleOverLife(EASED, CgKeyframes.constant(1f), EASED)
                .colorOverLife(EASED, CgKeyframes.constant(1f), EASED).intensityOverLife(EASED).opacity(EASED)
                .rasterize(0.3f, 0f, 0.1f, 0f)
                .module(new CgVfxModule.Collide(CgPlane.of(0.4, 1, 0, 5), 0.3f, 0.2f, 0.1f))
                .module(new CgVfxModule.Collide(CgSphere.of(2), 0.5f, 0.1f, 0f).at(0f, 1f, 0f))
                .module(new CgVfxModule.CollideWorld(0.35f, 0.4f, 0.4f, true))
                .module(new CgVfxModule.CollideDepth(0.2f, 0.3f, 0.1f, 0.6f))
                .module(new CgVfxModule.CollideEntities(0.2f, 0.3f, 0.1f, 0.25f))
                .module(new CgVfxModule.Kill(CgBox.of(4, 1, 4), true).at(0f, -2f, 0f))
                .module(new CgVfxModule.Attract(CgSphere.of(6), 30f).at(0f, 1.5f, 0f))
                .module(new CgVfxModule.Force(0f, 2f, 1f))
                .module(new CgVfxModule.Orbit(0.5f, 0f, 1f, 0f, 0f, 0f, 0f))
                .module(new CgVfxModule.Vortex(0f, 1f, 0f, 6f, 0f, 0f, -1f, 0f))
                .module(new CgVfxModule.Conform(CgSphere.of(2), 1f, 3f, 2f))
                .module(new CgVfxModule.Damping(0.3f)).module(new CgVfxModule.LimitSpeed(9f))
                .build());
        all.add(CgVfxEmitter.builder("box").state(CgVfxEmitterState.ONCE).burst(CgVfxSpawnBurst.at(0.2f, 8, 16).probability(0.5f))
                .shape(CgBox.of(2, 0.05, 2)).edge().inward().speed(1f, 1f).life(2f, 2f).build());
        all.add(CgVfxEmitter.builder("capsule").rate(30f).shape(CgCapsule.between(0, 0, 0, 0, 0, 12, 0.3)).edge().ordered(24)
                .speed(0.2f, 0.6f).ratePerBlock(6f).maxStep(4f).inherit(0.4f).local().build());
        all.add(CgVfxEmitter.builder("arc").rate(30f).shape(CgSphere.of(2)).surface().hemisphere().arc(120f).ordered(12)
                .speed(1f, 2f).build());
        return all;
    }

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    @Test
    public void emittersCompileFromTheirTreesAlone() {
        for (CgVfxEmitter e : emitters()) {
            Map<String, Object> tree = CgVfxEmitterAssets.write(e);
            CgAssetData asset = CgAssetData.of("crystalgraphics:test/" + e.name(), CgParticleKinds.EMITTER, tree, null);
            CgVfxEmitter back = CgVfxEmitterAssets.compile(e.name(), asset.resolved(), e);
            same(e, back);
            // Twice through: what a file read back writes compiles to the same emitter again.
            same(e, CgVfxEmitterAssets.compile(e.name(), CgVfxEmitterAssets.write(back), e));
            CgVfxEmitterAssets.compile(e.name(), asset.resolved(), null);
        }
    }

    /**
     * Rolls follow element ids, never places (core.md § Values): two bursts listed either way round roll the same counts
     * every loop, the ids are written back as read, and renaming the init element moves the launch's keys.
     */
    @Test
    public void rollsFollowElementIdsNotPlaces() {
        Map<String, Object> early = burstEntry("early", 0.0), late = burstEntry("late", 0.5);
        CgVfxEmitter one = CgVfxEmitterAssets.compile("ids", burstTree(early, late, "init"), null);
        CgVfxEmitter other = CgVfxEmitterAssets.compile("ids", burstTree(late, early, "spawn_init"), null);
        int seed = 0x5EED;
        for (int b = 0; b < 2; b++) {
            assertEquals(one.burstIds[b], other.burstIds[b]);
            for (int loop = 0; loop < 16; loop++) {
                assertEquals(one.bursts[b].count(seed, one.burstCountKeys[b], one.burstProbabilityKeys[b], loop),
                        other.bursts[b].count(seed, other.burstCountKeys[b], other.burstProbabilityKeys[b], loop));
            }
        }
        assertEquals(CgVfxFormCompiler.key("spawn_init", "lifetime"), other.rollKeys()[CgVfxGpuEmitter.KEY_LIFE]);
        assertEquals(CgVfxFormCompiler.key("velocity", "speed"), other.rollKeys()[CgVfxGpuEmitter.KEY_SPEED]);
        Map<String, Object> written = CgVfxEmitterAssets.write(other);
        assertEquals("spawn_init", ((Map<?, ?>) ((List<?>) written.get("particle_spawn")).get(0)).get("id"));
        CgVfxEmitter back = CgVfxEmitterAssets.compile("ids", written, null);
        assertArrayEquals(other.rollKeys(), back.rollKeys());
        assertArrayEquals(other.burstCountKeys, back.burstCountKeys);
    }

    private static Map<String, Object> burstEntry(String id, double time) {
        return Map.of("id", id, "module", "crystalgraphics:spawn_burst",
                "inputs", Map.of("time", time, "count", Map.of("range", List.of(2.0, 9.0)), "probability", 0.5));
    }

    private static Map<String, Object> burstTree(Map<String, Object> first, Map<String, Object> second, String init) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("_v", 1);
        tree.put("properties", Map.of("capacity", 64));
        tree.put("emitter_update", List.of(Map.of("id", "state", "module", "crystalgraphics:emitter_state",
                "inputs", Map.of("loop", "infinite", "duration", 1.0)), first, second));
        tree.put("particle_spawn", List.of(Map.of("id", init, "module", "crystalgraphics:initialize_particle",
                "inputs", Map.of("lifetime", Map.of("range", List.of(0.5, 1.0))))));
        return tree;
    }

    /** A colour of three floats (the schema takes 2 to 4) takes its alpha from the module's {@code alpha}. */
    @Test
    public void aColourOfThreeTakesItsAlphaInput() {
        Map<String, Object> three = burstTree(burstEntry("b", 0.0), burstEntry("c", 0.5), "init");
        three.put("particle_spawn", List.of(Map.of("id", "init", "module", "crystalgraphics:initialize_particle",
                "inputs", Map.of("lifetime", 1.0, "color", List.of(0.2, 0.4, 0.8), "alpha", 0.5))));
        Map<String, Object> four = burstTree(burstEntry("b", 0.0), burstEntry("c", 0.5), "init");
        four.put("particle_spawn", List.of(Map.of("id", "init", "module", "crystalgraphics:initialize_particle",
                "inputs", Map.of("lifetime", 1.0, "color", List.of(0.2, 0.4, 0.8, 0.5)))));
        assertArrayEquals(spawn(CgVfxEmitterAssets.compile("four", four, null)),
                spawn(CgVfxEmitterAssets.compile("three", three, null)), 0f);
    }

    /** A module input in a form, written back as its file wrote it and compiled again to the same words. */
    @Test
    public void formsRoundTrip() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("_v", 1);
        tree.put("properties", Map.of("capacity", 64));
        tree.put("emitter_update", List.of(Map.of("id", "state", "module", "crystalgraphics:emitter_state",
                "inputs", Map.of("loop", "once", "duration", 1.0))));
        Object range = Map.of("range", List.of(List.of(-1.0, 2.0, 0.5), List.of(3.0, -2.0, 4.0)), "per_channel", true);
        Object curve = Map.of("curve", Map.of("keys", List.of(List.of(0.0, 0.5, "linear"), List.of(1.0, 2.0, "linear"))), "by", "age");
        tree.put("particle_update", List.of(
                Map.of("id", "push", "module", "crystalgraphics:acceleration", "inputs", Map.of("acceleration", range)),
                Map.of("id", "drag", "module", "crystalgraphics:drag", "inputs", Map.of("drag", curve))));
        CgVfxEmitter e = CgVfxEmitterAssets.compile("forms", tree, null);
        assertEquals(2, e.moduleForms().size());
        Map<String, Object> written = CgVfxEmitterAssets.write(e);
        assertEquals(range, ((Map<?, ?>) ((Map<?, ?>) ((List<?>) written.get("particle_update")).get(0)).get("inputs")).get("acceleration"));
        CgVfxEmitter back = CgVfxEmitterAssets.compile("forms", written, null);
        same(e, back);
        assertEquals(e.moduleForms().toString().replaceAll("@[0-9a-f]+", ""), back.moduleForms().toString().replaceAll("@[0-9a-f]+", ""));
    }

    /** A size range over life, rolled each step, with a locked pair: read, written back and read again alike. */
    @Test
    public void lifeRollsRoundTrip() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("_v", 1);
        tree.put("properties", Map.of("capacity", 64));
        tree.put("emitter_update", List.of(Map.of("id", "state", "module", "crystalgraphics:emitter_state",
                "inputs", Map.of("loop", "once", "duration", 1.0))));
        Object range = Map.of("range", List.of(List.of(1.0, 0.7, 0.7), List.of(1.5, 1.0, 1.0)), "per_channel", true, "locked", "yz");
        tree.put("particle_update", List.of(Map.of("id", "flicker", "module", "crystalgraphics:scale_mesh_size",
                "inputs", Map.of("scale", range))));
        CgVfxLifeRoll roll = CgVfxEmitterAssets.compile("flicker", tree, null).lifeRolls().get(0);
        assertArrayEquals(new int[]{0, 1, 1}, roll.rolls());
        assertArrayEquals(new float[]{1f, 0.7f, 0.7f}, roll.from(), 0f);
        CgVfxLifeRoll back = CgVfxEmitterAssets.compile("flicker", CgVfxEmitterAssets.write(
                CgVfxEmitterAssets.compile("flicker", tree, null)), null).lifeRolls().get(0);
        assertArrayEquals(roll.from(), back.from(), 0f);
        assertArrayEquals(roll.to(), back.to(), 0f);
        assertArrayEquals(roll.rolls(), back.rolls());
    }

    /**
     * Cascade's rotation rate, its multiplier and its rate by life in stack order: the rolled rate integrates through
     * the multiplier; the rate added after it does not. Read, baked and written back alike.
     */
    @Test
    public void rotationRatesOverLifeIntegrate() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("_v", 1);
        tree.put("properties", Map.of("capacity", 64));
        tree.put("emitter_update", List.of(Map.of("id", "state", "module", "crystalgraphics:emitter_state",
                "inputs", Map.of("loop", "once", "duration", 1.0))));
        Object falling = Map.of("samples", Map.of("range", List.of(0.0, 1.0), "values", List.of(1.0, 0.0)));
        Object flat = Map.of("samples", Map.of("range", List.of(0.0, 1.0), "values", List.of(2.0, 2.0)));
        Object zero = Map.of("samples", Map.of("range", List.of(0.0, 1.0), "values", List.of(0.0, 0.0)));
        tree.put("particle_update", List.of(
                Map.of("id", "base", "module", "crystalgraphics:mesh_rotation_rate", "inputs", Map.of("rate", List.of(0.0, 0.0, 3.0))),
                Map.of("id", "slowing", "module", "crystalgraphics:mesh_rotation_rate", "inputs",
                        Map.of("mode", "scale", "rate_scale", Map.of("curves", List.of(falling, falling, falling), "by", "age"))),
                Map.of("id", "after", "module", "crystalgraphics:mesh_rotation_rate", "inputs",
                        Map.of("rate", Map.of("curves", List.of(zero, zero, flat), "by", "age")))));
        CgVfxMeshRotation turn = CgVfxEmitterAssets.compile("turns", tree, null).meshRotation();
        assertEquals(2, turn.steps().size());
        int texels = 5;
        float[] scale = new float[4 * texels], added = new float[4 * texels];
        turn.integrals(scale, 0, added, 0, texels);
        // ∫(1 - t) to 1 is 0.5; to 0.5, 0.375. The added 2 integrates alone: 2t.
        assertEquals(0.5f, scale[4 * 4 + 2], 1e-4f);
        assertEquals(0.375f, scale[4 * 2 + 2], 1e-4f);
        assertEquals(2f, added[4 * 4 + 2], 1e-4f);
        assertEquals(3f, turn.rateTo()[2], 0f);
        CgVfxMeshRotation back = CgVfxEmitterAssets.compile("turns", CgVfxEmitterAssets.write(
                CgVfxEmitterAssets.compile("turns", tree, null)), null).meshRotation();
        float[] scaleBack = new float[4 * texels], addedBack = new float[4 * texels];
        back.integrals(scaleBack, 0, addedBack, 0, texels);
        assertArrayEquals(scale, scaleBack, 1e-5f);
        assertArrayEquals(added, addedBack, 1e-5f);
        assertArrayEquals(turn.rateTo(), back.rateTo(), 0f);
    }

    /** A size and an alpha bound to user parameters, remapped: read on their targets, applied, and written back alike. */
    @Test
    public void lifeBindsRoundTrip() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("_v", 1);
        tree.put("properties", Map.of("capacity", 64));
        tree.put("emitter_update", List.of(Map.of("id", "state", "module", "crystalgraphics:emitter_state",
                "inputs", Map.of("loop", "once", "duration", 1.0))));
        Object length = Map.of("bind", "user.BeamLength", "default", List.of(100.0, 1.0, 1.0),
                "remap", Map.of("in", List.of(List.of(0.0, 0.0, 0.0), List.of(10.0, 10.0, 10.0)),
                        "out", List.of(List.of(0.0, 0.0, 0.0), List.of(20.0, 20.0, 20.0))));
        tree.put("particle_update", List.of(
                Map.of("id", "length", "module", "crystalgraphics:scale_mesh_size", "inputs", Map.of("scale", length)),
                Map.of("id", "fade", "module", "crystalgraphics:scale_color", "inputs",
                        Map.of("alpha", Map.of("bind", "user.Opacity", "default", 0.5)))));
        CgVfxEmitter e = CgVfxEmitterAssets.compile("binds", tree, null);
        assertEquals(2, e.lifeBinds().size());
        CgVfxLifeBind scale = e.lifeBinds().get(0), alpha = e.lifeBinds().get(1);
        assertEquals(CgVfxLifeBind.Target.MESH_SCALE, scale.target());
        assertEquals("BeamLength", scale.parameter());
        // Remapped and clamped: 3 of 0..10 is 6 of 0..20; the default 100 past the range, 20.
        assertEquals(6f, scale.bind().apply(3f, 0), 1e-6f);
        assertEquals(20f, scale.bind().apply(scale.bind().fallback()[0], 0), 1e-6f);
        assertEquals(CgVfxLifeBind.Target.ALPHA, alpha.target());
        assertEquals(0.5f, alpha.bind().apply(alpha.bind().fallback()[0], 0), 1e-6f);
        CgVfxEmitter back = CgVfxEmitterAssets.compile("binds", CgVfxEmitterAssets.write(e), null);
        assertEquals(e.lifeBinds().size(), back.lifeBinds().size());
        for (int i = 0; i < 2; i++) {
            assertEquals(e.lifeBinds().get(i).target(), back.lifeBinds().get(i).target());
            assertEquals(e.lifeBinds().get(i).source(), back.lifeBinds().get(i).source());
        }
    }

    /** A colour by the loop and a mesh scale by a function: stored at birth, in their record words, and written back. */
    @Test
    public void startsInFormsAreStored() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("_v", 1);
        tree.put("properties", Map.of("capacity", 64));
        tree.put("emitter_update", List.of(Map.of("id", "state", "module", "crystalgraphics:emitter_state",
                "inputs", Map.of("loop", "once", "duration", 2.0))));
        Object rising = Map.of("samples", Map.of("range", List.of(0.0, 2.0), "values", List.of(0.2, 1.0)));
        Object color = Map.of("curves", List.of(rising, rising, rising), "by", "loop");
        Object scale = Map.of("fn", "crystalgraphics:random_range", "inputs", Map.of("min", List.of(0.5, 0.5, 0.5),
                "max", List.of(2.0, 3.0, 4.0)));
        tree.put("particle_spawn", List.of(Map.of("id", "init", "module", "crystalgraphics:initialize_particle",
                "inputs", Map.of("lifetime", 2.0, "color", color, "mesh_scale", scale))));
        CgVfxEmitter e = CgVfxEmitterAssets.compile("starts", tree, null);
        assertTrue(value(e, CgVfxSpawnInput.COLOR) != null && value(e, CgVfxSpawnInput.MESH_SCALE) != null);
        CgVfxRecord record = CgVfxShape.of(e).record();
        assertTrue(record.word(CgVfxRecord.Tail.COLOR) >= 0);
        assertTrue(record.word(CgVfxRecord.Tail.SCALE) >= 0);
        CgVfxEmitter back = CgVfxEmitterAssets.compile("starts", CgVfxEmitterAssets.write(e), null);
        for (CgVfxSpawnInput stored : new CgVfxSpawnInput[]{CgVfxSpawnInput.COLOR, CgVfxSpawnInput.MESH_SCALE}) {
            assertEquals(stored + " written back", value(e, stored), value(back, stored));
        }
    }

    private static Object value(CgVfxEmitter e, CgVfxSpawnInput target) {
        for (CgVfxSpawnForm f : e.spawnForms()) if (f.target() == target) return f.value();
        return null;
    }

    /** A sprite size rolled each tick between two curves by age: read as a curved roll, and written back alike. */
    @Test
    public void curvedLifeRollsRoundTrip() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("_v", 1);
        tree.put("properties", Map.of("capacity", 64));
        tree.put("emitter_update", List.of(Map.of("id", "state", "module", "crystalgraphics:emitter_state",
                "inputs", Map.of("loop", "once", "duration", 1.0))));
        Object low = Map.of("samples", Map.of("range", List.of(0.0, 1.0), "values", List.of(1.0, 0.5)));
        Object high = Map.of("samples", Map.of("range", List.of(0.0, 1.0), "values", List.of(2.0, 3.0)));
        tree.put("particle_update", List.of(Map.of("id", "flicker", "module", "crystalgraphics:scale_sprite_size", "inputs",
                Map.of("scale", Map.of("fn", "crystalgraphics:random_range", "inputs", Map.of(
                        "min", Map.of("curves", List.of(low, low), "by", "age"), "max", Map.of("curves", List.of(high, high), "by", "age")))))));
        CgVfxLifeRoll roll = CgVfxEmitterAssets.compile("between", tree, null).lifeRolls().get(0);
        assertTrue(roll.curved());
        assertEquals(0.75f, roll.fromCurves()[0].at(0.5f), 1e-5f);
        assertEquals(3f, roll.to()[1], 1e-5f);
        CgVfxLifeRoll back = CgVfxEmitterAssets.compile("between", CgVfxEmitterAssets.write(
                CgVfxEmitterAssets.compile("between", tree, null)), null).lifeRolls().get(0);
        for (float t : new float[]{0f, 0.3f, 1f}) {
            for (int c = 0; c < 3; c++) {
                assertEquals(roll.fromCurves()[c].at(t), back.fromCurves()[c].at(t), 1e-5f);
                assertEquals(roll.toCurves()[c].at(t), back.toCurves()[c].at(t), 1e-5f);
            }
        }
    }

    private static void same(CgVfxEmitter e, CgVfxEmitter back) {
        String n = e.name();
        assertEquals(n + " capacity", e.capacity, back.capacity);
        assertEquals(n + " local", e.local, back.local);
        assertEquals(n + " optional", e.optional, back.optional);
        // Its state as written: the class keeps no equals of its own.
        assertEquals(n + " state", stack(e, "emitter_update").get(0).toString(), stack(back, "emitter_update").get(0).toString());
        assertEquals(n + " bursts", Arrays.asList(e.bursts), Arrays.asList(back.bursts));
        assertEquals(n + " per unit", e.perUnit, back.perUnit);
        assertEquals(n + " max step", e.maxStep, back.maxStep, 0f);
        assertArrayEquals(n + " rasterized", e.rasterized, back.rasterized, 0f);
        assertEquals(n + " rate", e.rate.perSecond(), back.rate.perSecond(), 0f);
        for (int i = 0; i <= 100; i++) {
            float t = Math.min(e.state.durationMax(), 10f) * i / 100f;   // a loop for ever: its first ten seconds
            near(n + " rate scale at " + t, e.rate.scale().at(t), back.rate.scale().at(t));
        }
        assertArrayEquals(n + "'s module words", e.moduleWords(), back.moduleWords());
        float[] a = spawn(e), b = spawn(back);
        for (int i = 0; i < a.length; i++) near(n + " spawn word " + i, a[i], b[i]);
        int length = 4 * (2 * CgVfxGpuEmitter.CURVE_TEXELS + CgVfxGpuEmitter.CURVE_STARTS);
        float[] ca = new float[length], cb = new float[length];
        e.writeCurves(ca, 0, CgVfxGpuEmitter.CURVE_TEXELS);
        back.writeCurves(cb, 0, CgVfxGpuEmitter.CURVE_TEXELS);
        for (int i = 0; i < length; i++) near(n + " curve word " + i, ca[i], cb[i]);
    }

    private static List<?> stack(CgVfxEmitter e, String name) {
        return (List<?>) CgVfxEmitterAssets.write(e).get(name);
    }

    private static float[] spawn(CgVfxEmitter e) {
        int[] words = new int[CgVfxGpuEmitter.SPAWN_VECTORS * 4];
        CgVfxWords out = CgVfxWords.into(words, 0, CgVfxGpuEmitter.SPAWN_VECTORS, e.name());
        e.writeSpawn(out);
        out.finish("spawn");
        float[] f = new float[words.length];
        for (int i = 0; i < f.length; i++) f[i] = Float.intBitsToFloat(words[i]);
        return f;
    }

    /** Equal to a float's rounding through a file's doubles and a curve's cubic: 1e-5 of the value, or of 1. */
    private static void near(String what, float a, float b) {
        if (Float.compare(a, b) == 0) return;
        if (!(Math.abs(a - b) <= 1e-5f * Math.max(1f, Math.abs(a)))) fail(what + ": " + a + " written, " + b + " read back");
    }
}
