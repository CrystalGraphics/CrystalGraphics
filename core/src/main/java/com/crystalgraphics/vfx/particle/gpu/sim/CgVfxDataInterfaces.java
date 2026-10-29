package com.crystalgraphics.vfx.particle.gpu.sim;

import com.crystalgraphics.render.world.CgWorldEntityBoxes;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgDispatch;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.vfx.field.CgFieldWorld;
import com.crystalgraphics.vfx.fluid.CgVfxFluid;
import com.crystalgraphics.vfx.fluid.CgVfxFluidAtlas;
import com.crystalgraphics.vfx.grid.CgVfxGrid3D;
import com.crystalgraphics.vfx.grid.CgVfxGridAtlas;
import com.crystalgraphics.vfx.particle.gpu.CgVfxDataInterface;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWords;
import com.crystalgraphics.render.world.CgWorldVoxelWindow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The engine's data interfaces, and the registry a mod's join: what a particle kind may read besides its particle. A
 * shape takes them in registration order, whatever order its kinds name them in.
 *
 * <pre>{@code
 * public CgVfxDataInterface[] dataInterfaces() { return new CgVfxDataInterface[]{CgVfxDataInterfaces.WORLD}; }
 * // void fx_blocks(inout FxParticle p, FxStep s, vec4 m, FxWorld world) { if (fx_world_solid(world, p.position)) ... }
 *
 * CgVfxDataInterfaces.register(new Magnet());   // a mod's own, once, before a shape takes it
 * }</pre>
 *
 * <ul>
 *   <li>Register before the first shape names it: a shape taking an unregistered interface throws, naming it.</li>
 *   <li>An id is registered once; a second interface under it throws.</li>
 * </ul>
 */
public final class CgVfxDataInterfaces {

    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Map<String, CgVfxDataInterface> BY_ID = new LinkedHashMap<>();
    private static final Map<CgVfxDataInterface, Integer> ORDER = new LinkedHashMap<>();

    /** The vec4s of the instance row a gas and a grid each keep: the instance's offset and slot, then its cell. */
    public static final int GAS_LANES = 2, GRID_LANES = 2;

    /** The scene's depth as {@code fx_depth_at.glsl} reads it; the pool binds them. */
    private static final String DEPTH_PROPERTIES = """
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

    private static final String SIM = "crystalgraphics:shaders/lib/vfx/sim/";

    /**
     * The voxel window, as an {@code FxWorld} that {@code fx_world_at.glsl}'s readers take: solid octants, fluid and
     * light at a point relative to the instance's origin. With no level they answer "nothing there".
     */
    public static final CgVfxDataInterface WORLD = register(new Engine("world", "FxWorld", "step_world(inst)") {
        @Override public List<String> libraries() { return List.of(SIM + "fx_world_at.glsl"); }
        @Override public String properties() { return CgWorldVoxelWindow.PROPERTIES; }
        @Override public String functions(int lanesAt) {
            return """

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
                    """;
        }
        @Override public void bind(CgDispatch d, CgVfxParticlePool pool) { CgWorldVoxelWindow.get().use().bind(d); }
    });

    /**
     * {@link #WORLD}, with the window's distance field kept current while the shape plays: {@code fx_world_sdf}, the
     * distance to the nearest solid octant and its gradient, Niagara's and Unity's distance field collision.
     */
    public static final CgVfxDataInterface WORLD_DISTANCE = register(new Engine("world_distance", "FxWorld", "step_world(inst)") {
        @Override public List<CgVfxDataInterface> requires() { return List.of(WORLD); }
        @Override public void bind(CgDispatch d, CgVfxParticlePool pool) { CgWorldVoxelWindow.get().useDistance(); }
    });

    /**
     * The ground's height under the particle, relative to its instance's origin, in blocks; NaN where there is none: the
     * voxel window's floor, or the instance's fixed height with no level. A shape taking it keeps each particle's
     * previous position, which the Look stage tests a stair's step against.
     */
    public static final CgVfxDataInterface FLOOR_Y = register(new Engine("floor_y", "float", "step_floor(p, inst)") {
        @Override public List<CgVfxDataInterface> requires() { return List.of(WORLD); }
        @Override public void bind(CgDispatch d, CgVfxParticlePool pool) { }
    });

    /**
     * The scene's depth as the camera saw it this frame, as an {@code FxDepth} that {@code fx_depth_at.glsl}'s readers
     * take: Niagara's and Unity's depth buffer collision. Blind off screen and behind the first surface; the voxel window
     * is the primary collider, this one for what it does not hold (entities, a mod's meshes). Read in
     * {@code WORLD_OPAQUE}, after the opaque pass and its pyramid ({@link CgVfxParticlePool#depth}).
     */
    public static final CgVfxDataInterface DEPTH = register(new Engine("depth", "FxDepth", "step_depth(inst)") {
        @Override public List<String> libraries() { return List.of(SIM + "fx_depth_at.glsl"); }
        @Override public String properties() { return DEPTH_PROPERTIES; }
        @Override public String functions(int lanesAt) {
            return """

