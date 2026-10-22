package com.crystalgraphics.vfx.particle.gpu;

import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxLifeBind;
import com.crystalgraphics.vfx.particle.CgVfxLifeRoll;
import com.crystalgraphics.vfx.particle.CgVfxMeshRotation;
import com.crystalgraphics.vfx.particle.CgVfxSockets;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxShape;
import com.crystalgraphics.vfx.particle.spawn.CgVfxSpawnFrom;
import com.crystalgraphics.vfx.sequence.CgVfxNodeClip;

import javax.annotation.Nullable;
import java.util.List;

import com.crystalgraphics.vfx.particle.form.CgVfxFormCompiler;
import com.crystalgraphics.vfx.particle.form.CgVfxModuleForm;
import com.crystalgraphics.vfx.particle.form.CgVfxSpawnForm;

/**
 * An emitter definition as a GPU pool reads it: its module stack, its renderer and its spawn numbers.
 * {@code CgVfxEmitter} implements it; its {@code name()}, {@code renderer()} and {@code modules()} already answer three
 * of the four, once {@code CgVfxModule} extends {@link CgVfxGpuModule}.
 *
 * <pre>{@code
 * public void writeSpawn(CgVfxWords out) {
 *     out.vec4(speedMin, speedMax, lifeMin, lifeMax)
 *        .vec4(sizeMin, sizeMax, 0f, heat)
 *        .vec4(spinMin, spinMax, inherit, 0f)
 *        .vec4(0f, 0f, 0f, 0f);
 *     spawnShape.write(out);                          // six more: where it is born, which way it flies
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Two definitions with the same module kinds in the same order and the same renderer share a pool and its
 *       kernels ({@link CgVfxShape}); their numbers are rows of its parameter table.</li>
 *   <li>A definition is held by identity: a look that changes a number holds another definition, so another row.</li>
 * </ul>
 */
public interface CgVfxGpuEmitter {

    /** The vec4s {@link #writeSpawn} writes. */
    int SPAWN_VECTORS = 10;

    /**
     * The launch's rolls, each an input's, by index into {@link #rollKeys}: the speed, the cone's two angles, the
     * lifetime, the sprite size, the starting rotation, the particle's own seed, the spin's rate and sense, the shape's
     * draws, and the density share's thinning.
     */
    int KEY_SPEED = 0, KEY_CONE = 1, KEY_LIFE = 2, KEY_SIZE = 3, KEY_ROTATION = 4, KEY_SEED = 5, KEY_SPIN = 6,
            KEY_SHAPE = 7, KEY_THIN = 8, ROLL_KEYS = 9;

    /**
     * Roll {@code k}'s element and input, by the writer's names for an emitter's elements ({@code init},
     * {@code velocity}, {@code shape}, {@code spin_rate}); {@code scalability} is the engine's own.
     */
    static String[] rollOf(int k) {
        return switch (k) {
            case KEY_SPEED -> new String[]{"velocity", "speed"};
            case KEY_CONE -> new String[]{"velocity", "cone_angle"};
            case KEY_LIFE -> new String[]{"init", "lifetime"};
            case KEY_SIZE -> new String[]{"init", "sprite_size"};
            case KEY_ROTATION -> new String[]{"init", "sprite_rotation"};
            case KEY_SEED -> new String[]{"init", "seed"};
            case KEY_SPIN -> new String[]{"spin_rate", "rate"};
            case KEY_SHAPE -> new String[]{"shape", "shape"};
            case KEY_THIN -> new String[]{"scalability", "spawn_count_scale"};
            default -> throw new IllegalArgumentException("no roll " + k);
        };
    }

    /** Roll {@code k}'s key with its elements under the writer's names: an emitter made in code. */
    static int defaultRollKey(int k) {
        String[] r = rollOf(k);
        return CgVfxFormCompiler.key(r[0], r[1]);
    }

    /**
     * The keys of the launch's {@link #ROLL_KEYS} rolls, each {@code CgVfxFormCompiler.key(element id, input)}: an
     * emitter file's element ids, or the writer's names. What the parameter row's key area holds.
     *
     * <pre>{@code
     * public int[] rollKeys() { return keys; }   // made once: keys[k] = CgVfxFormCompiler.key(idOf(element), input)
     * }</pre>
     */
    default int[] rollKeys() {
        int[] keys = new int[ROLL_KEYS];
        for (int k = 0; k < ROLL_KEYS; k++) keys[k] = defaultRollKey(k);
        return keys;
    }

    /** For messages. */
    String name();

    /** How its pool lays out what it draws. */
    CgVfxDrawLayout drawLayout();

    /** Its module stack, in the order each step runs it. */
    List<? extends CgVfxGpuModule> modules();

