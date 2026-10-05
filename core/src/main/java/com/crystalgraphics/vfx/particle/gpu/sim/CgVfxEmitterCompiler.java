package com.crystalgraphics.vfx.particle.gpu.sim;

import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.vfx.particle.gpu.CgVfxLane;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWorldInput;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Writes a shape's Step kernel: one {@code append} kernel that runs a particle step for every particle of a pool, in
 * the CPU tick's order (vfx-gpu §13.2). Element {@code e} below the live count is a particle of the pool's newest
 * version; one past it spawns from the step's spawn rows ({@code CgVfxEmitterInstance.spawnOne}, so the newborn step
 * with the rest, as on the CPU). Then the modules before the solver, in stack order; the solver; the modules after it;
 * ageing; and the survivors append into the pool's next version.
 *
 * <pre>{@code
 * CgKernel step = CgVfxEmitterCompiler.compile(shape).kernel("Step");
 * String glsl = CgVfxEmitterCompiler.source(shape);     // what it compiles: a .compute
 * }</pre>
 *
 * <ul>
 *   <li>Every tier: an {@code append} kernel lowers, so G40 and G33 run it through transform feedback.</li>
 *   <li>A kind's file is {@code crystalgraphics:shaders/lib/vfx/sim/fx_<kind>.glsl}; a missing one throws from
 *       {@link #compile}, naming it.</li>
 * </ul>
 */
public final class CgVfxEmitterCompiler {

    private static final String KIND_DIR = "crystalgraphics:shaders/lib/vfx/sim/fx_";

    private CgVfxEmitterCompiler() {
    }

    /** {@code shape}'s kernels, parsed once per shape. Any thread; its programs are the render thread's. */
    public static CgCompute compile(CgVfxShape shape) {
        return CgCompute.fromSource(key(shape), source(shape));
    }

    /** What {@link #compile} registers the source under. */
    public static String key(CgVfxShape shape) {
        return "crystalgraphics:vfx/pool/" + shape.key();
    }

    /** {@code shape}'s Step kernel as {@code .compute} text. */
    public static String source(CgVfxShape shape) {
        StringBuilder s = new StringBuilder(4096);
        s.append("// Written by CgVfxEmitterCompiler for ").append(shape.key()).append("\n");
        s.append("#pragma kernel Step append\n\n");
        s.append("#include \"crystalgraphics:shaders/lib/vfx/sim/fx_types.glsl\"\n");
        s.append("#include \"crystalgraphics:shaders/lib/vfx/fx_rand.glsl\"\n");
        Set<String> kinds = new LinkedHashSet<>();
        for (int i = 0; i < shape.modules(); i++) kinds.add(shape.kind(i));
        for (String kind : kinds) s.append("#include \"").append(KIND_DIR).append(kind).append(".glsl\"\n");
        s.append("""

                Properties {
                    _Step       ("Step length, then the wind",       vec4) = (0, 0, 0, 0)
                    _InstanceAt ("This step's instance rows",        int)  = 0
                    _SpawnAt    ("This step's spawn rows",           int)  = 0
                    _SpawnRows  ("How many spawn rows",              int)  = 0
                    _Spawned    ("Spawn candidates, every row's",    int)  = 0
                }

                """);
        s.append(CgVfxRecord.GLSL).append("\n\n");
        s.append("""
                Buffers {
                    IN        ("Particles",       FxRecord, readonly)
                    OUT       ("Particles after", FxRecord, append)
                    LIVE      ("Live",            uint,     readonly)
                    PARAMS    ("Parameter rows",  uvec4,    readonly)
                    INSTANCES ("Instance rows",   uvec4,    readonly)
                    SPAWNS    ("Spawn rows",      uvec4,    readonly)
                }

                """);
        s.append("const int STEP_PARAM_ROW = ").append(shape.paramRowVectors()).append(";\n");
        s.append("const int STEP_INSTANCE_ROW = ").append(shape.instanceRowVectors()).append(";\n");
        s.append("""

                vec4 step_param(int at) {
                    return uintBitsToFloat(PARAMS(at));
                }

                vec4 step_instance(int at) {
                    return uintBitsToFloat(INSTANCES(at));
                }

                FxParticle step_unpack(FxRecord r) {
                    FxParticle p;
                    p.position = r.positionAge.xyz;
                    p.age = r.positionAge.w;
                    p.previous = r.previousLife.xyz;
                    p.life = r.previousLife.w;
                    p.velocity = r.velocitySize.xyz;
                    p.size = r.velocitySize.w;
                    p.seed = r.seedSpin.x;
                    p.spin = r.seedSpin.y;
                    p.spinRate = r.seedSpin.z;
                    p.heat = r.seedSpin.w;
                    p.resting = (r.idSlot.z & 1u) != 0u;
                    p.id = r.idSlot.x;
                    p.slot = r.idSlot.y;
                    return p;
                }

                FxRecord step_pack(FxParticle p, uint paramRow) {
                    FxRecord r;
                    r.positionAge = vec4(p.position, p.age);
                    r.previousLife = vec4(p.previous, p.life);
                    r.velocitySize = vec4(p.velocity, p.size);
                    r.seedSpin = vec4(p.seed, p.spin, p.spinRate, p.heat);
                    r.idSlot = uvec4(p.id, p.slot, p.resting ? 1u : 0u, paramRow);
                    return r;
                }

                // CgVfxEmitterInstance.spawnOne: spawn k of the instance in slot, unless its share thins it out.
                bool step_spawn(uint slot, uint k, out FxParticle p) {
                    int inst = _InstanceAt + int(slot) * STEP_INSTANCE_ROW;
                    uvec4 head = INSTANCES(inst);
                    vec4 source = step_instance(inst + 1);
                    float share = step_instance(inst + 2).x;
                    uint seed = head.y;
                    if (share < 1.0 && fx_rand(seed, k, 10u) >= share) return false;
                    int row = int(head.x) * STEP_PARAM_ROW;
                    vec4 s0 = step_param(row), s1 = step_param(row + 1), s2 = step_param(row + 2), s3 = step_param(row + 3);
                    float up = s0.y + (s0.z - s0.y) * pow(fx_rand(seed, k, 0u), s0.w);
                    float heading = fx_rand(seed, k, 1u) * 6.2831853;
                    float across = sqrt(max(1.0 - up * up, 0.0));
                    vec3 dir = vec3(across * cos(heading), up, across * sin(heading));
                    float start = s0.x * pow(fx_rand(seed, k, 2u), 1.0 / 3.0);
                    float speed = s1.x + (s1.y - s1.x) * fx_rand(seed, k, 3u);
                    p.position = source.xyz + dir * start;
                    p.previous = p.position;
                    p.velocity = dir * speed;
                    p.age = 0.0;
                    p.life = s1.z + (s1.w - s1.z) * fx_rand(seed, k, 4u);
                    p.size = s2.x + (s2.y - s2.x) * pow(fx_rand(seed, k, 5u), s2.z);
                    p.seed = fx_rand(seed, k, 6u);
                    float spin = s3.x + (s3.y - s3.x) * fx_rand(seed, k, 7u);
                    p.spinRate = fx_rand(seed, k, 8u) < 0.5 ? -spin : spin;
                    p.spin = fx_rand(seed, k, 9u) * 6.2831853;
                    p.heat = s2.w;
                    p.resting = false;
                    p.id = k;
                    p.slot = slot;
                    return true;
                }

                void Step() {
                    int live = int(LIVE(0));
                    FxParticle p;
                    if (CG_ELEMENT < live) {
                        p = step_unpack(IN(CG_ELEMENT));
                    } else {
                        int j = CG_ELEMENT - live;
                        if (j >= _Spawned) return;
                        // The last spawn row starting at or before j: rows hold their first candidate in w.
                        int lo = 0, hi = _SpawnRows - 1;
                        while (lo < hi) {
                            int mid = (lo + hi + 1) >> 1;
                            if (int(SPAWNS(_SpawnAt + mid).w) <= j) lo = mid; else hi = mid - 1;
                        }
                        uvec4 spawn = SPAWNS(_SpawnAt + lo);
                        if (!step_spawn(spawn.x, spawn.y + uint(j - int(spawn.w)), p)) return;
                    }
                    int inst = _InstanceAt + int(p.slot) * STEP_INSTANCE_ROW;
                    uint paramRow = INSTANCES(inst).x;
                    int row = int(paramRow) * STEP_PARAM_ROW;
                    float ground = step_instance(inst + 2).y;
                    FxStep s;
                    s.dt = _Step.x;
                    s.wind = _Step.yzw;
                    FxForces f;
                    f.accel = vec3(0.0);
                    f.drag = 0.0;
                    f.dragQuad = 0.0;
                """);
        for (int i = 0; i < shape.modules(); i++) if (!shape.afterSolve(i)) call(s, shape, i);
        s.append("""
                    // CgVfxEmitterInstance.solve: forces into velocity, drag implicit, then position
                    p.previous = p.position;
                    if (!p.resting) {
                        vec3 v = p.velocity + f.accel * s.dt;
                        float keep = 1.0 / (1.0 + (f.drag + f.dragQuad * length(v)) * s.dt);
                        p.velocity = v * keep;
                        p.position += p.velocity * s.dt;
                        p.spin += p.spinRate * s.dt;
                    }
                """);
        for (int i = 0; i < shape.modules(); i++) if (shape.afterSolve(i)) call(s, shape, i);
        s.append("""
                    p.age += s.dt;
                    if (p.age >= p.life) return;
                    OUT_APPEND(step_pack(p, paramRow));
                }
                """);
        return s.toString();
    }

    /** Module {@code i}'s call: its numbers from the parameter row, its lanes from the instance row, its world inputs. */
    private static void call(StringBuilder s, CgVfxShape shape, int i) {
        s.append("    fx_").append(shape.kind(i)).append(shape.afterSolve(i) ? "(p, s" : "(p, f, s");
        for (int j = 0, at = shape.paramAt(i); j < shape.paramVectors(i); j++) s.append(", step_param(row + ").append(at + j).append(')');
        CgVfxLane[] lanes = shape.lanes(i);
        for (int l = 0, at = shape.lanesAt(i); l < lanes.length; l++) {
            switch (lanes[l]) {
                case VEC4 -> s.append(", step_instance(inst + ").append(at + l).append(')');
                case IVEC4 -> s.append(", ivec4(INSTANCES(inst + ").append(at + l).append("))");
                case UVEC4 -> s.append(", INSTANCES(inst + ").append(at + l).append(')');
            }
        }
        for (CgVfxWorldInput input : shape.worldInputs(i)) {
            switch (input) {
                case FLOOR_Y -> s.append(", ground");
            }
        }
        s.append(");\n");
    }
}