                    // The scene's depth as a kind taking DEPTH reads it: the camera from this instance's origin.
                    FxDepth step_depth(int inst) {
                        FxDepth d;
                        d.eye = vec3(ivec3(_DepthEyeX, _DepthEyeY, _DepthEyeZ) - ivec3(INSTANCES(inst + 3).xyz))
                                + _DepthEyeFrac.xyz - step_instance(inst + 4).xyz;
                        d.live = _DepthSize.x > 0.0;
                        return d;
                    }
                    """;
        }
        @Override public void beforeFrame(CgStageFrame frame) { CgVfxParticlePool.depth(frame.host().view(), frame.depthPyramid()); }
        @Override public void bind(CgDispatch d, CgVfxParticlePool pool) { CgVfxParticlePool.bindDepth(d); }
    });

    /**
     * The entities round the camera this frame, as boxes in an {@code FxEntities} that {@code fx_entities_at.glsl}'s
     * readers take ({@code fx_entities_enter}): mobs and players a particle can strike from any side, on screen or not.
     * At most {@code CgWorldEntityBoxes.MAX} of them, the living nearest the camera.
     */
    public static final CgVfxDataInterface ENTITIES = register(new Engine("entities", "FxEntities", "step_entities(inst)") {
        @Override public List<String> libraries() { return List.of(SIM + "fx_entities_at.glsl"); }
        @Override public String properties() { return CgWorldEntityBoxes.PROPERTIES; }
        @Override public String buffers() { return "    ENTITIES      (\"Entity boxes, two rows each\", uvec4,   readonly)\n"; }
        @Override public String functions(int lanesAt) {
            return """

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
                    """;
        }
        @Override public void beforeFrame(CgStageFrame frame) {
            CgHostView view = frame.host().view();
            CgVfxParticlePool.entities(view.x(), view.y(), view.z(), frame.host().partialTick());
        }
        @Override public void upload(CgRecording recording) { CgWorldEntityBoxes.upload(recording); }
        @Override public void bind(CgDispatch d, CgVfxParticlePool pool) { CgWorldEntityBoxes.bind(d); }
        @Override public void forget() { CgWorldEntityBoxes.forget(); }
    });

    /**
     * The fields placed in the world this frame ({@code vfx.field.CgFieldWorld}), as an {@code FxFields} that
     * {@code fx_field.glsl}'s {@code fx_field_apply} takes: every group's nearest instances, flattened once a frame.
     * Its lane is the instance's origin from the fields' anchor block, written from doubles.
     */
    public static final CgVfxDataInterface FIELDS = register(new Engine("fields", "FxFields", "step_fields(inst)") {
        @Override public boolean beforeSolver() { return true; }
        @Override public List<String> libraries() { return List.of(SIM + "fx_field.glsl"); }
        @Override public String properties() { return CgFieldWorld.PROPERTIES; }
        @Override public String buffers() { return "    FIELDS        (\"Field instances and their nodes\", uvec4,   readonly)\n"; }
        @Override public int instanceLanes() { return 1; }
        @Override public String functions(int lanesAt) {
            return "\n// The fields as a kind taking FIELDS reads them: this instance's origin from their anchor.\n"
                    + "FxFields step_fields(int inst) {\n"
                    + "    FxFields f;\n"
                    + "    f.origin = step_instance(inst + " + lanesAt + ").xyz;\n"
                    + "    return f;\n"
                    + "}\n\n"
                    + "uvec4 fx_field_word(int i) {\n"
                    + "    return FIELDS(i);\n"
                    + "}\n";
        }
        @Override public void writeInstance(CgVfxInstanceView view, double ox, double oy, double oz, CgVfxWords words) {
            words.vec4((float) (ox - CgFieldWorld.anchor(0)), (float) (oy - CgFieldWorld.anchor(1)), (float) (oz - CgFieldWorld.anchor(2)), 0f);
        }
        @Override public void beforeFrame(CgStageFrame frame) {
            CgHostView view = frame.host().view();
            CgFieldWorld.around(view.x(), view.y(), view.z());
        }
        @Override public void upload(CgRecording recording) { CgFieldWorld.upload(recording); }
        @Override public void bind(CgDispatch d, CgVfxParticlePool pool) { CgFieldWorld.bind(d); }
        @Override public void forget() { CgFieldWorld.forget(); }
    });

    /**
     * The particles near this one from the last step, as an {@code FxNeighbours} that {@code fx_neighbours_at.glsl}
     * walks: every particle of its slot in the 27 cells round it, each cell as wide as the widest
     * {@code neighbourRadius} of the pool's kinds. A force from its neighbours (boids, a swarm). Green's sort-based grid,
     * built after every step on every tier; a newborn is not in it yet.
     */
    public static final CgVfxDataInterface NEIGHBOURS = register(new Engine("neighbours", "FxNeighbours",
            "step_neighbours(CG_ELEMENT < live ? CG_ELEMENT : -1, p.slot)") {
        @Override public boolean beforeSolver() { return true; }
        @Override public List<String> libraries() { return List.of(SIM + "fx_neighbours_at.glsl"); }
        @Override public String properties() {
            return """
                        _NeighbourCell  ("The neighbour grid's cell width, blocks", float) = 1.0
                        _NeighbourCells ("Its hashed cells; 0 before it is built", int)   = 0
                    """;
        }
        @Override public String buffers() {
            return "    SORTED        (\"Last step's particles by hashed cell\", uint, readonly)\n"
                    + "    CELL_RUNS     (\"Each hashed cell's run in SORTED, first then end\", uint, readonly)\n";
        }
        @Override public String functions(int lanesAt) {
            return """

