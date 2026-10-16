package com.crystalgraphics.vfx.grid;

import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxElement;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.look.CgVfxKit;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.look.CgVfxSlot;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;

/**
 * A grid of cells an effect owns, riding with it, four channels a cell (x, y, z, w), on the GPU: what particles write
 * into and read back, and what a layer draws as a volume. Niagara's Grid3D, with two kinds of channel:
 * <ul>
 *   <li><b>A trail</b> ({@link #channel(int, float, float)}): particles splat amounts into it each step
 *       ({@code CgVfxModule.Splat}), it fades and spreads to its neighbours every frame, and particles steer by it
 *       ({@code CgVfxModule.Follow}, {@code Gradient}). What particles leave behind.</li>
 *   <li><b>Rebuilt</b> ({@link #channel(int)}): cleared every frame and filled from the particles that rasterize into it
 *       ({@code CgVfxEmitter.Builder.rasterize}), each a soft ball where it is drawn this frame. A body made of
 *       particles, moving exactly as they are drawn: Niagara's rasterization grid.</li>
 * </ul>
 * It is sparse, as the gas is: it holds cells only where something is, so it reaches as far as its particles go. Its
 * layers draw a channel as lit smoke ({@link #SMOKE}) or as glowing light ({@link #GLOW}).
 *
 * <pre>{@code
 * public final class SlimeMould extends CgVfxEffect<SlimeMould> {
 *     private final CgVfxGrid3D trail = new CgVfxGrid3D(this).place(0f, 3f, 0f)
 *             .channel(0, 1.75f, 0.125f).lit(false);        // channel x: fades 1.75 a second, spreads 0.125 blocks² a second
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
 * // agents splat into channel x and steer toward where it is strongest, inside a sphere: Physarum's trails, Jones's
 * // ratios at 0.15-block cells (sensors 6 cells out, 45 degrees, a domain ten sensor reaches across)
 * CgVfxEmitter.builder("agents").state(CgVfxEmitterState.ONCE).capacity(20000).burst(0f, 20000)
 *         .shape(CgSphere.of(4)).speed(2.5f, 2.5f).life(1e6f, 1e6f)
 *         .module(new CgVfxModule.Follow(0, 0.9f, 0.785f, 13f, 1.5f))
 *         .module(new CgVfxModule.Collide(CgSphere.of(4.5), 1f, 0f, 0f).container())
 *         .module(new CgVfxModule.Splat(1f, 0f, 0f, 0f)).build();
 * CgVfxLook.builder(SCHEMA).with(CgVfxGrid3D.GLOW).set(CgVfxGrid3D.CELL, 0.15f)...
 * }</pre>
 *
 * <pre>{@code
 * // particles as one body of smoke, lit through itself: channel x rebuilt each frame from soft balls where they are drawn
 * CgVfxEmitter.builder("smoke").capacity(6000).rate(1500f).size(0.6f, 0.6f)...rasterize(0.3f, 0f, 0f, 0f).build();
 * new CgVfxGrid3D(this).place(0f, 4f, 0f).channel(0);                   // and the look takes CgVfxGrid3D.SMOKE
 * }</pre>
 *
 * <ul>
 *   <li>Its cells are {@link #CELL} blocks, wider at lower tiers ({@link CgVfxGridAtlas#cellScale}), reaching
 *       {@code CgVfxGridAtlas.SPAN / 2} cells each way from its place, which moves with the effect. Its tiles come from
 *       a pool every grid shares ({@link CgVfxGridAtlas#tiles}). A grid whose splats keep landing past its share coarsens: its cells
 *       twice as wide, up to {@link CgVfxGridAtlas#MAX_LEVEL} times ({@link #level}), so it reaches further, softer,
 *       and past that what it holds fades out toward the tiles it lacks rather than stopping at an edge.</li>
 *   <li>A trail fades by its share a second and spreads to its neighbours (blocks² a second). A channel never set
 *       keeps all it is given.</li>
 *   <li>Splats land a frame's particles' worth at a time, each spread over the eight cells about it by distance; a
 *       rasterized particle covers the cells within its size. One landing where the grid has no tile yet asks for it,
 *       and is lost until it arrives, two frames on; the tiles round each instance writing it are asked for ahead of
 *       it, and {@link #wantTiles} asks for more.</li>
 *   <li>Channel x is what {@link #SMOKE} draws and what the grid lights itself by ({@link #EXTINCTION}); {@link #lit}
 *       false skips that light for a grid drawn as glow, or not drawn at all.</li>
 *   <li>Call {@link #tick} every effect tick; one left unticked gives its place back. At most
 *       {@link CgVfxGridAtlas#CAPACITY} grids at once; one waiting draws and takes nothing.</li>
 * </ul>
 */
