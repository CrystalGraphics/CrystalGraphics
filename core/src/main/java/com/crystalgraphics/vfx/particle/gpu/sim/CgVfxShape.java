package com.crystalgraphics.vfx.particle.gpu.sim;

import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuModule;
import com.crystalgraphics.vfx.particle.gpu.CgVfxLane;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWorldInput;

import java.util.List;
import java.util.regex.Pattern;

/**
 * What makes two emitter definitions share a pool and its kernels: their module kinds in stack order, what each kind
 * declares it takes, and the renderer. Never a number, so tuning a definition recompiles nothing. Also the layout of a
 * pool's rows, which follows from it.
 *
 * <pre>{@code
 * CgVfxShape shape = CgVfxShape.of(EMBERS);
 * shape.key();                  // "QUADS|gravity:1|drag:1|buoyancy:1|turbulence:1:IVEC4,VEC4,IVEC4,VEC4|wind:1"
 * shape.paramRowVectors();      // spawn numbers then each module's numbers
 * shape.instanceRowVectors();   // the row's header then each module's lanes
 * }</pre>
 *
 * <ul>
 *   <li>{@link #of} checks every kind against {@link CgVfxGpuModule}'s rules and throws naming the definition and the
 *       kind that breaks one.</li>
 * </ul>
 */
public final class CgVfxShape {

    private static final Pattern KIND = Pattern.compile("[a-z][a-z0-9_]*");

    private final String key;
    private final CgVfxEmitter.Renderer renderer;
    private final String[] kinds;
    private final boolean[] afterSolve;
    private final int[] params, paramAt, lanesAt;
    private final CgVfxLane[][] lanes;
    private final CgVfxWorldInput[][] world;
    private final int paramRow, instanceRow;
    private final boolean readsWorld;

    private CgVfxShape(CgVfxGpuEmitter emitter) {
        List<? extends CgVfxGpuModule> modules = emitter.modules();
        int n = modules.size();
        renderer = emitter.renderer();
        kinds = new String[n];
        afterSolve = new boolean[n];
        params = new int[n];
        paramAt = new int[n];
        lanesAt = new int[n];
        lanes = new CgVfxLane[n][];
        world = new CgVfxWorldInput[n][];
        StringBuilder key = new StringBuilder(renderer.name());
        int param = CgVfxGpuEmitter.SPAWN_VECTORS, lane = CgVfxParticlePool.INSTANCE_HEADER;
        for (int i = 0; i < n; i++) {
            CgVfxGpuModule m = modules.get(i);
            String kind = m.gpuKind();
            if (kind == null || !KIND.matcher(kind).matches()) {
                throw new IllegalArgumentException(emitter.name() + ": module " + m.getClass().getSimpleName() + "'s gpuKind '"
                        + kind + "' is not a lower-case GLSL name");
            }
            kinds[i] = kind;
            afterSolve[i] = m.afterSolve();
            params[i] = m.paramVectors();
            lanes[i] = m.instanceLanes().clone();
            world[i] = m.worldInputs().clone();
            if (params[i] < 0) throw new IllegalArgumentException(emitter.name() + ": fx_" + kind + " declares " + params[i] + " param vectors");
            if (world[i].length > 0 && !afterSolve[i]) {
                throw new IllegalArgumentException(emitter.name() + ": fx_" + kind + " takes world inputs but runs before the solver");
            }
            paramAt[i] = param;
            lanesAt[i] = lane;
            param += params[i];
            lane += lanes[i].length;
            key.append('|').append(kind).append(afterSolve[i] ? ">" : ":").append(params[i]);
            for (int l = 0; l < lanes[i].length; l++) key.append(l == 0 ? ':' : ',').append(lanes[i][l].name());
            for (int w = 0; w < world[i].length; w++) key.append(w == 0 ? '@' : ',').append(world[i][w].name());
        }
        paramRow = param;
        instanceRow = lane;
        boolean reads = false;
        for (CgVfxWorldInput[] inputs : world) reads |= inputs.length > 0;
        readsWorld = reads;
        this.key = key.toString();
    }

    /** {@code emitter}'s shape. */
    public static CgVfxShape of(CgVfxGpuEmitter emitter) {
        return new CgVfxShape(emitter);
    }

    /** One string naming the shape: equal for definitions that share kernels. */
    public String key() {
        return key;
    }

    public CgVfxEmitter.Renderer renderer() {
        return renderer;
    }

    /** How many modules the stack has. */
    public int modules() {
        return kinds.length;
    }

    /** Module {@code i}'s {@code <kind>}. */
    public String kind(int i) {
        return kinds[i];
    }

    public boolean afterSolve(int i) {
        return afterSolve[i];
    }

    /** Module {@code i}'s vec4s of numbers. */
    public int paramVectors(int i) {
        return params[i];
    }

    /** Where module {@code i}'s numbers start in a parameter row, in vec4s. */
    public int paramAt(int i) {
        return paramAt[i];
    }

    /** Module {@code i}'s lanes; a copy. */
    public CgVfxLane[] lanes(int i) {
        return lanes[i].clone();
    }

    /** Where module {@code i}'s lanes start in an instance row, in vec4s. */
    public int lanesAt(int i) {
        return lanesAt[i];
    }

    /** Module {@code i}'s world inputs; a copy. */
    public CgVfxWorldInput[] worldInputs(int i) {
        return world[i].clone();
    }

    /** Whether any module takes a world input: its Step kernel reads the voxel window. */
    public boolean readsWorld() {
        return readsWorld;
    }

    int laneCount(int i) {
        return lanes[i].length;
    }

    /** A parameter row's vec4s: the spawn numbers, then each module's in stack order. */
    public int paramRowVectors() {
        return paramRow;
    }

    /** An instance row's vec4s: {@link CgVfxParticlePool#INSTANCE_HEADER}, then each module's lanes in stack order. */
    public int instanceRowVectors() {
        return instanceRow;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CgVfxShape && ((CgVfxShape) o).key.equals(key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return key;
    }
}