    /**
     * Its modules' numbers: every module's vec4s end to end in stack order, each laid out from its kind's declaration.
     * Made once with the definition, and copied into a parameter row whenever one opens. The array itself: never written.
     *
     * <pre>{@code
     * private final int[] words = CgVfxModuleTypes.words(modules);   // in the constructor
     * public int[] moduleWords() { return words; }
     * }</pre>
     */
    int[] moduleWords();

    /**
     * Writes its spawn numbers, {@link #SPAWN_VECTORS} vec4s in this order: (speed min, speed max, life min, life max),
     * (size min, size max, 0, heat), (spin min, spin max, the share of its source's velocity, 0), (0, 0, 0, 0); then
     * where it is born: (the cone's inner and outer half angles, 0, direction mode), (direction xyz, 0), (shape kind,
     * placement | 4 for the upper half, arc, ordered), (the shape's numbers), its rotation from the sampling frame (axis
     * +Y), (its offset from the source, 0). {@code CgVfxSpawnShape} has the codes; a mesh's numbers name its section of
     * {@code CgVfxSpawnMeshes}. A value given in a {@link #spawnForms() form} keeps its first end in its word.
     */
    void writeSpawn(CgVfxWords out);

    /** Whether it spawns on a mesh: its shape's Step kernel then reads {@code CgVfxSpawnMeshes}. */
    default boolean spawnsOnMesh() {
        return false;
    }

    /**
     * How many of each particle's last positions its pool keeps, one a step, for a {@code trails()} layer to draw: 0
     * keeps none.
     */
    default int trailPoints() {
        return 0;
    }

    /**
     * The density each particle holds at its middle in the four channels of its instance's grid, drawn there each frame
     * where the particle is drawn; null for none. The array itself, read every frame: never written.
     */
    default float[] rasterized() {
        return null;
    }

    /**
     * What its particles do that it reacts to, in order: an event's index here is what {@link CgVfxEvent#childKey} and
     * {@link CgVfxEventListener} name it by. The triggers' kinds join its {@link CgVfxShape}; at most
     * {@link CgVfxEvent#MAX_EVENTS}.
     *
     * <pre>{@code
     * public List<CgVfxEvent> events() {
     *     return List.of(CgVfxEvent.onLanding().spawn(DUST, 3).inherit(0.2f), CgVfxEvent.onDeath().readback(16));
     * }
     * }</pre>
     */
    default List<CgVfxEvent> events() {
        return List.of();
    }

    /** Another emitter of its system whose live particles its own are born on ({@link CgVfxSpawnFrom}), or null. */
    @Nullable
    default CgVfxSpawnFrom spawnFrom() {
        return null;
    }

    /** Whether another emitter of its system is born on its particles: its steps then publish them, a slot at a time. */
    default boolean publishes() {
        return false;
    }

    /**
     * Its inputs bound to user parameters, read from instance lanes each step: which words are bound joins its
     * {@link CgVfxShape}. At most {@link CgVfxShape#MAX_USER_BINDINGS}.
     *
     * <pre>{@code
     * public List<CgVfxUserBinding> userBindings() { return List.of(CgVfxUserBinding.module(0, "gravity", 1, GRAVITY, -9.8f, 0f)); }
     * }</pre>
     */
    default List<CgVfxUserBinding> userBindings() {
        return List.of();
    }

    /**
     * Its module inputs given in a form other than a constant ({@code core.md} § Values): each built per lane in the
     * Step kernel from the parameter row's form area. Which input takes which form class is part of the shape.
     *
     * <pre>{@code
     * public List<CgVfxModuleForm> moduleForms() { return List.of(new CgVfxModuleForm(1, "drag", value, compiled)); }
     * }</pre>
     */
    default List<CgVfxModuleForm> moduleForms() {
        return List.of();
    }

    /**
     * Its spawn values given in a form rather than the spawn row's range: a pick, a curve by random, id or the loop's
     * seconds, built per spawn by the Step kernel's launch from the form area. Which value takes which form class is
     * part of the shape.
     *
     * <pre>{@code
     * public List<CgVfxSpawnForm> spawnForms() { return List.of(CgVfxSpawnForm.of(CgVfxSpawnInput.SPRITE_SIZE, "sprite_size", value)); }
     * }</pre>
     */
    default List<CgVfxSpawnForm> spawnForms() {
        return List.of();
    }

    /** The skeleton points it spawns at ({@link CgVfxSockets}); null for none. Their count is part of the shape. */
    @Nullable
    default CgVfxSockets sockets() {
        return null;
    }

    /** Samples each of a curve row's two curves holds. */
    int CURVE_TEXELS = 256;

    /** The vec4s a curve row holds after its two curves: the two start colours, then the two start scales. */
    int CURVE_STARTS = 4;

