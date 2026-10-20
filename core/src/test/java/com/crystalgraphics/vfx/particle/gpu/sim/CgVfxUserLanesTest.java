package com.crystalgraphics.vfx.particle.gpu.sim;

import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.gpu.CgVfxUserBinding;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** User parameters into the kernel as lanes: the shape, the row, the generated reads. */
public class CgVfxUserLanesTest {

    private static final CgVfxSchema SCHEMA = CgVfxSchema.of("test:user_lanes");
    private static final CgVfxParam.Scalar GRAVITY = SCHEMA.scalar("gravity", 1f);
    private static final CgVfxParam.Scalar SPEED = SCHEMA.scalar("speed", 3f);
    private static final Object OWNER = new Object();

    private static CgVfxEmitter.Builder base() {
        return CgVfxEmitter.builder("lanes").rate(10f).module(new CgVfxModule.Gravity(9.8f));
    }

    /** An instance whose effect sets GRAVITY to 2 and leaves SPEED at its default. */
    private static final class View implements CgVfxInstanceView {
        float gravity = 2f;

        public double originX() { return 0; }
        public double originY() { return 0; }
        public double originZ() { return 0; }
        public float time() { return 0f; }
        public float sourceX() { return 0f; }
        public float sourceY() { return 0f; }
        public float sourceZ() { return 0f; }
        public float user(CgVfxParam.Scalar param) { return param == GRAVITY ? gravity : param.defaultValue(); }
    }

    @After
    public void forget() {
        CgVfxParticlePool.forgetAll();
    }

    @Test
    public void theBoundWordsAndTheLaneCountAreTheShapes() {
        CgVfxShape plain = CgVfxShape.of(base().build());
        CgVfxShape bound = CgVfxShape.of(base().bind(CgVfxUserBinding.module(0, 0, GRAVITY, 9.8f, 0f)).build());
        CgVfxShape other = CgVfxShape.of(base().bind(CgVfxUserBinding.module(0, 1, GRAVITY, 9.8f, 0f)).build());
        assertNotEquals(plain, bound);
        assertNotEquals(bound, other);
        assertEquals(plain.instanceRowVectors() + 1, bound.instanceRowVectors());
        // The parameter's value is never the shape's: another scale shares the pool.
        assertEquals(bound, CgVfxShape.of(base().bind(CgVfxUserBinding.module(0, 0, SPEED, 2f, 1f)).build()));
        CgVfxEmitter.Builder five = base();
        for (int w = 0; w < 5; w++) five.bind(CgVfxUserBinding.spawn(w, SPEED, 1f, 0f));
        assertEquals(plain.instanceRowVectors() + 2, CgVfxShape.of(five.build()).instanceRowVectors());
    }

    @Test
    public void aWordTheDefinitionLacksOrBindsTwiceIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> CgVfxShape.of(base().bind(CgVfxUserBinding.module(1, 0, GRAVITY, 1f, 0f)).build()));
        assertThrows(IllegalArgumentException.class,
                () -> CgVfxShape.of(base().bind(CgVfxUserBinding.module(0, 4, GRAVITY, 1f, 0f)).build()));
        assertThrows(IllegalArgumentException.class, () -> CgVfxShape.of(base()
                .bind(CgVfxUserBinding.spawn(1, SPEED, 1f, 0f)).bind(CgVfxUserBinding.spawn(1, GRAVITY, 1f, 0f)).build()));
    }

    @Test
    public void eachStepsRowCarriesTheScaledValueFromTheEffect() {
        CgVfxEmitter def = base().bind(CgVfxUserBinding.module(0, 0, GRAVITY, 9.8f, 0f))
                .bind(CgVfxUserBinding.spawn(1, SPEED, 2f, 0.5f)).build();
        CgVfxParticlePool pool = CgVfxParticlePool.of(OWNER, def);
        int slot = pool.open(def, 10);
        View view = new View();
        pool.beginStep(1f / 60f, 0f, 0f, 0f);
        pool.instance(slot, 1, 1f, Float.NaN, view);
        pool.endStep();
        CgVfxShape shape = pool.shape();
        int lanes = pool.stepInstanceAt(0) + slot * shape.instanceRowVectors() * 4 + shape.userAt() * 4;
        int[] w = pool.instanceWords();
        assertEquals(19.6f, Float.intBitsToFloat(w[lanes]), 1e-5f);
        assertEquals(6.5f, Float.intBitsToFloat(w[lanes + 1]), 0f);
        // The next step reads the changed value: an update input moves every live particle with it.
        view.gravity = 0.5f;
        pool.beginStep(1f / 60f, 0f, 0f, 0f);
        pool.instance(slot, 1, 1f, Float.NaN, view);
        pool.endStep();
        lanes = pool.stepInstanceAt(1) + slot * shape.instanceRowVectors() * 4 + shape.userAt() * 4;
        assertEquals(4.9f, Float.intBitsToFloat(pool.instanceWords()[lanes]), 1e-5f);
    }

    @Test
    public void theKernelReadsABoundModuleWordEveryStepAndABoundSpawnWordAtLaunch() {
        CgVfxShape shape = CgVfxShape.of(base().bind(CgVfxUserBinding.module(0, 0, GRAVITY, 9.8f, 0f))
                .bind(CgVfxUserBinding.spawn(1, SPEED, 1f, 0f)).build());
        String glsl = CgVfxEmitterCompiler.source(shape);
        int at = shape.paramAt(0), lane = shape.userAt();
        assertTrue(glsl, glsl.contains("if (v == " + at + ") x[0] = step_instance(inst + " + lane + ")[0];"));
        assertTrue(glsl, glsl.contains("if (v == 0) x[1] = step_instance(inst + " + lane + ")[1];"));
        assertTrue(glsl, glsl.contains("fx_gravity(p, f, s, step_vector(row, inst, " + at + "))"));
        assertTrue(glsl, glsl.contains("vec4 s0 = step_vector(row, inst, 0)"));
        // Unbound, the module reads its row as before.
        assertTrue(CgVfxEmitterCompiler.source(CgVfxShape.of(base().build())).contains("fx_gravity(p, f, s, step_param(row + " + at + "))"));
    }
}
