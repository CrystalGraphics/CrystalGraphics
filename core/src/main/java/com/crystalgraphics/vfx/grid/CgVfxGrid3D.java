package com.crystalgraphics.vfx.grid;

import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxElement;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.look.CgVfxKit;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.look.CgVfxSlot;

/**
 * A grid of cells an effect owns, riding with it, four channels a cell (x, y, z, w), on the GPU: what particles write
 * into and read back, and what a layer draws as a volume. Niagara's Grid3D: particles splat amounts into it
 * ({@code CgVfxModule.Splat}), each channel fades and spreads to its neighbours every frame, and particles steer by it
 * ({@code CgVfxModule.Follow}, {@code Gradient}). Its layers draw a channel as lit smoke ({@link #SMOKE}) or as glowing
 * light ({@link #GLOW}).
 *
 * <pre>{@code
 * public final class SlimeMould extends CgVfxEffect<SlimeMould> {
 *     private final CgVfxGrid3D trail = new CgVfxGrid3D(this).place(0f, 3f, 0f)
 *             .channel(0, 0.8f, 0.4f).lit(false);           // channel x: fades 0.8 a second, spreads 0.4 blocks² a second
 *     private final CgVfxEmitterInstance agents;
 *
 *     public SlimeMould(CgVfxLook look, double x, double y, double z) {
 *         super(look, x, y, z);
 *         agents = new CgVfxEmitterInstance(look.emitter("agents"), seed).inGrid(trail);
 *         agents.start(0f, 3f, 0f);
 *     }
 *
 *     @Override protected void tick(float dt) {
 *         trail.tick(dt);
 *         tick(agents, dt);
 *     }
 *
 *     @Override protected void submit(CgVfxFrame frame) {
 *         trail.submit(frame);
 *         frame.particles(this, agents);
 *     }
 * }
 *
 * // agents splat into channel x and steer toward where it is strongest: Physarum's trails
 * CgVfxEmitter.builder("agents").capacity(20000).burst(0f, 20000).shape(CgSphere.of(4)).speed(2f, 2f).life(1e6f, 1e6f)
 *         .module(new CgVfxModule.Follow(0, 0.6f, 0.5f, 6f)).module(new CgVfxModule.Splat(1f, 0f, 0f, 0f)).build();
 * CgVfxLook.builder(SCHEMA).with(CgVfxGrid3D.GLOW).set(CgVfxGrid3D.CELLS, 48f).set(CgVfxGrid3D.CELL, 0.2f)...
 * }</pre>
 *
 * <pre>{@code
 * // particles as one body of smoke: each splats density, which fades fast, so the volume is where they are
 * new CgVfxGrid3D(this).place(0f, 4f, 0f).channel(0, 4f, 0.05f);         // and the look takes CgVfxGrid3D.SMOKE
 * }</pre>
 *
 * <ul>
 *   <li>It is {@link #CELLS} cells a side of {@link #CELL} blocks, centred on its place from the effect's origin, and it
 *       moves with the effect. Lower tiers take fewer, wider cells over the same box: three quarters at Medium, half at
 *       Low.</li>
 *   <li>A channel fades by its share a second and spreads to its neighbours (blocks² a second); outside the grid is
 *       empty, so what spreads to its edge is lost. A channel never set keeps all it is given.</li>
 *   <li>Splats land a frame's particles' worth at a time, each in its nearest cell: a spread of a cell or two smooths
 *       them into a body.</li>
 *   <li>Channel x is what {@link #SMOKE} draws and what the grid lights itself by ({@link #EXTINCTION}); {@link #lit}
 *       false skips that light for a grid drawn as glow, or not drawn at all.</li>
 *   <li>Call {@link #tick} every effect tick; one left unticked gives its place back. At most
 *       {@link CgVfxGridAtlas#CAPACITY} grids at once; one waiting draws and takes nothing.</li>
 * </ul>
 */
public final class CgVfxGrid3D extends CgVfxElement {

    public static final CgVfxSchema SCHEMA = CgVfxSchema.of("crystalgraphics:grid");
    /** Cells a side at High and Ultra, at most {@link CgVfxGridAtlas#BRICK}; taken when it takes its place in the atlas. */
    public static final CgVfxParam.Scalar CELLS = SCHEMA.scalar("cells", 32f);
    /** A cell's side in blocks at High and Ultra: the grid spans {@code CELLS * CELL} blocks a side. */
    public static final CgVfxParam.Scalar CELL = SCHEMA.length("cell", 0.25f);
    /** Light lost a block through a unit of channel x: how thick its smoke looks and how deep its shadows. */
    public static final CgVfxParam.Scalar EXTINCTION = SCHEMA.scalar("extinction", 3f);
    /** {@link #SMOKE}'s colour in full light. */
    public static final CgVfxParam.Color SMOKE_COLOR = SCHEMA.color("smoke", 0xFFB8B6B2);
    /** {@link #GLOW}'s colour where a channel is faint, and where it is strong, past white to bloom. */
    public static final CgVfxParam.Color GLOW_COLOR = SCHEMA.color("glow", 0xFF2A6CFF, 1.5f);
    public static final CgVfxParam.Color HOT = SCHEMA.color("hot", 0xFFBFF3FF, 3f);
    /** {@link #GLOW}'s light a unit a block, and the value it reaches {@link #HOT} at. */
    public static final CgVfxParam.Scalar GLOW_GAIN = SCHEMA.scalar("glowGain", 0.5f);
    public static final CgVfxParam.Scalar HOT_AT = SCHEMA.scalar("hotAt", 1f);