                    uvec2 step_cell_run(int h) {
                        return uvec2(CELL_RUNS(h * 2), CELL_RUNS(h * 2 + 1));
                    }

                    FxNeighbour step_neighbour(uint k) {
                        int i = int(SORTED(int(k)));
                        FxParticle q = step_unpack(IN(i));
                        FxNeighbour n;
                        n.position = q.position;
                        n.velocity = q.velocity;
                        n.slot = q.slot;
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
                    """;
        }
        @Override public void prepare() { CgVfxParticlePool.prepareGrid(); }
        @Override public void reserve(CgRecording recording, CgVfxParticlePool pool) { pool.reserveGrid(recording); }
        @Override public void afterStep(CgComputePass pass, CgVfxParticlePool pool) { pool.buildGrid(pass); }
        @Override public void bind(CgDispatch d, CgVfxParticlePool pool) { pool.bindGrid(d); }
    });

    /**
     * The gas its instance is carried by ({@code CgVfxEmitterInstance.inGas}), as an {@code FxGas} that
     * {@code fx_gas_at.glsl}'s {@code fx_gas_flow} reads: its velocity at a point from the instance's origin, as its last
     * step left it, and 1 inside its box (0 outside it, and with no gas). A force from the flow.
     */
    public static final CgVfxDataInterface GAS = register(new Engine("gas", "FxGas", "step_gas(inst)") {
        @Override public boolean beforeSolver() { return true; }
        @Override public List<String> libraries() { return List.of(SIM + "fx_gas_at.glsl"); }
        @Override public String properties() { return CgVfxFluidAtlas.FLOW_PROPERTIES; }
        @Override public int instanceLanes() { return GAS_LANES; }
        @Override public String functions(int lanesAt) {
            return "\n// The gas as a kind taking GAS reads it: this instance's origin from the gas's grid's low corner.\n"
                    + "FxGas step_gas(int inst) {\n"
                    + "    vec4 a = step_instance(inst + " + lanesAt + "), b = step_instance(inst + " + (lanesAt + 1) + ");\n"
                    + """
                        FxGas g;
                        g.offset = a.xyz;
                        g.gas = int(a.w + 0.5);
                        g.cell = b.x;
                        g.live = a.w >= 0.0 && _GasLive != 0;
                        return g;
                    }
                    """;
        }
        @Override public void writeInstance(CgVfxInstanceView view, double ox, double oy, double oz, CgVfxWords words) {
            CgVfxFluid gas = view.gas();
            if (gas == null || gas.index() < 0 || CgVfxFluidAtlas.get().flow() == null) {
                words.vec4(0f, 0f, 0f, -1f).vec4(1f, 0f, 0f, 0f);
            } else {
                // The instance's origin from its fluid's grid's low corner, in blocks.
                float cell = gas.cellSize(), grid = CgVfxFluidAtlas.SPAN * 0.5f * cell;
                words.vec4((float) (ox - gas.x()) + grid, (float) (oy - gas.y()) + grid, (float) (oz - gas.z()) + grid,
                        gas.index()).vec4(cell, 0f, 0f, 0f);
            }
        }
        @Override public void bind(CgDispatch d, CgVfxParticlePool pool) { CgVfxFluidAtlas.get().bindFlow(d); }
    });

    /**
     * {@link #GAS}, which the kind also pours into: {@code step_feed(gas, p.position, p.velocity, density, heat, couple,
     * s.dt)} deposits a second's density and heat where a particle is, and couples the gas's velocity there to its own at
     * {@code couple} a second, which the gas takes at its next step ({@code CgVfxModule.Feed}). Lands only where the gas
     * holds tiles; the engine asks for tiles round an instance feeding it.
     */
    public static final CgVfxDataInterface GAS_FEED = register(new Engine("gas_feed", "FxGas", "step_gas(inst)") {
        @Override public boolean beforeSolver() { return true; }
        @Override public List<CgVfxDataInterface> requires() { return List.of(GAS); }
        @Override public String declarations() {
            return "void step_feed(FxGas g, vec3 p, vec3 velocity, float density, float heat, float couple, float dt);\n";
        }
        @Override public String structs() { return CgVfxFluidAtlas.DEPOSIT_GLSL + "\n\n"; }
        @Override public String buffers() { return "    GAS_DEPOSITS  (\"What particles pour into the gas\", FxDeposit, append)\n"; }
        @Override public String functions(int lanesAt) {
            return """