public final class CgVfxGrid3D extends CgVfxElement {

    public static final CgVfxSchema SCHEMA = CgVfxSchema.of("crystalgraphics:grid");
    /** A cell's side in blocks at High and Ultra; taken when it takes its place in the atlas. */
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

    /** The most spheres {@link #wantTiles} keeps between two frames: the latest. */
    public static final int MAX_WANTED = 8;

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
    /** Each channel's share lost a second, and blocks² spread a second; whether it is rebuilt each frame instead. */
    final float[] fade = new float[4], spread = new float[4];
    final boolean[] rebuilt = new boolean[4];
    private boolean lit = true;
    private float placeX, placeY, placeZ;
    /** Its place in the atlas, -1 for none; a cell in blocks there, and how many times it coarsened; its middle in the world. */
    int index = -1;
    float cell;
    int level;
    double worldX, worldY, worldZ;
    /** Seconds ticked that no frame has stepped yet; the atlas frame of its last tick. */
    float owed;
    long ticked;
    /** The box round its tiles, from its middle in blocks, as of the atlas's last frame; whether it holds any. */
    private final float[] box = new float[6];
    private boolean boxed;
    /** The latest spheres asked for ({@link #wantTiles}), absolute, four doubles each; how many and where the next goes. */
    private final double[] wanted = new double[MAX_WANTED * 4];
    private int wantedCount, wantedNext;

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
     * Channel {@code c} (0 to 3: x, y, z, w) as a trail: {@code fade}, the share it loses a second, and {@code spread},
     * how far it diffuses, blocks² a second. Unless set, it keeps what it is given and stays put.
     */
    public CgVfxGrid3D channel(int c, float fade, float spread) {
        check(c);
        this.fade[c] = Math.max(0f, fade);
        this.spread[c] = Math.max(0f, spread);
        rebuilt[c] = false;
        return this;
    }

    /**
     * Channel {@code c} (0 to 3: x, y, z, w) rebuilt every frame: only what rasterizes into it this frame
     * ({@code CgVfxEmitter.Builder.rasterize}), where it is drawn. What {@code Splat} adds lasts one frame.
     */
    public CgVfxGrid3D channel(int c) {
        check(c);
        fade[c] = spread[c] = 0f;
        rebuilt[c] = true;
        return this;
    }

    private static void check(int c) {
        if (c < 0 || c > 3) throw new IllegalArgumentException("a grid's channel " + c + ": 0 to 3");
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

    /**
     * Asks for its tiles round the sphere {@code radius} blocks about {@code (x, y, z)}, absolute, at its next frame:
     * where particles are about to splat that its own instances do not cover.
     *
     * <pre>{@code
     * trail.wantTiles(handX, handY, handZ, 2f);   // each tick a swing is about to paint through it
     * }</pre>
     */
    public void wantTiles(double x, double y, double z, float radius) {
        int at = wantedNext * 4;
        wanted[at] = x;
        wanted[at + 1] = y;
        wanted[at + 2] = z;
        wanted[at + 3] = radius;
        wantedNext = (wantedNext + 1) % MAX_WANTED;
        wantedCount = Math.min(MAX_WANTED, wantedCount + 1);
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

    /** A cell's side in blocks in the atlas now, its coarsenings in; 0 before its first place. */
    public float cellSize() {
        return cell;
    }

    /** How many times it coarsened since it took its place: its cells {@code 2^level} times {@link #CELL}'s tier width. */
    public int level() {
        return level;
    }

    /** The box round the tiles it holds, from its middle in blocks, into {@code out}: false when it holds none. */
    public boolean bounds(float[] out) {
        if (!boxed) return false;
        System.arraycopy(box, 0, out, 0, 6);
        return true;
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

    float cellSetting() {
        return get(CELL);
    }

    /** The atlas set its box: null for no tile. */
    void box(float[] lohi) {
        boxed = lohi != null;
        if (boxed) System.arraycopy(lohi, 0, box, 0, 6);
    }

    /** Asks the atlas for the tiles round each sphere asked for since its last frame, its place {@code b}. */
    void wantTiles(CgVfxGridAtlas atlas, int b, int now) {
        for (int i = 0; i < wantedCount; i++) {
            int k = i * 4;
            atlas.wantSphere(b, (float) (wanted[k] - worldX), (float) (wanted[k + 1] - worldY), (float) (wanted[k + 2] - worldZ),
                    (float) wanted[k + 3], now);
        }
        wantedCount = 0;
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
        boxed = false;
    }
}