    /** Where a grid draws unless given a slot of its own. */
    public static final CgVfxSlot SLOT = CgVfxSlot.grid("crystalgraphics:grid");
    /** Channel x as lit smoke on {@link #SLOT}: the sun through itself and the sky over it. */
    public static final CgVfxKit SMOKE = CgVfxKit.of("crystalgraphics:grid/smoke").params(SCHEMA)
            .layer(CgVfxLayer.of("crystalgraphics:shaders/vfx/grid/smoke.shader").on(SLOT).role(CgVfxLayer.Role.SMOKE)
                    .build())
            .build();
    /** Channel x as light on {@link #SLOT}, from {@link #GLOW_COLOR} where faint to {@link #HOT} where strong, blooming. */
    public static final CgVfxKit GLOW = CgVfxKit.of("crystalgraphics:grid/glow").params(SCHEMA)
            .layer(CgVfxLayer.of("crystalgraphics:shaders/vfx/grid/glow.shader").on(SLOT).bind("_Gain", GLOW_GAIN)
                    .bind("_HotAt", HOT_AT).build())
            .build();

    private final CgVfxSlot slot;
    /** Each channel's share lost a second, and blocks² spread a second. */
    final float[] fade = new float[4], spread = new float[4];
    private boolean lit = true;
    private float placeX, placeY, placeZ;
    /** Its place in the atlas, -1 for none; its cells a side and a cell in blocks there; its middle in the world. */
    int index = -1;
    int cells;
    float cell;
    double worldX, worldY, worldZ;
    /** Seconds ticked that no frame has stepped yet; the atlas frame of its last tick; whether its brick is fresh. */
    float owed;
    long ticked;
    boolean fresh;

    /** A grid drawn on {@link #SLOT}. */
    public CgVfxGrid3D(CgVfxEffect<?> effect) {
        this(effect, SLOT);
    }

    public CgVfxGrid3D(CgVfxEffect<?> effect, CgVfxSlot slot) {
        super(effect);
        if (slot.kind() != CgVfxSlot.Kind.GRID) throw new IllegalArgumentException(slot + " is not a grid slot");
        this.slot = slot;
    }

    /** Its middle, from the effect's origin; it moves with the effect. */
    public CgVfxGrid3D place(float x, float y, float z) {
        placeX = x;
        placeY = y;
        placeZ = z;
        return this;
    }

    /**
     * How channel {@code c} (0 to 3: x, y, z, w) changes each frame: {@code fade}, the share it loses a second, and
     * {@code spread}, how far it diffuses, blocks² a second. Unless set, it keeps what it is given and stays put.
     */
    public CgVfxGrid3D channel(int c, float fade, float spread) {
        if (c < 0 || c > 3) throw new IllegalArgumentException("a grid's channel " + c + ": 0 to 3");
        this.fade[c] = Math.max(0f, fade);
        this.spread[c] = Math.max(0f, spread);
        return this;
    }

    /** Whether it lights channel x's density by the sun and the sky ({@link #SMOKE} reads it): true unless set. */
    public CgVfxGrid3D lit(boolean lit) {
        this.lit = lit;
        return this;
    }

    /** Its tick: from the effect's {@code tick}, every tick. Takes a place in the atlas the first time one is free. */
    public void tick(float dt) {
        CgVfxGridAtlas atlas = CgVfxGridAtlas.get();
        if (index < 0) index = atlas.acquire(this);
        worldX = originX() + placeX;
        worldY = originY() + placeY;
        worldZ = originZ() + placeZ;
        ticked = atlas.frame();
        owed += dt;
    }

    /** Draws it on its slot: from the effect's {@code submit}. */
    public void submit(CgVfxFrame frame) {
        if (index >= 0 && frame.draws(effect, slot)) frame.grid(effect, slot, this);
    }

    /** Gives its place back now: it draws nothing from here, and a later tick takes a place again, empty. */
    public void release() {
        CgVfxGridAtlas.get().release(index);
        index = -1;
    }

    /** Its place among the atlas's grids, its brick: -1 while it has none. */
    public int index() {
        return index;
    }

    public CgVfxSlot slot() {
        return slot;
    }

    /** Cells a side in the atlas now; 0 before its first place. */
    public int cells() {
        return cells;
    }

    /** A cell's side in blocks in the atlas now; 0 before its first place. */
    public float cellSize() {
        return cell;
    }

    /** Its side in blocks: {@link #CELLS} times {@link #CELL}, at every tier. */
    public float extent() {
        return get(CELLS) * get(CELL);
    }

    /** Its middle in the world, as of its last tick. */
    public double x() {
        return worldX;
    }

    public double y() {
        return worldY;
    }

    public double z() {
        return worldZ;
    }

    public float extinction() {
        return get(EXTINCTION);
    }

    float cellsSetting() {
        return get(CELLS);
    }

    boolean lights() {
        return lit;
    }

    CgVfxEffect<?> effect() {
        return effect;
    }

    /** The atlas took its place back: its effect died or stopped ticking. */
    void lost() {
        index = -1;
    }
}
