package com.crystalgraphics.vfx.particle.gpu.sim;

import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.CgVfxLane;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWorldInput;
import com.crystalgraphics.vfx.world.CgVfxVoxelWindow;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Writes a shape's Step kernel: one {@code append} kernel that runs a particle step for every particle of a pool, in
 * the CPU tick's order (vfx-gpu §13.2). Element {@code e} below the live count is a particle of the pool's newest
 * version; one past it spawns from the step's spawn rows ({@code CgVfxEmitterInstance.spawnOne}, so the newborn step
 * with the rest, as on the CPU). Then the modules before the solver, in stack order; the solver; the modules after it;
 * ageing; and the survivors append into the pool's next version. A shape with events appends a row each time one
 * fires ({@link CgVfxParticlePool#EVENT_GLSL}); a shape whose events spawn nothing can be a child, so past its spawn
 * rows it spawns children from the rows the step's parents appended ({@code step_child}).
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

    /** The scene's depth as {@code fx_depth_at.glsl} reads it; the pool binds them ({@code CgVfxParticlePool.depth}). */
    static final String DEPTH_PROPERTIES = """
                _DepthPyramid ("The scene's eye depth: CgGpuOps.depthPyramid's level 0", sampler2D) = "white"
                _DepthSize    ("Its width and height; 0 with no depth",                   vec4)      = (0, 0, 0, 0)
                _DepthClipX   ("Projection times view, camera-relative: row 0",            vec4)      = (1, 0, 0, 0)
                _DepthClipY   ("Row 1",                                                   vec4)      = (0, 1, 0, 0)
                _DepthClipW   ("Row 3",                                                   vec4)      = (0, 0, 0, 1)
                _DepthLens    ("Projection [0][0], [1][1], [2][0], [2][1]",               vec4)      = (1, 1, 0, 0)
                _DepthViewX   ("The view's inverse: column 0",                            vec4)      = (1, 0, 0, 0)
                _DepthViewY   ("Column 1",                                                vec4)      = (0, 1, 0, 0)
                _DepthViewZ   ("Column 2",                                                vec4)      = (0, 0, 1, 0)
                _DepthViewW   ("Column 3",                                                vec4)      = (0, 0, 0, 1)
                _DepthEyeX    ("The camera's block: x",                                   int)       = 0
                _DepthEyeY    ("y",                                                       int)       = 0
                _DepthEyeZ    ("z",                                                       int)       = 0
                _DepthEyeFrac ("Where in that block",                                     vec4)      = (0, 0, 0, 0)
            """;

    private CgVfxEmitterCompiler() {
    }

    /** {@code shape}'s kernels, parsed once per shape. Any thread; its programs are the render thread's. */
    public static CgCompute compile(CgVfxShape shape) {
        return CgCompute.fromSource(key(shape), source(shape));
    }

    /** What {@link #compile} registers the source under. */
    public static String key(CgVfxShape shape) {
        return "crystalgraphics:vfx/pool/" + shape.kernelKey();
    }

    /** {@code shape}'s Step kernel as {@code .compute} text. */
    public static String source(CgVfxShape shape) {
        StringBuilder s = new StringBuilder(4096);
        s.append("// Written by CgVfxEmitterCompiler for ").append(shape.kernelKey()).append("\n");
        s.append("#pragma kernel Step append\n\n");
        s.append("#include \"crystalgraphics:shaders/lib/vfx/sim/fx_types.glsl\"\n");
        s.append("#include \"crystalgraphics:shaders/lib/vfx/fx_rand.glsl\"\n");
        s.append("#include \"crystalgraphics:shaders/lib/vfx/fx_event.glsl\"\n");
        if (shape.readsWorld()) s.append("#include \"crystalgraphics:shaders/lib/vfx/sim/fx_world_at.glsl\"\n");
        if (shape.usesDepth()) s.append("#include \"crystalgraphics:shaders/lib/vfx/sim/fx_depth_at.glsl\"\n");
        Set<String> kinds = new LinkedHashSet<>();
        for (int i = 0; i < shape.modules(); i++) {
            if (!kinds.add(shape.kind(i))) continue;
            if (shape.source(i) == null) s.append("#include \"").append(KIND_DIR).append(shape.kind(i)).append(".glsl\"\n");
            else s.append("\n// fx_").append(shape.kind(i)).append(", given as text\n").append(shape.source(i)).append('\n');
        }
        s.append("""

                Properties {
                    _Step       ("Step length, then the wind",       vec4) = (0, 0, 0, 0)
                    _InstanceAt ("This step's instance rows",        int)  = 0
                    _SpawnAt    ("This step's spawn rows",           int)  = 0
                    _SpawnRows  ("How many spawn rows",              int)  = 0
                    _Spawned    ("Spawn candidates, every row's",    int)  = 0
                    _PoolId     ("The pool, as event rows name it",  int)  = 0
                    _ChildRows  ("Parent event rows read at most",   int)  = 0
                    _ChildMax   ("Children an event spawns at most", int)  = 0
                    _Feeds      ("Feed rows",                        int)  = 0
                """);
        if (shape.readsWorld()) s.append(CgVfxVoxelWindow.PROPERTIES);
        if (shape.usesDepth()) s.append(DEPTH_PROPERTIES);
        for (int i = 0; i < shape.modules(); i++) {
            for (int t = 0; t < shape.textureCount(i); t++) {
                s.append("    ").append(texture(i, t)).append(" (\"fx_").append(shape.kind(i)).append("'s texture ").append(t)
                        .append("\", ").append(shape.volume(i, t) ? "sampler3D" : "sampler2D").append(") = \"black\"\n");
            }
        }
        s.append("}\n\n");
        s.append(CgVfxRecord.GLSL).append("\n");
        s.append(CgVfxParticlePool.EVENT_GLSL).append("\n\n");
        s.append("""
                Buffers {
                    IN        ("Particles",       FxRecord, readonly)
                    OUT       ("Particles after", FxRecord, append)
                    LIVE      ("Live",            uint,     readonly)
                    PARAMS    ("Parameter rows",  uvec4,    readonly)
                    INSTANCES ("Instance rows",   uvec4,    readonly)
                    SPAWNS    ("Spawn rows",      uvec4,    readonly)
                """);
        if (shape.spawns()) s.append("    SPAWN_EVENTS  (\"Events that spawn children\", FxEvent, append)\n");
        if (shape.reports()) s.append("    REPORT_EVENTS (\"Events the CPU hears\",       FxEvent, append)\n");
        if (!shape.spawns()) {
            s.append("    PARENT_EVENTS (\"This step's parents' events\", FxEvent, readonly)\n");
            s.append("    PARENT_COUNT  (\"How many\",                    uint,    readonly)\n");
            s.append("    FEEDS         (\"Child slots by parent slot\",  uvec4,   readonly)\n");
        }
        s.append("}\n\n");
        s.append("const int STEP_PARAM_ROW = ").append(shape.paramRowVectors()).append(";\n");
        s.append("const int STEP_INSTANCE_ROW = ").append(shape.instanceRowVectors()).append(";\n");
        s.append("""

                vec4 step_param(int at) {
                    return uintBitsToFloat(PARAMS(at));
                }

                vec4 step_instance(int at) {
                    return uintBitsToFloat(INSTANCES(at));
                }
""");
        if (shape.readsWorld()) {
            s.append("""

                    // The floor under p from the voxel window (CgVfxGround.floor's), relative to its origin; the
                    // instance's fixed height with no level.
                    float step_floor(FxParticle p, int inst) {
                        if (_WorldLive == 0) return step_instance(inst + 2).y;
                        return fx_world_floor(_World, _WorldSections, ivec3(_WorldBaseX, _WorldBaseY, _WorldBaseZ),
                                ivec3(_WorldWrapX, _WorldWrapY, _WorldWrapZ), ivec3(INSTANCES(inst + 3).xyz),
                                step_instance(inst + 4).xyz, p.position, p.previous.y);
                    }

                    // The window as a kind taking WORLD reads it: from this instance's origin.
                    FxWorld step_world(int inst) {
                        FxWorld w;
                        w.originBlock = ivec3(INSTANCES(inst + 3).xyz);
                        w.originFrac = step_instance(inst + 4).xyz;
                        w.live = _WorldLive != 0;
                        return w;
                    }
                    """);
        }
        if (shape.usesDepth()) {
            s.append("""

                    // The scene's depth as a kind taking DEPTH reads it: the camera from this instance's origin.
                    FxDepth step_depth(int inst) {
                        FxDepth d;
                        d.eye = vec3(ivec3(_DepthEyeX, _DepthEyeY, _DepthEyeZ) - ivec3(INSTANCES(inst + 3).xyz))
                                + _DepthEyeFrac.xyz - step_instance(inst + 4).xyz;
                        d.live = _DepthSize.x > 0.0;
                        return d;
                    }
                    """);
        }
        s.append("""

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
                    p.collisions = r.idSlot.z >> 16u;
                    p.id = r.idSlot.x;
                    p.slot = r.idSlot.y;
                    p.hit = vec4(0.0);
                    return p;
                }

                FxRecord step_pack(FxParticle p, uint paramRow) {
                    FxRecord r;
                    r.positionAge = vec4(p.position, p.age);
                    r.previousLife = vec4(p.previous, p.life);
                    r.velocitySize = vec4(p.velocity, p.size);
                    r.seedSpin = vec4(p.seed, p.spin, p.spinRate, p.heat);
                    r.idSlot = uvec4(p.id, p.slot, (p.resting ? 1u : 0u) | p.collisions << 16u, paramRow);
                    return r;
                }

                // A launch's local (x, up, z) turned so up is n: Duff et al. 2017's basis, the identity for straight up.
                vec3 step_orient(vec3 v, vec3 n) {
                    if (n.x == 0.0 && n.z == 0.0 && n.y > 0.0) return v;
                    float sg = n.z >= 0.0 ? 1.0 : -1.0;
                    float a = -1.0 / (sg + n.z), b = n.x * n.y * a;
                    vec3 t = vec3(1.0 + sg * n.x * n.x * a, sg * b, -sg * n.x);
                    vec3 u = vec3(b, sg + n.y * n.y * a, -n.y);
                    return t * v.x + n * v.y + u * v.z;
                }

                // CgVfxEmitterInstance.spawnOne's launch of spawn k from seed with row's numbers, at `at`, about n.
                void step_launch(uint seed, uint k, int row, vec3 at, vec3 n, out FxParticle p) {
                    vec4 s0 = step_param(row), s1 = step_param(row + 1), s2 = step_param(row + 2), s3 = step_param(row + 3);
                    float up = s0.y + (s0.z - s0.y) * pow(fx_rand(seed, k, 0u), s0.w);
                    float heading = fx_rand(seed, k, 1u) * 6.2831853;
                    float across = sqrt(max(1.0 - up * up, 0.0));
                    vec3 dir = step_orient(vec3(across * cos(heading), up, across * sin(heading)), n);
                    float start = s0.x * pow(fx_rand(seed, k, 2u), 1.0 / 3.0);
                    float speed = s1.x + (s1.y - s1.x) * fx_rand(seed, k, 3u);
                    p.position = at + dir * start;
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
                    p.collisions = 0u;
                    p.hit = vec4(0.0);
                }

                // CgVfxEmitterInstance.spawnOne: spawn k of the instance in slot, unless its share thins it out.
                bool step_spawn(uint slot, uint k, out FxParticle p) {
                    int inst = _InstanceAt + int(slot) * STEP_INSTANCE_ROW;
                    uvec4 head = INSTANCES(inst);
                    float share = step_instance(inst + 2).x;
                    uint seed = head.y;
                    if (share < 1.0 && fx_rand(seed, k, 10u) >= share) return false;
                    step_launch(seed, k, int(head.x) * STEP_PARAM_ROW, step_instance(inst + 1).xyz, vec3(0.0, 1.0, 0.0), p);
                    p.id = k;
                    p.slot = slot;
                    return true;
                }

                """);
        if (!shape.spawns()) s.append("""
                // Child c of this step's parent events: row c / _ChildMax's child c % _ChildMax, for the slot its feed
                // names, launched about the event's normal from where it fired, with a share of its velocity.
                bool step_child(int c, out FxParticle p) {
                    if (_ChildMax == 0) return false;
                    int r = c / _ChildMax, i = c - r * _ChildMax;
                    if (r >= min(int(PARENT_COUNT(0)), _ChildRows) || _Feeds == 0) return false;
                    FxEvent ev = PARENT_EVENTS(r);
                    uint key = ev.ids.y << 3u | ev.ids.z;
                    int lo = 0, hi = _Feeds - 1;
                    while (lo < hi) {
                        int mid = (lo + hi) >> 1;
                        if (FEEDS(mid).x < key) lo = mid + 1; else hi = mid;
                    }
                    uvec4 feed = FEEDS(lo);
                    if (feed.x != key || uint(i) >= feed.z) return false;
                    uint slot = feed.y;
                    int inst = _InstanceAt + int(slot) * STEP_INSTANCE_ROW;
                    uvec4 head = INSTANCES(inst);
                    float share = step_instance(inst + 2).x;
                    // A repeating event's firing, from 1, in w: each firing's children are other particles.
                    uint seed = head.y, k = ev.ids.w == 0u ? fx_child_key(ev.ids.x, ev.ids.z, uint(i))
                            : fx_child_key_at(ev.ids.x, ev.ids.z, uint(i), ev.ids.w);
                    if (share < 1.0 && fx_rand(seed, k, 10u) >= share) return false;
                    // From the parent's block and the place within it to this slot's origin.
                    vec3 at = vec3(ev.block.xyz - ivec3(INSTANCES(inst + 3).xyz)) + ev.position.xyz - step_instance(inst + 4).xyz;
                    step_launch(seed, k, int(head.x) * STEP_PARAM_ROW, at, ev.normal.xyz, p);
                    p.velocity += uintBitsToFloat(feed.w) * ev.velocityAge.xyz;
                    p.id = k;
                    p.slot = slot;
                    return true;
                }
""");
        if (shape.events() > 0) s.append("""
                // The direction of v, or up when it barely moves: a death's and an age's normal.
                vec3 step_normal(vec3 v) {
                    float d = dot(v, v);
                    return d > 1e-12 ? v / sqrt(d) : vec3(0.0, 1.0, 0.0);
                }

                // Event e of p, its velocity v and normal n, and for a repeating trigger which firing: where it fired, as
                // its slot's origin's block and the place within it.
                FxEvent step_event(FxParticle p, int inst, uint e, vec3 v, vec3 n, uint firing) {
                    FxEvent ev;
                    ev.position = vec4(step_instance(inst + 4).xyz + p.position, 0.0);
                    ev.velocityAge = vec4(v, p.age);
                    ev.normal = vec4(n, 0.0);
                    ev.block = ivec4(INSTANCES(inst + 3));
                    ev.ids = uvec4(p.id, uint(_PoolId) << 16u | p.slot, e, firing);
                    return ev;
                }
""");
        s.append("""

                void Step() {
                    int live = int(LIVE(0));
                    FxParticle p;
                    if (CG_ELEMENT < live) {
                        p = step_unpack(IN(CG_ELEMENT));
                    } else {
                        int j = CG_ELEMENT - live;
                        if (j >= _Spawned) {
                """);
        s.append(shape.spawns() ? "            return;\n" : "            if (!step_child(j - _Spawned, p)) return;\n");
        s.append("""
                        } else {
                            // The last spawn row starting at or before j: rows hold their first candidate in w.
                            int lo = 0, hi = _SpawnRows - 1;
                            while (lo < hi) {
                                int mid = (lo + hi + 1) >> 1;
                                if (int(SPAWNS(_SpawnAt + mid).w) <= j) lo = mid; else hi = mid - 1;
                            }
                            uvec4 spawn = SPAWNS(_SpawnAt + lo);
                            if (!step_spawn(spawn.x, spawn.y + uint(j - int(spawn.w)), p)) return;
                        }
                    }
                    bool wasResting = p.resting;
                    int inst = _InstanceAt + int(p.slot) * STEP_INSTANCE_ROW;
                    uint paramRow = INSTANCES(inst).x;
                    int row = int(paramRow) * STEP_PARAM_ROW;
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
                    // What it struck with: a landing's and a collision's velocity.
                    vec3 impact = p.velocity;
                    p.hit = vec4(0.0);
                """);
        for (int i = 0; i < shape.modules(); i++) if (shape.afterSolve(i)) call(s, shape, i);
        s.append("""
                    p.age += s.dt;
                """);
        for (int e = 0; e < shape.events(); e++) {
            switch (shape.trigger(e)) {
                case LANDING -> event(s, shape, e, "p.resting && !wasResting", "impact", "vec3(0.0, 1.0, 0.0)", "0u");
                case AGE -> event(s, shape, e, "p.age - s.dt < " + eventValue(shape, e) + " && " + eventValue(shape, e)
                        + " <= p.age", "p.velocity", "step_normal(p.velocity)", "0u");
                case COLLISION -> event(s, shape, e, "p.hit.w > 0.0 && float(p.collisions) <= " + eventLimit(shape, e),
                        "impact", "p.hit.xyz", "p.collisions");
                case RATE -> event(s, shape, e, "floor((p.age - s.dt) / " + eventValue(shape, e) + ") < floor(p.age / "
                        + eventValue(shape, e) + ") && floor(p.age / " + eventValue(shape, e) + ") <= " + eventLimit(shape, e),
                        "p.velocity", "step_normal(p.velocity)", "uint(floor(p.age / " + eventValue(shape, e) + "))");
                case DEATH -> { }
            }
        }
        s.append("    if (p.age >= p.life) {\n");
        for (int e = 0; e < shape.events(); e++) {
            if (shape.trigger(e) == CgVfxEvent.Trigger.DEATH) event(s, shape, e, "true", "p.velocity", "step_normal(p.velocity)", "0u");
        }
        s.append("""
                        return;
                    }
                    OUT_APPEND(step_pack(p, paramRow));
                }
                """);
        return s.toString();
    }

    /** Event {@code e}'s age or period, from the parameter row. */
    private static String eventValue(CgVfxShape shape, int e) {
        return "step_param(row + " + (shape.eventsAt() + e / 4) + ")[" + e % 4 + "]";
    }

    /** Repeating event {@code e}'s most firings, from the parameter row. */
    private static String eventLimit(CgVfxShape shape, int e) {
        return "step_param(row + " + (shape.eventsAt() + CgVfxEvent.MAX_EVENTS / 4 + e / 4) + ")[" + e % 4 + "]";
    }

    /** Event {@code e}'s row, appended to each stream it goes to when {@code when} holds. */
    private static void event(StringBuilder s, CgVfxShape shape, int e, String when, String velocity, String normal,
                              String firing) {
        s.append("    if (").append(when).append(") {\n");
        s.append("        FxEvent ev = step_event(p, inst, ").append(e).append("u, ").append(velocity).append(", ").append(normal)
                .append(", ").append(firing).append(");\n");
        if (shape.eventSpawns(e)) s.append("        SPAWN_EVENTS_APPEND(ev);\n");
        if (shape.eventReports(e)) s.append("        REPORT_EVENTS_APPEND(ev);\n");
        s.append("    }\n");
    }

    /** Module {@code i}'s call: its numbers from the parameter row, its lanes from the instance row, its world inputs, its textures. */
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
                case FLOOR_Y -> s.append(", step_floor(p, inst)");
                case WORLD, WORLD_DISTANCE -> s.append(", step_world(inst)");
                case DEPTH -> s.append(", step_depth(inst)");
            }
        }
        for (int t = 0; t < shape.textureCount(i); t++) s.append(", ").append(texture(i, t));
        s.append(");\n");
    }

    /** Module {@code i}'s texture {@code t}'s sampler property: what the pool binds it as. */
    static String texture(int i, int t) {
        return "_Texture" + i + "_" + t;
    }
}
