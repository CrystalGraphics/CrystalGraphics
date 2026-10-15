package com.crystalgraphics.vfx.particle.gpu.sim;

import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.vfx.gas.CgVfxGasAtlas;
import com.crystalgraphics.vfx.grid.CgVfxGridAtlas;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.CgVfxLane;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWorldInput;
import com.crystalgraphics.vfx.world.CgVfxVoxelWindow;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Writes a shape's Step kernel: one {@code append} kernel that runs a particle step for every particle of a pool
 * (vfx-gpu §13.2). Element {@code e} below the live count is a particle of the pool's newest version; one past it spawns
 * from the step's spawn rows, born where its emitter's shape puts it ({@code step_place}) and stepped for the part of the
 * step after its birth. Then the modules before the solver, in stack order; the solver; the modules after it;
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

    /** The neighbour grid's properties, for a shape taking {@code NEIGHBOURS}: the pool sets them each step. */
    private static final String NEIGHBOUR_PROPERTIES = """
                _NeighbourCell  ("The neighbour grid's cell width, blocks", float) = 1.0
                _NeighbourCells ("Its hashed cells; 0 before it is built", int)   = 0
            """;

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
        if (shape.usesEntities()) s.append("#include \"crystalgraphics:shaders/lib/vfx/sim/fx_entities_at.glsl\"\n");
        if (shape.usesNeighbours()) s.append("#include \"crystalgraphics:shaders/lib/vfx/sim/fx_neighbours_at.glsl\"\n");
        if (shape.usesGas()) s.append("#include \"crystalgraphics:shaders/lib/vfx/sim/fx_gas_at.glsl\"\n");
        if (shape.usesGrid()) s.append("#include \"crystalgraphics:shaders/lib/vfx/sim/grid/fx_grid_at.glsl\"\n");
        // Declared ahead of the kinds' functions that call them; defined with the other step_ functions.
        if (shape.feedsGas()) s.append("void step_feed(FxGas g, vec3 p, float density, float heat, float dt);\n");
        if (shape.splatsGrid()) s.append("void step_splat(FxGrid g, vec3 p, vec4 amounts, float dt);\n");
        Set<String> kinds = new LinkedHashSet<>();
        for (int i = 0; i < shape.modules(); i++) {
            if (!kinds.add(shape.kind(i))) continue;
            if (shape.source(i) == null) {
                String file = shape.file(i) != null ? shape.file(i) : KIND_DIR + shape.kind(i) + ".glsl";
                s.append("#include \"").append(file).append("\"\n");
            }
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
        if (shape.usesEntities()) s.append(CgVfxEntityBoxes.PROPERTIES);
        if (shape.usesNeighbours()) s.append(NEIGHBOUR_PROPERTIES);
        if (shape.usesGas()) s.append(CgVfxGasAtlas.FLOW_PROPERTIES);
        if (shape.usesGrid()) s.append(CgVfxGridAtlas.VIEW_PROPERTIES);
        for (int i = 0; i < shape.modules(); i++) {
            for (int t = 0; t < shape.textureCount(i); t++) {
                s.append("    ").append(texture(i, t)).append(" (\"fx_").append(shape.kind(i)).append("'s texture ").append(t)
                        .append("\", ").append(shape.volume(i, t) ? "sampler3D" : "sampler2D").append(") = \"black\"\n");
            }
        }
        s.append("}\n\n");
        s.append(CgVfxRecord.GLSL).append("\n");
        s.append(CgVfxParticlePool.EVENT_GLSL).append("\n\n");
        if (shape.feedsGas()) s.append(CgVfxGasAtlas.DEPOSIT_GLSL).append("\n\n");
        if (shape.splatsGrid()) s.append(CgVfxGridAtlas.SPLAT_GLSL).append("\n\n");
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
        if (shape.usesEntities()) s.append("    ENTITIES      (\"Entity boxes, two rows each\", uvec4,   readonly)\n");
        if (shape.feedsGas()) s.append("    GAS_DEPOSITS  (\"What particles pour into the gas\", FxDeposit, append)\n");
        if (shape.splatsGrid()) s.append("    GRID_SPLATS   (\"What particles splat into the grid\", FxSplat, append)\n");
        if (shape.spawnsOnMesh()) s.append("    MESHES        (\"Spawn meshes' triangles and vertices\", uvec4, readonly)\n");
        if (shape.usesNeighbours()) {
            s.append("    SORTED        (\"Last step's particles by hashed cell\", uint, readonly)\n");
            s.append("    CELL_RUNS     (\"Each hashed cell's run in SORTED, first then end\", uint, readonly)\n");
        }
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

                    // The floor under p from the voxel window (a stair's step included), relative to its origin; the
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
                        w.floorY = step_instance(inst + 2).y;
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
        if (shape.usesEntities()) {
            s.append("""

                    // The entity boxes as a kind taking ENTITIES reads them: this instance's origin in their frame.
                    FxEntities step_entities(int inst) {
                        FxEntities e;
                        e.origin = vec3(ivec3(INSTANCES(inst + 3).xyz) - ivec3(_EntityAnchorX, _EntityAnchorY, _EntityAnchorZ))
                                + step_instance(inst + 4).xyz;
                        e.count = _Entities;
                        return e;
                    }

                    void step_entity(int i, out vec3 lo, out vec3 hi, out uint id) {
                        uvec4 a = ENTITIES(i * 2), b = ENTITIES(i * 2 + 1);
                        lo = uintBitsToFloat(a.xyz);
                        hi = uintBitsToFloat(b.xyz);
                        id = a.w;
                    }
                    """);
        }
        if (shape.usesGas()) {
            s.append("\n// The gas as a kind taking GAS reads it: this instance's origin from the gas's grid's low corner.\n")
                    .append("FxGas step_gas(int inst) {\n")
                    .append("    vec4 a = step_instance(inst + ").append(shape.gasAt()).append("), b = step_instance(inst + ")
                    .append(shape.gasAt() + 1).append(");\n")
                    .append("""
                                FxGas g;
                                g.offset = a.xyz;
                                g.gas = int(a.w + 0.5);
                                g.cell = b.x;
                                g.live = a.w >= 0.0 && _GasLive != 0;
                                return g;
                            }
                            """);
        }
        if (shape.feedsGas()) {
            s.append("""

                    // What a kind taking GAS_FEED pours: density and heat a second into the gas at p (from the instance's
                    // origin), for this step's dt, as a deposit its next step takes.
                    void step_feed(FxGas g, vec3 p, float density, float heat, float dt) {
                        if (!g.live) return;
                        GAS_DEPOSITS_APPEND(FxDeposit(vec4((p + g.offset) / g.cell, float(g.gas)),
                                vec4(density * dt, heat * dt, 0.0, 0.0)));
                    }
                    """);
        }
        if (shape.usesGrid()) {
            s.append("\n// The grid as a kind taking GRID reads it: this instance's origin from the grid's low corner.\n")
                    .append("FxGrid step_grid(int inst) {\n")
                    .append("    vec4 a = step_instance(inst + ").append(shape.gridAt()).append("), b = step_instance(inst + ")
                    .append(shape.gridAt() + 1).append(");\n")
                    .append("""
                                FxGrid g;
                                g.offset = a.xyz;
                                g.brick = int(a.w + 0.5);
                                g.cell = b.x;
                                g.cells = b.y;
                                g.live = a.w >= 0.0 && _GridLive != 0;
                                return g;
                            }
                            """);
        }
        if (shape.splatsGrid()) {
            s.append("""

                    // What a kind taking GRID_SPLAT adds: amounts a second into the grid's cell at p (from the instance's
                    // origin), for this step's dt, as a splat the grid takes this frame.
                    void step_splat(FxGrid g, vec3 p, vec4 amounts, float dt) {
                        if (!g.live) return;
                        GRID_SPLATS_APPEND(FxSplat(vec4((p + g.offset) / g.cell, float(g.brick)), amounts * dt));
                    }
                    """);
        }
        if (shape.spawnsOnMesh()) s.append("""

                vec4 step_mesh_word(int at) {
                    return uintBitsToFloat(MESHES(at));
                }

                // CgVfxSpawnMeshes: a triangle by area through its alias table (a column by u1, then u4 against its
                // share), a point in it by one uniform barycentric; or a vertex, drawn or the k-th of `ordered` spread
                // through them. Its normal is the way out.
                void step_mesh(uint k, vec4 n, int placement, vec4 s6, float u1, float u2, float u3, float u4,
                               out vec3 pos, out vec3 way) {
                    int first = int(n.x), count = int(n.y);
                    if (placement == 3) {
                        int ordered = int(s6.w);
                        int v = ordered > 0 ? int(k % uint(ordered)) * count / ordered : min(int(u1 * float(count)), count - 1);
                        pos = step_mesh_word(first + v * 2).xyz * n.z;
                        way = step_mesh_word(first + v * 2 + 1).xyz;
                        return;
                    }
                    int t = min(int(u1 * float(count)), count - 1);
                    vec4 a = step_mesh_word(first + t * 3);
                    if (u4 >= a.w) {
                        t = int(step_mesh_word(first + t * 3 + 1).w);
                        a = step_mesh_word(first + t * 3);
                    }
                    vec3 b = step_mesh_word(first + t * 3 + 1).xyz, c = step_mesh_word(first + t * 3 + 2).xyz;
                    float r = sqrt(u2);
                    pos = (a.xyz * (1.0 - r) + b * (r * (1.0 - u3)) + c * (r * u3)) * n.z;
                    vec3 f = cross(b - a.xyz, c - a.xyz);
                    float d = dot(f, f);
                    way = d > 1e-20 ? f * inversesqrt(d) : vec3(0.0, 1.0, 0.0);
                }
                """);
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
                    p.hitEntity = 0u;
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

                // CgVfxEmitter.front: how far out spawn k's swept front is, from the row's fourth vector; 0 for none.
                float step_front(uint k, int row) {
                    vec4 s3 = step_param(row + 3);
                    if (s3.y == 0.0) return 0.0;
                    float held = min(float(k), s3.x / (2.0 * s3.y));
                    return s3.x * held - s3.y * held * held;
                }

                const float STEP_TAU = 6.2831853;

                vec3 step_rotate(vec4 q, vec3 v) {
                    vec3 t = 2.0 * cross(q.xyz, v);
                    return v + q.w * t + cross(q.xyz, t);
                }

                // The heading round a shape's axis: spawn k's place among `ordered` even ones, else drawn, within its arc.
                float step_heading(uint seed, uint k, uint draw, vec4 s6) {
                    int n = int(s6.w);
                    if (n == 0) return fx_rand(seed, k, draw) * s6.z;
                    float i = float(k % uint(n));
                    if (s6.z >= STEP_TAU - 1e-4) return i / float(n) * s6.z;
                    return n > 1 ? i / float(n - 1) * s6.z : 0.0;
                }

                // A box of half extents e: filled, its faces by area, or its edges by length.
                void step_box(vec3 e, int placement, bool upper, float u1, float u2, float u3, float u4, out vec3 pos,
                              out vec3 way) {
                    if (placement == 0) {
                        pos = (vec3(u1, u2, u3) * 2.0 - 1.0) * e;
                    } else if (placement == 1) {
                        vec3 area = vec3(e.y * e.z, e.x * e.z, e.x * e.y);
                        float t = u1 * (area.x + area.y + area.z);
                        int axis = t < area.x ? 0 : t < area.x + area.y ? 1 : 2;
                        float face = u4 < 0.5 ? -1.0 : 1.0;
                        if (axis == 0) pos = vec3(face * e.x, (u2 * 2.0 - 1.0) * e.y, (u3 * 2.0 - 1.0) * e.z);
                        else if (axis == 1) pos = vec3((u2 * 2.0 - 1.0) * e.x, face * e.y, (u3 * 2.0 - 1.0) * e.z);
                        else pos = vec3((u2 * 2.0 - 1.0) * e.x, (u3 * 2.0 - 1.0) * e.y, face * e.z);
                    } else {
                        // An axis, which of its four, then along it.
                        float t = u1 * (e.x + e.y + e.z);
                        int axis = t < e.x ? 0 : t < e.x + e.y ? 1 : 2;
                        int corner = int(u2 * 4.0);
                        vec3 c = vec3((corner & 1) != 0 ? 1.0 : -1.0, (corner & 2) != 0 ? 1.0 : -1.0, 1.0);
                        if (axis == 0) pos = vec3((u3 * 2.0 - 1.0) * e.x, c.x * e.y, c.y * e.z);
                        else if (axis == 1) pos = vec3(c.x * e.x, (u3 * 2.0 - 1.0) * e.y, c.y * e.z);
                        else pos = vec3(c.x * e.x, c.y * e.y, (u3 * 2.0 - 1.0) * e.z);
                    }
                    if (upper) pos.y = abs(pos.y);
                    float d = dot(pos, pos);
                    way = d > 1e-12 ? pos / sqrt(d) : vec3(0.0, 1.0, 0.0);
                }

                // CgVfxSpawnShape: where spawn k of the instance at inst is born in its shape's sampling frame (axis
                // +Y), and the way out of the shape's middle there. `up` is the launch band's draw, which a point or a
                // sphere is born along.
                void step_place(uint seed, uint k, int row, int inst, float up, out vec3 pos, out vec3 way) {
                    vec4 s6 = step_param(row + 6), n = step_param(row + 7);
                    int kind = int(s6.x), flags = int(s6.y), placement = flags & 3;
                    bool upper = (flags & 4) != 0;
                    float u1 = fx_rand(seed, k, 11u), u2 = fx_rand(seed, k, 12u), u3 = fx_rand(seed, k, 13u);
                    float u4 = fx_rand(seed, k, 14u);
                    if (kind <= 1) {
                        // A point or a sphere: born along the band's direction, at a heading its arc and order allow.
                        if (upper) up = abs(up);
                        if (placement == 2) up = 0.0;
                        float h = step_heading(seed, k, 1u, s6), across = sqrt(max(1.0 - up * up, 0.0));
                        way = vec3(across * cos(h), up, across * sin(h));
                        float r = kind == 0 ? 0.0 : placement == 0
                                ? pow(mix(n.x * n.x * n.x, n.y * n.y * n.y, fx_rand(seed, k, 2u)), 1.0 / 3.0) : n.y;
                        pos = way * r;
                        return;
                    }
                    if (kind == 2) {
                        step_box(n.xyz, placement, upper, u1, u2, u3, u4, pos, way);
                        return;
                    }
                    if (kind == 7) {
                        // The followed entity's box, from the instance row: its centre from the source, its halves.
                        step_box(step_instance(inst + 7).xyz, placement, upper, u1, u2, u3, u4, pos, way);
                        pos += step_instance(inst + 6).xyz;
                        return;
                    }
                """);
        if (shape.spawnsOnMesh()) s.append("""
                    if (kind == 6) {
                        step_mesh(k, n, placement, s6, u1, u2, u3, u4, pos, way);
                        return;
                    }
                """);
        s.append("""
                    float h = step_heading(seed, k, 11u, s6);
                    vec3 ring = vec3(cos(h), 0.0, sin(h));
                    if (kind == 3) {
                        // A cylinder or ring: inner and outer radius, half height.
                        float r, y;
                        if (placement == 0) {
                            r = sqrt(mix(n.x * n.x, n.y * n.y, u2));
                            y = (u3 * 2.0 - 1.0) * n.z;
                        } else if (placement == 1) {
                            float outer = n.y * 2.0 * n.z, inner = n.x * 2.0 * n.z, caps = n.y * n.y - n.x * n.x;
                            float t = u2 * (outer + inner + caps);
                            if (t < outer + inner) {
                                r = t < outer ? n.y : n.x;
                                y = (u3 * 2.0 - 1.0) * n.z;
                            } else {
                                r = sqrt(mix(n.x * n.x, n.y * n.y, u3));
                                y = u4 < 0.5 ? -n.z : n.z;
                            }
                        } else {
                            r = n.y;
                            y = n.z == 0.0 ? 0.0 : u3 < 0.5 ? -n.z : n.z;
                        }
                        pos = vec3(ring.x * r, upper ? abs(y) : y, ring.z * r);
                        way = ring;
                        return;
                    }
                    if (kind == 4) {
                        // A cone from its apex: range, half angle; a spherical sector, its cap and its side by area.
                        float ca = cos(n.y), ct, r;
                        if (placement == 0) {
                            ct = 1.0 - u2 * (1.0 - ca);
                            r = n.x * pow(u3, 1.0 / 3.0);
                        } else if (placement == 1) {
                            float cap = 2.0 * (1.0 - ca), side = sin(n.y);
                            if (u2 * (cap + side) < cap) {
                                ct = 1.0 - u3 * (1.0 - ca);
                                r = n.x;
                            } else {
                                ct = ca;
                                r = n.x * sqrt(u3);
                            }
                        } else {
                            ct = ca;
                            r = n.x;
                        }
                        float st = sqrt(max(1.0 - ct * ct, 0.0));
                        way = vec3(ring.x * st, ct, ring.z * st);
                        pos = way * r;
                        return;
                    }
                    // A capsule along its segment: half length, radius; its side and its ends by volume or area.
                    float L = n.x, rr = n.y;
                    if (placement == 2) {
                        int count = int(s6.w);
                        float y = count > 1 ? -L + 2.0 * L * float(k % uint(count)) / float(count - 1)
                                : count == 1 ? 0.0 : (u3 * 2.0 - 1.0) * L;
                        float h2 = fx_rand(seed, k, 11u) * STEP_TAU;
                        pos = vec3(0.0, upper ? abs(y) : y, 0.0);
                        way = vec3(cos(h2), 0.0, sin(h2));
                        return;
                    }
                    bool side = placement == 0 ? u2 * (2.0 * L + 4.0 / 3.0 * rr) < 2.0 * L : u2 * (L + rr) < L;
                    float rho = placement == 0 ? rr * (side ? sqrt(u4) : pow(u4, 1.0 / 3.0)) : rr;
                    if (side) {
                        pos = vec3(ring.x * rho, (u3 * 2.0 - 1.0) * L, ring.z * rho);
                        way = ring;
                    } else {
                        float e = u3 * 2.0 - 1.0, across = sqrt(max(1.0 - e * e, 0.0));
                        way = vec3(ring.x * across, e, ring.z * across);
                        pos = way * rho + vec3(0.0, e >= 0.0 ? L : -L, 0.0);
                    }
                    if (upper && pos.y < 0.0) {
                        pos.y = -pos.y;
                        way.y = -way.y;
                    }
                }

                // Spawn k of the instance at inst from seed with the row's numbers, at `at`, its frame's up turned to n (an event's normal), front
                // blocks further out.
                void step_launch(uint seed, uint k, int row, int inst, vec3 at, vec3 n, float front, out FxParticle p) {
                    vec4 s0 = step_param(row), s1 = step_param(row + 1), s2 = step_param(row + 2), s4 = step_param(row + 4);
                    vec4 q = step_param(row + 8);
                    float up = s4.x + (s4.y - s4.x) * pow(fx_rand(seed, k, 0u), s4.z);
                    vec3 local, way;
                    step_place(seed, k, row, inst, up, local, way);
                    vec3 outward = step_rotate(q, way), dir;
                    int mode = int(s4.w);
                    if (mode == 0) dir = outward;
                    else if (mode == 1) dir = -outward;
                    else if (mode == 2) {
                        // Within a cone round the direction: uniform over its cap.
                        vec4 s5 = step_param(row + 5);
                        float ct = 1.0 - fx_rand(seed, k, 15u) * (1.0 - s5.w), st = sqrt(max(1.0 - ct * ct, 0.0));
                        float phi = fx_rand(seed, k, 16u) * STEP_TAU;
                        dir = step_orient(vec3(st * cos(phi), ct, st * sin(phi)), s5.xyz);
                    } else {
                        float heading = fx_rand(seed, k, 1u) * STEP_TAU, across = sqrt(max(1.0 - up * up, 0.0));
                        dir = step_rotate(q, vec3(across * cos(heading), up, across * sin(heading)));
                    }
                    vec3 place = step_rotate(q, local) + step_param(row + 9).xyz;
                    float speed = s0.x + (s0.y - s0.x) * fx_rand(seed, k, 3u);
                    p.position = at + step_orient(place + outward * front, n);
                    p.previous = p.position;
                    p.velocity = step_orient(dir, n) * speed;
                    p.age = 0.0;
                    p.life = s0.z + (s0.w - s0.z) * fx_rand(seed, k, 4u);
                    p.size = s1.x + (s1.y - s1.x) * pow(fx_rand(seed, k, 5u), s1.z);
                    p.seed = fx_rand(seed, k, 6u);
                    float spin = s2.x + (s2.y - s2.x) * fx_rand(seed, k, 7u);
                    p.spinRate = fx_rand(seed, k, 8u) < 0.5 ? -spin : spin;
                    p.spin = fx_rand(seed, k, 9u) * 6.2831853;
                    p.heat = s1.w;
                    p.resting = false;
                    p.collisions = 0u;
                    p.hit = vec4(0.0);
                    p.hitEntity = 0u;
                }

                // Spawn k, the i-th of its spawn row's n, of the instance in slot, unless its share thins it out: a burst
                // where the source was as the step began, else a rate spawn (i - bursts + 1) / (n - bursts) of the way to
                // where it ended. `born` is how far through the step it was born: it is stepped for the rest.
                bool step_spawn(uint slot, uint k, int i, int n, out FxParticle p, out float born) {
                    born = 0.0;
                    int inst = _InstanceAt + int(slot) * STEP_INSTANCE_ROW;
                    uvec4 head = INSTANCES(inst);
                    float share = step_instance(inst + 2).x;
                    uint seed = head.y;
                    if (share < 1.0 && fx_rand(seed, k, 10u) >= share) return false;
                    int row = int(head.x) * STEP_PARAM_ROW;
                    vec4 to = step_instance(inst + 1), from = step_instance(inst + 5);
                    int bursts = int(from.w);
                    if (i >= bursts) born = float(i - bursts + 1) / float(n - bursts);
                    step_launch(seed, k, row, inst, mix(from.xyz, to.xyz, born), vec3(0.0, 1.0, 0.0), step_front(k, row), p);
                    p.velocity += step_param(row + 2).z * (to.xyz - from.xyz) / _Step.x;
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
                    step_launch(seed, k, int(head.x) * STEP_PARAM_ROW, inst, at, ev.normal.xyz, 0.0, p);
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

                // Event e of p, its velocity v and normal n, what it met (an FX_HIT_ code) and for a repeating trigger which
                // firing: where it fired, as its slot's origin's block and the place within it.
                FxEvent step_event(FxParticle p, int inst, uint e, vec3 v, vec3 n, float hit, uint firing) {
                    FxEvent ev;
                    ev.position = vec4(step_instance(inst + 4).xyz + p.position, 0.0);
                    ev.velocityAge = vec4(v, p.age);
                    ev.normal = vec4(n, hit);
                    ev.block = ivec4(ivec3(INSTANCES(inst + 3).xyz), int(p.hitEntity));
                    ev.ids = uvec4(p.id, uint(_PoolId) << 16u | p.slot, e, firing);
                    return ev;
                }
""");
        if (shape.usesNeighbours()) s.append("""

                uvec2 step_cell_run(int h) {
                    return uvec2(CELL_RUNS(h * 2), CELL_RUNS(h * 2 + 1));
                }

                FxNeighbour step_neighbour(uint k) {
                    int i = int(SORTED(int(k)));
                    FxRecord r = IN(i);
                    FxNeighbour n;
                    n.position = r.positionAge.xyz;
                    n.velocity = r.velocitySize.xyz;
                    n.slot = r.idSlot.y;
                    n.index = i;
                    return n;
                }

                // The grid as a kind taking NEIGHBOURS reads it: p is last step's particle self, or a newborn (-1).
                FxNeighbours step_neighbours(int self, uint slot) {
                    FxNeighbours n;
                    n.cell = _NeighbourCell;
                    n.cells = _NeighbourCells;
                    n.slot = slot;
                    n.self = self;
                    return n;
                }
                """);
        s.append("""

                void Step() {
                    int live = int(LIVE(0));
                    FxParticle p;
                    float born = 0.0;
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
                            int i = j - int(spawn.w);
                            if (!step_spawn(spawn.x, spawn.y + uint(i), i, int(spawn.z), p, born)) return;
                        }
                    }
                    bool wasResting = p.resting;
                    int inst = _InstanceAt + int(p.slot) * STEP_INSTANCE_ROW;
                    // A killed slot: its particles end here, firing nothing.
                    if (step_instance(inst + 2).w > 0.0) return;
                    uint paramRow = INSTANCES(inst).x;
                    int row = int(paramRow) * STEP_PARAM_ROW;
                    FxStep s;
                    // A slot held still while another effect prewarms steps by 0: unmoved, unaged. A newborn steps for
                    // the part of the step after its birth.
                    s.dt = _Step.x * step_instance(inst + 2).z * (1.0 - born);
                    s.wind = _Step.yzw;
                    FxForces f;
                    f.accel = vec3(0.0);
                    f.drag = 0.0;
                    f.dragQuad = 0.0;
                """);
        for (int i = 0; i < shape.modules(); i++) if (!shape.afterSolve(i)) call(s, shape, i);
        s.append("""
                    // The solve: forces into velocity, drag implicit, then position
                    // Held still, it keeps the step it is drawn between.
                    if (s.dt > 0.0) p.previous = p.position;
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
                    p.hitEntity = 0u;
                """);
        for (int i = 0; i < shape.modules(); i++) if (shape.afterSolve(i)) call(s, shape, i);
        s.append("""
                    p.age += s.dt;
                """);
        for (int e = 0; e < shape.events(); e++) {
            switch (shape.trigger(e)) {
                case LANDING -> event(s, shape, e, "p.resting && !wasResting", "impact", "vec3(0.0, 1.0, 0.0)", "FX_HIT_GROUND", "0u");
                case AGE -> event(s, shape, e, "p.age - s.dt < " + eventValue(shape, e) + " && " + eventValue(shape, e)
                        + " <= p.age", "p.velocity", "step_normal(p.velocity)", "0.0", "0u");
                case COLLISION -> event(s, shape, e, "p.hit.w > 0.0 && float(p.collisions) <= " + eventLimit(shape, e),
                        "impact", "p.hit.xyz", "p.hit.w", "p.collisions");
                case RATE -> event(s, shape, e, "floor((p.age - s.dt) / " + eventValue(shape, e) + ") < floor(p.age / "
                        + eventValue(shape, e) + ") && floor(p.age / " + eventValue(shape, e) + ") <= " + eventLimit(shape, e),
                        "p.velocity", "step_normal(p.velocity)", "0.0", "uint(floor(p.age / " + eventValue(shape, e) + "))");
                case DEATH -> { }
            }
        }
        s.append("    if (p.age >= p.life) {\n");
        for (int e = 0; e < shape.events(); e++) {
            if (shape.trigger(e) == CgVfxEvent.Trigger.DEATH) event(s, shape, e, "true", "p.velocity", "step_normal(p.velocity)", "0.0", "0u");
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
                              String hit, String firing) {
        s.append("    if (").append(when).append(") {\n");
        s.append("        FxEvent ev = step_event(p, inst, ").append(e).append("u, ").append(velocity).append(", ").append(normal)
                .append(", ").append(hit).append(", ").append(firing).append(");\n");
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
                case ENTITIES -> s.append(", step_entities(inst)");
                case NEIGHBOURS -> s.append(", step_neighbours(CG_ELEMENT < live ? CG_ELEMENT : -1, p.slot)");
                case GAS, GAS_FEED -> s.append(", step_gas(inst)");
                case GRID, GRID_SPLAT -> s.append(", step_grid(inst)");
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