    /**
     * Writes its curve row from {@code out[at]}, vec4s: {@code texels} shape samples, {@code texels} tint samples, then
     * {@link #CURVE_STARTS} start values. Sample i is at progress {@code i / (texels - 1)} of its {@link #curveDomain()}.
     * The pool calls it when its parameter row opens.
     *
     * <ul>
     *   <li>a shape sample is each axis's scale, then the size multiplier;</li>
     *   <li>a tint sample is the colour multiplier's red, green and blue (times its intensity), then the opacity;</li>
     *   <li>the starts are the two colours a particle's start colour is mixed between (rgb times intensity, alpha), then
     *   the two scales its start axis scales are mixed between (xyz, 0).</li>
     * </ul>
     *
     * <pre>{@code
     * float[] row = new float[4 * (2 * CgVfxGpuEmitter.CURVE_TEXELS + CgVfxGpuEmitter.CURVE_STARTS)];
     * EMBERS.writeCurves(row, 0, CgVfxGpuEmitter.CURVE_TEXELS);
     * float sizeAtBirth = row[3], opacityAtBirth = row[4 * CgVfxGpuEmitter.CURVE_TEXELS + 3];
     * }</pre>
     */
    void writeCurves(float[] out, int at, int texels);

    /**
     * Channel {@code channel} (0 to 3) of drawn slot {@code slot}: what its particles carry for their draws alone
     * ({@link CgVfxDrawnChannel}); null where it sets none.
     */
    @Nullable
    default CgVfxDrawnChannel drawn(int slot, int channel) {
        return null;
    }

    /**
     * Channel {@code channel} of its light ({@link CgVfxDrawnChannel#LIGHT_RED} to {@code LIGHT_BRIGHTNESS}), Cascade's
     * {@code ColorScaleOverLife} and {@code BrightnessOverLife}; null for every channel where its particles give no light.
     */
    @Nullable
    default CgVfxDrawnChannel light(int channel) {
        return null;
    }

    /**
     * The drawn slots {@code emitter} sets a channel of, a bit each, {@link CgVfxDrawnChannel#ORIENTATION} where its
     * meshes turn as a {@link CgVfxMeshRotation} says, and {@link CgVfxDrawnChannel#LIGHT} where its particles give
     * light: what its pool writes after each drawn part.
     */
    static int drawnMask(CgVfxGpuEmitter emitter) {
        int mask = 0;
        for (int s = 0; s < CgVfxDrawnChannel.SLOTS; s++) {
            for (int c = 0; c < 4; c++) if (emitter.drawn(s, c) != null) mask |= 1 << s;
        }
        if (emitter.meshRotation() != null) mask |= 1 << CgVfxDrawnChannel.ORIENTATION;
        if (emitter.light(CgVfxDrawnChannel.LIGHT_BRIGHTNESS) != null) mask |= 1 << CgVfxDrawnChannel.LIGHT;
        return mask;
    }

    /** Its look over life bound to user parameters ({@link CgVfxLifeBind}). */
    default List<CgVfxLifeBind> lifeBinds() {
        return List.of();
    }

    /** Its look over life's ranges rolled each step ({@link CgVfxLifeRoll}). */
    default List<CgVfxLifeRoll> lifeRolls() {
        return List.of();
    }

    /** How its mesh particles turn ({@link CgVfxMeshRotation}); null for at random by their seed. */
    @Nullable
    default CgVfxMeshRotation meshRotation() {
        return null;
    }

    /**
     * Cascade's size by speed (ParticleModules_Size.cpp:419-430): each of x and y times
     * {@code clamp(speed * scale, 1, max)}, as {@code {scaleX, scaleY, maxX, maxY}}, speed in blocks a second. The
     * default, {@code {0, 0, 1, 1}}, scales nothing.
     */
    default float[] sizeBySpeed() {
        return new float[] {0f, 0f, 1f, 1f};
    }

    /**
     * What its curves are sampled by: over life, or by speed.
     *
     * <pre>{@code
     * public CgVfxCurveDomain curveDomain() { return CgVfxCurveDomain.speed(0.5f, 6f); }   // sparks shrink as they slow
     * }</pre>
     */
    default CgVfxCurveDomain curveDomain() {
        return CgVfxCurveDomain.LIFE;
    }

    /**
     * The clip each particle plays, which its events at a node ({@link CgVfxEvent#at}) read: a {@code SEQUENCE}
     * emitter's; null for none.
     */
    @Nullable
    default CgVfxNodeClip clip() {
        return null;
    }

    /** How fast its particles play their {@link #clip}: 1 plays it at its 30 frames a second of age. */
    default float playRate() {
        return 1f;
    }
}