                    // What a kind taking GAS_FEED pours: density and heat a second into the gas at p (from the instance's
                    // origin), for this step's dt, and its velocity coupled at couple a second (Flow's point emitter), as
                    // a deposit its next step takes.
                    void step_feed(FxGas g, vec3 p, vec3 velocity, float density, float heat, float couple, float dt) {
                        if (!g.live) return;
                        GAS_DEPOSITS_APPEND(FxDeposit(vec4((p + g.offset) / g.cell, float(g.gas)),
                                vec4(density * dt, heat * dt, couple, 0.0), vec4(velocity, 0.0)));
                    }
                    """;
        }
        @Override public void bind(CgDispatch d, CgVfxParticlePool pool) { CgVfxFluidAtlas.get().bindDeposits(d); }
    });

    /**
     * The grid its instance is in ({@code CgVfxEmitterInstance.inGrid}), as an {@code FxGrid} that
     * {@code sim/grid/fx_grid_at.glsl} reads: {@code fx_grid_value(grid, p)}, its four channels at a point from the
     * instance's origin as the last frame left them, and {@code fx_grid_gradient_at(grid, p, mask)}, a channel's slope a
     * block. Nothing outside it, and with no grid. A force from the grid.
     */
    public static final CgVfxDataInterface GRID = register(new Engine("grid", "FxGrid", "step_grid(inst)") {
        @Override public boolean beforeSolver() { return true; }
        @Override public List<String> libraries() { return List.of(SIM + "grid/fx_grid_at.glsl"); }
        @Override public String properties() { return CgVfxGridAtlas.VIEW_PROPERTIES; }
        @Override public int instanceLanes() { return GRID_LANES; }
        @Override public String functions(int lanesAt) {
            return "\n// The grid as a kind taking GRID reads it: this instance's origin from the grid's low corner.\n"
                    + "FxGrid step_grid(int inst) {\n"
                    + "    vec4 a = step_instance(inst + " + lanesAt + "), b = step_instance(inst + " + (lanesAt + 1) + ");\n"
                    + """
                        FxGrid g;
                        g.offset = a.xyz;
                        g.brick = int(a.w + 0.5);
                        g.cell = b.x;
                        g.live = a.w >= 0.0 && _GridLive != 0;
                        return g;
                    }
                    """;
        }
        @Override public void writeInstance(CgVfxInstanceView view, double ox, double oy, double oz, CgVfxWords words) {
            CgVfxGrid3D grid = view.grid();
            if (grid == null || grid.index() < 0 || grid.cellSize() <= 0f || CgVfxGridAtlas.get().view() == null) {
                words.vec4(0f, 0f, 0f, -1f).vec4(1f, 0f, 0f, 0f);
            } else {
                // The instance's origin from its grid's low corner, in blocks: its middle less half its span.
                float half = CgVfxGridAtlas.SPAN * 0.5f * grid.cellSize();
                words.vec4((float) (ox - grid.x()) + half, (float) (oy - grid.y()) + half, (float) (oz - grid.z()) + half,
                        grid.index()).vec4(grid.cellSize(), 0f, 0f, 0f);
            }
        }
        @Override public void bind(CgDispatch d, CgVfxParticlePool pool) { CgVfxGridAtlas.get().bindView(d); }
    });

    /**
     * {@link #GRID}, which the kind also splats into: {@code step_splat(grid, p.position, amounts, s.dt)} adds a second's
     * four amounts to the cell where a particle is, which the grid takes this frame ({@code CgVfxModule.Splat}).
     */
    public static final CgVfxDataInterface GRID_SPLAT = register(new Engine("grid_splat", "FxGrid", "step_grid(inst)") {
        @Override public boolean beforeSolver() { return true; }
        @Override public List<CgVfxDataInterface> requires() { return List.of(GRID); }
        @Override public String declarations() { return "void step_splat(FxGrid g, vec3 p, vec4 amounts, float dt);\n"; }
        @Override public String structs() { return CgVfxGridAtlas.SPLAT_GLSL + "\n\n"; }
        @Override public String buffers() { return "    GRID_SPLATS   (\"What particles splat into the grid\", FxSplat, append)\n"; }
        @Override public String functions(int lanesAt) {
            return """

                    // What a kind taking GRID_SPLAT adds: amounts a second into the grid's cell at p (from the instance's
                    // origin), for this step's dt, as a splat the grid takes this frame.
                    void step_splat(FxGrid g, vec3 p, vec4 amounts, float dt) {
                        if (!g.live) return;
                        GRID_SPLATS_APPEND(FxSplat(vec4((p + g.offset) / g.cell, float(g.brick)), amounts * dt));
                    }
                    """;
        }
        @Override public void bind(CgDispatch d, CgVfxParticlePool pool) { CgVfxGridAtlas.get().bindSplats(d); }
    });

    /**
     * The meshes emitters spawn on ({@code CgVfxSpawnMeshes}): a shape spawning on a mesh takes it itself, and its spawn
     * code places each particle through {@code step_mesh}. Niagara's static mesh location; no kind takes it.
     */
    public static final CgVfxDataInterface SPAWN_MESHES = register(new Engine("spawn_meshes", "void", "") {
        @Override public String buffers() { return "    MESHES        (\"Spawn meshes' triangles and vertices\", uvec4, readonly)\n"; }
        @Override public String functions(int lanesAt) {
            return """

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
                    """;
        }
        @Override public void upload(CgRecording recording) { CgVfxSpawnMeshes.upload(recording); }
        @Override public void bind(CgDispatch d, CgVfxParticlePool pool) { CgVfxSpawnMeshes.bind(d); }
        @Override public void forget() { CgVfxSpawnMeshes.forget(); }
    });

    /**
     * What a beam kind reads of its instance ({@code CgVfxModule.BeamSource} and the rest), as an {@code FxBeamFrame}
     * ({@code fx_beam.glsl}): its orientation, its origin in the world and its loop's seconds. A shape taking it with a
     * {@code beam_noise} keeps its beams' noise points ({@code CgVfxBeams}), written after each step.
     */
    public static final CgVfxDataInterface BEAM = register(new Engine("beam", "FxBeamFrame", "step_beam_frame(inst)") {
        @Override public List<String> libraries() { return List.of("crystalgraphics:shaders/lib/vfx/fx_beam.glsl"); }
        @Override public String functions(int lanesAt) {
            return """

                    FxBeamFrame step_beam_frame(int inst) {
                        FxBeamFrame b;
                        b.turn = step_instance(inst + 7);
                        b.origin = vec3(ivec3(INSTANCES(inst + 3).xyz)) + step_instance(inst + 4).xyz;
                        b.loop = step_instance(inst + 5).w;
                        return b;
                    }
                    """;
        }
        @Override public void prepare() { CgVfxBeams.prepare(); }
        @Override public void reserve(CgRecording recording, CgVfxParticlePool pool) { pool.reserveBeams(recording); }
        @Override public void afterStep(CgComputePass pass, CgVfxParticlePool pool) { pool.stepBeams(pass); }
        @Override public void bind(CgDispatch d, CgVfxParticlePool pool) { }
    });

    private CgVfxDataInterfaces() {
    }

    /** Registers {@code di} under its id, and answers it: a mod's own, once, before a shape takes it. */
    public static synchronized <T extends CgVfxDataInterface> T register(T di) {
        String id = di.id();
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("data interface id '" + id + "' is not namespace:path");
        CgVfxDataInterface known = BY_ID.putIfAbsent(id, di);
        if (known != null && known != di) throw new IllegalArgumentException("data interface " + id + " is registered already");
        ORDER.putIfAbsent(di, ORDER.size());
        return di;
    }

    /** The interface registered under {@code id}, or null. */
    public static synchronized CgVfxDataInterface get(String id) {
        return BY_ID.get(id);
    }

    /** Every interface registered, in order. */
    public static synchronized List<CgVfxDataInterface> all() {
        return Collections.unmodifiableList(new ArrayList<>(BY_ID.values()));
    }

    /** {@code di}'s place in registration order, the order a shape takes them in; throws for one never registered. */
    static synchronized int order(CgVfxDataInterface di) {
        Integer at = ORDER.get(di);
        if (at == null) throw new IllegalArgumentException("data interface " + di.id() + " is not registered (CgVfxDataInterfaces.register)");
        return at;
    }

    /** An engine interface: its id under {@code crystalgraphics}. */
    private abstract static class Engine implements CgVfxDataInterface {
        private final String id, type, call;

        Engine(String path, String type, String call) {
            this.id = "crystalgraphics:" + path;
            this.type = type;
            this.call = call;
        }

        @Override public String id() { return id; }
        @Override public String type() { return type; }
        @Override public String call() { return call; }
        @Override public String toString() { return id; }
    }
}
