package com.crystalgraphics.vfx.particle;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.ArrayList;
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
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWords;
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
