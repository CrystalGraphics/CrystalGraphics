package com.crystalgraphics.vfx.particle;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
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
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.value.CgUnits;
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
import com.crystalgraphics.vfx.particle.gpu.CgVfxDrawnChannel;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWords;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxRecord;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxShape;
import com.crystalgraphics.vfx.particle.renderer.CgVfxLightRendererProperties;
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
                .module(new CgVfxModule.CollideWorld(0.35f, 0.4f, 0.4f).killing())
                .module(new CgVfxModule.CollideWorld(0f, 0f, 0f).response(CgVfxModule.CollisionResponse.NONE
                        .damping(0.5f, 0.25f, 0.5f, 0.6f, 0.3f, 0.6f, CgVfxModule.CollisionResponse.PER_AXIS).delay(0.1f).rotation(0.5f, 0.5f, 0.5f)
                        .limit(1f, 3f, CgVfxModule.CollisionCompletion.FREEZE).lead(0.5f)))
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
        all.add(CgVfxEmitter.builder("triangle").rate(40f).triangle(4f, 70f, 0.5f).pointing(0f, 0f, 1f).speed(1f, 2f).build());
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

    /** Keys out of order play as Unreal plays them: every age below the first key's time holds its value. */
    @Test
    public void keysOutOfOrderHoldTheFirst() {
        Map<String, Object> tree = burstTree(burstEntry("b", 0.0), burstEntry("c", 0.5), "init");
        tree.put("particle_update", List.of(Map.of("id", "fade", "module", "crystalgraphics:scale_color", "inputs",
                Map.of("alpha", Map.of("curve", Map.of("keys", List.of(List.of(5.0, 4.0, "linear"), List.of(1.0, 10.0, "linear"))),
                        "by", "age")))));
        CgVfxEmitter e = CgVfxEmitterAssets.compile("unsorted", tree, null);
        for (float t : new float[]{0f, 0.5f, 1f}) assertEquals(4f, e.opacityAt(t), 1e-6f);
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

    /** A Cascade beam: its ends and modifier in forms, its noise's ranges, its renderer's count; written back and read again alike. */
    @Test
    public void beamsRoundTrip() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("_v", 1);
        tree.put("emitter_update", List.of(Map.of("id", "state", "module", "crystalgraphics:emitter_state",
                "inputs", Map.of("loop", "once", "duration", 1.0))));
        Object point = Map.of("range", List.of(List.of(0.1, -0.2, -0.2), List.of(0.4, 0.2, -0.2)), "per_channel", true);
        tree.put("particle_spawn", List.of(
                Map.of("id", "source", "module", "crystalgraphics:beam_source", "inputs", Map.of("point", point,
                        "tangent_method", "direct", "strength", 0.25, "lock", true, "lock_tangent", true)),
                Map.of("id", "target", "module", "crystalgraphics:beam_target", "inputs", Map.of("point", List.of(8.0, 0.0, 0.0),
                        "tangent", List.of(-1.0, 0.0, 0.0), "tangent_method", "user_set", "strength", 0.25, "lock", true,
                        "lock_radius", 0.1))));
        Map<String, Object> noise = new LinkedHashMap<>();
        noise.put("enabled_low_frequency", true);
        noise.put("frequency", 12);
        noise.put("frequency_low", 4);
        noise.put("range", Map.of("range", List.of(List.of(-1.0, -1.0, -0.15), List.of(1.0, 1.0, 0.15)), "per_channel", true));
        noise.put("range_scale", Map.of("range", List.of(0.5, 1.0)));
        noise.put("speed", List.of(0.5, 0.5, 0.5));
        noise.put("smooth", true);
        noise.put("lock_time", 0.06);
        noise.put("lock_radius", 0.01);
        noise.put("oscillate", true);
        noise.put("tessellation", 2);
        noise.put("tangent_strength", 0.0);
        noise.put("tension", 0.5);
        tree.put("particle_update", List.of(
                Map.of("id", "noise", "module", "crystalgraphics:beam_noise", "inputs", noise),
                Map.of("id", "shake", "module", "crystalgraphics:beam_modifier", "inputs", Map.of("end", "target",
                        "position", Map.of("value", Map.of("range", List.of(List.of(-0.05, -0.05, 0.0), List.of(0.05, 0.05, 0.0)),
                                "per_channel", true), "modify", true),
                        "tangent", Map.of("value", List.of(0.0, 0.0, 0.0)), "strength", Map.of("value", 0.0)))));
        tree.put("renderers", List.of(Map.of("id", "bolt", "kind", "beam", "sheets", 3, "max_beam_count", 6, "speed", 0.0,
                "interpolation_points", 25)));
        CgVfxEmitter e = CgVfxEmitterAssets.compile("bolt", tree, null);
        assertEquals(6, e.capacity);
        List<String> kinds = new ArrayList<>();
        for (CgVfxModule m : e.modules()) kinds.add(m.gpuKind());
        assertEquals(List.of("beam_source", "beam_target", "beam_noise", "beam_modifier"), kinds);
        assertEquals(3, ((CgVfxModule.BeamNoise) e.modules().get(2)).sheets());
        CgVfxEmitter back = CgVfxEmitterAssets.compile("bolt", CgVfxEmitterAssets.write(e), null);
        same(e, back);
        assertEquals(e.moduleForms().toString().replaceAll("@[0-9a-f]+", ""), back.moduleForms().toString().replaceAll("@[0-9a-f]+", ""));
    }

    /** A size range over life, rolled each step, with a locked pair: read, written back and read again alike. */
    /** A mesh's scale in Unreal's axes reaches the host's permuted, unsigned: Unreal's Y is the host's Z. */
    @Test
    public void aMeshScaleInUnrealAxesIsPermutedToTheHosts() {
        CgAssetCompiler.Dependencies unreal = new CgAssetCompiler.Dependencies() {
            @Override public Object definition(CgType kind, String id) {
                return null;
            }
            @Override public CgUnits units() {
                return CgUnits.UNREAL;
            }
        };
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("_v", 1);
        tree.put("properties", Map.of("capacity", 64));
        tree.put("emitter_update", List.of(Map.of("id", "state", "module", "crystalgraphics:emitter_state",
                "inputs", Map.of("loop", "once", "duration", 1.0))));
        Object range = Map.of("range", List.of(List.of(1.0, 20.0, 40.0), List.of(1.0, 40.0, 50.0)), "per_channel", true);
        tree.put("particle_update", List.of(Map.of("id", "rays", "module", "crystalgraphics:scale_mesh_size",
                "inputs", Map.of("scale", range))));
        CgVfxLifeRoll roll = CgVfxEmitterAssets.compile("rays", tree, null, unreal).lifeRolls().get(0);
        assertArrayEquals(new float[]{1f, 40f, 20f}, roll.from(), 0f);
        assertArrayEquals(new float[]{1f, 50f, 40f}, roll.to(), 0f);
    }

    /** Unreal's mesh factory sizes a mesh by its scale alone: the sprite size's default (50 cm) never shrinks it. */
    @Test
    public void aMeshParticleIsSizedByItsMeshScaleAlone() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("_v", 1);
        tree.put("properties", Map.of("capacity", 4));
        tree.put("emitter_update", List.of(Map.of("id", "state", "module", "crystalgraphics:emitter_state",
                "inputs", Map.of("loop", "once", "duration", 1.0))));
        tree.put("particle_spawn", List.of(Map.of("id", "init", "module", "crystalgraphics:initialize_particle",
                "inputs", Map.of("lifetime", 1.0, "mesh_scale", List.of(0.2, 0.2, 0.2)))));
        tree.put("renderers", List.of(Map.of("id", "ball", "kind", "mesh")));
        CgVfxEmitter ball = CgVfxEmitterAssets.compile("ball", tree, null, null);
        assertEquals(1f, ball.sizeMin, 0f);
        assertEquals(0.2f, ball.largestSize(), 1e-6f);
        assertNull("a mesh's file writes no sprite size", initInputs(CgVfxEmitterAssets.write(ball)).get("sprite_size"));
    }

    /** The inputs of {@code tree}'s initialize_particle. */
    private static Map<?, ?> initInputs(Map<String, Object> tree) {
        for (Object e : (List<?>) tree.get("particle_spawn")) {
            if (e instanceof Map<?, ?> m && "crystalgraphics:initialize_particle".equals(m.get("module"))) return (Map<?, ?>) m.get("inputs");
        }
        throw new AssertionError("no initialize_particle in " + tree);
    }

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

    /**
     * Niagara's three orientation modes: {@code random} a whole turn rolled per axis, {@code orient_to_vector} a constant
     * folded into both ends, or toward {@code particles.velocity} at birth, kept for the Step and written back.
     */
    @Test
    public void meshOrientationModes() {
        float turn = (float) (2 * Math.PI), half = (float) (Math.PI / 2);
        CgVfxMeshRotation random = oriented(Map.of("mode", "random", "rotation", List.of(1.0, 1.0, 1.0)));
        assertArrayEquals(new float[3], random.from(), 0f);
        assertArrayEquals(new float[] {turn, turn, turn}, random.to(), 0f);
        assertArrayEquals(new int[] {0, 1, 2}, random.rolls());
        assertNull(random.toward());
        // +X onto the host's +Y, Unreal's +Z: pitched up a quarter turn, after its own roll.
        CgVfxMeshRotation up = oriented(Map.of("mode", "orient_to_vector", "orientation_vector", List.of(0.0, 1.0, 0.0),
                "rotation", List.of(0.1, 0.0, 0.0)));
        assertArrayEquals(new float[] {0.1f, half, 0f}, up.from(), 1e-5f);
        assertArrayEquals(up.from(), up.to(), 0f);
        assertNull(up.toward());
        Map<String, Object> velocity = Map.of("mode", "orient_to_vector", "orientation_vector", Map.of("bind", "particles.velocity"),
                "mesh_axis", List.of(0.0, 2.0, 0.0));
        CgVfxMeshRotation along = oriented(velocity);
        assertArrayEquals(new float[] {0f, 0f, 1f}, along.toward(), 0f);   // the host's +Y is Unreal's +Z
        CgVfxMeshRotation back = CgVfxEmitterAssets.compile("o", CgVfxEmitterAssets.write(CgVfxEmitterAssets.compile("o",
                orientedTree(velocity), null)), null).meshRotation();
        assertArrayEquals(along.toward(), back.toward(), 0f);
        // Opposite vectors turn half a turn; a zero vector, nothing.
        assertEquals(Math.PI, Math.abs(CgVfxMeshRotation.between(new float[] {1f, 0f, 0f}, new float[] {-1f, 0f, 0f})[2]), 1e-5);
        assertArrayEquals(new float[3], CgVfxMeshRotation.between(new float[] {1f, 0f, 0f}, new float[3]), 0f);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> oriented(Map.of("mode", "spin")));
        assertTrue(e.getMessage(), e.getMessage().contains("initial_mesh_orientation's mode spin"));
    }

    private static CgVfxMeshRotation oriented(Map<String, Object> inputs) {
        return CgVfxEmitterAssets.compile("o", orientedTree(inputs), null).meshRotation();
    }

    private static Map<String, Object> orientedTree(Map<String, Object> inputs) {
        Map<String, Object> tree = burstTree(burstEntry("b1", 0.0), burstEntry("b2", 0.5), "init");
        tree.put("particle_spawn", List.of(Map.of("id", "init", "module", "crystalgraphics:initialize_particle",
                "inputs", Map.of("lifetime", 1.0)), Map.of("id", "turn", "module", "crystalgraphics:initial_mesh_orientation",
                "inputs", inputs)));
        return tree;
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

    /**
     * Cascade's Light: a brightness bound to a user parameter, a constant colour scale, a ranged radius scale and an
     * exponent stored at birth, and a light renderer's fields; read, laid out a third drawn element, written back alike.
     */
    @Test
    public void lightsRoundTrip() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("_v", 1);
        tree.put("properties", Map.of("capacity", 64));
        tree.put("emitter_update", List.of(Map.of("id", "state", "module", "crystalgraphics:emitter_state",
                "inputs", Map.of("loop", "once", "duration", 1.0))));
        Object glow = Map.of("bind", "user.Glow", "default", 0.5, "remap", Map.of("in", List.of(0.0, 1.0), "out", List.of(0.0, 40.0)));
        tree.put("particle_update", List.of(Map.of("id", "lamp", "module", "crystalgraphics:light_attributes", "inputs",
                Map.of("brightness", glow, "radius", Map.of("range", List.of(10.0, 20.0)), "exponent", 8.0,
                        "color_scale", List.of(1.0, 0.1, 0.5)))));
        tree.put("renderers", List.of(Map.of("id", "r1", "kind", "light", "inverse_squared_falloff", false, "spawn_fraction", 0.5)));
        CgVfxEmitter e = CgVfxEmitterAssets.compile("lamp", tree, null);
        CgVfxDrawnChannel brightness = e.light(CgVfxDrawnChannel.LIGHT_BRIGHTNESS);
        assertEquals(CgVfxDrawnChannel.Roll.BIND, brightness.roll());
        assertEquals(20f, brightness.bind().apply(brightness.bind().fallback()[0], 0), 1e-6f);
        assertEquals(0.1f, e.light(CgVfxDrawnChannel.LIGHT_GREEN).from().value(0), 1e-6f);
        assertEquals(8.0, ((Number) e.spawnForm(CgVfxSpawnInput.LIGHT_EXPONENT).value()).doubleValue(), 1e-9);
        CgVfxLightRendererProperties r = (CgVfxLightRendererProperties) e.renderers().get(0);
        assertEquals(0.5f, r.spawnFraction(), 0f);
        assertTrue(!r.inverseSquaredFalloff());
        CgVfxShape shape = CgVfxShape.of(e);
        assertTrue(shape.lights());
        assertEquals(3, shape.drawnStride());
        assertTrue(shape.record().word(CgVfxRecord.Tail.LIGHT) >= 0);

        CgVfxEmitter back = CgVfxEmitterAssets.compile("lamp", CgVfxEmitterAssets.write(e), null);
        for (int c = 0; c < 4; c++) assertEquals(e.light(c).roll(), back.light(c).roll());
        assertEquals(0.5f, back.light(CgVfxDrawnChannel.LIGHT_BLUE).from().value(0), 1e-6f);
        assertEquals(e.spawnForm(CgVfxSpawnInput.LIGHT_RADIUS).value(), back.spawnForm(CgVfxSpawnInput.LIGHT_RADIUS).value());
        assertEquals(e.spawnForm(CgVfxSpawnInput.LIGHT_EXPONENT).value(), back.spawnForm(CgVfxSpawnInput.LIGHT_EXPONENT).value());
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

    /**
     * Cascade's orbit: its offset, rotation and rate stored at birth whatever their form, written back alike; a second
     * orbit and a scaling chain refused by name.
     */
    @Test
    public void orbitsAreStoredAndWrittenBack() {
        Object rising = Map.of("samples", Map.of("range", List.of(0.0, 2.0), "values", List.of(0.0, 3.0)));
        Map<String, Object> orbit = new LinkedHashMap<>(Map.of("id", "o1", "module", "crystalgraphics:orbit", "inputs", Map.of(
                "offset", Map.of("range", List.of(List.of(0.0, 0.5, 0.0), List.of(0.0, 1.0, 0.0)), "per_channel", true),
                "rotation", List.of(0.0, 0.0, 0.25), "rotation_rate", Map.of("curves", List.of(rising, rising, rising), "by", "loop"))));
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("_v", 1);
        tree.put("properties", Map.of("capacity", 64));
        tree.put("emitter_update", List.of(Map.of("id", "state", "module", "crystalgraphics:emitter_state",
                "inputs", Map.of("loop", "once", "duration", 2.0))));
        tree.put("particle_update", List.of(orbit));
        CgVfxEmitter e = CgVfxEmitterAssets.compile("orbits", tree, null);
        assertTrue(CgVfxShape.of(e).record().word(CgVfxRecord.Tail.ORBIT) >= 0);
        CgVfxEmitter back = CgVfxEmitterAssets.compile("orbits", CgVfxEmitterAssets.write(e), null);
        for (CgVfxSpawnInput stored : new CgVfxSpawnInput[]{CgVfxSpawnInput.ORBIT_OFFSET, CgVfxSpawnInput.ORBIT_ROTATION,
                CgVfxSpawnInput.ORBIT_RATE}) {
            assertEquals(stored + " written back", value(e, stored), value(back, stored));
        }
        CgVfxEmitter built = CgVfxEmitter.builder("halo").orbit(0.5f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 3f).build();
        assertEquals(value(built, CgVfxSpawnInput.ORBIT_RATE),
                value(CgVfxEmitterAssets.compile("halo", CgVfxEmitterAssets.write(built), null), CgVfxSpawnInput.ORBIT_RATE));

        tree.put("particle_update", List.of(orbit, orbit));
        refuses(tree, "a second orbit");
        Map<String, Object> scaling = new LinkedHashMap<>(orbit);
        scaling.put("inputs", Map.of("chain", "scale", "offset", List.of(0.0, 1.0, 0.0), "rotation", List.of(0.0, 0.0, 0.0),
                "rotation_rate", List.of(0.0, 0.0, 0.0)));
        tree.put("particle_update", List.of(scaling));
        refuses(tree, "chained by scaling");
    }

    /**
     * Cascade's spawn and look values in the forms its kits write: a rate bound to a user parameter, a scale drawn
     * between two curves, a rate per block by the loop, a colour picked from two ends and an alpha rolled each tick;
     * each read, and written back alike.
     */
    @Test
    public void spawnAndLookFormsRoundTrip() {
        Object falling = Map.of("curve", Map.of("samples", Map.of("range", List.of(0.0, 0.5), "values", List.of(1.0, 0.0))), "by", "loop");
        Object rising = Map.of("curve", Map.of("samples", Map.of("range", List.of(0.0, 0.5), "values", List.of(0.0, 2.0))), "by", "loop");
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("_v", 1);
        tree.put("properties", Map.of("capacity", 64));
        tree.put("emitter_update", List.of(Map.of("id", "state", "module", "crystalgraphics:emitter_state",
                        "inputs", Map.of("loop", "once", "duration", 1.0)),
                Map.of("id", "rate", "module", "crystalgraphics:spawn_rate", "inputs", Map.of("rate", Map.of("bind", "user.SpawnOff",
                        "default", 20.0, "remap", Map.of("in", List.of(0.0, 1.0), "out", List.of(0.0, 1.0)), "mode", "direct"),
                        "scale", Map.of("fn", "crystalgraphics:random_range", "inputs", Map.of("min", falling, "max", rising)))),
                Map.of("id", "per_unit", "module", "crystalgraphics:spawn_per_unit", "inputs", Map.of("per_unit", falling))));
        tree.put("particle_update", List.of(Map.of("id", "tint", "module", "crystalgraphics:color", "inputs", Map.of(
                        "color", Map.of("pick", List.of(List.of(0.75, 1.5, 30.0), List.of(1.5, 7.5, 30.0))))),
                Map.of("id", "fade", "module", "crystalgraphics:scale_color", "inputs", Map.of("alpha", Map.of("range", List.of(0.0, 0.3))))));
        CgVfxEmitter e = CgVfxEmitterAssets.compile("forms", tree, null);
        assertEquals("rate", e.rate.bound().input());
        assertTrue(e.rate.scaleTo() != null && e.rate.drawn());
        assertEquals(2f * 1f, e.rate.at(0.5f, 1f) / e.rate.at(0f, 0f) * 1f * e.rate.scale().at(0f), 1e-5f);
        assertEquals(0.5f, e.perUnit.at(0.25f), 1e-5f);
        assertTrue(e.lifeRolls.get(0).picks());
        assertEquals(CgVfxLifeRoll.Target.ALPHA, e.lifeRolls.get(1).target());

        CgVfxEmitter back = CgVfxEmitterAssets.compile("forms", CgVfxEmitterAssets.write(e), null);
        assertEquals(e.rate.bound().source(), back.rate.bound().source());
        for (float t = 0f; t <= 0.5f; t += 0.125f) {
            assertEquals("rate at " + t, e.rate.at(t, 0.3f), back.rate.at(t, 0.3f), 1e-4f);
            assertEquals("per block at " + t, e.perUnit.at(t), back.perUnit.at(t), 1e-4f);
        }
        assertEquals(e.lifeRolls.size(), back.lifeRolls.size());
        for (int i = 0; i < e.lifeRolls.size(); i++) {
            assertEquals(e.lifeRolls.get(i).picks(), back.lifeRolls.get(i).picks());
            assertArrayEquals(e.lifeRolls.get(i).to(), back.lifeRolls.get(i).to(), 0f);
        }
    }

    private static void refuses(Map<String, Object> tree, String why) {
        try {
            CgVfxEmitterAssets.compile("orbits", tree, null);
            fail("compiled, which " + why + " should refuse");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(why));
        }
    }

    private static Object value(CgVfxEmitter e, CgVfxSpawnInput target) {
        for (CgVfxSpawnForm f : e.spawnForms()) if (f.target() == target) return f.value();
        return null;
    }

    /** A file's sprite size is Unreal's full width and ours a half: halved as it is read, doubled as it is written. */
    @Test
    public void aSpriteSizeIsTheFullWidthInAFile() {
        Object range = Map.of("range", List.of(List.of(1.0, 1.0), List.of(3.0, 3.0)));
        CgVfxEmitter ranged = CgVfxEmitterAssets.compile("w", sized(range), null);
        assertEquals(0.5f, ranged.sizeMin, 0f);
        assertEquals(1.5f, ranged.sizeMax, 0f);
        assertEquals(range, writtenSize(ranged));
        Object pick = Map.of("pick", List.of(List.of(2.0, 2.0), List.of(4.0, 4.0)));
        CgVfxEmitter picked = CgVfxEmitterAssets.compile("w", sized(pick), null);
        assertEquals(1f, picked.sizeMin, 0f);
        assertEquals(2f, picked.sizeMax, 0f);
        CgVfxEmitter back = CgVfxEmitterAssets.compile("w", CgVfxEmitterAssets.write(picked), null);
        assertEquals(picked.sizeMin, back.sizeMin, 0f);
        assertEquals(picked.sizeMax, back.sizeMax, 0f);
    }

    private static Map<String, Object> sized(Object size) {
        Map<String, Object> tree = burstTree(burstEntry("b1", 0.0), burstEntry("b2", 0.5), "init");
        tree.put("particle_spawn", List.of(Map.of("id", "init", "module", "crystalgraphics:initialize_particle",
                "inputs", Map.of("lifetime", 1.0, "sprite_size", size))));
        return tree;
    }

    private static Object writtenSize(CgVfxEmitter e) {
        for (Object entry : (List<?>) CgVfxEmitterAssets.write(e).get("particle_spawn")) {
            Map<?, ?> m = (Map<?, ?>) entry;
            if ("crystalgraphics:initialize_particle".equals(m.get("module"))) return ((Map<?, ?>) m.get("inputs")).get("sprite_size");
        }
        throw new AssertionError("no initialize_particle written");
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
        assertEquals(n + " per unit", e.perUnit.perBlock(), back.perUnit.perBlock(), 0f);
        for (int i = 0; i <= 10; i++) near(n + " per unit's scale", e.perUnit.scale().at(i / 10f), back.perUnit.scale().at(i / 10f));
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
