package com.crystalgraphics.vfx.particle.gpu.sim;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.vfx.particle.gpu.CgVfxDrawLayout;
import com.crystalgraphics.object.CgValueType;
import com.crystalgraphics.shape.primitive.CgBox;
import com.crystalgraphics.shape.primitive.CgPlane;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxField;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import com.crystalgraphics.vfx.particle.CgVfxModuleTypes;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuModule;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWords;
import com.crystalgraphics.vfx.particle.gpu.CgVfxDataInterface;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;
import com.crystalgraphics.vfx.prefab.CgVfxPrefab;
import com.crystalgraphics.vfx.sequence.CgVfxNodeClip;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CgVfxEmitterCompilerTest {

    /** Every module kind, the ground the one after the solver, and drag twice. */
    private static final CgVfxEmitter EVERY_KIND = CgVfxEmitter.builder("every").state(CgVfxEmitterState.ONCE)
            .capacity(64).burst(0f, 64).life(1f, 2f)
            .module(new CgVfxModule.Gravity(9.8f))
            .module(new CgVfxModule.Drag(0.3f, 0.15f))
            .module(new CgVfxModule.Wind(0.2f))
            .module(new CgVfxModule.Turbulence(1f, 0.06f, 0.15f))
            .module(new CgVfxModule.Buoyancy(1.2f, 2.5f))
            .module(new CgVfxModule.Updraft(4f, 2f, 6f, 1f))
            .module(new CgVfxModule.Ground(0.3f, 0.5f, 0.8f))
            .module(new CgVfxModule.Spin(0.5f))
            .module(new CgVfxModule.Drag(0.1f, 0f))
            .build();

    @Test
    public void modulesRunInStackOrderAroundTheSolver() {
        String glsl = CgVfxEmitterCompiler.source(CgVfxShape.of(EVERY_KIND));
        int solve = glsl.indexOf("// The solve");
        String[] before = {"fx_gravity(p, f, s", "fx_drag(p, f, s", "fx_wind(p, f, s", "fx_curl_noise(p, f, s",
                "fx_buoyancy(p, f, s", "fx_updraft(p, f, s", "fx_spin(p, f, s"};
        int at = -1;
        for (String call : before) {
            int next = glsl.indexOf(call, at + 1);
            assertTrue(call + " in order, before the solver", next > at && next < solve);
            at = next;
        }
        assertTrue(glsl.indexOf("fx_drag(p, f, s", at) > at);                                // the second drag, last
        assertTrue(glsl.indexOf("fx_ground(p, s") > solve);
        assertEquals(1, count(glsl, "#include \"crystalgraphics:shaders/lib/vfx/sim/fx_drag.glsl\""));
    }

    @Test
    public void aModuleTakesItsRowsNumbersItsInstanceLanesAndItsWorldInputs() {
        CgVfxShape shape = CgVfxShape.of(EVERY_KIND);
        String glsl = CgVfxEmitterCompiler.source(shape);
        int turbulence = 3, ground = 6;
        int p = shape.paramAt(turbulence), l = shape.lanesAt(turbulence);
        assertTrue(glsl, glsl.contains("fx_curl_noise(p, f, s, step_param(row + " + p + "), ivec4(INSTANCES(inst + " + l
                + ")), step_instance(inst + " + (l + 1) + "), ivec4(INSTANCES(inst + " + (l + 2) + ")), step_instance(inst + "
                + (l + 3) + "));"));
        assertTrue(glsl, glsl.contains("fx_ground(p, s, step_param(row + " + shape.paramAt(ground) + "), step_floor(p, inst));"));
        // the floor needs the voxel window's functions, so the kernel takes it too
        assertTrue(shape.takes(CgVfxDataInterfaces.WORLD));
        assertTrue(glsl.contains("lib/vfx/sim/fx_world_at.glsl") && glsl.contains("_WorldLive") && glsl.contains("float step_floor("));
    }

    /** A kind given as text, bouncing on the window's distance field, its impacts and a rate as events. */
    private static final class Bounce implements CgVfxGpuModule {
        private static final CgVfxDataInterface[] WORLD = {CgVfxDataInterfaces.WORLD_DISTANCE};
        static {
            CgVfxModuleTypes.declare("test_bounce", Bounce.class,
                    t -> t.property("keep", CgValueType.FLOAT).property("hit_speed", CgValueType.FLOAT),
                    (m, in) -> in.f("keep", 0.5f).f("hit_speed", 1f));
        }
        public String gpuKind() { return "test_bounce"; }
        public boolean afterSolve() { return true; }
        public CgVfxDataInterface[] dataInterfaces() { return WORLD; }
        public String gpuSource() {
            return """
                    void fx_test_bounce(inout FxParticle p, FxStep s, vec4 m, FxWorld world) {
                        vec3 g;
                        if (fx_world_sdf(world, p.position, g) > 0.25 || !fx_world_solid(world, p.position)) return;
                        vec3 n = length(g) > 0.0 ? normalize(g) : vec3(0.0, 1.0, 0.0);
                        if (-dot(p.velocity, n) >= m.y) fx_hit(p, n);
                        p.velocity = reflect(p.velocity, n) * m.x;
                    }
                    """;
        }
    }

    private record Hooked(CgVfxEmitter of, List<CgVfxGpuModule> modules, List<CgVfxEvent> events, int[] moduleWords)
            implements CgVfxGpuEmitter {
        Hooked(CgVfxEmitter of, List<CgVfxGpuModule> modules, List<CgVfxEvent> events) {
            this(of, modules, events, CgVfxModuleTypes.words(modules));
        }

        public String name() { return of.name(); }
        public CgVfxDrawLayout drawLayout() { return of.drawLayout(); }
        public void writeSpawn(CgVfxWords out) { of.writeSpawn(out); }
        public void writeCurves(float[] out, int at, int texels) { of.writeCurves(out, at, texels); }
    }

    @Test
    public void aCustomKindOnTheDistanceFieldFiresCollisionsAndARate() {
        List<CgVfxGpuModule> modules = new ArrayList<>(EVERY_KIND.modules());
        modules.add(new Bounce());
        CgVfxGpuEmitter hooked = new Hooked(EVERY_KIND, modules,
                List.of(CgVfxEvent.onCollision().spawn(EVERY_KIND, 2), CgVfxEvent.every(0.1f).readback(8)));
        CgVfxShape shape = CgVfxShape.of(hooked);
        String glsl = CgVfxEmitterCompiler.source(shape);
        assertTrue(shape.takes(CgVfxDataInterfaces.WORLD_DISTANCE) && shape.takes(CgVfxDataInterfaces.WORLD));
        assertEquals(1, count(glsl, "void fx_test_bounce("));                                   // inlined, once
        assertTrue(glsl.contains("fx_test_bounce(p, s, step_param(row + " + shape.paramAt(modules.size() - 1) + "), step_world(inst));"));
        assertTrue(glsl.contains("if (p.hit.w > 0.0 && float(p.collisions) <= step_param(") && glsl.contains("p.collisions);"));
        // A rate's periods count from its window's start, from birth unless given.
        assertTrue(glsl.contains("floor(((p.age - step_param(row + " + (shape.eventsAt() + CgVfxShape.EVENT_FROM)
                + ")[1]) - s.dt) / step_param(row + " + shape.eventsAt() + ")[1])"));
        CgVfxEmitterCompiler.compile(shape).kernel("Step").check();
    }

    /** Cascade's location bVelocity: the birth's place from the shape's middle times a rate, rolled per particle. */
    @Test
    public void aShapesVelocityAddsItsPlaceTimesARate() {
        CgVfxEmitter flung = CgVfxEmitter.builder("flung").state(CgVfxEmitterState.ONCE).capacity(64).burst(0f, 64)
                .life(1f, 2f).shape(CgSphere.of(0.12)).surface().shapeVelocity(Map.of("range", List.of(2.0, 5.0)), false)
                .build();
        CgVfxShape shape = CgVfxShape.of(flung);
        assertTrue(CgVfxEmitterCompiler.source(shape).contains("step_rotate(q, local * vec3(1.0, 1.0 - s4.z, 1.0)) * "));
        assertTrue(!CgVfxEmitterCompiler.source(CgVfxShape.of(EVERY_KIND)).contains("1.0 - s4.z"));
        CgVfxEmitterCompiler.compile(shape).kernel("Step").check();
    }

    @Test
    public void theStepKernelRunsOnEveryTier() {
        CgVfxEmitterCompiler.compile(CgVfxShape.of(EVERY_KIND)).kernel("Step").check();
    }

    @Test
    public void theCatalogueCompilesOnEveryTier() {
        CgSphere sphere = CgSphere.of(2);
        CgVfxField field = CgVfxField.of(4, 4, 4, true, (x, y, z, out) -> out[0] = x);
        CgVfxEmitter catalogue = CgVfxEmitter.builder("catalogue").state(CgVfxEmitterState.ONCE)
                .capacity(64).burst(0f, 64).life(1f, 2f)
                .module(new CgVfxModule.Attract(sphere, 20f).attenuation(2f).directed(0.3f, 0f, 1f, 0f).at(0f, 1f, 0f))
                .module(new CgVfxModule.Vortex(0f, 1f, 0f, 10f, -3f, 0f, 0f, 0f))
                .module(new CgVfxModule.Force(1f, 0f, 0f))
                .module(new CgVfxModule.VectorField(field, CgBox.of(2, 2, 2), 3f, 1f))
                .module(new CgVfxModule.Damping(2f))
                .module(new CgVfxModule.Conform(sphere, 2f, 30f, 5f).at(0f, 1f, 0f))
                .module(new CgVfxModule.LimitSpeed(8f))
                .module(new CgVfxModule.Orbit(0.5f, 0f, 1f, 0f, 0f, 0f, 0f))
                .module(new CgVfxModule.Collide(CgBox.of(1, 1, 1), 0.5f, 0.2f, 0.1f))
                .module(new CgVfxModule.Collide(CgPlane.of(0, 1, 0), 0f, 0f, 0f).killing())
                .module(new CgVfxModule.CollideWorld(0.4f, 0.3f, 0.3f))
                .module(new CgVfxModule.CollideDepth(0.4f, 0.3f, 0.3f, 0.5f))
                .module(new CgVfxModule.CollideEntities(0.4f, 0.3f, 0.3f, 0.5f))
                .module(new CgVfxModule.Kill(CgBox.of(20, 20, 20), false))
                .event(CgVfxEvent.onCollision().readback(8))
                .build();
        CgVfxShape shape = CgVfxShape.of(catalogue);
        String glsl = CgVfxEmitterCompiler.source(shape);
        assertEquals(1, count(glsl, "#include \"crystalgraphics:shaders/lib/vfx/sim/fx_collide.glsl\""));
        assertTrue(shape.takes(CgVfxDataInterfaces.WORLD));
        assertTrue(shape.takes(CgVfxDataInterfaces.ENTITIES));
        assertTrue("a row carries what was struck and its entity", glsl.contains("ev.normal = vec4(n, hit)")
                && glsl.contains("int(p.hitEntity)") && glsl.contains(", step_entities(inst));"));
        CgVfxEmitterCompiler.compile(shape).kernel("Step").check();
    }

    @Test
    public void aClipsEventsFireAtItsNodesAndTheirChildrenSpawnTwoLevelsDeep() {
        CgVfxEmitter leaf = CgVfxEmitter.builder("leaf").capacity(64).life(0.5f, 0.5f).event(CgVfxEvent.onDeath().readback(4))
                .build();
        CgVfxEmitter middle = CgVfxEmitter.builder("middle").capacity(64).life(1f, 1f)
                .module(new CgVfxModule.Gravity(9.8f)).module(new CgVfxModule.Ground(0.3f, 0.5f, 0.8f))
                .event(CgVfxEvent.onLanding().spawn(leaf, 2)).build();
        CgVfxPrefab prefab = CgVfxPrefab.builder("test:spinner").node("hub").node("tip").parent("hub").at(0f, 1f, 0f)
                .part(CgMaterialDefinition.of("test:blade", "crystalgraphics:shaders/vfx/sequence/part.shader").build())
                .build();
        CgVfxNodeClip clip = CgVfxNodeClip.of(prefab, 30).channel("hub", CgVfxNodeClip.Channel.ROTATION_Y,
                CgVfxNodeClip.Interp.LINEAR, 0, 0f, 30, 360f).burst("tip", middle, 10, 3).stream("tip", middle, 5, 25, 20f).build();
        CgVfxEmitter root = CgVfxEmitter.builder("root").sequence(clip).state(CgVfxEmitterState.ONCE).capacity(4)
                .burst(0f, 1).life(1f, 1f).build();

        CgVfxShape top = CgVfxShape.of(root), mid = CgVfxShape.of(middle, 1), low = CgVfxShape.of(leaf, 2);
        assertTrue(top.readsClips() && top.spawns() && top.eventAtNode(0) && top.eventAtNode(1));
        String glsl = CgVfxEmitterCompiler.source(top);
        assertTrue(glsl.contains("step_node(p, inst, row, 0, at, n);") && glsl.contains("FX_CLIPS"));
        assertTrue("a root reads no parents", !glsl.contains("PARENT_EVENTS"));
        assertTrue(CgVfxEmitterCompiler.source(mid).contains("fx_child_key_at(ev.ids.x"));
        assertTrue(CgVfxEmitterCompiler.source(low).contains("fx_grandchild_key(ev.ids.x"));
        assertTrue("each tier its own pool", !mid.key().equals(CgVfxShape.of(middle).key()));
        CgVfxShape deepest = CgVfxShape.of(leaf, CgVfxShape.TIERS - 1);
        assertTrue(CgVfxEmitterCompiler.source(deepest).contains("fx_grandchild_key(ev.ids.x"));
        for (CgVfxShape shape : new CgVfxShape[]{top, mid, low, deepest}) CgVfxEmitterCompiler.compile(shape).kernel("Step").check();

        // root has children's children: one more level above it is the third, and the fourth is refused
        CgVfxEmitter apex = CgVfxEmitter.builder("apex").capacity(4).event(CgVfxEvent.onDeath().spawn(root, 1)).build();
        assertThrows(IllegalArgumentException.class, () -> CgVfxEvent.onDeath().spawn(apex, 1));
        assertThrows(IllegalStateException.class, () -> CgVfxEmitter.builder("grounded").sequence(clip)
                .module(new CgVfxModule.Ground(0.3f, 0.5f, 0.8f)).build());
        assertThrows(IllegalStateException.class, () -> CgVfxEmitter.builder("loose")
                .event(CgVfxEvent.onDeath().at(prefab.node("tip"))).build());
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) n++;
        return n;
    }
}
